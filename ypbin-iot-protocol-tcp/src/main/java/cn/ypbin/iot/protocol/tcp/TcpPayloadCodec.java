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

import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * TCP 透传的载荷编码：把写项的值转为待发送字节。
 *
 * <p>支持的 value 类型：</p>
 * <ul>
 *   <li>{@code byte[]} —— 原样发送；</li>
 *   <li>{@code String} —— 按 UTF-8 发送；</li>
 *   <li>{@code String} 且以 {@code hex:} 开头 —— 按十六进制解析（允许空格分隔）。</li>
 * </ul>
 *
 * <p><b>不做任何隐式转换</b>：数字类型不会被「猜测字节序」后发送——工业透传场景下
 * 隐式字节序转换是灾难性设计。类型不支持时返回失败状态并给出消息键。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class TcpPayloadCodec {

    /** 十六进制字面量前缀。 */
    static final String HEX_PREFIX = "hex:";

    /** 载荷类型不支持的消息键。 */
    static final String MSG_PAYLOAD_UNSUPPORTED = "iot.tcp.payload.unsupported";

    /** 十六进制格式非法的消息键。 */
    static final String MSG_HEX_INVALID = "iot.tcp.payload.hex-invalid";

    private TcpPayloadCodec() {
    }

    /**
     * 编码结果：成功携带字节，失败携带消息键。
     *
     * @param payload    编码后的字节；失败时为 {@code null}
     * @param messageKey 失败消息键；成功时为 {@code null}
     * @author wenbin
     * @since 2026-09-13
     */
    // 注意注解位置：`@Nullable byte[]` 注解的是**元素类型**（「元素可空的非空数组」），
    // 数组本身可空必须写 `byte @Nullable []`（母仓教训十一）。
    record Encoded(byte @Nullable [] payload, @Nullable String messageKey) {

        /**
         * 编码成功。
         *
         * @param payload 字节
         * @return 结果
         */
        static Encoded ok(byte[] payload) {
            return new Encoded(payload, null);
        }

        /**
         * 编码失败。
         *
         * @param messageKey 消息键
         * @return 结果
         */
        static Encoded failure(String messageKey) {
            return new Encoded(null, messageKey);
        }

        /**
         * 是否成功。
         *
         * @return 成功返回 {@code true}
         */
        boolean success() {
            return messageKey == null;
        }
    }

    /**
     * 把任意值编码为字节。
     *
     * @param value 待编码的值
     * @return 编码结果
     */
    static Encoded encode(Object value) {
        if (value instanceof byte[] bytes) {
            return Encoded.ok(bytes);
        }
        if (value instanceof String text) {
            if (text.startsWith(HEX_PREFIX)) {
                return decodeHex(text.substring(HEX_PREFIX.length()));
            }
            return Encoded.ok(text.getBytes(StandardCharsets.UTF_8));
        }
        return Encoded.failure(MSG_PAYLOAD_UNSUPPORTED);
    }

    /**
     * 解析十六进制字符串。
     *
     * @param hex 十六进制内容（允许空格分隔）
     * @return 编码结果
     */
    static Encoded decodeHex(String hex) {
        String normalized = hex.replace(" ", "");
        if (normalized.isEmpty() || normalized.length() % 2 != 0) {
            return Encoded.failure(MSG_HEX_INVALID);
        }
        byte[] result = new byte[normalized.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(normalized.charAt(i * 2), 16);
            int low = Character.digit(normalized.charAt(i * 2 + 1), 16);
            if (high < 0 || low < 0) {
                return Encoded.failure(MSG_HEX_INVALID);
            }
            result[i] = (byte) ((high << 4) | low);
        }
        return Encoded.ok(result);
    }
}
