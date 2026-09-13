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

import java.util.List;

/**
 * 订阅句柄。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface SubscriptionHandle {

    /**
     * 订阅标识。
     *
     * @return 订阅标识
     */
    String subscriptionId();

    /**
     * 已订阅的地址。
     *
     * @return 地址列表
     */
    List<PointAddress> addresses();

    /**
     * 是否仍然有效。
     *
     * @return 有效返回 {@code true}
     */
    boolean active();

    /**
     * 已推送点位计数，用于宿主核对订阅是否真的在工作。
     *
     * @return 已推送点位数
     */
    long deliveredCount();
}
