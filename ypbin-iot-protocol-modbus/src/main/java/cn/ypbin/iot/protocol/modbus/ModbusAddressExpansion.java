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
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.protocol.AddressExpansionExtension;
import cn.ypbin.iot.core.protocol.ProtocolCode;

/**
 * Modbus 地址算术：给定基址与偏移量，算出同寄存器区内的兄弟地址。
 *
 * <p><b>为什么必须由协议模块提供</b>：多寄存器点位（{@code int}/{@code float}=2、
 * {@code double}=4、{@code string}=1..32）在订阅时必须把 {@code base+1 .. base+N-1}
 * 一起订上，否则解码拿不到完整字节。但「下一个寄存器怎么写字面量」只有本协议知道：
 * {@code holding:0} 的下一个是 {@code holding:1}，传统编号 {@code 40001} 的下一个是
 * {@code 40002}，而 OPC UA 的 {@code ns=2;s=X} 根本没有「+1」语义。
 * 框架若按字符串做算术，一旦偏移错一位，现象是「读到了值但值不对」——比直接报错难排查得多。</p>
 *
 * <p><b>输出形态</b>：统一输出规范写法 {@code type:offset}（与
 * {@link ModbusAddressCodec#format} 同口径），因为展开出来的兄弟地址是<b>框架侧合成</b>的，
 * 不是宿主原始输入，没有「保留原始写法」的语义负担；而 {@code type:offset} 无歧义
 * （传统编号的 {@code 0xxxx} 段是 1-based，把它当基址再 +1 会踩坑）。</p>
 *
 * <p><b>边界行为</b>（全部显式失败，绝不回绕/静默截断）：</p>
 * <ul>
 *   <li>{@code delta < 1}：非法参数——展开 0 个偏移没有意义，说明调用方算错了；</li>
 *   <li>基址不是本协议可解析的写法：抛 {@link AddressParseException}（带原始地址）；</li>
 *   <li>{@code offset + delta} 超过寄存器偏移上界（{@link ModbusRegisterType#MAX_OFFSET}
 *       = 65535）：抛 {@link AddressParseException}，<b>不做取模回绕</b>
 *       ——回绕会把「越界配置」变成「悄悄读到另一个寄存器的值」。</li>
 * </ul>
 *
 * <p>本类<b>无状态</b>（AGENTS I8）：唯一实例可由会话或适配器共享。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
final class ModbusAddressExpansion implements AddressExpansionExtension {

    /** 无状态单例。 */
    static final ModbusAddressExpansion INSTANCE = new ModbusAddressExpansion();

    private ModbusAddressExpansion() {
    }

    /**
     * 本扩展所属协议。
     *
     * @return Modbus 协议标识
     */
    @Override
    public ProtocolCode protocol() {
        return ModbusAdapter.PROTOCOL_CODE;
    }

    /**
     * 在基址上偏移 {@code delta} 个寄存器。
     *
     * @param base  基址（显式 {@code type:offset} 或传统 5 位编号）
     * @param delta 偏移个数，必须 ≥ 1
     * @return 偏移后的地址（规范 {@code type:offset} 写法）
     * @throws AddressParseException {@code delta < 1}、基址不可解析或偏移越界时
     */
    @Override
    public PointAddress offset(PointAddress base, int delta) {
        if (delta < 1) {
            throw new AddressParseException(ModbusAdapter.PROTOCOL_CODE, base.raw(),
                    "register delta must be >= 1 but was " + delta);
        }
        ModbusAddress parsed = ModbusAddressCodec.parse(base.raw());
        // 用 long 做加法：offset 与 delta 都是 int，直接相加在极端值上会溢出成负数，
        // 从而绕过下面的上界判断并产出一个「看起来合法」的负偏移地址
        long target = (long) parsed.offset() + delta;
        if (target > ModbusRegisterType.MAX_OFFSET) {
            throw new AddressParseException(ModbusAdapter.PROTOCOL_CODE, base.raw(),
                    "register offset out of range [0, " + ModbusRegisterType.MAX_OFFSET + "]: "
                            + target);
        }
        return PointAddress.of(ModbusAddressCodec.format(
                new ModbusAddress(parsed.type(), (int) target, base.raw())));
    }
}
