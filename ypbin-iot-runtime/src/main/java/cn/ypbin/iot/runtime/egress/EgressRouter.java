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

    private final int batchSize;

    private final int queueCapacityPoints;

    private final EgressOverflowPolicy overflowPolicy;

    private final Duration blockTimeout;

    private final List<DataSink> sinks;

    private final List<DeviceEventListener> listeners;

    private final Clock clock;

    private final BlockingQueue<DataBatch> queue;

    private final AtomicInteger queuedPoints = new AtomicInteger();

    private final AtomicLong droppedPoints = new AtomicLong();

    private final AtomicLong deliveredBatches = new AtomicLong();

    private final AtomicLong sinkFailures = new AtomicLong();

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
            Duration blockTimeout, List<DataSink> sinks, List<DeviceEventListener> listeners, Clock clock) {
        this.batchSize = batchSize <= 0 ? 1000 : batchSize;
        this.queueCapacityPoints = queueCapacityPoints <= 0 ? 100_000 : queueCapacityPoints;
        this.overflowPolicy = overflowPolicy == null ? EgressOverflowPolicy.DROP_OLDEST : overflowPolicy;
        this.blockTimeout = blockTimeout == null ? Duration.ofSeconds(1) : blockTimeout;
        this.sinks = sinks == null ? List.of() : List.copyOf(sinks);
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
        this.clock = clock == null ? Clock.systemUTC() : clock;
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
        if (!reserve(points)) {
            return;
        }
        queue.add(batch);
    }

    @Override
    public void emit(DeviceEvent event) {
        Objects.requireNonNull(event, "event must not be null");
        // 事件是低频控制面信息，直接投递并做异常隔离，不进数据队列（避免被高频数据挤掉）
        for (DeviceEventListener listener : listeners) {
            try {
                listener.onEvent(event);
            } catch (RuntimeException ex) {
                sinkFailures.incrementAndGet();
                log.error("[ypbin-iot] device event listener {} failed for device {}",
                        listener.getClass().getName(), event.deviceId(), ex);
            }
        }
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

    private void drop(int points, String reason) {
        long total = droppedPoints.addAndGet(points);
        log.warn("[ypbin-iot] dropped {} points ({}); total dropped={}", points, reason, total);
    }

    private void dispatchLoop() {
        while (running.get()) {
            try {
                DataBatch first = queue.poll(200L, TimeUnit.MILLISECONDS);
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
        Map<String, List<PointValue>> byDevice = new LinkedHashMap<>();
        Map<String, DataBatch> templates = new LinkedHashMap<>();
        List<DataBatch> overflow = new ArrayList<>();
        for (DataBatch batch : drained) {
            templates.putIfAbsent(batch.deviceId(), batch);
            List<PointValue> merged = byDevice.computeIfAbsent(batch.deviceId(), ignored -> new ArrayList<>());
            if (merged.size() + batch.size() > batchSize && !merged.isEmpty()) {
                overflow.add(rebuild(templates.get(batch.deviceId()), merged));
                merged = new ArrayList<>();
                byDevice.put(batch.deviceId(), merged);
            }
            merged.addAll(batch.points());
        }
        List<DataBatch> result = new ArrayList<>(overflow);
        for (Map.Entry<String, List<PointValue>> entry : byDevice.entrySet()) {
            if (!entry.getValue().isEmpty()) {
                result.add(rebuild(templates.get(entry.getKey()), entry.getValue()));
            }
        }
        return result;
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
