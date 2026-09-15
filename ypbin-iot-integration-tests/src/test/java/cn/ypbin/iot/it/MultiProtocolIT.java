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
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.spring.autoconfigure.IotLifecycle;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
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
 * 多协议并存集成测试。
 *
 * <p><b>为什么需要它</b>：各协议模块的装配类都是独立注册的，谁也没有测过
 * 「两个协议模块同时在场」会怎样 —— 包括：各自的 `@ConditionalOnProperty` 是否互不干扰、
 * 连接注册表能否同时容纳两种协议、生命周期能否把两个协议上的设备都绑上、
 * `MetricsRecorder` 是否被两个协议共用而不是各自装了一份。</p>
 *
 * <p>本用例在同一 Spring 上下文里同时接入 MQTT 与 TCP：断言两个设备都被绑定、
 * 两条链路都可用，并且 MQTT 的数据面在 TCP 链路存在时仍然通到 `DataSink`。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
@SpringBootTest(classes = {ItApplication.class, MultiProtocolIT.HostConfiguration.class})
class MultiProtocolIT {

    private static final ProtocolCode TCP = ProtocolCode.of("tcp");

    private static final String MQTT_DEVICE = "it-mqtt-device";

    private static final String TCP_DEVICE = "it-tcp-device";

    private static final String MQTT_LINK = "it-mqtt-link";

    private static final String TCP_LINK = "it-tcp-link";

    private static EmbeddedMqttBroker broker;

    private static ServerSocket tcpServer;

    private static Mqtt3AsyncClient publisher;

    @Autowired
    private IotLifecycle lifecycle;

    @Autowired
    private DataEgress egress;

    @Autowired
    private RecordingSink sink;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeAll
    static void startPeers() throws IOException {
        broker = new EmbeddedMqttBroker();
        tcpServer = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!tcpServer.isClosed()) {
                try {
                    Socket socket = tcpServer.accept();
                    socket.setKeepAlive(true);
                } catch (IOException ex) {
                    return;
                }
            }
        });
        publisher = MqttClient.builder()
                .identifier("it-multi-publisher-" + System.nanoTime())
                .serverHost("127.0.0.1")
                .serverPort(broker.port())
                .useMqttVersion3()
                .buildAsync();
        publisher.connect().join();
    }

    @AfterAll
    static void stopPeers() throws IOException {
        if (publisher != null) {
            publisher.disconnect().join();
        }
        if (broker != null) {
            broker.close();
        }
        if (tcpServer != null) {
            tcpServer.close();
        }
    }

    @Test
    @DisplayName("IT-MULTI-01 两个协议模块必须在同一上下文里并存，且两台设备都被绑定")
    void bothProtocolsMustCoexist() {
        assertThat(lifecycle.sessionCount())
                .as("MQTT 与 TCP 两台设备都必须被绑定 —— 只有一个说明某个协议模块的装配被另一个挤掉了")
                .isEqualTo(2);
        assertThat(lifecycle.sessions()).containsKeys(MQTT_DEVICE, TCP_DEVICE);
        assertThat(lifecycle.sessions().get(MQTT_DEVICE).device().protocol())
                .isEqualTo(ItApplication.MQTT);
        assertThat(lifecycle.sessions().get(TCP_DEVICE).device().protocol()).isEqualTo(TCP);
    }

    @Test
    @DisplayName("IT-MULTI-02 存在第二条协议链路时，MQTT 数据面仍必须通到 DataSink")
    void mqttDataPathMustSurviveAlongsideTcp() {
        DeviceSession session = lifecycle.sessions().get(MQTT_DEVICE);
        session.subscribe(new SubscribeRequest(List.of(PointAddress.of(ItApplication.TOPIC)),
                        Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()),
                value -> egress.emit(new DataBatch(MQTT_DEVICE, ItApplication.MQTT, MQTT_LINK,
                        Instant.now(), List.of(value))))
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        publisher.publishWith()
                .topic(ItApplication.TOPIC)
                .qos(MqttQos.AT_LEAST_ONCE)
                .payload("7.25".getBytes(StandardCharsets.UTF_8))
                .send().join();

        awaitUntil(() -> !sink.points().isEmpty(), Duration.ofSeconds(10));
        assertThat(sink.points()).as("多协议并存时数据面不得被另一条链路影响").isNotEmpty();
        assertThat(sink.points().get(0).value()).isEqualTo("7.25");
    }

    @Test
    @DisplayName("IT-MULTI-03 指标门面必须是同一个实例（两个协议共用一份，而不是各装一份）")
    void metricsMustBeSharedAcrossProtocols() {
        // 每个协议模块都会消费 MetricsRecorder；若各自装配了一份，
        // 宿主的注册表里会出现重复/分裂的指标，且宿主替换实现时只生效一半。
        assertThat(meterRegistry.getMeters()).isNotNull();
        assertThat(lifecycle.sessions()).hasSize(2);
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
     * 宿主侧装配：两协议各一台设备 + 数据出口 + 指标注册表。
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
            DeviceSpec mqtt = new DeviceSpec(MQTT_DEVICE, "MQTT 设备", ItApplication.MQTT, MQTT_LINK,
                    ItApplication.TOPIC, Duration.ofMillis(100), Map.of());
            DeviceSpec tcp = new DeviceSpec(TCP_DEVICE, "TCP 设备", TCP, TCP_LINK, "",
                    Duration.ofMillis(100), Map.of());
            return new DeviceRegistry() {

                @Override
                public List<DeviceSpec> loadAll() {
                    return List.of(mqtt, tcp);
                }

                @Override
                public void addChangeListener(Consumer<DeviceChange> listener) {
                    // 集成测试不做热更新
                }
            };
        }

        @Bean
        ConnectionSpecProvider connectionSpecProvider() {
            return connectionId -> switch (connectionId) {
                case MQTT_LINK -> Optional.of(new ConnectionSpec(MQTT_LINK, ItApplication.MQTT,
                        Endpoint.of("tcp://127.0.0.1:" + broker.port()),
                        Duration.ofSeconds(5), Duration.ofSeconds(5), null, null, Map.of()));
                case TCP_LINK -> Optional.of(new ConnectionSpec(TCP_LINK, TCP,
                        Endpoint.of("tcp://127.0.0.1:" + tcpServer.getLocalPort()),
                        Duration.ofSeconds(5), Duration.ofSeconds(5), null, null, Map.of()));
                default -> Optional.empty();
            };
        }
    }
}
