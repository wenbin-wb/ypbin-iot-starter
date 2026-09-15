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
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
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
 * MQTT 负载格式测试。
 *
 * <p>核心是那条<b>静默损坏</b>路径：{@code new String(bytes, UTF_8)} 对非 UTF-8 字节
 * <b>不会报错</b>，它把非法字节替换成 U+FFFD —— 数据在到达宿主前就变形了，
 * 而链路全程报成功。本类钉住「非法 UTF-8 必须产出 BAD 值」这一契约。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
class MqttPayloadFormatTest {

    private static final String TOPIC = "factory/line1/value";

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
                .identifier("payload-format-publisher-" + System.nanoTime())
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
    @DisplayName("MPF-01 text 格式下合法 UTF-8 必须解码为 String")
    void textFormatMustDecodeString() {
        PointValue point = receive(MqttPayloadFormat.TEXT, "23.5".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(point.quality()).isEqualTo(Quality.GOOD);
        assertThat(point.value()).isEqualTo("23.5");
    }

    @Test
    @DisplayName("MPF-02 text 格式下**非法 UTF-8 必须产出 BAD**，而不是带替换字符的损坏字符串")
    void textFormatMustRejectInvalidUtf8() {
        // 0xC3 0x28 是经典非法 UTF-8 序列；0x00 0xFF 也是常见二进制头
        PointValue point = receive(MqttPayloadFormat.TEXT, new byte[] {(byte) 0xC3, 0x28, (byte) 0xFF, 0x00});
        assertThat(point.quality())
                .as("非法 UTF-8 必须报 BAD —— 以前会静默产出带 U+FFFD 的字符串，"
                        + "数据已变形而链路报成功")
                .isEqualTo(Quality.BAD);
        assertThat(point.qualityReason()).isEqualTo(MqttAdapter.MSG_PAYLOAD_NOT_UTF8);
        assertThat(point.value()).as("不得交付损坏的字符串").isNull();
    }

    @Test
    @DisplayName("MPF-03 number 格式必须解析为 Double，非数值必须 BAD")
    void numberFormatMustParseAndReject() {
        assertThat(receive(MqttPayloadFormat.NUMBER, "42.5".getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .value()).isEqualTo(42.5D);
        assertThat(receive(MqttPayloadFormat.NUMBER, "not-a-number".getBytes(
                java.nio.charset.StandardCharsets.UTF_8)).quality()).isEqualTo(Quality.BAD);
    }

    @Test
    @DisplayName("MPF-04 binary 格式必须原样交付字节（不得解码，也就不会损坏）")
    void binaryFormatMustPassBytesThrough() {
        byte[] payload = {(byte) 0xC3, 0x28, (byte) 0xFF, 0x00, 0x7F};
        PointValue point = receive(MqttPayloadFormat.BINARY, payload);
        assertThat(point.quality()).isEqualTo(Quality.GOOD);
        assertThat(point.value()).isInstanceOf(byte[].class);
        assertThat((byte[]) point.value())
                .as("二进制负载必须逐字节保持原样")
                .containsExactly(payload);
    }

    @Test
    @DisplayName("MPF-05 枚举必须 code/desc 完备且 fromCode 可用（禁 ordinal）")
    void enumMustExposeCodeAndDesc() {
        for (MqttPayloadFormat format : MqttPayloadFormat.values()) {
            assertThat(format.getDesc()).isNotBlank();
            assertThat(MqttPayloadFormat.fromCode(format.getCode())).contains(format);
        }
        assertThat(MqttPayloadFormat.fromCode(999)).isEmpty();
    }

    /** 用指定格式订阅、发布一次、取回点位值。 */
    private PointValue receive(MqttPayloadFormat format, byte[] payload) {
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 1, null, format));
        ConnectionSpec spec = new ConnectionSpec("pf", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + broker.port()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), null, null, Map.of());
        ProtocolConnection connection = adapter.open(spec, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        DeviceSession session = adapter.bind(connection,
                new DeviceSpec("d1", "设备", MqttAdapter.PROTOCOL_CODE, "pf", TOPIC,
                        Duration.ofMillis(100), Map.of()),
                context).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        List<PointValue> received = new CopyOnWriteArrayList<>();
        session.subscribe(new SubscribeRequest(List.of(PointAddress.of(TOPIC)), Duration.ofMillis(100),
                        Duration.ofMillis(100), null, Map.of()), received::add)
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        publisher.publishWith().topic(TOPIC).qos(MqttQos.AT_LEAST_ONCE).payload(payload).send().join();
        awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(10));
        assertThat(received).as("未收到任何消息").isNotEmpty();
        return received.get(0);
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
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-09-15
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
