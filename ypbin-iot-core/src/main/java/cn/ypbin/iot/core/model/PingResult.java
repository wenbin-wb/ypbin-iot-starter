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

/**
 * 链路保活结果。
 *
 * @param alive         是否存活
 * @param roundTripMillis 往返耗时（毫秒）
 * @param failureReason 失败原因（i18n 消息键）；存活时为 {@code null}
 * @author wenbin
 * @since 2026-09-13
 */
public record PingResult(boolean alive, long roundTripMillis, String failureReason) {

    /**
     * 紧凑构造器：存活时清空失败原因。
     */
    public PingResult {
        if (alive) {
            failureReason = null;
        }
    }

    /**
     * 构造存活结果。
     *
     * @param roundTripMillis 往返耗时
     * @return 保活结果
     */
    public static PingResult alive(long roundTripMillis) {
        return new PingResult(true, roundTripMillis, null);
    }

    /**
     * 构造失活结果。
     *
     * @param reason 失败原因（i18n 消息键）
     * @return 保活结果
     */
    public static PingResult dead(String reason) {
        return new PingResult(false, -1L, reason);
    }
}
