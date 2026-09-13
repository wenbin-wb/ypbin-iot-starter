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
 * 读请求。
 *
 * <p>框架保证 {@code addresses} 非空且不含 {@code null}；协议相关的切分（如 Modbus 单次
 * 最多读 125 个寄存器、不连续地址需拆分为多次 PDU）由适配器负责，对调用方保持
 * 「一次请求一次结果」的语义。</p>
 *
 * @param addresses 待读地址列表
 * @param timeout   超时
 * @author wenbin
 * @since 2026-09-13
 */
public record ReadRequest(List<PointAddress> addresses, Duration timeout) {

    /**
     * 紧凑构造器：校验非空并归一化超时。
     */
    public ReadRequest {
        Objects.requireNonNull(addresses, "addresses must not be null");
        addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("addresses must not be empty");
        }
        timeout = timeout == null ? ConnectionSpec.DEFAULT_REQUEST_TIMEOUT : timeout;
    }

    /**
     * 由地址数组构造。
     *
     * @param addresses 地址数组
     * @return 读请求
     */
    public static ReadRequest of(PointAddress... addresses) {
        return new ReadRequest(List.of(addresses), ConnectionSpec.DEFAULT_REQUEST_TIMEOUT);
    }

    /**
     * 由地址列表构造。
     *
     * @param addresses 地址列表
     * @return 读请求
     */
    public static ReadRequest of(List<PointAddress> addresses) {
        return new ReadRequest(addresses, ConnectionSpec.DEFAULT_REQUEST_TIMEOUT);
    }
}
