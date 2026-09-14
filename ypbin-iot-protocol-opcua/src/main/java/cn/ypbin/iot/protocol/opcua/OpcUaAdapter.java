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
import cn.ypbin.iot.core.exception.IotException;
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
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.SessionActivityListener;
import org.eclipse.milo.opcua.sdk.client.UaSession;
import org.eclipse.milo.opcua.stack.core.UaException;
import org.eclipse.milo.opcua.stack.core.types.builtin.unsigned.UInteger;
import org.eclipse.milo.opcua.stack.core.types.structured.EndpointDescription;
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

    /** 订阅建立失败的消息键。 */
    public static final String MSG_SUBSCRIBE_FAILED = "iot.opcua.subscribe.failed";

    /** 订阅部分失败的消息键。 */
    public static final String MSG_SUBSCRIBE_PARTIAL_FAILED = "iot.opcua.subscribe.partial-failed";

    /** 节点状态码非 Good 的消息键。 */
    public static final String MSG_STATUS_BAD = "iot.opcua.status.bad";

    /** 凭据需要安全策略的消息键。 */
    public static final String MSG_CREDENTIALS_NEED_SECURITY = "iot.opcua.credentials.need-security";

    /** 缺少客户端证书（keystore）的消息键。 */
    public static final String MSG_KEYSTORE_MISSING = "iot.opcua.keystore.missing";

    /** 未配置信任列表的消息键。 */
    public static final String MSG_TRUST_NOT_CONFIGURED = "iot.opcua.trust.not-configured";

    /** 取不到凭据的消息键。 */
    public static final String MSG_CREDENTIAL_MISSING = "iot.opcua.credential.missing";

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

    /** 浏览时节点无法解析为本地 NodeId 的消息键。 */
    public static final String MSG_BROWSE_UNRESOLVED = "iot.opcua.browse.unresolved";

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
                null, null, null, null, null, null, null) : properties;
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
        if (properties.hasCredentials() && properties.isPlaintext()) {
            // 用户名密码在 SecurityPolicy#None 下是**明文**传输的（Nonce 加密只在非 None 策略下生效）。
            // 而本模块尚未实现任何非 None 策略 —— 唯一负责任的行为是拒绝，
            // 而不是把凭据明晃晃地发出去、同时让宿主以为"认证过了"。
            return Stages.failed(new ConnectionException(spec.connectionId(), MSG_CREDENTIALS_NEED_SECURITY,
                    properties.username()));
        }
        int port = endpoint.port() > 0 ? endpoint.port() : DEFAULT_PORT;
        String endpointUrl = "opc.tcp://" + endpoint.host() + ":" + port;
        // 关键：OpcUaClient.create(...) 内部要做端点发现，是**同步网络调用**（实测对静默对端
        // 耗时 11.4s）。因此不能在这里同步调用——必须整体搬到平台线程池，
        // 否则会阻塞调用线程（SPI §2 禁止），且 spec.connectTimeout 根本约束不到它。
        long connectTimeoutMillis = Math.max(1L, spec.connectTimeout().toMillis());
        return Stages.normalize(CompletableFuture
                .supplyAsync(() -> createAndConnect(spec, context, endpointUrl),
                        context.scheduler().platformThreadExecutor())
                .orTimeout(connectTimeoutMillis, TimeUnit.MILLISECONDS)
                .thenApply(connected -> opened(spec, endpointUrl, connected))
                .exceptionally(error -> {
                    Throwable cause = Stages.unwrap(error);
                    // 已经是领域异常就**原样抛出**：重包成 CONNECTION_FAILED 会把
                    // 「keystore 缺失」「策略不支持」这类配置原因抹平，
                    // 宿主看到的只有「连接失败」，排查方向被引向网络
                    if (cause instanceof IotException domain) {
                        throw domain;
                    }
                    if (cause instanceof TimeoutException) {
                        throw new ConnectionException(spec.connectionId(), cause,
                                IotMessageKeys.CONNECTION_TIMEOUT, endpointUrl);
                    }
                    throw new ConnectionException(spec.connectionId(), cause,
                            IotMessageKeys.CONNECTION_FAILED, endpointUrl);
                }));
    }

    /**
     * 建客户端并连接（在平台线程池上执行，允许阻塞）。
     *
     * <p>失败时必须显式断开：{@code create} 可能已经建出底层 channel，
     * 不回收会留下「对端看到的已接受连接 + 本端仍在发心跳」的泄漏。</p>
     */
    private OpcUaClient createAndConnect(ConnectionSpec spec, AdapterContext context,
            String endpointUrl) {
        OpcUaClient client = null;
        try {
            client = createClient(spec, context, endpointUrl);
            return client.connectAsync()
                    .orTimeout(Math.max(1L, spec.connectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                    .join();
        } catch (Exception ex) {
            if (client != null) {
                try {
                    client.disconnectAsync().join();
                } catch (RuntimeException ignored) {
                    log.warn("[ypbin-iot] failed to clean up opcua client after connect failure for {}",
                            spec.connectionId(), ignored);
                }
            }
            throw ex instanceof RuntimeException runtime
                    ? runtime : new IllegalStateException("opcua connect failed", ex);
        }
    }

    /**
     * 创建客户端。
     *
     * <p>明文策略走无参重载；非 None 策略必须携带证书、校验器与身份，
     * 并按安全策略选出匹配的端点——选错端点会让服务端在握手阶段拒绝，
     * 而错误看起来像「端点不可达」。</p>
     */
    private OpcUaClient createClient(ConnectionSpec spec, AdapterContext context, String endpointUrl)
            throws UaException {
        if (properties.isPlaintext()) {
            return OpcUaClient.create(endpointUrl);
        }
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties, context, spec.connectionId());
        return OpcUaClient.create(endpointUrl,
                endpoints -> selectEndpoint(endpoints, spec),
                transportConfig -> {
                    // 必须把库内部的超时也设成同一个值：OpcUaClient.create 内部会做端点发现，
                    // 用的是 Milo 默认超时（connect 5s + acknowledge 5s）。若只在外层 orTimeout，
                    // 两者就在赛跑——超时到底由谁触发不确定，表现为偶发失败（本仓实测到过 TCK 偶发超时）。
                    UInteger timeout = UInteger.valueOf(Math.max(1L, spec.connectTimeout().toMillis()));
                    transportConfig.setConnectTimeout(timeout);
                    transportConfig.setAcknowledgeTimeout(timeout);
                },
                config -> {
                    config.setKeyPair(material.keyPair());
                    config.setCertificate(material.certificate());
                    config.setCertificateChain(new X509Certificate[] {material.certificate()});
                    config.setCertificateValidator(material.certificateValidator());
                    if (material.identityProvider() != null) {
                        config.setIdentityProvider(material.identityProvider());
                    }
                });
    }

    /**
     * 归一化安全模式写法。
     *
     * <p>Milo 枚举的 {@code name()} 是 PascalCase（{@code SignAndEncrypt}），而配置与文档里
     * 习惯写 {@code SIGN_AND_ENCRYPT}。不归一化就永远匹配不上，表现为「配了策略却选不中端点」——
     * 而 Milo 只会报一个 {@code no endpoint selected}，看不出是写法问题。</p>
     */
    private static String normalizeMode(String mode) {
        return mode == null ? "" : mode.replace("_", "").toLowerCase(Locale.ROOT);
    }

    /**
     * 按配置的安全策略与模式选择服务端端点。
     */
    private Optional<EndpointDescription> selectEndpoint(List<EndpointDescription> endpoints,
            ConnectionSpec spec) {
        Optional<EndpointDescription> matched = endpoints.stream()
                .filter(endpoint -> properties.securityPolicy().equals(endpoint.getSecurityPolicyUri()))
                .filter(endpoint -> endpoint.getSecurityMode() != null
                        && normalizeMode(properties.securityMode())
                                .equals(normalizeMode(endpoint.getSecurityMode().name())))
                .findFirst();
        if (matched.isEmpty()) {
            log.warn("[ypbin-iot] no OPC UA endpoint matched policy {} / mode {} for connection {}; "
                    + "available: {}", properties.securityPolicy(), properties.securityMode(),
                    spec.connectionId(),
                    endpoints.stream().map(EndpointDescription::getSecurityPolicyUri).toList());
        }
        return matched;
    }

    /**
     * 打开链路并记录日志。
     *
     * @param spec        连接规格
     * @param endpointUrl 端点地址
     * @param connected   已连接的客户端
     * @return 链路
     */
    private static ProtocolConnection opened(ConnectionSpec spec, String endpointUrl, OpcUaClient connected) {
        OpcUaConnection connection = new OpcUaConnection(spec, connected);
        // 必须接线断线事件：否则 whenClosed() 永不完成，注册中心无法回收/重连，
        // 而 state() 会一直显示 ONLINE（宿主看到"健康"但数据已断）
        connected.addSessionActivityListener(new SessionActivityListener() {
            @Override
            public void onSessionInactive(UaSession session) {
                connection.onConnectionLost(null);
            }
        });
        log.debug("[ypbin-iot] opcua connection {} opened to {}.", spec.connectionId(), endpointUrl);
        return connection;
    }

    @Override
    public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
            AdapterContext context) {
        if (!(connection instanceof OpcUaConnection opcUaConnection)) {
            return Stages.failed(new ConnectionException(connection.connectionId(),
                    MSG_CONNECTION_INACTIVE, connection.getClass().getName()));
        }
        if (!opcUaConnection.state().isUsable()) {
            // 向已关闭的链路注册会话会得到一个永不工作的会话（宿主看起来"绑定成功"）
            return Stages.failed(new ConnectionException(connection.connectionId(),
                    MSG_CONNECTION_INACTIVE, "connection is not usable"));
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
        }).exceptionally(error -> ProbeResult.unreachable(DESCRIPTOR,
                        Stages.messageKeyOf(error, MSG_CONNECTION_INACTIVE))));
    }
}
