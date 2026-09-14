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

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Modbus 地址解析。
 *
 * <p><b>两种写法，语义固定、不做猜测</b>：</p>
 *
 * <table border="1">
 *   <caption>支持的地址写法</caption>
 *   <tr><th>写法</th><th>含义</th><th>说明</th></tr>
 *   <tr><td>{@code holding:0}</td><td>保持寄存器，偏移 0</td><td><b>推荐写法</b>：类型显式 + 偏移 0-based，无歧义</td></tr>
 *   <tr><td>{@code coil:5}</td><td>线圈，偏移 5</td><td>同上</td></tr>
 *   <tr><td>{@code input:3}</td><td>输入寄存器，偏移 3</td><td>同上</td></tr>
 *   <tr><td>{@code discrete:2}</td><td>离散输入，偏移 2</td><td>同上</td></tr>
 *   <tr><td>{@code 40001}</td><td>保持寄存器，偏移 0</td><td>传统 5 位编号（4xxxx 段）</td></tr>
 *   <tr><td>{@code 30001}</td><td>输入寄存器，偏移 0</td><td>传统编号（3xxxx 段）</td></tr>
 *   <tr><td>{@code 10001}</td><td>离散输入，偏移 0</td><td>传统编号（1xxxx 段）</td></tr>
 *   <tr><td>{@code 00001}</td><td>线圈，偏移 0</td><td>传统编号（0xxxx 段）</td></tr>
 * </table>
 *
 * <p><b>为什么不做「自动猜测」</b>：各厂商文档对同一寄存器的写法从 {@code 40001} 到 {@code 0}
 * 到 {@code holding:1} 不等，{@code 1}-based 与 {@code 0}-based 的混用是 Modbus 现场最常见的错误来源。
 * 猜测一旦猜错，表现是「读到了值但值不对（整体偏移一位）」，比直接报错难排查得多。
 * 因此本实现只支持上表两种<b>语义固定</b>的写法，其余一律抛
 * {@link AddressParseException} 并说明可用写法。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class ModbusAddressCodec {

    /** 协议标识：与适配器共用同一常量，避免两处维护漂移（测试已捕获过一次）。 */
    private static final ProtocolCode PROTOCOL = ModbusAdapter.PROTOCOL_CODE;

    /** 传统 5 位编号区段基址。 */
    private static final int COIL_BASE = 1;

    private static final int DISCRETE_BASE = 10001;

    private static final int INPUT_BASE = 30001;

    private static final int HOLDING_BASE = 40001;

    /** 传统编号的 5 位段上界。 */
    private static final int TRADITIONAL_MIN = 1;

    private static final int TRADITIONAL_MAX = 49999;

    /** {@code type:offset} 显式写法。 */
    private static final Pattern EXPLICIT = Pattern.compile("^([a-zA-Z]+)\\s*:\\s*(\\d{1,5})$");

    /** 纯数字的传统编号。 */
    private static final Pattern NUMERIC = Pattern.compile("^\\d{1,5}$");

    private ModbusAddressCodec() {
    }

    /**
     * 解析地址。
     *
     * @param raw 原始地址字符串
     * @return 解析后的地址
     * @throws AddressParseException 当写法不受支持或偏移越界时
     */
    static ModbusAddress parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new AddressParseException(PROTOCOL, raw, "address must not be blank");
        }
        String text = raw.trim();
        Matcher explicit = EXPLICIT.matcher(text);
        if (explicit.matches()) {
            ModbusRegisterType type = parseType(explicit.group(1), raw);
            return new ModbusAddress(type, parseOffset(explicit.group(2), raw), raw);
        }
        if (NUMERIC.matcher(text).matches()) {
            return parseTraditional(text, raw);
        }
        throw new AddressParseException(PROTOCOL, raw,
                "unsupported address form; use '<type>:<offset>' (e.g. holding:0) "
                        + "or traditional 5-digit notation (e.g. 40001)");
    }

    /**
     * 把地址格式化为规范写法（{@code type:offset}）。
     *
     * @param address 地址
     * @return 规范写法
     */
    static String format(ModbusAddress address) {
        return address.type().getCode() + ":" + address.offset();
    }

    private static ModbusRegisterType parseType(String text, String raw) {
        Optional<ModbusRegisterType> type = ModbusRegisterType.fromCode(text.toLowerCase(Locale.ROOT));
        if (type.isEmpty()) {
            throw new AddressParseException(PROTOCOL, raw,
                    "unknown register type '" + text + "'; expected one of "
                            + ModbusRegisterType.expectedCodes());
        }
        return type.get();
    }

    private static int parseOffset(String digits, String raw) {
        int offset;
        try {
            offset = Integer.parseInt(digits);
        } catch (NumberFormatException ex) {
            throw new AddressParseException(PROTOCOL, raw, "offset is not a number: " + digits);
        }
        if (offset < 0 || offset > ModbusRegisterType.MAX_OFFSET) {
            throw new AddressParseException(PROTOCOL, raw,
                    "offset out of range [0, " + ModbusRegisterType.MAX_OFFSET + "]: " + offset);
        }
        return offset;
    }

    private static ModbusAddress parseTraditional(String text, String raw) {
        int value = Integer.parseInt(text);
        if (value < TRADITIONAL_MIN || value > TRADITIONAL_MAX) {
            throw new AddressParseException(PROTOCOL, raw,
                    "traditional address out of range [1, " + TRADITIONAL_MAX + "]: " + value);
        }
        if (value >= HOLDING_BASE) {
            return new ModbusAddress(ModbusRegisterType.HOLDING_REGISTER, value - HOLDING_BASE, raw);
        }
        if (value >= INPUT_BASE) {
            return new ModbusAddress(ModbusRegisterType.INPUT_REGISTER, value - INPUT_BASE, raw);
        }
        if (value >= DISCRETE_BASE) {
            return new ModbusAddress(ModbusRegisterType.DISCRETE_INPUT, value - DISCRETE_BASE, raw);
        }
        // 0xxxx 段（含补零写法的 00001）按线圈处理，且该段是 1-based
        return new ModbusAddress(ModbusRegisterType.COIL, value - COIL_BASE, raw);
    }
}
