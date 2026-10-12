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

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link TcpPayloadFormat#KEY_VALUE} 的帧内字段选择器。
 *
 * <p><b>语义</b>：每个 {@link PointAddress#raw()} 被解释为帧内的字段键（selector），
 * 同一帧因此可服务 0..N 个点位。帧格式为 {@code KEY=VALUE} 字段，字段之间由
 * {@code ,}、{@code ;}、{@code \r} 或 {@code \n} 分隔；键值之间由第一个 {@code =} 分隔。</p>
 *
 * <p><b>匹配规则</b>：selector 与帧内键都做首尾去空白 + {@link Locale#ROOT} 小写归一化，
 * 因此匹配大小写不敏感；重复键以<b>先出现者</b>为准（取值确定，不受字段顺序抖动影响）。
 * selector 本身不得包含字段分隔符、{@code =} 或换行符，否则在订阅期以
 * {@link AddressParseException} <b>fail-fast</b>。</p>
 *
 * <p><b>失败显式可见</b>：整帧非 UTF-8、空帧、没有任何键值字段时，所有点位产出 BAD；
 * selector 在帧中找不到时，只让该点位产出 BAD（{@link TcpAdapter#MSG_SELECTOR_NOT_FOUND}），
 * 同帧其它点位照常交付；字段值为空白时产出 BAD（{@link TcpAdapter#MSG_SELECTOR_VALUE_EMPTY}）。
 * 本类不做数据类型转换，帧里的值以 {@code String} 交付——类型属于宿主物模型语义。</p>
 *
 * @author wenbin
 * @since 2026-10-12
 */
final class TcpValueSelector {

    private static final char FIELD_SEPARATOR_COMMA = ',';

    private static final char FIELD_SEPARATOR_SEMICOLON = ';';

    private static final char KEY_VALUE_SEPARATOR = '=';

    private TcpValueSelector() {
        // 工具类
    }

    /**
     * 从一帧中为每个地址选择字段值。
     *
     * @param addresses 订阅地址（在 {@code KEY_VALUE} 格式下即 selector 列表）
     * @param payload   帧字节
     * @param timestamp 统一时间戳
     * @return 与 {@code addresses} 等长的点位值列表，失败点位以 BAD 质量显式表达
     */
    static List<PointValue> select(List<PointAddress> addresses, byte[] payload, Instant timestamp) {
        if (payload.length == 0) {
            return badForAll(addresses, TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE, timestamp);
        }
        if (!TcpPayloadFormat.isValidUtf8(payload)) {
            return badForAll(addresses, TcpAdapter.MSG_PAYLOAD_NOT_UTF8, timestamp);
        }
        Map<String, String> fields = parseFields(new String(payload, StandardCharsets.UTF_8));
        if (fields.isEmpty()) {
            return badForAll(addresses, TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE, timestamp);
        }
        List<PointValue> values = new ArrayList<>(addresses.size());
        for (PointAddress address : addresses) {
            String valueText = fields.get(normalizeSelector(address.raw()));
            if (valueText == null) {
                values.add(PointValue.bad(address, Quality.BAD, TcpAdapter.MSG_SELECTOR_NOT_FOUND, timestamp));
            } else if (valueText.isBlank()) {
                values.add(PointValue.bad(address, Quality.BAD, TcpAdapter.MSG_SELECTOR_VALUE_EMPTY, timestamp));
            } else {
                values.add(PointValue.good(address, valueText, timestamp));
            }
        }
        return values;
    }

    /**
     * 校验 selector 语法。订阅期调用，非法即 fail-fast。
     *
     * @param address 点位地址
     * @throws AddressParseException selector 包含 {@code =}、字段分隔符或换行符时
     */
    static void validateSelector(PointAddress address) {
        String selector = address.raw().trim();
        for (int index = 0; index < selector.length(); index++) {
            char ch = selector.charAt(index);
            if (ch == KEY_VALUE_SEPARATOR || ch == FIELD_SEPARATOR_COMMA
                    || ch == FIELD_SEPARATOR_SEMICOLON || ch == '\r' || ch == '\n') {
                throw new AddressParseException(TcpAdapter.PROTOCOL_CODE, address.raw(),
                        "selector must not contain '=', ',', ';' or line break");
            }
        }
    }

    /**
     * 解析键值帧为字段表。
     *
     * @param text 严格 UTF-8 解码后的帧文本
     * @return 键已归一化的字段表；没有任何合法字段时返回空表
     */
    static Map<String, String> parseFields(String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        int index = 0;
        while (index < text.length()) {
            int end = index;
            while (end < text.length() && !isFieldSeparator(text.charAt(end))) {
                end++;
            }
            String field = text.substring(index, end).trim();
            index = end + 1;
            int separator = field.indexOf(KEY_VALUE_SEPARATOR);
            if (separator <= 0) {
                // 无 '='（或键为空）的字段不是键值字段：跳过
                continue;
            }
            String key = normalizeSelector(field.substring(0, separator));
            // 先出现者生效：字段顺序抖动不得改变取值
            fields.putIfAbsent(key, field.substring(separator + 1).trim());
        }
        return fields;
    }

    /**
     * selector / 帧内键归一化：首尾去空白 + {@link Locale#ROOT} 小写。
     *
     * @param selector 原始 selector
     * @return 归一化 selector
     */
    static String normalizeSelector(String selector) {
        return selector.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean isFieldSeparator(char ch) {
        return ch == FIELD_SEPARATOR_COMMA || ch == FIELD_SEPARATOR_SEMICOLON
                || ch == '\r' || ch == '\n';
    }

    private static List<PointValue> badForAll(List<PointAddress> addresses, String reason, Instant timestamp) {
        List<PointValue> values = new ArrayList<>(addresses.size());
        for (PointAddress address : addresses) {
            values.add(PointValue.bad(address, Quality.BAD, reason, timestamp));
        }
        return values;
    }
}
