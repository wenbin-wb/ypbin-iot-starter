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
package cn.ypbin.iot.protocol.modbus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ModbusRegisterDecoder} 的算术矩阵回归测试（issue #31）。
 *
 * <p><b>为什么要有 8 组矩阵</b>：Modbus 寄存器解码的错误几乎全是「字序 / 符号扩展」的静默错值
 * ——不抛异常、不告警，只是数字错了（例如把 {@code -1} 读成 {@code 65535}、把 {@code 1.5f}
 * 读成 {@code 1069547520}）。因此这里逐格钉死 {@code word_count × signed × byte_order}
 * 的 8 种组合，并补齐边界（{@code 0xFFFF}、{@code 0x8000}、{@code 2^32-1}）。</p>
 *
 * <p>矩阵口径来自宿主历史实现（{@code docs/VALUE-DECODE-DESIGN.md} §4.3.3），
 * 本仓以「协议基座原语」正式承接，故测试同时充当<b>下游迁移的对照表</b>。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class ModbusRegisterDecoderTest {

    /** 0x1234：高位字节 0x12、低位 0x34。 */
    private static final int WORD_A = 0x1234;

    /** 0x5678：用于拼 32 位 ABCD。 */
    private static final int WORD_B = 0x5678;

    /** 0x9ABC：带符号位的字。 */
    private static final int WORD_NEG_HIGH = 0x9ABC;

    /** 0xFFFF：全 1，即 16 位/32 位下的 -1。 */
    private static final int WORD_ALL_ONES = 0xFFFF;

    /** 0x8000：16 位最小负数。 */
    private static final int WORD_SIGN_BIT = 0x8000;

    @Test
    @DisplayName("MRD-01 单寄存器无符号：原样透传 0..65535（向后兼容锚点）")
    void singleUnsignedMustPassThrough() {
        assertThat(ModbusRegisterDecoder.decodeSingle(0, false)).isZero();
        assertThat(ModbusRegisterDecoder.decodeSingle(WORD_A, false)).isEqualTo(WORD_A);
        assertThat(ModbusRegisterDecoder.decodeSingle(WORD_ALL_ONES, false)).isEqualTo(65535);
        assertThat(ModbusRegisterDecoder.decodeSingle(WORD_SIGN_BIT, false)).isEqualTo(32768);
    }

    @Test
    @DisplayName("MRD-02 单寄存器有符号：16 位 two's complement 符号扩展")
    void singleSignedMustSignExtend() {
        assertThat(ModbusRegisterDecoder.decodeSingle(0, true)).isZero();
        assertThat(ModbusRegisterDecoder.decodeSingle(1, true)).isEqualTo(1);
        assertThat(ModbusRegisterDecoder.decodeSingle(0x7FFF, true)).isEqualTo(32767);
        assertThat(ModbusRegisterDecoder.decodeSingle(WORD_SIGN_BIT, true)).isEqualTo(-32768);
        assertThat(ModbusRegisterDecoder.decodeSingle(WORD_ALL_ONES, true)).isEqualTo(-1);
    }

    @Test
    @DisplayName("MRD-03 双寄存器无符号：big=ABCD 拼高位在前，32 位用 long 承载不截断")
    void int32UnsignedBigMustAssembleHighFirst() {
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_A, WORD_B, "big", false)).isEqualTo(0x12345678L);
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_A, WORD_B, null, false))
                .as("未声明字序时按高字在前（默认语义）")
                .isEqualTo(0x12345678L);
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_ALL_ONES, WORD_ALL_ONES, "big", false))
                .as("2^32-1 必须用 long 承载（不能回绕成 -1）")
                .isEqualTo(4294967295L);
    }

    @Test
    @DisplayName("MRD-04 双寄存器无符号：little=CDAB 低字在前")
    void int32UnsignedLittleMustAssembleLowFirst() {
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_A, WORD_B, "little", false)).isEqualTo(0x56781234L);
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_B, WORD_A, "little", false)).isEqualTo(0x12345678L);
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_ALL_ONES, 0x0000, "little", false))
                .as("低字在前时 0xFFFF 在低半部")
                .isEqualTo(0xFFFFL);
    }

    @Test
    @DisplayName("MRD-05 双寄存器有符号：big 下 32 位符号扩展（-1 / INT_MIN）")
    void int32SignedBigMustSignExtend() {
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_ALL_ONES, WORD_ALL_ONES, "big", true)).isEqualTo(-1L);
        assertThat(ModbusRegisterDecoder.decodeInt32(0x8000, 0x0000, "big", true))
                .as("0x80000000 = Integer.MIN_VALUE")
                .isEqualTo(Integer.MIN_VALUE);
        assertThat(ModbusRegisterDecoder.decodeInt32(0x7FFF, WORD_ALL_ONES, "big", true))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    @DisplayName("MRD-06 双寄存器有符号：little 下 32 位符号扩展")
    void int32SignedLittleMustSignExtend() {
        assertThat(ModbusRegisterDecoder.decodeInt32(WORD_ALL_ONES, WORD_ALL_ONES, "little", true)).isEqualTo(-1L);
        assertThat(ModbusRegisterDecoder.decodeInt32(0x0000, 0x8000, "little", true))
                .as("低字在前时高字在第二位：0x8000_0000")
                .isEqualTo(Integer.MIN_VALUE);
    }

    @Test
    @DisplayName("MRD-07 IEEE754 单精度：big / little 字序都要还原 1.5f 与负值")
    void floatBitsMustHonorWordOrder() {
        int bits = Float.floatToRawIntBits(1.5F);
        int high = (bits >>> 16) & ModbusRegisterDecoder.WORD_MASK;
        int low = bits & ModbusRegisterDecoder.WORD_MASK;
        assertThat(ModbusRegisterDecoder.decodeFloatBits(high, low, "big")).isEqualTo(1.5F);
        assertThat(ModbusRegisterDecoder.decodeFloatBits(low, high, "little")).isEqualTo(1.5F);
        assertThat(ModbusRegisterDecoder.decodeFloatBits(high, low, null))
                .as("未声明字序时按高字在前")
                .isEqualTo(1.5F);

        int negative = Float.floatToRawIntBits(-2.25F);
        assertThat(ModbusRegisterDecoder.decodeFloatBits((negative >>> 16) & ModbusRegisterDecoder.WORD_MASK,
                negative & ModbusRegisterDecoder.WORD_MASK, "big")).isEqualTo(-2.25F);
    }

    @Test
    @DisplayName("MRD-08 IEEE754 双精度：big=ABCD / little=DCBA 整体翻转")
    void doubleBitsMustHonorWordOrder() {
        long bits = Double.doubleToRawLongBits(3.141592653589793D);
        int[] big = splitWords(bits);
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(big, "big")).isEqualTo(3.141592653589793D);
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(big, null))
                .as("未声明字序时按高字在前")
                .isEqualTo(3.141592653589793D);
        int[] little = new int[] {big[3], big[2], big[1], big[0]};
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(little, "little"))
                .as("little = DCBA（整体翻转），不是 BADC")
                .isEqualTo(3.141592653589793D);
        assertThat(ModbusRegisterDecoder.decodeDoubleBits(new int[] {0, 0, 0, 0}, "little")).isZero();
    }

    @Test
    @DisplayName("MRD-09 双精度寄存器个数非法必须 fail-fast（不猜、不补零）")
    void doubleBitsMustRejectWrongWordCount() {
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeDoubleBits(new int[] {1, 2, 3}, "big"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4");
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeDoubleBits(new int[] {1, 2, 3, 4, 5}, "big"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MRD-10 UTF-8 字符串：高字节在前的寄存器序 + 去尾部 NUL + 保留中间 NUL")
    void stringUtf8MustAssembleHighByteFirst() throws CharacterCodingException {
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(toWords("AB"))).isEqualTo("AB");
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(toWords("温度"))).isEqualTo("温度");
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(toWords("SN123")))
                .as("奇数长度由尾部 NUL 补齐后必须去掉尾部 NUL")
                .isEqualTo("SN123");
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[] {0x4100, 0x4200}))
                .as("中间 0x00 必须保留（不得按 C 字符串截断）")
                .isEqualTo("A\u0000B");
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[] {0, 0, 0}))
                .as("全 NUL 是合法空串")
                .isEmpty();
        assertThat(ModbusRegisterDecoder.decodeStringUtf8(new int[0])).isEmpty();
    }

    @Test
    @DisplayName("MRD-11 UTF-8 字符串：非法字节必须抛 CharacterCodingException（不得替换成 U+FFFD）")
    void stringUtf8MustRejectMalformedBytes() {
        // 0xC3 0x28 是经典非法 UTF-8 序列（2 字节首字节后跟非法续字节）
        int[] malformed = {(0xC3 << 8) | 0x28};
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeStringUtf8(malformed))
                .as("非法 UTF-8 不得静默替换字符——那会让设备数据在解码阶段就变形")
                .isInstanceOf(CharacterCodingException.class);
    }

    @Test
    @DisplayName("MRD-12 位域：单 bit 返回 Boolean，多 bit 返回无符号 Integer，越界 fail-fast")
    void bitsMustDecodeAndValidateRange() {
        assertThat(ModbusRegisterDecoder.decodeBits(0b0000_0000_0000_0100, 2, 1)).isEqualTo(Boolean.TRUE);
        assertThat(ModbusRegisterDecoder.decodeBits(0b0000_0000_0000_0000, 2, 1)).isEqualTo(Boolean.FALSE);
        assertThat(ModbusRegisterDecoder.decodeBits(0xABCD, 4, 8)).isEqualTo(0xBC);
        assertThat(ModbusRegisterDecoder.decodeBits(0xFFFF, 0, 16)).isEqualTo(65535);
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeBits(0, 16, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeBits(0, 12, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ModbusRegisterDecoder.decodeBits(0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MRD-13 字序解析：little 大小写不敏感，未知/空白按 big")
    void wordOrderParsingMustBeLenient() {
        assertThat(ModbusRegisterDecoder.isLittleWordOrder("little")).isTrue();
        assertThat(ModbusRegisterDecoder.isLittleWordOrder("LITTLE")).isTrue();
        assertThat(ModbusRegisterDecoder.isLittleWordOrder(" little ")).isTrue();
        assertThat(ModbusRegisterDecoder.isLittleWordOrder("big")).isFalse();
        assertThat(ModbusRegisterDecoder.isLittleWordOrder("")).isFalse();
        assertThat(ModbusRegisterDecoder.isLittleWordOrder(null)).isFalse();
    }

    @Test
    @DisplayName("MRD-14 脏输入：超出 16 位的入参只取低 16 位（不得产生不确定结果）")
    void outOfRangeInputMustBeMasked() {
        assertThat(ModbusRegisterDecoder.decodeSingle(0x1_2345, false)).isEqualTo(0x2345);
        assertThat(ModbusRegisterDecoder.decodeInt32(0x1_0001, 0x1_0002, "big", false))
                .isEqualTo(0x00010002L);
        assertThat(ModbusRegisterDecoder.decodeBits(0x1_0004, 2, 1)).isEqualTo(Boolean.TRUE);
    }

    /** 把 64 位按「高字在前」拆成 4 个 16 位字。 */
    private static int[] splitWords(long bits) {
        return new int[] {
                (int) ((bits >>> 48) & ModbusRegisterDecoder.WORD_MASK),
                (int) ((bits >>> 32) & ModbusRegisterDecoder.WORD_MASK),
                (int) ((bits >>> 16) & ModbusRegisterDecoder.WORD_MASK),
                (int) (bits & ModbusRegisterDecoder.WORD_MASK)
        };
    }

    /** 把 UTF-8 文本按「每寄存器 2 字节、高字节在前」装成字序列（尾部不足补 0x00）。 */
    private static int[] toWords(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        int[] words = new int[(bytes.length + 1) / 2];
        for (int index = 0; index < words.length; index++) {
            int high = bytes[index * 2] & 0xFF;
            int low = index * 2 + 1 < bytes.length ? bytes[index * 2 + 1] & 0xFF : 0;
            words[index] = (high << 8) | low;
        }
        return words;
    }
}
