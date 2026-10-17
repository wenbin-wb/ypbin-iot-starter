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

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * Modbus 寄存器<b>纯词法解码</b>：把协议层交付的 16 位无符号寄存器值按位宽 / 符号 / 字序组合成
 * 平台规范值。
 *
 * <p><b>为什么放在协议模块而不是宿主</b>：本类只做算术（掩码 / 移位 / two's complement /
 * IEEE754 位模式 / 严格 UTF-8），<b>零 IO、零外部依赖、无状态</b>，是 Modbus 接入基座应当提供的
 * 协议原语；把它留在宿主会让每个接入 Modbus 的工程各抄一遍，且抄错时（字序、符号扩展）不会
 * 有任何编译期或运行期告警（issue #31）。</p>
 *
 * <p><b>输入口径（冻结）</b>：{@code ModbusSession} 对每个订阅地址交付一个 16 位
 * <b>无符号大端</b>寄存器值（{@code Integer} {@code 0..65535}）。本类所有寄存器形参只取
 * <b>低 16 位</b>（超出范围的高位被掩掉，不抛异常——解码位于协议热路径，协议层已保证取值域；
 * 显式掩码是为了让「脏输入」不产生不确定结果）。</p>
 *
 * <p><b>字序（word order）定义</b>（与 {@code docs/SPI.md} 及宿主历史实现一致）：</p>
 * <ul>
 *   <li>{@code big}（或 {@code null}/其它值）：高字在前 —— 32 位 ABCD、64 位 ABCD；</li>
 *   <li>{@code little}：低字在前 —— 32 位 CDAB、64 位 DCBA（<b>整体翻转</b>）。</li>
 * </ul>
 * <p><b>不支持</b> BADC / CDAB 之类「字内字节交换」的中间排列：那需要额外的字节序维度，
 * 属于设备方言，由宿主按点位单独声明（见 {@code docs/PROTOCOLS.md} 的兼容矩阵）。</p>
 *
 * <p><b>失败语义</b>：本类不做 IO，也不决定点位质量。非法输入（长度不符、位域越界）以
 * {@link IllegalArgumentException} 暴露（调用方据此判 BAD / 配置错误）；NaN / ±Inf 的处置
 * 也<b>不在本类</b>：本类只如实返回位模式对应的浮点，是否落库由调用方按业务决定。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
public final class ModbusRegisterDecoder {

    /** 单寄存器位宽。 */
    public static final int WORD_BITS = 16;

    /** 单寄存器掩码（{@code 0..65535}）。 */
    public static final int WORD_MASK = 0xFFFF;

    /** 双寄存器（32 位）的寄存器个数。 */
    public static final int INT32_WORDS = 2;

    /** 单精度浮点（32 位）的寄存器个数。 */
    public static final int FLOAT_WORDS = 2;

    /** 双精度浮点（64 位）的寄存器个数。 */
    public static final int DOUBLE_WORDS = 4;

    /** 字序：高字在前（32 位 ABCD / 64 位 ABCD）。 */
    public static final String WORD_ORDER_BIG = "big";

    /** 字序：低字在前（32 位 CDAB / 64 位 DCBA 整体翻转）。 */
    public static final String WORD_ORDER_LITTLE = "little";

    /** 位域起始位下限。 */
    private static final int BIT_OFFSET_MIN = 0;

    /** 位域起始位上限（16 位寄存器的最高位下标）。 */
    private static final int BIT_OFFSET_MAX = WORD_BITS - 1;

    private static final int BYTE_MASK = 0xFF;

    private static final int BYTE_BITS = 8;

    private ModbusRegisterDecoder() {
    }

    /**
     * 单寄存器（16 位）解码。
     *
     * @param raw    16 位无符号寄存器值（只取低 16 位）
     * @param signed 是否有符号（{@code true} 做 16 位 two's complement 符号扩展）
     * @return 无符号时 {@code 0..65535}；有符号时 {@code -32768..32767}
     */
    public static int decodeSingle(int raw, boolean signed) {
        int word = raw & WORD_MASK;
        return signed ? (short) word : word;
    }

    /**
     * 双寄存器（32 位）整型解码。
     *
     * @param first     基址寄存器值（只取低 16 位）
     * @param second    {@code base+1} 寄存器值（只取低 16 位）
     * @param byteOrder 字序：{@link #WORD_ORDER_LITTLE} 为低字在前（CDAB）；其它（含 {@code null}）
     *                  为高字在前（ABCD）
     * @param signed    是否有符号（{@code true} 做 32 位 two's complement 符号扩展）
     * @return 无符号时 {@code 0..4294967295}（{@code long} 承载，不截断）；有符号时
     *         {@code -2147483648..2147483647}
     */
    public static long decodeInt32(int first, int second, @Nullable String byteOrder, boolean signed) {
        long raw32 = assembleRaw32(first, second, byteOrder);
        return signed ? (int) raw32 : raw32;
    }

    /**
     * IEEE754 单精度浮点（32 位 ⟺ 2 寄存器）解码。
     *
     * @param first     基址寄存器值（只取低 16 位）
     * @param second    {@code base+1} 寄存器值（只取低 16 位）
     * @param byteOrder 字序：{@link #WORD_ORDER_LITTLE} 为低字在前；其它（含 {@code null}）为高字在前
     * @return 该 32 位位模式对应的单精度浮点（NaN / ±Inf 原样返回，是否拒绝由调用方决定）
     */
    public static float decodeFloatBits(int first, int second, @Nullable String byteOrder) {
        return Float.intBitsToFloat((int) assembleRaw32(first, second, byteOrder));
    }

    /**
     * IEEE754 双精度浮点（64 位 ⟺ 4 寄存器）解码。
     *
     * @param words     4 个 16 位无符号寄存器值（按 {@code word_index} 升序；只取各自低 16 位）
     * @param byteOrder 字序：{@link #WORD_ORDER_LITTLE} 为低字在前（DCBA 整体翻转）；
     *                  其它（含 {@code null}）为高字在前（ABCD）
     * @return 该 64 位位模式对应的双精度浮点（NaN / ±Inf 原样返回）
     * @throws IllegalArgumentException {@code words} 长度不为 4
     */
    public static double decodeDoubleBits(int[] words, @Nullable String byteOrder) {
        return Double.longBitsToDouble(assembleRaw64(words, byteOrder));
    }

    /**
     * 多寄存器 UTF-8 字符串解码。
     *
     * <p><b>口径（冻结）</b>：</p>
     * <ol>
     *   <li>按寄存器顺序、每寄存器<b>高字节在前</b>（Modbus 本体序）拼字节：
     *       {@code bytes[2i]=(word>>8)&0xFF}、{@code bytes[2i+1]=word&0xFF}；</li>
     *   <li>去掉<b>尾部连续 0x00</b>（设备常用 NUL 补齐定长字段）；不去空格、中间 0x00 保留；</li>
     *   <li>严格 UTF-8（{@link CodingErrorAction#REPORT}）：非法字节序列抛
     *       {@link CharacterCodingException}，调用方据此判点位 BAD（不替换字符、不静默）。</li>
     * </ol>
     *
     * <p>全 NUL ⇒ 去尾后为空字节串 ⇒ 返回空字符串（合法）。{@code byte_order} 对字符串
     * <b>不参与</b>（字符串没有「字」的数值语义），故本方法不接收该参数。</p>
     *
     * @param words 16 位无符号寄存器值（按 {@code word_index} 升序；只取各自低 16 位）
     * @return 严格 UTF-8 解码结果（可为空串）
     * @throws CharacterCodingException 字节串不是合法 UTF-8
     */
    public static String decodeStringUtf8(int[] words) throws CharacterCodingException {
        byte[] bytes = new byte[words.length * (WORD_BITS / BYTE_BITS)];
        for (int index = 0; index < words.length; index++) {
            int word = words[index] & WORD_MASK;
            bytes[index * 2] = (byte) ((word >>> BYTE_BITS) & BYTE_MASK);
            bytes[index * 2 + 1] = (byte) (word & BYTE_MASK);
        }
        int end = bytes.length;
        while (end > 0 && bytes[end - 1] == 0) {
            end--;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return decoder.decode(ByteBuffer.wrap(bytes, 0, end)).toString();
    }

    /**
     * 单寄存器位域（无符号）解码。
     *
     * @param raw       16 位无符号寄存器值（只取低 16 位）
     * @param bitOffset 起始位（{@code 0..15}）
     * @param bitLength 位宽（{@code 1..16-bitOffset}）
     * @return {@code bitLength=1} 返回 {@link Boolean}；{@code >1} 返回无符号 {@link Integer}
     * @throws IllegalArgumentException bit 区间越界
     */
    public static Object decodeBits(int raw, int bitOffset, int bitLength) {
        if (bitOffset < BIT_OFFSET_MIN || bitOffset > BIT_OFFSET_MAX
                || bitLength < 1 || bitOffset + bitLength > WORD_BITS) {
            throw new IllegalArgumentException(
                    "bit range out of bounds: offset=" + bitOffset + ", length=" + bitLength);
        }
        long field = ((raw & WORD_MASK) >>> bitOffset) & ((1L << bitLength) - 1);
        return bitLength == 1 ? Boolean.valueOf(field != 0) : Integer.valueOf((int) field);
    }

    /**
     * 是否「低字在前」。
     *
     * <p>只认 {@link #WORD_ORDER_LITTLE}（大小写不敏感、忽略首尾空白）；{@code null} / 未知值
     * 一律按高字在前处理 —— 与宿主历史实现保持一致（未知字序不改变默认语义，但配置侧应显式声明）。</p>
     *
     * @param byteOrder 字序声明
     * @return 低字在前返回 {@code true}
     */
    public static boolean isLittleWordOrder(@Nullable String byteOrder) {
        return byteOrder != null && WORD_ORDER_LITTLE.equalsIgnoreCase(byteOrder.trim());
    }

    /** 按字序组装 32 位原始字（big=高字在前 / little=低字在前）。 */
    private static long assembleRaw32(int first, int second, @Nullable String byteOrder) {
        long high = first & WORD_MASK;
        long low = second & WORD_MASK;
        return isLittleWordOrder(byteOrder) ? ((low << WORD_BITS) | high) : ((high << WORD_BITS) | low);
    }

    /**
     * 按字序组装 64 位原始字：big=ABCD（{@code words[0]} 为高 16 位）；little=DCBA（整体翻转，
     * 即 {@code words[3]} 为高 16 位）。不支持 BADC/CDAB 字内字节交换。
     */
    private static long assembleRaw64(int[] words, @Nullable String byteOrder) {
        if (words.length != DOUBLE_WORDS) {
            throw new IllegalArgumentException(
                    "double precision requires exactly " + DOUBLE_WORDS + " registers: actual="
                            + words.length);
        }
        long value = 0L;
        if (isLittleWordOrder(byteOrder)) {
            for (int index = words.length - 1; index >= 0; index--) {
                value = (value << WORD_BITS) | (words[index] & WORD_MASK);
            }
        } else {
            for (int word : words) {
                value = (value << WORD_BITS) | (word & WORD_MASK);
            }
        }
        return value;
    }
}
