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

import java.util.Arrays;
import org.jspecify.annotations.Nullable;

/**
 * MQTT 主题过滤器匹配。
 *
 * <p>MQTT 的订阅用<b>过滤器</b>（可含通配符），发布用<b>具体主题</b>。两者不是字符串相等关系，
 * 必须按下述规则匹配——用 {@code String.equals} 或 {@code startsWith} 判断是常见错误：
 * 前者会漏掉通配订阅，后者会把 {@code a/bc} 错误地匹配到 {@code a/b} 的前缀。</p>
 *
 * <ul>
 *   <li>{@code +} 匹配<b>恰好一层</b>（{@code a/+/c} 匹配 {@code a/b/c}，不匹配 {@code a/b/d/c}）</li>
 *   <li>{@code #} 匹配<b>剩余所有层</b>（含零层），只能是过滤器的最后一层</li>
 *   <li>通配符必须独占一层（{@code a/b+} 是非法过滤器）</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class MqttTopicMatcher {

    private static final String SINGLE_LEVEL = "+";

    private static final String MULTI_LEVEL = "#";

    private static final String SEPARATOR = "/";

    private MqttTopicMatcher() {
    }

    /**
     * 主题是否匹配过滤器。
     *
     * @param filter 订阅过滤器（可含通配符）
     * @param topic  具体主题
     * @return 匹配返回 {@code true}
     */
    static boolean matches(String filter, String topic) {
        if (filter == null || topic == null) {
            return false;
        }
        String[] filterLevels = filter.split(SEPARATOR, -1);
        String[] topicLevels = topic.split(SEPARATOR, -1);
        int index = 0;
        while (index < filterLevels.length) {
            String level = filterLevels[index];
            if (MULTI_LEVEL.equals(level)) {
                // '#' 必须是最后一层，且匹配剩余全部（含零层）
                return index == filterLevels.length - 1;
            }
            if (index >= topicLevels.length) {
                return false;
            }
            if (!SINGLE_LEVEL.equals(level) && !level.equals(topicLevels[index])) {
                return false;
            }
            index++;
        }
        return index == topicLevels.length;
    }

    /**
     * 过滤器是否合法。
     *
     * @param filter 过滤器
     * @return 合法返回 {@code true}
     */
    static boolean isValidFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return false;
        }
        String[] levels = filter.split(SEPARATOR, -1);
        for (int index = 0; index < levels.length; index++) {
            String level = levels[index];
            if (MULTI_LEVEL.equals(level) && index != levels.length - 1) {
                return false;
            }
            if ((level.contains(SINGLE_LEVEL) || level.contains(MULTI_LEVEL))
                    && !SINGLE_LEVEL.equals(level) && !MULTI_LEVEL.equals(level)) {
                // 通配符必须独占一层：a/b+ 与 a/#x 都是非法的
                return false;
            }
        }
        return true;
    }

    /**
     * 从一组过滤器中找出匹配该主题的那个。
     *
     * @param filters 过滤器列表
     * @param topic   具体主题
     * @return 匹配的过滤器；都不匹配时返回 {@code null}
     */
    static @Nullable String firstMatch(String[] filters, String topic) {
        return Arrays.stream(filters).filter(filter -> matches(filter, topic)).findFirst().orElse(null);
    }
}
