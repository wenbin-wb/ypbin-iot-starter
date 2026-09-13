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
 * 单个写项。
 *
 * @param address 点位地址
 * @param value   待写入的值
 * @author wenbin
 * @since 2026-09-13
 */
public record PointWrite(PointAddress address, Object value) {

    /**
     * 紧凑构造器：校验地址非空。
     */
    public PointWrite {
        Objects.requireNonNull(address, "address must not be null");
    }
}
