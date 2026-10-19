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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.model.DeviceSpec;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅寻址策略测试：协议级默认 + 按形态变体细分（issue #37 技术债反哺）。
 *
 * <p>核心不变量（每条都对应一个真实故障形态）：</p>
 * <ol>
 *   <li>未声明细分 ⇒ 行为与「只有单一协议级值」逐字一致（<b>向后兼容</b>）；</li>
 *   <li>声明细分且命中 ⇒ 用表内值（同一协议不同形态分别正确）；</li>
 *   <li>声明细分但形态未命中 ⇒ 兜底 <b>保守侧</b> 并标记 {@code variantMiss}
 *       （不能回落协议级默认值——那可能就是 BATCH，会把未知形态当支持多地址）；</li>
 *   <li>取不到形态（解析器为 null 或返回 null）⇒ 协议级默认值，不算未命中。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class SubscriptionAddressingPolicyTest {

    private static final String KEY_VALUE = "key-value";

    private static final String BINARY = "binary";

    // ------------------------------------------------------------------
    // 策略本身
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SAP-01 未声明细分：resolve 一律给出协议级默认值，且不标记未命中")
    void policyWithoutVariantsMustUseDefault() {
        SubscriptionAddressingPolicy policy =
                SubscriptionAddressingPolicy.of(SubscriptionAddressing.BATCH);

        assertThat(policy.hasVariants()).isFalse();
        SubscriptionAddressingPolicy.Resolution withVariant = policy.resolve(BINARY);
        assertThat(withVariant.addressing()).isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(withVariant.variantKnown()).isFalse();
        assertThat(withVariant.variantMiss()).as("未声明细化不算未命中").isFalse();

        assertThat(policy.resolve(null).addressing()).isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(policy.resolve("  ").addressing()).isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(policy.addressingFor(KEY_VALUE)).isEqualTo(SubscriptionAddressing.BATCH);
    }

    @Test
    @DisplayName("SAP-02 声明细分且命中：按形态给出各自寻址方式")
    void declaredVariantMustBeResolved() {
        SubscriptionAddressingPolicy policy = new SubscriptionAddressingPolicy(
                SubscriptionAddressing.PER_ADDRESS,
                Map.of(KEY_VALUE, SubscriptionAddressing.BATCH,
                        BINARY, SubscriptionAddressing.PER_ADDRESS));

        assertThat(policy.hasVariants()).isTrue();
        SubscriptionAddressingPolicy.Resolution keyValue = policy.resolve(KEY_VALUE);
        assertThat(keyValue.addressing()).isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(keyValue.variantKnown()).isTrue();
        assertThat(keyValue.variantMiss()).isFalse();

        SubscriptionAddressingPolicy.Resolution binary = policy.resolve(BINARY);
        assertThat(binary.addressing()).isEqualTo(SubscriptionAddressing.PER_ADDRESS);
        assertThat(binary.variantMiss()).isFalse();
    }

    @Test
    @DisplayName("SAP-03 声明细分但形态未命中：必须兜底保守侧并标记未命中（绝不回落协议级默认值）")
    void undeclaredVariantMustFallBackToConservativeSide() {
        // 关键：协议级默认故意设为 BATCH —— 若实现「未命中回落协议级默认」，
        // 这个用例就会拿到 BATCH 并转红，正是我们想要的保护
        SubscriptionAddressingPolicy policy = new SubscriptionAddressingPolicy(
                SubscriptionAddressing.BATCH, Map.of(KEY_VALUE, SubscriptionAddressing.BATCH));

        SubscriptionAddressingPolicy.Resolution miss = policy.resolve("some-unknown-format");
        assertThat(miss.addressing())
                .as("未知形态必须兜底保守侧，不能把「不知道」当成「支持多地址」")
                .isEqualTo(SubscriptionAddressing.PER_ADDRESS);
        assertThat(miss.variantKnown()).isTrue();
        assertThat(miss.variantMiss())
                .as("未命中必须可被调用方识别，否则会静默降级")
                .isTrue();
    }

    @Test
    @DisplayName("SAP-04 声明了细分但取不到形态（null/空串）：走协议级默认值，不算未命中")
    void missingVariantMustUseDefaultNotMiss() {
        SubscriptionAddressingPolicy policy = new SubscriptionAddressingPolicy(
                SubscriptionAddressing.BATCH, Map.of(KEY_VALUE, SubscriptionAddressing.BATCH));

        SubscriptionAddressingPolicy.Resolution unknown = policy.resolve(null);
        assertThat(unknown.addressing())
                .as("取不到形态属正常（宿主可能没配），不是「声明漏了」").isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(unknown.variantMiss()).isFalse();

        assertThat(policy.resolve("").variantMiss()).isFalse();
        assertThat(policy.resolve("   ").variantMiss()).isFalse();
    }

    @Test
    @DisplayName("SAP-05 工厂与不变式：null 归一、集合不可变、declaredFor 不做兜底")
    void factoriesMustProduceImmutablePolicies() {
        SubscriptionAddressingPolicy unspecified = SubscriptionAddressingPolicy.unspecified();
        assertThat(unspecified.defaultAddressing()).isEqualTo(SubscriptionAddressing.DEFAULT);
        assertThat(unspecified.byVariant()).isEmpty();

        SubscriptionAddressingPolicy single = SubscriptionAddressingPolicy.of(
                SubscriptionAddressing.PER_ADDRESS, KEY_VALUE, SubscriptionAddressing.BATCH);
        assertThat(single.byVariant()).containsEntry(KEY_VALUE, SubscriptionAddressing.BATCH);
        assertThat(single.declaredFor(KEY_VALUE)).contains(SubscriptionAddressing.BATCH);
        assertThat(single.declaredFor("nope"))
                .as("declaredFor 只看声明、不做兜底").isEmpty();
        assertThat(single.declaredFor(null)).isEmpty();

        // null 归一：默认值回落保守侧、variant 表归一为空表
        SubscriptionAddressingPolicy normalized = new SubscriptionAddressingPolicy(null, null);
        assertThat(normalized.defaultAddressing()).isEqualTo(SubscriptionAddressing.DEFAULT);
        assertThat(normalized.byVariant()).isEmpty();

        // 集合不可变
        Map<String, SubscriptionAddressing> mutable = new LinkedHashMap<>();
        mutable.put(KEY_VALUE, SubscriptionAddressing.BATCH);
        SubscriptionAddressingPolicy fromMutable =
                new SubscriptionAddressingPolicy(SubscriptionAddressing.PER_ADDRESS, mutable);
        mutable.put(BINARY, SubscriptionAddressing.BATCH);
        assertThat(fromMutable.byVariant())
                .as("构造时防御性拷贝，外部改动不得影响策略").doesNotContainKey(BINARY);

        assertThat(single.isValid()).isTrue();
    }

    // ------------------------------------------------------------------
    // 描述符接线
    // ------------------------------------------------------------------

    @Test
    @DisplayName("SAP-06 描述符向后兼容：10 参与 11 参旧构造器都必须可用，且语义为「未声明」")
    void legacyConstructorsMustStayCompatible() {
        ProtocolDescriptor tenArgs = new ProtocolDescriptor(ProtocolCode.of("tcp"), "TCP", "", "",
                "TCP", Set.of(), Set.of(), "", "", Map.of());
        assertThat(tenArgs.subscriptionAddressing()).isEqualTo(SubscriptionAddressing.DEFAULT);
        assertThat(tenArgs.addressingPolicy().hasVariants()).isFalse();
        assertThat(tenArgs.variantResolver()).isNull();

        ProtocolDescriptor elevenArgs = new ProtocolDescriptor(ProtocolCode.of("tcp"), "TCP", "", "",
                "TCP", Set.of(), Set.of(), "", "", Map.of(),
                SubscriptionAddressing.BATCH);
        assertThat(elevenArgs.subscriptionAddressing()).isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(elevenArgs.addressingPolicy().defaultAddressing())
                .isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(elevenArgs.addressingPolicy().hasVariants()).isFalse();
        assertThat(elevenArgs.variantResolver()).isNull();
    }

    @Test
    @DisplayName("SAP-07 描述符：builder 未声明细分时策略与单选值一致（0.4.x 行为逐字不变）")
    void builderWithoutVariantsMustMatchSingleValue() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("modbus")).name("Modbus")
                .subscriptionAddressing(SubscriptionAddressing.BATCH).build();

        assertThat(descriptor.subscriptionAddressing()).isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(descriptor.addressingPolicy().hasVariants()).isFalse();
        assertThat(descriptor.resolveAddressing(device()).addressing())
                .isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(descriptor.resolveAddressing(device()).variantMiss()).isFalse();
    }

    @Test
    @DisplayName("SAP-08 描述符：声明细分 + 形态解析器时，按设备解析出各自寻址方式")
    void descriptorMustResolveVariantFromResolver() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("tcp")).name("TCP")
                .subscriptionAddressing(SubscriptionAddressing.PER_ADDRESS)
                .subscriptionAddressingByVariant(Map.of(KEY_VALUE, SubscriptionAddressing.BATCH))
                .variantResolver(device -> device.properties().get("format"))
                .build();

        DeviceSpec keyValueDevice = deviceWith("format", KEY_VALUE);
        assertThat(descriptor.resolveAddressing(keyValueDevice).addressing())
                .isEqualTo(SubscriptionAddressing.BATCH);
        assertThat(descriptor.resolveAddressing(keyValueDevice).variantMiss()).isFalse();

        // 形态未在表内 ⇒ 兜底保守侧 + variantMiss
        DeviceSpec unknownDevice = deviceWith("format", "unknown-format");
        SubscriptionAddressingPolicy.Resolution miss = descriptor.resolveAddressing(unknownDevice);
        assertThat(miss.addressing()).isEqualTo(SubscriptionAddressing.PER_ADDRESS);
        assertThat(miss.variantMiss()).isTrue();

        // 取不到形态 ⇒ 协议级默认，不算未命中
        DeviceSpec noFormat = device();
        assertThat(descriptor.resolveAddressing(noFormat).addressing())
                .isEqualTo(SubscriptionAddressing.PER_ADDRESS);
        assertThat(descriptor.resolveAddressing(noFormat).variantMiss()).isFalse();
    }

    @Test
    @DisplayName("SAP-09 描述符：形态解析器抛异常时必须退化为协议级默认而不是让订阅整轮失败")
    void throwingResolverMustDegradeToDefault() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("tcp")).name("TCP")
                .subscriptionAddressing(SubscriptionAddressing.PER_ADDRESS)
                .subscriptionAddressingByVariant(Map.of(KEY_VALUE, SubscriptionAddressing.BATCH))
                .variantResolver(new SubscriptionAddressingPolicy.VariantResolver() {
                    @Override
                    public @Nullable String resolveVariant(DeviceSpec device) {
                        throw new IllegalStateException("resolver bug by design");
                    }
                })
                .build();

        SubscriptionAddressingPolicy.Resolution resolution = descriptor.resolveAddressing(device());
        assertThat(resolution.addressing())
                .as("解析器崩溃不能让订阅整轮失败，退化为协议级默认")
                .isEqualTo(SubscriptionAddressing.PER_ADDRESS);
        assertThat(resolution.variantMiss()).isFalse();
    }

    @Test
    @DisplayName("SAP-10 描述符：声明了细分但没给解析器时，按协议级默认处理（不误判为未命中）")
    void variantsWithoutResolverMustUseDefault() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("tcp")).name("TCP")
                .subscriptionAddressing(SubscriptionAddressing.PER_ADDRESS)
                .subscriptionAddressingByVariant(Map.of(KEY_VALUE, SubscriptionAddressing.BATCH))
                .build();

        assertThat(descriptor.variantResolver()).isNull();
        SubscriptionAddressingPolicy.Resolution resolution = descriptor.resolveAddressing(device());
        assertThat(resolution.addressing()).isEqualTo(SubscriptionAddressing.PER_ADDRESS);
        assertThat(resolution.variantMiss()).isFalse();
    }

    @Test
    @DisplayName("SAP-11 描述符：策略默认值与单选值不一致时以策略为准（避免同一事实两个值）")
    void policyDefaultMustWinOverSingleValue() {
        // 规范构造器同时收到两个入口时，必须收敛成一个事实：策略是更完整的声明
        ProtocolDescriptor descriptor = new ProtocolDescriptor(ProtocolCode.of("tcp"), "TCP", "", "",
                "TCP", Set.of(), Set.of(), "", "", Map.of(),
                SubscriptionAddressing.PER_ADDRESS,
                new SubscriptionAddressingPolicy(SubscriptionAddressing.BATCH,
                        Map.of(KEY_VALUE, SubscriptionAddressing.BATCH)),
                null);

        assertThat(descriptor.subscriptionAddressing())
                .as("单选值必须被策略的协议级默认值回写，否则同一描述符会有两个互相矛盾的答案")
                .isEqualTo(SubscriptionAddressing.BATCH);
    }

    @Test
    @DisplayName("SAP-12 描述符：variant 表声明后不可被外部修改（防御性拷贝）")
    void descriptorMustCopyVariantMap() {
        Map<String, SubscriptionAddressing> mutable = new LinkedHashMap<>();
        mutable.put(KEY_VALUE, SubscriptionAddressing.BATCH);
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("tcp")).name("TCP")
                .subscriptionAddressingByVariant(mutable)
                .build();
        mutable.put(BINARY, SubscriptionAddressing.BATCH);

        assertThat(descriptor.addressingPolicy().byVariant()).doesNotContainKey(BINARY);
    }

    // ------------------------------------------------------------------
    // 帮助方法
    // ------------------------------------------------------------------

    private static DeviceSpec device() {
        return new DeviceSpec("dev-1", "设备", ProtocolCode.of("tcp"), "conn-1", "",
                Duration.ZERO, Map.of());
    }

    private static DeviceSpec deviceWith(String key, String value) {
        return new DeviceSpec("dev-1", "设备", ProtocolCode.of("tcp"), "conn-1", "",
                Duration.ZERO, Map.of(key, value));
    }
}
