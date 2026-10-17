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

import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有界微批出口队列——流式采集缓冲出口的通用基座。
 *
 * <p><b>解决的问题</b>：协议接入回调（点位读数、事件、帧解析结果）是<b>高频热路径</b>，
 * 而宿主消费侧（写 HTTP / 发 Kafka / 批量落库）天然慢且会抖动。若在回调线程上直接调用宿主，
 * 一次远端阻塞就会把 Netty 协议线程连同整条链路一起拖死（AGENTS I4）。本类把这段距离
 * 用「有界队列 + 单消费线程 + 微批」隔开：</p>
 * <ol>
 *   <li><b>入队绝不阻塞</b>：{@link #offer(Object)} 用 {@code ArrayBlockingQueue#offer}，
 *       队满立即返回 {@code false} 并累加丢弃计数、上报
 *       {@link IotMessageKeys#EGRESS_DROPPED}、输出限流 WARN。丢弃是<b>可观测的损失</b>，
 *       阻塞是把背压反向灌回协议栈——后者不可接受；</li>
 *   <li><b>批量消费</b>：单条平台 daemon 线程先 {@code poll(flushInterval)} 取首个元素，
 *       再用 {@code drainTo} 补到 {@code batchSize}，凑够即送、凑不够也会在时间窗口到期时送，
 *       兼顾吞吐与端到端延迟；</li>
 *   <li><b>故障隔离</b>：宿主 Sink 抛 {@link RuntimeException} 只影响<b>本批</b>——记 ERROR 全堆栈、
 *       累加失败计数，线程继续消费下一批（绝不因一次远端抖动而停摆）；</li>
 *   <li><b>优雅停机</b>：实现 {@link AutoCloseable}，Spring 容器销毁时会调用；{@link #close()}
 *       先停消费线程，再把队列剩余元素按 {@code batchSize} <b>循环</b>刷给 Sink，
 *       不留数据在内存里静默消失。</li>
 * </ol>
 *
 * <h2>契约与边界</h2>
 * <ul>
 *   <li>容量按<b>元素个数</b>计；{@code capacity < batchSize} 在构造期 fail-fast
 *       （否则单批就可能超过容量，语义自相矛盾）；</li>
 *   <li>溢出策略只有<b>「丢新」</b>这一种：不支持 DROP_OLDEST。采集类数据里「保最新」与
 *       「保完整」是业务语义，应由宿主在 Sink 侧决定，基座只保证不阻塞、不静默；</li>
 *   <li>丢弃对同一个元素<b>只记账一次</b>；失败批次<b>不重投</b>（重试属于 Sink 的职责，
 *       基座重试只会放大远端压力并破坏顺序）；</li>
 *   <li>{@link #close()} 幂等；关闭后再 {@link #offer(Object)} 一律返回 {@code false} 并计数，
 *       绝不静默接受；</li>
 *   <li>线程安全：队列与计数全部走并发容器/原子量；<b>不使用 {@code synchronized}</b>
 *       （虚拟线程下会 pinning，由 ArchUnit 拦截）。</li>
 * </ul>
 *
 * <h2>宿主用法</h2>
 * <pre>{@code
 * BoundedBatchEgressQueue<Reading> queue = new BoundedBatchEgressQueue<>(
 *         10_000, 200, Duration.ofSeconds(1), batch -> httpClient.post(batch), metrics);
 * queue.offer(reading);   // 协议回调线程：永不阻塞
 * // 容器销毁时（或 try-with-resources 结束）调用 queue.close()，剩余数据会被刷出
 * }</pre>
 *
 * @param <T> 元素类型（{@link #offer(Object)} 拒收 {@code null}）
 * @author wenbin
 * @since 2026-10-17
 */
public final class BoundedBatchEgressQueue<T> implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedBatchEgressQueue.class);

    /** 默认队列容量（元素个数）。 */
    public static final int DEFAULT_CAPACITY = 10_000;

    /** 默认单批最大元素数。 */
    public static final int DEFAULT_BATCH_SIZE = 200;

    /** 默认时间窗口：凑不满一批时，最长等待多久也要送出。 */
    public static final Duration DEFAULT_FLUSH_INTERVAL = Duration.ofMillis(1_000L);

    /** 队列深度指标名（名字白名单由调用方保证，见 {@link MetricsRecorder#gauge}）。 */
    public static final String METRIC_PENDING = "ypbin.iot.egress.pending";

    /** 消费线程名：排障时一眼能看出是谁在调宿主 Sink。 */
    private static final String THREAD_NAME = "ypbin-iot-batch-egress";

    /** 丢弃 WARN 的最小间隔：过载时日志风暴会先于背压本身把进程打死。 */
    private static final Duration DROP_LOG_INTERVAL = Duration.ofSeconds(5);

    private static final long DROP_LOG_INTERVAL_NANOS = DROP_LOG_INTERVAL.toNanos();

    /** 关闭时等待消费线程退出的上限（宿主 Sink 卡死不得把停机一起卡死）。 */
    private static final Duration CLOSE_JOIN_TIMEOUT = Duration.ofSeconds(5);

    /** 单次 poll 的最小等待：避免 1ns 这种正数时长被 {@code toMillis()} 截断成 0 后空转。 */
    private static final long MIN_POLL_MILLIS = 1L;

    private final int batchSize;

    private final Duration flushInterval;

    private final BatchEgressSink<T> sink;

    private final MetricsRecorder metrics;

    private final BlockingQueue<T> queue;

    private final AtomicLong dropped = new AtomicLong();

    private final AtomicLong deliveredBatches = new AtomicLong();

    private final AtomicLong deliveredItems = new AtomicLong();

    private final AtomicLong sinkFailures = new AtomicLong();

    private final AtomicLong lastDropLogNanos = new AtomicLong(0L);

    private final AtomicLong lastLoggedDropped = new AtomicLong(0L);

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 生命周期锁：把「检查 closed + 入队」与「置 closed」串行化。
     *
     * <p><b>为什么必须有它（实测缺陷）</b>：offer 与 close 若无同步，存在如下窗口——
     * offer 读到 {@code closed=false} → close 置位、interrupt、join 成功、{@code flushRemaining()}
     * 排空（此刻队列还是空的）→ close 返回 → offer 才把元素放进队列并返回 {@code true}。
     * 此后消费线程已退出、close 又幂等，该元素<b>永久滞留内存且既未送达也未计入丢弃</b>，
     * 直接违反「关闭后不得静默接受」的承诺。用锁把两段临界区串行化后，
     * 「已置 closed 之后不可能再插入新元素」，而「插入成功的元素必然被随后的 drain 看到」。</p>
     *
     * <p>用 {@link ReentrantLock} 而非 {@code synchronized}：自研代码在虚拟线程下禁
     * {@code synchronized}（会 pinning，ArchUnit 强制）。锁内只做 O(1) 的队列 offer/标志位操作，
     * 不含日志与 IO（丢弃日志在锁外记录），因此不会成为协议线程的竞争热点。</p>
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    private final Thread consumer;

    /**
     * 创建出口队列并<b>立即启动</b>消费线程。
     *
     * @param capacity      队列容量（元素个数）； {@code <= 0} 时用 {@link #DEFAULT_CAPACITY}
     * @param batchSize     单批最大元素数； {@code <= 0} 时用 {@link #DEFAULT_BATCH_SIZE}
     * @param flushInterval 时间窗口；{@code null} 或非正数时用 {@link #DEFAULT_FLUSH_INTERVAL}
     * @param sink          宿主消费实现，非空
     * @param metrics       指标门面；{@code null} 时用 {@link NoopMetricsRecorder#INSTANCE}
     * @throws IllegalArgumentException 解析默认值后仍满足 {@code capacity < batchSize}
     * @throws NullPointerException     {@code sink} 为 {@code null}
     */
    public BoundedBatchEgressQueue(int capacity, int batchSize, @Nullable Duration flushInterval,
            BatchEgressSink<T> sink, @Nullable MetricsRecorder metrics) {
        int resolvedCapacity = capacity <= 0 ? DEFAULT_CAPACITY : capacity;
        int resolvedBatchSize = batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
        Duration resolvedInterval = flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()
                ? DEFAULT_FLUSH_INTERVAL : flushInterval;
        if (resolvedCapacity < resolvedBatchSize) {
            // 否则单批就可能装不满/装不下：容量语义与批次语义自相矛盾，构造期就失败
            throw new IllegalArgumentException("capacity(" + resolvedCapacity
                    + ") must be >= batchSize(" + resolvedBatchSize + ")");
        }
        this.batchSize = resolvedBatchSize;
        this.flushInterval = resolvedInterval;
        this.sink = Objects.requireNonNull(sink, "sink must not be null");
        this.metrics = metrics == null ? NoopMetricsRecorder.INSTANCE : metrics;
        this.queue = new ArrayBlockingQueue<>(resolvedCapacity);
        this.consumer = Thread.ofPlatform().daemon(true).name(THREAD_NAME).unstarted(this::consumeLoop);
        this.consumer.start();
    }

    /**
     * 尝试入队，<b>绝不阻塞</b>（可在协议线程/EventLoop 上直接调用）。
     *
     * <p>队满或队列已关闭时返回 {@code false}：累加 {@link #droppedCount()}、上报
     * {@link IotMessageKeys#EGRESS_DROPPED}、并输出限流 WARN。<b>丢弃必须可见</b>——
     * 静默丢弃会让「数据去哪了」无从回答。</p>
     *
     * @param item 元素，非空
     * @return 入队成功返回 {@code true}；队满或已关闭返回 {@code false}
     * @throws NullPointerException {@code item} 为 {@code null}
     */
    public boolean offer(T item) {
        Objects.requireNonNull(item, "item must not be null");
        String dropReason;
        lifecycleLock.lock();
        try {
            if (closed.get()) {
                dropReason = "queue closed";
            } else if (queue.offer(item)) {
                return true;
            } else {
                dropReason = "queue full";
            }
        } finally {
            lifecycleLock.unlock();
        }
        // 日志与计数放在锁外：限流 WARN 虽然低频，但没必要让它占住协议线程与 close 共享的临界区
        recordDrop(dropReason);
        return false;
    }

    /**
     * 累计丢弃元素数（队满或已关闭）。
     *
     * @return 丢弃数
     */
    public long droppedCount() {
        return dropped.get();
    }

    /**
     * 累计成功投递的批次数（仅统计 Sink 未抛异常的批次）。
     *
     * @return 批次数
     */
    public long deliveredBatches() {
        return deliveredBatches.get();
    }

    /**
     * 累计成功投递的元素数。
     *
     * @return 元素数
     */
    public long deliveredItems() {
        return deliveredItems.get();
    }

    /**
     * 累计 Sink 失败次数（按批计）。
     *
     * @return 失败次数
     */
    public long sinkFailures() {
        return sinkFailures.get();
    }

    /**
     * 当前队列中的元素数（不含消费线程手上正在投递的那一批）。
     *
     * @return 待投递元素数
     */
    public int pending() {
        return queue.size();
    }

    /**
     * 关闭队列：先停消费线程，再把剩余元素按批次刷给 Sink。
     *
     * <p>顺序与语义：</p>
     * <ol>
     *   <li>在生命周期锁内置关闭标志（与 {@link #offer(Object)} 的「检查 + 入队」互斥，因此
     *       close 返回后<b>不可能</b>再有元素插入队列；此后的 {@link #offer(Object)} 一律被拒并计数）；</li>
     *   <li><b>有界</b>等待消费线程退出（上限 {@code 5000ms}，见内部常量 {@code CLOSE_JOIN_TIMEOUT}）；</li>
     *   <li>线程已退出 → 把队列剩余元素按 {@code batchSize} 循环 flush（不静默留在内存里）；
     *       Sink 失败逐批计数并记 ERROR，不中断后续批次；</li>
     *   <li>线程仍存活（宿主 Sink 卡死）→ <b>不</b>并发调用 Sink（会违反单线程契约、造成
     *       use-after-close 式的数据竞争），只记录 WARN 与剩余数；那些元素仍归消费线程所有，
     *       它恢复后会继续送达，因此<b>不计入丢弃</b>以免重复记账。</li>
     * </ol>
     *
     * <p>本方法幂等：重复调用直接返回。</p>
     *
     * <p><b>从消费线程内部调用</b>（宿主 Sink 在自己的 {@code accept} 里关队列）时不做
     * interrupt / join / 兜底 flush：中断自己会让消费循环立刻退出，从而把<b>队列里剩下的元素
     * 永久留在内存里</b>（且不会再有人调用 close 去 flush）；在 Sink 调用栈里递归调用 Sink
     * 同样不可接受。此时只置关闭标志——消费循环的判据是「未关闭 <b>或</b> 队列非空」，
     * 因此它会在本批返回后自行把剩余元素排空再退出。</p>
     */
    @Override
    public void close() {
        // 置位必须与 offer 的「检查 + 入队」互斥：否则两者之间的窗口会让 offer 在 drain 之后
        // 才入队成功，元素永久滞留（详见 lifecycleLock 字段注释）。CAS 保证 close 幂等。
        lifecycleLock.lock();
        try {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        } finally {
            lifecycleLock.unlock();
        }
        if (Thread.currentThread() == consumer) {
            log.debug("[ypbin-iot] batch egress queue closed from its consumer thread; "
                    + "{} item(s) will be drained by the running loop.", pending());
            reportPending();
            return;
        }
        consumer.interrupt();
        if (!awaitConsumer()) {
            log.warn("[ypbin-iot] batch egress consumer still alive after {}ms; {} item(s) left in queue "
                    + "and will be delivered by the consumer once the sink returns.",
                    CLOSE_JOIN_TIMEOUT.toMillis(), pending());
            reportPending();
            return;
        }
        flushRemaining();
        reportPending();
        // 记录「剩余未投递数」：正常应为 0；非 0 说明有元素在关闭后仍未被送出，排障时可直接看到
        log.debug("[ypbin-iot] batch egress queue closed: pending={}, deliveredBatches={}, deliveredItems={}, "
                + "dropped={}, sinkFailures={}.", pending(), deliveredBatches.get(), deliveredItems.get(),
                dropped.get(), sinkFailures.get());
    }

    /**
     * 消费主循环：取首个元素 → 补齐一批 → 交给 Sink。
     *
     * <p>关闭后只要队列还有元素就继续消费（直到排空），这样停机时不会因为
     * 「关闭标志已置位」而把在途元素落在队列里。</p>
     */
    private void consumeLoop() {
        while (!closed.get() || !queue.isEmpty()) {
            T first;
            try {
                first = queue.poll(Math.max(MIN_POLL_MILLIS, flushInterval.toMillis()), TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            if (first == null) {
                continue;
            }
            List<T> batch = new ArrayList<>(batchSize);
            batch.add(first);
            queue.drainTo(batch, batchSize - 1);
            flush(batch);
        }
    }

    /**
     * 把一批交给宿主 Sink；失败只影响本批，绝不让消费线程死掉。
     *
     * @param batch 待投递批次（非空）
     */
    private void flush(List<T> batch) {
        try {
            sink.accept(List.copyOf(batch));
            deliveredBatches.incrementAndGet();
            deliveredItems.addAndGet(batch.size());
        } catch (RuntimeException ex) {
            sinkFailures.incrementAndGet();
            log.error("[ypbin-iot] batch egress sink failed for {} item(s); the batch is dropped without retry",
                    batch.size(), ex);
        } finally {
            reportPending();
        }
    }

    /**
     * 关闭阶段的兜底投递：循环把队列排空（每轮最多 {@code batchSize} 个）。
     */
    private void flushRemaining() {
        List<T> batch = new ArrayList<>(batchSize);
        int drained = queue.drainTo(batch, batchSize);
        while (drained > 0) {
            flush(batch);
            batch.clear();
            drained = queue.drainTo(batch, batchSize);
        }
    }

    /**
     * 有界等待消费线程退出。
     *
     * @return 线程已退出返回 {@code true}；等待超时或等待被中断返回 {@code false}
     */
    private boolean awaitConsumer() {
        try {
            consumer.join(CLOSE_JOIN_TIMEOUT.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[ypbin-iot] interrupted while waiting for the batch egress consumer to stop", ex);
        }
        return !consumer.isAlive();
    }

    /**
     * 上报队列深度（每次 flush 后调用，名字与标签用常量，避免魔法值）。
     */
    private void reportPending() {
        metrics.gauge(METRIC_PENDING, queue.size());
    }

    /**
     * 记录一次丢弃：计数 + 指标 + 限流 WARN。
     *
     * @param reason 丢弃原因（用于日志）
     */
    private void recordDrop(String reason) {
        long total = dropped.incrementAndGet();
        metrics.recordError(IotMessageKeys.EGRESS_DROPPED);
        long now = System.nanoTime();
        long last = lastDropLogNanos.get();
        if (now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now)) {
            long delta = total - lastLoggedDropped.getAndSet(total);
            log.warn("[ypbin-iot] dropped {} egress item(s) this window ({}); total dropped={}",
                    delta, reason, total);
        }
    }
}
