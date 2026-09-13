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
package cn.ypbin.iot.protocol.tcp;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.PointWriteStatus;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.transport.NettyChannelConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TCP 透传设备会话。
 *
 * <p><b>能力声明与语义（刻意的窄口径）</b>：TCP 透传只声明
 * {@code WRITE} 与 {@code SUBSCRIBE_STREAM}：</p>
 * <ul>
 *   <li>{@code write}：把值编码为字节后发送（{@code byte[]} / UTF-8 字符串 / {@code hex:} 十六进制）；</li>
 *   <li>{@code subscribe}：收到字节帧后组装 {@link PointValue} 推送到 egress（或调用方传入的 listener）；</li>
 *   <li>{@code read}：<b>不支持</b>，抛 {@link UnsupportedCapabilityException}。</li>
 * </ul>
 *
 * <p>为什么 {@code read} 不支持：裸 TCP 没有请求-响应语义，无法定义「读一次返回什么」。
 * 数据是被动到达的，对应的是订阅；把「读」实现成「取缓冲区里的帧」会引入不可预测的时序语义。
 * 需要请求-响应语义的协议（Modbus、SNMP）应实现自己的适配器。</p>
 *
 * <p>TCP 报文中收到的字节帧被包装为 {@link PointValue}，其 {@code address} 使用订阅标识
 * （即调用方给出的订阅地址之一），{@code value} 为 {@code byte[]}。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class TcpSession implements DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(TcpSession.class);

    private final String sessionId;

    private final DeviceSpec device;

    private final NettyChannelConnection connection;

    private final AdapterContext context;

    private final Instant boundAt;

    private final Map<String, TcpSubscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicLong subscriptionSequence = new AtomicLong();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.ONLINE;

    /**
     * 创建会话。
     *
     * @param device     设备规格
     * @param connection 传输链路
     * @param context    适配器上下文
     */
    public TcpSession(DeviceSpec device, NettyChannelConnection connection,
            AdapterContext context) {
        this.device = device;
        this.connection = connection;
        this.context = context;
        this.boundAt = context.clock().instant();
        this.sessionId = device.deviceId() + "@" + connection.connectionId();
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public DeviceSpec device() {
        return device;
    }

    @Override
    public String connectionId() {
        return connection.connectionId();
    }

    @Override
    public SessionState state() {
        if (!closed.get() && !connection.isActive()) {
            return SessionState.CLOSED;
        }
        return state;
    }

    @Override
    public Instant boundAt() {
        return boundAt;
    }

    @Override
    public CompletionStage<ReadResult> read(ReadRequest request) {
        return CompletableFuture.failedFuture(
                new UnsupportedCapabilityException(TcpAdapter.PROTOCOL_CODE, "read"));
    }

    @Override
    public CompletionStage<WriteResult> write(WriteRequest request) {
        Instant started = context.clock().instant();
        CompletableFuture<WriteResult> result = new CompletableFuture<>();
        List<PointWriteStatus> statuses = new ArrayList<>();
        advanceWrites(request.writes(), 0, statuses, started, result);
        return result;
    }

    /**
     * 顺序推进写列表。
     *
     * <p><b>刻意不写成「每次递归推进一条」</b>：那样在两种情况下会同步递归——
     * ① 载荷编码失败（纯同步分支）；② {@code connection.write} 对空载荷返回<b>已完成</b>的 Future
     * 时 {@code whenComplete} 内联执行。批量写几万条即触发 {@code StackOverflowError}，
     * 且是从 SPI 同步抛出而非返回失败 Stage。</p>
     *
     * <p>这里改为「同步可推进就继续循环、遇到真正异步才注册回调并返回」，
     * 调用栈深度与在途请求数同阶，而非与写条目数同阶。</p>
     */
    private void advanceWrites(List<PointWrite> writes, int startIndex, List<PointWriteStatus> statuses,
            Instant started, CompletableFuture<WriteResult> result) {
        int index = startIndex;
        while (index < writes.size()) {
            PointWrite write = writes.get(index);
            TcpPayloadCodec.Encoded encoded = TcpPayloadCodec.encode(write.value());
            if (!encoded.success()) {
                statuses.add(PointWriteStatus.fail(write.address(), encoded.messageKey()));
                context.metrics().recordWrite(Duration.ZERO, false);
                index++;
                continue;
            }
            CompletableFuture<Void> pending = connection.write(encoded.payload()).toCompletableFuture();
            if (pending.isDone()) {
                // 已同步完成：继续用循环推进，避免回调内联递归
                recordStatus(write, pending.isCompletedExceptionally(), statuses);
                index++;
                continue;
            }
            int next = index + 1;
            pending.whenComplete((ignored, error) -> {
                recordStatus(write, error != null, statuses);
                advanceWrites(writes, next, statuses, started, result);
            });
            return;
        }
        result.complete(new WriteResult(statuses, Duration.between(started, context.clock().instant())));
    }

    private void recordStatus(PointWrite write, boolean failed, List<PointWriteStatus> statuses) {
        if (failed) {
            statuses.add(PointWriteStatus.fail(write.address(), TcpAdapter.MSG_WRITE_FAILED));
            context.metrics().recordWrite(Duration.ZERO, false);
            context.log(LogLevel.WARN, TcpAdapter.MSG_WRITE_FAILED, device.deviceId());
            return;
        }
        statuses.add(PointWriteStatus.ok(write.address()));
        context.metrics().recordWrite(Duration.ZERO, true);
    }

    @Override
    public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("session already closed: " + sessionId));
        }
        String subscriptionId = "tcp-" + subscriptionSequence.incrementAndGet();
        PointAddress streamAddress = request.addresses().get(0);
        TcpSubscription subscription = new TcpSubscription(subscriptionId, request.addresses(), listener);
        Consumer<byte[]> frameConsumer = payload -> dispatch(subscription, streamAddress, payload);
        subscription.attach(frameConsumer);
        connection.addFrameListener(frameConsumer);
        subscriptions.put(subscriptionId, subscription);
        context.log(LogLevel.DEBUG, "iot.tcp.subscribed", subscriptionId);
        return CompletableFuture.completedFuture(subscription);
    }

    @Override
    public CompletionStage<Void> unsubscribe(SubscriptionHandle handle) {
        if (handle == null) {
            return CompletableFuture.completedFuture(null);
        }
        TcpSubscription subscription = subscriptions.remove(handle.subscriptionId());
        if (subscription == null) {
            // 幂等：已取消或从未存在都视为成功
            return CompletableFuture.completedFuture(null);
        }
        subscription.cancel();
        connection.removeFrameListener(subscription.frameConsumer());
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<PingResult> ping() {
        long started = System.nanoTime();
        if (connection.isActive()) {
            return CompletableFuture.completedFuture(
                    PingResult.alive(Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }
        return CompletableFuture.completedFuture(PingResult.dead(TcpAdapter.MSG_CONNECTION_INACTIVE));
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> extensionType) {
        // TCP 透传在 M0 阶段不提供协议扩展能力
        return Optional.empty();
    }

    @Override
    public CompletionStage<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        state = SessionState.CLOSED;
        subscriptions.values().forEach(subscription -> {
            subscription.cancel();
            connection.removeFrameListener(subscription.frameConsumer());
        });
        subscriptions.clear();
        connection.close();
        return CompletableFuture.completedFuture(null);
    }

    private void dispatch(TcpSubscription subscription, PointAddress address, byte[] payload) {
        if (!subscription.active()) {
            return;
        }
        PointValue value = PointValue.good(address, payload, context.clock().instant());
        subscription.incrementDelivered();
        context.metrics().recordSubscriptionBatch(1);
        DataListener listener = subscription.listener();
        if (listener != null) {
            try {
                listener.onData(value);
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] data listener failed for session {}", sessionId, ex);
            }
            return;
        }
        context.egress().emit(new DataBatch(device.deviceId(), device.protocol(),
                connection.connectionId(), context.clock().instant(), List.of(value)));
    }

    /**
     * TCP 流式订阅句柄。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    static final class TcpSubscription implements SubscriptionHandle {

        private final String subscriptionId;

        private final List<PointAddress> addresses;

        private final DataListener listener;

        private final AtomicLong delivered = new AtomicLong();

        private final AtomicBoolean active = new AtomicBoolean(true);

        private volatile Consumer<byte[]> frameConsumer;

        private TcpSubscription(String subscriptionId, List<PointAddress> addresses, DataListener listener) {
            this.subscriptionId = subscriptionId;
            this.addresses = List.copyOf(addresses);
            this.listener = listener;
        }

        private void attach(Consumer<byte[]> consumer) {
            this.frameConsumer = consumer;
        }

        private Consumer<byte[]> frameConsumer() {
            return frameConsumer;
        }

        private DataListener listener() {
            return listener;
        }

        private void incrementDelivered() {
            delivered.incrementAndGet();
        }

        private void cancel() {
            active.set(false);
        }

        @Override
        public String subscriptionId() {
            return subscriptionId;
        }

        @Override
        public List<PointAddress> addresses() {
            return addresses;
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public long deliveredCount() {
            return delivered.get();
        }
    }
}
