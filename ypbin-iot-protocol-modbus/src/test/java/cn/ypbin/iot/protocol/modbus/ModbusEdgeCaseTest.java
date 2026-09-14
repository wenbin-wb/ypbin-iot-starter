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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.exception.ProtocolException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.TlsOptions;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Modbus 边界与异常路径测试。
 *
 * <p>覆盖正常流程之外的契约：链路对象的行为、不支持的承载方式、串口端点、
 * 批量写、多分块读、关闭后的行为、无符号寄存器取值等。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class ModbusEdgeCaseTest {

    private static final int UNIT_A = 1;

    private ModbusTcpTestServer server;

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private ModbusAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        server = new ModbusTcpTestServer();
        server.start();
        scheduler = new DefaultTaskScheduler(2, 64);
        context = new DefaultAdapterContext(ModbusAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 32);
        adapter = new ModbusAdapter();
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        scheduler.close();
        server.close();
    }

    @Test
    @DisplayName("MBE-TYPES 全部寄存器类型与多种地址形态都必须走通读路径（覆盖分支）")
    void allRegisterTypesMustBeReadable() {
        DeviceSession session = openSession();
        // 四种寄存器类型各读一次：FC01/FC02/FC03/FC04 是四条独立分支
        for (ModbusRegisterType type : ModbusRegisterType.values()) {
            PointAddress address = PointAddress.of(type.getCode() + ":0");
            ReadResult result = session.read(new ReadRequest(List.of(address), Duration.ofSeconds(3)))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(result).as("寄存器类型 %s 必须能产出结果", type.getCode()).isNotNull();
            assertThat(result.values()).hasSize(1);
            // 必须断言 quality：只断言「不为空」的话，某类型静默失败也全绿，
            // 用例名里的「都必须走通」就成了空话（复审逐行核对时发现了这一点）
            assertThat(result.values().get(0).quality())
                    .as("寄存器类型 %s 的读取必须成功（quality=GOOD）", type.getCode())
                    .isEqualTo(Quality.GOOD);
        }
        // 地址形态：批量、以及越界长度（后者必须走「长度不足」分支）
        ReadResult bulk = session.read(new ReadRequest(List.of(
                PointAddress.of("holding:0"), PointAddress.of("holding:1"),
                PointAddress.of("coil:0")), Duration.ofSeconds(3)))
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(bulk.values()).hasSize(3);

        // 重复读同一批地址必须稳定产出（不是「越界长度」——原注释与代码不符，已更正）
        ReadResult repeated = session.read(new ReadRequest(List.of(
                PointAddress.of("holding:0"), PointAddress.of("holding:1")), Duration.ofSeconds(3)))
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(repeated.values()).hasSize(2);
    }

    @Test
    @DisplayName("MBE-CONN ModbusConnection 的状态/描述/扩展解包与幂等关闭")
    void connectionAccessorsMustBehave() throws Exception {
        ConnectionSpec spec = tcpSpec("conn-accessors");
        ProtocolConnection connection = adapter.open(spec, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        try {
            assertThat(connection.connectionId()).isEqualTo("conn-accessors");
            assertThat(connection.endpoint()).isNotNull();
            assertThat(connection.state()).isEqualTo(SessionState.ONLINE);
            assertThat(connection.openedAt()).isNotNull();
            // Modbus 是多设备链路：未指定 unitId 时取会话必须**显式报错**，
            // 而不是返回 null 让宿主拿到半个会话（返回 null 会让调用点在很远的地方才 NPE）
            assertThatThrownBy(connection::session)
                    .as("多设备链路上取会话必须显式报错并指明要按 unitId 绑定")
                    .isInstanceOf(UnsupportedCapabilityException.class);
            assertThat(connection.describe())
                    .as("describe 必须含端点与从站数（诊断入口）")
                    .containsEntry("slaveCount", "0");
            assertThat(connection.describe().get("endpoint"))
                    .as("endpoint 必须是实际连接的 URI，而不是空串或占位")
                    .contains("127.0.0.1");
            // 传输层不提供协议扩展能力：任何类型都必须返回空，而不是抛异常
            assertThat(connection.unwrap(ModbusConnection.class)).isEmpty();
            assertThat(connection.unwrap(String.class)).isEmpty();
        } finally {
            connection.close();
            connection.close();
        }
        assertThat(connection.state()).isEqualTo(SessionState.CLOSED);
        CloseReason reason = connection.whenClosed().toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
        // 必须断言原因：宿主的重连策略依赖「主动关闭 vs 意外断开」的区分，
        // 只断言 not-null 等于什么都没验证
        assertThat(reason.cause())
                .as("本端调用 close() 必须以 CLIENT_REQUEST 完成，否则会被误判为意外断开而触发重连")
                .isEqualTo(CloseCause.CLIENT_REQUEST);
    }

    @Test
    @DisplayName("MBE-PROBE-CFG probe 必须带出**配置错误**的真实原因，而不是折叠成「链路不可用」")
    void probeMustSurfaceConfigurationErrors() {
        // 这一条是上一轮「probe 不再折叠失败原因」改动的**正面验证**：
        // 原先配了未实现的 TLS 会被报成「端点不可达」，把排查方向引向网络。
        ConnectionSpec tlsSpec = new ConnectionSpec("probe-tls", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("modbus+tcp://127.0.0.1:502"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                TlsOptions.enabledDefault(), null, Map.of());
        var result = adapter.probe(tlsSpec, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(result.reachable()).isFalse();
        // 钉死**具体**原因：只断言「不等于某个常量」的话，把所有失败换成另一个固定常量
        // （例如 iot.common.config.invalid）照样能通过 —— 那仍是另一种折叠。
        assertThat(result.failureReason())
                .as("TLS 未实现属配置错误，必须带出该原因本身")
                .isEqualTo(IotMessageKeys.CONFIG_INVALID);

        // 对照：不可达端点必须得到**另一个**原因，证明 probe 能区分原因
        ConnectionSpec deadSpec = new ConnectionSpec("probe-dead", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("modbus+tcp://127.0.0.1:1"), Duration.ofMillis(500), Duration.ofMillis(500),
                null, null, Map.of());
        var deadResult = adapter.probe(deadSpec, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(deadResult.reachable()).isFalse();
        assertThat(deadResult.failureReason())
                .as("配置错误与端点不可达必须是不同原因，否则仍是一种折叠")
                .isNotEqualTo(result.failureReason());
    }

    @Test
    @DisplayName("MBE-01 链路对象：session() 不适用、describe 带从站数、unwrap 为空")
    void connectionObjectBehaviour() {
        ProtocolConnection connection = openConnection();
        try {
            assertThat(connection.connectionId()).isEqualTo("edge");
            assertThat(connection.openedAt()).isNotNull();
            assertThat(connection.state()).isEqualTo(SessionState.ONLINE);
            assertThat(connection.describe()).containsKey("endpoint");
            assertThat(connection.unwrap(ProtocolCapability.class)).isEmpty();
            // 1:N 协议：必须经 bind 取设备会话
            assertThatThrownBy(connection::session).isInstanceOf(UnsupportedCapabilityException.class);
        } finally {
            connection.close();
        }
        assertThat(connection.state()).isEqualTo(SessionState.CLOSED);
        connection.close();
    }

    @Test
    @DisplayName("MBE-01b TLS 未实现时必须拒绝，不得静默明文建链")
    void tlsMustFailFastInsteadOfSilentPlaintext() {
        ConnectionSpec tlsSpec = new ConnectionSpec("tls", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()), Duration.ofSeconds(3),
                Duration.ofSeconds(3), TlsOptions.enabledDefault(), null, Map.of());
        Throwable error = adapter.open(tlsSpec, context).toCompletableFuture()
                .handle((connection, ex) -> {
                    if (connection != null) {
                        connection.close();
                    }
                    return ex;
                })
                .orTimeout(5, TimeUnit.SECONDS).join();
        // M0 已在 iot-transport 修过同类缺陷；协议模块自建客户端时极易复发，
        // 因此这条用例是防复发门禁，不能只断言 error != null
        assertThat(error).isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("MBE-02 不支持的承载方式必须 fail-fast")
    void unsupportedTransportMustFailFast() {
        ConnectionSpec spec = new ConnectionSpec("bad", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("http://127.0.0.1:502"), Duration.ofSeconds(1), Duration.ofSeconds(1),
                null, null, Map.of());
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isNotNull();
    }

    @Test
    @DisplayName("MBE-03 串口端点缺少设备路径必须报错，不得静默按默认值连")
    void serialEndpointWithoutPathMustFail() {
        ConnectionSpec spec = new ConnectionSpec("serial", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("serial://?baud=9600"), Duration.ofSeconds(1), Duration.ofSeconds(1),
                null, null, Map.of());
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isNotNull();
    }

    @Test
    @DisplayName("MBE-04 串口设备不存在时必须失败（覆盖 RTU 建链路径）")
    void serialDeviceMissingMustFail() {
        ConnectionSpec spec = new ConnectionSpec("serial-missing", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("serial:///dev/does-not-exist?baud=9600"), Duration.ofSeconds(1),
                Duration.ofSeconds(1), null, null, Map.of());
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isNotNull();
        assertThat(spec.tls().enabled()).isFalse();
    }

    @Test
    @DisplayName("MBE-05 bind 到非 Modbus 链路必须报错")
    void bindWithForeignConnectionMustFail() {
        ProtocolConnection foreign = new ProtocolConnection() {
            @Override
            public String connectionId() {
                return "foreign";
            }

            @Override
            public Endpoint endpoint() {
                return Endpoint.of("tcp://127.0.0.1:1");
            }

            @Override
            public SessionState state() {
                return SessionState.ONLINE;
            }

            @Override
            public Instant openedAt() {
                return Instant.now();
            }

            @Override
            public DeviceSession session() {
                throw new UnsupportedOperationException("n/a");
            }

            @Override
            public CompletionStage<CloseReason> whenClosed() {
                return new CompletableFuture<>();
            }

            @Override
            public Map<String, String> describe() {
                return Map.of();
            }

            @Override
            public <T> Optional<T> unwrap(Class<T> extensionType) {
                return Optional.empty();
            }

            @Override
            public void close() {
                // 无资源
            }
        };
        Throwable error = adapter.bind(foreign, device(UNIT_A), context).toCompletableFuture()
                .handle((session, ex) -> ex).join();
        assertThat(error).isNotNull();
    }

    @Test
    @DisplayName("MBE-06 批量写必须按顺序逐项返回状态，非法类型逐项失败")
    void batchWriteMustReturnPerItemStatus() {
        DeviceSession session = openSession();
        try {
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("holding:20"), 11),
                            new PointWrite(PointAddress.of("holding:21"), "22"),
                            new PointWrite(PointAddress.of("holding:22"), true),
                            new PointWrite(PointAddress.of("holding:23"), Map.of()),
                            new PointWrite(PointAddress.of("coil:7"), true),
                            new PointWrite(PointAddress.of("garbage"), 1)))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.statuses()).hasSize(6);
            assertThat(result.statuses().get(0).success()).isTrue();
            assertThat(result.statuses().get(1).success()).as("数字字符串应可转换").isTrue();
            assertThat(result.statuses().get(2).success()).as("布尔写寄存器应转 0/1").isTrue();
            assertThat(result.statuses().get(3).success()).as("Map 类型应逐项失败").isFalse();
            assertThat(result.statuses().get(5).success()).as("非法地址应逐项失败").isFalse();
            assertThat(server.register(UNIT_A, 20)).isEqualTo(11);
            assertThat(server.register(UNIT_A, 21)).isEqualTo(22);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MBE-07 批量写中的布尔值必须写进线圈")
    void booleanWriteMustTargetCoil() {
        DeviceSession session = openSession();
        try {
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("coil:9"), true)))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.allSuccess()).as("FC05 写单线圈应成功").isTrue();
            ReadResult read = session.read(ReadRequest.of(PointAddress.of("coil:9")))
                    .toCompletableFuture().join();
            assertThat(read.values().get(0).value()).isEqualTo(true);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MBE-08 跨 125 个寄存器上限的读必须自动分成多块并合并")
    void readMustChunkBeyondProtocolLimit() {
        for (int offset = 0; offset < 200; offset += 10) {
            server.setRegister(UNIT_A, offset, offset);
        }
        DeviceSession session = openSession();
        try {
            List<PointAddress> addresses = new ArrayList<>();
            for (int offset = 0; offset < 200; offset += 10) {
                addresses.add(PointAddress.of("holding:" + offset));
            }
            ReadResult result = session.read(ReadRequest.of(addresses))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.values()).hasSize(addresses.size());
            assertThat(result.failureCount()).isZero();
            assertThat(result.values().get(0).value()).isEqualTo(0);
            assertThat(result.values().get(19).value()).isEqualTo(190);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MBE-09 无符号 16 位寄存器必须按无符号取值（>0x7FFF 不得变负）")
    void registerMustBeUnsigned() {
        server.setRegister(UNIT_A, 30, 0xFFFF);
        DeviceSession session = openSession();
        try {
            ReadResult result = session.read(ReadRequest.of(PointAddress.of("holding:30")))
                    .toCompletableFuture().join();
            assertThat(result.values().get(0).value()).isEqualTo(65535);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MBE-10 ping 在链路可用时必须存活；关闭后必须失活且带原因")
    void pingMustReflectConnectionState() {
        ProtocolConnection connection = openConnection();
        DeviceSession session = adapter.bind(connection, device(UNIT_A), context)
                .toCompletableFuture().join();
        PingResult alive = session.ping().toCompletableFuture().join();
        assertThat(alive.alive()).isTrue();
        assertThat(alive.failureReason()).isNull();

        closeQuietly(session);
        connection.close();
        PingResult dead = session.ping().toCompletableFuture().join();
        assertThat(dead.alive()).isFalse();
        assertThat(dead.failureReason()).isEqualTo(ModbusAdapter.MSG_CONNECTION_INACTIVE);
    }

    @Test
    @DisplayName("MBE-11 关闭后的会话不得再接受订阅，且关闭幂等")
    void closedSessionMustRejectSubscribe() {
        DeviceSession session = openSession();
        closeQuietly(session);
        closeQuietly(session);
        Throwable error = session.subscribe(
                        SubscribeRequest.of(List.of(PointAddress.of("holding:0"))), null)
                .toCompletableFuture().handle((handle, ex) -> ex).join();
        assertThat(error).isInstanceOf(ProtocolException.class);
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(session.unwrap(ProtocolCapability.class)).isEmpty();
        assertThat(session.device().deviceId()).isNotBlank();
    }

    @Test
    @DisplayName("MBE-12 从站地址缺省时用默认值；描述符必须自洽")
    void defaultUnitIdAndDescriptorMustBeConsistent() {
        ProtocolConnection connection = openConnection();
        try {
            DeviceSpec noUnit = new DeviceSpec("no-unit", "缺省从站", ModbusAdapter.PROTOCOL_CODE,
                    "edge", "", Duration.ZERO, Map.of());
            DeviceSession session = adapter.bind(connection, noUnit, context)
                    .toCompletableFuture().join();
            assertThat(session.sessionId()).contains(String.valueOf(ModbusAdapter.DEFAULT_UNIT_ID));
            closeQuietly(session);
        } finally {
            connection.close();
        }
        assertThat(adapter.descriptor().code()).isEqualTo(ModbusAdapter.PROTOCOL_CODE);
        assertThat(adapter.descriptor().name()).isEqualTo("Modbus");
        assertThat(adapter.descriptor().stackVersion()).isEqualTo("2.1.6");
        assertThat(adapter.capabilities()).isEqualTo(adapter.descriptor().capabilities());
        assertThat(adapter.descriptor().extensions()).isEmpty();
    }

    @Test
    @DisplayName("MBE-13 探测必须返回可达结果并关闭临时链路")
    void probeMustReportReachable() {
        var result = adapter.probe(tcpSpec("probe"), context).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(result.reachable()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.protocol().code()).isEqualTo(ModbusAdapter.PROTOCOL_CODE);
    }

    @Test
    @DisplayName("MBE-14 探测不可达端点时不得异常完成，而要返回 unreachable")
    void probeUnreachableMustNotThrow() {
        ConnectionSpec dead = new ConnectionSpec("dead", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:1"), Duration.ofMillis(300), Duration.ofMillis(300),
                null, null, Map.of());
        var result = adapter.probe(dead, context).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(result.reachable()).isFalse();
        assertThat(result.failureReason()).isNotBlank();
    }

    @Test
    @DisplayName("MBE-15 读空点位列表必须被参数校验拒绝")
    void emptyReadRequestMustBeRejected() {
        assertThatThrownBy(() -> ReadRequest.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WriteRequest.of()).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MBE-16 保活探针收到异常响应不得判死（异常响应也是「对端活着」）")
    void keepAliveMustNotKillHealthyDeviceOnExceptionResponse() throws Exception {
        // 真实 PLC 对未映射地址回异常码 02 是常态。若把异常响应当成链路死亡，
        // 健康设备会被下线：会话关闭、订阅取消、采集永久停止——比不探测更糟。
        server.denyRegister(UNIT_A, 0);
        DefaultTaskScheduler shortScheduler = new DefaultTaskScheduler(2, 64);
        AdapterContext keepAliveContext = new DefaultAdapterContext(ModbusAdapter.PROTOCOL_CODE,
                new DefaultAdapterSettings(true, Duration.ofMillis(200), Duration.ofSeconds(2),
                        Duration.ofSeconds(2), Duration.ofSeconds(2), Duration.ofMillis(100), 0.0D, 64, 64,
                        Map.of()),
                new NoopEgress(), shortScheduler, NoopMetricsRecorder.INSTANCE,
                new EnvCredentialResolver(), Clock.systemUTC(), 32);
        ProtocolConnection connection = adapter.open(tcpSpec("keepalive"), keepAliveContext)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            DeviceSession session = adapter.bind(connection, device(UNIT_A), keepAliveContext)
                    .toCompletableFuture().join();
            server.setRegister(UNIT_A, 100, 42);
            // 等保活探针至少跑一轮（探针读 holding:0，该地址被标记为未映射）
            Thread.sleep(600L);
            assertThat(connection.state().isUsable())
                    .as("对端回异常响应证明它活着，不得据此判死链路")
                    .isTrue();
            assertThat(session.ping().toCompletableFuture().join().alive()).isTrue();
            ReadResult after = session.read(ReadRequest.of(PointAddress.of("holding:100")))
                    .toCompletableFuture().join();
            assertThat(after.values().get(0).value())
                    .as("判死会导致会话关闭、采集停止；这里必须仍能读到数据")
                    .isEqualTo(42);
        } finally {
            connection.close();
            shortScheduler.close();
        }
    }

    private ProtocolConnection openConnection() {
        return adapter.open(tcpSpec("edge"), context).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
    }

    private DeviceSession openSession() {
        ProtocolConnection connection = openConnection();
        return adapter.bind(connection, device(UNIT_A), context).toCompletableFuture().join();
    }

    private ConnectionSpec tcpSpec(String connectionId) {
        return new ConnectionSpec(connectionId, ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()), Duration.ofSeconds(3),
                Duration.ofSeconds(3), null, null, Map.of());
    }

    private static DeviceSpec device(int unitId) {
        return new DeviceSpec("modbus-" + unitId, "从站 " + unitId, ModbusAdapter.PROTOCOL_CODE,
                "edge", String.valueOf(unitId), Duration.ofMillis(50), Map.of());
    }

    private static void closeQuietly(DeviceSession session) {
        session.close().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
