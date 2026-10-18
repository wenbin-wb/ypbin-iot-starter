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
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MQTT 多地址订阅的能力证据测试（issue #37 阻塞缺口 2）。
 *
 * <p><b>为什么要专门证明</b>：`subscriptionAddressing=BATCH` 是「一次请求可携带多个地址，
 * 且每个地址都独立产出数据」的承诺。凭感觉声明 BATCH 是危险的——若实际只投递第一个主题，
 * 宿主会拿到「订阅成功但只有第一个点位有数据」的静默数据缺失。因此这里用真实嵌入式 broker
 * 实测：<b>一次 subscribe 请求带 2 个主题，两个主题的数据都必须被投递回来</b>。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class MqttMultiAddressSubscriptionTest {

    private static final String TOPIC_A = "factory/line1/temp";

    private static final String TOPIC_B = "factory/line1/humidity";

    private MqttTestBroker broker;

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private Mqtt3AsyncClient publisher;

    private MqttAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        broker = new MqttTestBroker();
        scheduler = new DefaultTaskScheduler(2, 64);
        context = new DefaultAdapterContext(MqttAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 32);
        publisher = MqttClient.builder()
                .identifier("multi-address-publisher-" + System.nanoTime())
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
        broker.close();
    }

    @Test
    @DisplayName("MPF-06 描述符必须声明 BATCH（一次订阅可携带多个主题）")
    void descriptorMustDeclareBatch() {
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 1, null, null));
        assertThat(adapter.descriptor().subscriptionAddressing())
                .as("MQTT 一次订阅可携带多个主题过滤器，声明 BATCH 以减少订阅调用数")
                .isEqualTo(SubscriptionAddressing.BATCH);
    }

    @Test
    @DisplayName("MPF-07 一次 subscribe 带 2 个主题时，两个主题的数据都必须被投递（BATCH 承诺的证据）")
    void oneRequestWithTwoTopicsMustDeliverBoth() {
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 1, null, null));
        ConnectionSpec spec = new ConnectionSpec("multi", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + broker.port()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), null, null, Map.of());
        ProtocolConnection connection = adapter.open(spec, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        DeviceSession session = adapter.bind(connection,
                new DeviceSpec("mqtt-multi", "设备", MqttAdapter.PROTOCOL_CODE, "multi", TOPIC_A,
                        Duration.ofMillis(100), Map.of()),
                context).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        List<PointValue> received = new CopyOnWriteArrayList<>();
        // 关键：**一次**请求携带 2 个地址（这正是 BATCH 声明的语义）
        SubscriptionHandle handle = session.subscribe(new SubscribeRequest(
                        List.of(PointAddress.of(TOPIC_A), PointAddress.of(TOPIC_B)),
                        Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()),
                        received::add)
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(handle.addresses()).hasSize(2);

        publisher.publishWith().topic(TOPIC_A).qos(MqttQos.AT_LEAST_ONCE)
                .payload("21.5".getBytes(StandardCharsets.UTF_8)).send().join();
        publisher.publishWith().topic(TOPIC_B).qos(MqttQos.AT_LEAST_ONCE)
                .payload("55.0".getBytes(StandardCharsets.UTF_8)).send().join();

        awaitDistinctTopics(received, 2);
        assertThat(received).extracting(value -> value.address().raw())
                .as("两个主题都必须有数据 —— 只订到第一个是「声称订阅 N 个点位却只交付 1 个」的静默缺失")
                .contains(TOPIC_A, TOPIC_B);
    }

    private static void awaitDistinctTopics(List<PointValue> received, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            long distinct = received.stream().map(value -> value.address().raw()).distinct().count();
            if (distinct >= expected) {
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
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作（本测试用 listener 接收数据）
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
