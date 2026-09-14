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

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.test.tck.AbstractProtocolAdapterTckTest;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Modbus 适配器的 TCK 一致性测试 + Modbus 特有行为测试。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class ModbusAdapterTckTest extends AbstractProtocolAdapterTckTest {

    private static final int UNIT_A = 1;

    private static final int UNIT_B = 2;

    private ModbusTcpTestServer server;

    private ModbusAdapter adapter;

    @Override
    protected void beforeTck() {
        try {
            server = new ModbusTcpTestServer();
            server.start();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start modbus test server", ex);
        }
        server.setRegister(UNIT_A, 0, 0x1234);
        server.setRegister(UNIT_A, 1, 0x5678);
        server.setRegister(UNIT_A, 40, 0x00FF);
        server.setRegister(UNIT_B, 0, 0xABCD);
        server.setCoil(UNIT_A, 0, true);
        server.setCoil(UNIT_A, 3, true);
        adapter = new ModbusAdapter();
    }

    @Override
    protected void afterTck() {
        adapter.close();
        server.close();
    }

    @Override
    protected ProtocolAdapter adapter() {
        return adapter;
    }

    @Override
    protected ConnectionSpec connectionSpec() {
        return new ConnectionSpec("modbus-tck", ModbusAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()), Duration.ofSeconds(3),
                Duration.ofSeconds(3), null, null, Map.of());
    }

    @Override
    protected DeviceSpec deviceSpec() {
        return new DeviceSpec("modbus-device-a", "从站 A", ModbusAdapter.PROTOCOL_CODE, "modbus-tck",
                String.valueOf(UNIT_A), Duration.ofMillis(50), Map.of());
    }

    @Test
    @DisplayName("MB-01 能力声明必须包含链路复用（一条链路承载多从站）")
    void mustDeclareMultiDeviceLink() {
        assertThat(adapter.capabilities()).contains(
                ProtocolCapability.READ, ProtocolCapability.WRITE,
                ProtocolCapability.SUBSCRIBE_POLLING, ProtocolCapability.MULTI_DEVICE_LINK);
        // Modbus 无推送能力：不能声明原生订阅
        assertThat(adapter.capabilities()).doesNotContain(ProtocolCapability.SUBSCRIBE_NATIVE);
    }

    @Test
    @DisplayName("MB-02 读保持寄存器必须按传统编号与显式编号两种写法都取到正确值")
    void readMustReturnPresetRegisters() {
        DeviceSession session = openSession();
        try {
            ReadResult result = session.read(ReadRequest.of(List.of(
                            PointAddress.of("holding:0"), PointAddress.of("holding:1"),
                            PointAddress.of("40041"))))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.values()).hasSize(3);
            assertThat(result.values().get(0).value()).isEqualTo(0x1234);
            assertThat(result.values().get(1).value()).isEqualTo(0x5678);
            assertThat(result.values().get(2).value()).isEqualTo(0x00FF);
            assertThat(result.failureCount()).isZero();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MB-03 读线圈必须正确解位（一位一个线圈的位打包）")
    void readCoilsMustUnpackBits() {
        DeviceSession session = openSession();
        try {
            ReadResult result = session.read(ReadRequest.of(List.of(
                            PointAddress.of("coil:0"), PointAddress.of("coil:1"),
                            PointAddress.of("coil:3"))))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.values().get(0).value()).isEqualTo(true);
            assertThat(result.values().get(1).value()).isEqualTo(false);
            assertThat(result.values().get(2).value()).isEqualTo(true);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MB-04 写单寄存器后读回必须一致")
    void writeThenReadMustRoundTrip() {
        DeviceSession session = openSession();
        try {
            WriteResult write = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("holding:10"), 4242)))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(write.allSuccess()).isTrue();
            assertThat(server.register(UNIT_A, 10)).isEqualTo(4242);

            ReadResult read = session.read(ReadRequest.of(PointAddress.of("holding:10")))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(read.values().get(0).value()).isEqualTo(4242);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MB-05 只读寄存器区写入必须逐项失败，不影响其余写项")
    void readOnlyWriteMustFailPerItem() {
        DeviceSession session = openSession();
        try {
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("input:0"), 1),
                            new PointWrite(PointAddress.of("discrete:0"), true),
                            new PointWrite(PointAddress.of("holding:11"), 7)))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.allSuccess()).isFalse();
            assertThat(result.failures()).hasSize(2);
            assertThat(result.statuses().get(2).success()).as("可写项仍应成功").isTrue();
            assertThat(server.register(UNIT_A, 11)).isEqualTo(7);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MB-06 非法地址必须产生逐项失败而不是整批异常")
    void invalidAddressMustFailPerItem() {
        DeviceSession session = openSession();
        try {
            ReadResult result = session.read(ReadRequest.of(List.of(
                            PointAddress.of("garbage"), PointAddress.of("holding:0"))))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.values().get(0).quality()).isEqualTo(Quality.CONFIG_ERROR);
            assertThat(result.values().get(0).qualityReason()).isEqualTo(ModbusAdapter.MSG_ADDRESS_INVALID);
            assertThat(result.values().get(1).isGood()).as("合法地址不应受非法地址影响").isTrue();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MB-07 一条链路必须能承载多个从站（连接复用）")
    void oneLinkMustHostMultipleSlaves() {
        ProtocolConnection connection = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            DeviceSession sessionA = adapter.bind(connection, deviceSpec(), context())
                    .toCompletableFuture().join();
            DeviceSession sessionB = adapter.bind(connection, deviceFor(UNIT_B), context())
                    .toCompletableFuture().join();
            assertThat(sessionA.connectionId()).isEqualTo(sessionB.connectionId());
            assertThat(sessionA.read(ReadRequest.of(PointAddress.of("holding:0")))
                    .toCompletableFuture().join().values().get(0).value()).isEqualTo(0x1234);
            assertThat(sessionB.read(ReadRequest.of(PointAddress.of("holding:0")))
                    .toCompletableFuture().join().values().get(0).value()).isEqualTo(0xABCD);
            closeQuietly(sessionA);
            closeQuietly(sessionB);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("MB-08 轮询订阅必须按其周期推送数据，取消后停止")
    void pollingSubscriptionMustDeliverAndStop() {
        DeviceSession session = openSession();
        try {
            SubscriptionHandle handle = session.subscribe(
                            SubscribeRequest.of(List.of(PointAddress.of("holding:0"))), null)
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(handle.active()).isTrue();
            awaitUntil(() -> handle.deliveredCount() > 0, Duration.ofSeconds(5));
            assertThat(handle.deliveredCount()).as("轮询订阅必须真的推送数据").isPositive();
            assertThat(egress().pointCount()).as("未传 listener 时必须经 egress 出口").isPositive();

            session.unsubscribe(handle).toCompletableFuture().join();
            assertThat(handle.active()).isFalse();
            long deliveredAfterCancel = handle.deliveredCount();
            sleep(200L);
            assertThat(handle.deliveredCount())
                    .as("取消后不得继续推送").isEqualTo(deliveredAfterCancel);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MB-09 从站地址非法必须在绑定时 fail-fast")
    void invalidUnitIdMustFailFastOnBind() {
        ProtocolConnection connection = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            DeviceSpec invalid = new DeviceSpec("bad", "非法从站", ModbusAdapter.PROTOCOL_CODE,
                    "modbus-tck", "999", Duration.ZERO, Map.of());
            Throwable error = adapter.bind(connection, invalid, context())
                    .toCompletableFuture().handle((session, ex) -> ex).join();
            assertThat(error).isNotNull();
        } finally {
            connection.close();
        }
    }

    private DeviceSpec deviceFor(int unitId) {
        return new DeviceSpec("modbus-device-" + unitId, "从站 " + unitId,
                ModbusAdapter.PROTOCOL_CODE, "modbus-tck", String.valueOf(unitId),
                Duration.ofMillis(50), Map.of());
    }

    private static void closeQuietly(DeviceSession session) {
        session.close().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
