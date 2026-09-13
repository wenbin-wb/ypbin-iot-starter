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

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 协议描述符：协议身份、能力与兼容性声明。
 *
 * <p>全部字段不可变；建议协议模块用静态常量持有单例，避免每次注册重复构造。</p>
 *
 * @param code                  协议标识，全局唯一
 * @param name                  展示名，如 {@code Modbus TCP}
 * @param vendor                底层协议栈来源，如 {@code Apache PLC4X}
 * @param stackVersion          底层协议栈版本，用于问题排查
 * @param transport             承载方式：TCP / UDP / SERIAL / CAN / SIP / LOCAL
 * @param capabilities          能力集合
 * @param extensions            显式声明的扩展接口集合
 * @param minimumRuntimeVersion 要求的最低 iot-runtime 版本（含）
 * @param maximumRuntimeVersion 支持的最高 iot-runtime 版本（不含）
 * @param attributes            协议元信息（默认端口、规范编号等）
 * @author wenbin
 * @since 2026-09-13
 */
public record ProtocolDescriptor(
        ProtocolCode code,
        String name,
        String vendor,
        String stackVersion,
        String transport,
        Set<ProtocolCapability> capabilities,
        Set<Class<? extends ProtocolExtension>> extensions,
        String minimumRuntimeVersion,
        String maximumRuntimeVersion,
        Map<String, String> attributes) {

    /**
     * 紧凑构造器：归一化可空字段，避免下游到处判空。
     */
    public ProtocolDescriptor {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(name, "name must not be null");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        extensions = extensions == null ? Set.of() : Set.copyOf(extensions);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
        vendor = vendor == null ? "" : vendor;
        stackVersion = stackVersion == null ? "" : stackVersion;
        transport = transport == null ? "" : transport;
        minimumRuntimeVersion = minimumRuntimeVersion == null ? "" : minimumRuntimeVersion;
        maximumRuntimeVersion = maximumRuntimeVersion == null ? "" : maximumRuntimeVersion;
    }

    /**
     * 创建构建器。
     *
     * @return 构建器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * 是否声明支持某个能力。
     *
     * @param capability 能力
     * @return 支持返回 {@code true}
     */
    public boolean supports(ProtocolCapability capability) {
        return capabilities.contains(capability);
    }

    /**
     * 是否声明支持某个扩展接口。
     *
     * @param extensionType 扩展接口类型
     * @return 声明支持返回 {@code true}
     */
    public boolean supportsExtension(Class<? extends ProtocolExtension> extensionType) {
        return extensions.contains(extensionType);
    }

    /**
     * 描述符构建器。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    public static final class Builder {

        private final Set<ProtocolCapability> capabilities = new LinkedHashSet<>();

        private final Set<Class<? extends ProtocolExtension>> extensions = new LinkedHashSet<>();

        private final Map<String, String> attributes = new LinkedHashMap<>();

        private ProtocolCode code;

        private String name;

        private String vendor = "";

        private String stackVersion = "";

        private String transport = "";

        private String minimumRuntimeVersion = "";

        private String maximumRuntimeVersion = "";

        private Builder() {
        }

        /**
         * 设置协议标识。
         *
         * @param code 协议标识
         * @return 当前构建器
         */
        public Builder code(ProtocolCode code) {
            this.code = code;
            return this;
        }

        /**
         * 设置展示名。
         *
         * @param name 展示名
         * @return 当前构建器
         */
        public Builder name(String name) {
            this.name = name;
            return this;
        }

        /**
         * 设置底层协议栈来源。
         *
         * @param vendor 来源
         * @return 当前构建器
         */
        public Builder vendor(String vendor) {
            this.vendor = vendor;
            return this;
        }

        /**
         * 设置底层协议栈版本。
         *
         * @param stackVersion 版本
         * @return 当前构建器
         */
        public Builder stackVersion(String stackVersion) {
            this.stackVersion = stackVersion;
            return this;
        }

        /**
         * 设置承载方式。
         *
         * @param transport 承载方式
         * @return 当前构建器
         */
        public Builder transport(String transport) {
            this.transport = transport;
            return this;
        }

        /**
         * 追加能力。
         *
         * @param values 能力数组
         * @return 当前构建器
         */
        public Builder capabilities(ProtocolCapability... values) {
            if (values != null) {
                for (ProtocolCapability value : values) {
                    if (value != null) {
                        this.capabilities.add(value);
                    }
                }
            }
            return this;
        }

        /**
         * 声明扩展接口。
         *
         * @param extensionTypes 扩展接口类型
         * @return 当前构建器
         */
        @SafeVarargs
        public final Builder extensions(Class<? extends ProtocolExtension>... extensionTypes) {
            if (extensionTypes != null) {
                for (Class<? extends ProtocolExtension> type : extensionTypes) {
                    if (type != null) {
                        this.extensions.add(type);
                    }
                }
            }
            return this;
        }

        /**
         * 设置支持的 iot-runtime 版本区间。
         *
         * @param minimum 最低版本（含）
         * @param maximum 最高版本（不含）
         * @return 当前构建器
         */
        public Builder runtimeVersionRange(String minimum, String maximum) {
            this.minimumRuntimeVersion = minimum;
            this.maximumRuntimeVersion = maximum;
            return this;
        }

        /**
         * 追加元信息。
         *
         * @param key   键
         * @param value 值
         * @return 当前构建器
         */
        public Builder attribute(String key, String value) {
            this.attributes.put(key, value);
            return this;
        }

        /**
         * 构建描述符。
         *
         * @return 描述符
         */
        public ProtocolDescriptor build() {
            return new ProtocolDescriptor(code, name, vendor, stackVersion, transport,
                    capabilities, extensions, minimumRuntimeVersion, maximumRuntimeVersion, attributes);
        }
    }
}
