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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 连接注册中心：<b>单飞建链 + 引用计数 + 空闲回收</b>。
 *
 * <p>为什么必须有单飞：设备批量上线时，一条 Modbus 网关链路后面的 200 个从站的
 * {@code bind} 会并发触发同一条链路的 {@code open}；没有单飞就会建出 200 条 TCP 连接，
 * 把网关打挂。这类问题在压测前几乎不会暴露。</p>
 *
 * <p><b>行为契约（由专项并发测试锁定）</b>：</p>
 * <table border="1">
 *   <caption>并发行为</caption>
 *   <tr><th>场景</th><th>期望</th></tr>
 *   <tr><td>N 个线程并发 acquire 同一 connectionId</td><td>适配器 {@code open} 只被调用 1 次</td></tr>
 *   <tr><td>最后一个引用释放</td><td>进入空闲回收，不产生无引用的活跃连接</td></tr>
 *   <tr><td>空闲超时到达</td><td>连接被关闭并移出注册表，之后可正常重建</td></tr>
 *   <tr><td>建链失败</td><td>所有等待者收到<b>同一个</b>异常，且不残留状态</td></tr>
 * </table>
 *
 * <p>并发原语一律使用 {@link ReentrantLock} 与 {@link ConcurrentHashMap} 的原子操作，
 * 不使用 {@code synchronized}（虚拟线程下会 pinning）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class ConnectionRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistry.class);

    private final ConcurrentMap<String, CompletableFuture<Entry>> entries = new ConcurrentHashMap<>();

    private final Duration idleTimeout;

    private final TaskScheduler scheduler;

    private final Clock clock;

    private final int maxConnections;

    private final AtomicInteger openCount = new AtomicInteger();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建连接注册中心。
     *
     * @param idleTimeout    空闲回收超时
     * @param maxConnections 最大并发链路数
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
     * @return 连接句柄 Stage
     */
    public CompletionStage<ConnectionHandle> acquire(
            ProtocolAdapter adapter, ConnectionSpec spec, AdapterContext context) {
        Objects.requireNonNull(adapter, "adapter must not be null");
        Objects.requireNonNull(spec, "spec must not be null");
        Objects.requireNonNull(context, "context must not be null");
        if (closed.get()) {
            return Stages.failed(new ConnectionException(spec.connectionId(), IotMessageKeys.CONNECTION_CLOSED));
        }
        String key = spec.connectionId();
        CompletableFuture<Entry> fresh = new CompletableFuture<>();
        CompletableFuture<Entry> existing = entries.putIfAbsent(key, fresh);
        CompletableFuture<Entry> target;
        if (existing != null) {
            target = existing;
        } else {
            if (entries.size() > maxConnections) {
                entries.remove(key, fresh);
                return Stages.failed(new ConnectionException(
                        key, IotMessageKeys.CONNECTION_LIMIT_EXCEEDED, maxConnections));
            }
            target = fresh;
            openAsync(adapter, spec, context, key, fresh);
        }
        // 刻意不用 thenApply：组合算子会把领域异常包成 CompletionException，
        // 调用方将无法直接 catch ConnectionException。这里显式交付原始异常。
        CompletableFuture<ConnectionHandle> result = new CompletableFuture<>();
        target.whenComplete((entry, error) -> {
            if (error != null) {
                result.completeExceptionally(Stages.unwrap(error));
                return;
            }
            if (entry == null) {
                result.completeExceptionally(
                        new ConnectionException(key, IotMessageKeys.CONNECTION_FAILED, "null entry"));
                return;
            }
            entry.retain();
            result.complete(new ConnectionHandle(entry));
        });
        return result;
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
     * @return 全部关闭后完成的 Future
     */
    public CompletableFuture<Void> closeAll() {
        List<CompletableFuture<Entry>> snapshot = new ArrayList<>(entries.values());
        entries.clear();
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
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        closeAll().join();
    }

    private void openAsync(ProtocolAdapter adapter, ConnectionSpec spec, AdapterContext context, String key,
            CompletableFuture<Entry> fresh) {
        CompletionStage<ProtocolConnection> stage;
        try {
            openCount.incrementAndGet();
            stage = adapter.open(spec, context);
        } catch (RuntimeException ex) {
            entries.remove(key, fresh);
            fresh.completeExceptionally(new ConnectionException(key, ex, IotMessageKeys.CONNECTION_FAILED));
            return;
        }
        if (stage == null) {
            entries.remove(key, fresh);
            fresh.completeExceptionally(new ConnectionException(
                    key, IotMessageKeys.CONNECTION_FAILED, "adapter returned null stage"));
            return;
        }
        stage.whenComplete((connection, error) -> {
            if (error != null) {
                entries.remove(key, fresh);
                fresh.completeExceptionally(new ConnectionException(
                        key, error, IotMessageKeys.CONNECTION_FAILED));
                log.warn("[ypbin-iot] open failed for connection {}", key, error);
                return;
            }
            if (connection == null) {
                entries.remove(key, fresh);
                fresh.completeExceptionally(new ConnectionException(
                        key, IotMessageKeys.CONNECTION_FAILED, "adapter returned null connection"));
                return;
            }
            Entry entry = new Entry(key, connection);
            fresh.complete(entry);
            // 对端异常断开时，主动让引用计数归零并触发回收
            connection.whenClosed().whenComplete((reason, ignored) -> entry.onConnectionClosed(reason));
            log.debug("[ypbin-iot] connection {} opened.", key);
        });
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

        private final ReentrantLock lock = new ReentrantLock();

        private int refCount;

        private Instant idleSince;

        private boolean connectionClosed;

        private Entry(String key, ProtocolConnection connection) {
            this.key = key;
            this.connection = connection;
        }

        private void retain() {
            lock.lock();
            try {
                refCount++;
                idleSince = null;
            } finally {
                lock.unlock();
            }
        }

        private void release() {
            boolean scheduleReclaim = false;
            lock.lock();
            try {
                if (refCount > 0) {
                    refCount--;
                }
                if (refCount == 0) {
                    idleSince = clock.instant();
                    scheduleReclaim = true;
                }
            } finally {
                lock.unlock();
            }
            if (scheduleReclaim) {
                scheduleIdleReclaim();
            }
        }

        private void onConnectionClosed(Object reason) {
            lock.lock();
            try {
                connectionClosed = true;
                refCount = 0;
                idleSince = null;
            } finally {
                lock.unlock();
            }
            entries.remove(key, entryFuture());
            log.debug("[ypbin-iot] connection {} closed by remote: {}", key, reason);
        }

        @SuppressWarnings("unchecked")
        private CompletableFuture<Entry> entryFuture() {
            CompletableFuture<Entry> future = entries.get(key);
            if (future != null && future.isDone() && !future.isCompletedExceptionally()) {
                return future;
            }
            return CompletableFuture.completedFuture(this);
        }

        private void scheduleIdleReclaim() {
            scheduler.scheduleOnce(this::reclaimIfIdle, idleTimeout);
        }

        private void reclaimIfIdle() {
            Instant marked;
            lock.lock();
            try {
                if (connectionClosed || refCount > 0 || idleSince == null) {
                    return;
                }
                marked = idleSince;
            } finally {
                lock.unlock();
            }
            // 空闲起点在调度后可能被新的 retain 刷新，需要二次确认
            lock.lock();
            try {
                if (refCount > 0 || idleSince == null || !idleSince.equals(marked)) {
                    return;
                }
            } finally {
                lock.unlock();
            }
            closeConnection();
        }

        private void closeConnection() {
            entries.remove(key, entryFuture());
            try {
                connection.close();
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] failed to close connection {}", key, ex);
            }
            lock.lock();
            try {
                connectionClosed = true;
            } finally {
                lock.unlock();
            }
            log.debug("[ypbin-iot] connection {} reclaimed.", key);
        }
    }

    /**
     * 诊断快照：链路标识 → 状态。
     *
     * @return 不可变快照
     */
    public Map<String, String> snapshot() {
        Map<String, String> result = new LinkedHashMap<>();
        entries.forEach((key, future) -> result.put(key,
                future.isDone() && !future.isCompletedExceptionally()
                        ? String.valueOf(future.getNow(null).connection.state()) : "CONNECTING"));
        return Map.copyOf(result);
    }
}
