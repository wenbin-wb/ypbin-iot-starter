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
package cn.ypbin.iot.spring.subscription;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * 单次订阅尝试的结果。
 *
 * @param deviceId    设备标识
 * @param status      结果状态（成功/部分成功/失败/跳过及跳过原因）
 * @param outcome     地址级成败视图：成功地址集合 + 失败地址及原因
 * @param nextRetryAt 失败退避的下次可重试时刻；无退避时为 {@code null}
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionResult(String deviceId, SubscriptionStatus status,
        SubscriptionOutcome outcome, @Nullable Instant nextRetryAt) {

    /**
     * 紧凑构造器：校验必填项。
     */
    public SubscriptionResult {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        outcome = outcome == null ? new SubscriptionOutcome(Set.of(), List.of()) : outcome;
    }

    /**
     * 构造成功类结果。
     *
     * @param deviceId 设备标识
     * @param status   状态
     * @param outcome  地址级视图
     * @return 结果
     */
    public static SubscriptionResult of(String deviceId, SubscriptionStatus status,
            SubscriptionOutcome outcome) {
        return new SubscriptionResult(deviceId, status, outcome, null);
    }

    /**
     * 构造跳过类结果（无地址级视图）。
     *
     * @param deviceId 设备标识
     * @param status   状态
     * @return 结果
     */
    public static SubscriptionResult skipped(String deviceId, SubscriptionStatus status) {
        return new SubscriptionResult(deviceId, status, new SubscriptionOutcome(
                Set.of(), List.of()), null);
    }

    /**
     * 是否发起了协议层订阅调用。
     *
     * @return 状态为 {@link SubscriptionStatus#SUBSCRIBED}、{@link SubscriptionStatus#PARTIAL}
     *         或 {@link SubscriptionStatus#FAILED} 时返回 {@code true}
     */
    public boolean dispatched() {
        return status == SubscriptionStatus.SUBSCRIBED
                || status == SubscriptionStatus.PARTIAL
                || status == SubscriptionStatus.FAILED;
    }
}
