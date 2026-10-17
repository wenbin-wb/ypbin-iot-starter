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
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.DecodeContext;
import cn.ypbin.iot.core.spi.DecodeFailure;
import cn.ypbin.iot.core.spi.DecodeOutcome;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.registry.DefaultBytePayloadDecoderRegistry;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OPC UA {@code ByteString} 节点与宿主解码 SPI 的接线测试（issue #30）。
 *
 * <p><b>为什么这条路径必须单独验证</b>：OPC UA 的原始字节类型是 Milo 的
 * {@code ByteString}（一个 record），而不是 {@code byte[]}。若不做归一，宿主解码器
 * （输入契约是 {@code byte[]}）根本无从认领——SPI 装了也等于没装，且宿主会被迫依赖 Milo 类型。
 * 因此本类同时断言两件事：<b>①</b> 无解码器时宿主拿到的是 {@code byte[]}（而非 Milo 类型）；
 * <b>②</b> 注册解码器后被真正解码，失败则显式 BAD。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class OpcUaBytePayloadDecodingTest {

    private OpcUaTestServer server;

    private OpcUaAdapter adapter;

    private DefaultTaskScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        server = new OpcUaTestServer();
        adapter = new OpcUaAdapter(null);
        scheduler = new DefaultTaskScheduler(2, 64);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
        if (scheduler != null) {
            scheduler.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("OBPD-01 未注册解码器：ByteString 节点必须交付 byte[]（不得把 Milo 类型漏给宿主）")
    void byteStringMustBeNormalizedToBytes() {
        PointValue value = readWithRegistry(BytePayloadDecoderRegistry.empty());

        assertThat(value.quality()).isEqualTo(Quality.GOOD);
        assertThat(value.value())
                .as("宿主解码 SPI 的输入契约是 byte[]；交付 Milo ByteString 会让每个宿主都被迫依赖 Milo")
                .isInstanceOf(byte[].class);
        assertThat((byte[]) value.value()).containsExactly(0x00, 0x2A);
    }

    @Test
    @DisplayName("OBPD-02 注册解码器：ByteString 节点被解码为规范数值（SPI 真的接进了 OPC UA 路径）")
    void registeredDecoderMustDecodeByteString() {
        PointValue value = readWithRegistry(new DefaultBytePayloadDecoderRegistry(
                List.of(new UnsignedShortDecoder())));

        assertThat(value.quality()).isEqualTo(Quality.GOOD);
        assertThat(value.value()).isEqualTo(42);
    }

    @Test
    @DisplayName("OBPD-03 解码失败：产出 BAD + 失败原因键，不得把原始字节当 GOOD 交付")
    void decodeFailureMustYieldBadPoint() {
        PointValue value = readWithRegistry(new DefaultBytePayloadDecoderRegistry(
                List.of(new FixedOutcomeDecoder(DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH)))));

        assertThat(value.quality()).isEqualTo(Quality.BAD);
        assertThat(value.value()).isNull();
        assertThat(value.qualityReason()).isEqualTo(DecodeFailure.LENGTH_MISMATCH.messageKey());
    }

    @Test
    @DisplayName("OBPD-04 原生订阅推送的 ByteString 也必须过解码 SPI（不能只修 read 路径）")
    void subscriptionPushMustBeDecodedToo() throws Exception {
        AdapterContext context = contextWith(new DefaultBytePayloadDecoderRegistry(
                List.of(new UnsignedShortDecoder())));
        DeviceSession session = openSession(context);
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            session.subscribe(new SubscribeRequest(
                            List.of(PointAddress.of(server.nodeAddress("BinaryBlob"))),
                            Duration.ofMillis(100), Duration.ofMillis(100), null, Map.of()),
                            received::add)
                    .toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join();

            server.updateValue("BinaryBlob", ByteString.of(new byte[] {0x00, 0x2B}));
            awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(10));

            assertThat(received).isNotEmpty();
            assertThat(received.get(0).quality()).isEqualTo(Quality.GOOD);
            assertThat(received.get(0).value()).isEqualTo(43);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    private PointValue readWithRegistry(BytePayloadDecoderRegistry registry) {
        AdapterContext context = contextWith(registry);
        DeviceSession session = openSession(context);
        try {
            return session.read(ReadRequest.of(
                            PointAddress.of(server.nodeAddress("BinaryBlob"))))
                    .toCompletableFuture().orTimeout(15, TimeUnit.SECONDS).join()
                    .values().get(0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    private AdapterContext contextWith(BytePayloadDecoderRegistry registry) {
        return new DefaultAdapterContext(OpcUaAdapter.PROTOCOL_CODE, DefaultAdapterSettings.defaults(),
                new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(),
                Clock.systemUTC(), 16, registry);
    }

    private DeviceSession openSession(AdapterContext context) {
        ConnectionSpec spec = new ConnectionSpec("opcua-decode", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of(server.endpointUrl()), Duration.ofSeconds(20), Duration.ofSeconds(20),
                null, null, Map.of());
        ProtocolConnection connection = adapter.open(spec, context).toCompletableFuture()
                .orTimeout(30, TimeUnit.SECONDS).join();
        return adapter.bind(connection,
                new DeviceSpec("opcua-decode-device", "OPC UA decode", OpcUaAdapter.PROTOCOL_CODE,
                        "opcua-decode", "", Duration.ZERO, Map.of()),
                context).toCompletableFuture().orTimeout(30, TimeUnit.SECONDS).join();
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
     * 参考解码器：把 2 字节大端无符号数解成 {@link Integer}（只认 BinaryBlob 节点）。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class UnsignedShortDecoder implements BytePayloadDecoder {

        @Override
        public String id() {
            return "test-opcua-unsigned-short";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return OpcUaAdapter.PROTOCOL_CODE.value().equals(context.protocol().value())
                    && context.rawAddress().endsWith("s=BinaryBlob");
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
            return "test-opcua-fixed-outcome";
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
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 本测试走 read / listener 直接消费
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
