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
package cn.ypbin.iot.core.spi;

import java.util.Optional;

/**
 * 设备配置变更类型。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum ChangeType {

    /** 新增。 */
    ADD(1, "新增"),

    /** 修改。 */
    UPDATE(2, "修改"),

    /** 删除。 */
    REMOVE(3, "删除");

    private final int code;

    private final String desc;

    ChangeType(int code, String desc) {
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
    public static Optional<ChangeType> fromCode(int code) {
        for (ChangeType type : values()) {
            if (type.code == code) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
