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

import cn.ypbin.iot.core.model.DeviceSpec;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

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
 * @param subscriptionAddressing 订阅寻址方式（协议级默认）：一次 {@code subscribe} 可携带几个地址
 *                              （未声明时取 {@link SubscriptionAddressing#DEFAULT}）
 * @param addressingPolicy      订阅寻址策略：协议级默认值 + 按形态变体细分的覆盖表
 *                              （未声明细分时与 {@code subscriptionAddressing} 等价）
 * @param variantResolver       「当前设备属于哪个形态」的解析器；{@code null} 表示不做形态细分
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
        Map<String, String> attributes,
        SubscriptionAddressing subscriptionAddressing,
        SubscriptionAddressingPolicy addressingPolicy,
        SubscriptionAddressingPolicy.@Nullable VariantResolver variantResolver) {

    /**
     * 向后兼容构造器：不声明订阅寻址方式（等价于 {@link SubscriptionAddressing#DEFAULT}）。
     *
     * <p>保留它是为了<b>不破坏已发布的构造调用点</b>：新增字段若只留规范构造器，
     * 任何直接 {@code new ProtocolDescriptor(...)} 的宿主机/第三方协议模块都会编译失败。
     * 本重载委托给规范构造器，语义是「未声明」而不是「随便给个值」，
     * 与 builder 未调用 {@code subscriptionAddressing(...)} 完全一致。</p>
     *
     * @param code                  协议标识
     * @param name                  展示名
     * @param vendor                底层协议栈来源
     * @param stackVersion          底层协议栈版本
     * @param transport             承载方式
     * @param capabilities          能力集合
     * @param extensions            扩展接口集合
     * @param minimumRuntimeVersion 最低 iot-runtime 版本（含）
     * @param maximumRuntimeVersion 最高 iot-runtime 版本（不含）
     * @param attributes            协议元信息
     */
    public ProtocolDescriptor(ProtocolCode code, String name, String vendor, String stackVersion,
            String transport, Set<ProtocolCapability> capabilities,
            Set<Class<? extends ProtocolExtension>> extensions, String minimumRuntimeVersion,
            String maximumRuntimeVersion, Map<String, String> attributes) {
        this(code, name, vendor, stackVersion, transport, capabilities, extensions,
                minimumRuntimeVersion, maximumRuntimeVersion, attributes,
                SubscriptionAddressing.DEFAULT);
    }

    /**
     * 向后兼容构造器（上一版的规范构造器）：只声明协议级寻址方式、不声明形态细分。
     *
     * <p>保留它是为了<b>不破坏 0.4.x 已发布的构造调用点</b>：本次把寻址能力从「单一值」升级为
     * 「单一值 + 按形态细分」，若只留新的规范构造器，任何直接构造描述符的第三方协议模块都会
     * 编译失败。本重载把 11 参映射为「策略 = 该单选值、无细分、无形态解析器」，
     * 与旧行为<b>逐字等价</b>。</p>
     *
     * @param code                  协议标识
     * @param name                  展示名
     * @param vendor                底层协议栈来源
     * @param stackVersion          底层协议栈版本
     * @param transport             承载方式
     * @param capabilities          能力集合
     * @param extensions            扩展接口集合
     * @param minimumRuntimeVersion 最低 iot-runtime 版本（含）
     * @param maximumRuntimeVersion 最高 iot-runtime 版本（不含）
     * @param attributes            协议元信息
     * @param subscriptionAddressing 协议级订阅寻址方式
     */
    public ProtocolDescriptor(ProtocolCode code, String name, String vendor, String stackVersion,
            String transport, Set<ProtocolCapability> capabilities,
            Set<Class<? extends ProtocolExtension>> extensions, String minimumRuntimeVersion,
            String maximumRuntimeVersion, Map<String, String> attributes,
            SubscriptionAddressing subscriptionAddressing) {
        this(code, name, vendor, stackVersion, transport, capabilities, extensions,
                minimumRuntimeVersion, maximumRuntimeVersion, attributes, subscriptionAddressing,
                SubscriptionAddressingPolicy.of(subscriptionAddressing), null);
    }

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
        // 未声明 = 保守侧（逐点位单订阅）；理由见 SubscriptionAddressing#DEFAULT 的注释
        subscriptionAddressing = subscriptionAddressing == null
                ? SubscriptionAddressing.DEFAULT : subscriptionAddressing;
        if (addressingPolicy == null) {
            // 未给策略时用单选值构造「无细分」策略：保持 0.4.x 语义逐字一致
            addressingPolicy = SubscriptionAddressingPolicy.of(subscriptionAddressing);
        } else {
            // 两者可能由不同入口设置；为避免「同一事实两个值」的不一致，
            // 以策略的协议级默认值为准回写单选值（策略是更完整的那份声明）
            subscriptionAddressing = addressingPolicy.defaultAddressing();
        }
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
     * 解析当前设备/上下文下的订阅寻址方式。
     *
     * <p>这是编排器唯一应该调用的入口——它把「取形态 → 查声明表 → 兜底」三步封装在描述符内，
     * 调用方<b>不需要</b>认识任何形态字符串，也不会漏掉兜底逻辑。</p>
     *
     * <p>解析顺序：① 声明了 {@link #variantResolver()} ⇒ 用当前设备/上下文解析出形态键，
     * 到 {@link #addressingPolicy()} 的细化表里查；② 未声明解析器或解析不出形态 ⇒
     * 协议级 {@link #subscriptionAddressing()}；③ 声明了细化表但形态未命中 ⇒
     * 兜底保守侧并标记 {@link SubscriptionAddressingPolicy.Resolution#variantMiss()}。</p>
     *
     * @param device 设备规格
     * @return 解析结果（恒非空；{@code variantMiss} 为真时调用方应 WARN）
     */
    public SubscriptionAddressingPolicy.Resolution resolveAddressing(DeviceSpec device) {
        if (variantResolver == null || !addressingPolicy.hasVariants()) {
            return addressingPolicy.resolve(null);
        }
        String variant;
        try {
            variant = variantResolver.resolveVariant(device);
        } catch (RuntimeException ex) {
            // 形态解析失败不能让订阅整轮失败：退化为协议级默认，但必须让调用方看到
            // （返回 variantMiss 会触发 WARN；这里把异常本身也当成「形态未知」）
            return addressingPolicy.resolve(null);
        }
        return addressingPolicy.resolve(variant);
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

        @Nullable
        private ProtocolCode code;

        private String name = "";

        private String vendor = "";

        private String stackVersion = "";

        private String transport = "";

        private String minimumRuntimeVersion = "";

        private String maximumRuntimeVersion = "";

        private SubscriptionAddressing subscriptionAddressing = SubscriptionAddressing.DEFAULT;

        @Nullable
        private Map<String, SubscriptionAddressing> subscriptionAddressingByVariant;

        private SubscriptionAddressingPolicy.@Nullable VariantResolver variantResolverField;

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
         * 声明订阅寻址方式：一次 {@code subscribe} 请求能携带几个地址。
         *
         * <p><b>必须由协议模块显式声明</b>：框架的订阅编排（{@code SubscriptionOrchestrator}）
         * 据此决定「整批订阅」还是「逐点位单订阅」，不再由宿主按协议码字符串硬编码。
         * 不调用本方法等价于 {@link SubscriptionAddressing#DEFAULT}（保守侧：逐点位单订阅）。</p>
         *
         * @param addressing 寻址方式（{@code null} 表示未声明，回落默认值）
         * @return 当前构建器
         */
        public Builder subscriptionAddressing(SubscriptionAddressing addressing) {
            this.subscriptionAddressing = addressing == null
                    ? SubscriptionAddressing.DEFAULT : addressing;
            return this;
        }

        /**
         * 按「形态变体」细分订阅寻址方式（协议级默认值仍由
         * {@link #subscriptionAddressing(SubscriptionAddressing)} 给出）。
         *
         * <p>用于<b>同一协议在不同配置形态下多地址能力不同</b>的场景（典型：TCP 透传在
         * {@code payload-format=key-value} 时一帧可服务多个 selector，其余形态是单点位流）。
         * 键是协议自定义的稳定字面量，框架不认识其含义，只做「取当前 variant → 查表 → 兜底」。</p>
         *
         * <p>未命中（协议声明了细分但当前形态不在表内）时框架兜底保守侧
         * {@link SubscriptionAddressing#PER_ADDRESS} 并 WARN 一次，<b>不会</b>回落协议级默认值
         * ——协议级默认值可能就是 {@code BATCH}，把未知形态当「支持多地址」会导致静默数据缺失。</p>
         *
         * @param byVariant 形态变体键 → 寻址方式；{@code null}/空表表示不做细分
         * @return 当前构建器
         */
        public Builder subscriptionAddressingByVariant(
                Map<String, SubscriptionAddressing> byVariant) {
            this.subscriptionAddressingByVariant =
                    byVariant == null ? null : new LinkedHashMap<>(byVariant);
            return this;
        }

        /**
         * 声明「当前设备属于哪个形态」的解析器。
         *
         * <p>判据只有协议自己知道（可能来自 {@code DeviceSpec}、连接属性或适配器构造参数），
         * 因此由协议实现 {@link SubscriptionAddressingPolicy.VariantResolver}，
         * 框架只消费返回的稳定键去查 {@link #subscriptionAddressingByVariant(Map)} 的声明。</p>
         *
         * @param resolver 形态解析器；{@code null} 表示不支持形态细分
         * @return 当前构建器
         */
        public Builder variantResolver(SubscriptionAddressingPolicy.VariantResolver resolver) {
            this.variantResolverField = resolver;
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
            // 必填项校验：原来直接构造，未设置 code/name 时会产出一个「看似合法」的描述符
            // （空标识），错误会漂到很远的地方才暴露
            if (code == null) {
                throw new IllegalStateException("ProtocolDescriptor.code must be set before build()");
            }
            if (name.isBlank()) {
                throw new IllegalStateException("ProtocolDescriptor.name must be set before build()");
            }
            SubscriptionAddressingPolicy policy = subscriptionAddressingByVariant == null
                    || subscriptionAddressingByVariant.isEmpty()
                            ? SubscriptionAddressingPolicy.of(subscriptionAddressing)
                            : new SubscriptionAddressingPolicy(subscriptionAddressing,
                                    subscriptionAddressingByVariant);
            return new ProtocolDescriptor(code, name, vendor, stackVersion, transport,
                    capabilities, extensions, minimumRuntimeVersion, maximumRuntimeVersion, attributes,
                    subscriptionAddressing, policy, variantResolverField);
        }
    }
}
