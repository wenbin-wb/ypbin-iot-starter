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
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import org.jspecify.annotations.Nullable;
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

    /** 令牌桶定点运算倍率。 */
    private static final long TOKEN_SCALE = 1000L;

    private static final long NANOS_PER_SECOND = Duration.ofSeconds(1).toNanos();

    private final ConcurrentMap<String, CompletableFuture<Entry>> entries = new ConcurrentHashMap<>();

    /** 保护 {@link #closed} 与「入表 + 发起建链 / 快照清表」的原子性。 */
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    private final Duration idleTimeout;

    private final TaskScheduler scheduler;

    private final Clock clock;

    private final int maxConnections;

    /** 建链速率闸门：每秒允许的新建链数；0 表示不限速。 */
    private final int connectRateLimit;

    /** 建链抖动系数：把同一时刻的建链请求打散，避免集群重连风暴。 */
    private final double connectRateJitter;

    /** 令牌桶的「上次补充时刻」（纳秒）。 */
    private final AtomicLong lastRefillNanos = new AtomicLong(System.nanoTime());

    /** 当前可用令牌数（放大 1000 倍做定点运算，避免浮点误差累积）。 */
    private final AtomicLong availableTokens;

    private final AtomicInteger openCount = new AtomicInteger();

    private final AtomicLong connectThrottleWaitMillis = new AtomicLong();

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
        this(idleTimeout, maxConnections, 0, 0.0D, scheduler, clock);
    }

    /**
     * 创建连接注册中心。
     *
     * @param idleTimeout        空闲回收超时
     * @param maxConnections     最大并发链路数（软限制）
     * @param connectRateLimit   每秒最大新建链数；非正数表示不限速
     * @param connectRateJitter  建链抖动系数（0~1），用于打散同一时刻的建链请求
     * @param scheduler          调度器
     * @param clock              时钟
     */
    public ConnectionRegistry(Duration idleTimeout, int maxConnections, int connectRateLimit,
            double connectRateJitter, TaskScheduler scheduler, Clock clock) {
        this.idleTimeout = idleTimeout == null || idleTimeout.isZero() || idleTimeout.isNegative()
                ? Duration.ofMinutes(5) : idleTimeout;
        this.maxConnections = maxConnections <= 0 ? 100_000 : maxConnections;
        this.connectRateLimit = Math.max(connectRateLimit, 0);
        this.connectRateJitter = Math.min(Math.max(connectRateJitter, 0.0D), 1.0D);
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.clock = clock == null ? Clock.systemUTC() : clock;
        this.availableTokens = new AtomicLong(tokensOf(this.connectRateLimit));
    }

    private static long tokensOf(int ratePerSecond) {
        return ratePerSecond <= 0 ? 0L : ratePerSecond * TOKEN_SCALE;
    }

    /**
     * 获取一个建链令牌（阻塞调用线程直到拿到或超时）。
     *
     * <p><b>为什么必须是闸门而不是计数器</b>：10 万设备同时上线会在数秒内产生 10 万次 TCP 握手，
     * 既打满本机 FD/SYN 队列，也会击穿对端 PLC 的并发连接上限——工业设备对此极其敏感，
     * <b>打挂对端是真实事故</b>。这里用令牌桶把建链速率钳在配置值以内，
     * 并叠加抖动把请求打散（否则限速后所有请求仍会在每秒初的同一毫秒涌出）。</p>
     *
     * @return 实际等待的毫秒数；未限速时返回 0
     */
    private long acquireConnectToken() {
        if (connectRateLimit <= 0) {
            return 0L;
        }
        long startedNanos = System.nanoTime();
        while (true) {
            // 已关闭时立即中止等待：否则 closeAll 的快照里那些未完成的 fresh
            // 要等这里拿到令牌才会完成，停机被拖 ≈ N/R 秒（实测 6 并发/2 每秒即 2.8s）
            if (closed) {
                return -1L;
            }
            refillTokens();
            long current = availableTokens.get();
            if (current >= TOKEN_SCALE && availableTokens.compareAndSet(current, current - TOKEN_SCALE)) {
                break;
            }
            // 桶空：等待一个令牌的产生时间（按剩余量估算，最长不超过一个补充周期）
            sleepQuietly(Math.max(1L, 1000L / connectRateLimit));
        }
        // 抖动：把拿到令牌后立刻发起的建链再打散一点，避免"限速后仍同步"
        if (connectRateJitter > 0.0D) {
            long spreadMillis = (long) (1000.0D / Math.max(connectRateLimit, 1) * connectRateJitter);
            if (spreadMillis > 0L) {
                sleepQuietly(ThreadLocalRandom.current().nextLong(spreadMillis + 1L));
            }
        }
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private void refillTokens() {
        long now = System.nanoTime();
        long last = lastRefillNanos.get();
        long elapsedNanos = now - last;
        if (elapsedNanos <= 0L) {
            return;
        }
        long capacity = tokensOf(connectRateLimit);
        // 溢出安全：先拆成「整秒 + 余数纳秒」再相乘。
        // 原写法 elapsedNanos * rate 在长时间空闲后会溢出为负，导致 refill<=0 直接 return，
        // 而 CAS 不执行又让 lastRefillNanos 永不推进 —— 桶永远为 0，取令牌死循环。
        long wholeSeconds = elapsedNanos / NANOS_PER_SECOND;
        long remainderNanos = elapsedNanos % NANOS_PER_SECOND;
        long refill = wholeSeconds * connectRateLimit * TOKEN_SCALE
                + remainderNanos * connectRateLimit / NANOS_PER_SECOND * TOKEN_SCALE;
        if (refill <= 0L) {
            return;
        }
        if (!lastRefillNanos.compareAndSet(last, now)) {
            return;
        }
        // 必须把 min 的结果写回：原实现把 Math.min 的结果丢弃，只有溢出为负时才回写，
        // 于是桶的容量实际上没有上限 —— 空闲 5s 就能攒出 10 倍于配置速率的令牌，
        // 限速闸门在「启动风暴」这个唯一目标场景下完全失效。
        availableTokens.updateAndGet(current -> {
            long updated = current + refill;
            if (updated < 0L) {
                // 极端溢出保护：宁可回到满桶（限速偏松）也不能留下永久为负的桶（取令牌死循环）
                return capacity;
            }
            return Math.min(capacity, updated);
        });
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 已累计等待的建链限速时间（毫秒），用于诊断限速是否真的在起作用。
     *
     * @return 累计等待毫秒数
     */
    public long connectThrottleWaitMillis() {
        return connectThrottleWaitMillis.get();
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
        // 必须放在方法入口：若放在插入/建链之后，重试耗尽的最后一次会真的建出链路并入表，
        // 却立刻返回失败且永不 tryRetain → 该条目的 refCount 恒为 0、不排空闲回收 → 永久孤儿连接。
        if (remainingAttempts <= 0) {
            return Stages.failed(new ConnectionException(key, IotMessageKeys.CONNECTION_FAILED,
                    "acquire retries exhausted"));
        }
        CompletableFuture<Entry> fresh = new CompletableFuture<>();
        // 不用 null 兜底：null 会让后续 target.thenCompose 变成可达的空解引用。
        // 该默认值实际不可达（staleFailure 分支在上面就 return 了），
        // 一旦真被用到会给出明确原因，而不是 NPE。
        CompletableFuture<Entry> target = Stages.failed(new ConnectionException(key,
                IotMessageKeys.CONNECTION_FAILED, "no connection path was selected"));
        boolean staleFailure = false;
        boolean mustOpen = false;
        lifecycle.readLock().lock();
        try {
            if (closed) {
                return Stages.failed(new ConnectionException(key, IotMessageKeys.CONNECTION_CLOSED));
            }
            CompletableFuture<Entry> existing = entries.putIfAbsent(key, fresh);
            if (existing != null) {
                if (existing.isCompletedExceptionally()) {
                    // 陈旧失败条目：摘除后重试。
                    // 不摘除会永久残留（activeCount 偏大）；不重试会让新调用者继承上一次的失败。
                    entries.remove(key, existing);
                    staleFailure = true;
                } else {
                    target = existing;
                }
            } else if (entries.size() > maxConnections) {
                // 关键：拒绝路径也必须完成 fresh，否则已挂到它上面的并发调用者会永久挂起
                entries.remove(key, fresh);
                fresh.completeExceptionally(new ConnectionException(
                        key, IotMessageKeys.CONNECTION_LIMIT_EXCEEDED, maxConnections));
                target = fresh;
            } else {
                target = fresh;
                mustOpen = true;
            }
        } finally {
            lifecycle.readLock().unlock();
        }
        if (mustOpen) {
            // 限速等待必须在锁外：在读锁内 sleep 会把 closeAll（需写锁）拖住，
            // 实测 6 并发/2 每秒就让停机阻塞近 3 秒，10 万连接规模下不可接受。
            long waited = acquireConnectToken();
            if (waited > 0L) {
                connectThrottleWaitMillis.addAndGet(waited);
            }
            // 等待期间可能已被 closeAll 清表：必须复核条目仍是自己的，否则会建出无人关闭的孤儿链路
            lifecycle.readLock().lock();
            try {
                if (closed || entries.get(key) != fresh) {
                    entries.remove(key, fresh);
                    fresh.completeExceptionally(new ConnectionException(key, IotMessageKeys.CONNECTION_CLOSED));
                    target = fresh;
                } else {
                    openAsync(adapter, spec, context, key, fresh);
                }
            } finally {
                lifecycle.readLock().unlock();
            }
        }
        if (staleFailure) {
            return acquireAttempt(adapter, spec, context, key, remainingAttempts - 1);
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
        } catch (Throwable ex) {
            // 必须捕获 Throwable：只捕 RuntimeException 时，适配器抛 Error（如 NoClassDefFoundError）
            // 会让 fresh 永不完成、条目永久留在表中 → 等待者挂起且 closeAll 永不完成。
            // 这里不吞掉：异常被完整记录并交付给等待者。
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

        /** 空闲起始时刻；引用计数归零前为空（此时不参与空闲回收）。 */
        @Nullable
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
