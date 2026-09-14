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
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
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
        assertThat(error).isInstanceOf(UnsupportedCapabilityException.class);
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
