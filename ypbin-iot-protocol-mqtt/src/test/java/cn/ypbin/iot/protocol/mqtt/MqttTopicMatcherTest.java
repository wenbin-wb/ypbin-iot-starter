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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MQTT 主题过滤器匹配测试。
 *
 * <p>MQTT 的订阅用过滤器（可含通配符）、发布用具体主题，两者不是字符串相等关系。
 * 用 {@code equals} 会漏掉通配订阅，用 {@code startsWith} 会把 {@code a/bc} 错配到 {@code a/b}。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class MqttTopicMatcherTest {

    @Test
    @DisplayName("TOPIC-01 精确主题必须相等匹配")
    void exactMatch() {
        assertThat(MqttTopicMatcher.matches("a/b/c", "a/b/c")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/b/c", "a/b/d")).isFalse();
        assertThat(MqttTopicMatcher.matches("a/b", "a/bc"))
                .as("前缀相同但不是同一层，startsWith 会错判")
                .isFalse();
        assertThat(MqttTopicMatcher.matches("a/b", "a/b/c")).isFalse();
    }

    @Test
    @DisplayName("TOPIC-02 单层通配 + 只匹配恰好一层")
    void singleLevelWildcard() {
        assertThat(MqttTopicMatcher.matches("a/+/c", "a/b/c")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/+/c", "a/x/c")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/+/c", "a/b/d/c"))
                .as("+ 只能匹配一层")
                .isFalse();
        assertThat(MqttTopicMatcher.matches("+/b", "a/b")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/+", "a")).isFalse();
    }

    @Test
    @DisplayName("TOPIC-03 多层通配 # 匹配剩余全部（含零层），且只能是最后一层")
    void multiLevelWildcard() {
        assertThat(MqttTopicMatcher.matches("a/#", "a/b")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/#", "a/b/c/d")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/#", "a"))
                .as("# 含零层：a/# 应匹配 a 自身")
                .isTrue();
        assertThat(MqttTopicMatcher.matches("#", "anything/at/all")).isTrue();
        assertThat(MqttTopicMatcher.matches("a/#", "b/c")).isFalse();
        assertThat(MqttTopicMatcher.matches("a/#/c", "a/b/c"))
                .as("# 不是最后一层，非法过滤器不应匹配")
                .isFalse();
    }

    @Test
    @DisplayName("TOPIC-04 过滤器合法性校验必须拒绝通配符不独占一层的写法")
    void filterValidation() {
        assertThat(MqttTopicMatcher.isValidFilter("a/b/c")).isTrue();
        assertThat(MqttTopicMatcher.isValidFilter("a/+/c")).isTrue();
        assertThat(MqttTopicMatcher.isValidFilter("a/#")).isTrue();
        assertThat(MqttTopicMatcher.isValidFilter("#")).isTrue();
        assertThat(MqttTopicMatcher.isValidFilter("a/b+"))
                .as("通配符必须独占一层")
                .isFalse();
        assertThat(MqttTopicMatcher.isValidFilter("a/#/c")).isFalse();
        assertThat(MqttTopicMatcher.isValidFilter("")).isFalse();
        assertThat(MqttTopicMatcher.isValidFilter(null)).isFalse();
    }

    @Test
    @DisplayName("TOPIC-05 多过滤器必须返回第一个匹配者")
    void firstMatch() {
        String[] filters = {"a/+/c", "a/b/#", "x/y"};
        assertThat(MqttTopicMatcher.firstMatch(filters, "a/b/c")).isEqualTo("a/+/c");
        assertThat(MqttTopicMatcher.firstMatch(filters, "a/b/c/d")).isEqualTo("a/b/#");
        assertThat(MqttTopicMatcher.firstMatch(filters, "x/y")).isEqualTo("x/y");
        assertThat(MqttTopicMatcher.firstMatch(filters, "nope")).isNull();
    }

    @Test
    @DisplayName("TOPIC-06 null 输入不得抛异常")
    void nullSafety() {
        assertThat(MqttTopicMatcher.matches(null, "a")).isFalse();
        assertThat(MqttTopicMatcher.matches("a", null)).isFalse();
        assertThat(MqttTopicMatcher.matches(null, null)).isFalse();
    }
}
