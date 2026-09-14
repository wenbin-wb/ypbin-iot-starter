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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.core.spi.ValidationResult;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link IotLifecycle} 设备接入编排测试。
 *
 * <p>覆盖：正常绑定、缺适配器/缺链路规格/校验失败的拒绝路径、关闭释放、探测。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class IotLifecycleTest {

    private static final ProtocolCode CODE = ProtocolCode.of("stub");

    private static final String CONNECTION_ID = "conn-1";

    private DefaultTaskScheduler scheduler;

    private ConnectionRegistry connectionRegistry;

    private StubAdapter adapter;

    private AdapterRegistry adapterRegistry;

    private IotProperties properties;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(2, 64);
        connectionRegistry = new ConnectionRegistry(Duration.ofMinutes(5), 100, scheduler, Clock.systemUTC());
        adapter = new StubAdapter();
        AdapterContext context = new DefaultAdapterContext(CODE, DefaultAdapterSettings.defaults(),
                new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE,
                ref -> Optional.empty(), Clock.systemUTC(), 16);
        Map<ProtocolCode, AdapterContext> contexts = new LinkedHashMap<>();
        contexts.put(CODE, context);
        adapterRegistry = new AdapterRegistry(List.of(adapter), contexts);
        properties = new IotProperties(null, null, null, null, null, null);
    }

    @AfterEach
    void tearDown() {
        adapterRegistry.close();
        connectionRegistry.close();
        scheduler.close();
    }

    @Test
    @DisplayName("LIFE-01 正常路径：设备应被绑定并可通过 close 释放")
    void bindMustSucceedAndCloseMustRelease() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(true)), properties, scheduler);
        assertThat(lifecycle.bind(device())).isTrue();
        assertThat(lifecycle.sessionCount()).isEqualTo(1);
        assertThat(lifecycle.sessions()).containsKey("d1");
        assertThat(adapter.lastConnection().sessionClosed()).isFalse();

        lifecycle.close();
        assertThat(lifecycle.sessionCount()).isZero();
        assertThat(adapter.lastConnection().sessionClosed()).as("关闭必须释放会话").isTrue();
        lifecycle.close();
        assertThat(lifecycle.sessionCount()).as("重复 close 必须幂等").isZero();
    }

    @Test
    @DisplayName("LIFE-02 校验失败必须拒绝接入，且不建立链路")
    void validationFailureMustRejectDevice() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.fail("iot.test.invalid"))),
                List.of(new StubSpecProvider(true)), properties, scheduler);
        assertThat(lifecycle.bind(device())).isFalse();
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-03 缺失链路规格必须拒绝接入")
    void missingSpecMustRejectDevice() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(false)), properties, scheduler);
        assertThat(lifecycle.bind(device())).isFalse();
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-04 未注册协议的设备必须被拒绝")
    void unknownProtocolMustBeRejected() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(), List.of(new StubSpecProvider(true)), properties, scheduler);
        DeviceSpec unknown = new DeviceSpec("d2", "未知", ProtocolCode.of("absent"), CONNECTION_ID, "",
                Duration.ZERO, Map.of());
        assertThat(lifecycle.bind(unknown)).isFalse();
    }

    @Test
    @DisplayName("LIFE-05 无 DeviceRegistry 时启动钩子必须安全返回")
    void applicationReadyWithoutRegistryMustBeSafe() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry, List.of(),
                List.of(), properties, scheduler);
        lifecycle.onApplicationEvent(null);
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-06 设备接入开关关闭时启动钩子不得绑定")
    void disabledDeviceBootstrapMustSkip() {
        IotProperties disabled = new IotProperties(true, null, null, null,
                new IotProperties.DeviceProperties(false, null, null), null);
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(true)), disabled, scheduler);
        lifecycle.onApplicationEvent(null);
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-07 探测必须委托给适配器并返回结果")
    void probeMustDelegateToAdapter() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry, List.of(),
                List.of(), properties, scheduler);
        ProbeResult result = lifecycle.probe(spec()).toCompletableFuture().join();
        assertThat(result.reachable()).isTrue();

        ConnectionSpec unknown = new ConnectionSpec(CONNECTION_ID, ProtocolCode.of("absent"),
                Endpoint.of("tcp://h:1"), null, null, null, null, Map.of());
        assertThat(lifecycle.probe(unknown).isCompletedExceptionally()).isTrue();
    }

    @Test
    @DisplayName("LIFE-08 设备来源抛异常时启动钩子必须继续处理其余来源")
    void failingRegistryMustNotAbortBootstrap() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new FailingDeviceRegistry(), new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(true)), properties, scheduler);
        lifecycle.onApplicationEvent(null);
        assertThat(lifecycle.sessionCount()).as("第二个来源的设备仍应被接入").isEqualTo(1);
    }

    @Test
    @DisplayName("LIFE-09 bind 失败必须归还链路引用，不得让配额永久泄漏")
    void failedBindMustReleaseConnectionHandle() {
        adapter.failBind = true;
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(true)), properties, scheduler);
        assertThat(lifecycle.bind(device())).isFalse();
        assertThat(lifecycle.sessionCount()).isZero();
        // 归还引用后空闲回收才能生效：把超时压到很小再验证链路被回收
        ConnectionRegistry shortIdle = new ConnectionRegistry(Duration.ofMillis(30), 100, scheduler,
                Clock.systemUTC());
        IotLifecycle second = new IotLifecycle(adapterRegistry, shortIdle,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(true)), properties, scheduler);
        assertThat(second.bind(device())).isFalse();
        awaitUntil(() -> shortIdle.activeCount() == 0, Duration.ofSeconds(3));
        assertThat(shortIdle.activeCount())
                .as("bind 失败未归还引用时，连接会一直留在注册表里（配额泄漏）")
                .isZero();
        second.close();
        lifecycle.close();
    }

    @Test
    @DisplayName("LIFE-10 close 之后不得再接受绑定")
    void bindAfterCloseMustBeRejected() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(), List.of(new StubSpecProvider(true)), properties, scheduler);
        lifecycle.close();
        assertThat(lifecycle.bind(device()))
                .as("关闭后仍接受绑定会让新链路永久无人释放")
                .isFalse();
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-11 设备变更通道必须已接线（ADD 生效、REMOVE 解绑、重复 revision 幂等）")
    void deviceChangeChannelMustBeWired() {
        StubDeviceRegistry registry = new StubDeviceRegistry(ValidationResult.ok());
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(registry), List.of(new StubSpecProvider(true)), properties, scheduler);
        lifecycle.onApplicationEvent(null);
        assertThat(registry.listener)
                .as("框架必须在启动后注册变更监听器，否则运行期增删设备完全无效")
                .isNotNull();

        String firstSession = lifecycle.sessions().get("d1").sessionId();
        // 重复 revision 必须被丢弃（幂等）
        registry.listener.accept(new DeviceChange(ChangeType.UPDATE, device(), 1L));
        assertThat(lifecycle.sessions().get("d1").sessionId())
                .as("重复 revision 不得触发重绑").isEqualTo(firstSession);

        // 更大 revision 触发「先解绑再绑定」，会话必须被替换
        registry.listener.accept(new DeviceChange(ChangeType.UPDATE, device(), 2L));
        assertThat(lifecycle.sessionCount()).isEqualTo(1);
        assertThat(adapter.lastConnection()).isNotNull();

        // REMOVE 必须解绑
        registry.listener.accept(new DeviceChange(ChangeType.REMOVE, device(), 3L));
        assertThat(lifecycle.sessionCount()).as("REMOVE 必须解绑设备").isZero();
        lifecycle.close();
    }


    @Test
    @DisplayName("LIFE-12 链路意外关闭必须自动重连并重新绑定设备")
    void unexpectedCloseMustTriggerReconnect() {
        DefaultTaskScheduler shortScheduler = new DefaultTaskScheduler(2, 64);
        // 退避压到很短，让用例可快速观察到重连
        DefaultAdapterSettings fastBackoff = new DefaultAdapterSettings(true,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMillis(30), Duration.ofMillis(120), 0.0D, 100, 64, Map.of());
        AdapterContext reconnectContext = new DefaultAdapterContext(CODE, fastBackoff,
                new NoopEgress(), shortScheduler, NoopMetricsRecorder.INSTANCE,
                ref -> Optional.empty(), Clock.systemUTC(), 32);
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter), Map.of(CODE, reconnectContext));
        ConnectionRegistry connections = new ConnectionRegistry(Duration.ofMinutes(5), 100, shortScheduler,
                Clock.systemUTC());
        IotLifecycle lifecycle = new IotLifecycle(registry, connections,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(true)), properties, shortScheduler);
        try {
            assertThat(lifecycle.bind(device())).isTrue();
            assertThat(adapter.openInvocations()).isEqualTo(1);

            // 对端异常关闭：注册中心会摘除条目，框架应安排重连
            adapter.lastConnection().simulateRemoteClose();
            awaitUntil(() -> adapter.openInvocations() >= 2, Duration.ofSeconds(10));
            assertThat(adapter.openInvocations())
                    .as("链路意外关闭后必须自动重连（原实现零重连，一次抖动即永久离线）")
                    .isGreaterThanOrEqualTo(2);
            // 必须以「恢复计数」为等待条件：sessionCount 在重连前就已为 1（旧会话尚未释放），
            // 用它等待会让断言跑在退避到期之前，从而测不到真正的重连
            awaitUntil(() -> lifecycle.reconnectRecovered() > 0, Duration.ofSeconds(10));
            assertThat(lifecycle.reconnectRecovered()).as("必须记录一次恢复").isPositive();
            assertThat(lifecycle.sessionCount()).as("重连后设备必须仍然绑定").isEqualTo(1);
            awaitUntil(() -> lifecycle.reconnectingCount() == 0, Duration.ofSeconds(10));
            assertThat(lifecycle.reconnectingCount()).as("恢复后不得再处于重连中").isZero();
        } finally {
            lifecycle.close();
            shortScheduler.close();
        }
    }

    @Test
    @DisplayName("LIFE-13 主动解绑必须取消重连，不得把设备重新绑回来")
    void explicitUnbindMustCancelReconnect() {
        DefaultTaskScheduler shortScheduler = new DefaultTaskScheduler(2, 64);
        DefaultAdapterSettings fastBackoff = new DefaultAdapterSettings(true,
                Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofMillis(200), Duration.ofMillis(400), 0.0D, 100, 64, Map.of());
        AdapterContext reconnectContext = new DefaultAdapterContext(CODE, fastBackoff,
                new NoopEgress(), shortScheduler, NoopMetricsRecorder.INSTANCE,
                ref -> Optional.empty(), Clock.systemUTC(), 32);
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter), Map.of(CODE, reconnectContext));
        ConnectionRegistry connections = new ConnectionRegistry(Duration.ofMinutes(5), 100, shortScheduler,
                Clock.systemUTC());
        IotLifecycle lifecycle = new IotLifecycle(registry, connections, List.of(),
                List.of(new StubSpecProvider(true)), properties, shortScheduler);
        try {
            assertThat(lifecycle.bind(device())).isTrue();
            adapter.lastConnection().simulateRemoteClose();
            // 在退避到期前解绑：重连必须被取消
            assertThat(lifecycle.unbind("d1")).isTrue();
            assertThat(lifecycle.reconnectingCount()).as("解绑后不得残留重连状态").isZero();
            sleep(700L);
            assertThat(lifecycle.sessionCount())
                    .as("已解绑的设备不得被重连逻辑重新绑回来")
                    .isZero();
        } finally {
            lifecycle.close();
            shortScheduler.close();
        }
    }

    private static void awaitUntil(java.util.function.BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static DeviceSpec device() {
        return new DeviceSpec("d1", "设备", CODE, CONNECTION_ID, "", Duration.ZERO, Map.of());
    }

    private static ConnectionSpec spec() {
        return new ConnectionSpec(CONNECTION_ID, CODE, Endpoint.of("tcp://127.0.0.1:1"),
                Duration.ofSeconds(1), Duration.ofSeconds(1), null, null, Map.of());
    }

    /**
     * 设备来源桩。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubDeviceRegistry implements DeviceRegistry {

        private final ValidationResult validation;

        private Consumer<DeviceChange> listener;

        private StubDeviceRegistry(ValidationResult validation) {
            this.validation = validation;
        }

        @Override
        public List<DeviceSpec> loadAll() {
            return List.of(device());
        }

        @Override
        public ValidationResult validate(DeviceSpec device) {
            return validation;
        }

        @Override
        public void addChangeListener(Consumer<DeviceChange> listener) {
            // 只保存引用：由测试显式投递变更，避免「注册即回调」干扰启动流程
            this.listener = listener;
        }
    }

    /**
     * 加载即失败的设备来源。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class FailingDeviceRegistry implements DeviceRegistry {

        @Override
        public List<DeviceSpec> loadAll() {
            throw new IllegalStateException("registry failure by design");
        }

        @Override
        public void addChangeListener(Consumer<DeviceChange> listener) {
            // 本测试不关心变更
        }
    }

    /**
     * 链路规格来源桩。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubSpecProvider implements ConnectionSpecProvider {

        private final boolean present;

        private StubSpecProvider(boolean present) {
            this.present = present;
        }

        @Override
        public Optional<ConnectionSpec> find(String connectionId) {
            return present && CONNECTION_ID.equals(connectionId) ? Optional.of(spec()) : Optional.empty();
        }
    }

    /**
     * 适配器桩：返回可追踪的链路与会话。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubAdapter implements ProtocolAdapter {

        private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
                .code(CODE)
                .name("Stub")
                .transport("TCP")
                .capabilities(ProtocolCapability.READ)
                .build();

        private volatile StubConnection lastConnection;

        private final java.util.concurrent.atomic.AtomicInteger openInvocations =
                new java.util.concurrent.atomic.AtomicInteger();

        private boolean failBind;

        private int openInvocations() {
            return openInvocations.get();
        }

        @Override
        public ProtocolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
            openInvocations.incrementAndGet();
            StubConnection connection = new StubConnection(spec.connectionId());
            lastConnection = connection;
            return CompletableFuture.completedFuture(connection);
        }

        @Override
        public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
                AdapterContext context) {
            if (failBind) {
                return CompletableFuture.failedFuture(new IllegalStateException("bind failure by design"));
            }
            StubSession session = new StubSession(device, connection);
            ((StubConnection) connection).attach(session);
            return CompletableFuture.completedFuture(session);
        }

        @Override
        public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.completedFuture(ProbeResult.reachable(DESCRIPTOR, Map.of()));
        }

        private StubConnection lastConnection() {
            return lastConnection;
        }
    }

    /**
     * 可追踪链路桩。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubConnection implements ProtocolConnection {

        private final String connectionId;

        private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private StubSession session;

        private StubConnection(String connectionId) {
            this.connectionId = connectionId;
        }

        private void attach(StubSession stubSession) {
            this.session = stubSession;
        }

        /** 模拟对端异常关闭（非主动关闭），用于验证重连。 */
        private void simulateRemoteClose() {
            closed.set(true);
            closeReason.complete(new CloseReason(CloseCause.REMOTE_CLOSED, "remote closed", null,
                    Instant.now()));
        }

        @Override
        public String connectionId() {
            return connectionId;
        }

        @Override
        public Endpoint endpoint() {
            return Endpoint.of("tcp://127.0.0.1:1");
        }

        @Override
        public SessionState state() {
            return closed.get() ? SessionState.CLOSED : SessionState.ONLINE;
        }

        @Override
        public Instant openedAt() {
            return Instant.now();
        }

        @Override
        public DeviceSession session() {
            return session;
        }

        @Override
        public CompletionStage<CloseReason> whenClosed() {
            return closeReason;
        }

        @Override
        public Map<String, String> describe() {
            return Map.of();
        }

        @Override
        public <T> Optional<T> unwrap(Class<T> extensionType) {
            return Optional.empty();
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                closeReason.complete(CloseReason.clientRequest(Instant.now()));
            }
        }

        private boolean sessionClosed() {
            return session != null && session.closed();
        }
    }

    /**
     * 可追踪会话桩。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubSession implements DeviceSession {

        private final DeviceSpec device;

        private final ProtocolConnection connection;

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private StubSession(DeviceSpec device, ProtocolConnection connection) {
            this.device = device;
            this.connection = connection;
        }

        @Override
        public String sessionId() {
            return "stub-" + device.deviceId();
        }

        @Override
        public DeviceSpec device() {
            return device;
        }

        @Override
        public String connectionId() {
            return connection.connectionId();
        }

        @Override
        public SessionState state() {
            return closed.get() ? SessionState.CLOSED : SessionState.ONLINE;
        }

        @Override
        public Instant boundAt() {
            return Instant.now();
        }

        @Override
        public CompletionStage<ReadResult> read(ReadRequest request) {
            return CompletableFuture.completedFuture(new ReadResult(List.of(), Duration.ZERO));
        }

        @Override
        public CompletionStage<WriteResult> write(WriteRequest request) {
            return CompletableFuture.completedFuture(new WriteResult(List.of(), Duration.ZERO));
        }

        @Override
        public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request,
                DataListener listener) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
        }

        @Override
        public CompletionStage<Void> unsubscribe(SubscriptionHandle handle) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<PingResult> ping() {
            return CompletableFuture.completedFuture(PingResult.alive(0L));
        }

        @Override
        public <T> Optional<T> unwrap(Class<T> extensionType) {
            return Optional.empty();
        }

        @Override
        public CompletionStage<Void> close() {
            closed.set(true);
            connection.close();
            return CompletableFuture.completedFuture(null);
        }

        private boolean closed() {
            return closed.get();
        }
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }

}
