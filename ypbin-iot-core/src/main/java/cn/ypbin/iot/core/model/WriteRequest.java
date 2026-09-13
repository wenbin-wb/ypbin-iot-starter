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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 写请求。
 *
 * @param writes  待写列表
 * @param timeout 超时
 * @author wenbin
 * @since 2026-09-13
 */
public record WriteRequest(List<PointWrite> writes, Duration timeout) {

    /**
     * 紧凑构造器：校验非空并归一化超时。
     */
    public WriteRequest {
        Objects.requireNonNull(writes, "writes must not be null");
        writes = List.copyOf(writes);
        if (writes.isEmpty()) {
            throw new IllegalArgumentException("writes must not be empty");
        }
        timeout = timeout == null ? ConnectionSpec.DEFAULT_REQUEST_TIMEOUT : timeout;
    }

    /**
     * 由写项数组构造。
     *
     * @param writes 写项数组
     * @return 写请求
     */
    public static WriteRequest of(PointWrite... writes) {
        return new WriteRequest(List.of(writes), ConnectionSpec.DEFAULT_REQUEST_TIMEOUT);
    }
}
