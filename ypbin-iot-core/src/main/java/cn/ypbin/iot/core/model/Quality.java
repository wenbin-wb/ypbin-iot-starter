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
 * 数据质量。与工业协议常见的质量语义对齐，取值刻意保持精简。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum Quality {

    /** 正常。 */
    GOOD(0, "正常"),

    /** 不确定（设备自报或数值超量程）。 */
    UNCERTAIN(1, "不确定"),

    /** 坏值（点位级读取失败）。 */
    BAD(2, "坏值"),

    /** 数据陈旧（超过预期刷新周期未更新）。 */
    STALE(3, "数据陈旧"),

    /** 链路未连接。 */
    NOT_CONNECTED(4, "链路未连接"),

    /** 配置错误（地址非法、类型不匹配）。 */
    CONFIG_ERROR(5, "配置错误");

    private final int code;

    private final String desc;

    Quality(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 质量码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 质量码
     */
    public int getCode() {
        return code;
    }

    /**
     * 质量描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按质量码查找。
     *
     * @param code 质量码
     * @return 匹配的质量；无匹配时返回空 Optional
     */
    public static Optional<Quality> fromCode(int code) {
        for (Quality quality : values()) {
            if (quality.code == code) {
                return Optional.of(quality);
            }
        }
        return Optional.empty();
    }
}
