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
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.transport.FramingSpec;
import cn.ypbin.iot.transport.NettyChannelConnection;
import cn.ypbin.iot.transport.NettyTransport;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.Set;
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

    private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
            .code(PROTOCOL_CODE)
            .name("TCP 透传")
            .vendor("Netty")
            .transport("TCP")
            .capabilities(ProtocolCapability.WRITE, ProtocolCapability.SUBSCRIBE_STREAM)
            .runtimeVersionRange("0.1.0", "1.0.0")
            .attribute("defaultPort", "0")
            .build();

    private final NettyTransport transport;

    private final FramingSpec framingSpec;

    private final Duration idleInterval;

    /**
     * 创建适配器。
     *
     * @param transport    传输底座
     * @param framingSpec  帧定界规格
     * @param idleInterval 空闲检测间隔
     */
    public TcpAdapter(NettyTransport transport, FramingSpec framingSpec, Duration idleInterval) {
        this.transport = transport;
        this.framingSpec = framingSpec;
        this.idleInterval = idleInterval;
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
            TcpSession session = new TcpSession(device, nettyConnection, context);
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
                        return ProbeResult.unreachable(DESCRIPTOR, MSG_CONNECTION_INACTIVE);
                    }
                    Map<String, String> details = connection.describe();
                    connection.close();
                    return ProbeResult.reachable(DESCRIPTOR, details);
                });
    }
}
