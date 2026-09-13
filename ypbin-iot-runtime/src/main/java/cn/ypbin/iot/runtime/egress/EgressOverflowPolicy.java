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
package cn.ypbin.iot.runtime.egress;

import java.util.Optional;

/**
 * 出口队列溢出策略。
 *
 * <p>无论选择哪种策略，<b>都必须累加丢弃指标并输出限流日志</b>——
 * 丢弃可以，静默丢弃不行。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum EgressOverflowPolicy {

    /** 丢弃最旧数据，保住最新值（监控类数据的默认选择）。 */
    DROP_OLDEST(1, "丢弃最旧"),

    /** 丢弃新到数据，保住完整性（计量类数据的选择）。 */
    DROP_NEWEST(2, "丢弃最新"),

    /** 阻塞生产者等待队列腾空。 */
    BLOCK(3, "阻塞等待");

    private final int code;

    private final String desc;

    EgressOverflowPolicy(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 策略码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 策略码
     */
    public int getCode() {
        return code;
    }

    /**
     * 策略描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按策略码查找。
     *
     * @param code 策略码
     * @return 匹配的策略；无匹配时返回空 Optional
     */
    public static Optional<EgressOverflowPolicy> fromCode(int code) {
        for (EgressOverflowPolicy policy : values()) {
            if (policy.code == code) {
                return Optional.of(policy);
            }
        }
        return Optional.empty();
    }
}
