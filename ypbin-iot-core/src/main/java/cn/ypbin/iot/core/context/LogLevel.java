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
package cn.ypbin.iot.core.context;

import java.util.Optional;

/**
 * 适配器结构化日志级别。
 *
 * <p>刻意不复用 SLF4J 的 {@code Level}：框架需要的是接入层语义级别而非通用日志级别，
 * 且 core 只依赖 {@code slf4j-api}（不含实现），日志级别由框架统一映射到具体后端。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum LogLevel {

    /** 调试：协议报文级细节，生产默认关闭。 */
    DEBUG(0, "调试"),

    /** 信息：建链、断链、订阅建立等状态变更。 */
    INFO(1, "信息"),

    /** 警告：可自愈的异常（重连、点位质量劣化、配置项拼写可疑）。 */
    WARN(2, "警告"),

    /** 错误：需要人工介入的异常（建链持续失败、协议不兼容、配置非法）。 */
    ERROR(3, "错误");

    private final int code;

    private final String desc;

    LogLevel(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 级别码，用于跨进程传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 级别码
     */
    public int getCode() {
        return code;
    }

    /**
     * 级别描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按级别码查找。
     *
     * @param code 级别码
     * @return 匹配的级别；无匹配时返回空 Optional
     */
    public static Optional<LogLevel> fromCode(int code) {
        for (LogLevel level : values()) {
            if (level.code == code) {
                return Optional.of(level);
            }
        }
        return Optional.empty();
    }
}
