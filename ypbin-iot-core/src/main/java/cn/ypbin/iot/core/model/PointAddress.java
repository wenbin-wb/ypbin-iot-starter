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
package cn.ypbin.iot.core.model;

import java.util.Objects;

/**
 * 点位地址：原始字符串 + 协议解释权在适配器。
 *
 * <p>工业地址语义（{@code 40001} / {@code DB1.DBW0} / {@code ns=2;s=X}）无法无损归一，
 * 因此核心只透传 {@code raw}，解析由协议模块负责，并可借助
 * {@link cn.ypbin.iot.core.context.AdapterContext#addressCache(String, int)} 缓存解析结果。</p>
 *
 * @param raw 原始地址字符串
 * @author wenbin
 * @since 2026-09-13
 */
public record PointAddress(String raw) {

    /**
     * 紧凑构造器：校验非空。
     */
    public PointAddress {
        Objects.requireNonNull(raw, "raw must not be null");
        if (raw.isBlank()) {
            throw new IllegalArgumentException("point address must not be blank");
        }
    }

    /**
     * 由原始地址构造。
     *
     * @param raw 原始地址字符串
     * @return 点位地址
     */
    public static PointAddress of(String raw) {
        return new PointAddress(raw);
    }

    @Override
    public String toString() {
        return raw;
    }
}
