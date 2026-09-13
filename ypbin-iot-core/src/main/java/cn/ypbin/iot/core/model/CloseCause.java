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
 * 链路关闭原因分类。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum CloseCause {

    /** 客户端主动关闭。 */
    CLIENT_REQUEST(1, "主动关闭"),

    /** 对端关闭。 */
    REMOTE_CLOSED(2, "对端关闭"),

    /** 超时。 */
    TIMEOUT(3, "超时"),

    /** 协议错误。 */
    PROTOCOL_ERROR(4, "协议错误"),

    /** 传输层错误。 */
    TRANSPORT_ERROR(5, "传输错误"),

    /** 服务关停。 */
    SERVER_SHUTDOWN(6, "服务关停");

    private final int code;

    private final String desc;

    CloseCause(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 原因码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 原因码
     */
    public int getCode() {
        return code;
    }

    /**
     * 原因描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按原因码查找。
     *
     * @param code 原因码
     * @return 匹配的原因；无匹配时返回空 Optional
     */
    public static Optional<CloseCause> fromCode(int code) {
        for (CloseCause cause : values()) {
            if (cause.code == code) {
                return Optional.of(cause);
            }
        }
        return Optional.empty();
    }
}
