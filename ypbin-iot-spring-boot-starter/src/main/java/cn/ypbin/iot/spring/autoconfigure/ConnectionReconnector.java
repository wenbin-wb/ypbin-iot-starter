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

import cn.ypbin.iot.core.context.AdapterSettings;
import cn.ypbin.iot.core.context.TaskScheduler;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 重连退避调度器：为每条断开的链路维护独立的指数退避。
 *
 * <p><b>为什么必须有重连</b>：没有它，任何一次网络抖动或对端重启都会让设备<b>永久离线</b>——
 * 链路被判定死亡后，注册中心把条目摘除、会话被关闭、订阅被取消，而框架只在启动与设备变更时才 bind。
 * 本仓已有 12 个 P0 的经验表明，「断开」本身不可怕，<b>断开后没有恢复路径</b>才是事故。</p>
 *
 * <p><b>为什么退避必须按链路独立</b>：一台 Modbus 网关挂 200 个从站共享一条链路，
 * 若用全局退避，一条坏链路会把所有链路的恢复节奏一起拖慢或一起打快。
 * 按 connectionId 独立维护，才能做到「只让坏的那条慢下来」。</p>
 *
 * <p><b>为什么要有抖动</b>：集群里成百上千个实例同时掉线时，固定退避会让它们在<b>同一毫秒</b>
 * 一起重连（惊群），把刚恢复的对端再次打挂。抖动把重试时刻打散。</p>
 *
 * <p><b>绝不静默</b>：每次断开、每次重试失败都记日志（失败日志按链路限流，避免风暴），
 * 恢复时记一条可检索的成功日志，并维护 {@link #activeCount()} 与累计次数供诊断。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class ConnectionReconnector implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionReconnector.class);

    /** 退避倍率：每次失败后翻倍。 */
    private static final int BACKOFF_MULTIPLIER = 2;

    /** 同一链路的失败日志最小间隔：避免长时间不可用时刷屏。 */
    private static final Duration FAILURE_LOG_INTERVAL = Duration.ofSeconds(30);

    /** 重连尝试的回调：返回 {@code true} 表示该链路已恢复。 */
    @FunctionalInterface
    interface ReconnectAction {

        /**
         * 执行一次重连尝试。
         *
         * @param connectionId 链路标识
         * @return 全部设备都重新绑定成功时返回 {@code true}
         */
        boolean attempt(String connectionId);
    }

    private final TaskScheduler scheduler;

    private final ReconnectAction action;

    private final Map<String, Attempt> attempts = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicLong totalAttempts = new AtomicLong();

    private final AtomicLong totalRecovered = new AtomicLong();



    /**
     * 创建调度器。
     *
     * @param scheduler 调度器
     * @param action    重连动作
     */
    ConnectionReconnector(TaskScheduler scheduler, ReconnectAction action) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.action = Objects.requireNonNull(action, "action must not be null");
    }

    /**
     * 为一条链路安排重连。
     *
     * <p>幂等：同一条链路已在重连中时不会重复排定，避免「一次断开触发 N 次重连风暴」。</p>
     *
     * @param connectionId 链路标识
     * @param settings     退避参数（来自该链路的适配器上下文）
     */
    void schedule(String connectionId, AdapterSettings settings) {
        if (closed.get()) {
            return;
        }
        Attempt created = new Attempt(settings);
        Attempt existing = attempts.putIfAbsent(connectionId, created);
        if (existing != null) {
            log.debug("[ypbin-iot] reconnect already scheduled for connection {}; ignoring duplicate",
                    connectionId);
            return;
        }
        log.warn("[ypbin-iot] scheduling reconnect for connection {} in {} (max {}).",
                connectionId, created.currentDelay, created.maxDelay);
        scheduleOnce(connectionId, created.currentDelay);
    }

    /**
     * 报告一次重连尝试的结果。
     *
     * @param connectionId 链路标识
     * @param success      是否已恢复
     */
    void attemptFinished(String connectionId, boolean success) {
        Attempt attempt = attempts.get(connectionId);
        if (attempt == null) {
            return;
        }
        totalAttempts.incrementAndGet();
        if (success) {
            attempts.remove(connectionId);
            totalRecovered.incrementAndGet();
            log.info("[ypbin-iot] connection {} recovered after {} attempt(s).",
                    connectionId, attempt.attempts.get());
            return;
        }
        Duration next = nextDelay(attempt);
        // 失败日志限流：链路长时间不可用时，每 FAILURE_LOG_INTERVAL 只输出一条
        long now = System.currentTimeMillis();
        long last = attempt.lastFailureLogMillis.get();
        if (now - last >= FAILURE_LOG_INTERVAL.toMillis()
                && attempt.lastFailureLogMillis.compareAndSet(last, now)) {
            log.warn("[ypbin-iot] reconnect attempt {} for connection {} failed; next attempt in {}.",
                    attempt.attempts.get(), connectionId, next);
        }
        scheduleOnce(connectionId, next);
    }

    /**
     * 取消某条链路的重连（显式关闭、设备移除、生命周期关闭时调用）。
     *
     * @param connectionId 链路标识
     * @return 此前确实在重连中则返回 {@code true}
     */
    boolean cancel(String connectionId) {
        Attempt removed = attempts.remove(connectionId);
        if (removed != null) {
            TaskScheduler.ScheduledTask task = removed.scheduled;
            if (task != null) {
                task.cancel();
            }
            log.debug("[ypbin-iot] reconnect cancelled for connection {}.", connectionId);
            return true;
        }
        return false;
    }

    /**
     * 当前正在重连的链路数。
     *
     * @return 链路数
     */
    int activeCount() {
        return attempts.size();
    }

    /**
     * 累计重连尝试次数。
     *
     * @return 尝试次数
     */
    long totalAttempts() {
        return totalAttempts.get();
    }

    /**
     * 累计恢复次数。
     *
     * @return 恢复次数
     */
    long totalRecovered() {
        return totalRecovered.get();
    }

    private void scheduleOnce(String connectionId, Duration delay) {
        Attempt attempt = attempts.get(connectionId);
        if (attempt == null || closed.get()) {
            return;
        }
        // 定时任务可能晚于取消执行：这里的 attempt.attempts 自增与 attempts 表校验共同保证
        // 「已取消的链路不会再发一次重连」
        attempt.scheduled = scheduler.scheduleOnce(() -> runAttempt(connectionId), delay);
    }

    /**
     * 执行一次重连尝试。
     *
     * <p><b>必须切到平台线程池</b>：重连动作内部会做阻塞式 `join`（acquire + bind 都有超时等待），
     * 而 `scheduleOnce` 跑在调度器的<b>单线程 timer</b> 上。直接在 timer 上执行会阻塞
     * 同 JVM 的全部定时任务——包括所有轮询订阅、空闲回收与保活探测。
     * 一次限速窗口（令牌桶 sleep）就能把整个调度器拖停。</p>
     */
    private void runAttempt(String connectionId) {
        Attempt attempt = attempts.get(connectionId);
        if (attempt == null || closed.get()) {
            return;
        }
        try {
            scheduler.platformThreadExecutor().execute(() -> executeAttempt(connectionId));
        } catch (RuntimeException ex) {
            // 平台池拒绝（停机中）：按一次失败处理并继续退避，绝不静默
            log.warn("[ypbin-iot] failed to schedule reconnect attempt for connection {}; backing off",
                    connectionId, ex);
            attemptFinished(connectionId, false);
        }
    }

    private void executeAttempt(String connectionId) {
        Attempt attempt = attempts.get(connectionId);
        if (attempt == null || closed.get()) {
            return;
        }
        attempt.attempts.incrementAndGet();
        boolean success;
        try {
            success = action.attempt(connectionId);
        } catch (RuntimeException ex) {
            log.error("[ypbin-iot] reconnect attempt for connection {} threw", connectionId, ex);
            success = false;
        }
        attemptFinished(connectionId, success);
    }

    private static Duration nextDelay(Attempt attempt) {
        long current = attempt.currentDelay.toMillis();
        long doubled = current <= 0 ? attempt.maxDelay.toMillis() : current * BACKOFF_MULTIPLIER;
        long capped = Math.min(doubled, attempt.maxDelay.toMillis());
        attempt.currentDelay = Duration.ofMillis(capped);
        return jittered(attempt.currentDelay, attempt.jitter);
    }

    private static Duration jittered(Duration base, double jitter) {
        if (jitter <= 0.0D) {
            return base;
        }
        // 只向「更长」方向抖动：比基准更早重试会让退避失去意义（对端还没恢复就又被敲一次）
        long spread = (long) (base.toMillis() * Math.min(jitter, 1.0D));
        if (spread <= 0L) {
            return base;
        }
        return base.plusMillis(ThreadLocalRandom.current().nextLong(spread + 1L));
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        int pending = attempts.size();
        attempts.values().forEach(attempt -> {
            TaskScheduler.ScheduledTask task = attempt.scheduled;
            if (task != null) {
                task.cancel();
            }
        });
        attempts.clear();
        if (pending > 0) {
            log.debug("[ypbin-iot] reconnector closed with {} pending reconnect(s) cancelled.", pending);
        }
    }

    /**
     * 单条链路的重连状态。
     *
     * @author wenbin
     * @since 2026-09-14
     */
    private static final class Attempt {

        private final Duration initialDelay;

        private final Duration maxDelay;

        private final double jitter;

        private final AtomicInteger attempts = new AtomicInteger();

        private final AtomicLong lastFailureLogMillis = new AtomicLong(0L);

        private volatile Duration currentDelay;

        private volatile TaskScheduler.ScheduledTask scheduled;

        private Attempt(AdapterSettings settings) {
            this.initialDelay = positive(settings.reconnectInitialDelay(), Duration.ofSeconds(1));
            this.maxDelay = positive(settings.reconnectMaxDelay(), Duration.ofMinutes(1));
            this.jitter = Math.min(Math.max(settings.reconnectJitter(), 0.0D), 1.0D);
            this.currentDelay = initialDelay;
        }

        private static Duration positive(Duration value, Duration fallback) {
            return value == null || value.isZero() || value.isNegative() ? fallback : value;
        }
    }
}
