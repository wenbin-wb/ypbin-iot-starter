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
 * 单个写项的结果。
 *
 * @param address 点位地址
 * @param success 是否成功
 * @param reason  失败原因（i18n 消息键）；成功时为 {@code null}
 * @author wenbin
 * @since 2026-09-13
 */
public record PointWriteStatus(PointAddress address, boolean success, String reason) {

    /**
     * 紧凑构造器：校验地址非空，成功后强制清空原因。
     */
    public PointWriteStatus {
        Objects.requireNonNull(address, "address must not be null");
        if (success) {
            reason = null;
        }
    }

    /**
     * 构造成功状态。
     *
     * @param address 点位地址
     * @return 写项结果
     */
    public static PointWriteStatus ok(PointAddress address) {
        return new PointWriteStatus(address, true, null);
    }

    /**
     * 构造失败状态。
     *
     * @param address 点位地址
     * @param reason  失败原因（i18n 消息键）
     * @return 写项结果
     */
    public static PointWriteStatus fail(PointAddress address, String reason) {
        return new PointWriteStatus(address, false, reason);
    }
}
