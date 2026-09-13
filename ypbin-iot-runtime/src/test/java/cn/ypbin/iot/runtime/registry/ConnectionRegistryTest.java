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
package cn.ypbin.iot.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ConnectionRegistry} 并发行为契约测试。
 *
 * <p>本类锁定四条行为（对应 DESIGN §4.2 的 CR-1 ~ CR-4）。它们是「连接复用」这条
 * 框架能力的全部保证，也是最容易写出并发 bug 的地方：</p>
 * <ol>
 *   <li><b>CR-1 单飞建链</b>：N 线程并发 acquire 同一 connectionId，适配器 open 只被调用一次；</li>
 *   <li><b>CR-2 引用计数与竞态释放</b>：引用归零后进入空闲回收，不产生无引用的活跃连接；</li>
 *   <li><b>CR-3 空闲回收</b>：空闲超时到达后连接被关闭，之后可正常重建；</li>
 *   <li><b>CR-4 失败传播</b>：建链失败时所有等待者收到同一个异常，且不残留状态。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class ConnectionRegistryTest {

    private static final ProtocolCode CODE = ProtocolCode.of("tck");

    private static final String CONNECTION_ID = "conn-1";

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(2, 256);
        context = new DefaultAdapterContext(CODE, DefaultAdapterSettings.defaults(), new NoopEgress(),
                scheduler, NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 16);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    @DisplayName("CR-1 200 线程并发 acquire 同一链路，适配器 open 只被调用一次")
    void singleFlightMustOpenOnlyOnce() throws Exception {
        int threads = 200;
        CountingAdapter adapter = new CountingAdapter(Duration.ofMillis(50), null);
        ConnectionRegistry registry = new ConnectionRegistry(Duration.ofMinutes(5), 1000, scheduler,
                Clock.systemUTC());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<ConnectionRegistry.ConnectionHandle>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(start);
                    return registry.acquire(adapter, spec(CONNECTION_ID), context)
                            .toCompletableFuture().join();
                }, pool));
            }
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .orTimeout(10, TimeUnit.SECONDS).join();

            assertThat(adapter.openInvocations()).as("单飞失败：open 被调用多次").isEqualTo(1);
            assertThat(registry.activeCount()).as("注册表中应只有一条链路").isEqualTo(1);
            futures.forEach(future -> assertThat(future.join().connectionId()).isEqualTo(CONNECTION_ID));
        } finally {
            futures.forEach(future -> future.join().release());
            pool.shutdownNow();
            registry.close();
        }
    }

    @Test
    @DisplayName("CR-2/CR-3 引用归零后进入空闲回收，超时到达后链路被关闭")
    void idleReclaimMustCloseConnection() {
        CountingAdapter adapter = new CountingAdapter(Duration.ZERO, null);
        ConnectionRegistry registry = new ConnectionRegistry(Duration.ofMillis(80), 1000, scheduler,
                Clock.systemUTC());
        try {
            ConnectionRegistry.ConnectionHandle handle = registry.acquire(adapter, spec(CONNECTION_ID), context)
                    .toCompletableFuture().join();
            TestConnection connection = adapter.lastConnection();
            assertThat(connection.closed()).as("持有引用期间不得关闭").isFalse();

            handle.release();

            awaitUntil(() -> connection.closed(), Duration.ofSeconds(3));
            assertThat(connection.closed()).as("空闲超时后链路应被回收").isTrue();
            assertThat(registry.activeCount()).as("回收后注册表应为空").isZero();
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("CR-3 空闲回收后可正常重建链路")
    void connectionMustBeRecreatableAfterReclaim() {
        CountingAdapter adapter = new CountingAdapter(Duration.ZERO, null);
        ConnectionRegistry registry = new ConnectionRegistry(Duration.ofMillis(60), 1000, scheduler,
                Clock.systemUTC());
        try {
            registry.acquire(adapter, spec(CONNECTION_ID), context).toCompletableFuture().join().release();
            awaitUntil(() -> registry.activeCount() == 0, Duration.ofSeconds(3));

            ConnectionRegistry.ConnectionHandle second = registry.acquire(adapter, spec(CONNECTION_ID), context)
                    .toCompletableFuture().join();
            assertThat(second.connectionId()).isEqualTo(CONNECTION_ID);
            assertThat(adapter.openInvocations()).as("回收后重建应触发第二次 open").isEqualTo(2);
            second.release();
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("CR-4 建链失败时所有等待者收到同一个异常，且不残留状态")
    void openFailureMustPropagateToAllWaiters() throws Exception {
        int threads = 32;
        RuntimeException failure = new ConnectionException(CONNECTION_ID, "iot.test.failure");
        CountingAdapter adapter = new CountingAdapter(Duration.ofMillis(50), failure);
        ConnectionRegistry registry = new ConnectionRegistry(Duration.ofMinutes(5), 1000, scheduler,
                Clock.systemUTC());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<CompletableFuture<Throwable>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(start);
                    return registry.acquire(adapter, spec(CONNECTION_ID), context)
                            .toCompletableFuture()
                            .handle((handle, error) -> error);
                }, pool).thenCompose(stage -> stage));
            }
            start.countDown();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                    .orTimeout(10, TimeUnit.SECONDS).join();

            assertThat(adapter.openInvocations()).as("失败也应只 open 一次").isEqualTo(1);
            for (CompletableFuture<Throwable> future : futures) {
                Throwable error = future.join();
                assertThat(error)
                        .as("每个等待者都必须收到原始 ConnectionException（不得是 CompletionException 包装）")
                        .isInstanceOf(ConnectionException.class);
            }
            assertThat(registry.activeCount()).as("失败后不得残留注册项").isZero();
        } finally {
            pool.shutdownNow();
            registry.close();
        }
    }

    @Test
    @DisplayName("CR-2 重复 release 不得把引用计数减成负数")
    void duplicateReleaseMustBeIdempotent() {
        CountingAdapter adapter = new CountingAdapter(Duration.ZERO, null);
        ConnectionRegistry registry = new ConnectionRegistry(Duration.ofMillis(120), 1000, scheduler,
                Clock.systemUTC());
        try {
            ConnectionRegistry.ConnectionHandle first = registry.acquire(adapter, spec(CONNECTION_ID), context)
                    .toCompletableFuture().join();
            ConnectionRegistry.ConnectionHandle second = registry.acquire(adapter, spec(CONNECTION_ID), context)
                    .toCompletableFuture().join();
            assertThat(adapter.openInvocations()).as("第二次 acquire 应复用同一链路").isEqualTo(1);

            first.release();
            first.release();
            TestConnection connection = adapter.lastConnection();
            assertThat(connection.closed()).as("仍有第二个引用，不得关闭").isFalse();

            second.release();
            awaitUntil(() -> connection.closed(), Duration.ofSeconds(3));
            assertThat(connection.closed()).isTrue();
        } finally {
            registry.close();
        }
    }

    private static ConnectionSpec spec(String connectionId) {
        return new ConnectionSpec(connectionId, CODE, Endpoint.of("tcp://127.0.0.1:1"),
                Duration.ofSeconds(2), Duration.ofSeconds(2), null, null, Map.of());
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 无操作数据出口：{@link cn.ypbin.iot.core.context.DataEgress} 有两个方法，不是函数式接口。
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

    /**
     * 计数适配器：记录 open 调用次数，并可注入建链失败。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class CountingAdapter implements ProtocolAdapter {

        private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
                .code(CODE)
                .name("TCK")
                .transport("TCP")
                .capabilities(ProtocolCapability.WRITE)
                .build();

        private final AtomicInteger openInvocations = new AtomicInteger();

        private final Duration openDelay;

        private final RuntimeException failure;

        private volatile TestConnection lastConnection;

        private CountingAdapter(Duration openDelay, RuntimeException failure) {
            this.openDelay = openDelay;
            this.failure = failure;
        }

        @Override
        public ProtocolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
            openInvocations.incrementAndGet();
            return CompletableFuture.supplyAsync(() -> {
                if (openDelay != null && !openDelay.isZero()) {
                    try {
                        Thread.sleep(openDelay.toMillis());
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    }
                }
                if (failure != null) {
                    throw failure;
                }
                TestConnection connection = new TestConnection(spec.connectionId());
                lastConnection = connection;
                return connection;
            });
        }

        @Override
        public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.completedFuture(ProbeResult.reachable(DESCRIPTOR, Map.of()));
        }

        private int openInvocations() {
            return openInvocations.get();
        }

        private TestConnection lastConnection() {
            return lastConnection;
        }
    }

    /**
     * 测试用链路实现。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class TestConnection implements ProtocolConnection {

        private final String connectionId;

        private final Instant openedAt = Instant.now();

        private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private TestConnection(String connectionId) {
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
            return openedAt;
        }

        @Override
        public DeviceSession session() {
            throw new UnsupportedOperationException("not used by this test");
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

        private boolean closed() {
            return closed.get();
        }
    }
}
