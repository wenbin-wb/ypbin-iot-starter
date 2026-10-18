/*
 * Copyright (c) 2024-present ypbin-iot-starter authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package cn.ypbin.iot.spring.subscription;

import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.protocol.AddressExpansionExtension;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 订阅编排默认实现。
 *
 * <p>把订阅领域<b>全部已知的坑</b>一次性收进框架（issue #37），宿主只提供「点位 → 地址范围」映射：</p>
 *
 * <ol>
 *   <li><b>整批 vs 逐点位分流</b>：判据来自 {@link ProtocolDescriptor#subscriptionAddressing()} 的
 *       <b>协议声明</b>，不再由宿主按协议码 + {@code payload-format} 字符串硬编码；</li>
 *   <li><b>多寄存器地址展开</b>：点位占用多个寄存器时，兄弟地址由协议声明的
 *       {@link AddressExpansionExtension} 计算（{@code base+1..base+N-1}），框架不猜地址语法；</li>
 *   <li><b>在途去重</b>：同设备同会话的并发订阅合并为一次真实调用，慢订阅不会让下一轮重复发起；</li>
 *   <li><b>部分成功只补失败地址</b>：成功地址记入设备状态，下一轮只发失败地址——
 *       绝不给已成功地址重复挂监听（会导致同一点位重复读数）；</li>
 *   <li><b>按「设备 + 会话实例」退避</b>：会话实例换了（重连后框架新建会话）即视为新上下文，
 *       退避立即作废并重订，否则表现为「链路恢复了但数据不再上报」；</li>
 *   <li><b>绝不静默</b>：每个失败地址带消息键与原因，退避与跳过都有可区分的状态与日志。</li>
 * </ol>
 *
 * <p><b>线程安全</b>：设备状态用 {@link ReentrantLock} 保护（自研代码禁用 {@code synchronized}，
 * 见 AGENTS 4.5）；协议调用在锁外发起，绝不在锁内做 IO 等待。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public final class DefaultSubscriptionOrchestrator implements SubscriptionOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(DefaultSubscriptionOrchestrator.class);

    /** 单次调用的兜底结果（{@code allOf} 保证调用已完成，理论上不会用到空值）。 */
    private static final CallOutcome FALLBACK_OUTCOME = new CallOutcome(List.of(), List.of());

    private final ProtocolDescriptorSource descriptors;

    private final SubscriptionBackoffPolicy backoffPolicy;

    private final Clock clock;

    /** 设备级订阅状态（会话实例、已成功地址、退避）。 */
    private final Map<String, DeviceState> states = new ConcurrentHashMap<>();

    /** 未注册协议只 WARN 一次，避免每轮每设备刷屏。 */
    private final Set<String> warnedProtocols = ConcurrentHashMap.newKeySet();

    /** 累计发起的协议层订阅调用次数（观测入口，供宿主与测试核对「只发一次」语义）。 */
    private final AtomicLong dispatchedCalls = new AtomicLong();

    /**
     * 创建编排器。
     *
     * @param descriptors   协议描述符来源
     * @param backoffPolicy 退避参数
     * @param clock         时钟（可注入以便测试退避窗口）
     */
    public DefaultSubscriptionOrchestrator(ProtocolDescriptorSource descriptors,
            SubscriptionBackoffPolicy backoffPolicy, Clock clock) {
        this.descriptors = Objects.requireNonNull(descriptors, "descriptors must not be null");
        this.backoffPolicy = backoffPolicy == null ? SubscriptionBackoffPolicy.defaults() : backoffPolicy;
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    /**
     * 以默认退避参数创建编排器。
     *
     * @param descriptors 协议描述符来源
     * @param clock       时钟
     */
    public DefaultSubscriptionOrchestrator(ProtocolDescriptorSource descriptors, Clock clock) {
        this(descriptors, SubscriptionBackoffPolicy.defaults(), clock);
    }

    @Override
    public CompletionStage<SubscriptionResult> subscribe(SubscriptionBatchItem item) {
        Objects.requireNonNull(item, "item must not be null");
        String deviceId = item.device().deviceId();
        if (item.points().isEmpty()) {
            log.debug("[ypbin-iot] device {} has no subscription point; nothing to subscribe.", deviceId);
            return CompletableFuture.completedFuture(
                    SubscriptionResult.skipped(deviceId, SubscriptionStatus.NO_ADDRESSES));
        }
        DeviceState state = states.computeIfAbsent(deviceId, ignored -> new DeviceState());
        Expansion expansion = expand(item);
        Round round = beginRound(state, item, expansion);
        SubscriptionResult completed = round.result();
        if (completed != null) {
            return CompletableFuture.completedFuture(completed);
        }
        CompletableFuture<SubscriptionResult> pending = round.pending();
        if (pending == null) {
            // 不可达：beginRound 只会返回「已完成」或「待发起」两种形态之一
            throw new IllegalStateException("subscription round for device " + deviceId
                    + " has neither a result nor a pending future");
        }
        try {
            dispatch(state, item, round.addresses(), expansion.failures(), pending);
        } catch (RuntimeException ex) {
            // 防御：dispatch 内部已收敛同步异常；走到这里说明编排自身有缺陷，不能让 future 悬空
            log.error("[ypbin-iot] subscription orchestration failed for device {}", deviceId, ex);
            finishRound(state, deviceId, new CallOutcome(List.of(), List.of()), expansion.failures(), pending);
        }
        return pending;
    }

    /**
     * 开启一轮订阅：在锁内完成会话判定、在途去重、退避判断与待发地址计算。
     *
     * <p>返回两类结果之一：① 本轮无需发起（已完成的结果，如已订阅/退避/无地址）；
     * ② 需要发起（待发地址 + 待完成的 future）。锁在方法返回前释放，
     * 协议调用一律在锁外发起——绝不在锁内做 IO 等待。</p>
     */
    private Round beginRound(DeviceState state, SubscriptionBatchItem item, Expansion expansion) {
        String deviceId = item.device().deviceId();
        state.lock.lock();
        try {
            resetIfSessionChanged(state, item.session());
            CompletableFuture<SubscriptionResult> inFlight = state.pending;
            if (inFlight != null) {
                log.debug("[ypbin-iot] subscription already in flight for device {}; joining the pending round.",
                        deviceId);
                return Round.inFlight(inFlight);
            }
            Instant retryAt = state.nextRetryAt;
            if (retryAt != null && clock.instant().isBefore(retryAt)) {
                log.debug("[ypbin-iot] device {} subscription in backoff until {}; skipping this round.",
                        deviceId, retryAt);
                return Round.completed(new SubscriptionResult(deviceId, SubscriptionStatus.SKIPPED_BACKOFF,
                        new SubscriptionOutcome(Set.of(), List.of()), retryAt));
            }
            // 只补「未成功」的地址：部分成功后绝不重发已成功地址（否则同一点位重复读数）
            List<PointAddress> pending = new ArrayList<>();
            for (PointAddress address : expansion.addresses()) {
                if (!state.succeeded.contains(address.raw())) {
                    pending.add(address);
                }
            }
            if (pending.isEmpty() && expansion.failures().isEmpty()) {
                log.debug("[ypbin-iot] device {} already fully subscribed on session {}.",
                        deviceId, item.session().sessionId());
                state.nextRetryAt = null;
                state.attempt = 0;
                return Round.completed(new SubscriptionResult(deviceId, SubscriptionStatus.ALREADY_SUBSCRIBED,
                        new SubscriptionOutcome(state.succeededAddresses(), List.of()), null));
            }
            CompletableFuture<SubscriptionResult> pendingRound = new CompletableFuture<>();
            state.pending = pendingRound;
            return Round.dispatch(pending, pendingRound);
        } finally {
            state.lock.unlock();
        }
    }

    @Override
    public void forget(String deviceId) {
        if (deviceId == null) {
            return;
        }
        DeviceState removed = states.remove(deviceId);
        if (removed != null) {
            log.debug("[ypbin-iot] subscription tracking for device {} dropped.", deviceId);
        }
    }

    /**
     * 当前在退避中的设备数（诊断用）。
     *
     * @return 设备数
     */
    public int backoffDeviceCount() {
        int count = 0;
        for (DeviceState state : states.values()) {
            if (state.nextRetryAt != null) {
                count++;
            }
        }
        return count;
    }

    /**
     * 累计发起的协议层订阅调用次数（诊断与测试用）。
     *
     * @return 调用次数
     */
    public long dispatchedCalls() {
        return dispatchedCalls.get();
    }

    /**
     * 已跟蹬的会话数（诊断用）。
     *
     * @return 设备数
     */
    public int trackedDeviceCount() {
        return states.size();
    }

    // ------------------------------------------------------------------
    // 展开与分流
    // ------------------------------------------------------------------

    /**
     * 把点位映射展开为「待订阅地址 + 展开失败项」。
     *
     * <p>展开规则来自协议声明的 {@link AddressExpansionExtension}；协议未声明时，
     * 需要展开的点位<b>显式失败</b>（绝不静默只订基址——那会让多寄存器解码拿到半截字节）。
     * 同一 {@code raw} 地址只保留一次（key-value 与 Modbus 相邻点位都会产出重叠地址）。</p>
     */
    private Expansion expand(SubscriptionBatchItem item) {
        Map<String, PointAddress> addresses = new LinkedHashMap<>();
        List<SubscriptionFailure> failures = new ArrayList<>();
        Optional<AddressExpansionExtension> expansionExtension =
                item.session().unwrap(AddressExpansionExtension.class);
        for (SubscriptionPoint point : item.points()) {
            List<PointAddress> pointAddresses = new ArrayList<>();
            pointAddresses.add(point.address());
            boolean complete = true;
            for (int delta = 1; delta < point.wordCount(); delta++) {
                if (expansionExtension.isEmpty()) {
                    // 协议没声明地址算术：整个点位失败，且**不订阅基址**——
                    // 只订基址会让多寄存器解码拿到半截字节却仍报 GOOD，比直接失败更难排查
                    complete = false;
                    failures.add(new SubscriptionFailure(point.address(),
                            IotMessageKeys.SUBSCRIPTION_ADDRESS_EXPANSION_UNSUPPORTED,
                            "wordCount=" + point.wordCount() + ", protocol="
                                    + item.device().protocol().value()));
                    break;
                }
                try {
                    pointAddresses.add(expansionExtension.get().offset(point.address(), delta));
                } catch (RuntimeException ex) {
                    // 单个兄弟地址越界同样让整个点位失败：缺寄存器与「没订上」等价
                    complete = false;
                    failures.add(SubscriptionFailure.of(point.address(), ex));
                    break;
                }
            }
            if (!complete) {
                continue;
            }
            for (PointAddress address : pointAddresses) {
                addresses.putIfAbsent(address.raw(), address);
            }
        }
        return new Expansion(List.copyOf(addresses.values()), List.copyOf(failures));
    }

    /**
     * 判定该设备走整批还是逐点位单订阅。
     *
     * <p>未注册协议按 {@link SubscriptionAddressing#DEFAULT} 处理并 WARN 一次：
     * 「默认整批」的失败模式是静默数据缺失，「默认逐点位」只是效率损失，两者不对称。</p>
     */
    private SubscriptionAddressing addressing(DeviceSpec device) {
        Optional<ProtocolDescriptor> descriptor = descriptors.find(device);
        if (descriptor.isEmpty()) {
            if (warnedProtocols.add(device.protocol().value())) {
                log.warn("[ypbin-iot] no ProtocolDescriptor registered for protocol {}; subscription falls back "
                                + "to {} (one address per request). Register the protocol adapter, or declare "
                                + "ProtocolDescriptor.subscriptionAddressing to get batch subscription.",
                        device.protocol().value(), SubscriptionAddressing.DEFAULT);
            }
            return SubscriptionAddressing.DEFAULT;
        }
        return descriptor.get().subscriptionAddressing();
    }

    // ------------------------------------------------------------------
    // 发起订阅
    // ------------------------------------------------------------------

    /**
     * 发起本轮订阅并在完成后更新设备状态。
     *
     * <p>方法返回时协议调用已全部发起（可能是异步完成的）；<b>不阻塞调用线程</b>。</p>
     */
    private void dispatch(DeviceState state, SubscriptionBatchItem item, List<PointAddress> pending,
            List<SubscriptionFailure> expansionFailures, CompletableFuture<SubscriptionResult> round) {
        String deviceId = item.device().deviceId();
        SubscriptionAddressing addressing = addressing(item.device());
        if (pending.isEmpty()) {
            // 只有展开失败（没有任何地址可发）：不进协议层，直接按失败结算
            finishRound(state, deviceId, new CallOutcome(List.of(), List.of()), expansionFailures, round);
            return;
        }
        List<CompletableFuture<CallOutcome>> calls = new ArrayList<>();
        try {
            if (addressing == SubscriptionAddressing.PER_ADDRESS) {
                for (PointAddress address : pending) {
                    calls.add(callOnce(item.session(), List.of(address), item, "per-address"));
                }
            } else {
                calls.add(callOnce(item.session(), pending, item, "batch"));
            }
        } catch (RuntimeException ex) {
            // 防御：callOnce 自身已捕获同步异常，这里只兜「调度前」的编程错误
            log.error("[ypbin-iot] failed to dispatch subscription for device {}", deviceId, ex);
            if (!round.isDone()) {
                finishRound(state, deviceId, new CallOutcome(List.of(), List.of()), expansionFailures, round);
            }
            return;
        }
        log.debug("[ypbin-iot] device {} subscription dispatched ({} mode, {} address(es), {} call(s)).",
                deviceId, addressing, pending.size(), calls.size());
        CompletableFuture.allOf(calls.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
            List<PointAddress> succeeded = new ArrayList<>();
            List<SubscriptionFailure> failures = new ArrayList<>(expansionFailures);
            for (CompletableFuture<CallOutcome> call : calls) {
                CallOutcome outcome = call.getNow(FALLBACK_OUTCOME);
                succeeded.addAll(outcome.succeeded());
                failures.addAll(outcome.failures());
            }
            if (error != null) {
                // allOf 只会在 callOnce 自身异常完成时触发（正常路径都归一化为 CallOutcome）
                log.error("[ypbin-iot] subscription round for device {} failed unexpectedly", deviceId, error);
            }
            finishRound(state, deviceId, new CallOutcome(succeeded, failures), List.of(), round);
        });
    }

    /**
     * 发起一次（一组地址的）订阅调用，并把结果归一化为 {@link CallOutcome}。
     *
     * <p>同步抛异常（适配器契约并不禁止）、Stage 为 null、异步异常完成三种路径都要收敛成
     * 「逐地址失败」，否则整轮 result 会永远不完成。</p>
     */
    private CompletableFuture<CallOutcome> callOnce(DeviceSession session, List<PointAddress> addresses,
            SubscriptionBatchItem item, String mode) {
        dispatchedCalls.incrementAndGet();
        CompletableFuture<CallOutcome> result = new CompletableFuture<>();
        CompletionStage<SubscriptionHandle> stage;
        try {
            stage = session.subscribe(requestFor(item.device(), addresses), item.listener());
        } catch (RuntimeException ex) {
            log.error("[ypbin-iot] subscription call threw synchronously for device {} ({} mode, {} address(es))",
                    item.device().deviceId(), mode, addresses.size(), ex);
            result.complete(failureOutcome(addresses, ex));
            return result;
        }
        if (stage == null) {
            IllegalStateException nullStage = new IllegalStateException(
                    "DeviceSession.subscribe returned null CompletionStage");
            log.error("[ypbin-iot] subscription call returned null stage for device {} ({} mode)",
                    item.device().deviceId(), mode, nullStage);
            result.complete(failureOutcome(addresses, nullStage));
            return result;
        }
        stage.whenComplete((handle, error) -> {
            if (error != null) {
                log.error("[ypbin-iot] subscription failed for device {} ({} mode, {} address(es))",
                        item.device().deviceId(), mode, addresses.size(), error);
                result.complete(failureOutcome(addresses, error));
                return;
            }
            result.complete(new CallOutcome(addresses, List.of()));
        });
        return result;
    }

    /**
     * 汇总一轮结果并更新设备状态。
     *
     * <p>退避按<b>轮</b>计数而不是按地址：一设备一轮 N 个地址一起失败时，若每个地址各记一次
     * 退避，第一轮就会直接把 attempts 冲到上限（宿主的 639 行实现踩过，这里保留同一口径）。</p>
     */
    private void finishRound(DeviceState state, String deviceId, CallOutcome outcome,
            List<SubscriptionFailure> extraFailures, CompletableFuture<SubscriptionResult> round) {
        List<PointAddress> succeeded = new ArrayList<>(outcome.succeeded());
        List<SubscriptionFailure> failures = new ArrayList<>(outcome.failures());
        failures.addAll(extraFailures);
        SubscriptionResult result;
        state.lock.lock();
        try {
            for (PointAddress address : succeeded) {
                state.succeeded.add(address.raw());
            }
            if (failures.isEmpty()) {
                state.attempt = 0;
                state.nextRetryAt = null;
                result = SubscriptionResult.of(deviceId, SubscriptionStatus.SUBSCRIBED,
                        new SubscriptionOutcome(new LinkedHashSet<>(succeeded), List.of()));
                log.info("[ypbin-iot] subscription established for device {} ({} address(es)).",
                        deviceId, succeeded.size());
            } else {
                state.attempt++;
                Instant nextRetryAt = clock.instant().plus(backoffPolicy.delayFor(state.attempt));
                state.nextRetryAt = nextRetryAt;
                SubscriptionStatus status = succeeded.isEmpty()
                        ? SubscriptionStatus.FAILED : SubscriptionStatus.PARTIAL;
                result = new SubscriptionResult(deviceId, status,
                        new SubscriptionOutcome(new LinkedHashSet<>(succeeded), failures), nextRetryAt);
                log.error("[ypbin-iot] subscription {}/{} for device {} failed (attempt {}); retry in {}. "
                                + "failed addresses: {}",
                        failures.size(), failures.size() + succeeded.size(), deviceId, state.attempt,
                        backoffPolicy.delayFor(state.attempt),
                        failures.stream().map(failure -> failure.address().raw()
                                + "=" + failure.reasonKey()).toList());
            }
            state.pending = null;
        } finally {
            state.lock.unlock();
        }
        round.complete(result);
    }

    private static SubscribeRequest requestFor(DeviceSpec device, List<PointAddress> addresses) {
        if (device.pollInterval().isZero()) {
            return SubscribeRequest.of(addresses);
        }
        return new SubscribeRequest(addresses, device.pollInterval(), device.pollInterval(), null, Map.of());
    }

    private static CallOutcome failureOutcome(List<PointAddress> addresses, Throwable error) {
        List<SubscriptionFailure> failures = new ArrayList<>(addresses.size());
        for (PointAddress address : addresses) {
            failures.add(SubscriptionFailure.of(address, error));
        }
        return new CallOutcome(List.of(), failures);
    }

    /**
     * 会话实例变化时重置订阅跟踪。
     *
     * <p>用<b>对象身份</b>而不是 {@code sessionId()}：框架重连会新建会话实例，而协议模块的
     * {@code sessionId} 由「设备 + 链路 + 从站」拼成，重连后字符串可能完全相同——
     * 用字符串判重会把「新会话」误认成「老会话」，表现为链路恢复但数据永久停更。</p>
     */
    private void resetIfSessionChanged(DeviceState state, DeviceSession session) {
        if (state.session == null) {
            state.session = session;
            return;
        }
        if (state.session == session) {
            return;
        }
        log.debug("[ypbin-iot] session instance changed for device {} ({} -> {}); re-subscribing from scratch.",
                session.device().deviceId(), state.session.sessionId(), session.sessionId());
        state.session = session;
        state.succeeded.clear();
        state.attempt = 0;
        state.nextRetryAt = null;
        // 上一会话的在途轮次不会被复用：它的结果属于旧会话，不能算到新会话头上
        state.pending = null;
    }

    /**
     * 一轮订阅的启用结果：要么已有现成结果，要么带一批待发地址与待完成的 future。
     *
     * @param addresses 本轮待发起订阅的地址（已去掉此前成功的地址）
     * @param pending   本轮 future（需要发起时非空）
     * @param result    无需发起时已有的结果（需要发起时为 {@code null}）
     * @author wenbin
     * @since 2026-10-18
     */
    private record Round(List<PointAddress> addresses,
            @Nullable CompletableFuture<SubscriptionResult> pending,
            @Nullable SubscriptionResult result) {

        private Round {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
        }

        private static Round dispatch(List<PointAddress> addresses,
                CompletableFuture<SubscriptionResult> pending) {
            return new Round(addresses, pending, null);
        }

        private static Round inFlight(CompletableFuture<SubscriptionResult> pending) {
            return new Round(List.of(), pending, null);
        }

        private static Round completed(SubscriptionResult result) {
            return new Round(List.of(), null, result);
        }
    }

    /**
     * 单次协议层调用（一组地址）的归一化结果。
     *
     * @param succeeded 本次调用成功的地址
     * @param failures  本次调用失败的地址与原因
     * @author wenbin
     * @since 2026-10-18
     */
    private record CallOutcome(List<PointAddress> succeeded, List<SubscriptionFailure> failures) {

        private CallOutcome {
            succeeded = succeeded == null ? List.of() : List.copyOf(succeeded);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    /**
     * 展开结果：可发起的地址 + 展开失败项。
     *
     * @param addresses 去重后的地址
     * @param failures  展开失败的地址与原因
     * @author wenbin
     * @since 2026-10-18
     */
    private record Expansion(List<PointAddress> addresses, List<SubscriptionFailure> failures) {

        private Expansion {
            addresses = addresses == null ? List.of() : List.copyOf(addresses);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    /**
     * 单设备订阅状态。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class DeviceState {

        private final ReentrantLock lock = new ReentrantLock();

        /** 当前跟踪的会话实例（身份比较，见 {@link #resetIfSessionChanged}）。 */
        private volatile @Nullable DeviceSession session;

        /** 当前会话上已成功订阅的地址（raw）。 */
        private final Set<String> succeeded = new LinkedHashSet<>();

        /** 在途轮次；非空表示同设备同会话已有订阅在飞。 */
        private volatile @Nullable CompletableFuture<SubscriptionResult> pending;

        /** 连续失败轮次。 */
        private int attempt;

        /** 下次允许发起订阅的时刻；{@code null} 表示不在退避窗口内。 */
        private volatile @Nullable Instant nextRetryAt;

        private Set<PointAddress> succeededAddresses() {
            Set<PointAddress> addresses = new LinkedHashSet<>();
            for (String raw : succeeded) {
                addresses.add(PointAddress.of(raw));
            }
            return addresses;
        }
    }
}
