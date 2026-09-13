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
import java.util.Map;
import java.util.Objects;

/**
 * 订阅请求。
 *
 * @param addresses         待订阅地址列表
 * @param samplingInterval  采样周期（轮询式订阅必填；原生订阅可忽略）
 * @param publishingInterval 发布周期（原生订阅用；下限由服务端决定）
 * @param deadband          死区；{@code null} 表示不过滤
 * @param options           协议扩展选项
 * @author wenbin
 * @since 2026-09-13
 */
public record SubscribeRequest(
        List<PointAddress> addresses,
        Duration samplingInterval,
        Duration publishingInterval,
        Double deadband,
        Map<String, String> options) {

    /** 默认采样周期。 */
    public static final Duration DEFAULT_SAMPLING_INTERVAL = Duration.ofSeconds(1);

    /**
     * 紧凑构造器：校验非空并归一化可空字段。
     */
    public SubscribeRequest {
        Objects.requireNonNull(addresses, "addresses must not be null");
        addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("addresses must not be empty");
        }
        samplingInterval = samplingInterval == null ? DEFAULT_SAMPLING_INTERVAL : samplingInterval;
        publishingInterval = publishingInterval == null ? samplingInterval : publishingInterval;
        options = options == null ? Map.of() : Map.copyOf(options);
    }

    /**
     * 由地址列表构造（使用默认周期，无死区）。
     *
     * @param addresses 地址列表
     * @return 订阅请求
     */
    public static SubscribeRequest of(List<PointAddress> addresses) {
        return new SubscribeRequest(addresses, DEFAULT_SAMPLING_INTERVAL, DEFAULT_SAMPLING_INTERVAL,
                null, Map.of());
    }
}
