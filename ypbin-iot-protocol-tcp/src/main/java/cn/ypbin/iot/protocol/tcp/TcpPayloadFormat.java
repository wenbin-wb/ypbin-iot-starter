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
import java.util.Locale;

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
 * @author wenbin
 * @since 2026-09-28
 */
public enum TcpPayloadFormat {

    /** 文本：严格 UTF-8 解码为 {@code String}；<b>非 UTF-8 帧产出 BAD 值而不是损坏的字符串</b>。 */
    TEXT(0, "文本（UTF-8）"),

    /** 数值：解析为 {@code Double}；无法解析（或非 UTF-8）时产出 BAD 值。 */
    NUMBER(1, "数值"),

    /** 二进制：原样交付 {@code byte[]}，不做任何解码（默认，向后兼容）。 */
    BINARY(2, "二进制");

    private final int code;

    private final String desc;

    TcpPayloadFormat(int code, String desc) {
        this.code = code;
        this.desc = desc;
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

    /**
     * 按编码反查格式。
     *
     * @param code 编码
     * @return 格式；未知编码返回 {@code null}
     */
    static TcpPayloadFormat fromCode(int code) {
        for (TcpPayloadFormat format : values()) {
            if (format.code == code) {
                return format;
            }
        }
        return null;
    }

    /**
     * 解析大小写不敏感的名称。
     *
     * @param name 名称
     * @return 格式；未知名称返回 {@code null}
     */
    static TcpPayloadFormat fromName(String name) {
        if (name == null) {
            return null;
        }
        return valueOfSafe(name.toUpperCase(Locale.ROOT));
    }

    private static TcpPayloadFormat valueOfSafe(String upper) {
        for (TcpPayloadFormat format : values()) {
            if (format.name().equals(upper)) {
                return format;
            }
        }
        return null;
    }
}
