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
package cn.ypbin.iot.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.CredentialResolver;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.runtime.egress.EgressRouter;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.support.SpringFactoriesLoader;
import org.springframework.mock.env.MockEnvironment;

/**
 * 装配层测试：验证「默认装配 / 开关关闭 / 宿主覆盖 / 无适配器」四种场景。
 *
 * <p>这是 DESIGN §3.5 与 PROTOCOLS §7.3 的 M0 验收项之一。核心断言是
 * <b>条件装配真的按条件生效</b>，以及 <b>每个 Bean 都可被宿主覆盖</b>。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class IotAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class));

    @Test
    @DisplayName("CFG-01 默认装配必须提供调度器/出口/连接注册中心/适配器注册中心")
    void defaultAutoConfigurationMustProvideCoreBeans() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(TaskScheduler.class);
            assertThat(context).hasSingleBean(DataEgress.class);
            assertThat(context).hasSingleBean(ConnectionRegistry.class);
            assertThat(context).hasSingleBean(AdapterRegistry.class);
            assertThat(context).hasSingleBean(CredentialResolver.class);
            assertThat(context).hasSingleBean(IotLifecycle.class);
        });
    }

    @Test
    @DisplayName("CFG-02 总开关关闭时整个框架不得装配")
    void disabledSwitchMustSkipEverything() {
        runner.withPropertyValues("ypbin.iot.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(TaskScheduler.class);
            assertThat(context).doesNotHaveBean(DataEgress.class);
            assertThat(context).doesNotHaveBean(AdapterRegistry.class);
        });
    }

    @Test
    @DisplayName("CFG-03 宿主自定义 Bean 必须覆盖框架默认实现")
    void hostBeansMustOverrideDefaults() {
        runner.withUserConfiguration(HostOverrideConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(CredentialResolver.class))
                    .isSameAs(HostOverrideConfiguration.HOST_RESOLVER);
            assertThat(context.getBean(DataEgress.class)).isSameAs(HostOverrideConfiguration.HOST_EGRESS);
            assertThat(context).hasSingleBean(CredentialResolver.class);
        });
    }

    @Test
    @DisplayName("CFG-04 无适配器时框架仍可启动（注册表为空）")
    void noAdapterMustStillStart() {
        runner.withPropertyValues("ypbin.iot.devices.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            AdapterRegistry registry = context.getBean(AdapterRegistry.class);
            assertThat(registry.descriptors()).isEmpty();
            assertThat(registry.protocolCodes()).isEmpty();
        });
    }

    @Test
    @DisplayName("CFG-05 注册的适配器必须被注册中心收录且能取到上下文")
    void adapterMustBeRegisteredWithContext() {
        runner.withUserConfiguration(AdapterConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            AdapterRegistry registry = context.getBean(AdapterRegistry.class);
            assertThat(registry.protocolCodes()).containsExactly(ProtocolCode.of("stub"));
            assertThat(registry.find(ProtocolCode.of("stub"))).isPresent();
            assertThat(registry.contextOf(ProtocolCode.of("stub"))).isPresent();
            AdapterContext adapterContext = registry.contextOf(ProtocolCode.of("stub")).orElseThrow();
            assertThat(adapterContext.protocol()).isEqualTo(ProtocolCode.of("stub"));
            assertThat(adapterContext.settings().maxConnections()).isPositive();
        });
    }

    @Test
    @DisplayName("CFG-06 协议级配置必须生效到适配器上下文")
    void protocolPropertiesMustReachAdapterContext() {
        runner.withUserConfiguration(AdapterConfiguration.class)
                .withPropertyValues(
                        "ypbin.iot.protocol.stub.connect-timeout=7s",
                        "ypbin.iot.protocol.stub.max-connections=42",
                        "ypbin.iot.protocol.stub.extended.unit-id=3")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AdapterRegistry registry = context.getBean(AdapterRegistry.class);
                    AdapterContext adapterContext = registry.contextOf(ProtocolCode.of("stub")).orElseThrow();
                    assertThat(adapterContext.settings().connectTimeout())
                            .isEqualTo(Duration.ofSeconds(7));
                    assertThat(adapterContext.settings().maxConnections()).isEqualTo(42);
                    assertThat(adapterContext.settings().find("unit-id", Integer.class)).contains(3);
                });
    }

    @Test
    @DisplayName("CFG-07 出口 Bean 必须是 EgressRouter 且参数来自配置")
    void egressMustBeConfiguredFromProperties() {
        runner.withPropertyValues("ypbin.iot.egress.batch-size=512", "ypbin.iot.egress.queue-capacity=2048")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(DataEgress.class)).isInstanceOf(EgressRouter.class);
                });
    }

    @Test
    @DisplayName("CFG-08 EnvironmentPostProcessor 必须能被 SpringFactoriesLoader 发现（父仓陷阱）")
    void environmentPostProcessorMustBeDiscoverable() {
        // 母仓教训：Boot 4.1 仍兼容旧注册键，但默认值会静默失效。
        // 源码扫描只能证明「键写对了」，加载一遍才能证明「Boot 真的找得到」。
        // 必须传「失败处理器」：classpath 上还有框架与第三方注册的 EP，其中部分无法用
        // 无参构造实例化（例如依赖 DeferredLogFactory），不提供处理器会直接抛异常，
        // 导致整个断言失败在「加载」这一步而不是「是否注册」这一步。
        List<String> failures = new ArrayList<>();
        List<String> discovered = SpringFactoriesLoader
                .forDefaultResourceLocation()
                .load(EnvironmentPostProcessor.class, SpringFactoriesLoader.ArgumentResolver.none(),
                        (factoryType, factoryName, failure) -> failures.add(factoryName))
                .stream()
                .map(processor -> processor.getClass().getName())
                .toList();
        assertThat(discovered)
                .as("注册键必须是 org.springframework.boot.EnvironmentPostProcessor")
                .contains(IotDefaultsEnvironmentPostProcessor.class.getName());
        assertThat(failures)
                .as("本仓的处理器必须能无参实例化，否则注册了也装配不上")
                .noneMatch(name -> name.startsWith("cn.ypbin."));
    }

    @Test
    @DisplayName("CFG-09 默认值注入必须把本仓消息 basename 追加到 spring.messages.basename")
    void defaultsMustAppendMessageBasename() {
        IotDefaultsEnvironmentPostProcessor processor = new IotDefaultsEnvironmentPostProcessor();
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("spring.messages.basename", "messages,extra");
        processor.postProcessEnvironment(environment, null);
        ConfigurableEnvironment configurable = environment;
        assertThat(configurable.getProperty(IotDefaultsEnvironmentPostProcessor.MESSAGES_BASENAME_KEY,
                String.class))
                .isEqualTo("messages,extra," + IotDefaultsEnvironmentPostProcessor.MESSAGES_BASENAME);
        assertThat(processor.getOrder()).isLessThan(Integer.MAX_VALUE);
    }

    /**
     * 宿主覆盖配置。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    @Configuration(proxyBeanMethods = false)
    static class HostOverrideConfiguration {

        static final CredentialResolver HOST_RESOLVER = reference -> Optional.empty();

        static final DataEgress HOST_EGRESS = new DataEgress() {

            @Override
            public void emit(DataBatch batch) {
                // 宿主实现
            }

            @Override
            public void emit(DeviceEvent event) {
                // 宿主实现
            }
        };

        @Bean
        CredentialResolver hostCredentialResolver() {
            return HOST_RESOLVER;
        }

        @Bean
        DataEgress hostDataEgress() {
            return HOST_EGRESS;
        }
    }

    /**
     * 测试用适配器配置。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    @Configuration(proxyBeanMethods = false)
    static class AdapterConfiguration {

        @Bean
        ProtocolAdapter stubAdapter() {
            return new StubAdapter();
        }
    }

    /**
     * 最小适配器桩。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    static final class StubAdapter implements ProtocolAdapter {

        private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("stub"))
                .name("Stub")
                .transport("TCP")
                .capabilities(ProtocolCapability.READ)
                .build();

        @Override
        public ProtocolDescriptor descriptor() {
            return DESCRIPTOR;
        }

        @Override
        public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
        }

        @Override
        public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.completedFuture(ProbeResult.reachable(DESCRIPTOR, Map.of()));
        }
    }
}
