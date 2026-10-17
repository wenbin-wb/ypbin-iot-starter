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
import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.PointWriteStatus;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.transport.NettyChannelConnection;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
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
 * （即调用方给出的订阅地址），{@code value} 按 {@link TcpPayloadFormat} 解码
 * （默认 {@code binary}：原样交付 {@code byte[]}；{@code text}/{@code number} 见下方载荷解码节）。</p>
 *
 * <p><b>单点位契约（UP-4）与 valueSelector 演进</b>：裸 TCP 帧默认没有寻址语义，因此
 * {@code payload-format=binary|text|number} 时一次订阅只接受 <b>1 个地址</b>
 * （{@code subscribe()} 对多地址 <b>fail-fast</b>——拒绝在「声称订阅了 N 个点位」的同时静默只交付 1 个）。
 * 配置 {@code payload-format=key-value} 后，订阅地址（{@code PointAddress.raw}）被解释为帧内
 * {@code KEY=VALUE} 字段的 selector，同一帧可服务多个点位；selector 语法非法在订阅期 fail-fast，
 * 运行时 selector 未命中只让该点位产出 BAD，绝不静默丢弃。语义与失败矩阵见
 * {@code docs/TCP-VALUE-SELECTOR.md}。</p>
 *
 * <p><b>载荷解码（UP-3）</b>：入站帧按 {@code ypbin.iot.protocol.tcp.payload-format} 解码——
 * {@code binary}（默认，原样交付 {@code byte[]}，向后兼容）/ {@code text}（严格 UTF-8）/
 * {@code number}（数值）/ {@code key-value}（键值帧按 selector 取字段）；解码失败产出
 * <b>BAD 质量 + 明确消息键</b>，不把畸形内容当 GOOD 交付。</p>
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

    /** 入站载荷解码格式（见 {@link TcpPayloadFormat}；binary=原样交付，向后兼容）。 */
    private final TcpPayloadFormat payloadFormat;

    private final Instant boundAt;

    private final Map<String, TcpSubscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicLong subscriptionSequence = new AtomicLong();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.ONLINE;

    /**
     * 创建会话。
     *
     * @param device       设备规格
     * @param connection   传输链路
     * @param context      适配器上下文
     * @param payloadFormat 入站载荷解码格式（{@link TcpPayloadFormat#BINARY}=原样交付，向后兼容）
     */
    public TcpSession(DeviceSpec device, NettyChannelConnection connection,
            AdapterContext context, TcpPayloadFormat payloadFormat) {
        this.device = device;
        this.connection = connection;
        this.context = context;
        this.payloadFormat = payloadFormat == null ? TcpPayloadFormat.BINARY : payloadFormat;
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
                // 与下面 payload 的断言同一理由：failure 与 messageKey 非空是配套的，
                // 但类型系统表达不了 ⇒ 显式断言（NullAway 要求 @NonNull，且避免 NPE 隐式传播）
                String messageKey = Objects.requireNonNull(encoded.messageKey(),
                        "failed encoding must carry a message key");
                statuses.add(PointWriteStatus.fail(write.address(), messageKey));
                context.metrics().recordWrite(Duration.ZERO, false);
                recordEncodeFailure(messageKey);
                index++;
                continue;
            }
            // success 与 payload 非空是配套的，但类型系统表达不了这个关联：
            // 显式断言，把「成功却没有负载」变成带原因的失败而不是 NPE
            byte[] payload = Objects.requireNonNull(encoded.payload(),
                    "successful encoding must carry a payload");
            CompletableFuture<Void> pending = connection.write(payload).toCompletableFuture();
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
            context.metrics().recordError(TcpAdapter.MSG_WRITE_FAILED);
            context.log(LogLevel.WARN, TcpAdapter.MSG_WRITE_FAILED, device.deviceId());
            return;
        }
        statuses.add(PointWriteStatus.ok(write.address()));
        context.metrics().recordWrite(Duration.ZERO, true);
    }

    /**
     * 把一次写载荷编码失败计入错误指标。
     *
     * <p>只认两个稳定错误码作为标签；其它字符串（未来新增原因）不新造维度，
     * 避免标签基数失控（见 {@link cn.ypbin.iot.core.context.MetricsRecorder#recordError} 的约定）。</p>
     *
     * @param messageKey 编码失败的消息键
     */
    private void recordEncodeFailure(String messageKey) {
        if (TcpPayloadCodec.MSG_HEX_INVALID.equals(messageKey)) {
            context.metrics().recordError(TcpPayloadCodec.MSG_HEX_INVALID);
        } else {
            context.metrics().recordError(TcpPayloadCodec.MSG_PAYLOAD_UNSUPPORTED);
        }
    }

    /**
     * 把一次入站帧解码失败计入错误指标。
     *
     * <p>只统计白名单内的稳定错误码；未知原因不产生标签（同样是为了避免基数爆炸）。</p>
     *
     * @param reason 失败点位的质量原因（消息键）
     */
    private void recordDecodeFailure(String reason) {
        switch (reason) {
            case TcpAdapter.MSG_PAYLOAD_NOT_UTF8 ->
                    context.metrics().recordError(TcpAdapter.MSG_PAYLOAD_NOT_UTF8);
            case TcpAdapter.MSG_PAYLOAD_NOT_NUMBER ->
                    context.metrics().recordError(TcpAdapter.MSG_PAYLOAD_NOT_NUMBER);
            case TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE ->
                    context.metrics().recordError(TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE);
            case TcpAdapter.MSG_SELECTOR_NOT_FOUND ->
                    context.metrics().recordError(TcpAdapter.MSG_SELECTOR_NOT_FOUND);
            case TcpAdapter.MSG_SELECTOR_VALUE_EMPTY ->
                    context.metrics().recordError(TcpAdapter.MSG_SELECTOR_VALUE_EMPTY);
            default -> {
                // 未知原因不进标签：只统计文档承诺的稳定错误码
            }
        }
    }

    @Override
    public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("session already closed: " + sessionId));
        }
        // UP-4（B 方案）在 payload-format=key-value 时演进为「selector 多点位流」：
        // 订阅地址是帧内字段键，同一帧可派发到 N 个点位。selector 语法非法必须在订阅期 fail-fast。
        // 非 key-value 格式仍保持「单点位流」：裸 TCP 帧没有寻址语义，多地址直接拒绝，
        // 绝不在「声称订阅了 N 个点位」的同时静默只交付 1 个。
        if (payloadFormat == TcpPayloadFormat.KEY_VALUE) {
            for (PointAddress address : request.addresses()) {
                try {
                    TcpValueSelector.validateSelector(address);
                } catch (AddressParseException ex) {
                    return CompletableFuture.failedFuture(ex);
                }
            }
        } else if (request.addresses().size() > 1) {
            context.metrics().recordError(TcpAdapter.MSG_MULTI_ADDRESS_UNSUPPORTED);
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    TcpAdapter.MSG_MULTI_ADDRESS_UNSUPPORTED
                            + ": 非 key-value 的 tcp 透传为单点位流，一次订阅仅支持 1 个地址，收到 "
                            + request.addresses().size() + " 个"));
        }
        String subscriptionId = "tcp-" + subscriptionSequence.incrementAndGet();
        TcpSubscription subscription = new TcpSubscription(subscriptionId, request.addresses(), listener);
        Consumer<byte[]> frameConsumer = payload -> dispatch(subscription, payload);
        subscription.attach(frameConsumer);
        connection.addFrameListener(frameConsumer);
        subscriptions.put(subscriptionId, subscription);
        context.log(LogLevel.DEBUG, "iot.tcp.subscribed", subscriptionId);
        return CompletableFuture.completedFuture(subscription);
    }

    /**
     * 摘除该订阅注册的帧监听器。
     *
     * <p>判空是<b>防御性</b>的：正常路径下 {@code subscribe()} 里 {@code attach()} 必先于
     * {@code subscriptions.put(...)}，所以订阅一旦在册就一定有消费者。留着判空是为了让
     * 「未来某条新路径漏了 attach」表现为「少摘一个监听器」而不是抛错。</p>
     *
     * @param subscription 订阅
     */
    private void removeFrameListener(TcpSubscription subscription) {
        Consumer<byte[]> consumer = subscription.frameConsumer();
        if (consumer != null) {
            connection.removeFrameListener(consumer);
        }
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
        removeFrameListener(subscription);
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
            removeFrameListener(subscription);
        });
        subscriptions.clear();
        connection.close();
        return CompletableFuture.completedFuture(null);
    }

    private void dispatch(TcpSubscription subscription, byte[] payload) {
        if (!subscription.active()) {
            return;
        }
        List<PointValue> values = decodeAll(subscription.addresses(), payload);
        // 解码失败（非 UTF-8 / 非数值 / 非键值帧 / selector 未命中）必须留下错误指标：
        // 每个 BAD 点位各记一次，与同帧其它健康点位互不掩盖。
        for (PointValue value : values) {
            if (!value.isGood() && value.qualityReason() != null) {
                recordDecodeFailure(value.qualityReason());
            }
        }
        subscription.incrementDelivered(values.size());
        context.metrics().recordSubscriptionBatch(values.size());
        DataListener listener = subscription.listener();
        if (listener != null) {
            // 本方法运行在 Netty EventLoop 上：必须经投递器卸载宿主代码（I4）。
            // 逐点位投递：一个点位回调抛异常不得吞掉同帧其余点位（与 MQTT/OPC UA 一致）。
            for (PointValue value : values) {
                context.delivery().dispatch(() -> listener.onData(value));
            }
            return;
        }
        context.egress().emit(new DataBatch(device.deviceId(), device.protocol(),
                connection.connectionId(), context.clock().instant(), values));
    }

    /**
     * 按配置的 {@link TcpPayloadFormat} 解码入站帧为点位值列表。
     *
     * <p><b>解码失败显式可见（UP-3）</b>：非 UTF-8 / 非数值 / 非 key-value 帧产出
     * <b>BAD 质量 + 明确消息键</b>，绝不允许把畸形内容当 GOOD 交付（替代旧的「整帧
     * {@code byte[]} 直接当值」——那会让落库变成 {@code [B@<hash>} 字面文本，
     * 曲线/聚合全部不可用且无失败信号）。</p>
     *
     * @param addresses 本次订阅的全部地址
     * @param payload   帧字节
     * @return 与 {@code addresses} 等长的点位值列表
     */
    private List<PointValue> decodeAll(List<PointAddress> addresses, byte[] payload) {
        Instant now = context.clock().instant();
        return switch (payloadFormat) {
            case NUMBER -> List.of(decodeNumber(addresses.get(0), payload, now));
            case TEXT -> List.of(decodeText(addresses.get(0), payload, now));
            case KEY_VALUE -> TcpValueSelector.select(addresses, payload, now);
            // binary 交付的是原始字节：先过宿主可插解码 SPI（issue #30）。
            // 无解码器/未认领/解码失败的行为见 AdapterContext#decodePayload 的矩阵，
            // 失败一律 BAD（绝不让畸形字节以 GOOD 形态流到宿主）。
            case BINARY -> List.of(context.decodePayload(
                    PointValue.good(addresses.get(0), payload.clone(), now),
                    device.deviceId(), connection.connectionId()));
        };
    }

    private PointValue decodeNumber(PointAddress address, byte[] payload, Instant now) {
        if (!TcpPayloadFormat.isValidUtf8(payload)) {
            return PointValue.bad(address, Quality.BAD, TcpAdapter.MSG_PAYLOAD_NOT_UTF8, now);
        }
        String text = new String(payload, StandardCharsets.UTF_8).trim();
        try {
            return PointValue.good(address, Double.parseDouble(text), now);
        } catch (NumberFormatException ex) {
            return PointValue.bad(address, Quality.BAD, TcpAdapter.MSG_PAYLOAD_NOT_NUMBER, now);
        }
    }

    private PointValue decodeText(PointAddress address, byte[] payload, Instant now) {
        if (!TcpPayloadFormat.isValidUtf8(payload)) {
            return PointValue.bad(address, Quality.BAD, TcpAdapter.MSG_PAYLOAD_NOT_UTF8, now);
        }
        return PointValue.good(address, new String(payload, StandardCharsets.UTF_8), now);
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

        private volatile @Nullable Consumer<byte[]> frameConsumer;

        private TcpSubscription(String subscriptionId, List<PointAddress> addresses, DataListener listener) {
            this.subscriptionId = subscriptionId;
            this.addresses = List.copyOf(addresses);
            this.listener = listener;
        }

        private void attach(Consumer<byte[]> consumer) {
            this.frameConsumer = consumer;
        }

        private @Nullable Consumer<byte[]> frameConsumer() {
            // 未注册帧消费者时为空；调用方按「有则消费」处理
            return frameConsumer;
        }

        private DataListener listener() {
            return listener;
        }

        private void incrementDelivered(int pointCount) {
            delivered.addAndGet(pointCount);
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
