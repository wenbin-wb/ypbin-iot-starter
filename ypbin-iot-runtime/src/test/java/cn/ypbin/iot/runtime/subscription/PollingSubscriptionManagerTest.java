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
package cn.ypbin.iot.runtime.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link PollingSubscriptionManager} 测试。
 *
 * <p>重点验证三件事：① 数据真的按周期推送到出口；② <b>上一轮未结束时不得叠加下一轮</b>
 * （否则慢设备上请求会堆积把链路压垮）；③ 单轮失败不能让订阅死掉。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class PollingSubscriptionManagerTest {

    private static final ProtocolCode CODE = ProtocolCode.of("polling");

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private RecordingEgress egress;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(2, 64);
        egress = new RecordingEgress();
        context = new DefaultAdapterContext(CODE, DefaultAdapterSettings.defaults(), egress, scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 16);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    @DisplayName("POLL-01 订阅必须按周期经 egress 推送数据")
    void subscriptionMustDeliverThroughEgress() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession session = new StubSession();
        SubscriptionHandle handle = manager.subscribe(session, request(Duration.ofMillis(20)), null);
        try {
            awaitUntil(() -> handle.deliveredCount() >= 2, Duration.ofSeconds(3));
            assertThat(handle.deliveredCount()).isPositive();
            assertThat(egress.pointCount()).as("未传 listener 时必须走 egress").isPositive();
            assertThat(manager.activeCount()).isEqualTo(1);
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("POLL-02 传入 listener 时不得再走 egress（避免重复投递）")
    void listenerMustSuppressEgress() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession session = new StubSession();
        List<PointValue> received = new CopyOnWriteArrayList<>();
        DataListener listener = received::add;
        manager.subscribe(session, request(Duration.ofMillis(20)), listener);
        try {
            awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(3));
            assertThat(received).isNotEmpty();
            assertThat(egress.pointCount())
                    .as("传了 listener 就不该再投一次到 egress")
                    .isZero();
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("POLL-03 上一轮未结束时不得叠加下一轮（慢设备不堆积请求）")
    void pollingMustNotOverlap() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession slow = new StubSession();
        slow.stallMillis = 200L;
        manager.subscribe(slow, request(Duration.ofMillis(10)), null);
        try {
            sleepQuietly(900L);
            // 周期 10ms、每轮耗时 200ms → 900ms 内最多完成约 4 轮；若叠加则会是数十轮
            assertThat(slow.readCount.get())
                    .as("轮询发生了叠加：%d 次读远超「一轮结束再排下一轮」的上限", slow.readCount.get())
                    .isLessThanOrEqualTo(8);
            assertThat(slow.concurrentReads.get())
                    .as("不得出现并发读")
                    .isLessThanOrEqualTo(1);
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("POLL-04 单轮读失败不得让订阅死掉")
    void readFailureMustNotKillSubscription() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession session = new StubSession();
        session.failFirstReads = 1;
        SubscriptionHandle handle = manager.subscribe(session, request(Duration.ofMillis(20)), null);
        try {
            // 首次失败后按退避重试（5s 上限内），本用例只断言订阅仍然 active 且未抛出
            sleepQuietly(300L);
            assertThat(handle.active()).as("单轮失败不得让订阅失效").isTrue();
            assertThat(session.readCount.get()).isGreaterThanOrEqualTo(1);
        } finally {
            manager.close();
        }
    }

    @Test
    @DisplayName("POLL-05 取消订阅后必须停止推送，且取消幂等")
    void unsubscribeMustStopAndBeIdempotent() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession session = new StubSession();
        SubscriptionHandle handle = manager.subscribe(session, request(Duration.ofMillis(20)), null);
        awaitUntil(() -> handle.deliveredCount() > 0, Duration.ofSeconds(3));
        manager.unsubscribe(handle);
        manager.unsubscribe(handle);
        assertThat(handle.active()).isFalse();
        long frozen = handle.deliveredCount();
        sleepQuietly(150L);
        assertThat(handle.deliveredCount()).isEqualTo(frozen);
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    @DisplayName("POLL-06 cancelAll 必须按会话标识清空订阅")
    void cancelAllMustScopeBySession() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession first = new StubSession();
        first.sessionId = "s-1";
        StubSession second = new StubSession();
        second.sessionId = "s-2";
        manager.subscribe(first, request(Duration.ofMillis(20)), null);
        SubscriptionHandle keep = manager.subscribe(second, request(Duration.ofMillis(20)), null);
        assertThat(manager.activeCount()).isEqualTo(2);
        manager.cancelAll("s-1");
        assertThat(manager.activeCount()).isEqualTo(1);
        assertThat(keep.active()).isTrue();
        manager.close();
        assertThat(manager.activeCount()).isZero();
    }

    @Test
    @DisplayName("POLL-07 空读结果不得产生投递")
    void emptyResultMustNotDeliver() {
        PollingSubscriptionManager manager = new PollingSubscriptionManager(scheduler, context);
        StubSession session = new StubSession();
        session.emptyResult = true;
        SubscriptionHandle handle = manager.subscribe(session, request(Duration.ofMillis(10)), null);
        try {
            sleepQuietly(200L);
            assertThat(handle.deliveredCount()).isZero();
            assertThat(egress.pointCount()).isZero();
        } finally {
            manager.close();
        }
    }

    private static SubscribeRequest request(Duration interval) {
        return new SubscribeRequest(List.of(PointAddress.of("p")), interval, interval, null, Map.of());
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(10L);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 记录型出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class RecordingEgress implements DataEgress {

        private final AtomicInteger points = new AtomicInteger();

        @Override
        public void emit(DataBatch batch) {
            points.addAndGet(batch.size());
        }

        @Override
        public void emit(DeviceEvent event) {
            // 本测试不关心事件
        }

        private int pointCount() {
            return points.get();
        }
    }

    /**
     * 会话桩：可注入慢响应与首轮失败。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubSession implements DeviceSession {

        private final AtomicInteger readCount = new AtomicInteger();

        private final AtomicInteger concurrentReads = new AtomicInteger();

        private final AtomicBoolean closed = new AtomicBoolean(false);

        private volatile String sessionId = "poll-session";

        private volatile long stallMillis;

        private volatile int failFirstReads;

        private volatile boolean emptyResult;

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public DeviceSpec device() {
            return new DeviceSpec("d1", "设备", CODE, "c1", "", Duration.ZERO, Map.of());
        }

        @Override
        public String connectionId() {
            return "c1";
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
            readCount.incrementAndGet();
            int concurrent = concurrentReads.incrementAndGet();
            if (failFirstReads > 0) {
                failFirstReads--;
                concurrentReads.decrementAndGet();
                return CompletableFuture.failedFuture(new IllegalStateException("read failure by design"));
            }
            if (emptyResult) {
                concurrentReads.decrementAndGet();
                return CompletableFuture.completedFuture(new ReadResult(List.of(), Duration.ZERO));
            }
            if (stallMillis <= 0L) {
                concurrentReads.decrementAndGet();
                return CompletableFuture.completedFuture(result());
            }
            CompletableFuture<ReadResult> future = new CompletableFuture<>();
            Thread.ofVirtual().start(() -> {
                sleepQuietly(stallMillis);
                concurrentReads.decrementAndGet();
                future.complete(result());
            });
            if (concurrent > 1) {
                throw new IllegalStateException("overlapping reads detected");
            }
            return future;
        }

        private static ReadResult result() {
            return new ReadResult(List.of(PointValue.good(PointAddress.of("p"), 1, Instant.now())),
                    Duration.ZERO);
        }

        @Override
        public CompletionStage<WriteResult> write(WriteRequest request) {
            return CompletableFuture.completedFuture(new WriteResult(List.of(), Duration.ZERO));
        }

        @Override
        public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
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
            return CompletableFuture.completedFuture(null);
        }
    }

}
