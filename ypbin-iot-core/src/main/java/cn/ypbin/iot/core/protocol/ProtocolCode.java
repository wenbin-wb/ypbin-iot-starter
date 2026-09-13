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
package cn.ypbin.iot.core.protocol;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 协议标识：开放集合值对象而非枚举。
 *
 * <p>协议是外延可扩展的（第三方可新增协议模块），用枚举会逼第三方修改 {@code iot-core}，
 * 因此这里用值对象。取值约定为小写字母开头、仅含小写字母/数字/连字符，
 * 例如 {@code modbus-tcp}、{@code opcua}、{@code mqtt}。</p>
 *
 * <p>该值同时是<b>协议的唯一身份</b>：注册中心按它建索引，两个适配器声明同一 code
 * 会导致启动失败（fail-fast）。</p>
 *
 * @param value 协议标识字面值
 * @author wenbin
 * @since 2026-09-13
 */
public record ProtocolCode(String value) {

    /** 合法格式：小写字母开头，允许小写字母、数字与连字符。 */
    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9-]*");

    /**
     * 紧凑构造器：校验格式。
     *
     * @throws IllegalArgumentException 当取值为空或格式非法时
     */
    public ProtocolCode {
        Objects.requireNonNull(value, "value must not be null");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "invalid protocol code: " + value + " (expect [a-z][a-z0-9-]*)");
        }
    }

    /**
     * 由字面值构造协议标识。
     *
     * @param value 协议标识字面值
     * @return 协议标识
     */
    public static ProtocolCode of(String value) {
        return new ProtocolCode(value);
    }

    @Override
    public String toString() {
        return value;
    }
}
