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
 * 设备事件类型。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum DeviceEventType {

    /** 设备上线。 */
    DEVICE_ONLINE(1, "设备上线"),

    /** 设备离线。 */
    DEVICE_OFFLINE(2, "设备离线"),

    /** 建链失败。 */
    CONNECT_FAILED(3, "建链失败"),

    /** 重连中。 */
    RECONNECTING(4, "重连中"),

    /** 协议错误。 */
    PROTOCOL_ERROR(5, "协议错误"),

    /** 数据质量劣化。 */
    QUALITY_DEGRADED(6, "数据质量劣化"),

    /** 订阅丢失。 */
    SUBSCRIPTION_LOST(7, "订阅丢失"),

    /** 配置非法。 */
    CONFIG_INVALID(8, "配置非法");

    private final int code;

    private final String desc;

    DeviceEventType(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 类型码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 类型码
     */
    public int getCode() {
        return code;
    }

    /**
     * 类型描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按类型码查找。
     *
     * @param code 类型码
     * @return 匹配的类型；无匹配时返回空 Optional
     */
    public static Optional<DeviceEventType> fromCode(int code) {
        for (DeviceEventType type : values()) {
            if (type.code == code) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
