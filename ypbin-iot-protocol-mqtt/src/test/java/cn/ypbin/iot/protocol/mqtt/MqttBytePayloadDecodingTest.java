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
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.DecodeContext;
import cn.ypbin.iot.core.spi.DecodeFailure;
import cn.ypbin.iot.core.spi.DecodeOutcome;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.registry.DefaultBytePayloadDecoderRegistry;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import com.hivemq.client.mqtt.MqttClient;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
 * MQTT 二进制负载与宿主解码 SPI 的接线测试（issue #30）。
 *
 * <p>{@code payload-format=binary} 的承诺是「原样交付字节，宿主自行按业务协议解析」。
 * 本类验证两件事：<b>①</b> 未注册解码器时行为逐字不变（字节原样交付，向后兼容）；
 * <b>②</b> 注册解码器后二进制字节被真正解码成规范值，失败则显式 BAD。</p>
 *
 * <p>它同时是「SPI 真的接进了协议模块」的证据：只测 core 的路由函数无法证明 MQTT 的
 * dispatch 路径调用了它。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class MqttBytePayloadDecodingTest {

    private static final String TOPIC = "factory/line1/value";

    private static final byte[] PAYLOAD = {0x00, 0x2A};

    private MqttTestBroker broker;

    private DefaultTaskScheduler scheduler;

    private Mqtt3AsyncClient publisher;

    private MqttAdapter adapter;

    private AdapterContext context;

    @BeforeEach
    void setUp() throws IOException {
        broker = new MqttTestBroker();
        scheduler = new DefaultTaskScheduler(2, 64);
        context = contextWith(BytePayloadDecoderRegistry.empty());
        publisher = MqttClient.builder()
                .identifier("byte-payload-publisher-" + System.nanoTime())
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
    @DisplayName("MBPD-01 未注册解码器：binary 负载仍原样交付 byte[]（向后兼容锚点）")
    void binaryPayloadMustStayUntouchedWithoutDecoder() {
        PointValue point = receive(BytePayloadDecoderRegistry.empty());
        assertThat(point.quality()).isEqualTo(Quality.GOOD);
        assertThat(point.value()).isInstanceOf(byte[].class);
        assertThat((byte[]) point.value()).containsExactly(PAYLOAD);
    }

    @Test
    @DisplayName("MBPD-02 注册解码器：binary 负载被解码为规范数值（SPI 真的接进了 MQTT 路径）")
    void registeredDecoderMustDecodeBinaryPayload() {
        PointValue point = receive(registryOf(new UnsignedShortDecoder()));
        assertThat(point.quality()).isEqualTo(Quality.GOOD);
        assertThat(point.value()).isEqualTo(42);
    }

    @Test
    @DisplayName("MBPD-03 解码失败：产出 BAD + 失败原因键，不得把原始字节当 GOOD 交付")
    void decodeFailureMustYieldBadPoint() {
        PointValue point = receive(registryOf(new FixedOutcomeDecoder(
                DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH))));
        assertThat(point.quality()).isEqualTo(Quality.BAD);
        assertThat(point.value()).isNull();
        assertThat(point.qualityReason())
                .isEqualTo(DecodeFailure.LENGTH_MISMATCH.messageKey());
    }

    private AdapterContext contextWith(BytePayloadDecoderRegistry registry) {
        return new DefaultAdapterContext(MqttAdapter.PROTOCOL_CODE, DefaultAdapterSettings.defaults(),
                new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(),
                Clock.systemUTC(), 32, registry);
    }

    private static BytePayloadDecoderRegistry registryOf(BytePayloadDecoder decoder) {
        return new DefaultBytePayloadDecoderRegistry(List.of(decoder));
    }

    /** 以 binary 格式订阅、发布一次负载、取回点位值。 */
    private PointValue receive(BytePayloadDecoderRegistry registry) {
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 1, null,
                MqttPayloadFormat.BINARY));
        AdapterContext effectiveContext = contextWith(registry);
        ConnectionSpec spec = new ConnectionSpec("mbpd", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + broker.port()), Duration.ofSeconds(5),
                Duration.ofSeconds(5), null, null, Map.of());
        ProtocolConnection connection = adapter.open(spec, effectiveContext).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        DeviceSession session = adapter.bind(connection,
                new DeviceSpec("d1", "设备", MqttAdapter.PROTOCOL_CODE, "mbpd", TOPIC,
                        Duration.ofMillis(100), Map.of()),
                effectiveContext).toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        List<PointValue> received = new CopyOnWriteArrayList<>();
        session.subscribe(new SubscribeRequest(List.of(PointAddress.of(TOPIC)),
                        Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()), received::add)
                .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

        publisher.publishWith().topic(TOPIC).qos(MqttQos.AT_LEAST_ONCE).payload(PAYLOAD).send().join();
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
     * 参考解码器：把 2 字节大端无符号整数解成 {@link Integer}。
     *
     * <p>只认 MQTT 的 {@value #TOPIC} 主题，用于证明「按点位认领」可用。</p>
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class UnsignedShortDecoder implements BytePayloadDecoder {

        @Override
        public String id() {
            return "test-unsigned-short";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return context.protocol().value().equals(MqttAdapter.PROTOCOL_CODE.value())
                    && TOPIC.equals(context.rawAddress());
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            if (payload.length != Short.BYTES) {
                return DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH);
            }
            return DecodeOutcome.ok((int) ByteBuffer.wrap(payload)
                    .order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF);
        }
    }

    /**
     * 固定结果解码器（只用于失败路径）。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class FixedOutcomeDecoder implements BytePayloadDecoder {

        private final DecodeOutcome outcome;

        private FixedOutcomeDecoder(DecodeOutcome outcome) {
            this.outcome = outcome;
        }

        @Override
        public String id() {
            return "test-fixed-outcome";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return true;
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return outcome;
        }
    }

    /**
     * 无操作出口（本测试走 listener 直接消费）。
     *
     * @author wenbin
     * @since 2026-10-17
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
