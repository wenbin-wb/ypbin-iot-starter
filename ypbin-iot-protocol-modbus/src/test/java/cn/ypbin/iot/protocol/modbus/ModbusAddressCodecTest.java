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
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.ypbin.iot.core.exception.AddressParseException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Modbus 地址解析测试。
 *
 * <p>重点：两种写法语义固定、不做猜测；非法写法必须 fail-fast 而不是给出一个"看似合理"的偏移。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class ModbusAddressCodecTest {

    @Test
    @DisplayName("ADDR-01 显式写法 type:offset 的偏移按字面量取（0-based）")
    void explicitFormMustUseLiteralOffset() {
        assertThat(ModbusAddressCodec.parse("holding:0"))
                .isEqualTo(new ModbusAddress(ModbusRegisterType.HOLDING_REGISTER, 0, "holding:0"));
        assertThat(ModbusAddressCodec.parse("coil:5").offset()).isEqualTo(5);
        assertThat(ModbusAddressCodec.parse("input:3").type()).isEqualTo(ModbusRegisterType.INPUT_REGISTER);
        assertThat(ModbusAddressCodec.parse("discrete:2").type()).isEqualTo(ModbusRegisterType.DISCRETE_INPUT);
        assertThat(ModbusAddressCodec.parse(" HOLDING : 7 ").offset()).isEqualTo(7);
    }

    @Test
    @DisplayName("ADDR-02 传统 5 位编号按区段换算为 0-based 偏移")
    void traditionalFormMustMapBySegment() {
        assertThat(ModbusAddressCodec.parse("40001"))
                .isEqualTo(new ModbusAddress(ModbusRegisterType.HOLDING_REGISTER, 0, "40001"));
        assertThat(ModbusAddressCodec.parse("40100").offset()).isEqualTo(99);
        assertThat(ModbusAddressCodec.parse("30001").type()).isEqualTo(ModbusRegisterType.INPUT_REGISTER);
        assertThat(ModbusAddressCodec.parse("30001").offset()).isZero();
        assertThat(ModbusAddressCodec.parse("10001").type()).isEqualTo(ModbusRegisterType.DISCRETE_INPUT);
        // 0xxxx 段是 1-based
        assertThat(ModbusAddressCodec.parse("00001")).isEqualTo(
                new ModbusAddress(ModbusRegisterType.COIL, 0, "00001"));
        assertThat(ModbusAddressCodec.parse("9").offset()).isEqualTo(8);
    }

    @Test
    @DisplayName("ADDR-03 非法写法必须 fail-fast，不得猜测")
    void invalidFormMustFailFast() {
        assertThatThrownBy(() -> ModbusAddressCodec.parse("holding")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse("holding:-1")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse("holding:abc")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse("holding:70000")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse("unknown:1")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse("50000")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse("")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> ModbusAddressCodec.parse(null)).isInstanceOf(AddressParseException.class);
    }

    @Test
    @DisplayName("ADDR-04 异常必须带协议与原始地址，便于定位")
    void exceptionMustCarryContext() {
        AddressParseException ex = catchThrowableOfType(
                () -> ModbusAddressCodec.parse("unknown:1"), AddressParseException.class);
        assertThat(ex.getRawAddress()).isEqualTo("unknown:1");
        assertThat(ex.getProtocol()).isEqualTo(ModbusAdapter.PROTOCOL_CODE);
        assertThat(ex.getMessageKey()).isNotBlank();
    }

    @Test
    @DisplayName("ADDR-05 规范化输出可用于回显")
    void formatMustRoundTrip() {
        String formatted = ModbusAddressCodec.format(ModbusAddressCodec.parse("40001"));
        assertThat(formatted).isEqualTo("holding:0");
        assertThat(ModbusAddressCodec.parse(formatted).offset()).isZero();
    }

    @Test
    @DisplayName("ADDR-06 寄存器区特性必须正确")
    void registerTypeCharacteristicsMustBeCorrect() {
        assertThat(ModbusRegisterType.HOLDING_REGISTER.isWritable()).isTrue();
        assertThat(ModbusRegisterType.COIL.isWritable()).isTrue();
        assertThat(ModbusRegisterType.INPUT_REGISTER.isWritable()).isFalse();
        assertThat(ModbusRegisterType.DISCRETE_INPUT.isWritable()).isFalse();
        assertThat(ModbusRegisterType.COIL.isBitType()).isTrue();
        assertThat(ModbusRegisterType.HOLDING_REGISTER.isBitType()).isFalse();
        assertThat(ModbusRegisterType.COIL.maxQuantity()).isEqualTo(2000);
        assertThat(ModbusRegisterType.HOLDING_REGISTER.maxQuantity()).isEqualTo(125);
        assertThat(ModbusRegisterType.fromCode("HOLDING")).contains(ModbusRegisterType.HOLDING_REGISTER);
        assertThat(ModbusRegisterType.fromCode("nope")).isEmpty();
        assertThat(ModbusRegisterType.expectedCodes()).contains("holding", "coil");
    }

    @Test
    @DisplayName("ADDR-07 同类型且跨度内的地址可合并成一次请求")
    void mergesWithMustRespectSpan() {
        ModbusAddress first = ModbusAddressCodec.parse("holding:0");
        ModbusAddress near = ModbusAddressCodec.parse("holding:10");
        ModbusAddress far = ModbusAddressCodec.parse("holding:200");
        ModbusAddress other = ModbusAddressCodec.parse("coil:1");
        assertThat(first.mergesWith(near, 125)).isTrue();
        assertThat(first.mergesWith(far, 125)).isFalse();
        assertThat(first.mergesWith(other, 125)).isFalse();
        assertThat(first.mergesWith(null, 125)).isFalse();
    }
}
