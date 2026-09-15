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
package cn.ypbin.iot.runtime.egress;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceEventType;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.core.spi.DeviceEventListener;
import cn.ypbin.iot.runtime.delivery.BoundedDeliveryDispatcher;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link EgressRouter} 行为测试。
 *
 * <p>关注四件事：数据完整送达、单 Sink 故障隔离、队列溢出可见（不静默丢弃）、
 * 关闭时 flush 剩余数据。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class EgressRouterTest {

    @Test
    @DisplayName("EGRESS-08 设备事件必须卸载出调用线程（原实现在调用者线程同步执行宿主回调）")
    void deviceEventMustBeOffloadedToDispatcher() throws Exception {
        String caller = Thread.currentThread().getName();
        List<String> callbackThreads = new CopyOnWriteArrayList<>();
        CountDownLatch delivered = new CountDownLatch(1);
        DefaultTaskScheduler taskScheduler = new DefaultTaskScheduler(2, 64);
        EgressRouter router = new EgressRouter(16, 1000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(),
                List.of(event -> {
                    callbackThreads.add(Thread.currentThread().getName());
                    delivered.countDown();
                }), Clock.systemUTC(), new BoundedDeliveryDispatcher(taskScheduler, 64));
        try {
            router.emit(new DeviceEvent("d1", CODE, DeviceEventType.DEVICE_ONLINE, null, null,
                    Clock.systemUTC().instant()));
            assertThat(delivered.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(callbackThreads).hasSize(1);
            assertThat(callbackThreads.get(0))
                    .as("宿主事件回调不得在调用线程上执行（调用线程=%s）", caller)
                    .isNotEqualTo(caller);
        } finally {
            router.close();
            taskScheduler.close();
        }
    }

    @Test
    @DisplayName("EGRESS-09 BLOCK 策略不得在调用线程上阻塞（原实现会 sleep 到 blockTimeout）")
    void blockPolicyMustNotBlockCaller() {
        DefaultTaskScheduler taskScheduler = new DefaultTaskScheduler(2, 64);
        // 容量刚好装满，让后续 emit 必须走「等待有位置」的分支
        EgressRouter router = new EgressRouter(10, 10, EgressOverflowPolicy.BLOCK,
                Duration.ofSeconds(2), Duration.ofMillis(20), List.of(), List.of(), Clock.systemUTC(),
                new BoundedDeliveryDispatcher(taskScheduler, 64));
        try {
            router.emit(batch("d1", 10));
            long started = System.nanoTime();
            router.emit(batch("d1", 10));
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
            assertThat(elapsedMillis)
                    .as("BLOCK 的等待必须卸载出调用线程（实测调用线程阻塞 %d ms）", elapsedMillis)
                    .isLessThan(500L);
        } finally {
            router.close();
            taskScheduler.close();
        }
    }

    private static final ProtocolCode CODE = ProtocolCode.of("tck");

    @Test
    @DisplayName("EGRESS-01 数据应完整送达全部 Sink")
    void dataMustReachAllSinks() {
        RecordingSink first = new RecordingSink("first");
        RecordingSink second = new RecordingSink("second");
        try (EgressRouter router = new EgressRouter(100, 10_000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(100), Duration.ofMillis(20), List.of(first, second), List.of(), Clock.systemUTC())) {
            router.emit(batch("d1", 3));
            awaitPoints(router, 3);
            assertThat(first.points()).isEqualTo(3);
            assertThat(second.points()).isEqualTo(3);
            assertThat(router.droppedPoints()).isZero();
        }
    }

    @Test
    @DisplayName("EGRESS-02 单个 Sink 抛异常不影响其余 Sink")
    void failingSinkMustNotBlockOthers() {
        RecordingSink healthy = new RecordingSink("healthy");
        try (EgressRouter router = new EgressRouter(100, 10_000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(100), Duration.ofMillis(20), List.of(new FailingSink(), healthy), List.of(),
                Clock.systemUTC())) {
            router.emit(batch("d1", 2));
            awaitPoints(router, 2);
            assertThat(healthy.points()).as("健康 Sink 必须仍然收到数据").isEqualTo(2);
            assertThat(router.sinkFailures()).as("失败次数必须被计数").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("EGRESS-03 队列溢出必须丢弃并计数，不得静默")
    void overflowMustBeCounted() {
        SlowSink slow = new SlowSink();
        try (EgressRouter router = new EgressRouter(4, 5, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(slow), List.of(), Clock.systemUTC())) {
            for (int i = 0; i < 20; i++) {
                router.emit(batch("d" + i, 5));
            }
            assertThat(router.droppedPoints())
                    .as("溢出必须产生可见的丢弃计数（禁静默丢弃）")
                    .isPositive();
        }
    }

    @Test
    @DisplayName("EGRESS-04 DROP_NEWEST 策略下新数据被拒并计数")
    void dropNewestMustRejectIncoming() {
        SlowSink slow = new SlowSink();
        try (EgressRouter router = new EgressRouter(4, 5, EgressOverflowPolicy.DROP_NEWEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(slow), List.of(), Clock.systemUTC())) {
            for (int i = 0; i < 10; i++) {
                router.emit(batch("d" + i, 5));
            }
            assertThat(router.droppedPoints()).isPositive();
        }
    }

    @Test
    @DisplayName("EGRESS-05 空批次不产生投递")
    void emptyBatchMustBeIgnored() {
        RecordingSink sink = new RecordingSink("sink");
        try (EgressRouter router = new EgressRouter(100, 1000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(sink), List.of(), Clock.systemUTC())) {
            router.emit(new DataBatch("d1", CODE, "c1", Instant.now(), List.of()));
            assertThat(sink.points()).isZero();
            assertThat(router.deliveredBatches()).isZero();
        }
    }

    @Test
    @DisplayName("EGRESS-06 设备事件必须送达监听器")
    void deviceEventMustReachListener() {
        RecordingListener listener = new RecordingListener();
        try (EgressRouter router = new EgressRouter(100, 1000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(), List.of(listener), Clock.systemUTC())) {
            router.emit(DeviceEvent.of("d1", CODE, DeviceEventType.DEVICE_ONLINE, "iot.test", Instant.now()));
            assertThat(listener.events()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("EGRESS-07 关闭时必须 flush 剩余数据")
    void closeMustFlushRemaining() {
        RecordingSink sink = new RecordingSink("sink");
        EgressRouter router = new EgressRouter(1000, 10_000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(sink), List.of(), Clock.systemUTC());
        router.emit(batch("d1", 4));
        router.close();
        assertThat(sink.points()).as("关闭后剩余数据必须被 flush，不能丢在内存里").isEqualTo(4);
    }

    @Test
    @DisplayName("EGRESS-10 同设备超批必须按 batchSize 拆分且不丢点、顺序不变（覆盖 coalesce 的溢出分支）")
    void sameDeviceOverflowMustSplitWithoutLoss() throws Exception {
        // coalesce 的溢出分支此前没有任何数据断言：唯一走到它的用例（EGRESS-09）没有 sink、
        // 只断言「调用线程不阻塞」。这条用例把等价性钉成数据断言。
        //
        // 关键：**必须让一次 drain 里出现同设备的多批**，否则根本不进溢出分支
        // （最初只发 2 批时就是这样 —— 变异测试证明那条写法抓不到丢点）。
        // 因此发 20 批；批次多到足以让多个 drain 窗口里都出现同设备多批。
        final int batches = 20;
        final int pointsPerBatch = 10;
        RecordingSink sink = new RecordingSink("sink");
        EgressRouter router = new EgressRouter(pointsPerBatch, 10_000, EgressOverflowPolicy.DROP_OLDEST,
                Duration.ofMillis(50), Duration.ofMillis(20), List.of(sink), List.of(),
                Clock.systemUTC());
        try {
            for (int index = 0; index < batches; index++) {
                router.emit(batch("d1", pointsPerBatch));
            }
            awaitPoints(sink, batches * pointsPerBatch);

            assertThat(sink.received)
                    .as("共 %d 点必须一个不少（溢出分支若在 rebuild 之前 clear，就会产出空批次而丢点）",
                            batches * pointsPerBatch)
                    .hasSize(batches * pointsPerBatch);
            List<Integer> expected = IntStream.range(0, batches)
                    .boxed()
                    .flatMap(ignored -> IntStream.range(0, pointsPerBatch).boxed())
                    .toList();
            assertThat(sink.received.stream().map(PointValue::value).toList())
                    .as("单设备内点位顺序必须保持（每批 0..9，批间按投递顺序）")
                    .containsExactlyElementsOf(expected);
        } finally {
            router.close();
        }
    }

    private static DataBatch batch(String deviceId, int points) {
        List<PointValue> values = IntStream.range(0, points)
                .mapToObj(index -> PointValue.good(PointAddress.of("p" + index), index, Instant.now()))
                .toList();
        return new DataBatch(deviceId, CODE, "c1", Instant.now(), values);
    }

    private static void awaitPoints(RecordingSink sink, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (sink.received.size() >= expected) {
                return;
            }
            sleep();
        }
    }

    private static void awaitPoints(EgressRouter router, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            if (router.deliveredBatches() > 0) {
                return;
            }
            sleep();
        }
    }

    private static void sleep() {
        try {
            Thread.sleep(5L);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 记录型 Sink。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class RecordingSink implements DataSink {

        private final String name;

        private final CopyOnWriteArrayList<PointValue> received = new CopyOnWriteArrayList<>();

        private RecordingSink(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void write(DataBatch batch) {
            received.addAll(batch.points());
        }

        private int points() {
            return received.size();
        }
    }

    /**
     * 固定失败的 Sink。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class FailingSink implements DataSink {

        @Override
        public String name() {
            return "failing";
        }

        @Override
        public void write(DataBatch batch) {
            throw new IllegalStateException("sink failure by design");
        }
    }

    /**
     * 慢 Sink：用于触发溢出。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class SlowSink implements DataSink {

        private final AtomicInteger writes = new AtomicInteger();

        @Override
        public String name() {
            return "slow";
        }

        @Override
        public void write(DataBatch batch) {
            writes.incrementAndGet();
            try {
                Thread.sleep(200L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * 记录型事件监听器。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class RecordingListener implements DeviceEventListener {

        private final AtomicInteger count = new AtomicInteger();

        @Override
        public void onEvent(DeviceEvent event) {
            count.incrementAndGet();
        }

        private int events() {
            return count.get();
        }
    }
}
