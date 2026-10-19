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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * 订阅寻址策略：协议级默认值 + 按「形态变体（variant）」细分的覆盖表。
 *
 * <p><b>为什么需要细分</b>（本类的存在理由）：同一个协议在<b>不同配置形态</b>下，多地址请求的
 * 能力可能完全不同。最典型的是 TCP 透传——</p>
 * <ul>
 *   <li>{@code payload-format=binary/text/number}：帧是不透明字节流，<b>没有寻址语义</b>，
 *       一次只能订一个地址（多地址直接 fail-fast）；</li>
 *   <li>{@code payload-format=key-value}：帧内是 {@code KEY=VALUE} 字段，订阅地址被解释为
 *       <b>字段 selector</b>，一帧可服务多个点位，<b>一次订阅多个地址完全可行</b>。</li>
 * </ul>
 * <p>如果声明只能有一个值，TCP 只能取交集（{@link SubscriptionAddressing#PER_ADDRESS}），
 * 于是 key-value 形态被迫把 N 个点位拆成 N 次订阅——这正是宿主为了拿回性能而<b>在业务仓自建
 * 退避表、绕开框架编排器</b>的直接原因（全局规范 1.6：通用能力不得长期焊在业务仓）。</p>
 *
 * <p><b>为什么是「协议声明 variant 键 → 寻址方式」而不是「编排器认 payload-format 字符串」</b>：
 * 上一轮的教训是<b>判据必须来自协议声明</b>。本类把「哪些 variant 存在、各自什么寻址方式」
 * 完全交给协议模块声明；编排器只做三件事——取当前上下文的 variant 键、查表、兜底。
 * 它<b>不认识任何</b>{@code payload-format} 之类的业务字符串，也就不存在「框架硬编码某协议细节」。</p>
 *
 * <p><b>variant 键由谁提供</b>：由 {@link ProtocolDescriptor.VariantResolver}（协议自己实现）
 * 从运行时上下文里读出当前形态，返回一个协议自定义的稳定键（如 {@code "key-value"}）。
 * 之所以不直接用 {@code AdapterContext} 让编排器去猜，是因为「当前设备是什么形态」的判据
 * 只有协议自己知道（可能来自 {@code DeviceSpec}、{@code ConnectionSpec}、适配器构造参数或连接属性）。</p>
 *
 * <p><b>兜底语义（重要）</b>：显式声明了细化表、但运行期取到的 variant 不在表内时，
 * <b>兜底到保守侧</b> {@link SubscriptionAddressing#PER_ADDRESS} 并 WARN 一次——
 * 不是回落协议级默认值，因为协议级默认值可能就是 {@code BATCH}（那会把「未知形态」
 * 当成「支持多地址」，失败模式是静默数据缺失）。</p>
 *
 * <p><b>不变式</b>：{@link #defaultAddressing()} 恒非空；{@link #byVariant()} 恒非空且不可变。
 * 未声明任何细化时 {@link #byVariant()} 为空表，行为与「只有协议级单选值」完全一致。</p>
 *
 * @param defaultAddressing 协议级默认寻址方式（未取到 variant 或未声明细化时使用）
 * @param byVariant         形态变体键 → 寻址方式；空表表示不做细分
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionAddressingPolicy(SubscriptionAddressing defaultAddressing,
        Map<String, SubscriptionAddressing> byVariant) {

    /**
     * 紧凑构造器：归一化可空字段并保证不变式。
     */
    public SubscriptionAddressingPolicy {
        defaultAddressing = defaultAddressing == null
                ? SubscriptionAddressing.DEFAULT : defaultAddressing;
        byVariant = byVariant == null ? Map.of() : Map.copyOf(byVariant);
    }

    /**
     * 只有协议级默认值、不做细分的策略。
     *
     * <p>用于「既有实现」与「不区分形态的协议」：行为和上一轮的单一
     * {@code subscriptionAddressing} 字段逐字等价。</p>
     *
     * @param addressing 寻址方式
     * @return 策略
     */
    public static SubscriptionAddressingPolicy of(SubscriptionAddressing addressing) {
        return new SubscriptionAddressingPolicy(addressing, Map.of());
    }

    /**
     * 未声明任何订阅寻址信息的策略（保守侧）。
     *
     * @return 策略
     */
    public static SubscriptionAddressingPolicy unspecified() {
        return new SubscriptionAddressingPolicy(SubscriptionAddressing.DEFAULT, Map.of());
    }

    /**
     * 解析某个 variant 键对应的寻址方式。
     *
     * <p>解析顺序：① 有 variant 且命中细化表 ⇒ 用表内值；② 有 variant 但未命中 ⇒
     * <b>保守侧</b>并标记为「未命中」（调用方据此 WARN）；③ 无 variant ⇒ 协议级默认值。</p>
     *
     * <p>注意 ② 与 ③ 的差异是刻意的：未命中意味着「协议说了它分形态，但当前形态没声明」，
     * 这种状态说明配置或声明漏了，不能静默当成「协议级默认」处理。</p>
     *
     * @param variant 当前形态变体键；{@code null}/空串表示取不到形态
     * @return 解析结果（恒非空）
     */
    public Resolution resolve(@Nullable String variant) {
        if (variant == null || variant.isBlank()) {
            return new Resolution(defaultAddressing, false, false);
        }
        if (byVariant.isEmpty()) {
            // 未声明细化：variant 无关，直接用协议级默认（与旧行为逐字一致）
            return new Resolution(defaultAddressing, false, false);
        }
        SubscriptionAddressing mapped = byVariant.get(variant);
        if (mapped != null) {
            return new Resolution(mapped, true, false);
        }
        // 声明了细化但当前 variant 未声明：兜底保守侧 + 让调用方 WARN（绝不静默）
        return new Resolution(SubscriptionAddressing.PER_ADDRESS, true, true);
    }

    /**
     * 便捷方法：只取寻址方式（不关心是否为未命中）。
     *
     * @param variant 当前形态变体键
     * @return 寻址方式（恒非空）
     */
    public SubscriptionAddressing addressingFor(@Nullable String variant) {
        return resolve(variant).addressing();
    }

    /**
     * 是否声明了细分表。
     *
     * @return 声明了至少一个 variant 时返回 {@code true}
     */
    public boolean hasVariants() {
        return !byVariant.isEmpty();
    }

    /**
     * 校验 variant 表本身是否自洽（装配期使用）。
     *
     * @return 键都非空且值都非空时返回 {@code true}
     */
    public boolean isValid() {
        return byVariant.entrySet().stream()
                .allMatch(entry -> entry.getKey() != null && !entry.getKey().isBlank()
                        && entry.getValue() != null);
    }

    /**
     * 解析结果：寻址方式 + 是否命中 + 是否需要 WARN。
     *
     * @param addressing   解析出的寻址方式（恒非空）
     * @param variantKnown 是否声明了细化表并命中（或声明了细化但未命中时也为 {@code true}）
     * @param variantMiss  声明了细化表但当前 variant 未声明
     * @author wenbin
     * @since 2026-10-18
     */
    public record Resolution(SubscriptionAddressing addressing, boolean variantKnown,
            boolean variantMiss) {

        /**
         * 紧凑构造器：寻址方式恒非空。
         */
        public Resolution {
            addressing = addressing == null ? SubscriptionAddressing.DEFAULT : addressing;
        }
    }

    /**
     * 形态变体解析器：由协议实现，从运行时上下文读出「当前设备属于哪个形态」。
     *
     * <p><b>为什么放在协议侧</b>：判据可能是 {@code DeviceSpec.properties()} 里的键、
     * 适配器构造参数或设备地址的一部分——只有协议自己知道该看哪里。
     * 框架只消费返回的稳定键去查 {@link #byVariant()}。</p>
     *
     * <p><b>为什么只收 {@code DeviceSpec}</b>：形态经常是<b>适配器级配置</b>（如 TCP 的
     * {@code payload-format}），协议实现可在 lambda 里直接捕获自己的配置字段，无需框架传上下文；
     * 而<b>设备级</b>差异走 {@code DeviceSpec.properties()} 即可覆盖。
     * 这样解析器签名最窄，编排器也不必持有 {@code AdapterContext}。</p>
     *
     * <p><b>契约</b>：返回值必须是<b>稳定、可枚举</b>的键（与协议在
     * {@link SubscriptionAddressingPolicy#byVariant()} 里声明的键同一套字面量）；
     * 取不到形态时返回 {@code null}（框架按协议级默认值处理），
     * <b>不得</b>抛异常——形态未知是正常状态（宿主可能没配），不是故障。</p>
     *
     * @author wenbin
     * @since 2026-10-18
     */
    @FunctionalInterface
    public interface VariantResolver {

        /**
         * 解析当前形态变体键。
         *
         * @param device 设备规格
         * @return 形态变体键；取不到时返回 {@code null}
         */
        @Nullable
        String resolveVariant(DeviceSpec device);
    }

    /**
     * 便捷工厂：构造一个「协议级默认 + 单个 variant 覆盖」的策略。
     *
     * @param defaultAddressing 协议级默认
     * @param variant           形态变体键
     * @param variantAddressing 该形态的寻址方式
     * @return 策略
     */
    public static SubscriptionAddressingPolicy of(SubscriptionAddressing defaultAddressing,
            String variant, SubscriptionAddressing variantAddressing) {
        Objects.requireNonNull(variant, "variant must not be null");
        return new SubscriptionAddressingPolicy(defaultAddressing,
                Map.of(variant, variantAddressing));
    }

    /**
     * 取某个 variant 的声明值（不做兜底）。
     *
     * @param variant 形态变体键
     * @return 声明值；未声明时为空 Optional
     */
    public Optional<SubscriptionAddressing> declaredFor(String variant) {
        if (variant == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byVariant.get(variant));
    }
}
