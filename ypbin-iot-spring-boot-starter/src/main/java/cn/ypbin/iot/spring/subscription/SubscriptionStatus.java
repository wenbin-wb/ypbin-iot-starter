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

import java.util.Optional;

/**
 * 订阅尝试的结果状态。
 *
 * <p><b>为什么状态要成枚举而不是布尔</b>：「本轮没发订阅」至少有三种完全不同的原因——
 * 会话没变（无需重订）、退避窗口内（不该重订）、设备没有点位（没得订）。
 * 折叠成 {@code false} 会让宿主的诊断日志失去区分能力，而订阅不生效恰恰是最难从现象反推的故障。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public enum SubscriptionStatus {

    /** 会话实例未变且此前已订阅成功：本轮不重复订阅。 */
    ALREADY_SUBSCRIBED(0, "已订阅"),

    /** 本轮全部地址订阅成功。 */
    SUBSCRIBED(1, "订阅成功"),

    /** 部分地址成功、部分失败：可只补失败地址。 */
    PARTIAL(2, "部分成功"),

    /** 全部地址订阅失败。 */
    FAILED(3, "订阅失败"),

    /** 处于失败退避窗口内，本轮未发起订阅。 */
    SKIPPED_BACKOFF(4, "退避窗口内跳过"),

    /** 该设备当前没有可订阅地址（点位映射为空）。 */
    NO_ADDRESSES(5, "无可订阅地址"),

    /** 该设备当前没有已建链会话（尚未绑定或建链失败），本轮跳过。 */
    SKIPPED_NO_SESSION(6, "无已建链会话");

    private final int code;

    private final String desc;

    SubscriptionStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 状态码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 状态码
     */
    public int getCode() {
        return code;
    }

    /**
     * 状态描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按状态码查找。
     *
     * @param code 状态码
     * @return 匹配的状态；无匹配时返回空 Optional
     */
    public static Optional<SubscriptionStatus> fromCode(int code) {
        for (SubscriptionStatus status : values()) {
            if (status.code == code) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }
}
