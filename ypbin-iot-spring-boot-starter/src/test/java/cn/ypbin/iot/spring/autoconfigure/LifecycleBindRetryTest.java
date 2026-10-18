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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceEventType;
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
import cn.ypbin.iot.core.spi.DeviceEventListener;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * {@link IotLifecycle} 设备级 bind 退避重试与 revision 幂等测试（issue #38 验收项 1）。
 *
 * <p>覆盖三条验收标准：</p>
 * <ol>
 *   <li><b>BRT-01~03</b>：bind 持续失败 ⇒ 按退避重试，且<b>每轮不超过上限</b>；</li>
 *   <li><b>BRT-04</b>：重试耗尽 ⇒ <b>WARN 带完整堆栈</b> + 通过 {@link DeviceEventListener} 通知宿主；</li>
 *   <li><b>BRT-05~06</b>：revision 回退 ⇒ 被丢弃且<b>有非 debug 级日志</b>（级别可配）。</li>
 * </ol>
 *
 * <p><b>为什么用日志断言而不是只看返回值</b>：issue #38 的病根正是「失效形态是静默」——
 * 丢弃只有 debug、bind 失败只有一行 WARN。因此这里直接抓 logback 事件，
 * 断言「级别与堆栈」这两个宿主真正能看见的东西。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class LifecycleBindRetryTest {

    private static final ProtocolCode CODE = ProtocolCode.of("stub");

    private static final String CONNECTION_ID = "conn-1";

    private DefaultTaskScheduler scheduler;

    private ConnectionRegistry connectionRegistry;

    private StubAdapter adapter;

    private AdapterRegistry adapterRegistry;

    private IotProperties properties;

    private ListAppender<ILoggingEvent> appender;

    private Logger lifecycleLogger;

    private Logger retrierLogger;

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

        lifecycleLogger = (Logger) LoggerFactory.getLogger(IotLifecycle.class);
        retrierLogger = (Logger) LoggerFactory.getLogger(DeviceBindRetrier.class);
        appender = new ListAppender<>();
        appender.start();
        // 「重试耗尽」的 WARN 由重试器打（带堆栈），生命周期再补一条「已通知宿主」的 WARN：
        // 两处都要抓，否则断言会漏掉真正带堆栈的那一条
        lifecycleLogger.addAppender(appender);
        retrierLogger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        lifecycleLogger.detachAppender(appender);
        retrierLogger.detachAppender(appender);
        appender.stop();
        adapterRegistry.close();
        connectionRegistry.close();
        scheduler.close();
    }

    @Test
    @DisplayName("BRT-01 bind 持续失败必须按退避重试（不是只试一次就放弃）")
    void persistentBindFailureMustBeRetriedWithBackoff() {
        adapter.failBind = true;
        IotProperties fast = propertiesWithRetry(Duration.ofMillis(30), Duration.ofMillis(60), 2, 20);
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider()), fast, scheduler, List.of(), Clock.systemUTC());
        try {
            assertThat(lifecycle.bind(device("d1")))
                    .as("首次 bind 必然失败（适配器被设为持续失败）")
                    .isFalse();
            assertThat(lifecycle.bindRetryPendingCount())
                    .as("瞬时失败必须进入重试队列，否则设备永久离线（issue #38 的病根）")
                    .isEqualTo(1);
            // 30ms → 60ms(封顶) → 60ms…：等足够久观察至少 3 次重试
            awaitUntil(() -> lifecycle.bindRetryAttempts() >= 3, Duration.ofSeconds(10));
            assertThat(lifecycle.bindRetryAttempts())
                    .as("持续失败必须反复重试（每轮 1 台设备、退避 30→60ms）")
                    .isGreaterThanOrEqualTo(3);
            assertThat(lifecycle.sessionCount()).isZero();
        } finally {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("BRT-02 退避必须有上限且按设备独立（不同设备互不拖累）")
    void backoffMustBeCappedAndPerDevice() {
        adapter.failBind = true;
        IotProperties fast = propertiesWithRetry(Duration.ofMillis(20), Duration.ofMillis(40), 4, 20);
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok(), "d1", "d2")),
                List.of(new StubSpecProvider()), fast, scheduler, List.of(), Clock.systemUTC());
        try {
            lifecycle.bind(device("d1"));
            lifecycle.bind(device("d2"));
            assertThat(lifecycle.bindRetryPendingCount()).isEqualTo(2);
            awaitUntil(() -> lifecycle.bindRetryAttempts() >= 4, Duration.ofSeconds(10));
            // 两台设备各自独立退避：总量至少是「每台 2 次」
            assertThat(lifecycle.bindRetryPendingCount())
                    .as("退避未耗尽前两台设备都必须留在队列中")
                    .isEqualTo(2);
        } finally {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("BRT-03 每轮重试设备数不得超过 rebind-max-per-cycle（防整批离线打爆对端）")
    void retriesPerCycleMustBeCapped() {
        AtomicInteger concurrentBind = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        adapter.bindHook = () -> {
            int now = concurrentBind.incrementAndGet();
            maxConcurrent.accumulateAndGet(now, Math::max);
            // 故意让 bind 变慢，使同一轮内的设备真正重叠
            sleep(20L);
            concurrentBind.decrementAndGet();
        };
        adapter.failBind = true;
        IotProperties fast = propertiesWithRetry(Duration.ofMillis(10), Duration.ofMillis(10), 2, 20);
        DeviceBindRetrier retrier = new DeviceBindRetrier(scheduler, fast.devices(),
                device -> {
                    adapter.bindHook.run();
                    return DeviceBindRetrier.Outcome.retryable(null);
                },
                (device, attempts, cause) -> {
                    // 本用例只关心配额
                });
        try {
            for (int index = 0; index < 6; index++) {
                retrier.schedule(device("d" + index));
            }
            awaitUntil(() -> retrier.sweepCount() >= 2, Duration.ofSeconds(10));
            assertThat(retrier.maxAttemptsPerSweep())
                    .as("单轮最多处理 %d 台设备，实测 %d", 2, retrier.maxAttemptsPerSweep())
                    .isLessThanOrEqualTo(2);
        } finally {
            retrier.close();
        }
    }

    @Test
    @DisplayName("BRT-04 重试耗尽 ⇒ WARN 带完整堆栈 + 通过 DeviceEventListener 通知宿主")
    void exhaustedRetriesMustWarnWithStackAndNotifyListener() {
        adapter.failBind = true;
        IotProperties fast = propertiesWithRetry(Duration.ofMillis(10), Duration.ofMillis(10), 1, 2);
        List<DeviceEvent> notified = new CopyOnWriteArrayList<>();
        DeviceEventListener listener = notified::add;
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(new StubDeviceRegistry(ValidationResult.ok())),
                List.of(new StubSpecProvider()), fast, scheduler, List.of(listener), Clock.systemUTC());
        try {
            lifecycle.bind(device("d1"));
            awaitUntil(() -> lifecycle.bindRetryExhausted() >= 1, Duration.ofSeconds(10));
            assertThat(lifecycle.bindRetryExhausted()).isEqualTo(1);
            assertThat(lifecycle.bindRetryPendingCount())
                    .as("耗尽后必须停止重试（无限重试会刷屏且没有意义）")
                    .isZero();

            List<ILoggingEvent> exhaustLogs = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("EXHAUSTED")
                            || event.getFormattedMessage().contains("retry exhausted"))
                    .toList();
            assertThat(exhaustLogs)
                    .as("重试耗尽必须留下 WARN 日志（此前只有一行 debug/一行普通 WARN，现场看不出来）")
                    .isNotEmpty();
            assertThat(exhaustLogs.get(exhaustLogs.size() - 1).getLevel()).isEqualTo(Level.WARN);
            assertThat(exhaustLogs.get(exhaustLogs.size() - 1).getThrowableProxy())
                    .as("WARN 必须带完整堆栈，否则现场无法判断是超时、拒绝还是协议错误")
                    .isNotNull();

            assertThat(notified)
                    .as("耗尽必须通知宿主（框架不落库，宿主只能靠 DeviceEventListener 更新状态/告警）")
                    .hasSize(1);
            assertThat(notified.get(0).type()).isEqualTo(DeviceEventType.CONNECT_FAILED);
            assertThat(notified.get(0).deviceId()).isEqualTo("d1");
            assertThat(notified.get(0).cause())
                    .as("事件必须携带最后一次失败原因，宿主据此告警分类")
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("BRT-05 revision 回退被丢弃，且日志级别非 debug（默认 INFO）")
    void staleRevisionMustBeLoggedAboveDebug() {
        StubDeviceRegistry registry = new StubDeviceRegistry(ValidationResult.ok());
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(registry), List.of(new StubSpecProvider()), properties, scheduler,
                List.of(), Clock.systemUTC());
        try {
            lifecycle.onApplicationEvent(null);
            assertThat(registry.listener).isNotNull();
            // 先应用 revision=5，再投递 revision=4（乱序/重复）：必须丢弃
            registry.listener.accept(new DeviceChange(ChangeType.UPDATE, device("d1"), 5L));
            appender.list.clear();
            registry.listener.accept(new DeviceChange(ChangeType.UPDATE, device("d1"), 4L));

            List<ILoggingEvent> stale = appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("stale device change ignored"))
                    .toList();
            assertThat(stale)
                    .as("过期 revision 必须有日志：宿主漏递增 revision 时这是唯一的现场线索")
                    .isNotEmpty();
            assertThat(stale.get(0).getLevel())
                    .as("默认不得是 DEBUG（debug 在生产上等于没有日志）")
                    .isEqualTo(Level.INFO);
            assertThat(stale.get(0).getFormattedMessage())
                    .as("日志必须给出修复方向（改用框架分配器）")
                    .contains("DeviceRevisionAllocator");
        } finally {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("BRT-06 过期 revision 日志级别可配置（WARN / ERROR / DEBUG）")
    void staleRevisionLogLevelMustBeConfigurable() {
        StubDeviceRegistry registry = new StubDeviceRegistry(ValidationResult.ok());
        IotProperties warnProperties = new IotProperties(null, null, null, null,
                new IotProperties.DeviceProperties(null, null, null, null, null, null, null, LogLevel.WARN),
                null);
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry,
                List.of(registry), List.of(new StubSpecProvider()), warnProperties, scheduler,
                List.of(), Clock.systemUTC());
        try {
            lifecycle.onApplicationEvent(null);
            registry.listener.accept(new DeviceChange(ChangeType.UPDATE, device("d1"), 9L));
            appender.list.clear();
            registry.listener.accept(new DeviceChange(ChangeType.ADD, device("d1"), 9L));
            assertThat(appender.list.stream()
                    .filter(event -> event.getFormattedMessage().contains("stale device change ignored"))
                    .map(ILoggingEvent::getLevel))
                    .as("stale-revision-log-level=WARN 必须真的按 WARN 输出")
                    .containsExactly(Level.WARN);
        } finally {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("BRT-07 显式 unbind 必须取消待重试，不得把设备重新绑回来")
    void unbindMustCancelPendingRetry() {
        adapter.failBind = true;
        IotProperties fast = propertiesWithRetry(Duration.ofMillis(30), Duration.ofMillis(60), 2, 20);
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry, List.of(),
                List.of(new StubSpecProvider()), fast, scheduler, List.of(), Clock.systemUTC());
        try {
            lifecycle.bind(device("d1"));
            assertThat(lifecycle.bindRetryPendingCount()).isEqualTo(1);
            lifecycle.unbind("d1");
            assertThat(lifecycle.bindRetryPendingCount())
                    .as("解绑后仍留在重试队列会被重新绑回来（与 REMOVE 语义冲突）")
                    .isZero();
            int attempts = lifecycle.bindRetryAttempts();
            sleep(200L);
            assertThat(lifecycle.bindRetryAttempts())
                    .as("取消后不得再产生尝试（取消前 %d，之后 %d）", attempts, lifecycle.bindRetryAttempts())
                    .isEqualTo(attempts);
        } finally {
            lifecycle.close();
        }
    }

    @Test
    @DisplayName("BRT-08 永久失败（无适配器）不得进入重试队列（避免无意义重试）")
    void permanentFailureMustNotBeRetried() {
        IotLifecycle lifecycle = new IotLifecycle(adapterRegistry, connectionRegistry, List.of(),
                List.of(new StubSpecProvider()), properties, scheduler, List.of(), Clock.systemUTC());
        DeviceSpec absent = new DeviceSpec("d2", "未知", ProtocolCode.of("absent"), CONNECTION_ID, "",
                Duration.ZERO, Map.of());
        try {
            assertThat(lifecycle.bind(absent)).isFalse();
            assertThat(lifecycle.bindRetryPendingCount())
                    .as("配置错误（协议未注册）换时间再试也不会成功，不应占用重试队列")
                    .isZero();
        } finally {
            lifecycle.close();
        }
    }

    private IotProperties propertiesWithRetry(Duration initial, Duration max, int maxPerCycle,
            int maxAttempts) {
        return new IotProperties(null, null, null, null,
                new IotProperties.DeviceProperties(null, null, null, initial, max, maxPerCycle,
                        maxAttempts, LogLevel.INFO),
                null);
    }

    private static DeviceSpec device(String deviceId) {
        return new DeviceSpec(deviceId, deviceId, CODE, CONNECTION_ID, "",
                Duration.ZERO, Map.of());
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
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

    /**
     * 设备来源桩：可配置设备清单，记录 listener 供测试显式投递变更。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class StubDeviceRegistry implements DeviceRegistry {

        private final ValidationResult validation;

        private final List<String> deviceIds;

        private Consumer<DeviceChange> listener;

        private StubDeviceRegistry(ValidationResult validation, String... deviceIds) {
            this.validation = validation;
            this.deviceIds = deviceIds.length == 0 ? List.of("d1") : List.of(deviceIds);
        }

        @Override
        public List<DeviceSpec> loadAll() {
            return deviceIds.stream().map(LifecycleBindRetryTest::device).toList();
        }

        @Override
        public ValidationResult validate(DeviceSpec device) {
            return validation;
        }

        @Override
        public void addChangeListener(Consumer<DeviceChange> listener) {
            this.listener = listener;
        }
    }

    /**
     * 链路规格来源桩。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class StubSpecProvider implements ConnectionSpecProvider {

        @Override
        public Optional<ConnectionSpec> find(String connectionId) {
            if (!CONNECTION_ID.equals(connectionId)) {
                return Optional.empty();
            }
            return Optional.of(new ConnectionSpec(CONNECTION_ID, CODE, Endpoint.of("tcp://127.0.0.1:1"),
                    Duration.ofSeconds(1), Duration.ofSeconds(1), null, null, Map.of()));
        }
    }

    /**
     * 适配器桩：可切换 bind 失败、可插入 bind 钩子（统计并发/耗时）。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class StubAdapter implements ProtocolAdapter {

        private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
                .code(CODE)
                .name("Stub")
                .transport("TCP")
                .capabilities(ProtocolCapability.READ)
                .build();

        private volatile boolean failBind;

        private volatile Runnable bindHook = () -> { };

        @Override
        public ProtocolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.completedFuture(new StubConnection(spec.connectionId()));
        }

        @Override
        public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
                AdapterContext context) {
            bindHook.run();
            if (failBind) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("bind failure by design (device offline)"));
            }
            return CompletableFuture.completedFuture(new StubSession(device, connection));
        }

        @Override
        public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.completedFuture(ProbeResult.reachable(DESCRIPTOR, Map.of()));
        }
    }

    /**
     * 链路桩。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class StubConnection implements ProtocolConnection {

        private final String connectionId;

        private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private StubConnection(String connectionId) {
            this.connectionId = connectionId;
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
            return null;
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
    }

    /**
     * 会话桩。
     *
     * @author wenbin
     * @since 2026-10-18
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
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-10-18
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
