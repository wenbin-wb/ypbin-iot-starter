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
                List.of(new StubSpecProvider(true)), properties);
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
                List.of(new StubSpecProvider(true)), properties);
        assertThat(lifecycle.bind(device())).isFalse();
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-03 缺失链路规格必须拒绝接入")
    void missingSpecMustRejectDevice() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider(false)), properties);
        assertThat(lifecycle.bind(device())).isFalse();
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-04 未注册协议的设备必须被拒绝")
    void unknownProtocolMustBeRejected() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(), List.of(new StubSpecProvider(true)), properties);
        DeviceSpec unknown = new DeviceSpec("d2", "未知", ProtocolCode.of("absent"), CONNECTION_ID, "",
                Duration.ZERO, Map.of());
        assertThat(lifecycle.bind(unknown)).isFalse();
    }

    @Test
    @DisplayName("LIFE-05 无 DeviceRegistry 时启动钩子必须安全返回")
    void applicationReadyWithoutRegistryMustBeSafe() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry, List.of(),
                List.of(), properties);
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
                List.of(new StubSpecProvider(true)), disabled);
        lifecycle.onApplicationEvent(null);
        assertThat(lifecycle.sessionCount()).isZero();
    }

    @Test
    @DisplayName("LIFE-07 探测必须委托给适配器并返回结果")
    void probeMustDelegateToAdapter() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry, List.of(),
                List.of(), properties);
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
                List.of(new StubSpecProvider(true)), properties);
        lifecycle.onApplicationEvent(null);
        assertThat(lifecycle.sessionCount()).as("第二个来源的设备仍应被接入").isEqualTo(1);
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
            listener.accept(new DeviceChange(ChangeType.ADD, device(), 1L));
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

        @Override
        public ProtocolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
            StubConnection connection = new StubConnection(spec.connectionId());
            lastConnection = connection;
            return CompletableFuture.completedFuture(connection);
        }

        @Override
        public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
                AdapterContext context) {
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
