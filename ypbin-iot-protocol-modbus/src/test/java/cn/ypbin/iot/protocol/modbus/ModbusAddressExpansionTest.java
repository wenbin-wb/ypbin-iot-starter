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

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.protocol.AddressExpansionExtension;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Modbus 地址算术扩展测试（issue #37 阻塞缺口 1）。
 *
 * <p>多寄存器点位（{@code int}/{@code float}=2、{@code double}=4、{@code string}=1..32）
 * 在订阅时必须展开 {@code base+1 .. base+N-1}，否则解码拿不到完整字节。
 * 本测试把「展开结果」与「边界必须显式失败」都钉成可执行断言：</p>
 *
 * <ul>
 *   <li>正确性：wordCount 语义下的地址序列（2 / 4 / 32 个寄存器）；</li>
 *   <li>写法归一：传统 5 位编号作为基址时输出规范 {@code type:offset}
 *       （传统 {@code 0xxxx} 段是 1-based，直接对字符串 +1 会踩坑）；</li>
 *   <li>边界：上界 65535 越界必须抛异常而不是回绕；{@code delta=0}/负数属非法参数；
 *       {@code offset + delta} 用 int 相加会溢出成负数从而绕过上界判断，因此必须用 long 运算。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class ModbusAddressExpansionTest {

    private final AddressExpansionExtension expansion = ModbusAddressExpansion.INSTANCE;

    @Test
    @DisplayName("MAE-01 协议归属必须为 Modbus（扩展按协议登记，不能被别的协议误用）")
    void protocolMustBeModbus() {
        assertThat(expansion.protocol()).isEqualTo(ModbusAdapter.PROTOCOL_CODE);
    }

    @Test
    @DisplayName("MAE-02 holding:0 + 1 = holding:1（wordCount=2 的 int/float 点位）")
    void singleStepMustProduceSiblingRegister() {
        assertThat(expansion.offset(PointAddress.of("holding:0"), 1).raw()).isEqualTo("holding:1");
        assertThat(expansion.offset(PointAddress.of("holding:100"), 1).raw()).isEqualTo("holding:101");
    }

    @Test
    @DisplayName("MAE-03 wordCount=4（double）必须能连续偏移到 base+3，且顺序与类型保持不变")
    void fourRegistersMustExpandInOrder() {
        PointAddress base = PointAddress.of("holding:10");
        List<String> expanded = new ArrayList<>();
        expanded.add(base.raw());
        for (int delta = 1; delta < 4; delta++) {
            expanded.add(expansion.offset(base, delta).raw());
        }
        assertThat(expanded).containsExactly("holding:10", "holding:11", "holding:12", "holding:13");
    }

    @Test
    @DisplayName("MAE-04 wordCount=32（string 上限）必须能展开到 base+31")
    void thirtyTwoRegistersMustExpandToLastSibling() {
        assertThat(expansion.offset(PointAddress.of("holding:0"), 31).raw()).isEqualTo("holding:31");
        assertThat(expansion.offset(PointAddress.of("holding:65000"), 31).raw())
                .isEqualTo("holding:65031");
    }

    @Test
    @DisplayName("MAE-05 寄存器区类型必须原样保留（input 不会被写成 holding）")
    void registerTypeMustBePreserved() {
        assertThat(expansion.offset(PointAddress.of("input:3"), 2).raw()).isEqualTo("input:5");
        assertThat(expansion.offset(PointAddress.of("coil:0"), 1).raw()).isEqualTo("coil:1");
        assertThat(expansion.offset(PointAddress.of("discrete:7"), 3).raw()).isEqualTo("discrete:10");
    }

    @Test
    @DisplayName("MAE-06 传统 5 位编号作基址必须归一为规范写法（40001 → holding:0，+1 → holding:1）")
    void traditionalNotationMustNormalizeToExplicitForm() {
        // 40001 在传统段里表示「保持寄存器偏移 0」；下一寄存器是偏移 1。
        // 若直接对字符串做「40001 + 1 = 40002」虽然结果也对，但对 00001（1-based 段）
        // 就会错位——因此统一走解析 → 偏移 → 规范格式化 这一条路。
        assertThat(expansion.offset(PointAddress.of("40001"), 1).raw()).isEqualTo("holding:1");
        assertThat(expansion.offset(PointAddress.of("30001"), 2).raw()).isEqualTo("input:2");
        assertThat(expansion.offset(PointAddress.of("10001"), 1).raw()).isEqualTo("discrete:1");
        assertThat(expansion.offset(PointAddress.of("00001"), 1).raw()).isEqualTo("coil:1");
    }

    @Test
    @DisplayName("MAE-07 越界必须显式失败而不是回绕（offset+delta > 65535）")
    void overflowMustFailInsteadOfWrapping() {
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("holding:65535"), 1))
                .as("回绕会把「越界配置」变成「悄悄读到另一个寄存器的值」")
                .isInstanceOf(AddressParseException.class)
                .hasMessageContaining("out of range");
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("holding:65534"), 2))
                .isInstanceOf(AddressParseException.class);
    }

    @Test
    @DisplayName("MAE-08 int 溢出保护：offset 很大 + delta 很大时不得产出负偏移地址")
    void integerOverflowMustNotProduceNegativeOffset() {
        // int 相加会溢出成负数（65500 + 100 不会，但 Integer.MAX_VALUE 附近的 delta 会），
        // 因此实现必须用 long 运算再判上界
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("holding:0"), Integer.MAX_VALUE))
                .as("超大 delta 必须被判为越界，而不是溢出成负偏移")
                .isInstanceOf(AddressParseException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    @DisplayName("MAE-09 delta=0 与负数属非法参数（展开 0 个偏移说明调用方算错了）")
    void nonPositiveDeltaMustFail() {
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("holding:0"), 0))
                .isInstanceOf(AddressParseException.class)
                .hasMessageContaining("delta");
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("holding:0"), -1))
                .isInstanceOf(AddressParseException.class)
                .hasMessageContaining("delta");
    }

    @Test
    @DisplayName("MAE-10 基址不可解析时必须带原始地址失败，不得静默返回基址")
    void unparsableBaseMustFail() {
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("not-an-address"), 1))
                .isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> expansion.offset(PointAddress.of("holding:abc"), 1))
                .isInstanceOf(AddressParseException.class);
    }
}
