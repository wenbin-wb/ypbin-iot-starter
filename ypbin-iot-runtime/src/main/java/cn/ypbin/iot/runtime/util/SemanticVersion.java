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
package cn.ypbin.iot.runtime.util;

import java.util.Objects;

/**
 * 极简语义化版本：仅支持 {@code major.minor.patch} 三段数字比较。
 *
 * <p>刻意不实现预发布与构建元数据语义——本仓只用它做「协议模块 ↔ 运行时」的区间校验，
 * 版本号由本仓自己维护，不引入完整 semver 实现的复杂度。</p>
 *
 * @param major 主版本
 * @param minor 次版本
 * @param patch 修订号
 * @author wenbin
 * @since 2026-09-13
 */
public record SemanticVersion(int major, int minor, int patch) implements Comparable<SemanticVersion> {

    /**
     * 解析版本字符串。
     *
     * <p>允许 {@code 1.2} 与 {@code 1} 这类省略写法（缺失段按 0 处理），
     * 并忽略 {@code -SNAPSHOT} 等后缀。</p>
     *
     * @param text 版本字符串
     * @return 版本对象
     * @throws IllegalArgumentException 当字符串为空或格式非法时
     */
    public static SemanticVersion parse(String text) {
        Objects.requireNonNull(text, "version text must not be null");
        String value = text.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("version text must not be blank");
        }
        int dash = value.indexOf('-');
        if (dash >= 0) {
            value = value.substring(0, dash);
        }
        String[] parts = value.split("\\.");
        int[] numbers = new int[3];
        for (int i = 0; i < parts.length && i < numbers.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException("invalid version: " + text, ex);
            }
        }
        return new SemanticVersion(numbers[0], numbers[1], numbers[2]);
    }

    /**
     * 判断当前版本是否落在 {@code [minimum, maximum)} 区间内。
     *
     * <p>空字符串表示该侧不限制。</p>
     *
     * @param minimum 最低版本（含），可为空
     * @param maximum 最高版本（不含），可为空
     * @return 在区间内返回 {@code true}
     */
    public boolean isWithin(String minimum, String maximum) {
        if (minimum != null && !minimum.isBlank() && compareTo(parse(minimum)) < 0) {
            return false;
        }
        return maximum == null || maximum.isBlank() || compareTo(parse(maximum)) < 0;
    }

    @Override
    public int compareTo(SemanticVersion other) {
        int result = Integer.compare(major, other.major);
        if (result != 0) {
            return result;
        }
        result = Integer.compare(minor, other.minor);
        if (result != 0) {
            return result;
        }
        return Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }
}
