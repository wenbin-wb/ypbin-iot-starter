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
package cn.ypbin.iot.protocol.tcp;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * TCP 透传入站载荷解码格式（{@code ypbin.iot.protocol.tcp.payload-format}）。
 *
 * <p><b>为什么需要显式配置（UP-3）</b>：TCP 透传的帧是<b>不透明字节</b>，协议本身不携带类型信息。
 * 此前的实现把整帧原始 {@code byte[]} 直接作为 {@code PointValue.value} 交付——落库后是
 * {@code [B@<identityHash>} 字面文本，`value_double` 恒为 {@code null}，曲线/聚合/阈值告警全部不可用，
 * 且链路全程报 GOOD（静默语义丢失）。显式配置让「值是什么」由宿主决定，解码失败产出
 * <b>BAD 质量 + 明确消息键</b>，而不是把畸形内容当 GOOD 交付。</p>
 *
 * <p><b>默认值 = {@link #BINARY}</b>：保持向后兼容（不配置时与 0.1.0 行为逐字一致）。</p>
 *
 * <p><b>{@link #KEY_VALUE}</b>：帧是 {@code KEY=VALUE} 键值文本，订阅地址被解释为字段 selector，
 * 同一帧可服务多个点位；语义、失败矩阵与边界见 {@code docs/TCP-VALUE-SELECTOR.md}。</p>
 *
 * @author wenbin
 * @since 2026-09-28
 */
public enum TcpPayloadFormat {

    /** 文本：严格 UTF-8 解码为 {@code String}；<b>非 UTF-8 帧产出 BAD 值而不是损坏的字符串</b>。 */
    TEXT(0, "文本（UTF-8）", "text"),

    /** 数值：解析为 {@code Double}；无法解析（或非 UTF-8）时产出 BAD 值。 */
    NUMBER(1, "数值", "number"),

    /** 二进制：原样交付 {@code byte[]}，不做任何解码（默认，向后兼容）。 */
    BINARY(2, "二进制", "binary"),

    /** 键值文本：按 {@code KEY=VALUE} 拆帧，用订阅地址（{@code PointAddress.raw}）作为 selector 取值。 */
    KEY_VALUE(3, "键值文本（valueSelector）", "key-value");

    private final int code;

    private final String desc;

    private final String variant;

    TcpPayloadFormat(int code, String desc, String variant) {
        this.code = code;
        this.desc = desc;
        this.variant = variant;
    }

    /**
     * 形态变体键：用于向框架声明「本形态下的订阅寻址能力」。
     *
     * <p>与配置项取值同字面量（{@code binary|text|number|key-value}），由
     * {@link TcpAdapter} 在描述符里作为 {@code subscriptionAddressingByVariant} 的键声明。
     * 框架不认识这些字符串的含义，只拿它查表——这让「同一协议在不同形态下能力不同」
     * 能被正确表达，而不必在宿主里按 {@code payload-format} 硬编码分流。</p>
     *
     * @return 稳定的形态键
     */
    public String variant() {
        return variant;
    }

    /**
     * 编码。
     *
     * @return 编码
     */
    public int code() {
        return code;
    }

    /**
     * 描述。
     *
     * @return 描述
     */
    public String desc() {
        return desc;
    }

    /**
     * 校验字节是否为合法 UTF-8。
     *
     * <p>用 {@link CodingErrorAction#REPORT} 的严格解码——非法字节必须<b>显式可见</b>
     * （BAD 值），不允许静默降级成 U+FFFD 替换字符（UP-3 验收 1 的变异点）。</p>
     *
     * @param payload 帧字节
     * @return 合法 UTF-8 返回 {@code true}
     */
    static boolean isValidUtf8(byte[] payload) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(payload));
            return true;
        } catch (CharacterCodingException ex) {
            return false;
        }
    }
}
