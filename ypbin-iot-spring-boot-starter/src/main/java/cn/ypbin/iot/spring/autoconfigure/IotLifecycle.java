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
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.core.spi.ValidationResult;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
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

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, ConnectionRegistry.ConnectionHandle> handles = new ConcurrentHashMap<>();

    /** 设备级排他锁：保证同一设备的变更不会产生「新旧会话双活」。 */
    private final Map<String, ReentrantLock> deviceLocks = new ConcurrentHashMap<>();

    /** 已应用的配置版本号，用于变更幂等（消息总线可能 at-least-once 重复投递）。 */
    private final Map<String, Long> appliedRevisions = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 重连退避调度（按 connectionId 独立）。 */
    private final ConnectionReconnector reconnector;

    /** 设备当前生效的规格：重连时必须用同一份规格重新绑定。 */
    private final Map<String, DeviceSpec> deviceSpecs = new ConcurrentHashMap<>();

    /** 链路 → 该链路上的设备集合（1:N 链路重连时要一起恢复）。 */
    private final Map<String, Set<String>> devicesByConnection = new ConcurrentHashMap<>();

    /** 已挂上 whenClosed 监听的链路，避免同一链路重复挂监听导致重复重连。 */
    private final Set<String> watchedConnections = ConcurrentHashMap.newKeySet();

    /**
     * 创建生命周期编排器。
     *
     * @param adapterRegistry    适配器注册中心
     * @param connectionRegistry 连接注册中心
     * @param deviceRegistries   设备来源实现
     * @param specProviders      链路规格来源实现
     * @param properties         配置
     */
    public IotLifecycle(AdapterRegistry adapterRegistry, ConnectionRegistry connectionRegistry,
            List<DeviceRegistry> deviceRegistries, List<ConnectionSpecProvider> specProviders,
            IotProperties properties, TaskScheduler taskScheduler) {
        this.adapterRegistry = adapterRegistry;
        this.connectionRegistry = connectionRegistry;
        this.deviceRegistries = deviceRegistries == null ? List.of() : List.copyOf(deviceRegistries);
        this.specProviders = specProviders == null ? List.of() : List.copyOf(specProviders);
        this.properties = properties;
        this.reconnector = new ConnectionReconnector(
                Objects.requireNonNull(taskScheduler, "taskScheduler must not be null"),
                this::reconnect);
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
        for (DeviceSpec device : devices) {
            if (bind(device)) {
                bound++;
            } else {
                failed++;
            }
        }
        log.info("[ypbin-iot] device bootstrap finished: total={}, bound={}, failed={}",
                devices.size(), bound, failed);
    }

    /**
     * 接入单个设备。
     *
     * @param device 设备规格
     * @return 成功返回 {@code true}
     */
    public boolean bind(DeviceSpec device) {
        if (closed.get()) {
            log.warn("[ypbin-iot] lifecycle already closed; refusing to bind device {}", device.deviceId());
            return false;
        }
        Optional<ProtocolAdapter> adapterOptional = adapterRegistry.find(device.protocol());
        Optional<AdapterContext> contextOptional = adapterRegistry.contextOf(device.protocol());
        if (adapterOptional.isEmpty() || contextOptional.isEmpty()) {
            log.warn("[ypbin-iot] no adapter registered for device {} protocol {}",
                    device.deviceId(), device.protocol());
            return false;
        }
        ValidationResult validation = validate(device);
        if (!validation.passed()) {
            log.warn("[ypbin-iot] device {} rejected by validation: {}",
                    device.deviceId(), validation.reasons());
            return false;
        }
        Optional<ConnectionSpec> specOptional = resolveSpec(device.connectionId());
        if (specOptional.isEmpty()) {
            log.warn("[ypbin-iot] no connection spec found for device {} connectionId {}",
                    device.deviceId(), device.connectionId());
            return false;
        }
        ProtocolAdapter adapter = adapterOptional.get();
        AdapterContext context = contextOptional.get();
        DeviceSpec effective = withDefaultPollInterval(device);
        if (properties.devices().isProbeBeforeBind()) {
            ProbeResult probe = adapter.probe(specOptional.get(), context)
                    .toCompletableFuture()
                    .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .handle((result, error) -> error == null ? result : null)
                    .join();
            if (probe == null || !probe.reachable()) {
                log.warn("[ypbin-iot] device {} skipped: probe before bind reported unreachable ({})",
                        device.deviceId(), probe == null ? "probe failed" : probe.failureReason());
                return false;
            }
        }
        try {
            // 同一设备重复绑定：先释放旧会话与旧引用，否则旧引用永不归还、连接只增不减
            // 注意用 detach 而非 unbind：重连路径会走到这里，不能把归属与重连一起清掉
            detach(device.deviceId());
            ConnectionRegistry.ConnectionHandle handle = connectionRegistry
                    .acquire(adapter, specOptional.get(), context)
                    .toCompletableFuture()
                    .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            DeviceSession session;
            try {
                session = adapter.bind(handle.connection(), effective, context)
                        .toCompletableFuture()
                        .orTimeout(BIND_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                        .join();
            } catch (RuntimeException ex) {
                // 绑定失败必须归还链路引用：否则引用计数永不为零、空闲回收永不触发、配额泄漏
                handle.release();
                log.error("[ypbin-iot] bind failed for device {}; connection released to avoid quota leak",
                        device.deviceId(), ex);
                return false;
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
                return false;
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
            return true;
        } catch (RuntimeException ex) {
            log.error("[ypbin-iot] failed to bind device {}", device.deviceId(), ex);
            return false;
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
     * @param change 变更事件
     */
    public void onDeviceChange(DeviceChange change) {
        String deviceId = change.device().deviceId();
        ReentrantLock deviceLock = deviceLocks.computeIfAbsent(deviceId, ignored -> new ReentrantLock());
        deviceLock.lock();
        try {
            long applied = appliedRevisions.getOrDefault(deviceId, Long.MIN_VALUE);
            if (change.revision() <= applied) {
                log.debug("[ypbin-iot] stale device change ignored: device={} revision={} applied={}",
                        deviceId, change.revision(), applied);
                return;
            }
            appliedRevisions.put(deviceId, change.revision());
            if (change.type() == ChangeType.REMOVE) {
                unbind(deviceId);
                forgetDevice(deviceId);
                deviceLocks.remove(deviceId);
                appliedRevisions.remove(deviceId);
                return;
            }
            if (!bind(change.device())) {
                log.warn("[ypbin-iot] device change not applied: device={} type={}",
                        deviceId, change.type());
            }
        } finally {
            deviceLock.unlock();
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
        // 必须先停重连：否则关闭过程中被解绑的设备会被重连逻辑重新绑回来
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
