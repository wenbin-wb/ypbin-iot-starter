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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.util.Stages;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接注册中心：<b>单飞建链 + 引用计数 + 空闲回收</b>。
 *
 * <p>为什么必须有单飞：设备批量上线时，一条 Modbus 网关链路后面的 200 个从站的
 * {@code bind} 会并发触发同一条链路的 {@code open}；没有单飞就会建出 200 条 TCP 连接，
 * 把网关打挂。这类问题在压测前几乎不会暴露。</p>
 *
 * <p><b>三种并发危险的处置</b>（均由专项测试锁定，见 {@code ConnectionRegistryTest}）：</p>
 * <ol>
 *   <li><b>上限拒绝必须完成 Future</b>：被拒绝的 {@code fresh} 若只从表中摘除而不完成，
 *       已经挂到它上面的并发调用者会<b>永久挂起</b>（表现为容器启动死锁）。
 *       因此拒绝路径也走统一的交付通道。</li>
 *   <li><b>空闲回收与 acquire 的 TOCTOU</b>：回收的「判定 + 状态翻转 + 摘除」必须在<b>同一个
 *       临界区</b>内完成，且引用计数改为可失败的 {@code tryRetain}——否则 acquire 会拿到
 *       一条正在被关闭的链路。</li>
 *   <li><b>关闭与 acquire 的竞态</b>：{@code closed} 检查、入表、发起建链三者必须在读锁内，
 *       {@link #closeAll()} 的快照清表在写锁内；否则关闭后仍会建出无人关闭的「孤儿链路」。</li>
 * </ol>
 *
 * <p>并发原语一律使用 {@link ReentrantLock} / {@link ReentrantReadWriteLock}，
 * 不使用 {@code synchronized}（虚拟线程下会 pinning）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class ConnectionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    /** acquire 因条目被回收而重试的最大次数。 */
    private static final int MAX_ACQUIRE_ATTEMPTS = 3;

    private final ConcurrentMap<String, CompletableFuture<Entry>> entries = new ConcurrentHashMap<>();

    /** 保护 {@link #closed} 与「入表 + 发起建链 / 快照清表」的原子性。 */
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    private final Duration idleTimeout;

    private final TaskScheduler scheduler;

    private final Clock clock;

    private final int maxConnections;

    private final AtomicInteger openCount = new AtomicInteger();

    private volatile boolean closed;

    /**
     * 创建连接注册中心。
     *
     * @param idleTimeout    空闲回收超时
     * @param maxConnections 最大并发链路数（<b>软限制</b>：并发下可能短暂略微超出）
     * @param scheduler      调度器（用于空闲回收定时）
     * @param clock          时钟
     */
    public ConnectionRegistry(Duration idleTimeout, int maxConnections, TaskScheduler scheduler, Clock clock) {
        this.idleTimeout = idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()
                ? Duration.ofMinutes(5) : idleTimeout;
        this.maxConnections = maxConnections <= 0 ? 100_000 : maxConnections;
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    /**
     * 获取一条链路的使用权。
     *
     * <p>相同 {@code spec.connectionId()} 的并发调用只会触发一次真实建链。</p>
     *
     * @param adapter 适配器
     * @param spec    连接规格
     * @param context 适配器上下文
     * @return 连接句柄 Stage；失败时交付<b>原始</b> {@link ConnectionException}
     */
    public CompletionStage<ConnectionHandle> acquire(
            ProtocolAdapter adapter, ConnectionSpec spec, AdapterContext context) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(context, "context must not be null");
        // 内部组合算子会产生 CompletionException 包装，在公共边界统一归一化为原始领域异常
        return Stages.normalize(
                acquireAttempt(adapter, spec, context, spec.connectionId(), MAX_ACQUIRE_ATTEMPTS));
    }

    private CompletionStage<ConnectionHandle> acquireAttempt(ProtocolAdapter adapter, ConnectionSpec spec,
            AdapterContext context, String key, int remainingAttempts) {
        CompletableFuture<Entry> fresh = new CompletableFuture<>();
        CompletableFuture<Entry> target;
        lifecycle.readLock().lock();
        try {
            if (closed) {
                return Stages.failed(new ConnectionException(key, IotMessageKeys.CONNECTION_CLOSED));
            }
            CompletableFuture<Entry> existing = entries.putIfAbsent(key, fresh);
            if (existing != null) {
                target = existing;
            } else if (entries.size() > maxConnections) {
                // 关键：拒绝路径也必须完成 fresh，否则已挂到它上面的并发调用者会永久挂起
                entries.remove(key, fresh);
                fresh.completeExceptionally(new ConnectionException(
                        key, IotMessageKeys.CONNECTION_LIMIT_EXCEEDED, maxConnections));
                target = fresh;
            } else {
                target = fresh;
                openAsync(adapter, spec, context, key, fresh);
            }
        } finally {
            lifecycle.readLock().unlock();
        }
        if (remainingAttempts <= 0) {
            return Stages.failed(new ConnectionException(key, IotMessageKeys.CONNECTION_FAILED,
                    "acquire retries exhausted"));
        }
        return target.thenCompose(entry -> {
            if (entry == null) {
                return Stages.failed(
                        new ConnectionException(key, IotMessageKeys.CONNECTION_FAILED, "null entry"));
            }
            if (entry.tryRetain()) {
                return CompletableFuture.completedFuture(new ConnectionHandle(entry));
            }
            // 该条目已进入空闲回收：摘除后重试，避免交付一条正在关闭的链路
            entries.remove(key, entry.self);
            return acquireAttempt(adapter, spec, context, key, remainingAttempts - 1);
        });
    }

    /**
     * 当前活跃链路数。
     *
     * @return 链路数
     */
    public int activeCount() {
        return entries.size();
    }

    /**
     * 适配器 {@code open} 的真实调用次数，用于验证单飞行为。
     *
     * @return 调用次数
     */
    public int openInvocationCount() {
        return openCount.get();
    }

    /**
     * 关闭全部链路并清空注册表。
     *
     * <p>本方法是<b>终止性</b>的：调用后 {@link #acquire} 一律快速失败。</p>
     *
     * @return 全部关闭后完成的 Future
     */
    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Entry>> snapshot;
        lifecycle.writeLock().lock();
        try {
            closed = true;
            snapshot = new ArrayList<>(entries.values());
            entries.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
        // 关闭动作放在写锁之外：connection.close() 可能阻塞（TLS/串口），不能占着生命周期锁
        CompletableFuture<?>[] futures = snapshot.stream()
                .map(future -> future.thenCompose(entry -> {
                    entry.closeConnection();
                    return CompletableFuture.completedFuture(null);
                }).exceptionally(ex -> null))
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures);
    }

    @Override
    public void close() {
        closeAll().join();
    }

    /**
     * 诊断快照：链路标识 → 状态。
     *
     * @return 不可变快照
     */
    public Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        entries.forEach((key, future) -> result.put(key, describe(future)));
        return Map.copyOf(result);
    }

    private static String describe(CompletableFuture<Entry> future) {
        if (future.isDone() && !future.isCompletedExceptionally()) {
            Entry entry = future.getNow(null);
            return entry == null ? "UNKNOWN" : String.valueOf(entry.connection.state());
        }
        return "CONNECTING";
    }

    private void openAsync(ProtocolAdapter adapter, ConnectionSpec spec, AdapterContext context, String key,
            CompletableFuture<Entry> fresh) {
        CompletionStage<ProtocolConnection> stage;
        try {
            openCount.incrementAndGet();
            stage = adapter.open(spec, context);
        } catch (RuntimeException ex) {
            failFresh(key, fresh, ex);
            return;
        }
        if (stage == null) {
            failFresh(key, fresh, new IllegalStateException("adapter returned null stage"));
            return;
        }
        stage.whenComplete((connection, error) -> {
            if (error != null) {
                failFresh(key, fresh, error);
                return;
            }
            if (connection == null) {
                failFresh(key, fresh, new IllegalStateException("adapter returned null connection"));
                return;
            }
            Entry entry = new Entry(key, connection, fresh);
            fresh.complete(entry);
            // 对端异常断开时，主动让引用计数归零并触发回收
            connection.whenClosed().whenComplete((reason, ignored) -> entry.onConnectionClosed(reason));
            log.debug("[ypbin-iot] connection {} opened.", key);
        });
    }

    private void failFresh(String key, CompletableFuture<Entry> fresh, Throwable error) {
        fresh.completeExceptionally(new ConnectionException(key, error, IotMessageKeys.CONNECTION_FAILED));
        // 先完成再摘除：完成后新调用者才会看到「已异常完成」并走重试路径；
        // 若先摘除，窗口内的新调用者会把它当作新键而发起第二次建链（破坏单飞）
        entries.remove(key, fresh);
        log.warn("[ypbin-iot] open failed for connection {}", key, error);
    }

    /**
     * 连接句柄：一次 acquire 对应一次 release。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    public final class ConnectionHandle implements AutoCloseable {

        private final Entry entry;

        private final AtomicBoolean released = new AtomicBoolean(false);

        private ConnectionHandle(Entry entry) {
            this.entry = entry;
        }

        /**
         * 底层链路。
         *
         * @return 链路
         */
        public ProtocolConnection connection() {
            return entry.connection;
        }

        /**
         * 链路标识。
         *
         * @return 链路标识
         */
        public String connectionId() {
            return entry.key;
        }

        /** 释放使用权（幂等）。 */
        public void release() {
            if (released.compareAndSet(false, true)) {
                entry.release();
            }
        }

        @Override
        public void close() {
            release();
        }
    }

    /**
     * 注册表内部条目：持有链路与引用计数。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private final class Entry {

        private final String key;

        private final ProtocolConnection connection;

        /**
         * 本条目在注册表中的 Future（即自身）。
         *
         * <p>自持引用是为了让 {@code entries.remove(key, self)} 精确命中自身。
         * 早先的写法是从 map 反查 future——当条目已被替换（回收后重建）时，反查会拿到
         * <b>新条目的</b> future 并把它误删，导致健康链路失去注册（单飞失效 + 影子连接）。</p>
         */
        private final CompletableFuture<Entry> self;

        private final ReentrantLock lock = new ReentrantLock();

        private int refCount;

        private Instant idleSince;

        private long idleEpoch;

        private boolean connectionClosed;

        private Entry(String key, ProtocolConnection connection, CompletableFuture<Entry> self) {
            this.key = key;
            this.connection = connection;
            this.self = self;
        }

        /**
         * 尝试获取一个引用。
         *
         * <p><b>可失败</b>是关键：若条目已被回收流程翻转状态，这里必须拒绝，
         * 否则会交付一条正在被关闭的链路。</p>
         *
         * @return 成功返回 {@code true}；条目已回收时返回 {@code false}
         */
        private boolean tryRetain() {
            lock.lock();
            try {
                if (connectionClosed) {
                    return false;
                }
                refCount++;
                idleSince = null;
                return true;
            } finally {
                lock.unlock();
            }
        }

        private void release() {
            long epoch = -1L;
            lock.lock();
            try {
                if (refCount > 0) {
                    refCount--;
                } else {
                    // 计数失衡说明状态机出了问题，显式记录而不是静默吞掉
                    log.debug("[ypbin-iot] release without retain on connection {}", key);
                }
                if (refCount == 0 && !connectionClosed) {
                    idleSince = clock.instant();
                    epoch = ++idleEpoch;
                }
            } finally {
                lock.unlock();
            }
            if (epoch >= 0) {
                long expected = epoch;
                scheduler.scheduleOnce(() -> reclaimIfIdle(expected), idleTimeout);
            }
        }

        /**
         * 空闲回收：判定、状态翻转与摘除在<b>同一个临界区</b>内完成。
         *
         * <p>早先的实现在「二次确认」后释放锁才关闭链路，导致 acquire 可以在校验与关闭之间
         * 通过 retain 拿到一条即将关闭的链路（TOCTOU）。与 {@link #tryRetain()} 共用同一把锁后窗口消失。</p>
         *
         * @param expectedEpoch 排定该任务时的空闲纪元；与当前纪元不符说明期间发生过引用变化
         */
        private void reclaimIfIdle(long expectedEpoch) {
            lock.lock();
            try {
                if (connectionClosed || refCount > 0 || expectedEpoch != idleEpoch) {
                    return;
                }
                connectionClosed = true;
                entries.remove(key, self);
            } finally {
                lock.unlock();
            }
            closeQuietly();
            log.debug("[ypbin-iot] connection {} reclaimed after idle timeout.", key);
        }

        private void onConnectionClosed(Object reason) {
            lock.lock();
            try {
                connectionClosed = true;
                refCount = 0;
                idleSince = null;
                idleEpoch++;
            } finally {
                lock.unlock();
            }
            entries.remove(key, self);
            log.debug("[ypbin-iot] connection {} closed by remote: {}", key, reason);
        }

        private void closeConnection() {
            lock.lock();
            try {
                connectionClosed = true;
                idleEpoch++;
            } finally {
                lock.unlock();
            }
            entries.remove(key, self);
            closeQuietly();
        }

        private void closeQuietly() {
            try {
                connection.close();
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] failed to close connection {}", key, ex);
            }
        }
    }
}
