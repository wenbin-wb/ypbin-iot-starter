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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import cn.ypbin.iot.spring.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Modbus 协议层错误指标的契约测试：真实触发一次失败，断言
 * {@code ypbin.iot.errors{type=<错误码>}} 计数 +1。
 *
 * @author wenbin
 * @since 2026-10-14
 */
class ModbusErrorMetricsTest {

    private static final int UNIT_A = 1;

    private ModbusTcpTestServer server;

    private DefaultTaskScheduler scheduler;

    private SimpleMeterRegistry registry;

    private AdapterContext context;

    private ModbusAdapter adapter;

    private ProtocolConnection connection;

    @BeforeEach
    void setUp() throws IOException {
        server = new ModbusTcpTestServer();
        server.start();
        scheduler = new DefaultTaskScheduler(2, 64);
        registry = new SimpleMeterRegistry();
        context = new DefaultAdapterContext(ModbusAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                new MicrometerMetricsRecorder(registry), new EnvCredentialResolver(), Clock.systemUTC(), 32);
        adapter = new ModbusAdapter();
        connection = adapter.open(tcpSpec("metrics"), context).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
        adapter.close();
        scheduler.close();
        registry.close();
        server.close();
    }

    @Test
    @DisplayName("ERR-MB-01 读地址非法：必须记 iot.modbus.address.invalid")
    void invalidReadAddressMustBeCounted() {
        DeviceSession session = bind(UNIT_A);
        List<?> values = session.read(new ReadRequest(List.of(PointAddress.of("garbage")), Duration.ofSeconds(3)))
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join().values();
        assertThat(values).hasSize(1);
        assertThat(errorCount(ModbusAdapter.MSG_ADDRESS_INVALID)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERR-MB-02 只读寄存器写入：必须记 iot.modbus.not-writable")
    void notWritableRegisterMustBeCounted() {
        DeviceSession session = bind(UNIT_A);
        session.write(WriteRequest.of(new PointWrite(PointAddress.of("input:0"), 1)))
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(errorCount(ModbusAdapter.MSG_NOT_WRITABLE)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERR-MB-03 写入值类型非法：必须记 iot.modbus.value.invalid")
    void invalidWriteValueMustBeCounted() {
        DeviceSession session = bind(UNIT_A);
        session.write(WriteRequest.of(new PointWrite(PointAddress.of("holding:0"), Map.of())))
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(errorCount(ModbusAdapter.MSG_VALUE_INVALID)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERR-MB-04 从站地址非法：建会话失败必须记 iot.modbus.unit-id.invalid")
    void invalidUnitIdMustBeCounted() {
        DeviceSpec bad = new DeviceSpec("bad-unit", "坏从站", ModbusAdapter.PROTOCOL_CODE,
                "metrics", "999", Duration.ofMillis(50), Map.of());
        adapter.bind(connection, bad, context).toCompletableFuture()
                .handle((session, error) -> error)
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(errorCount(ModbusAdapter.MSG_UNIT_ID_INVALID)).isEqualTo(1.0);
    }

    private DeviceSession bind(int unitId) {
        DeviceSpec device = new DeviceSpec("modbus-" + unitId, "从站 " + unitId,
                ModbusAdapter.PROTOCOL_CODE, "metrics", String.valueOf(unitId),
                Duration.ofMillis(50), Map.of());
        return adapter.bind(connection, device, context).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
    }

    private ConnectionSpec tcpSpec(String connectionId) {
        return new ConnectionSpec(connectionId, ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()), Duration.ofSeconds(3),
                Duration.ofSeconds(3), null, null, Map.of());
    }

    private double errorCount(String type) {
        var counter = registry.find("ypbin.iot.errors").tag("type", type).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 本测试只关心指标
        }

        @Override
        public void emit(DeviceEvent event) {
            // 本测试只关心指标
        }
    }
}
