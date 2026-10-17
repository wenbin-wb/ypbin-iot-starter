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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link BoundedBatchEgressQueue} 行为测试。
 *
 * <p>关注四件事：<b>不丢元素</b>（按批切分与关闭 flush）、<b>不阻塞协议线程</b>
 * （队满即丢且可见）、<b>故障隔离</b>（Sink 抛异常不影响后续批次）、
 * <b>并发正确性</b>（多线程投放不丢不重）。</p>
 *
 * <p>所有等待都是「轮询 + 超时」而非固定 sleep 断言：本机 2 核、CI 更慢，
 * 固定 sleep 的计时断言必然产生随机红。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class BoundedBatchEgressQueueTest {

    /** 断言前等待异步结果的上限（宽松：2 核机器上调度抖动可能到秒级）。 */
    private static final Duration AWAIT_TIMEOUT = Duration.ofSeconds(10);

    /** 轮询间隔。 */
    private static final long POLL_MILLIS = 5L;

    /** 门闩等待上限：必须足够长，否则门闩超时会伪装成 Sink 失败。 */
    private static final long GATE_TIMEOUT_SECONDS = 30L;

    @Test
    @DisplayName("BEG-01 按 batchSize 切批消费，元素一个不少且顺序不变")
    void mustConsumeInBatchesWithoutLoss() {
        GatedSink sink = new GatedSink();
        RecordingMetrics metrics = new RecordingMetrics();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(64, 3, Duration.ofSeconds(1), sink, metrics);
        try {
            // 先用一个哨兵占住唯一的消费线程：这样后续 9 条会在队列里一次性排好，
            // 让切批边界成为**确定**的 3/3/3，而不是取决于线程调度刚好拍到几条。
            assertThat(queue.offer(-1)).isTrue();
            awaitUntil("消费线程必须进入 Sink", () -> sink.entered.getCount() == 0L);
            for (int index = 0; index < 9; index++) {
                assertThat(queue.offer(index)).as("容量足够时不得丢弃").isTrue();
            }
            sink.openGate();
            // 等待条件必须包含计数器：Sink 记账发生在 accept 内部，而计数器在 accept 返回后才加，
            // 只等元素个数会与计数断言产生竞态（实测会偶发红）
            awaitUntil("10 条数据必须全部送达并计数", () ->
                    sink.received.size() == 10 && queue.deliveredBatches() == 4);

            assertThat(sink.received)
                    .as("元素不得丢失也不得乱序")
                    .containsExactly(-1, 0, 1, 2, 3, 4, 5, 6, 7, 8);
            assertThat(sink.batches)
                    .as("哨兵之后必须严格按 batchSize=3 切批")
                    .containsExactly(List.of(-1), List.of(0, 1, 2), List.of(3, 4, 5), List.of(6, 7, 8));
            assertThat(queue.deliveredBatches()).isEqualTo(4);
            assertThat(queue.deliveredItems()).isEqualTo(10);
            assertThat(queue.droppedCount()).isZero();
            assertThat(queue.pending()).isZero();
            awaitUntil("队列深度必须被上报", () -> metrics.gauges.containsKey(
                    BoundedBatchEgressQueue.METRIC_PENDING));
        } finally {
            sink.openGate();
            queue.close();
        }
        assertThat(queue.deliveredItems()).as("关闭时不得重复投递").isEqualTo(10);
        assertThat(queue.pending()).isZero();
    }

    @Test
    @DisplayName("BEG-02 数据不足一批时按时间窗口送出，不等到凑满 batchSize")
    void mustFlushOnTimeWindowWhenBatchNotFull() {
        GatedSink sink = new GatedSink();
        // 窗口取 1s、batchSize 取 100：只有「到期/有数据就送」才可能在有界等待内收到 3 条，
        // 若语义是「必须凑满 100 条」，这 3 条会一直躺在队列里直到超时。
        try (BoundedBatchEgressQueue<Integer> queue = new BoundedBatchEgressQueue<>(256, 100,
                Duration.ofSeconds(1), sink, new RecordingMetrics())) {
            // 用哨兵占住消费线程，让 0/1/2 在队列里排好，批次边界才是确定的（否则取决于调度）
            assertThat(queue.offer(-1)).isTrue();
            awaitUntil("消费线程必须进入 Sink", () -> sink.entered.getCount() == 0L);
            for (int index = 0; index < 3; index++) {
                assertThat(queue.offer(index)).isTrue();
            }
            sink.openGate();
            awaitUntil("不足一批时也必须在时间窗口内送出", () -> sink.received.size() == 4);
            assertThat(sink.received).containsExactly(-1, 0, 1, 2);
            assertThat(sink.batches)
                    .as("3 条远未凑满 batchSize=100，仍必须成批送出")
                    .containsExactly(List.of(-1), List.of(0, 1, 2));
            assertThat(queue.deliveredBatches()).isEqualTo(2);
            assertThat(queue.droppedCount()).isZero();
            awaitUntil("队列必须被消费干净", () -> queue.pending() == 0);
        }
    }

    @Test
    @DisplayName("BEG-03 队满时 offer 返回 false 且丢弃计数可见（绝不阻塞、绝不静默）")
    void fullQueueMustDropAndCount() {
        GatedSink sink = new GatedSink();
        RecordingMetrics metrics = new RecordingMetrics();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(8, 8, Duration.ofSeconds(1), sink, metrics);
        try {
            assertThat(queue.offer(-1)).isTrue();
            awaitUntil("消费线程必须进入 Sink", () -> sink.entered.getCount() == 0L);
            // 消费线程被 Sink 卡住 ⇒ 队列不会被排空，容量 8 必然被打满
            for (int index = 0; index < 8; index++) {
                assertThat(queue.offer(index)).as("未满时必须入队成功").isTrue();
            }
            long startedAt = System.nanoTime();
            assertThat(queue.offer(99)).as("队满必须立即返回 false").isFalse();
            long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();
            assertThat(elapsedMillis)
                    .as("队满必须立刻返回（实测阻塞 %d ms）", elapsedMillis)
                    .isLessThan(500L);
            assertThat(queue.droppedCount()).as("丢弃必须记账").isEqualTo(1);
            assertThat(queue.pending()).isEqualTo(8);
            assertThat(metrics.errorCount(IotMessageKeys.EGRESS_DROPPED))
                    .as("丢弃必须上报 iot.common.egress.dropped")
                    .isEqualTo(1);
        } finally {
            sink.openGate();
            queue.close();
        }
        assertThat(sink.received).as("已入队的数据不得因关闭而丢失").hasSize(9);
        assertThat(queue.droppedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("BEG-04 关闭时把队列剩余数据刷给 Sink（不静默丢在内存里）")
    void closeMustFlushRemaining() {
        GatedSink sink = new GatedSink();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(64, 10, Duration.ofSeconds(1), sink, new RecordingMetrics());
        assertThat(queue.offer(-1)).isTrue();
        awaitUntil("消费线程必须进入 Sink", () -> sink.entered.getCount() == 0L);
        for (int index = 0; index < 25; index++) {
            assertThat(queue.offer(index)).as("容量 64 远大于 25+1，不应拒收").isTrue();
        }
        // close 会中断卡在 Sink 里的消费线程 ⇒ 消费循环随之退出（仅剩手上这批），
        // 队列中的 25 条只能由 close 的兜底 flush 按 batchSize 循环送出
        queue.close();

        awaitUntil("关闭后剩余数据必须全部送出", () -> sink.received.size() == 26);
        assertThat(sink.received).containsExactlyElementsOf(
                IntStream.concat(IntStream.of(-1), IntStream.range(0, 25)).boxed().toList());
        assertThat(queue.pending()).isZero();
        assertThat(queue.droppedCount()).as("能送出就不该记成丢弃").isZero();
        assertThat(queue.deliveredItems()).isEqualTo(26);
        assertThat(sink.batches).allSatisfy(batch ->
                assertThat(batch.size()).as("兜底 flush 也必须按 batchSize 切批").isLessThanOrEqualTo(10));
        assertThat(sink.batches.stream().mapToInt(List::size).sum()).isEqualTo(26);
        queue.close();
        assertThat(sink.received).as("close 必须幂等，不得重复投递").hasSize(26);
    }

    @Test
    @DisplayName("BEG-05 Sink 抛异常只损失本批，后续批次照常送达并计数失败")
    void sinkFailureMustNotStopConsumer() {
        FailFirstSink sink = new FailFirstSink();
        try (BoundedBatchEgressQueue<Integer> queue = new BoundedBatchEgressQueue<>(256, 1,
                Duration.ofMillis(20), sink, new RecordingMetrics())) {            assertThat(queue.offer(0)).isTrue();
            awaitUntil("首批失败必须被计数", () -> queue.sinkFailures() >= 1);
            assertThat(queue.deliveredItems()).as("失败的批次不得计入已投递").isZero();

            assertThat(queue.offer(1)).isTrue();
            assertThat(queue.offer(2)).isTrue();
            awaitUntil("失败后的批次必须继续送达", () -> sink.received.size() == 2);
            assertThat(sink.received).containsExactly(1, 2);
            assertThat(queue.deliveredItems()).isEqualTo(2);
            assertThat(queue.sinkFailures()).isGreaterThanOrEqualTo(1);
            assertThat(queue.droppedCount()).as("Sink 失败不等于入队丢弃").isZero();

            // 刻意制造一段空闲（远长于 20ms 窗口）：覆盖消费线程「poll 超时且队列为空则继续」的分支，
            // 并证明空闲不会让线程退出——之后再投放必须照常送达。
            // 这里不是「用 sleep 做计时断言」：断言的是空闲后的行为，sleep 只是构造空闲窗口。
            idleFor(Duration.ofMillis(200));
            assertThat(queue.offer(3)).isTrue();
            awaitUntil("空闲后消费线程必须仍然存活并继续送达", () -> sink.received.size() == 3);
            assertThat(sink.received).containsExactly(1, 2, 3);
        }
    }

    @Test
    @DisplayName("BEG-06 关闭后 offer 一律返回 false 并计数")
    void offerAfterCloseMustBeRejectedAndCounted() {
        RecordingSink sink = new RecordingSink();
        RecordingMetrics metrics = new RecordingMetrics();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(16, 16, Duration.ofMillis(16), sink, metrics);
        queue.close();
        queue.close();

        assertThat(queue.offer(1)).isFalse();
        assertThat(queue.offer(2)).isFalse();
        assertThat(queue.offer(3)).isFalse();
        assertThat(queue.droppedCount()).as("关闭后的拒收必须逐条记账").isEqualTo(3);
        assertThat(metrics.errorCount(IotMessageKeys.EGRESS_DROPPED)).isEqualTo(3);
        assertThat(queue.pending()).isZero();
        assertThat(queue.deliveredItems()).isZero();
        assertThat(queue.deliveredBatches()).isZero();
    }

    @Test
    @DisplayName("BEG-07 容量小于 batchSize 构造即失败（fail-fast），默认值可解析")
    void capacitySmallerThanBatchSizeMustFailFast() {
        assertThatThrownBy(() -> new BoundedBatchEgressQueue<Integer>(4, 5, Duration.ofSeconds(1),
                batch -> { }, null))
                .as("单批就可能超过容量，语义矛盾，必须构造期失败")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
        assertThatThrownBy(() -> new BoundedBatchEgressQueue<Integer>(1, 0, null, batch -> { }, null))
                .as("batchSize<=0 走默认 200，此时容量 1 仍小于 batchSize")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedBatchEgressQueue<Integer>(16, 16, null, null, null))
                .as("sink 非空是硬约束")
                .isInstanceOf(NullPointerException.class);

        assertThat(BoundedBatchEgressQueue.DEFAULT_CAPACITY).isEqualTo(10_000);
        assertThat(BoundedBatchEgressQueue.DEFAULT_BATCH_SIZE).isEqualTo(200);
        assertThat(BoundedBatchEgressQueue.DEFAULT_FLUSH_INTERVAL).isEqualTo(Duration.ofMillis(1_000L));

        RecordingSink sink = new RecordingSink();
        try (BoundedBatchEgressQueue<Integer> queue =
                     new BoundedBatchEgressQueue<>(0, 0, null, sink, null)) {
            assertThat(queue.offer(7)).isTrue();
            assertThatThrownBy(() -> queue.offer(null))
                    .as("元素不得为 null")
                    .isInstanceOf(NullPointerException.class);
            awaitUntil("默认参数（含 null 指标）下也必须可正常投递", () -> sink.received.size() == 1);
        }
    }

    @Test
    @DisplayName("BEG-08 并发 offer 不丢不重（8 线程 × 100 条精确计数）")
    void concurrentOfferMustNotLoseOrDuplicate() throws Exception {
        final int threads = 8;
        final int perThread = 100;
        RecordingSink sink = new RecordingSink();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(10_000, 50, Duration.ofMillis(20), sink,
                        new RecordingMetrics());
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger rejected = new AtomicInteger();
        try {
            for (int worker = 0; worker < threads; worker++) {
                final int base = worker * perThread;
                pool.submit(() -> {
                    awaitQuietly(start);
                    for (int index = 0; index < perThread; index++) {
                        if (!queue.offer(base + index)) {
                            rejected.incrementAndGet();
                        }
                    }
                });
            }
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
            assertThat(rejected.get()).as("容量 10000 远大于 800，不应有拒收").isZero();

            awaitUntil("并发投放的元素必须全部送达", () -> sink.received.size() == threads * perThread);
            queue.close();

            assertThat(sink.received).hasSize(threads * perThread);
            assertThat(sink.received).as("不得重复、不得丢失")
                    .containsExactlyInAnyOrderElementsOf(
                            IntStream.range(0, threads * perThread).boxed().toList());
            assertThat(queue.droppedCount()).isZero();
            assertThat(queue.deliveredItems()).isEqualTo(threads * perThread);
        } finally {
            queue.close();
        }
    }

    @Test
    @DisplayName("BEG-09 close 幂等；消费线程未退出时不得并发调用 Sink（只记录剩余数）")
    void closeMustBeIdempotentAndAvoidConcurrentSinkAccess() throws Exception {
        StubbornSink sink = new StubbornSink();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(64, 8, Duration.ofSeconds(1), sink, new RecordingMetrics());
        try {
            assertThat(queue.offer(-1)).isTrue();
            awaitUntil("消费线程必须进入 Sink", () -> sink.entered.getCount() == 0L);
            // 让 close 在「等待消费线程」这一步被中断：join 立刻抛 InterruptedException，
            // 于是走「线程仍存活 ⇒ 不并发调用 Sink，只记 WARN 与剩余数」的分支
            Thread closer = new Thread(() -> {
                Thread.currentThread().interrupt();
                queue.close();
            }, "closer-interrupted");
            closer.start();
            closer.join(TimeUnit.SECONDS.toMillis(10));
            assertThat(closer.isAlive()).as("close 不得被卡死的 Sink 无限拖住").isFalse();
            assertThat(sink.maxConcurrent.get())
                    .as("关闭阶段绝不允许与消费线程并发调用 Sink")
                    .isEqualTo(1);

            sink.openGate();
            awaitUntil("放开 Sink 后消费线程必须把手上数据送完并计数", () ->
                    sink.received.size() == 1 && queue.deliveredItems() == 1);
            queue.close();
            assertThat(sink.received).containsExactly(-1);
            assertThat(queue.droppedCount()).as("这属于在途数据，不是丢弃，不得记账").isZero();
            assertThat(queue.deliveredItems()).isEqualTo(1);
            assertThat(queue.pending()).isZero();
        } finally {
            sink.openGate();
            queue.close();
        }
    }

    @Test
    @DisplayName("BEG-10 每次 flush 后上报队列深度 gauge（名字与标签走常量）")
    void mustReportPendingGaugeAfterEachFlush() {
        GatedSink sink = new GatedSink();
        RecordingMetrics metrics = new RecordingMetrics();
        try (BoundedBatchEgressQueue<Integer> queue = new BoundedBatchEgressQueue<>(64, 64,
                Duration.ofMillis(20), sink, metrics)) {
            assertThat(queue.offer(-1)).isTrue();
            awaitUntil("消费线程必须进入 Sink", () -> sink.entered.getCount() == 0L);
            for (int index = 0; index < 3; index++) {
                assertThat(queue.offer(index)).isTrue();
            }
            sink.openGate();
            awaitUntil("数据必须送达", () -> sink.received.size() == 4);
            awaitUntil("每次 flush 后必须上报队列深度", () ->
                    metrics.gauges.containsKey(BoundedBatchEgressQueue.METRIC_PENDING));
            assertThat(BoundedBatchEgressQueue.METRIC_PENDING).isEqualTo("ypbin.iot.egress.pending");
            assertThat(metrics.gauges.get(BoundedBatchEgressQueue.METRIC_PENDING)).isNotNull();
            assertThat(queue.deliveredBatches()).as("哨兵 + 剩余 3 条 = 2 批").isEqualTo(2);
        } finally {
            sink.openGate();
        }
    }

    @Test
    @DisplayName("BEG-11 从消费线程内部 close（Sink 自关）不得把剩余元素留在内存里")
    void closeFromConsumerThreadMustNotStrandRemainingItems() {
        SelfClosingSink sink = new SelfClosingSink();
        BoundedBatchEgressQueue<Integer> queue =
                new BoundedBatchEgressQueue<>(64, 2, Duration.ofMillis(20), sink, new RecordingMetrics());
        sink.bind(queue);
        try {
            // 先把 6 条全部排进队列，再放行 Sink：否则「首批评审时 close」会先于入队发生，
            // 后几条 offer 会被合法拒收，用例就会退化成「测关闭后拒收」而不是「测排空」
            for (int index = 0; index < 6; index++) {
                assertThat(queue.offer(index)).isTrue();
            }
            sink.openGate();
            // Sink 在第一批里调用 close()：此时若 interrupt/join 自己，消费循环会退出，
            // 剩下 4 条将永久留在队列里且无人再 flush —— 这正是要防的静默丢数据
            awaitUntil("消费循环必须在自关后把剩余元素排空", () -> sink.received.size() == 6);
            assertThat(sink.received).containsExactly(0, 1, 2, 3, 4, 5);
            assertThat(queue.droppedCount()).isZero();
            assertThat(queue.deliveredItems()).isEqualTo(6);
        } finally {
            sink.openGate();
            queue.close();
        }
        awaitUntil("最终队列必须为空", () -> queue.pending() == 0);
        assertThat(sink.received).as("不得因 close 重复投递").hasSize(6);
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 构造一段空闲窗口（<b>不用于计时断言</b>，只为让消费线程经历 poll 超时分支）。
     *
     * @param duration 空闲时长
     */
    private static void idleFor(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError("空闲等待被中断", ex);
        }
    }

    /**
     * 轮询等待条件成立（超时即失败，不做固定 sleep 断言）。
     *
     * @param description 断言说明
     * @param condition   条件
     */
    private static void awaitUntil(String description, BooleanSupplier condition) {
        long deadline = System.nanoTime() + AWAIT_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new AssertionError("等待被中断：" + description, ex);
            }
        }
        throw new AssertionError("等待超时（" + AWAIT_TIMEOUT.toMillis() + "ms）：" + description);
    }

    /**
     * 记录型 Sink：收集元素与批次。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static class RecordingSink implements BatchEgressSink<Integer> {

        /** 收到的元素（包级可读：子类 GatedSink/StubbornSink 的用例要按子类引用取值，
         * Java 不允许经子类引用访问父类 private 字段）。 */
        final List<Integer> received = new CopyOnWriteArrayList<>();

        /** 收到的批次（包级可读，理由同上）。 */
        final List<List<Integer>> batches = new CopyOnWriteArrayList<>();

        @Override
        public void accept(List<Integer> batch) {
            batches.add(List.copyOf(batch));
            received.addAll(batch);
        }
    }

    /**
     * 只阻塞<b>首个</b>调用的 Sink：用于确定性地制造「消费线程被占住」的场景。
     *
     * <p>为何只阻塞首次：用例既要「占住消费者让队列堆积」，又要「放开后 close 的兜底 flush
     * 能正常送出」——若每次都阻塞，close 阶段会连兜底 flush 一起卡住（本用例第一版就是这样红的）。</p>
     *
     * <p>被中断时<b>仍然记账本批并恢复中断标记</b>：这模拟「远端调用已被受理，
     * 但调用线程收到取消信号」的情形，使用例能确定性地断言「关闭阶段要么由消费线程送完，
     * 要么由 close 的兜底 flush 送完，绝不重复也绝不丢失」。</p>
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static class GatedSink extends RecordingSink {

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch gate = new CountDownLatch(1);

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public void accept(List<Integer> batch) {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                try {
                    if (!gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("gate timeout");
                    }
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            super.accept(batch);
        }

        private void openGate() {
            gate.countDown();
        }
    }

    /**
     * 不响应中断的 Sink：模拟卡死且不可取消的远端调用，用于覆盖 close 的有界等待分支。
     *
     * <p>同时统计 Sink 的<b>并发进入次数</b>：关闭阶段若并发调用 Sink，
     * 最大值会大于 1，用例即可抓住「use-after-close 式」的并发写。</p>
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class StubbornSink extends RecordingSink {

        private final CountDownLatch entered = new CountDownLatch(1);

        private final CountDownLatch gate = new CountDownLatch(1);

        private final AtomicInteger concurrent = new AtomicInteger();

        private final AtomicInteger maxConcurrent = new AtomicInteger();

        @Override
        public void accept(List<Integer> batch) {
            maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
            entered.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    // 刻意忽略中断：远端阻塞不响应取消，close 只能靠有界 join 兜底
                    Thread.interrupted();
                }
            }
            try {
                super.accept(batch);
            } finally {
                concurrent.decrementAndGet();
            }
        }

        private void openGate() {
            gate.countDown();
        }
    }

    /**
     * 在自己的 {@code accept} 里关闭所属队列的 Sink：覆盖「从消费线程内部 close」的分支。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class SelfClosingSink extends RecordingSink {

        private final AtomicInteger calls = new AtomicInteger();

        private final CountDownLatch gate = new CountDownLatch(1);

        private BoundedBatchEgressQueue<Integer> queue;

        private void bind(BoundedBatchEgressQueue<Integer> owner) {
            this.queue = owner;
        }

        private void openGate() {
            gate.countDown();
        }

        @Override
        public void accept(List<Integer> batch) {
            try {
                if (!gate.await(GATE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("gate timeout");
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            super.accept(batch);
            if (calls.incrementAndGet() == 1) {
                queue.close();
            }
        }
    }

    /**
     * 首个批次必然失败的 Sink。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class FailFirstSink implements BatchEgressSink<Integer> {

        private final AtomicInteger calls = new AtomicInteger();

        private final List<Integer> received = new CopyOnWriteArrayList<>();

        @Override
        public void accept(List<Integer> batch) {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("sink failure by design");
            }
            received.addAll(batch);
        }
    }

    /**
     * 记录指标调用：错误按消息键分类计数，gauge 保留最后一次取值。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class RecordingMetrics implements MetricsRecorder {

        private final Map<String, AtomicInteger> errors = new ConcurrentHashMap<>();

        private final Map<String, Double> gauges = new ConcurrentHashMap<>();

        @Override
        public void recordRead(Duration elapsed, boolean success) {
            // 本测试不关心读写指标
        }

        @Override
        public void recordWrite(Duration elapsed, boolean success) {
            // 本测试不关心读写指标
        }

        @Override
        public void recordSubscriptionBatch(int pointCount) {
            // 本测试不关心订阅指标
        }

        @Override
        public void recordError(String errorType) {
            errors.computeIfAbsent(errorType, ignored -> new AtomicInteger()).incrementAndGet();
        }

        @Override
        public void gauge(String name, double value) {
            gauges.put(name, value);
        }

        private int errorCount(String errorType) {
            AtomicInteger counter = errors.get(errorType);
            return counter == null ? 0 : counter.get();
        }
    }
}
