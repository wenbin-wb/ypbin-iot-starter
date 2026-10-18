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
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.model.PointAddress;

/**
 * 多寄存器点位的地址展开扩展：按协议自己的地址语法给出「基址 + delta」。
 *
 * <p><b>为什么由协议实现而不是框架猜</b>：一个点位占多个寄存器时（Modbus {@code word_count=2}
 * 乃至 32），订阅不能只订基址，必须把 {@code base+1 .. base+N-1} 一起订上，否则多寄存器解码
 * 拿不到完整字节。但「下一个寄存器怎么写字面量」只有协议自己知道：
 * {@code holding:0} 的下一个是 {@code holding:1}，{@code 40001} 的下一个是 {@code 40002}，
 * OPC UA 的 {@code ns=2;s=X} 则根本不存在「+1」这种语义。框架按字符串做算术必然出错——
 * 一旦偏移错一位，现象是「读到了值但值不对」，比直接报错难排查得多。</p>
 *
 * <p>因此把「地址算术」做成协议扩展：实现方在 {@link ProtocolDescriptor#extensions()} 中声明，
 * 并通过 {@link DeviceSession#unwrap(Class)} 暴露；编排器（{@code SubscriptionOrchestrator}）
 * 只在协议声明了本扩展时才做展开，否则把「需要展开」显式标记为该点位的失败原因，
 * <b>绝不静默只订基址</b>（那会产出半截字节，质量却是 GOOD）。</p>
 *
 * <p><b>合法输入</b>：{@code base} 必须是本协议可解析的地址；{@code delta} ≥ 1。
 * 越界（如 Modbus 偏移超过 65535）必须抛 {@link AddressParseException}，不得返回越界地址。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public interface AddressExpansionExtension extends ProtocolExtension {

    /**
     * 在基址上偏移 {@code delta} 个寻址单元后的地址。
     *
     * @param base  基址（非空，必须本协议可解析）
     * @param delta 偏移个数，≥ 1
     * @return 偏移后的地址（保留协议自己的表达形式）
     * @throws AddressParseException 基址不可解析或偏移越界时
     */
    PointAddress offset(PointAddress base, int delta);
}
