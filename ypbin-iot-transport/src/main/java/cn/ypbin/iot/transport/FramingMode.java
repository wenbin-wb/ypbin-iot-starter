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
package cn.ypbin.iot.transport;

import java.util.Optional;

/**
 * 帧定界模式。
 *
 * <p>透传类协议的粘包与半包问题由此统一解决，不允许协议模块自己写裸 {@code ByteBuf} 处理逻辑。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum FramingMode {

    /** 不定界：收到多少算多少（仅适合极短报文或上层自带定界的场景）。 */
    NONE(0, "不定界"),

    /** 长度字段定界（最常用）。 */
    LENGTH_FIELD(1, "长度字段"),

    /** 分隔符定界。 */
    DELIMITER(2, "分隔符");

    private final int code;

    private final String desc;

    FramingMode(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 模式码（严禁使用 {@link #ordinal()}）。
     *
     * @return 模式码
     */
    public int getCode() {
        return code;
    }

    /**
     * 模式描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按模式码查找。
     *
     * @param code 模式码
     * @return 匹配的模式；无匹配时返回空 Optional
     */
    public static Optional<FramingMode> fromCode(int code) {
        for (FramingMode mode : values()) {
            if (mode.code == code) {
                return Optional.of(mode);
            }
        }
        return Optional.empty();
    }
}
