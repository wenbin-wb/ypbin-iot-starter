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
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.BlockingProtocolAdapter;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import cn.ypbin.iot.core.util.Stages;
import cn.ypbin.iot.transport.FramingSpec;
import cn.ypbin.iot.transport.NettyChannelConnection;
import cn.ypbin.iot.transport.NettyTransport;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 通用 TCP 透传适配器：把设备当作裸字节流的读写通道，不做任何协议解释。
 *
 * <p>它是 M0 的验收载体——用于验证异步契约、虚拟线程桥接、连接生命周期、
 * 微批出口、TCK 与架构门禁是否成立，而不是为了覆盖某个具体设备的通信需求。</p>
 *
 * <p><b>能力</b>：{@code WRITE} + {@code SUBSCRIBE_STREAM}。不声明 {@code READ}——
 * 裸 TCP 没有请求-响应语义（详见 {@link TcpSession}）。</p>
 *
 * <p><b>线程模型</b>：基于 Netty 的异步链路，{@link #open} 直接返回链路 Stage，
 * 不经过 {@link BlockingProtocolAdapter} 的线程桥接（那是留给阻塞式协议库的）。
 * 因此本适配器不涉及 native 调用，也不受 pinning 约束。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class TcpAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(TcpAdapter.class);

    /** 协议标识。 */
    public static final ProtocolCode PROTOCOL_CODE = ProtocolCode.of("tcp");

    /** 写失败的消息键。 */
    public static final String MSG_WRITE_FAILED = "iot.tcp.write.failed";

    /** 链路不活跃的消息键。 */
    public static final String MSG_CONNECTION_INACTIVE = "iot.tcp.connection.inactive";

    /** 载荷非合法 UTF-8（payload-format=text/number 时）的消息键。 */
    public static final String MSG_PAYLOAD_NOT_UTF8 = "iot.tcp.payload.not-utf8";

    /** 载荷无法解析为数值（payload-format=number 时）的消息键。 */
    public static final String MSG_PAYLOAD_NOT_NUMBER = "iot.tcp.payload.not-number";

    /** 订阅地址超过 1 个（非 KEY_VALUE 格式的 TCP 是单点位透传流）的消息键。 */
    public static final String MSG_MULTI_ADDRESS_UNSUPPORTED = "iot.tcp.subscribe.multi-address-unsupported";

    /** 帧不是 KEY=VALUE 键值格式（payload-format=key-value 时）的消息键。 */
    public static final String MSG_PAYLOAD_NOT_KEY_VALUE = "iot.tcp.payload.not-key-value";

    /** 帧内找不到 selector 对应字段的消息键（仅该点位 BAD，不静默丢弃）。 */
    public static final String MSG_SELECTOR_NOT_FOUND = "iot.tcp.selector.not-found";

    /** 帧内 selector 对应字段值为空的消息键。 */
    public static final String MSG_SELECTOR_VALUE_EMPTY = "iot.tcp.selector.value-empty";

    private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
            .code(PROTOCOL_CODE)
            .name("TCP 透传")
            .vendor("Netty")
            .transport("TCP")
            .capabilities(ProtocolCapability.WRITE, ProtocolCapability.SUBSCRIBE_STREAM)
            // 显式声明为逐点位单订阅（与默认保守侧一致，但与「未声明」语义不同：
            // 未声明意味着「协议作者没表态」，这里是协议作者明确表态）。
            // 依据：非 key-value 形态下裸 TCP 帧没有寻址语义，TcpSession#subscribe 对
            // addresses().size() > 1 直接 fail-fast（iot.tcp.subscribe.multi-address-unsupported）。
            // key-value 形态（payload-format=key-value）确实支持一次订阅多个 selector，
            // 但它是由**配置**而非协议身份决定的形态，声明是协议级的、无法随配置变化；
            // 因此在声明层面取两种形态的交集（PER_ADDRESS，对两者都正确，代价是
            // key-value 下 N 个点位拆成 N 次订阅）。若要为 key-value 放宽，需把
            // 寻址方式做成「按设备配置」的能力而非协议级声明 —— 属后续独立设计。
            .subscriptionAddressing(SubscriptionAddressing.PER_ADDRESS)
            .runtimeVersionRange("0.1.0", "1.0.0")
            .attribute("defaultPort", "0")
            .attribute("payloadFormat", "binary|text|number|key-value")
            .build();

    private final NettyTransport transport;

    private final FramingSpec framingSpec;

    private final Duration idleInterval;

    /** 入站载荷解码格式（默认 binary=向后兼容）。 */
    private final TcpPayloadFormat payloadFormat;

    /**
     * 创建适配器（默认 {@code payload-format=binary}，与 0.1.0 行为一致）。
     *
     * @param transport    传输底座
     * @param framingSpec  帧定界规格
     * @param idleInterval 空闲检测间隔
     */
    public TcpAdapter(NettyTransport transport, FramingSpec framingSpec, Duration idleInterval) {
        this(transport, framingSpec, idleInterval, TcpPayloadFormat.BINARY);
    }

    /**
     * 创建适配器。
     *
     * @param transport    传输底座
     * @param framingSpec  帧定界规格
     * @param idleInterval 空闲检测间隔
     * @param payloadFormat 入站载荷解码格式（{@link TcpPayloadFormat#BINARY}=原样交付，向后兼容）
     */
    public TcpAdapter(NettyTransport transport, FramingSpec framingSpec, Duration idleInterval,
            TcpPayloadFormat payloadFormat) {
        this.transport = transport;
        this.framingSpec = framingSpec;
        this.idleInterval = idleInterval;
        this.payloadFormat = payloadFormat == null ? TcpPayloadFormat.BINARY : payloadFormat;
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public Set<ProtocolCapability> capabilities() {
        return DESCRIPTOR.capabilities();
    }

    @Override
    public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
        log.debug("[ypbin-iot] opening tcp connection {} with {}", spec.connectionId(), framingSpec);
        return transport.connect(spec, idleInterval).thenApply(connection -> connection);
    }

    /**
     * 绑定逻辑设备：为链路创建 TCP 会话并装入。
     *
     * <p>TCP 透传是 1:1 协议（一条链路一个设备），因此会话直接绑定在链路上；
     * 设备的 {@code localAddress} 在透传场景下不参与寻址。1:N 协议（Modbus 网关）
     * 必须自行管理设备视图并覆写本方法。</p>
     */
    @Override
    public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
            AdapterContext context) {
        if (connection instanceof NettyChannelConnection nettyConnection) {
            TcpSession session = new TcpSession(device, nettyConnection, context, payloadFormat);
            nettyConnection.bindSession(session);
            return CompletableFuture.completedFuture(session);
        }
        return CompletableFuture.failedFuture(new IllegalStateException(
                "TcpAdapter requires a NettyChannelConnection but got "
                        + connection.getClass().getName()));
    }

    @Override
    public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        return transport.connect(spec, idleInterval)
                .handle((connection, error) -> {
                    if (error != null) {
                        // 指标维度用稳定协议码；ProbeResult 仍带出**真实**原因（TLS 未实现、端点
                        // 不可达、超时是不同的排查方向，一律折叠成 MSG_CONNECTION_INACTIVE
                        // 会把「配了未实现的 TLS」误读为网络问题）
                        context.metrics().recordError(MSG_CONNECTION_INACTIVE);
                        return ProbeResult.unreachable(DESCRIPTOR,
                                Stages.messageKeyOf(error, MSG_CONNECTION_INACTIVE));
                    }
                    Map<String, String> details = connection.describe();
                    connection.close();
                    return ProbeResult.reachable(DESCRIPTOR, details);
                });
    }
}
