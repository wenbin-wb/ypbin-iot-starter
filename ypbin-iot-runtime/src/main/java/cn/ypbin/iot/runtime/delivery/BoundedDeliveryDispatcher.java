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
package cn.ypbin.iot.runtime.delivery;

import cn.ypbin.iot.core.context.DeliveryDispatcher;
import cn.ypbin.iot.core.context.TaskScheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 有界宿主回调投递器。
 *
 * <p>用虚拟线程执行宿主回调（宿主阻塞不再占用平台线程），用信号量做<b>有界</b>准入，
 * 过载即丢弃并计数。</p>
 *
 * <p><b>为什么丢弃而不是阻塞等位</b>：本类是在协议线程上被调用的，
 * 一旦在这里等待，就等于把背压原样传回 EventLoop——正是要避免的事。
 * 采集类业务里「丢一批并计数告警」比「拖死整条链路」安全得多。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
public final class BoundedDeliveryDispatcher implements DeliveryDispatcher, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BoundedDeliveryDispatcher.class);

    /** 默认在途上限。 */
    public static final int DEFAULT_CAPACITY = 4096;

    /** 丢弃告警的最小间隔：避免过载时日志风暴反过来打死进程。 */
    private static final Duration DROP_LOG_INTERVAL = Duration.ofSeconds(5);

    /** 关闭时等待在途宿主回调的上限（有界：回调卡死不得把停机一起卡死）。 */
    private static final Duration DRAIN_TIMEOUT = Duration.ofSeconds(5);

    private static final long DRAIN_POLL_MILLIS = 10L;

    private final ExecutorService executor;

    private final Semaphore capacity;

    private final int maxInFlight;

    private final AtomicInteger inFlight = new AtomicInteger();

    private final AtomicLong dropped = new AtomicLong();

    private final AtomicLong lastDropLogMillis = new AtomicLong(0L);

    private final AtomicLong lastLoggedDropped = new AtomicLong(0L);

    private volatile boolean closed;

    /**
     * 创建投递器。
     *
     * @param scheduler   调度器（提供虚拟线程执行器）
     * @param maxInFlight 在途上限；非正数时用 {@link #DEFAULT_CAPACITY}
     */
    public BoundedDeliveryDispatcher(TaskScheduler scheduler, int maxInFlight) {
        Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.executor = scheduler.virtualThreadExecutor();
        this.maxInFlight = maxInFlight <= 0 ? DEFAULT_CAPACITY : maxInFlight;
        this.capacity = new Semaphore(this.maxInFlight);
    }

    @Override
    public boolean dispatch(Runnable task) {
        Objects.requireNonNull(task, "task must not be null");
        if (closed) {
            // 关闭后的投递一律计数丢弃：不能让宿主以为回调还在送达
            drop("dispatcher closed");
            return false;
        }
        if (!capacity.tryAcquire()) {
            drop("delivery queue saturated");
            return false;
        }
        inFlight.incrementAndGet();
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException ex) {
                    // 宿主回调抛异常不得影响后续投递，但也绝不静默
                    log.error("[ypbin-iot] host data listener threw", ex);
                } finally {
                    inFlight.decrementAndGet();
                    capacity.release();
                }
            });
            return true;
        } catch (RejectedExecutionException ex) {
            inFlight.decrementAndGet();
            capacity.release();
            drop("executor rejected task");
            log.warn("[ypbin-iot] delivery executor rejected a host callback; is the scheduler shutting down?",
                    ex);
            return false;
        } catch (RuntimeException ex) {
            // 只捕 RejectedExecutionException 是不够的：执行器抛其它异常时
            // 许可与在途计数会永久泄漏（容量为 1 时此后所有回调都被静默丢弃），
            // 且异常会逃逸到协议线程。这里归还款项并计数，绝不静默。
            inFlight.decrementAndGet();
            capacity.release();
            drop("executor failed to accept task");
            log.error("[ypbin-iot] delivery executor failed to accept a host callback", ex);
            return false;
        }
    }

    @Override
    public long droppedCount() {
        return dropped.get();
    }

    @Override
    public int inFlight() {
        return inFlight.get();
    }

    /**
     * 在途上限。
     *
     * @return 上限
     */
    public int maxInFlight() {
        return maxInFlight;
    }

    private void drop(String reason) {
        long total = dropped.incrementAndGet();
        long now = System.currentTimeMillis();
        long last = lastDropLogMillis.get();
        if (now - last >= DROP_LOG_INTERVAL.toMillis() && lastDropLogMillis.compareAndSet(last, now)) {
            long delta = total - lastLoggedDropped.getAndSet(total);
            log.warn("[ypbin-iot] dropped {} host callback(s) this window ({}); total dropped={}",
                    delta, reason, total);
        }
    }

    /**
     * 关闭投递器，并<b>有界等待在途宿主回调完成</b>。
     *
     * <p>不能只置位就返回：投递器先于 `TaskScheduler` 关闭，而在途回调可能正写到一半
     * （JDBC / HTTP）。等一小段再返回，能让它们跑完而不是被随后的
     * {@code shutdownNow()} 打断成「半途而废且无任何计数」。</p>
     *
     * <p>等待是有界的：宿主回调卡死时绝不能把停机也一起卡死。</p>
     */
    @Override
    public void close() {
        closed = true;
        long deadline = System.nanoTime() + DRAIN_TIMEOUT.toNanos();
        while (inFlight.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(DRAIN_POLL_MILLIS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = inFlight.get();
        if (remaining > 0) {
            log.warn("[ypbin-iot] delivery dispatcher closed with {} host callback(s) still in flight; "
                    + "they may be interrupted by scheduler shutdown.", remaining);
        } else {
            log.debug("[ypbin-iot] delivery dispatcher closed (dropped {} total).", dropped.get());
        }
    }
}
