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

/**
 * 宿主<b>可插字节载荷解码 SPI</b>：把协议模块交付的原始 {@code byte[]} 按设备/点位上下文解成
 * 「平台规范值」或「显式失败原因」。
 *
 * <p><b>为什么需要它（缺口登记）</b>：本仓的二进制交付路径（MQTT {@code payload-format=binary}、
 * TCP {@code payload-format=binary}、OPC UA {@code ByteString} 节点）只负责「原样交付字节」——
 * 这是刻意的：私有帧格式属于业务协议，框架不猜。但此前框架<b>没有</b>统一的解码扩展点，宿主只能
 * 各自造一套 SPI + 注册机（issue #30）；换个宿主就要重写一遍，且「没认领就丢弃」的失败信号
 * 也各写各的。本 SPI 把这一层收进契约层（{@code iot-core} 零 Spring，宿主实现无框架依赖）。</p>
 *
 * <p><b>契约（实现者必读）</b>：</p>
 * <ol>
 *   <li>{@link #supports(DecodeContext)} 只回答「本解码器是否认领这个点位」；<b>不得</b>在这里做
 *       实际解码，也不得抛异常（它是每个点位都会走的判定入口）；</li>
 *   <li>{@link #decode(byte[], DecodeContext)} 只做「字节 → 规范值」的纯解码：产出物是平台规范
 *       形态（数值/文本/布尔），<b>不负责落列、不做缩放、不做物模型类型转换</b>（那些属于宿主业务）；
 *       失败必须返回 {@link DecodeOutcome#failed(DecodeFailure)}，<b>不得</b>返回 {@code null} 充数；</li>
 *   <li>实现必须线程安全（协议回调可能在多线程上并发进入）；</li>
 *   <li><b>未注册任何解码器时行为与没有本 SPI 时逐字一致</b>：{@code byte[]} 原样交付宿主，
 *       框架不猜、不静默改写；</li>
 *   <li>注册方式：宿主声明 {@code @Bean BytePayloadDecoder} 即可，由
 *       {@link BytePayloadDecoderRegistry} 统一收集与选择，无需改动接入层代码。</li>
 * </ol>
 *
 * <p><b>与协议层解码的分工</b>：协议模块已声明的文本/数值解码（如 MQTT {@code payload-format=text}）
 * 仍由协议模块完成，本 SPI <b>只处理交付给宿主的 {@code byte[]}</b>，两者不重叠。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
public interface BytePayloadDecoder {

    /**
     * 稳定标识：用于日志、排障与同序解码器的确定性排序。
     *
     * <p>取值应稳定且有界（如 {@code mqtt-binary-number}），<b>不得</b>包含设备 ID 等高频变化内容。</p>
     *
     * @return 解码器标识
     */
    String id();

    /**
     * 本解码器是否认领该点位。
     *
     * <p>只做判定，不做解码；必须非阻塞、非 IO。</p>
     *
     * @param context 点位/设备上下文（非空）
     * @return 认领返回 {@code true}
     */
    boolean supports(DecodeContext context);

    /**
     * 解码一个原始字节载荷。
     *
     * @param payload 原始字节（协议模块交付；非空）
     * @param context 点位/设备上下文（非空）
     * @return 解码结果（成功带规范值，失败带显式原因；绝不返回 {@code null}）
     */
    DecodeOutcome decode(byte[] payload, DecodeContext context);
}
