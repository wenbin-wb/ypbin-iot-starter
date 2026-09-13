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

import java.util.Optional;

/**
 * 链路与会话状态。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum SessionState {

    /** 未连接。 */
    IDLE(0, "未连接"),

    /** 连接中（含协议握手）。 */
    CONNECTING(1, "连接中"),

    /** 在线。 */
    ONLINE(2, "在线"),

    /** 降级（可通信但质量劣化，如部分点位读取失败、响应变慢）。 */
    DEGRADED(3, "降级"),

    /** 重连中。 */
    RECONNECTING(4, "重连中"),

    /** 已关闭（主动关闭或对端正常关闭）。 */
    CLOSED(5, "已关闭"),

    /** 失败（建链或绑定失败）。 */
    FAILED(6, "失败");

    private final int code;

    private final String desc;

    SessionState(int code, String desc) {
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
     * 是否处于可通信状态。
     *
     * @return ONLINE 或 DEGRADED 时返回 {@code true}
     */
    public boolean isUsable() {
        return this == ONLINE || this == DEGRADED;
    }

    /**
     * 按状态码查找。
     *
     * @param code 状态码
     * @return 匹配的状态；无匹配时返回空 Optional
     */
    public static Optional<SessionState> fromCode(int code) {
        for (SessionState state : values()) {
            if (state.code == code) {
                return Optional.of(state);
            }
        }
        return Optional.empty();
    }
}
