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
package cn.ypbin.iot.runtime.scheduler;

import cn.ypbin.iot.core.context.TaskScheduler;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 调度器默认实现。
 *
 * <p><b>M0 阶段实现说明（与 DESIGN §4.4 的差异，刻意保留）</b>：本实现用
 * {@link ScheduledExecutorService} 承载周期任务，尚未引入分层时间轮。
 * 设计文档中的时间轮是为「10 万周期任务」准备的优化；而 M0 的验收门槛已按 D6
 * 收敛到 1 万连接，此规模下 {@code ScheduledExecutorService} 的堆开销与精度完全够用。
 * 时间轮将在通过 1 万连接门禁、并决定冲击 10 万连接时再引入——
 * 提前实现一个没有压测数据支撑的调度器，只会增加不确定性。</p>
 *
 * <p><b>三个线程域的分工</b>（这是本类最重要的设计点）：</p>
 * <ul>
 *   <li>{@code timer}：只做「触发」，平台线程 1 个。任务体内不得阻塞；</li>
 *   <li>{@link #virtualThreadExecutor()}：承载阻塞式纯 Java 协议栈；</li>
 *   <li>{@link #platformThreadExecutor()}：承载 JNI / native 调用（串口、CAN、媒体），
 *       有界池，避免 native pinning 拖垮载体线程。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class DefaultTaskScheduler implements TaskScheduler, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(DefaultTaskScheduler.class);

    private static final String TIMER_THREAD_NAME = "ypbin-iot-timer";

    private static final String PLATFORM_THREAD_NAME = "ypbin-iot-platform-";

    private final ScheduledExecutorService timer;

    private final ExecutorService virtualExecutor;

    private final ExecutorService platformExecutor;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 以默认参数创建调度器。
     *
     * @param platformPoolSize 平台线程池大小（建议 ≥ 串口数 + CAN 通道数）
     * @param platformQueueCapacity 平台线程池队列容量
     */
    public DefaultTaskScheduler(int platformPoolSize, int platformQueueCapacity) {
        this.timer = Executors.newSingleThreadScheduledExecutor(namedDaemon(TIMER_THREAD_NAME));
        this.virtualExecutor = Executors.newVirtualThreadPerTaskExecutor();
        int poolSize = platformPoolSize <= 0 ? defaultPlatformPoolSize() : platformPoolSize;
        int queueCapacity = platformQueueCapacity <= 0 ? 10_000 : platformQueueCapacity;
        this.platformExecutor = new ThreadPoolExecutor(poolSize, poolSize, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                namedDaemon(PLATFORM_THREAD_NAME),
                new ThreadPoolExecutor.AbortPolicy());
    }

    private static int defaultPlatformPoolSize() {
        int ports = 8;
        return Math.max(ports, Runtime.getRuntime().availableProcessors());
    }

    @Override
    public ScheduledTask schedule(Runnable task, Duration initialDelay, Duration interval) {
        Objects.requireNonNull(task, "task must not be null");
        Duration delay = initialDelay == null ? Duration.ZERO : initialDelay;
        Duration period = interval == null ? Duration.ofSeconds(1) : interval;
        ensureOpen();
        ScheduledFuture<?> future = timer.scheduleAtFixedRate(guard(task), delay.toMillis(),
                period.toMillis(), TimeUnit.MILLISECONDS);
        return new ScheduledFutureTask(future);
    }

    @Override
    public ScheduledTask scheduleOnce(Runnable task, Duration delay) {
        Objects.requireNonNull(task, "task must not be null");
        Duration wait = delay == null ? Duration.ZERO : delay;
        ensureOpen();
        ScheduledFuture<?> future = timer.schedule(guard(task), wait.toMillis(), TimeUnit.MILLISECONDS);
        return new ScheduledFutureTask(future);
    }

    @Override
    public ExecutorService virtualThreadExecutor() {
        return virtualExecutor;
    }

    @Override
    public ExecutorService platformThreadExecutor() {
        return platformExecutor;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        timer.shutdownNow();
        virtualExecutor.shutdownNow();
        platformExecutor.shutdownNow();
        log.debug("[ypbin-iot] task scheduler closed.");
    }

    private void ensureOpen() {
        if (closed.get()) {
            throw new IllegalStateException("task scheduler already closed");
        }
    }

    /**
     * 包裹任务体：捕获异常并完整记录，避免一个失败任务中断整个调度器。
     */
    private static Runnable guard(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (Throwable ex) {
                log.error("[ypbin-iot] scheduled task failed and was isolated", ex);
            }
        };
    }

    private static ThreadFactory namedDaemon(String name) {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, name + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * 调度句柄实现。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private record ScheduledFutureTask(ScheduledFuture<?> future) implements ScheduledTask {

        @Override
        public void cancel() {
            future.cancel(false);
        }

        @Override
        public boolean isCancelled() {
            return future.isCancelled();
        }
    }
}
