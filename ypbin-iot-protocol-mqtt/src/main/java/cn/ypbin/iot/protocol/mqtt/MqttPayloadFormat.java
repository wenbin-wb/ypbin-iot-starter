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
package cn.ypbin.iot.protocol.mqtt;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * MQTT 负载解码格式。
 *
 * <p><b>为什么需要显式配置</b>：MQTT 的负载是<b>不透明字节</b>，协议本身不携带类型信息。
 * 只有一个「一律按 UTF-8 解码成字符串」的实现时，二进制负载（CBOR / protobuf /
 * Modbus-over-MQTT）会被<b>静默损坏</b> —— 非 UTF-8 字节变成替换字符 U+FFFD，
 * 而链路全程报成功，宿主拿到的数据已经变形却毫无信号。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
public enum MqttPayloadFormat {

    /** 文本：UTF-8 解码为 {@code String}；<b>非 UTF-8 负载会产出 BAD 值而不是损坏的字符串</b>。 */
    TEXT(0, "文本（UTF-8）"),

    /** 数值：解析为 {@code Double}；无法解析时产出 BAD 值。 */
    NUMBER(1, "数值"),

    /** 二进制：原样交付 {@code byte[]}，不做任何解码。 */
    BINARY(2, "二进制");

    private final int code;

    private final String desc;

    MqttPayloadFormat(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 编码。
     *
     * @return 编码
     */
    public int getCode() {
        return code;
    }

    /**
     * 描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按编码查找。
     *
     * @param code 编码
     * @return 匹配的格式
     */
    public static Optional<MqttPayloadFormat> fromCode(int code) {
        for (MqttPayloadFormat format : values()) {
            if (format.code == code) {
                return Optional.of(format);
            }
        }
        return Optional.empty();
    }

    /**
     * 判断字节序列是否是合法的 UTF-8。
     *
     * <p>{@code new String(bytes, UTF_8)} <b>不会</b>报错，它会把非法字节替换成 U+FFFD ——
     * 这正是「静默损坏」的来源。这里用严格解码器来判断，据此决定是交付字符串还是报 BAD。</p>
     *
     * @param payload 负载字节
     * @return 合法返回 {@code true}
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
