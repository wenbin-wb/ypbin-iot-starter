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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import cn.ypbin.iot.test.tck.AbstractProtocolAdapterTckTest;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MQTT 适配器的 TCK 一致性测试与特有行为测试（对嵌入式 broker 端到端）。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class MqttAdapterTckTest extends AbstractProtocolAdapterTckTest {

    private MqttTestBroker broker;

    private MqttAdapter adapter;

    @Override
    protected void beforeTck() {
        try {
            broker = new MqttTestBroker();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start embedded mqtt broker", ex);
        }
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 1, null, null));
    }

    @Override
    protected void afterTck() {
        adapter.close();
        broker.close();
    }

    @Override
    protected ProtocolAdapter adapter() {
        return adapter;
    }

    @Override
    protected ConnectionSpec connectionSpec() {
        return new ConnectionSpec("mqtt-tck", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + broker.port()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), null, null, Map.of());
    }

    @Override
    protected DeviceSpec deviceSpec() {
        return new DeviceSpec("mqtt-device", "MQTT 设备", MqttAdapter.PROTOCOL_CODE, "mqtt-tck",
                "factory/line1", Duration.ZERO, Map.of());
    }

    @Test
    @DisplayName("MQ-01 能力声明：只声明写与原生订阅，绝不声明读")
    void mustNotClaimReadCapability() {
        assertThat(adapter.capabilities()).contains(
                ProtocolCapability.WRITE, ProtocolCapability.SUBSCRIBE_NATIVE,
                ProtocolCapability.MULTI_DEVICE_LINK);
        assertThat(adapter.capabilities())
                .as("MQTT 无请求-响应语义，声明 READ 就是撒谎")
                .doesNotContain(ProtocolCapability.READ);
        assertThat(adapter.capabilities()).doesNotContain(ProtocolCapability.BROWSE);
    }

    @Test
    @DisplayName("MQ-02 读操作必须按未声明能力 fail-fast")
    void readMustFailFast() {
        DeviceSession session = openSession();
        try {
            Throwable error = session.read(ReadRequest.of(PointAddress.of("a/b")))
                    .toCompletableFuture().handle((result, ex) -> ex).join();
            assertThat(error).isInstanceOf(UnsupportedCapabilityException.class);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MQ-03 发布必须真的到达 broker 并可被另一订阅者收到")
    void publishMustReachBroker() {
        Mqtt5Probe probe = new Mqtt5Probe(broker.port());
        DeviceSession session = openSession();
        try {
            // 必须先订阅再发布：MQTT 只在订阅建立后投递（未设 retained 的历史消息不会补发）
            probe.subscribe("factory/line1/temp");
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("factory/line1/temp"), "23.5")))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.allSuccess()).isTrue();
            assertThat(probe.await(Duration.ofSeconds(5)))
                    .as("broker 应把该载荷投递给同时在线订阅者")
                    .isEqualTo("23.5");
        } finally {
            probe.close();
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MQ-04 原生订阅必须收到 broker 推送并经 listener 回调")
    void nativeSubscriptionMustReceivePush() throws IOException {
        DeviceSession session = openSession();
        List<String> received = new CopyOnWriteArrayList<>();
        try {
            SubscriptionHandle handle = session.subscribe(
                            SubscribeRequest.of(List.of(PointAddress.of("factory/line1/#"))),
                            value -> received.add(String.valueOf(value.value())))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(handle.active()).isTrue();

            try (MqttPublisher publisher = new MqttPublisher(broker.port())) {
                publisher.publish("factory/line1/temp", "42");
                awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(5));
            }
            assertThat(received).contains("42");
            assertThat(handle.deliveredCount()).isEqualTo(1);

            session.unsubscribe(handle).toCompletableFuture().join();
            assertThat(handle.active()).isFalse();
            // 取消后不得再收到
            try (MqttPublisher publisher = new MqttPublisher(broker.port())) {
                publisher.publish("factory/line1/temp", "43");
                sleep(300L);
            }
            assertThat(received).as("取消后不得继续投递").containsExactly("42");
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MQ-05 未传 listener 的订阅必须经 egress 出口投递")
    void subscriptionWithoutListenerMustUseEgress() {
        DeviceSession session = openSession();
        try {
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("factory/line2/#"))), null)
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            try (MqttPublisher publisher = new MqttPublisher(broker.port())) {
                publisher.publish("factory/line2/humidity", "61");
            }
            awaitUntil(() -> egress().pointCount() > 0, Duration.ofSeconds(5));
            assertThat(egress().pointCount()).isPositive();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MQ-06 非法主题过滤器必须在订阅时 fail-fast")
    void invalidFilterMustFailFast() {
        DeviceSession session = openSession();
        try {
            Throwable error = session.subscribe(
                            SubscribeRequest.of(List.of(PointAddress.of("a/b+/c"))), null)
                    .toCompletableFuture().handle((handle, ex) -> ex).join();
            // 过滤器非法是配置错误，不是"协议不支持订阅"：用 UnsupportedCapabilityException
            // 会让宿主把配置写错误判成协议能力缺失而统一降级
            assertThat(error).isInstanceOf(AddressParseException.class);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MQ-07 一条链路必须能承载多台设备（按主题前缀切分）")
    void oneLinkMustHostMultipleDevices() {
        ProtocolConnection connection = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            DeviceSession a = adapter.bind(connection, deviceSpec(), context())
                    .toCompletableFuture().join();
            DeviceSession b = adapter.bind(connection, deviceFor("factory/line2"), context())
                    .toCompletableFuture().join();
            assertThat(a.connectionId()).isEqualTo(b.connectionId());
            assertThat(a.sessionId()).isNotEqualTo(b.sessionId());
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("MQ-08 载荷类型不支持必须逐项失败，不影响其余写项")
    void unsupportedPayloadMustFailPerItem() {
        DeviceSession session = openSession();
        try {
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("factory/line1/a"), Map.of()),
                            new PointWrite(PointAddress.of("factory/line1/b"), "ok"),
                            new PointWrite(PointAddress.of("factory/line1/c"), 7)))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.statuses().get(0).success()).isFalse();
            assertThat(result.statuses().get(1).success()).isTrue();
            assertThat(result.statuses().get(2).success()).as("数字应转成文本载荷").isTrue();
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MQ-09 客户端标识必须唯一（否则 broker 会互踢）")
    void clientIdMustBeUnique() {
        ProtocolConnection first = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        ProtocolConnection second = adapter.open(connectionSpec(), context())
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            // 两条链路都能正常发布，说明第一条没有被第二条踢掉
            DeviceSession sessionA = adapter.bind(first, deviceSpec(), context())
                    .toCompletableFuture().join();
            DeviceSession sessionB = adapter.bind(second, deviceFor("factory/line2"), context())
                    .toCompletableFuture().join();
            assertThat(sessionA.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("factory/line1/x"), "1")))
                    .toCompletableFuture().join().allSuccess()).isTrue();
            assertThat(sessionB.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("factory/line2/y"), "2")))
                    .toCompletableFuture().join().allSuccess()).isTrue();
        } finally {
            first.close();
            second.close();
        }
    }

    private DeviceSpec deviceFor(String prefix) {
        return new DeviceSpec("mqtt-" + prefix, "MQTT " + prefix, MqttAdapter.PROTOCOL_CODE,
                "mqtt-tck", prefix, Duration.ZERO, Map.of());
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

    /**
     * 独立的订阅探针：验证「发布真的到了 broker」而不是只在客户端内部打转。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class Mqtt5Probe implements AutoCloseable {

        private final Mqtt3AsyncClient client;

        private Mqtt5Probe(int port) {
            client = Mqtt3Client.builder()
                    .identifier("probe-" + System.nanoTime())
                    .serverHost("127.0.0.1")
                    .serverPort(port)
                    .buildAsync();
            client.connect().join();
        }

        private final List<String> received = new CopyOnWriteArrayList<>();

        private void subscribe(String topic) {
            client.subscribeWith()
                    .topicFilter(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .callback(publish -> received.add(
                            new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8)))
                    .send().join();
        }

        private String await(Duration timeout) {
            awaitUntil(() -> !received.isEmpty(), timeout);
            return received.isEmpty() ? null : received.get(0);
        }

        @Override
        public void close() {
            client.disconnect().join();
        }
    }

    /**
     * 独立的发布客户端：模拟「设备侧主动上报」。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class MqttPublisher implements AutoCloseable {

        private final Mqtt3AsyncClient client;

        private MqttPublisher(int port) {
            client = Mqtt3Client.builder()
                    .identifier("publisher-" + System.nanoTime())
                    .serverHost("127.0.0.1")
                    .serverPort(port)
                    .buildAsync();
            client.connect().join();
        }

        private void publish(String topic, String payload) {
            client.publishWith()
                    .topic(topic)
                    .qos(MqttQos.AT_LEAST_ONCE)
                    .payload(payload.getBytes(StandardCharsets.UTF_8))
                    .send().join();
        }

        @Override
        public void close() {
            client.disconnect().join();
        }
    }
}
