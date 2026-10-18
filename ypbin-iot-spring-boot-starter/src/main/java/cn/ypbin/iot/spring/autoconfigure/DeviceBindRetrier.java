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
package cn.ypbin.iot.spring.autoconfigure;

import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.util.ExponentialBackoff;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 设备级 bind 退避重试（issue #38）。
 *
 * <p><b>为什么需要它</b>：链路级重连（{@link ConnectionReconnector}）只负责「已绑定的链路意外断开」；
 * 而 <b>bind 本身失败</b>（设备离线、链路尚未就绪、探测不可达、acquire 超时）此前框架<b>完全不重试</b>：
 * 变更事件处理完就结束，设备永久离线——日志里只有一行 WARN，直到宿主下一次推送变更。
 * 宿主因此被迫自己写一套 {@code REBIND_*} 退避（在业务仓里已经出现），
 * 而这条与「设备有没有数据」直接相关的语义不该由宿主重复实现。</p>
 *
 * <p><b>语义（三条，与宿主历史常量对齐）</b>：</p>
 * <ol>
 *   <li><b>按设备独立指数退避</b>：从 {@code ypbin.iot.devices.rebind-backoff-initial} 起、
 *       封顶 {@code rebind-backoff-max}，由 {@link ExponentialBackoff} 计算（纯函数，边界已测）；</li>
 *   <li><b>每轮有上限</b>：一轮扫描最多重试 {@code rebind-max-per-cycle} 台设备，
 *       避免「整批设备离线」时同时打爆对端；同一轮内按「最早到期」排序，保证
 *       长期排不上的设备不会饿死（宿主侧曾因按列表顺序取配额导致第 21 台起永久零数据）；</li>
 *   <li><b>耗尽必须可见</b>：连续失败达到 {@code rebind-max-attempts} 后记 <b>WARN 带完整堆栈</b>，
 *       并通过 {@code ExhaustedAction} 交给生命周期通知宿主（{@code DeviceEventListener}），
 *       之后不再重试（避免无限重试垃圾日志），直到设备再次变更。</li>
 * </ol>
 *
 * <p><b>为什么扫描与 bind 不能在 timer 线程上跑</b>：{@link TaskScheduler#scheduleOnce} 跑在
 * 调度器的<b>单线程 timer</b> 上，而 bind 内部是带超时的阻塞 {@code join}（acquire + bind + probe）。
 * 直接在 timer 上执行会阻塞同 JVM 的全部定时任务（轮询订阅、空闲回收、保活探测），
 * 因此与 {@code ConnectionReconnector} 一样把实际尝试卸载到平台线程池。</p>
 *
 * <p>线程安全：内部状态用 {@link ConcurrentHashMap} + {@link ReentrantLock} 保护，
 * 不使用 {@code synchronized}（arch 规则禁止：虚拟线程 pinning）。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
final class DeviceBindRetrier implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DeviceBindRetrier.class);

    /**
     * 一次 bind 尝试的结果。
     *
     * @param success   是否成功
     * @param cause     失败原因；成功时为 {@code null}
     * @param retryable 是否值得重试（配置非法、无适配器等永久失败不重试）
     * @author wenbin
     * @since 2026-10-18
     */
    record Outcome(boolean success, @Nullable Throwable cause, boolean retryable) {

        /** 成功结果。 */
        private static final Outcome OK = new Outcome(true, null, false);

        /**
         * 成功。
         *
         * @return 结果
         */
        static Outcome ok() {
            return OK;
        }

        /**
         * 可重试的失败。
         *
         * @param cause 失败原因
         * @return 结果
         */
        static Outcome retryable(@Nullable Throwable cause) {
            return new Outcome(false, cause, true);
        }

        /**
         * 不可重试的失败（配置非法 / 无适配器 / 生命周期已关闭）。
         *
         * @param cause 失败原因
         * @return 结果
         */
        static Outcome permanent(@Nullable Throwable cause) {
            return new Outcome(false, cause, false);
        }
    }

    /**
     * 执行一次 bind 尝试。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    @FunctionalInterface
    interface BindAction {

        /**
         * 尝试绑定设备。
         *
         * @param device 设备规格
         * @return 尝试结果
         */
        Outcome attempt(DeviceSpec device);
    }

    /**
     * 重试耗尽时的回调（由生命周期记 WARN 并通知宿主）。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    @FunctionalInterface
    interface ExhaustedAction {

        /**
         * 处理一次「重试耗尽」。
         *
         * @param device   设备规格
         * @param attempts 实际失败的尝试次数
         * @param cause    最后一次失败原因；可能为空
         */
        void onExhausted(DeviceSpec device, int attempts, @Nullable Throwable cause);
    }

    private final TaskScheduler scheduler;

    private final BindAction bindAction;

    private final ExhaustedAction exhaustedAction;

    private final Duration initialDelay;

    private final Duration maxDelay;

    private final int maxPerCycle;

    private final int maxAttempts;

    /** 待重试设备（deviceId → 状态）。 */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicInteger totalAttempts = new AtomicInteger();

    private final AtomicInteger totalRecovered = new AtomicInteger();

    private final AtomicInteger totalExhausted = new AtomicInteger();

    private final AtomicInteger sweepCount = new AtomicInteger();

    /** 单轮最多尝试数（诊断/测试用：断言「每轮不超上限」）。 */
    private final AtomicInteger maxAttemptsPerSweep = new AtomicInteger();

    private final AtomicLong registrationSequence = new AtomicLong();

    private final ReentrantLock sweepLock = new ReentrantLock();

    private TaskScheduler.@Nullable ScheduledTask sweepTask;

    private boolean sweepScheduled;

    /**
     * 创建重试器。
     *
     * @param scheduler       调度门面（生产实现即 {@code TaskScheduler}）
     * @param settings        设备级配置（已归一化）
     * @param bindAction      bind 动作
     * @param exhaustedAction 耗尽回调
     */
    DeviceBindRetrier(TaskScheduler scheduler, IotProperties.DeviceProperties settings,
            BindAction bindAction, ExhaustedAction exhaustedAction) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.bindAction = Objects.requireNonNull(bindAction, "bindAction must not be null");
        this.exhaustedAction = Objects.requireNonNull(exhaustedAction, "exhaustedAction must not be null");
        IotProperties.DeviceProperties effective = Objects.requireNonNull(settings, "settings must not be null");
        this.initialDelay = effective.rebindBackoffInitial();
        this.maxDelay = effective.rebindBackoffMax();
        this.maxPerCycle = effective.rebindMaxPerCycle();
        this.maxAttempts = effective.rebindMaxAttempts();
    }

    /**
     * 把一台设备加入重试队列（由生命周期在 bind 失败后调用）。
     *
     * <p>幂等：同一设备已在队列中时只保留一份状态，不重置已有退避进度
     * （否则宿主频繁重发 ADD 会把退避永久打回起点，形成「越推越快」的重试风暴）。</p>
     *
     * @param device 设备规格
     */
    void schedule(DeviceSpec device) {
        Objects.requireNonNull(device, "device must not be null");
        if (closed.get()) {
            return;
        }
        Pending created = new Pending(device, registrationSequence.incrementAndGet());
        Pending existing = pending.putIfAbsent(device.deviceId(), created);
        if (existing != null) {
            log.debug("[ypbin-iot] device {} is already queued for bind retry; keeping existing backoff.",
                    device.deviceId());
            return;
        }
        Duration delay = ExponentialBackoff.nextDelay(1, initialDelay, maxDelay);
        created.nextRetryNanos = System.nanoTime() + delay.toNanos();
        log.warn("[ypbin-iot] device {} bind failed; scheduling retry in {} (max {} attempt(s), "
                + "up to {} device(s) per cycle).", device.deviceId(), delay, maxAttempts, maxPerCycle);
        ensureSweepScheduled();
    }

    /**
     * 取消一台设备的待重试（设备被移除 / 显式解绑 / 重试成功时调用）。
     *
     * @param deviceId 设备标识
     * @return 此前确实在队列中则返回 {@code true}
     */
    boolean cancel(String deviceId) {
        return pending.remove(deviceId) != null;
    }

    /**
     * 当前待重试的设备数（诊断用）。
     *
     * @return 设备数
     */
    int pendingCount() {
        return pending.size();
    }

    /**
     * 累计 bind 重试次数。
     *
     * @return 次数
     */
    int totalAttempts() {
        return totalAttempts.get();
    }

    /**
     * 累计重试后成功恢复的设备数。
     *
     * @return 次数
     */
    int totalRecovered() {
        return totalRecovered.get();
    }

    /**
     * 累计「重试耗尽」的设备数。
     *
     * @return 次数
     */
    int totalExhausted() {
        return totalExhausted.get();
    }

    /**
     * 累计扫描轮次。
     *
     * @return 轮次
     */
    int sweepCount() {
        return sweepCount.get();
    }

    /**
     * 单轮尝试数历史最大值（断言「每轮不超过 maxPerCycle」）。
     *
     * @return 单轮最大尝试数
     */
    int maxAttemptsPerSweep() {
        return maxAttemptsPerSweep.get();
    }

    /**
     * 某台设备当前的剩余退避（诊断/测试用）。
     *
     * @param deviceId 设备标识
     * @return 剩余退避；不在队列中返回 {@code null}
     */
    @Nullable
    Duration pendingDelay(String deviceId) {
        Pending state = pending.get(deviceId);
        if (state == null) {
            return null;
        }
        long remaining = state.nextRetryNanos - System.nanoTime();
        return remaining <= 0 ? Duration.ZERO : Duration.ofNanos(remaining);
    }

    private void ensureSweepScheduled() {
        sweepLock.lock();
        try {
            if (closed.get() || sweepScheduled || pending.isEmpty()) {
                return;
            }
            long now = System.nanoTime();
            long earliest = Long.MAX_VALUE;
            for (Pending state : pending.values()) {
                earliest = Math.min(earliest, state.nextRetryNanos);
            }
            long delayNanos = Math.max(0L, earliest - now);
            sweepScheduled = true;
            sweepTask = scheduler.scheduleOnce(this::onSweep, Duration.ofNanos(delayNanos));
        } finally {
            sweepLock.unlock();
        }
    }

    /**
     * 一轮重试扫描的触发点：先摘掉「已排定」标记，再把实际尝试卸载到平台线程池。
     *
     * <p>卸载是硬要求：bind 内部是阻塞 {@code join}，而本方法跑在调度器单线程 timer 上。</p>
     */
    private void onSweep() {
        sweepLock.lock();
        try {
            sweepScheduled = false;
            sweepTask = null;
        } finally {
            sweepLock.unlock();
        }
        if (closed.get()) {
            return;
        }
        try {
            scheduler.platformThreadExecutor().execute(this::runSweep);
        } catch (RuntimeException ex) {
            // 平台池拒绝（停机中）：不能静默丢设备，记录完整堆栈并尝试在下一轮继续
            log.warn("[ypbin-iot] failed to dispatch a bind-retry sweep; will retry on the next cycle.", ex);
            ensureSweepScheduled();
        }
    }

    private void runSweep() {
        if (closed.get()) {
            return;
        }
        long now = System.nanoTime();
        List<Pending> due = new ArrayList<>();
        for (Pending state : pending.values()) {
            if (state.nextRetryNanos <= now) {
                due.add(state);
            }
        }
        // 公平性：按「最早到期」再按登记顺序，长期排不上的设备不会被饿死
        due.sort(Comparator.comparingLong((Pending state) -> state.nextRetryNanos)
                .thenComparingLong(state -> state.sequence));
        int attempted = 0;
        for (Pending state : due) {
            if (attempted >= maxPerCycle) {
                log.debug("[ypbin-iot] bind retry cycle limit ({}) reached; {} device(s) deferred to the next cycle.",
                        maxPerCycle, due.size() - attempted);
                break;
            }
            attempt(state);
            attempted++;
        }
        if (attempted > 0) {
            sweepCount.incrementAndGet();
            maxAttemptsPerSweep.accumulateAndGet(attempted, Math::max);
        }
        ensureSweepScheduled();
    }

    private void attempt(Pending state) {
        String deviceId = state.device.deviceId();
        // 扫描期间设备可能被移除/解绑：只对仍在队列里的状态执行
        if (pending.get(deviceId) != state) {
            return;
        }
        totalAttempts.incrementAndGet();
        Outcome outcome;
        try {
            outcome = bindAction.attempt(state.device);
        } catch (RuntimeException ex) {
            outcome = Outcome.retryable(ex);
        }
        if (outcome.success()) {
            pending.remove(deviceId, state);
            totalRecovered.incrementAndGet();
            log.info("[ypbin-iot] device {} bound successfully after {} retry attempt(s).",
                    deviceId, state.attempts + 1);
            return;
        }
        state.attempts++;
        state.lastCause = outcome.cause();
        if (!outcome.retryable()) {
            // 永久失败（配置非法 / 无适配器）：继续重试没有意义，立即放弃并如实记录
            pending.remove(deviceId, state);
            log.warn("[ypbin-iot] device {} bind retry aborted: failure is not retryable "
                    + "(configuration or adapter problem), giving up after {} attempt(s).",
                    deviceId, state.attempts, state.lastCause);
            return;
        }
        if (state.attempts >= maxAttempts) {
            pending.remove(deviceId, state);
            totalExhausted.incrementAndGet();
            log.warn("[ypbin-iot] device {} bind retry EXHAUSTED after {} attempt(s); the device stays "
                    + "OFFLINE and produces NO data until the next device change. Check endpoint, "
                    + "connection spec and network.", deviceId, state.attempts, state.lastCause);
            exhaustedAction.onExhausted(state.device, state.attempts, state.lastCause);
            return;
        }
        Duration delay = ExponentialBackoff.nextDelay(state.attempts + 1, initialDelay, maxDelay);
        state.nextRetryNanos = System.nanoTime() + delay.toNanos();
        log.debug("[ypbin-iot] device {} bind retry {} failed; next retry in {}.", deviceId, state.attempts, delay);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        sweepLock.lock();
        try {
            if (sweepTask != null) {
                sweepTask.cancel();
                sweepTask = null;
            }
            sweepScheduled = false;
        } finally {
            sweepLock.unlock();
        }
        int dropped = pending.size();
        pending.clear();
        if (dropped > 0) {
            log.debug("[ypbin-iot] bind retrier closed; {} pending device retry(ies) cancelled.", dropped);
        }
    }

    /**
     * 单台设备的退避状态。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class Pending {

        private final DeviceSpec device;

        private final long sequence;

        private int attempts;

        private long nextRetryNanos;

        private @Nullable Throwable lastCause;

        private Pending(DeviceSpec device, long sequence) {
            this.device = device;
            this.sequence = sequence;
        }
    }
}
