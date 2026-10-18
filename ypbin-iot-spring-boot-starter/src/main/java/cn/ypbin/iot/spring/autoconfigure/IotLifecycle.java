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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.AdapterSettings;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceEventType;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceEventListener;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.core.spi.ValidationResult;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * 接入生命周期编排：容器就绪后按设备清单建链绑定，容器关闭时优雅解绑。
 *
 * <p><b>启动期是唯一允许访问设备来源（可能落库）的时刻</b>：{@link DeviceRegistry#loadAll()}
 * 在此被调用一次并全量加载到内存，此后运行期只读内存。这是「接入路径零 DB 访问」约束的落地方式。</p>
 *
 * <p>单个设备失败<b>不</b>影响其余设备：失败被完整记录并计入启动报告。</p>
 *
 * <p><b>设备与链路分两个来源</b>：设备清单来自 {@link DeviceRegistry}（deviceId → protocol + localAddress），
 * 建链参数来自 {@link ConnectionSpecProvider}（connectionId → endpoint/timeout/TLS）。
 * 这一拆分是「连接 / 设备两级模型」的直接结果：一条链路可被 200 个从站共享。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class IotLifecycle implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IotLifecycle.class);

    /** 单次绑定/解绑的等待上限：没有超时的 join 会让容器启动或停机被一条挂死链路永久阻塞。 */
    private static final Duration BIND_TIMEOUT = Duration.ofSeconds(10);

    private final AdapterRegistry adapterRegistry;

    private final ConnectionRegistry connectionRegistry;

    private final List<DeviceRegistry> deviceRegistries;

    private final List<ConnectionSpecProvider> specProviders;

    private final IotProperties properties;

    /** 宿主设备事件监听器：bind 重试耗尽时据此通知宿主（issue #38）。 */
    private final List<DeviceEventListener> eventListeners;

    /** 时钟：事件时间戳统一取时（测试可注入固定时钟）。 */
    private final Clock clock;

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, ConnectionRegistry.ConnectionHandle> handles = new ConcurrentHashMap<>();

    /** 设备级排他锁：保证同一设备的变更不会产生「新旧会话双活」。 */
    private final Map<String, ReentrantLock> deviceLocks = new ConcurrentHashMap<>();

    /** 已应用的配置版本号，用于变更幂等（消息总线可能 at-least-once 重复投递）。 */
    private final Map<String, Long> appliedRevisions = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 重连退避调度（按 connectionId 独立）。 */
    private final ConnectionReconnector reconnector;

    /**
     * 设备级 bind 退避重试（issue #38）。
     *
     * <p>链路级重连只覆盖「已绑定链路断开」；本字段负责「bind 本身失败」的设备级重试，
     * 否则设备在配置变更后永久离线（宿主此前必须自己实现，见 {@link DeviceBindRetrier} 类注释）。</p>
     */
    private final DeviceBindRetrier bindRetrier;

    /** 设备当前生效的规格：重连时必须用同一份规格重新绑定。 */
    private final Map<String, DeviceSpec> deviceSpecs = new ConcurrentHashMap<>();

    /** 链路 → 该链路上的设备集合（1:N 链路重连时要一起恢复）。 */
    private final Map<String, Set<String>> devicesByConnection = new ConcurrentHashMap<>();

    /** 已挂上 whenClosed 监听的链路，避免同一链路重复挂监听导致重复重连。 */
    private final Set<String> watchedConnections = ConcurrentHashMap.newKeySet();

    /**
     * 创建生命周期编排器（不注入设备事件监听器）。
     *
     * <p>该重载为直接 {@code new} 的单元测试与旧调用方保留；Spring 装配走带
     * {@code listeners} 的重载，以便把「bind 重试耗尽」通知到宿主。</p>
     *
     * @param adapterRegistry    适配器注册中心
     * @param connectionRegistry 连接注册中心
     * @param deviceRegistries   设备来源实现
     * @param specProviders      链路规格来源实现
     * @param properties         配置
     * @param taskScheduler      调度器
     */
    public IotLifecycle(AdapterRegistry adapterRegistry, ConnectionRegistry connectionRegistry,
            List<DeviceRegistry> deviceRegistries, List<ConnectionSpecProvider> specProviders,
            IotProperties properties, TaskScheduler taskScheduler) {
        this(adapterRegistry, connectionRegistry, deviceRegistries, specProviders, properties,
                taskScheduler, List.of(), Clock.systemUTC());
    }

    /**
     * 创建生命周期编排器。
     *
     * @param adapterRegistry    适配器注册中心
     * @param connectionRegistry 连接注册中心
     * @param deviceRegistries   设备来源实现
     * @param specProviders      链路规格来源实现
     * @param properties         配置
     * @param taskScheduler      调度器
     * @param listeners          宿主设备事件监听器（bind 重试耗尽时通知）
     * @param clock              时钟（事件时间戳）
     */
    public IotLifecycle(AdapterRegistry adapterRegistry, ConnectionRegistry connectionRegistry,
            List<DeviceRegistry> deviceRegistries, List<ConnectionSpecProvider> specProviders,
            IotProperties properties, TaskScheduler taskScheduler,
            List<DeviceEventListener> listeners, Clock clock) {
        this.eventListeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.adapterRegistry = adapterRegistry;
        this.connectionRegistry = connectionRegistry;
        this.deviceRegistries = deviceRegistries == null ? List.of() : List.copyOf(deviceRegistries);
        this.specProviders = specProviders == null ? List.of() : List.copyOf(specProviders);
        this.properties = properties;
        TaskScheduler scheduler = Objects.requireNonNull(taskScheduler, "taskScheduler must not be null");
        this.reconnector = new ConnectionReconnector(scheduler, this::reconnect);
        this.bindRetrier = new DeviceBindRetrier(scheduler, this.properties.devices(),
                this::attemptBind, this::onBindRetryExhausted);
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.devices().isEnabled()) {
            log.debug("[ypbin-iot] device bootstrap disabled by configuration.");
            return;
        }
        if (deviceRegistries.isEmpty()) {
            log.debug("[ypbin-iot] no DeviceRegistry bean registered; nothing to bind.");
            return;
        }
        List<DeviceSpec> devices = new ArrayList<>();
        for (DeviceRegistry registry : deviceRegistries) {
            try {
                devices.addAll(registry.loadAll());
                // 接线变更通道：不注册监听器会让运行期增删改设备完全无效（且毫无提示）
                registry.addChangeListener(this::onDeviceChange);
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] failed to load devices from registry {}",
                        registry.getClass().getName(), ex);
            }
        }
        int bound = 0;
        int failed = 0;
        int retrying = 0;
        for (DeviceSpec device : devices) {
            DeviceBindRetrier.Outcome outcome = attemptBind(device);
            if (outcome.success()) {
                bound++;
                continue;
            }
            failed++;
            if (outcome.retryable()) {
                // 启动瞬间设备离线是最常见形态：不重试就等于「开机即永久零数据」
                bindRetrier.schedule(device);
                retrying++;
            }
        }
        log.info("[ypbin-iot] device bootstrap finished: total={}, bound={}, failed={} ({} scheduled for "
                + "device-level bind retry)", devices.size(), bound, failed, retrying);
    }

    /**
     * 接入单个设备。
     *
     * @param device 设备规格
     * @return 成功返回 {@code true}
     */
    public boolean bind(DeviceSpec device) {
        DeviceBindRetrier.Outcome outcome = attemptBind(device);
        if (!outcome.success() && outcome.retryable()) {
            // 显式调用方同样享受设备级重试：宿主调 bind 失败后不必自己安排重试
            bindRetrier.schedule(device);
        }
        return outcome.success();
    }

    /**
     * 尝试接入单个设备，并给出「是否值得重试」的判定（issue #38）。
     *
     * <p>与 {@link #bind(DeviceSpec)} 的区别只在返回值：这里区分
     * <b>瞬时失败</b>（建链失败、探测不可达、acquire/bind 超时——值得退避重试）
     * 与 <b>永久失败</b>（无适配器、校验不通过、链路规格缺失、生命周期已关闭——重试没有意义）。
     * 判定依据是「换时间再试是否可能成功」，不是「异常类型」。</p>
     *
     * @param device 设备规格
     * @return 尝试结果（永不返回 {@code null}）
     */
    private DeviceBindRetrier.Outcome attemptBind(DeviceSpec device) {
        if (closed.get()) {
            log.warn("[ypbin-iot] lifecycle already closed; refusing to bind device {}", device.deviceId());
            return DeviceBindRetrier.Outcome.permanent(null);
        }
        Optional<ProtocolAdapter> adapterOptional = adapterRegistry.find(device.protocol());
        Optional<AdapterContext> contextOptional = adapterRegistry.contextOf(device.protocol());
        if (adapterOptional.isEmpty() || contextOptional.isEmpty()) {
            log.warn("[ypbin-iot] no adapter registered for device {} protocol {}",
                    device.deviceId(), device.protocol());
            return DeviceBindRetrier.Outcome.permanent(null);
        }
        ValidationResult validation = validate(device);
        if (!validation.passed()) {
            log.warn("[ypbin-iot] device {} rejected by validation: {}",
                    device.deviceId(), validation.reasons());
            return DeviceBindRetrier.Outcome.permanent(null);
        }
        Optional<ConnectionSpec> specOptional = resolveSpec(device.connectionId());
        if (specOptional.isEmpty()) {
            log.warn("[ypbin-iot] no connection spec found for device {} connectionId {}",
                    device.deviceId(), device.connectionId());
            return DeviceBindRetrier.Outcome.permanent(null);
        }
        ProtocolAdapter adapter = adapterOptional.get();
        AdapterContext context = contextOptional.get();
        DeviceSpec effective = withDefaultPollInterval(device);
        // UP-2：把 ypbin.iot.protocol.<code>.connect-timeout 变成「建链超时的配置默认值」——
        // 宿主 provider 未显式给超时（恰为 DEFAULT）时用配置值覆盖（死键变活键）；
        // 绑定编排的各 orTimeout 也跟随该值（connect-timeout=2s 时一趟绑定最坏 2s，不再恒为 10s 饿死租约）。
        ConnectionSpec spec = applyConfiguredConnectTimeout(specOptional.get());
        Duration bindTimeout = spec.connectTimeout();
        if (properties.devices().isProbeBeforeBind()) {
            ProbeResult probe = adapter.probe(spec, context)
                    .toCompletableFuture()
                    .orTimeout(bindTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .handle((result, error) -> error == null ? result : null)
                    .join();
            if (probe == null || !probe.reachable()) {
                log.warn("[ypbin-iot] device {} skipped: probe before bind reported unreachable ({})",
                        device.deviceId(), probe == null ? "probe failed" : probe.failureReason());
                // 探测不可达是典型的瞬时失败（对端离线/网络抖动）：值得设备级退避重试
                return DeviceBindRetrier.Outcome.retryable(null);
            }
        }
        try {
            // 同一设备重复绑定：先释放旧会话与旧引用，否则旧引用永不归还、连接只增不减
            // 注意用 detach 而非 unbind：重连路径会走到这里，不能把归属与重连一起清掉
            detach(device.deviceId());
            ConnectionRegistry.ConnectionHandle handle = connectionRegistry
                    .acquire(adapter, spec, context)
                    .toCompletableFuture()
                    .orTimeout(bindTimeout.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            DeviceSession session;
            try {
                session = adapter.bind(handle.connection(), effective, context)
                        .toCompletableFuture()
                        .orTimeout(bindTimeout.toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (RuntimeException ex) {
                // 绑定失败必须归还链路引用：否则引用计数永不为零、空闲回收永不触发、配额泄漏
                handle.release();
                log.error("[ypbin-iot] bind failed for device {}; connection released to avoid quota leak",
                        device.deviceId(), ex);
                return DeviceBindRetrier.Outcome.retryable(unwrap(ex));
            }
            if (closed.get()) {
                // close() 与在途 bind 交错：绑定期间生命周期已被关闭，必须立即撤销，
                // 否则 close() 返回后仍残留永不释放的会话（它不在 close 的快照里）
                session.close().toCompletableFuture()
                        .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .exceptionally(ex -> null)
                        .join();
                handle.release();
                log.warn("[ypbin-iot] lifecycle closed during bind; device {} rolled back.",
                        device.deviceId());
                return DeviceBindRetrier.Outcome.permanent(null);
            }
            sessions.put(device.deviceId(), session);
            handles.put(device.deviceId(), handle);
            deviceSpecs.put(device.deviceId(), effective);
            devicesByConnection
                    .computeIfAbsent(device.connectionId(), ignored -> ConcurrentHashMap.newKeySet())
                    .add(device.deviceId());
            // 绑定成功即挂监听：链路意外关闭时这台设备要能自动恢复
            watchConnection(device.connectionId(), handle.connection());
            log.debug("[ypbin-iot] device {} bound to session {}.", device.deviceId(), session.sessionId());
            return DeviceBindRetrier.Outcome.ok();
        } catch (RuntimeException ex) {
            log.error("[ypbin-iot] failed to bind device {}", device.deviceId(), ex);
            // 建链/绑定异常（超时、连接被拒、协议错误）通常是瞬时的：值得退避重试
            return DeviceBindRetrier.Outcome.retryable(unwrap(ex));
        }
    }

    /**
     * 设备未声明采集周期时补上配置的默认值。
     *
     * <p>{@code DeviceSpec.pollInterval} 为 {@link Duration#ZERO} 表示「仅订阅不轮询」，
     * 但设备台账往往不显式填这一项——此时用 {@code ypbin.iot.devices.default-poll-interval} 兜底，
     * 避免「配置了默认周期却不生效」。</p>
     */
    private DeviceSpec withDefaultPollInterval(DeviceSpec device) {
        if (!device.pollInterval().isZero()) {
            return device;
        }
        Duration fallback = properties.devices().defaultPollInterval();
        if (fallback == null || fallback.isZero()) {
            return device;
        }
        return new DeviceSpec(device.deviceId(), device.deviceName(), device.protocol(),
                device.connectionId(), device.localAddress(), fallback, device.properties());
    }

    /**
     * 解绑设备并归还链路引用；幂等。
     *
     * @param deviceId 设备标识
     * @return 此前存在绑定或句柄则返回 {@code true}
     */
    public boolean unbind(String deviceId) {
        // 显式解绑即「不再接入」：取消该设备所有待重试，否则解绑后它还会被重试逻辑绑回来
        bindRetrier.cancel(deviceId);
        boolean detached = detach(deviceId);
        // 公开的 unbind 语义是「这台设备不再接入」：连归属一起清除，
        // 若它是该链路最后一台设备则停掉重连（否则会为一条没人用的链路反复重试）
        forgetDevice(deviceId);
        return detached;
    }

    /**
     * 仅断开设备与会话/句柄的关联，**不清除归属**。
     *
     * <p>供重连路径使用：{@code reconnect → bind → 先 detach 再 bind}，
     * 若这里连归属一起清除，重连会把自己取消掉（该缺陷已被 LIFE-12 用例实证）。</p>
     *
     * @param deviceId 设备标识
     * @return 此前存在会话或句柄则返回 {@code true}
     */
    private boolean detach(String deviceId) {
        DeviceSession session = sessions.remove(deviceId);
        ConnectionRegistry.ConnectionHandle handle = handles.remove(deviceId);
        // 注意：这里**不**清除设备归属（deviceSpecs / devicesByConnection）。
        // unbind 也被「重连路径」调用（reconnect → bind → 先 unbind 再 bind），
        // 若在此清除归属并取消重连，重连会把自己取消掉（该缺陷已被 LIFE-12 用例实证）。
        // 归属只在「设备被显式移除」（onDeviceChange REMOVE）或生命周期关闭时清除。
        if (session != null) {
            session.close().toCompletableFuture()
                    .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        log.error("[ypbin-iot] failed to close session of device {}", deviceId, ex);
                        return null;
                    })
                    .join();
        }
        if (handle != null) {
            handle.release();
        }
        return session != null || handle != null;
    }

    /**
     * 处理设备配置变更。
     *
     * <p>幂等：{@code revision} 不大于已应用值时直接丢弃。同一设备的变更按设备级锁串行，
     * 且统一走「先解绑再绑定」，避免切换窗口内出现新旧会话双活。</p>
     *
     * <p><b>两个「静默零数据」防线的落点（issue #38）</b>：</p>
     * <ul>
     *   <li><b>过期 revision 丢弃</b>不再是 debug：级别由
     *       {@code ypbin.iot.devices.stale-revision-log-level} 配置（默认 {@code INFO}）。
     *       原因：宿主的 revision 计数一旦漏递增，变更就被永久丢弃、设备零数据，
     *       而 debug 日志在生产环境上等于不存在。日志里同时给出「如何修复」的提示与计数器值。</li>
     *   <li><b>bind 失败</b>不再只记一行 WARN 了事：交给 {@link DeviceBindRetrier} 按设备退避重试，
     *       耗尽后由 {@link #onBindRetryExhausted} 记 WARN 带完整堆栈并通知
     *       {@link DeviceEventListener}。</li>
     * </ul>
     *
     * @param change 变更事件
     */
    public void onDeviceChange(DeviceChange change) {
        String deviceId = change.device().deviceId();
        ReentrantLock deviceLock = deviceLocks.computeIfAbsent(deviceId, ignored -> new ReentrantLock());
        deviceLock.lock();
        try {
            long applied = appliedRevisions.getOrDefault(deviceId, Long.MIN_VALUE);
            if (change.revision() <= applied) {
                logStaleRevision(deviceId, change.revision(), applied);
                return;
            }
            appliedRevisions.put(deviceId, change.revision());
            if (change.type() == ChangeType.REMOVE) {
                unbind(deviceId);
                bindRetrier.cancel(deviceId);
                forgetDevice(deviceId);
                deviceLocks.remove(deviceId);
                appliedRevisions.remove(deviceId);
                return;
            }
            DeviceBindRetrier.Outcome outcome = attemptBind(change.device());
            if (outcome.success()) {
                // 配置变更成功：该设备此前若在等待重试，直接取消（否则会再 bind 一次）
                bindRetrier.cancel(deviceId);
                return;
            }
            if (outcome.retryable()) {
                log.warn("[ypbin-iot] device change not applied: device={} type={}; scheduling "
                        + "device-level bind retry (see ypbin.iot.devices.rebind-backoff-initial/-max).",
                        deviceId, change.type(), outcome.cause());
                bindRetrier.schedule(change.device());
                return;
            }
            // 永久失败（配置非法、无适配器、规格缺失）：重试没有意义，如实记录即可
            log.warn("[ypbin-iot] device change not applied and will NOT be retried: device={} type={} "
                    + "(configuration problem, fix the device definition and push the change again).",
                    deviceId, change.type(), outcome.cause());
        } finally {
            deviceLock.unlock();
        }
    }

    /**
     * 记录「过期 revision 被丢弃」。
     *
     * <p><b>刻意不用固定 debug</b>：这条日志对应「设备永久零数据」的根因，
     * 宿主侧曾经注释自证「≤ 已应用值的变更被直接丢弃且没有任何日志」。
     * 默认级别 {@code INFO}，可配（{@code ypbin.iot.devices.stale-revision-log-level}）。</p>
     *
     * @param deviceId 设备标识
     * @param revision 收到的 revision
     * @param applied  已应用的 revision
     */
    private void logStaleRevision(String deviceId, long revision, long applied) {
        LogLevel level = properties.devices().staleRevisionLogLevel();
        // 刻意不用 switch-on-enum：javac 会编译成 ordinal() 查表，触发「禁 ordinal」门禁误报
        if (level == LogLevel.ERROR) {
            log.error("[ypbin-iot] stale device change ignored: device={} revision={} applied={} —— "
                    + "宿主必须按设备严格递增 revision（推荐改用框架的 DeviceRevisionAllocator）；"
                    + "revision 漏递增会让该设备的变更被永久丢弃、设备零数据。",
                    deviceId, revision, applied);
        } else if (level == LogLevel.WARN) {
            log.warn("[ypbin-iot] stale device change ignored: device={} revision={} applied={} —— "
                    + "宿主必须按设备严格递增 revision（推荐改用框架的 DeviceRevisionAllocator）；"
                    + "revision 漏递增会让该设备的变更被永久丢弃、设备零数据。",
                    deviceId, revision, applied);
        } else if (level == LogLevel.INFO) {
            log.info("[ypbin-iot] stale device change ignored: device={} revision={} applied={} —— "
                    + "宿主必须按设备严格递增 revision（推荐改用框架的 DeviceRevisionAllocator）；"
                    + "revision 漏递增会让该设备的变更被永久丢弃、设备零数据。",
                    deviceId, revision, applied);
        } else {
            log.debug("[ypbin-iot] stale device change ignored: device={} revision={} applied={}.",
                    deviceId, revision, applied);
        }
    }

    /**
     * 重试耗尽：记 WARN 带完整堆栈，并通知宿主。
     *
     * <p><b>通知宿主而不是只打日志</b>：设备离线到「框架放弃重试」是需要告警的事件，
     * 宿主只有通过 {@link DeviceEventListener} 才能更新设备状态/告警（框架不落库）。
     * 通知异常不得影响重试器状态机：监听器是宿主的代码，其缺陷不能反过来搞坏框架。</p>
     *
     * @param device   设备规格
     * @param attempts 失败尝试次数
     * @param cause    最后一次失败原因（可能为空）
     */
    private void onBindRetryExhausted(DeviceSpec device, int attempts, @Nullable Throwable cause) {
        // 完整堆栈必须传给日志：只有一行消息时现场无法判断是超时、拒绝还是协议错误
        log.warn("[ypbin-iot] device {} bind retry exhausted after {} attempt(s); giving up and "
                + "notifying listeners. The device stays OFFLINE with NO data until the next change.",
                device.deviceId(), attempts, cause);
        DeviceEvent event = new DeviceEvent(device.deviceId(), device.protocol(),
                DeviceEventType.CONNECT_FAILED, IotMessageKeys.DEVICE_BIND_RETRY_EXHAUSTED, cause,
                clock.instant());
        for (DeviceEventListener listener : eventListeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] device event listener {} failed while handling bind-retry "
                        + "exhaustion of device {}", listener.getClass().getName(), device.deviceId(), ex);
            }
        }
    }

    /**
     * 彻底忘记一台设备（显式移除时调用）：清除归属，并在该链路已无设备承载时停掉重连。
     *
     * @param deviceId 设备标识
     */
    private void forgetDevice(String deviceId) {
        DeviceSpec bound = deviceSpecs.remove(deviceId);
        if (bound == null) {
            return;
        }
        Set<String> peers = devicesByConnection.get(bound.connectionId());
        if (peers == null) {
            return;
        }
        peers.remove(deviceId);
        if (peers.isEmpty()) {
            // 该链路已无设备承载：停掉重连，避免为一条没人用的链路反复重试
            devicesByConnection.remove(bound.connectionId());
            watchedConnections.remove(bound.connectionId());
            reconnector.cancel(bound.connectionId());
        }
    }

    /**
     * 监听链路关闭。
     *
     * <p>只挂一次：同一链路上 N 台设备各挂一次监听会让一次断开触发 N 次重连调度。</p>
     */
    private void watchConnection(String connectionId, ProtocolConnection connection) {
        if (!watchedConnections.add(connectionId)) {
            return;
        }
        connection.whenClosed().whenComplete((reason, error) -> {
            // 必须先摘掉监听标记：意外断开后注册中心会摘除条目，重连会 open 出**全新的
            // ProtocolConnection 实例**，而标记是按 connectionId 记账的 —— 不在这里摘掉，
            // 新实例的 watchConnection 会因 add 失败而直接 return，
            // 结果是「第一次断开能恢复、第二次之后永久离线」（该缺陷已被复审实测确证）。
            watchedConnections.remove(connectionId);
            // 主动关闭（框架停机 / 设备解绑）不重连；只有意外断开才恢复
            if (closed.get()) {
                return;
            }
            CloseCause cause = reason == null ? CloseCause.TRANSPORT_ERROR : reason.cause();
            if (cause == CloseCause.CLIENT_REQUEST) {
                log.debug("[ypbin-iot] connection {} closed on request; no reconnect.", connectionId);
                return;
            }
            log.warn("[ypbin-iot] connection {} closed unexpectedly ({}); scheduling reconnect.",
                    connectionId, cause);
            reconnector.schedule(connectionId, settingsOf(connectionId));
        });
    }

    /**
     * 重连一条链路：把该链路上的全部设备重新绑定。
     *
     * <p>必须整体成功才算恢复 —— 一台设备绑定失败说明链路仍不可用，
     * 继续退避比「部分恢复」更安全（部分恢复会让退避节奏与实际可用性脱节）。</p>
     */
    private boolean reconnect(String connectionId) {
        Set<String> deviceIds = devicesByConnection.get(connectionId);
        if (deviceIds == null || deviceIds.isEmpty()) {
            return true;
        }
        boolean allBound = true;
        for (String deviceId : List.copyOf(deviceIds)) {
            DeviceSpec spec = deviceSpecs.get(deviceId);
            if (spec == null) {
                // 该设备已在重连期间被移除：跳过是正确行为，但**不能算作恢复成功**
                allBound = false;
                continue;
            }
            if (!bind(spec)) {
                // 链路重连里 bind 失败：同样进入设备级退避重试（否则链路恢复后这台设备仍永久离线）
                bindRetrier.schedule(spec);
                allBound = false;
                continue;
            }
            if (!deviceSpecs.containsKey(deviceId)) {
                // 绑定期间该设备被 REMOVE：撤销刚建立的绑定，否则被删除的设备会被永久复活
                unbind(deviceId);
                allBound = false;
                log.debug("[ypbin-iot] device {} was removed during reconnect; bind rolled back.", deviceId);
            }
        }
        return allBound;
    }

    /**
     * 取某条链路的退避参数。
     *
     * <p>退避参数属于「链路所属协议」的适配器设置；链路已在 devicesByConnection 中，
     * 但这里拿不到协议，因此从该链路上任一设备的规格反查。</p>
     */
    private AdapterSettings settingsOf(String connectionId) {
        Set<String> deviceIds = devicesByConnection.get(connectionId);
        if (deviceIds != null) {
            for (String deviceId : deviceIds) {
                DeviceSpec spec = deviceSpecs.get(deviceId);
                if (spec != null) {
                    Optional<AdapterContext> context = adapterRegistry.contextOf(spec.protocol());
                    if (context.isPresent()) {
                        return context.get().settings();
                    }
                }
            }
        }
        return DefaultAdapterSettings.defaults();
    }

    /**
     * 正在重连的链路数（诊断用）。
     *
     * @return 链路数
     */
    public int reconnectingCount() {
        return reconnector.activeCount();
    }

    /**
     * 累计重连尝试次数（诊断用）。
     *
     * @return 尝试次数
     */
    public long reconnectAttempts() {
        return reconnector.totalAttempts();
    }

    /**
     * 累计恢复次数（诊断用）。
     *
     * @return 恢复次数
     */
    public long reconnectRecovered() {
        return reconnector.totalRecovered();
    }

    /**
     * 当前等待 bind 重试的设备数（诊断用）。
     *
     * @return 设备数
     */
    public int bindRetryPendingCount() {
        return bindRetrier.pendingCount();
    }

    /**
     * 累计 bind 重试次数（诊断用）。
     *
     * @return 次数
     */
    public int bindRetryAttempts() {
        return bindRetrier.totalAttempts();
    }

    /**
     * 累计「重试后恢复」的设备次数（诊断用）。
     *
     * @return 次数
     */
    public int bindRetryRecovered() {
        return bindRetrier.totalRecovered();
    }

    /**
     * 累计「重试耗尽」的设备次数（诊断用）。
     *
     * @return 次数
     */
    public int bindRetryExhausted() {
        return bindRetrier.totalExhausted();
    }

    /**
     * 探测一条链路（供诊断使用）。
     *
     * @param spec 链路规格
     * @return 探测结果 Stage
     */
    public CompletableFuture<ProbeResult> probe(ConnectionSpec spec) {
        Optional<ProtocolAdapter> adapter = adapterRegistry.find(spec.protocol());
        Optional<AdapterContext> context = adapterRegistry.contextOf(spec.protocol());
        if (adapter.isEmpty() || context.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "no adapter registered for protocol " + spec.protocol()));
        }
        return adapter.get().probe(spec, context.get()).toCompletableFuture();
    }

    /**
     * 已绑定的会话数。
     *
     * @return 会话数
     */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 已绑定会话的快照（设备标识 → 会话）。
     *
     * @return 不可变视图
     */
    public Map<String, DeviceSession> sessions() {
        return Map.copyOf(sessions);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        // 必须先停重试与重连：否则关闭过程中被解绑的设备会被重试/重连逻辑重新绑回来
        bindRetrier.close();
        reconnector.close();
        int count = sessions.size();
        for (String deviceId : new ArrayList<>(sessions.keySet())) {
            unbind(deviceId);
        }
        devicesByConnection.clear();
        watchedConnections.clear();
        log.debug("[ypbin-iot] lifecycle closed, {} session(s) released.", count);
    }

    private Optional<ConnectionSpec> resolveSpec(String connectionId) {
        for (ConnectionSpecProvider provider : specProviders) {
            try {
                Optional<ConnectionSpec> found = provider.find(connectionId);
                if (found.isPresent()) {
                    return found;
                }
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] connection spec provider {} failed for {}",
                        provider.getClass().getName(), connectionId, ex);
            }
        }
        return Optional.empty();
    }

    /**
     * 应用协议级建链超时配置（UP-2：死键变活键）。
     *
     * <p>规则：宿主 provider 显式提供了超时（非 {@link ConnectionSpec#DEFAULT_CONNECT_TIMEOUT}）
     * 时尊重宿主；否则用 {@code ypbin.iot.protocol.<code>.connect-timeout} 的配置值。
     * 这样「看起来可配、实际不生效」的假缝被补上——协议模块内部的
     * {@code AdapterSettings.connectTimeout} 与本处建链超时口径一致。</p>
     *
     * @param spec 宿主提供的规格（非空）
     * @return 应用配置超时后的规格
     */
    private ConnectionSpec applyConfiguredConnectTimeout(ConnectionSpec spec) {
        if (spec.connectTimeout() != ConnectionSpec.DEFAULT_CONNECT_TIMEOUT) {
            // 宿主显式给过超时：以宿主为准
            return spec;
        }
        Duration configured = properties.protocol(spec.protocol().value()).connectTimeout();
        if (configured == null || configured.isZero() || configured.isNegative()) {
            return spec;
        }
        return spec.withConnectTimeout(configured);
    }

    /**
     * 剥掉 {@link CompletionException}/{@link ExecutionException} 包装，取出真正的原因。
     *
     * <p><b>为什么要剥</b>：异步 bind 失败经 {@code join()} 抛出时外面套了
     * {@code CompletionException}。若把它原样交给重试器，耗尽时打的堆栈首行是
     * 「java.util.concurrent.CompletionException」、事件携带的 cause 也是包装类 ——
     * 宿主按异常类型告警分类会全部落到同一个桶里，等于没有分类。剥到最内层原因即可。</p>
     *
     * @param error 捕获到的异常（非空）
     * @return 最内层原因（至少返回入参本身，永不返回 {@code null}）
     */
    private static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private ValidationResult validate(DeviceSpec device) {
        for (DeviceRegistry registry : deviceRegistries) {
            ValidationResult result = registry.validate(device);
            if (!result.passed()) {
                return result;
            }
        }
        return ValidationResult.ok();
    }
}
