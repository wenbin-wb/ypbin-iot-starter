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
package cn.ypbin.iot.core.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 字节载荷解码路由测试（issue #30）。
 *
 * <p>本类钉住 {@link BytePayloadDecoding} 的行为矩阵：<b>空注册表 / 未认领 / 解码失败 /
 * 解码器抛异常 / 成功</b>五种路径，每一种都必须同时断言「点位值形态」与「指标计数」——
 * 只断言其一都会漏掉 issue #30 的原始病根（能力缺失时字节被静默丢弃且无信号）。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class BytePayloadDecodingTest {

    private static final ProtocolCode CODE = ProtocolCode.of("mqtt");

    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    private static final String DEVICE_ID = "dev-1";

    private static final String CONNECTION_ID = "conn-1";

    private final RecordingMetrics metrics = new RecordingMetrics();

    @Test
    @DisplayName("BPD-01 空注册表：字节原样交付，且不产生任何错误计数（与没有 SPI 逐字一致）")
    void emptyRegistryMustKeepBytesUntouched() {
        byte[] payload = {1, 2, 3};
        PointValue result = BytePayloadDecoding.apply(binaryPoint(payload), DEVICE_ID, CODE,
                CONNECTION_ID, BytePayloadDecoderRegistry.empty(), metrics);

        assertThat(result.quality()).isEqualTo(Quality.GOOD);
        assertThat(result.value()).isEqualTo(payload);
        assertThat(metrics.errors).as("未注册解码器不是错误，不得污染错误指标").isEmpty();
    }

    @Test
    @DisplayName("BPD-02 注册表为 null：等价于空注册表（不得 NPE）")
    void nullRegistryMustBehaveLikeEmpty() {
        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {9}), DEVICE_ID, CODE,
                null, null, metrics);
        assertThat(result.value()).isEqualTo(new byte[] {9});
        assertThat(metrics.errors).isEmpty();
    }

    @Test
    @DisplayName("BPD-03 注册表非空但无解码器认领：字节原样交付 + 显式计数 not-found")
    void unclaimedPayloadMustBeCounted() {
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(new FixedDecoder("other", false,
                DecodeOutcome.ok(1))));
        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {7}), DEVICE_ID, CODE,
                CONNECTION_ID, registry, metrics);

        assertThat(result.value()).isEqualTo(new byte[] {7});
        assertThat(metrics.errors)
                .as("宿主已启用解码却没有解码器认领该点位 ⇒ 配置遗漏必须可见")
                .containsExactly(IotMessageKeys.PAYLOAD_DECODER_NOT_FOUND);
    }

    @Test
    @DisplayName("BPD-04 解码成功：值被替换，地址与源时间戳保持不变")
    void successfulDecodingMustKeepAddressAndTimestamp() {
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(
                new FixedDecoder("temp", true, DecodeOutcome.ok(23.5D))));
        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {1}), DEVICE_ID, CODE,
                CONNECTION_ID, registry, metrics);

        assertThat(result.quality()).isEqualTo(Quality.GOOD);
        assertThat(result.value()).isEqualTo(23.5D);
        assertThat(result.address()).isEqualTo(PointAddress.of("factory/line1/temp"));
        assertThat(result.timestamp()).isEqualTo(NOW);
        assertThat(metrics.errors).isEmpty();
    }

    @Test
    @DisplayName("BPD-05 解码失败：产出 BAD + 失败原因消息键 + 对应错误指标（不把垃圾当 GOOD）")
    void failedDecodingMustYieldBadWithMessageKey() {
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(
                new FixedDecoder("temp", true, DecodeOutcome.failed(DecodeFailure.LENGTH_MISMATCH))));
        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {1, 2}), DEVICE_ID, CODE,
                CONNECTION_ID, registry, metrics);

        assertThat(result.quality()).isEqualTo(Quality.BAD);
        assertThat(result.value()).isNull();
        assertThat(result.qualityReason())
                .isEqualTo(DecodeFailure.LENGTH_MISMATCH.messageKey())
                .isEqualTo("iot.common.payload.decode-failed.length-mismatch");
        assertThat(metrics.errors).containsExactly(DecodeFailure.LENGTH_MISMATCH.messageKey());
    }

    @Test
    @DisplayName("BPD-06 解码器抛异常：转成 BAD + decoder-error，不向协议线程逃逸")
    void throwingDecoderMustNotEscape() {
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(
                new FixedDecoder("broken", true, null) {
                    @Override
                    public DecodeOutcome decode(byte[] payload, DecodeContext context) {
                        throw new IllegalStateException("decoder bug by design");
                    }
                }));
        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {1}), DEVICE_ID, CODE,
                CONNECTION_ID, registry, metrics);

        assertThat(result.quality()).isEqualTo(Quality.BAD);
        assertThat(result.qualityReason()).isEqualTo(DecodeFailure.DECODER_ERROR.messageKey());
        assertThat(metrics.errors).containsExactly(DecodeFailure.DECODER_ERROR.messageKey());
    }

    @Test
    @DisplayName("BPD-07 解码器返回 null：按实现缺陷处理（不得 NPE、不得静默当作成功）")
    void nullOutcomeMustBeTreatedAsDecoderError() {
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(
                new FixedDecoder("broken", true, null) {
                    @Override
                    public DecodeOutcome decode(byte[] payload, DecodeContext context) {
                        return null;
                    }
                }));
        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {1}), DEVICE_ID, CODE,
                CONNECTION_ID, registry, metrics);

        assertThat(result.quality()).isEqualTo(Quality.BAD);
        assertThat(result.qualityReason()).isEqualTo(DecodeFailure.DECODER_ERROR.messageKey());
        assertThat(metrics.errors).containsExactly(DecodeFailure.DECODER_ERROR.messageKey());
    }

    @Test
    @DisplayName("BPD-08 非 byte[] 值 / 非 GOOD 值：完全绕过解码器（不得改写宿主已拿到的值）")
    void nonByteAndNonGoodValuesMustBeIgnored() {
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(new FixedDecoder("spy", true,
                DecodeOutcome.ok("decoded"))));
        PointValue text = PointValue.good(PointAddress.of("a"), "23.5", NOW);
        PointValue bad = PointValue.bad(PointAddress.of("b"), Quality.BAD, "iot.test", NOW);

        assertThat(BytePayloadDecoding.apply(text, DEVICE_ID, CODE, CONNECTION_ID, registry, metrics))
                .isSameAs(text);
        assertThat(BytePayloadDecoding.apply(bad, DEVICE_ID, CODE, CONNECTION_ID, registry, metrics))
                .isSameAs(bad);
        assertThat(metrics.errors).isEmpty();
    }

    @Test
    @DisplayName("BPD-09 选择顺序：第一个认领者胜出；都不认领才计 not-found")
    void firstClaimingDecoderMustWin() {
        BytePayloadDecoder first = new FixedDecoder("first", true, DecodeOutcome.ok("A"));
        BytePayloadDecoder second = new FixedDecoder("second", true, DecodeOutcome.ok("B"));
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(first, second));

        PointValue result = BytePayloadDecoding.apply(binaryPoint(new byte[] {1}), DEVICE_ID, CODE,
                CONNECTION_ID, registry, metrics);
        assertThat(result.value()).isEqualTo("A");
        assertThat(metrics.errors).isEmpty();
    }

    @Test
    @DisplayName("BPD-10 解码上下文必须携带设备/协议/地址/链路（解码器据此认领）")
    void contextMustCarryDecisionInputs() {
        List<DecodeContext> seen = new ArrayList<>();
        BytePayloadDecoderRegistry registry = new DefaultRegistry(List.of(new BytePayloadDecoder() {
            @Override
            public String id() {
                return "capturing";
            }

            @Override
            public boolean supports(DecodeContext context) {
                seen.add(context);
                return true;
            }

            @Override
            public DecodeOutcome decode(byte[] payload, DecodeContext context) {
                return DecodeOutcome.ok(payload.length);
            }
        }));

        BytePayloadDecoding.apply(binaryPoint(new byte[5]), DEVICE_ID, CODE, CONNECTION_ID, registry,
                metrics);

        assertThat(seen).hasSize(1);
        DecodeContext context = seen.get(0);
        assertThat(context.deviceId()).isEqualTo(DEVICE_ID);
        assertThat(context.protocol()).isEqualTo(CODE);
        assertThat(context.rawAddress()).isEqualTo("factory/line1/temp");
        assertThat(context.connectionId()).isEqualTo(CONNECTION_ID);
    }

    @Test
    @DisplayName("BPD-11 解码结果契约：成功必带值、失败必带原因，二者不可同时出现")
    void outcomeMustRejectInvalidCombination() {
        assertThatThrownBy(() -> new DecodeOutcome(null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new DecodeOutcome("value", DecodeFailure.INVALID_VALUE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DecodeOutcome.ok(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> DecodeOutcome.failed(null)).isInstanceOf(NullPointerException.class);
        assertThat(DecodeOutcome.ok("v").success()).isTrue();
        assertThat(DecodeOutcome.failed(DecodeFailure.INVALID_VALUE).success()).isFalse();
    }

    @Test
    @DisplayName("BPD-12 失败原因枚举必须 code/detail/desc 完备且 fromCode 可用（禁 ordinal）")
    void failureEnumMustBeWellFormed() {
        for (DecodeFailure failure : DecodeFailure.values()) {
            assertThat(failure.getCode()).isPositive();
            assertThat(failure.getDetail()).isNotBlank();
            assertThat(failure.getDesc()).isNotBlank();
            assertThat(failure.messageKey())
                    .isEqualTo(IotMessageKeys.PAYLOAD_DECODE_FAILED_PREFIX + failure.getDetail());
            assertThat(DecodeFailure.fromCode(failure.getCode())).contains(failure);
        }
        assertThat(DecodeFailure.fromCode(999)).isEmpty();
    }

    private static PointValue binaryPoint(byte[] payload) {
        return PointValue.good(PointAddress.of("factory/line1/temp"), payload.clone(), NOW);
    }

    /**
     * 测试用固定解码器。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static class FixedDecoder implements BytePayloadDecoder {

        private final String id;

        private final boolean claims;

        private final DecodeOutcome outcome;

        private FixedDecoder(String id, boolean claims, DecodeOutcome outcome) {
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
     * 测试用有序注册表（生产实现位于 runtime 模块，core 测试不得反向依赖）。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class DefaultRegistry implements BytePayloadDecoderRegistry {

        private final List<BytePayloadDecoder> decoders;

        private DefaultRegistry(List<BytePayloadDecoder> decoders) {
            this.decoders = List.copyOf(decoders);
        }

        @Override
        public Optional<BytePayloadDecoder> find(DecodeContext context) {
            return decoders.stream().filter(decoder -> decoder.supports(context)).findFirst();
        }

        @Override
        public List<BytePayloadDecoder> decoders() {
            return decoders;
        }
    }

    /**
     * 记录型指标实现。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class RecordingMetrics implements MetricsRecorder {

        private final List<String> errors = new ArrayList<>();

        @Override
        public void recordRead(Duration elapsed, boolean success) {
            // 本测试不关心
        }

        @Override
        public void recordWrite(Duration elapsed, boolean success) {
            // 本测试不关心
        }

        @Override
        public void recordSubscriptionBatch(int pointCount) {
            // 本测试不关心
        }

        @Override
        public void recordError(String errorType) {
            errors.add(errorType);
        }

        @Override
        public void gauge(String name, double value) {
            // 本测试不关心
        }
    }
}
