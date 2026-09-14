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

import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.context.DeliveryDispatcher;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.core.spi.DeviceEventListener;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 微批数据出口。
 *
 * <p>职责边界：<b>适配器已经把点位组装成 {@link DataBatch}</b>（协议层最清楚一次读取
 * 返回了什么），本类负责「不让宿主 Sink 的延迟反压到协议线程」与「把相邻批次合并成
 * 更大的批次」，即：</p>
 * <ol>
 *   <li><b>有界缓冲</b>：队列容量按<b>点位数</b>而非批次数计量，超限按策略处理；</li>
 *   <li><b>合并</b>：消费线程一次取出多个批次，按 deviceId 合并到 {@code batchSize} 上限，
 *       在保持单设备顺序的前提下减少 Sink 调用次数；</li>
 *   <li><b>隔离</b>：单个 Sink 抛异常不影响其余 Sink，异常被完整记录并计入指标；</li>
 *   <li><b>丢弃可见</b>：任何丢弃都累加计数并输出限流日志，<b>绝不静默丢弃</b>。</li>
 * </ol>
 *
 * <p><b>关于 {@link EgressOverflowPolicy#BLOCK}</b>：真正的无限阻塞在 Netty EventLoop 上
 * 会导致死锁，因此本实现把 BLOCK 解释为「在 {@code blockTimeout} 内等待，
 * 超时后按 {@link EgressOverflowPolicy#DROP_OLDEST} 处理并记录 warn」。
 * 这是刻意的：一个会让进程卡死的策略比丢数据更糟。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class EgressRouter implements DataEgress, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EgressRouter.class);

    /** 单次消费最多合并的批次数。 */
    private static final int MAX_COALESCE_BATCHES = 64;

    /** 丢弃日志的最小间隔：避免过载时日志风暴打死事件循环。 */
    private static final long DROP_LOG_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

    private final int batchSize;

    private final int queueCapacityPoints;

    private final EgressOverflowPolicy overflowPolicy;

    private final Duration blockTimeout;

    /** 消费线程的 drain 等待窗口，来自 {@code ypbin.iot.egress.batch-interval}。 */
    private final Duration batchInterval;

    private final List<DataSink> sinks;

    private final List<DeviceEventListener> listeners;

    private final Clock clock;

    /**
     * 宿主回调投递器：设备事件与 BLOCK 背压等待都必须卸载出调用线程。
     *
     * <p>允许为空（未注入时退化为调用线程同步执行），这条路径只为直接 {@code new} 的单元测试保留。</p>
     */
    @Nullable
    private final DeliveryDispatcher delivery;

    private final BlockingQueue<DataBatch> queue;

    private final AtomicInteger queuedPoints = new AtomicInteger();

    private final AtomicLong droppedEvents = new AtomicLong();

    private final AtomicLong droppedPoints = new AtomicLong();

    private final AtomicLong deliveredBatches = new AtomicLong();

    private final AtomicLong sinkFailures = new AtomicLong();

    private final AtomicLong lastDropLogNanos = new AtomicLong(0L);

    private final AtomicLong lastLoggedDropped = new AtomicLong(0L);

    private final AtomicBoolean running = new AtomicBoolean(true);

    private final Thread dispatcher;

    /**
     * 创建出口路由器。
     *
     * @param batchSize          合并后的单批最大点数
     * @param queueCapacityPoints 队列容量（点位数）
     * @param overflowPolicy     溢出策略
     * @param blockTimeout       BLOCK 策略的等待上限
     * @param sinks              数据落点
     * @param listeners          设备事件监听器
     * @param clock              时钟
     */
    public EgressRouter(int batchSize, int queueCapacityPoints, EgressOverflowPolicy overflowPolicy,
            Duration blockTimeout, Duration batchInterval, List<DataSink> sinks,
            List<DeviceEventListener> listeners, Clock clock) {
        this(batchSize, queueCapacityPoints, overflowPolicy, blockTimeout, batchInterval, sinks, listeners,
                clock, null);
    }

    /**
     * 创建出口路由器。
     *
     * @param batchSize           单批最大点数
     * @param queueCapacityPoints 队列容量（点数）
     * @param overflowPolicy      溢出策略
     * @param blockTimeout        BLOCK 策略的等待上限
     * @param batchInterval       消费线程 drain 等待窗口
     * @param sinks               数据出口
     * @param listeners           设备事件监听器
     * @param clock               时钟
     * @param delivery            宿主回调投递器（为 {@code null} 时退化为同步执行）
     */
    public EgressRouter(int batchSize, int queueCapacityPoints, EgressOverflowPolicy overflowPolicy,
            Duration blockTimeout, Duration batchInterval, List<DataSink> sinks,
            List<DeviceEventListener> listeners, Clock clock, @Nullable DeliveryDispatcher delivery) {
        this.batchSize = batchSize <= 0 ? 1000 : batchSize;
        this.queueCapacityPoints = queueCapacityPoints <= 0 ? 100_000 : queueCapacityPoints;
        this.overflowPolicy = overflowPolicy == null ? EgressOverflowPolicy.DROP_OLDEST : overflowPolicy;
        this.blockTimeout = blockTimeout == null ? Duration.ofSeconds(1) : blockTimeout;
        this.batchInterval = batchInterval == null || batchInterval.isNegative() || batchInterval.isZero()
                ? Duration.ofMillis(200) : batchInterval;
        if (queueCapacityPoints > 0 && queueCapacityPoints < this.batchSize) {
            // 否则单批就可能超过队列容量：DROP_OLDEST 会把整个队列清空再丢掉新批次（等于全丢）
            throw new IllegalArgumentException("queueCapacityPoints(" + queueCapacityPoints
                    + ") must be >= batchSize(" + this.batchSize + ")");
        }
        this.sinks = sinks == null ? List.of() : List.copyOf(sinks);
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.delivery = delivery;
        this.queue = new LinkedBlockingQueue<>();
        this.dispatcher = Thread.ofPlatform().daemon(true).name("ypbin-iot-egress").unstarted(this::dispatchLoop);
        this.dispatcher.start();
        if (this.sinks.isEmpty()) {
            log.warn("[ypbin-iot] no DataSink registered; emitted data will be dropped. "
                    + "Register a DataSink bean if you expect device data.");
        }
    }

    @Override
    public void emit(DataBatch batch) {
        Objects.requireNonNull(batch, "batch must not be null");
        if (batch.isEmpty()) {
            return;
        }
        int points = batch.size();
        if (!running.get()) {
            // 关闭后入队的数据永远无人投递：必须显式计数，不能静默留在队列里
            drop(points, "router already closed");
            return;
        }
        if (points > queueCapacityPoints) {
            drop(points, "batch larger than queue capacity");
            return;
        }
        if (overflowPolicy == EgressOverflowPolicy.BLOCK && !hasRoomFor(points)) {
            // BLOCK 策略的语义是「不丢，等有位置」——但**不能在有位置之前就阻塞调用线程**：
            // emit 会在协议线程/BLOCK 调用链上被调用，在这里 sleep 就把背压原样传回了 EventLoop
            // （正是 I4 要防的事）。因此把「等待 + 入队」整体卸载到投递器执行。
            Runnable task = () -> {
                if (reserve(points)) {
                    queue.add(batch);
                }
            };
            if (delivery != null && delivery.dispatch(task)) {
                return;
            }
            if (delivery == null) {
                task.run();
                return;
            }
            // 投递器饱和：无法承载等待任务，只能丢弃并计数（绝不静默）
            drop(points, "backpressure dispatcher saturated");
            return;
        }
        if (!reserve(points)) {
            return;
        }
        queue.add(batch);
    }

    private boolean hasRoomFor(int points) {
        return queuedPoints.get() + points <= queueCapacityPoints;
    }

    @Override
    public void emit(DeviceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // 事件是低频控制面信息，不进数据队列（避免被高频数据挤掉）。
        // 但**必须卸载出调用线程**：本方法会在协议线程上被调用，
        // 而 onEvent 是任意宿主代码——直接在协议线程执行等于把 I4 防线绕开。
        Runnable task = () -> {
            for (DeviceEventListener listener : listeners) {
                try {
                    listener.onEvent(event);
                } catch (RuntimeException ex) {
                    sinkFailures.incrementAndGet();
                    log.error("[ypbin-iot] device event listener {} failed for device {}",
                            listener.getClass().getName(), event.deviceId(), ex);
                }
            }
        };
        if (delivery == null || !delivery.dispatch(task)) {
            if (delivery == null) {
                // 未注入投递器（如直接 new 的单元测试）：退化为同步执行并留痕
                task.run();
                return;
            }
            // 投递器饱和：事件被有界丢弃（已在投递器内计数告警），不能静默
            dropEvent(event);
        }
    }

    private void dropEvent(DeviceEvent event) {
        droppedEvents.incrementAndGet();
        log.debug("[ypbin-iot] dropped device event for device {} (delivery saturated).", event.deviceId());
    }

    /**
     * 累计因投递饱和被丢弃的设备事件数。
     *
     * @return 丢弃数
     */
    public long droppedEvents() {
        return droppedEvents.get();
    }

    /**
     * 队列中待投递的点位数。
     *
     * @return 点位数
     */
    public int queuedPoints() {
        return queuedPoints.get();
    }

    /**
     * 累计丢弃点位数。
     *
     * @return 丢弃点位数
     */
    public long droppedPoints() {
        return droppedPoints.get();
    }

    /**
     * 累计投递批次数。
     *
     * @return 批次数
     */
    public long deliveredBatches() {
        return deliveredBatches.get();
    }

    /**
     * 累计 Sink 失败次数。
     *
     * @return 失败次数
     */
    public long sinkFailures() {
        return sinkFailures.get();
    }

    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        dispatcher.interrupt();
        try {
            dispatcher.join(TimeUnit.SECONDS.toMillis(5));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("[ypbin-iot] interrupted while waiting for egress dispatcher to stop", ex);
        }
        if (dispatcher.isAlive()) {
            // dispatcher 仍在写 Sink：此时关 Sink 会造成 use-after-close，宁可泄漏也不破坏语义
            log.warn("[ypbin-iot] egress dispatcher still alive after timeout; skipping sink close "
                    + "to avoid concurrent write and close on the same sink.");
            return;
        }
        // 停机时把队列剩余数据交给 Sink，避免丢在内存里；剩余部分显式计数
        List<DataBatch> remaining = new ArrayList<>();
        queue.drainTo(remaining);
        if (!remaining.isEmpty()) {
            deliver(remaining);
            log.info("[ypbin-iot] flushed {} remaining batches on shutdown.", remaining.size());
        }
        for (DataSink sink : sinks) {
            try {
                sink.close();
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] failed to close data sink {}", sink.name(), ex);
            }
        }
    }

    private boolean reserve(int points) {
        while (true) {
            int current = queuedPoints.get();
            if (current + points > queueCapacityPoints) {
                if (overflowPolicy == EgressOverflowPolicy.DROP_NEWEST) {
                    drop(points, "queue full");
                    return false;
                }
                if (overflowPolicy == EgressOverflowPolicy.BLOCK) {
                    if (awaitRoom(points)) {
                        continue;
                    }
                    drop(points, "queue full after block timeout");
                    return false;
                }
                // DROP_OLDEST
                if (!evictOldest(points)) {
                    drop(points, "queue full and nothing to evict");
                    return false;
                }
                continue;
            }
            if (queuedPoints.compareAndSet(current, current + points)) {
                return true;
            }
        }
    }

    private boolean awaitRoom(int points) {
        long deadline = System.nanoTime() + blockTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (queuedPoints.get() + points <= queueCapacityPoints) {
                return true;
            }
            try {
                Thread.sleep(1L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean evictOldest(int points) {
        DataBatch evicted = queue.poll();
        if (evicted == null) {
            return false;
        }
        queuedPoints.addAndGet(-evicted.size());
        drop(evicted.size(), "drop oldest due to back pressure");
        return true;
    }

    /**
     * 记录丢弃。
     *
     * <p><b>必须限流</b>：过载时 {@code drop} 是在业务线程（可能是 Netty EventLoop）上同步调用的，
     * 每条都打日志会先于背压本身把事件循环打死。</p>
     */
    private void drop(int points, String reason) {
        long total = droppedPoints.addAndGet(points);
        long now = System.nanoTime();
        long last = lastDropLogNanos.get();
        if (now - last >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(last, now)) {
            long delta = total - lastLoggedDropped.getAndSet(total);
            log.warn("[ypbin-iot] dropped {} points this window ({}); total dropped={}",
                    delta, reason, total);
        }
    }

    private void dispatchLoop() {
        while (running.get()) {
            try {
                DataBatch first = queue.poll(batchInterval.toMillis(), TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                List<DataBatch> drained = new ArrayList<>();
                drained.add(first);
                queue.drainTo(drained, MAX_COALESCE_BATCHES - 1);
                int points = drained.stream().mapToInt(DataBatch::size).sum();
                queuedPoints.addAndGet(-points);
                deliver(coalesce(drained));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] egress dispatcher loop error", ex);
            }
        }
    }

    /**
     * 按 deviceId 合并批次，保持单设备内的点位顺序。
     */
    private List<DataBatch> coalesce(List<DataBatch> drained) {
        // 模板与点位放在同一个持有者里：原先用两个 Map（templates / byDevice）靠 deviceId 关联，
        // 静态分析无法证明 templates.get(id) 非空，运行期也存在「关联断了就 NPE」的隐患。
        Map<String, Pending> byDevice = new LinkedHashMap<>();
        List<DataBatch> overflow = new ArrayList<>();
        for (DataBatch batch : drained) {
            Pending pending = byDevice.computeIfAbsent(batch.deviceId(),
                    ignored -> new Pending(batch, new ArrayList<>()));
            if (pending.points().size() + batch.size() > batchSize && !pending.points().isEmpty()) {
                overflow.add(rebuild(pending.template(), pending.points()));
                pending.points().clear();
            }
            pending.points().addAll(batch.points());
        }
        List<DataBatch> result = new ArrayList<>(overflow);
        for (Pending pending : byDevice.values()) {
            if (!pending.points().isEmpty()) {
                result.add(rebuild(pending.template(), pending.points()));
            }
        }
        return result;
    }

    /**
     * 单设备待合并的模板与点位。
     *
     * @param template 该设备首批数据的模板（提供 deviceId/protocol/connectionId）
     * @param points   累积的点位
     * @author wenbin
     * @since 2026-09-14
     */
    private record Pending(DataBatch template, List<PointValue> points) {
    }

    private DataBatch rebuild(DataBatch template, List<PointValue> points) {
        return new DataBatch(template.deviceId(), template.protocol(), template.connectionId(),
                clock.instant(), points);
    }

    private void deliver(List<DataBatch> batches) {
        for (DataBatch batch : batches) {
            for (DataSink sink : sinks) {
                try {
                    sink.write(batch);
                } catch (RuntimeException ex) {
                    sinkFailures.incrementAndGet();
                    log.error("[ypbin-iot] data sink {} failed for device {}",
                            sink.name(), batch.deviceId(), ex);
                }
            }
            deliveredBatches.incrementAndGet();
        }
    }

    /**
     * 当前时刻（供诊断端点使用）。
     *
     * @return 时刻
     */
    public Instant now() {
        return clock.instant();
    }
}
