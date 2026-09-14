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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.BrowseNode;
import cn.ypbin.iot.core.model.BrowseRequest;
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
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.test.tck.AbstractProtocolAdapterTckTest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OPC UA 适配器 TCK 一致性测试与端到端行为测试（对自建 Milo 服务端）。
 *
 * @author wenbin
 * @since 2026-09-14
 */
class OpcUaAdapterTckTest extends AbstractProtocolAdapterTckTest {

    private OpcUaTestServer server;

    private OpcUaAdapter adapter;

    @Override
    protected void beforeTck() {
        try {
            server = new OpcUaTestServer();
        } catch (Exception ex) {
            throw new IllegalStateException("failed to start opcua test server", ex);
        }
        adapter = new OpcUaAdapter(null);
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
        return new ConnectionSpec("opcua-tck", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of(server.endpointUrl()), Duration.ofSeconds(5), Duration.ofSeconds(5),
                null, null, Map.of());
    }

    @Override
    protected DeviceSpec deviceSpec() {
        return new DeviceSpec("opcua-device", "OPC UA 设备", OpcUaAdapter.PROTOCOL_CODE, "opcua-tck",
                "", Duration.ZERO, Map.of());
    }

    @Override
    protected PointAddress subscriptionAddress() {
        return PointAddress.of(server.nodeAddress("Temperature"));
    }

    @Test
    @DisplayName("OPC-01 能力声明必须包含读/写/原生订阅/浏览，且扩展已登记")
    void capabilitiesMustBeComplete() {
        assertThat(adapter.capabilities()).contains(
                ProtocolCapability.READ, ProtocolCapability.WRITE,
                ProtocolCapability.SUBSCRIBE_NATIVE, ProtocolCapability.BROWSE,
                ProtocolCapability.MULTI_DEVICE_LINK);
        assertThat(adapter.capabilities())
                .as("OPC UA 有原生推送，不应声明轮询订阅")
                .doesNotContain(ProtocolCapability.SUBSCRIBE_POLLING);
        assertThat(adapter.descriptor().supportsExtension(BrowseExtension.class)).isTrue();
    }

    @Test
    @DisplayName("OPC-02 批量读必须一次请求取回多个节点且顺序与请求一致")
    void batchReadMustPreserveOrder() {
        // 不依赖初始值：同类中其他用例会改服务端状态，显式设定才能避免用例间耦合
        server.updateValue("Temperature", 23.5D);
        server.updateValue("Pressure", 1013L);
        server.updateValue("SerialNumber", "SN-0001");
        DeviceSession session = openSession();
        try {
            ReadResult result = session.read(ReadRequest.of(List.of(
                            PointAddress.of(server.nodeAddress("Pressure")),
                            PointAddress.of(server.nodeAddress("Temperature")),
                            PointAddress.of(server.nodeAddress("SerialNumber")))))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(result.values()).hasSize(3);
            assertThat(result.failureCount()).isZero();
            // 顺序必须与请求一致（不是服务端返回顺序）
            assertThat(result.values().get(0).value()).isEqualTo(1013L);
            assertThat(result.values().get(1).value()).isEqualTo(23.5D);
            assertThat(result.values().get(2).value()).isEqualTo("SN-0001");
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-03 非法地址必须逐点位失败，不影响其余点位")
    void invalidAddressMustFailPerItem() {
        DeviceSession session = openSession();
        try {
            ReadResult result = session.read(ReadRequest.of(List.of(
                            PointAddress.of("Temperature"),
                            PointAddress.of(server.nodeAddress("Temperature")))))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(result.values().get(0).quality())
                    .as("裸标识符缺命名空间，属配置错误，必须逐点位标记而不是整批异常")
                    .isEqualTo(Quality.CONFIG_ERROR);
            assertThat(result.values().get(0).qualityReason())
                    .isEqualTo(OpcUaAdapter.MSG_ADDRESS_INVALID);
            assertThat(result.values().get(1).isGood()).as("合法地址不受影响").isTrue();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-04 写后读回必须一致")
    void writeThenReadMustRoundTrip() {
        DeviceSession session = openSession();
        try {
            WriteResult write = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of(server.nodeAddress("Temperature")), 31.25D)))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(write.allSuccess()).isTrue();

            ReadResult read = session.read(ReadRequest.of(
                            PointAddress.of(server.nodeAddress("Temperature"))))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(read.values().get(0).value()).isEqualTo(31.25D);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-05 写只读节点必须逐项失败，其余写项照常")
    void writeToReadOnlyNodeMustFailPerItem() {
        DeviceSession session = openSession();
        try {
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of(server.nodeAddress("SerialNumber")), "HACKED"),
                            new PointWrite(PointAddress.of(server.nodeAddress("Pressure")), 900L)))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(result.statuses()).hasSize(2);
            assertThat(result.statuses().get(0).success())
                    .as("只读节点的写必须被服务端拒绝并逐项标记")
                    .isFalse();
            assertThat(result.statuses().get(1).success()).as("可写节点仍须成功").isTrue();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-06 原生订阅必须能被服务端接受，且取消幂等")
    void nativeSubscriptionMustBeAcceptedAndCancelled() {
        DeviceSession session = openSession();
        try {
            SubscriptionHandle handle = session.subscribe(
                            new SubscribeRequest(
                                    List.of(PointAddress.of(server.nodeAddress("Temperature"))),
                                    Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()),
                            value -> { })
                    .toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join();
            assertThat(handle.active()).isTrue();
            assertThat(handle.subscriptionId()).isNotBlank();
            assertThat(handle.addresses()).hasSize(1);

            session.unsubscribe(handle).toCompletableFuture()
                    .orTimeout(15, TimeUnit.SECONDS).join();
            assertThat(handle.active()).isFalse();
            // 重复取消必须幂等
            session.unsubscribe(handle).toCompletableFuture()
                    .orTimeout(15, TimeUnit.SECONDS).join();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-06b 服务端改值必须推送到订阅者（harness 尚不具备该能力，显式跳过）")
    void nativeSubscriptionMustReceivePush() {
        // 【显式缺口，不是静默跳过】订阅创建与取消已验证（OPC-06），但"服务端改值 → 推送"
        // 这条链路需要 harness 通过 Milo 的 AttributeService 驱动值变更，当前 harness 用
        // UaVariableNode.setValue 不足以触发 DataItem 上报。在产品代码确认收到推送之前，
        // 这个用例必须保持"未验证"状态，而不是被删掉或改成永远通过。
        Assumptions.abort("Milo 测试服务端的值变更驱动尚未打通（需经 AttributeService），"
                + "原生订阅的推送路径待下轮验证");
        DeviceSession session = openSession();
        List<Object> received = new CopyOnWriteArrayList<>();
        try {
            session.subscribe(
                    new SubscribeRequest(List.of(PointAddress.of(server.nodeAddress("Temperature"))),
                            Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()),
                    value -> received.add(value.value()))
                    .toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join();
            server.updateValue("Temperature", 42.5D);
            awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(10));
            assertThat(received).contains(42.5D);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-07 浏览扩展必须返回地址空间中的节点（BrowseExtension 的首个真实实现）")
    void browseExtensionMustListNodes() {
        DeviceSession session = openSession();
        try {
            BrowseExtension browser = session.unwrap(BrowseExtension.class)
                    .orElseThrow(() -> new IllegalStateException("BrowseExtension 未提供"));
            assertThat(browser.protocol()).isEqualTo(OpcUaAdapter.PROTOCOL_CODE);
            List<BrowseNode> nodes = browser.browse(new BrowseRequest("i=85", 1, 100))
                    .toCompletableFuture().orTimeout(20, TimeUnit.SECONDS).join();
            assertThat(nodes).as("Objects 文件夹下应能浏览到自定义变量").isNotEmpty();
            assertThat(nodes.stream().map(BrowseNode::displayName).toList())
                    .contains("Temperature", "Pressure", "SerialNumber");
            assertThat(nodes).allSatisfy(node -> assertThat(OpcUaNodeIdCodec
                    .parse(node.address().raw()))
                    .as("浏览返回的地址必须能被解析回 NodeId（否则宿主拿到也存不进点位表）")
                    .isNotNull());
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-08 浏览上限必须被遵守（防止在大型地址空间上失控）")
    void browseMustRespectNodeLimit() {
        DeviceSession session = openSession();
        try {
            BrowseExtension browser = session.unwrap(BrowseExtension.class).orElseThrow();
            List<BrowseNode> nodes = browser.browse(new BrowseRequest("i=85", 3, 2))
                    .toCompletableFuture().orTimeout(20, TimeUnit.SECONDS).join();
            assertThat(nodes.size()).as("超过上限必须截断而不是继续拉取").isLessThanOrEqualTo(2);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-09 一条链路必须能承载多台设备")
    void oneLinkMustHostMultipleDevices() {
        ProtocolConnection connection = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        try {
            DeviceSession first = adapter.bind(connection, deviceSpec(), context())
                    .toCompletableFuture().join();
            DeviceSession second = adapter.bind(connection, new DeviceSpec("opcua-device-2", "设备 2",
                            OpcUaAdapter.PROTOCOL_CODE, "opcua-tck", "", Duration.ZERO, Map.of()), context())
                    .toCompletableFuture().join();
            assertThat(first.connectionId()).isEqualTo(second.connectionId());
            assertThat(first.sessionId()).isNotEqualTo(second.sessionId());
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("OPC-10 ping 必须发真实协议请求并报告往返耗时")
    void pingMustPerformRealRequest() {
        DeviceSession session = openSession();
        try {
            var result = session.ping().toCompletableFuture()
                    .orTimeout(10, TimeUnit.SECONDS).join();
            assertThat(result.alive()).isTrue();
            assertThat(result.roundTripMillis()).isNotNegative();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-11 多余的 unsubscribe 必须幂等")
    void unsubscribeMustBeIdempotent() {
        DeviceSession session = openSession();
        try {
            assertThat(session.unsubscribe(null).toCompletableFuture().join()).isNull();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("OPC-12 链路对象：session() 不适用、describe 带设备数、状态随关闭翻转")
    void connectionObjectBehaviour() {
        ProtocolConnection connection = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        try {
            assertThat(connection.connectionId()).isEqualTo("opcua-tck");
            assertThat(connection.openedAt()).isNotNull();
            assertThat(connection.state().isUsable()).isTrue();
            assertThat(connection.describe()).containsKeys("endpoint", "deviceCount");
            assertThat(connection.endpoint().uri()).startsWith("opc.tcp://");
            assertThatThrownBy(connection::session)
                    .as("OPC UA 一条连接承载多台设备子树，session() 入口不适用")
                    .isInstanceOf(UnsupportedCapabilityException.class);
            adapter.bind(connection, deviceSpec(), context()).toCompletableFuture().join();
            assertThat(connection.describe()).containsEntry("deviceCount", "1");
        } finally {
            connection.close();
        }
        assertThat(connection.state()).isEqualTo(cn.ypbin.iot.core.model.SessionState.CLOSED);
        // 关闭幂等
        connection.close();
    }

    @Test
    @DisplayName("OPC-13 未声明的扩展类型必须返回空 Optional")
    void undeclaredExtensionMustBeEmpty() {
        DeviceSession session = openSession();
        try {
            assertThat(session.unwrap(java.util.function.Supplier.class)).isEmpty();
            assertThat(session.unwrap(BrowseExtension.class)).isPresent();
        } finally {
            closeQuietly(session);
        }
    }

    private static void closeQuietly(DeviceSession session) {
        session.close().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50L);
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
