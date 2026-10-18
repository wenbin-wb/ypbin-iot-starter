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
package cn.ypbin.iot.spring.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import cn.ypbin.iot.spring.autoconfigure.SubscriptionAutoConfiguration;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 订阅编排的装配测试（issue #37 验收标准 2）。
 *
 * <p>断言四件事，缺一即「框架给了个取不到/改不动的承诺」：</p>
 * <ol>
 *   <li>自动配置在 {@code AutoConfiguration.imports} 里登记（否则静默不装配，AGENTS R1）；</li>
 *   <li>默认装配提供 {@link SubscriptionOrchestrator}，且它是可用的默认实现；</li>
 *   <li>宿主提供自己的 {@link SubscriptionOrchestrator} Bean 时框架<b>让位</b>
 *       （{@link ConditionalOnMissingBean} 真的生效，AGENTS R2）；</li>
 *   <li>配置项 {@code ypbin.iot.subscription.backoff.*} 真的被绑定到退避策略上。</li>
 * </ol>
 *
 * <p>第 1 条刻意用反射读 imports 文件而不是「装配能跑起来就算过」：
 * 后者在模块被宿主直接 {@code @Import} 时也成立，无法证明自动配置被发现。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class SubscriptionOrchestratorWiringTest {

    /** 自动配置登记文件（Boot 4 的唯一发现路径）。 */
    private static final String AUTO_CONFIGURATION_IMPORTS =
            "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class,
                    SubscriptionAutoConfiguration.class))
            .withPropertyValues("ypbin.iot.devices.enabled=false");

    @Test
    @DisplayName("ORCHW-01 自动配置必须登记在 AutoConfiguration.imports（否则模块引入后静默不装配）")
    void autoConfigurationMustBeRegistered() throws IOException {
        // 直接读 imports 资源：这是 Boot 4 发现自动配置**唯一**的契约文件
        // （spring.factories 路径已被禁用，见 AGENTS R1）。
        // 只用 AutoConfigurations.of(...) 跑通不算证据——宿主显式 @Import 时也能通过，
        // 无法证明「自动配置会被发现」。
        List<String> registrations;
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(AUTO_CONFIGURATION_IMPORTS)) {
            assertThat(input).as("classpath 上必须有 %s", AUTO_CONFIGURATION_IMPORTS).isNotNull();
            registrations = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))
                    .lines().map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
        }
        assertThat(registrations)
                .as("SubscriptionAutoConfiguration 必须登记，否则宿主引入后订阅编排静默不生效")
                .contains(SubscriptionAutoConfiguration.class.getName());
    }

    @Test
    @DisplayName("ORCHW-02 默认装配必须提供 SubscriptionOrchestrator（且是框架默认实现）")
    void defaultAssemblyMustProvideOrchestrator() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SubscriptionOrchestrator.class);
            assertThat(context.getBean(SubscriptionOrchestrator.class))
                    .isInstanceOf(DefaultSubscriptionOrchestrator.class);
            assertThat(context).hasSingleBean(ProtocolDescriptorSource.class);
            assertThat(context).hasSingleBean(SubscriptionBackoffPolicy.class);
        });
    }

    @Test
    @DisplayName("ORCHW-03 宿主提供自己的 SubscriptionOrchestrator Bean 时框架必须让位（可被覆盖）")
    void hostOrchestratorMustOverrideDefault() {
        runner.withUserConfiguration(HostOrchestratorConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(SubscriptionOrchestrator.class))
                    .as("@ConditionalOnMissingBean 必须让宿主的实现生效")
                    .isSameAs(HostOrchestratorConfiguration.HOST);
        });
    }

    @Test
    @DisplayName("ORCHW-04 ypbin.iot.subscription.backoff.* 必须真的绑定到退避策略")
    void backoffPropertiesMustBeBound() {
        runner.withPropertyValues(
                "ypbin.iot.subscription.backoff.initial-delay=3s",
                "ypbin.iot.subscription.backoff.max-delay=17s",
                "ypbin.iot.subscription.backoff.multiplier=3.0").run(context -> {
            assertThat(context).hasNotFailed();
            SubscriptionBackoffPolicy policy = context.getBean(SubscriptionBackoffPolicy.class);
            assertThat(policy.initialDelay()).isEqualTo(Duration.ofSeconds(3));
            assertThat(policy.maxDelay()).isEqualTo(Duration.ofSeconds(17));
            assertThat(policy.multiplier()).isEqualTo(3.0D);
        });
    }

    @Test
    @DisplayName("ORCHW-05 非法退避配置不得阻断启动（归一化 + WARN），启动后策略仍是安全值")
    void illegalBackoffMustNotBreakStartup() {
        runner.withPropertyValues(
                "ypbin.iot.subscription.backoff.initial-delay=0s",
                "ypbin.iot.subscription.backoff.max-delay=0s",
                "ypbin.iot.subscription.backoff.multiplier=0.1").run(context -> {
            assertThat(context).as("可降级能力的配置错误不应让应用起不来").hasNotFailed();
            SubscriptionBackoffPolicy policy = context.getBean(SubscriptionBackoffPolicy.class);
            assertThat(policy.initialDelay()).isEqualTo(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY);
            assertThat(policy.maxDelay()).isEqualTo(SubscriptionBackoffPolicy.DEFAULT_MAX_DELAY);
            assertThat(policy.multiplier()).isEqualTo(SubscriptionBackoffPolicy.DEFAULT_MULTIPLIER);
        });
    }

    @Test
    @DisplayName("ORCHW-06 描述符来源必须接到已注册的适配器上（宿主不用自己再声明一遍）")
    void descriptorSourceMustReadFromAdapterRegistry() {
        runner.withUserConfiguration(StubAdapterConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            ProtocolDescriptorSource source = context.getBean(ProtocolDescriptorSource.class);
            assertThat(source.find(StubAdapterConfiguration.CODE))
                    .as("描述符来源必须能查到已注册适配器的声明（否则编排器永远走保守侧）")
                    .isPresent()
                    .get()
                    .extracting(ProtocolDescriptor::subscriptionAddressing)
                    .isEqualTo(SubscriptionAddressing.BATCH);
            assertThat(source.find(ProtocolCode.of("not-registered"))).isEmpty();
        });
    }

    /**
     * 宿主自定义编排器（验证可覆盖）。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    @Configuration(proxyBeanMethods = false)
    static class HostOrchestratorConfiguration {

        static final SubscriptionOrchestrator HOST = new SubscriptionOrchestrator() {

            @Override
            public CompletionStage<SubscriptionResult> subscribe(SubscriptionBatchItem item) {
                return CompletableFuture.completedFuture(
                        SubscriptionResult.skipped(item.device().deviceId(), SubscriptionStatus.NO_ADDRESSES));
            }

            @Override
            public void forget(String deviceId) {
                // 宿主实现（测试桩）
            }
        };

        @Bean
        SubscriptionOrchestrator hostSubscriptionOrchestrator() {
            return HOST;
        }
    }

    /**
     * 最小适配器：只提供描述符，用于验证描述符来源的接线。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    @Configuration(proxyBeanMethods = false)
    static class StubAdapterConfiguration {

        static final ProtocolCode CODE = ProtocolCode.of("stub");

        @Bean
        ProtocolAdapter stubProtocolAdapter() {
            return new ProtocolAdapter() {

                private final ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                        .code(CODE)
                        .name("Stub")
                        .capabilities(ProtocolCapability.SUBSCRIBE_NATIVE)
                        .subscriptionAddressing(SubscriptionAddressing.BATCH)
                        .build();

                @Override
                public ProtocolDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public CompletionStage<ProtocolConnection> open(ConnectionSpec spec,
                        AdapterContext context) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
                }
            };
        }
    }

}
