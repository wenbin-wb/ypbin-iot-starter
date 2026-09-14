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

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 点位值。
 *
 * <p>{@code timestamp} 是<b>源时间戳</b>（设备侧提供时），类型为 {@link Instant}：
 * 协议时序是 UTC 绝对时刻，跨时区无损；宿主的实体与 API 契约再按母仓规范转
 * {@code LocalDateTime}（GMT+8）。</p>
 *
 * @param address       点位地址
 * @param value         值；类型由协议决定，非 GOOD 质量时可为 {@code null}
 * @param quality       质量
 * @param timestamp     源时间戳
 * @param qualityReason 非 GOOD 时的原因；GOOD 时为 {@code null}
 * @author wenbin
 * @since 2026-09-13
 */
public record PointValue(
        PointAddress address,
        @Nullable Object value,
        Quality quality,
        Instant timestamp,
        @Nullable String qualityReason) {

    /**
     * 紧凑构造器：校验必填项。
     */
    public PointValue {
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(quality, "quality must not be null");
        Objects.requireNonNull(timestamp, "timestamp must not be null");
        if (quality == Quality.GOOD) {
            qualityReason = null;
        }
    }

    /**
     * 构造正常值。
     *
     * @param address   点位地址
     * @param value     值
     * @param timestamp 源时间戳
     * @return 点位值
     */
    public static PointValue good(PointAddress address, Object value, Instant timestamp) {
        return new PointValue(address, value, Quality.GOOD, timestamp, null);
    }

    /**
     * 构造异常值。
     *
     * @param address   点位地址
     * @param quality   质量（不得为 GOOD）
     * @param reason    原因
     * @param timestamp 源时间戳
     * @return 点位值
     */
    public static PointValue bad(PointAddress address, Quality quality, String reason, Instant timestamp) {
        if (quality == Quality.GOOD) {
            throw new IllegalArgumentException("use good(...) for GOOD quality");
        }
        return new PointValue(address, null, quality, timestamp, reason);
    }

    /**
     * 是否正常。
     *
     * @return 质量为 GOOD 时返回 {@code true}
     */
    public boolean isGood() {
        return quality == Quality.GOOD;
    }
}
