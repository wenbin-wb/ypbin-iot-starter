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
package cn.ypbin.iot.protocol.modbus;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.exception.ProtocolException;
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
import com.digitalpetri.modbus.client.ModbusClient;
import com.digitalpetri.modbus.client.ModbusClientConfig;
import com.digitalpetri.modbus.client.ModbusRtuClient;
import com.digitalpetri.modbus.client.ModbusTcpClient;
import com.digitalpetri.modbus.serial.SerialPortTransportConfig;
import com.digitalpetri.modbus.serial.client.SerialPortClientTransport;
import com.digitalpetri.modbus.tcp.client.NettyClientTransportConfig;
import com.digitalpetri.modbus.tcp.client.NettyTcpClientTransport;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Modbus 协议适配器（TCP / RTU）。
 *
 * <p><b>一个模块一个协议 code</b>：code 为 {@code modbus}，承载方式由端点 URI 的 scheme 决定
 * ——{@code tcp://host:502} 走 TCP，{@code serial:///dev/ttyS0?baud=9600} 走 RTU。
 * 这样「协议 code ↔ 包名 ↔ 配置键 ↔ 模块名」仍然一一对应（AGENTS I11），
 * 同时避免把 TCP 与 RTU 拆成两个几乎相同的模块。</p>
 *
 * <p><b>能力</b>：{@code READ} + {@code WRITE} + {@code SUBSCRIBE_POLLING} +
 * {@code MULTI_DEVICE_LINK}。最后一项很关键：它声明「一条链路承载多个从站」，
 * 框架据此不会为每个从站重复建链。</p>
 *
 * <p><b>为什么直接用协议库的 Netty 栈而不是本仓 {@code iot-transport}</b>：
 * Modbus 需要 MBAP 帧定界与事务号匹配，协议库已完整实现且经过验证；
 * 本仓传输底座是给「裸字节通道」类协议（TCP/UDP/WebSocket 透传）准备的。
 * 强行拼接只会引入两套 Netty 管线的协调成本。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class ModbusAdapter implements ProtocolAdapter {

    /** 配置注入的默认从站地址（设备未声明 localAddress 时使用）。 */
    private final int defaultUnitId;

    private static final Logger log = LoggerFactory.getLogger(ModbusAdapter.class);

    /** 协议标识。 */
    public static final ProtocolCode PROTOCOL_CODE = ProtocolCode.of("modbus");

    /** 默认从站地址。 */
    public static final int DEFAULT_UNIT_ID = 1;

    /** 从站地址上限（协议为 8 位）。 */
    public static final int MAX_UNIT_ID = 247;

    /** 默认串口波特率。 */
    public static final int DEFAULT_BAUD_RATE = 9600;

    /** 从站地址非法的消息键。 */
    public static final String MSG_UNIT_ID_INVALID = "iot.modbus.unit-id.invalid";

    /** 地址非法的消息键。 */
    public static final String MSG_ADDRESS_INVALID = "iot.modbus.address.invalid";

    /** 寄存器只读的消息键。 */
    public static final String MSG_NOT_WRITABLE = "iot.modbus.not-writable";

    /** 写入值类型不支持的消息键。 */
    public static final String MSG_VALUE_INVALID = "iot.modbus.value.invalid";

    /** 写失败的消息键。 */
    public static final String MSG_WRITE_FAILED = "iot.modbus.write.failed";

    /** 分块读失败的消息键。 */
    public static final String MSG_CHUNK_FAILED = "iot.modbus.read.chunk-failed";

    /** 读超时的消息键。 */
    public static final String MSG_TIMEOUT = "iot.modbus.read.timeout";

    /** 响应长度不足的消息键。 */
    public static final String MSG_RESPONSE_TOO_SHORT = "iot.modbus.response.too-short";

    /** 无读取结果的消息键。 */
    public static final String MSG_NO_RESULT = "iot.modbus.read.no-result";

    /** 会话已关闭的消息键。 */
    public static final String MSG_SESSION_CLOSED = "iot.modbus.session.closed";

    /** 链路不可用的消息键。 */
    public static final String MSG_CONNECTION_INACTIVE = "iot.modbus.connection.inactive";

    /** 传输方式不支持的消息键。 */
    public static final String MSG_TRANSPORT_UNSUPPORTED = "iot.modbus.transport.unsupported";

    private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
            .code(PROTOCOL_CODE)
            .name("Modbus")
            .vendor("digitalpetri modbus")
            .stackVersion("2.1.6")
            .transport("TCP/SERIAL")
            .capabilities(ProtocolCapability.READ, ProtocolCapability.WRITE,
                    ProtocolCapability.SUBSCRIBE_POLLING, ProtocolCapability.MULTI_DEVICE_LINK)
            .runtimeVersionRange("0.1.0", "1.0.0")
            .attribute("defaultPort", "502")
            .build();

    /**
     * 以默认从站地址创建适配器。
     */
    public ModbusAdapter() {
        this(DEFAULT_UNIT_ID);
    }

    /**
     * 创建适配器。
     *
     * @param defaultUnitId 设备未声明 from站地址时使用的默认 unitId
     */
    public ModbusAdapter(int defaultUnitId) {
        this.defaultUnitId = defaultUnitId >= 0 && defaultUnitId <= MAX_UNIT_ID
                ? defaultUnitId : DEFAULT_UNIT_ID;
    }

    /**
     * 默认从站地址。
     *
     * @return 默认 unitId
     */
    public int defaultUnitId() {
        return defaultUnitId;
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
        if (spec.tls().enabled()) {
            // M0 已在 iot-transport 修过同类缺陷；协议模块自建客户端时极易复发，因此这里独立再拦一次
            return Stages.failed(new ConnectionException(spec.connectionId(), IotMessageKeys.CONFIG_INVALID,
                    "TLS not implemented; refusing plaintext connect"));
        }
        String scheme = spec.endpoint().scheme();
        ModbusClient client;
        try {
            client = switch (scheme) {
                case "tcp", "modbus+tcp" -> createTcpClient(spec);
                case "serial", "modbus+serial", "rtu" -> createRtuClient(spec);
                default -> null;
            };
        } catch (RuntimeException ex) {
            // 建链参数非法（如串口端点缺设备路径）也必须以失败 Stage 交付，
            // 不能同步抛出——open 的契约是「返回 Stage」，同步抛会绕过调用方的异常处理路径
            return Stages.failed(ex);
        }
        if (client == null) {
            return Stages.failed(new ConnectionException(spec.connectionId(), MSG_TRANSPORT_UNSUPPORTED, scheme));
        }
        return Stages.normalize(client.connectAsync().thenApply(ignored -> {
            ModbusConnection connection = new ModbusConnection(spec, client, context);
            log.debug("[ypbin-iot] modbus connection {} opened over {}.", spec.connectionId(), scheme);
            return (ProtocolConnection) connection;
        }).exceptionally(error -> {
            throw new ConnectionException(spec.connectionId(), error, IotMessageKeys.CONNECTION_FAILED,
                    spec.endpoint().uri());
        }));
    }

    private static ModbusClient createTcpClient(ConnectionSpec spec) {
        Endpoint endpoint = spec.endpoint();
        NettyClientTransportConfig config = NettyClientTransportConfig.create(builder -> {
            builder.hostname = endpoint.host();
            builder.port = endpoint.port();
            builder.connectTimeout = spec.connectTimeout();
        });
        return ModbusTcpClient.create(new NettyTcpClientTransport(config), requestTimeout(spec));
    }

    private static ModbusClient createRtuClient(ConnectionSpec spec) {
        Endpoint endpoint = spec.endpoint();
        Map<String, String> parameters = endpoint.parameters();
        String serialPort = endpoint.path().orElseThrow(() -> new ProtocolException(
                MSG_TRANSPORT_UNSUPPORTED, "serial endpoint requires a device path, e.g. serial:///dev/ttyS0"));
        SerialPortTransportConfig serialConfig = SerialPortTransportConfig.create(builder -> {
            builder.serialPort = serialPort;
            builder.baudRate = intParameter(parameters, "baud", DEFAULT_BAUD_RATE);
        });
        // RTU 走 modbus-serial 的串口传输（依赖 jSerialComm，LGPL-3.0，已登记许可证白名单）
        return ModbusRtuClient.create(new SerialPortClientTransport(serialConfig), requestTimeout(spec));
    }

    private static Consumer<ModbusClientConfig.Builder> requestTimeout(ConnectionSpec spec) {
        // 请求超时来自连接规格：远程调用必须显式超时，禁止使用库默认值（AGENTS R15）
        return builder -> builder.setRequestTimeout(spec.requestTimeout());
    }

    private static int intParameter(Map<String, String> parameters, String key, int defaultValue) {
        String raw = parameters.get(key);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            throw new ProtocolException(ex, IotMessageKeys.CONFIG_INVALID, key, raw);
        }
    }

    /**
     * 绑定从站会话。
     *
     * <p>Modbus 是 1:N 协议：本方法在<b>已建好的共享链路</b>上为每个 unitId 建立逻辑会话，
     * 不会重新建链（这正是 {@code MULTI_DEVICE_LINK} 声明的语义）。</p>
     */
    @Override
    public CompletionStage<DeviceSession> bind(ProtocolConnection connection, DeviceSpec device,
            AdapterContext context) {
        if (!(connection instanceof ModbusConnection modbusConnection)) {
            return Stages.failed(new ProtocolException(MSG_TRANSPORT_UNSUPPORTED,
                    "expected ModbusConnection but got " + connection.getClass().getName()));
        }
        try {
            ModbusSession session = new ModbusSession(device, modbusConnection, context,
                    modbusConnection.polling(), defaultUnitId);
            modbusConnection.register(session);
            return CompletableFuture.completedFuture(session);
        } catch (RuntimeException ex) {
            return Stages.failed(ex);
        }
    }

    @Override
    public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        // 复用 open：建链成功即证明端点可达且协议栈握手正常
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
