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
package cn.ypbin.iot.runtime.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.BytePayloadDecoding;
import cn.ypbin.iot.core.spi.DecodeContext;
import cn.ypbin.iot.core.spi.DecodeFailure;
import cn.ypbin.iot.core.spi.DecodeOutcome;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字节载荷解码器注册表测试（issue #30）。
 *
 * <p>重点验证三件事：<b>选择顺序确定</b>（不随注册顺序漂动）、<b>空注册表语义</b>
 * （与「没有 SPI」逐字一致）、以及 <b>{@code AdapterContext} 的默认路由真的接上了</b>
 * （否则 SPI 存在但协议模块拿不到，等于没实现）。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class DefaultBytePayloadDecoderRegistryTest {

    private static final ProtocolCode CODE = ProtocolCode.of("mqtt");

    private static final DecodeContext CONTEXT = new DecodeContext("dev-1", CODE, "topic/a", "conn-1");

    @Test
    @DisplayName("BPR-01 按给定顺序返回第一个认领者（顺序由装配层决定，注册表不改写）")
    void findMustReturnFirstClaimingDecoderInGivenOrder() {
        BytePayloadDecoder first = new StubDecoder("first", true, DecodeOutcome.ok("A"));
        BytePayloadDecoder second = new StubDecoder("second", true, DecodeOutcome.ok("B"));

        DefaultBytePayloadDecoderRegistry registry = new DefaultBytePayloadDecoderRegistry(
                List.of(first, second));

        assertThat(registry.find(CONTEXT)).containsSame(first);
        assertThat(registry.decoders()).containsExactly(first, second);
    }

    @Test
    @DisplayName("BPR-02 都不认领时返回空（调用方据此原样交付字节）")
    void findMustBeEmptyWhenNoDecoderClaims() {
        DefaultBytePayloadDecoderRegistry registry = new DefaultBytePayloadDecoderRegistry(
                List.of(new StubDecoder("a", false, DecodeOutcome.ok(1))));
        assertThat(registry.find(CONTEXT)).isEmpty();
    }

    @Test
    @DisplayName("BPR-03 空/ null 集合归一为空注册表，且 isEmpty() 为真（不得返回 null 集合）")
    void emptyInputsMustNormalizeToEmptyRegistry() {
        assertThat(new DefaultBytePayloadDecoderRegistry(null).isEmpty()).isTrue();
        assertThat(new DefaultBytePayloadDecoderRegistry(null).decoders()).isEmpty();
        assertThat(new DefaultBytePayloadDecoderRegistry(List.of()).isEmpty()).isTrue();
        assertThat(BytePayloadDecoderRegistry.empty().isEmpty()).isTrue();
        assertThat(BytePayloadDecoderRegistry.empty().find(CONTEXT)).isEmpty();
        assertThat(BytePayloadDecoderRegistry.empty().decoders()).isEmpty();
    }

    @Test
    @DisplayName("BPR-04 重复登记同一实例必须去重且保留首次位置（不得问同一个解码器两次）")
    void duplicateInstancesMustBeDeduplicated() {
        BytePayloadDecoder once = new StubDecoder("once", false, DecodeOutcome.ok(1));
        DefaultBytePayloadDecoderRegistry registry = new DefaultBytePayloadDecoderRegistry(
                List.of(once, once, once));
        assertThat(registry.decoders()).containsExactly(once);
    }

    @Test
    @DisplayName("BPR-05 null 解码器不得被静默接受（否则 find 时 NPE）")
    void nullDecoderMustBeRejected() {
        assertThatThrownBy(() -> new DefaultBytePayloadDecoderRegistry(
                Arrays.asList(new StubDecoder("a", true, DecodeOutcome.ok(1)), null)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("BPR-06 find(null) 返回空而不是 NPE（防御性，调用方可能传空上下文）")
    void findWithNullContextMustReturnEmpty() {
        DefaultBytePayloadDecoderRegistry registry = new DefaultBytePayloadDecoderRegistry(
                List.of(new StubDecoder("a", true, DecodeOutcome.ok(1))));
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    @DisplayName("BPR-07 DefaultAdapterContext 必须把注册表透传给协议模块（否则 SPI 装上也没用）")
    void adapterContextMustExposeRegistry() {
        DefaultTaskScheduler scheduler = new DefaultTaskScheduler(2, 64);
        try {
            BytePayloadDecoder decoder = new StubDecoder("ctx", true, DecodeOutcome.ok("decoded"));
            DefaultBytePayloadDecoderRegistry registry = new DefaultBytePayloadDecoderRegistry(
                    List.of(decoder));
            DefaultAdapterContext context = new DefaultAdapterContext(CODE,
                    DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                    NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 16,
                    registry);

            assertThat(context.payloadDecoders()).isSameAs(registry);
            PointValue raw = PointValue.good(PointAddress.of("topic/a"), new byte[] {1, 2},
                    Instant.parse("2026-10-17T00:00:00Z"));
            PointValue decoded = context.decodePayload(raw, "dev-1", "conn-1");
            assertThat(decoded.value()).isEqualTo("decoded");
            assertThat(decoded.quality()).isEqualTo(Quality.GOOD);
        } finally {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("BPR-08 不传注册表的旧构造器必须等价空注册表（向后兼容，不得 NPE）")
    void legacyConstructorMustDefaultToEmptyRegistry() {
        DefaultTaskScheduler scheduler = new DefaultTaskScheduler(2, 64);
        try {
            DefaultAdapterContext context = new DefaultAdapterContext(CODE,
                    DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                    NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 16);
            assertThat(context.payloadDecoders().isEmpty()).isTrue();

            PointValue raw = PointValue.good(PointAddress.of("topic/a"), new byte[] {5},
                    Instant.parse("2026-10-17T00:00:00Z"));
            PointValue kept = context.decodePayload(raw, "dev-1", "conn-1");
            assertThat(kept).isSameAs(raw);
        } finally {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("BPR-09 端到端路由：认领后解码失败必须产出 BAD + 原因键（不是 GOOD 的垃圾值）")
    void endToEndFailureMustBecomeBad() {
        DefaultTaskScheduler scheduler = new DefaultTaskScheduler(2, 64);
        try {
            BytePayloadDecoder decoder = new StubDecoder("bad", true,
                    DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH));
            DefaultAdapterContext context = new DefaultAdapterContext(CODE,
                    DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                    NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 16,
                    new DefaultBytePayloadDecoderRegistry(List.of(decoder)));

            PointValue raw = PointValue.good(PointAddress.of("topic/a"), new byte[] {1},
                    Instant.parse("2026-10-17T00:00:00Z"));
            PointValue bad = context.decodePayload(raw, "dev-1", "conn-1");
            assertThat(bad.quality()).isEqualTo(Quality.BAD);
            assertThat(bad.qualityReason()).startsWith(IotMessageKeys.PAYLOAD_DECODE_FAILED_PREFIX);
        } finally {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("BPR-10 路由门面在空注册表下必须原样返回 bytes（与 BytePayloadDecoding 语义一致）")
    void facadeMustKeepBytesWhenRegistryEmpty() {
        PointValue raw = PointValue.good(PointAddress.of("topic/a"), new byte[] {9},
                Instant.parse("2026-10-17T00:00:00Z"));
        assertThat(BytePayloadDecoding.apply(raw, "dev-1", CODE, null,
                BytePayloadDecoderRegistry.empty(), NoopMetricsRecorder.INSTANCE)).isSameAs(raw);
    }

    /**
     * 测试用固定解码器。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static class StubDecoder implements BytePayloadDecoder {

        private final String id;

        private final boolean claims;

        private final DecodeOutcome outcome;

        private StubDecoder(String id, boolean claims, DecodeOutcome outcome) {
            this.id = id;
            this.claims = claims;
            this.outcome = outcome;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean supports(DecodeContext context) {
            return claims;
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return outcome;
        }
    }

    /**
     * 无操作出口（本测试不关心数据投递）。
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
