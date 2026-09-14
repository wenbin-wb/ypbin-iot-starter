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
package cn.ypbin.iot.protocol.mqtt;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.util.Stages;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import com.hivemq.client.mqtt.mqtt3.Mqtt3ClientBuilder;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT 协议适配器（3.1.1 / 5.0）。
 *
 * <p><b>协议版本基线为 MQTT 3.1.1</b>：工业现场的 broker 与设备绝大多数只支持 3.1.1，
 * 而 5.0 专属能力（会话过期、共享订阅、原因码）在现场几乎用不上。选 3.1.1 是兼容性优先，
 * 不是功能缺失——需要 5.0 时再按需扩展，而不是现在就背上一套现场跑不起来的功能。</p>
 *
 * <p><b>能力口径</b>：{@code WRITE} + {@code SUBSCRIBE_NATIVE} + {@code MULTI_DEVICE_LINK}。
 * <b>刻意不声明 {@code READ}</b>——MQTT 是发布/订阅模型，没有请求-响应语义。
 * 若声称支持读，就必须自造一套「请求主题 + 关联 id + 超时匹配」的伪协议，
 * 那是宿主业务层的编排职责，伪装成协议能力只会误导使用者。</p>
 *
 * <p><b>与 Modbus 同属 1:N 链路</b>，但切分依据是主题前缀而不是 unitId。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class MqttAdapter implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttAdapter.class);

    /** 协议标识。 */
    public static final ProtocolCode PROTOCOL_CODE = ProtocolCode.of("mqtt");

    /** 默认端口（明文）。 */
    public static final int DEFAULT_PORT = 1883;

    /** 默认 TLS 端口。 */
    public static final int DEFAULT_TLS_PORT = 8883;

    /** QoS 上限（协议定义）。 */
    public static final int MAX_QOS = MqttProperties.MAX_QOS;

    /** 设备属性键：QoS。 */
    public static final String ATTRIBUTE_QOS = "qos";

    /** 设备属性键：是否保留消息。 */
    public static final String ATTRIBUTE_RETAINED = "retained";

    /** 生成客户端标识用的序号（同进程多链路必须唯一，否则 broker 会互踢）。 */
    private static final AtomicLong CLIENT_SEQUENCE = new AtomicLong();

    /** 发布主题非法的消息键。 */
    public static final String MSG_TOPIC_INVALID = "iot.mqtt.topic.invalid";

    /** 会话已关闭的消息键。 */
    public static final String MSG_SESSION_CLOSED = "iot.mqtt.session.closed";

    /** 值类型不支持的消息键。 */
    public static final String MSG_VALUE_INVALID = "iot.mqtt.value.invalid";

    /** 发布失败的消息键。 */
    public static final String MSG_PUBLISH_FAILED = "iot.mqtt.publish.failed";

    /** 订阅建立的消息键。 */
    public static final String MSG_SUBSCRIBED = "iot.mqtt.subscribed";

    /** 主题匹配的消息键。 */
    public static final String MSG_MATCHED = "iot.mqtt.topic.matched";

    /** QoS 非法的消息键。 */
    public static final String MSG_QOS_INVALID = "iot.mqtt.qos.invalid";

    /** 链路不可用的消息键。 */
    public static final String MSG_CONNECTION_INACTIVE = "iot.mqtt.connection.inactive";

    /** 承载方式不支持的消息键。 */
    public static final String MSG_TRANSPORT_UNSUPPORTED = "iot.mqtt.transport.unsupported";

    private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
            .code(PROTOCOL_CODE)
            .name("MQTT")
            .vendor("HiveMQ MQTT Client")
            .stackVersion("1.4.0")
            .transport("TCP/WEBSOCKET/TLS")
            .capabilities(ProtocolCapability.WRITE, ProtocolCapability.SUBSCRIBE_NATIVE,
                    ProtocolCapability.MULTI_DEVICE_LINK)
            .runtimeVersionRange("0.1.0", "1.0.0")
            .attribute("defaultPort", String.valueOf(DEFAULT_PORT))
            .build();

    private final MqttProperties properties;

    /**
     * 创建适配器。
     *
     * @param properties MQTT 配置
     */
    public MqttAdapter(MqttProperties properties) {
        this.properties = properties == null ? new MqttProperties(null, null, null, null, null)
                : properties;
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
        if (!"tcp".equals(scheme) && !"mqtt".equals(scheme)) {
            // 只接受明文 TCP 的两种写法。TLS（ssl/mqtts）与 WebSocket（ws/wss）**尚未实现**，
            // 此前把它们放进白名单会让用户以为在加密，实际拿到明文连接 —— 安全静默降级。
            return Stages.failed(new ConnectionException(spec.connectionId(), MSG_TRANSPORT_UNSUPPORTED, scheme));
        }
        if (spec.tls().enabled()) {
            // 与 TCP/Modbus 一致的 fail-fast 口径：尚未实现 TLS 时绝不静默降级为明文
            return Stages.failed(new ConnectionException(spec.connectionId(), IotMessageKeys.CONFIG_INVALID,
                    "TLS not implemented in M1; refusing plaintext connect"));
        }
        int port = endpoint.port() > 0 ? endpoint.port() : DEFAULT_PORT;
        // 用 open() 内的局部 holder 绑定断线回调：适配器是 Spring 单例、可同时服务多条链路，
        // 用实例字段承载「当前链路」会让多条链路串台（A 断线却把 B 标记为 FAILED）
        CompletableFuture<MqttConnection> holder = new CompletableFuture<>();
        Mqtt3AsyncClient client;
        try {
            Mqtt3ClientBuilder builder = Mqtt3Client.builder()
                    .identifier(clientId(spec))
                    .serverHost(endpoint.host())
                    .serverPort(port)
                    .automaticReconnectWithDefaultConfig();
            builder.addDisconnectedListener(disconnectContext -> {
                // 断线必须上抛给框架：否则 whenClosed 永不完成、state 永为 ONLINE，
                // broker 永久不可达时系统会一直显示健康而数据已断流（静默失败）。
                holder.thenAccept(connection -> connection.onConnectionLost(disconnectContext.getCause()));
            });
            client = builder.buildAsync();
        } catch (RuntimeException ex) {
            return Stages.failed(new ConnectionException(spec.connectionId(), ex,
                    IotMessageKeys.CONFIG_INVALID, endpoint.uri()));
        }
        return Stages.normalize(client.connectWith()
                // MQTT 3.1.1 基线：cleanSession=false 时 broker 保留订阅与离线消息
                .cleanSession(properties.isCleanSession())
                .keepAlive((int) Math.max(1L, context.settings().keepAliveInterval().toSeconds()))
                .send()
                // 必须显式施加连接超时：客户端的自动重连会让「连不上」表现为长时间不返回，
                // 而 ConnectionSpec.connectTimeout 是调用方的契约，不能因为库有默认行为就忽略
                .orTimeout(Math.max(1L, spec.connectTimeout().toMillis()), TimeUnit.MILLISECONDS)
                .thenApply(connAck -> {
                    MqttConnection connection = new MqttConnection(spec, client);
                    holder.complete(connection);
                    log.debug("[ypbin-iot] mqtt connection {} opened to {}:{}.", spec.connectionId(),
                            endpoint.host(), port);
                    return (ProtocolConnection) connection;
                })
                .exceptionally(error -> {
                    throw new ConnectionException(spec.connectionId(), error,
                            IotMessageKeys.CONNECTION_FAILED, endpoint.uri());
                }));
    }

    /**
     * 生成客户端标识。
     *
     * <p>必须<b>进程内唯一</b>：broker 看到相同 clientId 的第二个连接时会把第一个踢掉，
     * 表现为「新连接一上来旧连接就断」的诡异循环。这里用配置前缀 + 连接标识 + 序号保证唯一。</p>
     */
    private String clientId(ConnectionSpec spec) {
        return properties.clientIdPrefix() + "-" + spec.connectionId() + "-"
                + CLIENT_SEQUENCE.incrementAndGet();
    }

    @Override
    public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
            AdapterContext context) {
        if (!(connection instanceof MqttConnection mqttConnection)) {
            return Stages.failed(new ConnectionException(connection.connectionId(),
                    MSG_TRANSPORT_UNSUPPORTED, connection.getClass().getName()));
        }
        MqttSession session = new MqttSession(device, mqttConnection, context,
                properties.qosDefault(), properties.isRetainedDefault());
        mqttConnection.register(session);
        return CompletableFuture.completedFuture(session);
    }

    @Override
    public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        // 探测即建链：MQTT 的 CONNACK 已经证明了端点可达与认证通过
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
