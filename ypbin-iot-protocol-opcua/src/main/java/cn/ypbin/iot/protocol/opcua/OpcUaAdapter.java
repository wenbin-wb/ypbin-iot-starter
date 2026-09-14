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
package cn.ypbin.iot.protocol.opcua;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.util.Stages;
import cn.ypbin.iot.protocol.opcua.autoconfigure.OpcUaProperties;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 协议适配器（基于 Eclipse Milo）。
 *
 * <p><b>能力口径</b>：{@code READ} + {@code WRITE} + {@code SUBSCRIBE_NATIVE} + {@code BROWSE}
 * + {@code MULTI_DEVICE_LINK}。这是 M1 三个模块里能力最全的一个——
 * 读、写、原生订阅、地址空间浏览都由协议标准提供，不需要框架兜底。</p>
 *
 * <p><b>安全口径</b>：默认 {@code SecurityPolicy#None}（明文），因为现场大量旧设备只支持明文；
 * 但配置了非 None 策略时**必须真正生效**，未实现就必须 fail-fast——
 * 「以为加密了」比报错危险得多（本仓已有两次同类教训）。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
public final class OpcUaAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(OpcUaAdapter.class);

    /** 协议标识。 */
    public static final ProtocolCode PROTOCOL_CODE = ProtocolCode.of("opcua");

    /** OPC UA TCP 承载的标准 scheme（注意含点号）。 */
    public static final String OPC_TCP_SCHEME = "opc.tcp";

    /** 默认端口。 */
    public static final int DEFAULT_PORT = 4840;

    /** 默认请求超时。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /** 地址非法的消息键。 */
    public static final String MSG_ADDRESS_INVALID = "iot.opcua.address.invalid";

    /** 读失败的消息键。 */
    public static final String MSG_READ_FAILED = "iot.opcua.read.failed";

    /** 读超时的消息键。 */
    public static final String MSG_READ_TIMEOUT = "iot.opcua.read.timeout";

    /** 无读取结果的消息键。 */
    public static final String MSG_NO_RESULT = "iot.opcua.read.no-result";

    /** 写失败的消息键。 */
    public static final String MSG_WRITE_FAILED = "iot.opcua.write.failed";

    /** 订阅建立的消息键。 */
    public static final String MSG_SUBSCRIBED = "iot.opcua.subscribed";

    /** 会话已关闭的消息键。 */
    public static final String MSG_SESSION_CLOSED = "iot.opcua.session.closed";

    /** 链路不可用的消息键。 */
    public static final String MSG_CONNECTION_INACTIVE = "iot.opcua.connection.inactive";

    /** 浏览失败的消息键。 */
    public static final String MSG_BROWSE_FAILED = "iot.opcua.browse.failed";

    /** 浏览被截断的消息键。 */
    public static final String MSG_BROWSE_TRUNCATED = "iot.opcua.browse.truncated";

    /** 安全策略不支持的消息键。 */
    public static final String MSG_SECURITY_UNSUPPORTED = "iot.opcua.security.unsupported";

    private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
            .code(PROTOCOL_CODE)
            .name("OPC UA")
            .vendor("Eclipse Milo")
            .stackVersion("1.1.7")
            .transport("TCP")
            .capabilities(ProtocolCapability.READ, ProtocolCapability.WRITE,
                    ProtocolCapability.SUBSCRIBE_NATIVE, ProtocolCapability.BROWSE,
                    ProtocolCapability.MULTI_DEVICE_LINK)
            .extensions(BrowseExtension.class)
            .runtimeVersionRange("0.1.0", "1.0.0")
            .attribute("defaultPort", String.valueOf(DEFAULT_PORT))
            .build();

    private final OpcUaProperties properties;

    /**
     * 创建适配器。
     *
     * @param properties 协议配置
     */
    public OpcUaAdapter(OpcUaProperties properties) {
        this.properties = properties == null ? new OpcUaProperties(null, null, null, null, null, null,
                null, null) : properties;
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
        Endpoint endpoint = spec.endpoint();
        String scheme = endpoint.scheme();
        // 注意：URI.getScheme() 对 opc.tcp:// 返回的是 "opc.tcp"（scheme 允许点号），
        // 不是 "opc"。这个差异只有端到端测试才会暴露 —— 按直觉写白名单会让所有连接被拒。
        if (!OPC_TCP_SCHEME.equals(scheme) && !"opc".equals(scheme) && !"opcua".equals(scheme)
                && !"tcp".equals(scheme)) {
            return Stages.failed(new ConnectionException(spec.connectionId(), MSG_CONNECTION_INACTIVE, scheme));
        }
        if (spec.tls().enabled()) {
            // 与 Modbus / MQTT 同一口径：未实现的加密能力必须 fail-fast，绝不静默降级为明文
            return Stages.failed(new ConnectionException(spec.connectionId(), IotMessageKeys.CONFIG_INVALID,
                    "transport TLS not implemented; configure OPC UA security policy instead"));
        }
        if (!properties.isPlaintext()) {
            // 非 None 策略在 M1 未实现：接受它会让用户以为在签名/加密，实际仍是明文会话
            return Stages.failed(new ConnectionException(spec.connectionId(), MSG_SECURITY_UNSUPPORTED,
                    properties.securityPolicy(), properties.securityMode()));
        }
        int port = endpoint.port() > 0 ? endpoint.port() : DEFAULT_PORT;
        String endpointUrl = "opc.tcp://" + endpoint.host() + ":" + port;
        OpcUaClient client;
        try {
            client = OpcUaClient.create(endpointUrl);
        } catch (org.eclipse.milo.opcua.stack.core.UaException ex) {
            // create 抛受检异常（端点 URL 非法/无法解析）：必须以失败 Stage 交付而不是同步抛出
            return Stages.failed(new ConnectionException(spec.connectionId(), ex,
                    IotMessageKeys.CONFIG_INVALID, endpointUrl));
        } catch (RuntimeException ex) {
            return Stages.failed(new ConnectionException(spec.connectionId(), ex,
                    IotMessageKeys.CONFIG_INVALID, endpointUrl));
        }
        try {
            return Stages.normalize(client.connectAsync()
                    .orTimeout(Math.max(1L, spec.connectTimeout().toMillis()),
                            java.util.concurrent.TimeUnit.MILLISECONDS)
                    .thenApply(connected -> {
                        log.debug("[ypbin-iot] opcua connection {} opened to {}.", spec.connectionId(),
                                endpointUrl);
                        return (ProtocolConnection) new OpcUaConnection(spec, connected);
                    })
                    .exceptionally(error -> {
                        throw new ConnectionException(spec.connectionId(), error,
                                IotMessageKeys.CONNECTION_FAILED, endpointUrl);
                    }));
        } catch (RuntimeException ex) {
            return Stages.failed(new ConnectionException(spec.connectionId(), ex,
                    IotMessageKeys.CONFIG_INVALID, endpointUrl));
        }
    }

    @Override
    public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
            AdapterContext context) {
        if (!(connection instanceof OpcUaConnection opcUaConnection)) {
            return Stages.failed(new ConnectionException(connection.connectionId(),
                    MSG_CONNECTION_INACTIVE, connection.getClass().getName()));
        }
        OpcUaSession session = new OpcUaSession(device, opcUaConnection, context,
                properties.maxNodesPerRead(), properties.publishingInterval());
        opcUaConnection.register(session);
        return CompletableFuture.completedFuture(session);
    }

    @Override
    public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        return Stages.normalize(open(spec, context).thenApply(connection -> {
            try {
                return ProbeResult.reachable(DESCRIPTOR, connection.describe());
            } finally {
                connection.close();
            }
        }).exceptionally(error -> ProbeResult.unreachable(DESCRIPTOR, MSG_CONNECTION_INACTIVE)));
    }
}
