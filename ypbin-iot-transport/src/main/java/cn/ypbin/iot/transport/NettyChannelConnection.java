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
package cn.ypbin.iot.transport;

import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import io.netty.channel.Channel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基于 Netty {@link Channel} 的物理链路实现。
 *
 * <p>职责：维护链路状态、缓存收到的字节帧、向会话广播入站帧、把关闭事件暴露为 Stage。
 * <b>不理解协议语义</b>——帧怎么解释由协议模块的 {@link DeviceSession} 决定。</p>
 *
 * <p><b>入站帧缓冲是有界的</b>：无界队列在对端高速推送而消费者卡住时会 OOM。
 * 溢出时丢弃最旧帧并计数与告警，与出口的背压策略保持一致。</p>
 *
 * <p>并发原语使用 {@link ReentrantLock} 与 {@link CopyOnWriteArrayList}，
 * 不使用 {@code synchronized}（虚拟线程下会 pinning）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class NettyChannelConnection implements ProtocolConnection {

    private static final Logger log = LoggerFactory.getLogger(NettyChannelConnection.class);

    /** 默认入站帧缓冲上限。 */
    public static final int DEFAULT_INBOUND_CAPACITY = 1024;

    private final ConnectionSpec spec;

    private final Channel channel;

    private final Instant openedAt;

    private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

    private final Deque<byte[]> inbound = new ArrayDeque<>();

    private final List<Consumer<byte[]>> frameListeners = new CopyOnWriteArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 被淘汰的辅助缓冲副本帧数（语义见 {@link #droppedFrames()}；UP-12 起内部改名，公共方法名兼容保留） */
    private final AtomicLong auxBufferEvicted = new AtomicLong();

    /**
     * 辅助入站缓冲是否已被启用（首次调用 {@link #drainFrames()} 后置位）。
     *
     * <p>UP-12 修复：push 模式宿主（只挂帧监听、从不 drain）不需要该缓冲——无条件入队会在
     * 缓冲填满后每帧淘汰一次并 WARN（计数与帧率同步增长，易被误判为数据面丢帧）。
     * 因此缓冲<b>惰性启用</b>：无 drain 消费者时不入队、不计数、不告警；首次 drain 后启用。</p>
     */
    private final AtomicBoolean drainEnabled = new AtomicBoolean(false);

    /** 缓冲溢出是否已 WARN 过一次（后续降 DEBUG，避免每帧一条淹没日志） */
    private volatile boolean overflowWarned = false;

    private volatile SessionState state = SessionState.ONLINE;

    private int inboundCapacity = DEFAULT_INBOUND_CAPACITY;

    /** 绑定的设备会话；1:N 协议在 bind 之前为空（见 {@link #bindSession(DeviceSession)}）。 */
    @Nullable
    private volatile DeviceSession session;

    /**
     * 创建链路对象（由 {@link NettyTransport} 在 channelActive 时调用）。
     *
     * <p><b>此时尚无会话</b>：1:N 协议（Modbus 网关）的设备身份在 {@code bind} 阶段才确定，
     * 因此会话由协议模块在绑定后通过 {@link #bindSession(DeviceSession)} 装入。</p>
     *
     * @param spec    连接规格
     * @param channel 底层 channel
     */
    NettyChannelConnection(ConnectionSpec spec, Channel channel) {
        this.spec = spec;
        this.channel = channel;
        this.openedAt = Instant.now();
    }

    /**
     * 装入设备会话（由协议模块在 bind 阶段调用）。
     *
     * @param deviceSession 设备会话
     */
    public void bindSession(DeviceSession deviceSession) {
        this.session = deviceSession;
    }

    @Override
    public String connectionId() {
        return spec.connectionId();
    }

    @Override
    public Endpoint endpoint() {
        return spec.endpoint();
    }

    @Override
    public SessionState state() {
        return state;
    }

    @Override
    public Instant openedAt() {
        return openedAt;
    }

    @Override
    public DeviceSession session() {
        DeviceSession current = session;
        if (current == null) {
            // 与 SPI 文档一致：未绑定属「能力尚不可用」，用 UnsupportedCapabilityException
            throw new UnsupportedCapabilityException(spec.protocol(),
                    "session (call ProtocolAdapter.bind first)");
        }
        return current;
    }

    @Override
    public CompletionStage<CloseReason> whenClosed() {
        return closeReason;
    }

    @Override
    public Map<String, String> describe() {
        SocketAddress address = channel.remoteAddress();
        if (address instanceof InetSocketAddress inet) {
            return Map.of("remoteHost", inet.getHostString(), "remotePort", String.valueOf(inet.getPort()));
        }
        return address == null ? Map.of() : Map.of("remoteAddress", address.toString());
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> extensionType) {
        // 传输层不提供协议扩展能力；由协议模块的 Connection 实现覆写
        return Optional.empty();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = SessionState.CLOSED;
        channel.close();
        closeReason.complete(CloseReason.clientRequest(Instant.now()));
    }

    /**
     * 发送字节。
     *
     * @param payload 字节内容
     * @return 写入完成后完成的 Stage
     */
    public CompletionStage<Void> write(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return CompletableFuture.completedFuture(null);
        }
        if (!channel.isActive()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("channel not active: " + spec.connectionId()));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        channel.writeAndFlush(payload).addListener(future -> {
            if (future.isSuccess()) {
                result.complete(null);
            } else {
                result.completeExceptionally(future.cause());
            }
        });
        return result;
    }

    /**
     * 注册入站帧监听器。
     *
     * @param listener 监听器
     */
    public void addFrameListener(Consumer<byte[]> listener) {
        if (listener != null) {
            frameListeners.add(listener);
        }
    }

    /**
     * 移除入站帧监听器。
     *
     * @param listener 监听器
     */
    public void removeFrameListener(Consumer<byte[]> listener) {
        frameListeners.remove(listener);
    }

    /**
     * 取出并清空当前缓冲的全部入站帧。
     *
     * <p><strong>UP-12 语义</strong>：本缓冲是<b>辅助</b>副本——入站帧已无条件经 push
     * 监听（{@link #addFrameListener}）交付给会话，这里只是再留一份供拉取消费。
     * 首次调用本方法会<b>启用</b>该缓冲（此前 push-only 宿主不启用、不计数、不告警）。</p>
     *
     * @return 帧列表；无数据时返回空列表
     */
    public List<byte[]> drainFrames() {
        // 惰性启用：一旦有人以拉模式消费，后续帧开始入队（启用前的帧不回溯）
        drainEnabled.set(true);
        lock.lock();
        try {
            List<byte[]> drained = new ArrayList<>(inbound);
            inbound.clear();
            return drained;
        } finally {
            lock.unlock();
        }
    }

    /**
     * 被淘汰的入站缓冲帧数（缓冲启用且溢出时累加）。
     *
     * <p><strong>语义澄清（UP-12）</strong>：此计数<b>不代表数据面投递丢失</b>——
     * 入站帧已全部经 push 监听交付，被淘汰的是「无人及时排空的辅助缓冲副本」。
     * 计量面（受理==发出==落库）是对账真丢数据的判据，本计数只反映拉模式消费者的排空节奏。</p>
     *
     * @return 淘汰的辅助缓冲帧数
     */
    public long droppedFrames() {
        return auxBufferEvicted.get();
    }

    /**
     * 设置入站缓冲上限。
     *
     * @param capacity 上限，必须为正
     */
    public void setInboundCapacity(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("inbound capacity must be positive: " + capacity);
        }
        lock.lock();
        try {
            this.inboundCapacity = capacity;
            while (inbound.size() > capacity) {
                inbound.pollFirst();
                auxBufferEvicted.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * 是否仍然可写。
     *
     * @return channel 活跃返回 {@code true}
     */
    public boolean isActive() {
        return channel.isActive();
    }

    /**
     * 由传输层回调：收到一个入站帧。
     *
     * @param payload 帧内容
     */
    void onFrame(byte[] payload) {
        // UP-12：无 drain 消费者（缓冲未启用）时不入队、不计数、不告警。
        // 溢出的只能是「无人及时排空的辅助缓冲副本」，push 交付在下方锁外无条件执行，不受影响。
        if (drainEnabled.get()) {
            lock.lock();
            try {
                while (inbound.size() >= inboundCapacity) {
                    inbound.pollFirst();
                    long evicted = auxBufferEvicted.incrementAndGet();
                    if (!overflowWarned) {
                        overflowWarned = true;
                        log.warn("[ypbin-iot] inbound aux buffer overflow on connection {}; "
                                        + "evicted aux frames={}（淘汰的是未及时 drain 的辅助缓冲副本，不表示数据面丢帧；"
                                        + "请消费方及时调用 drainFrames()，或移除不需要的拉模式消费者）",
                                spec.connectionId(), evicted);
                    } else {
                        log.debug("[ypbin-iot] inbound aux buffer overflow on connection {}; evicted aux frames={}",
                                spec.connectionId(), evicted);
                    }
                }
                inbound.addLast(payload);
            } finally {
                lock.unlock();
            }
        }
        for (Consumer<byte[]> listener : frameListeners) {
            try {
                listener.accept(payload);
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] frame listener failed on connection {}", spec.connectionId(), ex);
            }
        }
    }

    /**
     * 由传输层回调：channel 变为非活跃。
     */
    void onChannelInactive() {
        state = SessionState.CLOSED;
        closed.set(true);
        closeReason.complete(new CloseReason(CloseCause.REMOTE_CLOSED, "", null, Instant.now()));
    }

    /**
     * 由传输层回调：发生传输层错误。
     *
     * @param cause 异常
     */
    void onError(Throwable cause) {
        state = SessionState.FAILED;
        closed.set(true);
        closeReason.complete(new CloseReason(CloseCause.TRANSPORT_ERROR, "", cause, Instant.now()));
    }
}
