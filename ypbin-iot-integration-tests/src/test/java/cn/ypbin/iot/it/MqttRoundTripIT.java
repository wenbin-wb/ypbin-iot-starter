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
package cn.ypbin.iot.it;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * MQTT 全链路集成测试。
 *
 * <p><b>为什么需要它</b>：协议模块的单测都是「直接 new 适配器」，绕过了
 * 自动装配、生命周期编排、连接注册表、出口路由。本用例把真实宿主要做的事
 * （提供设备台账、建链参数、数据出口）组装成一个完整 Spring Boot 应用，
 * 断言<b>数据真的到达了 DataSink</b> —— 而不是「某处没抛异常」。</p>
 *
 * <p>覆盖链路：自动装配 → {@code ApplicationReadyEvent} 绑定设备 → 建链 →
 * 订阅 → broker 推送 → 适配器回调 → 宿主转发 → {@code EgressRouter} →
 * 批量合并 → {@code DataSink}。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
@SpringBootTest(classes = {ItApplication.class, MqttRoundTripIT.HostConfiguration.class})
class MqttRoundTripIT {

    private static EmbeddedMqttBroker broker;

    private static Mqtt3AsyncClient publisher;

    @Autowired
    private IotLifecycle lifecycle;

    @Autowired
    private DataEgress egress;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private RecordingSink sink;

    @BeforeAll
    static void startBroker() {
        // broker 必须在 Spring 上下文启动前就绪（建链参数要用它的端口）
        broker = new EmbeddedMqttBroker();
        publisher = MqttClient.builder()
                .identifier("it-publisher-" + System.nanoTime())
                .serverHost("127.0.0.1")
                .serverPort(broker.port())
                .useMqttVersion3()
                .buildAsync();
        publisher.connect().join();
    }

    @AfterAll
    static void stopBroker() {
        if (publisher != null) {
            publisher.disconnect().join();
        }
        if (broker != null) {
            broker.close();
        }
    }

    @Test
    @DisplayName("IT-MQTT-01 启动后设备必须自动绑定，且 broker 推送的数据必须到达 DataSink")
    void pushedDataMustReachSink() {
        assertThat(lifecycle.sessionCount())
                .as("ApplicationReadyEvent 上必须自动绑定设备台账里的设备")
                .isEqualTo(1);
        DeviceSession session = lifecycle.sessions().get(ItApplication.DEVICE_ID);
        assertThat(session).isNotNull();

        // 宿主侧订阅：把回调转发到出口（这正是真实宿主要写的代码）
        session.subscribe(new SubscribeRequest(List.of(PointAddress.of(ItApplication.TOPIC)),
                        Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()),
                value -> egress.emit(new DataBatch(ItApplication.DEVICE_ID, ItApplication.MQTT,
                        ItApplication.CONNECTION_ID, java.time.Instant.now(), List.of(value))))
                .toCompletableFuture().orTimeout(10, java.util.concurrent.TimeUnit.SECONDS).join();

        publish(ItApplication.TOPIC, "23.5");

        awaitUntil(() -> !sink.points().isEmpty(), Duration.ofSeconds(10));
        assertThat(sink.points())
                .as("数据必须一路走到 DataSink；只断言会话建立是测不出中途丢数据的")
                .isNotEmpty();
        // MQTT 的负载是不透明字节，本适配器目前**无条件按 UTF-8 解码为 String**，
        // 因此 "23.5" 到达宿主时是 String 而不是 Double（宿主需自行按业务类型解析）。
        // 这条断言把当前行为钉住；二进制负载的静默损坏风险已登记在 docs/PROTOCOLS.md。
        assertThat(sink.points().get(0).value())
                .as("MQTT 文本负载以 String 形态交付（当前契约）")
                .isEqualTo("23.5");
    }

    @Test
    @DisplayName("IT-MQTT-02 指标必须真的记进 MeterRegistry（有 actuator 时的真实路径）")
    void metricsMustBeRecorded() {
        publish(ItApplication.TOPIC, "42");

        awaitUntil(() -> meterRegistry.getMeters().stream()
                .anyMatch(meter -> meter.getId().getName().startsWith("ypbin.iot.")), Duration.ofSeconds(10));
        assertThat(meterRegistry.getMeters().stream()
                .map(meter -> meter.getId().getName())
                .filter(name -> name.startsWith("ypbin.iot."))
                .toList())
                .as("集成路径下 Micrometer 实现必须被装配并真的收到指标")
                .isNotEmpty();
    }

    private static void publish(String topic, String payload) {
        publisher.publishWith()
                .topic(topic)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload(payload.getBytes(StandardCharsets.UTF_8))
                .send().join();
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

    /**
     * 宿主侧装配：设备台账 + 建链参数 + 数据出口 + 指标注册表。
     *
     * <p>刻意只提供这三种 SPI 加一个 {@code MeterRegistry} —— 不做任何框架内部的接线，
     * 以此验证「宿主只需要做这几件事」这一契约。</p>
     *
     * @author wenbin
     * @since 2026-09-15
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class HostConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }

        @Bean
        RecordingSink recordingSink() {
            return new RecordingSink();
        }

        @Bean
        DeviceRegistry deviceRegistry() {
            DeviceSpec device = new DeviceSpec(ItApplication.DEVICE_ID, "集成测试 MQTT 设备",
                    ItApplication.MQTT, ItApplication.CONNECTION_ID, ItApplication.TOPIC,
                    Duration.ofMillis(100), Map.of());
            return new DeviceRegistry() {

                @Override
                public List<DeviceSpec> loadAll() {
                    return List.of(device);
                }

                @Override
                public void addChangeListener(Consumer<DeviceChange> listener) {
                    // 集成测试不做热更新
                }
            };
        }

        @Bean
        ConnectionSpecProvider connectionSpecProvider() {
            return connectionId -> ItApplication.CONNECTION_ID.equals(connectionId)
                    ? Optional.of(new ConnectionSpec(connectionId, ItApplication.MQTT,
                            Endpoint.of("tcp://127.0.0.1:" + broker.port()),
                            Duration.ofSeconds(5), Duration.ofSeconds(5), null, null, Map.of()))
                    : Optional.empty();
        }
    }
}
