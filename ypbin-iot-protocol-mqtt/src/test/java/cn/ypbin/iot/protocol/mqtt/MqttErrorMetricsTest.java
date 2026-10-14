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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import cn.ypbin.iot.spring.metrics.MicrometerMetricsRecorder;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MQTT 协议层错误指标的契约测试：真实触发一次失败，断言
 * {@code ypbin.iot.errors{type=<错误码>}} 计数 +1。
 *
 * @author wenbin
 * @since 2026-10-14
 */
class MqttErrorMetricsTest {

    private static final String TOPIC = "factory/line1/value";

    private MqttTestBroker broker;

    private DefaultTaskScheduler scheduler;

    private SimpleMeterRegistry registry;

    private AdapterContext context;

    private Mqtt3AsyncClient publisher;

    private MqttAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        broker = new MqttTestBroker();
        scheduler = new DefaultTaskScheduler(2, 64);
        registry = new SimpleMeterRegistry();
        context = new DefaultAdapterContext(MqttAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                new MicrometerMetricsRecorder(registry), new EnvCredentialResolver(), Clock.systemUTC(), 32);
        publisher = MqttClient.builder()
                .identifier("error-metrics-publisher-" + System.nanoTime())
                .serverHost("127.0.0.1")
                .serverPort(broker.port())
                .useMqttVersion3()
                .buildAsync();
        publisher.connect().join();
    }

    @AfterEach
    void tearDown() {
        if (publisher != null) {
            publisher.disconnect().join();
        }
        if (adapter != null) {
            adapter.close();
        }
        scheduler.close();
        registry.close();
        broker.close();
    }

    @Test
    @DisplayName("ERR-MQTT-01 非 UTF-8 负载：BAD 之外必须记 iot.mqtt.payload.not-utf8")
    void invalidUtf8PayloadMustBeCounted() {
        DeviceSession session = openSession(MqttPayloadFormat.TEXT);
        List<PointValue> received = new CopyOnWriteArrayList<>();
        session.subscribe(new SubscribeRequest(List.of(PointAddress.of(TOPIC)), Duration.ofMillis(100),
                        Duration.ofMillis(100), null, Map.of()), received::add)
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        publisher.publishWith().topic(TOPIC).qos(MqttQos.AT_LEAST_ONCE)
                .payload(new byte[] {(byte) 0xC3, 0x28, (byte) 0xFF, 0x00}).send().join();

        awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(10));
        assertThat(received).isNotEmpty();
        awaitError(MqttAdapter.MSG_PAYLOAD_NOT_UTF8);
        assertThat(errorCount(MqttAdapter.MSG_PAYLOAD_NOT_UTF8)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERR-MQTT-02 非数值负载：必须记 iot.mqtt.payload.not-number")
    void nonNumericPayloadMustBeCounted() {
        DeviceSession session = openSession(MqttPayloadFormat.NUMBER);
        List<PointValue> received = new CopyOnWriteArrayList<>();
        session.subscribe(new SubscribeRequest(List.of(PointAddress.of(TOPIC)), Duration.ofMillis(100),
                        Duration.ofMillis(100), null, Map.of()), received::add)
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        publisher.publishWith().topic(TOPIC).qos(MqttQos.AT_LEAST_ONCE)
                .payload("not-a-number".getBytes(java.nio.charset.StandardCharsets.UTF_8)).send().join();

        awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(10));
        assertThat(received).isNotEmpty();
        awaitError(MqttAdapter.MSG_PAYLOAD_NOT_NUMBER);
        assertThat(errorCount(MqttAdapter.MSG_PAYLOAD_NOT_NUMBER)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERR-MQTT-03 发布主题非法：必须记 iot.mqtt.topic.invalid")
    void invalidPublishTopicMustBeCounted() {
        DeviceSession session = openSession(MqttPayloadFormat.TEXT);
        session.write(WriteRequest.of(new PointWrite(PointAddress.of("#"), "value")))
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(errorCount(MqttAdapter.MSG_TOPIC_INVALID)).isEqualTo(1.0);
    }

    @Test
    @DisplayName("ERR-MQTT-04 值类型不支持：必须记 iot.mqtt.value.invalid")
    void unsupportedWriteValueMustBeCounted() {
        DeviceSession session = openSession(MqttPayloadFormat.TEXT);
        session.write(WriteRequest.of(new PointWrite(PointAddress.of(TOPIC), List.of(1))))
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(errorCount(MqttAdapter.MSG_VALUE_INVALID)).isEqualTo(1.0);
    }

    private DeviceSession openSession(MqttPayloadFormat format) {
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 1, null, format));
        ConnectionSpec spec = new ConnectionSpec("metrics", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + broker.port()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), null, null, Map.of());
        ProtocolConnection connection = adapter.open(spec, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        return adapter.bind(connection,
                new DeviceSpec("metrics-device", "设备", MqttAdapter.PROTOCOL_CODE, "metrics", TOPIC,
                        Duration.ofMillis(100), Map.of()),
                context).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
    }

    private double errorCount(String type) {
        var counter = registry.find("ypbin.iot.errors").tag("type", type).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void awaitError(String type) {
        awaitUntil(() -> errorCount(type) >= 1.0, Duration.ofSeconds(10));
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
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
