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

import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.spring.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 指标实现的装配选择测试。
 *
 * <p>这里验证的是**选择逻辑**：有 {@link MeterRegistry} 时必须用 Micrometer 实现，
 * 没有时必须回退到无操作实现。两者都用「不抛异常」是测不出来的 ——
 * 一个永远返回 Noop 的实现也能让上下文启动成功。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
class IotMicrometerAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotMicrometerAutoConfiguration.class,
                    IotAutoConfiguration.class));

    @Test
    @DisplayName("METCFG-01 存在 MeterRegistry 时必须装配 Micrometer 实现（不能回退成 Noop）")
    void micrometerMustBeSelectedWhenRegistryPresent() {
        runner.withUserConfiguration(RegistryConfiguration.class).run(context -> {
            assertThat(context).hasSingleBean(MetricsRecorder.class);
            assertThat(context.getBean(MetricsRecorder.class))
                    .as("有 MeterRegistry 却回退成 Noop —— 指标会被静默丢弃")
                    .isInstanceOf(MicrometerMetricsRecorder.class);
        });
    }

    @Test
    @DisplayName("METCFG-02 没有 MeterRegistry 时必须回退到无操作实现")
    void noopMustBeUsedWhenRegistryAbsent() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(MetricsRecorder.class);
            assertThat(context.getBean(MetricsRecorder.class))
                    .isInstanceOf(NoopMetricsRecorder.class);
        });
    }

    @Test
    @DisplayName("METCFG-03 宿主自带的 MetricsRecorder 必须优先（不得被框架覆盖）")
    void hostRecorderMustWin() {
        runner.withUserConfiguration(RegistryConfiguration.class, HostRecorderConfiguration.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(MetricsRecorder.class);
                    assertThat(context.getBean(MetricsRecorder.class))
                            .as("框架不得覆盖宿主显式提供的实现")
                            .isSameAs(HostRecorderConfiguration.HOST_RECORDER);
                });
    }

    @Test
    @DisplayName("METCFG-04 即使把 Noop 那个装配类排在前面，Micrometer 版仍必须胜出（验证 before 语义）")
    void declarationOrderMustBeGuaranteedByBeforeAttribute() {
        // 这条用例的存在原因：METCFG-01 显式按「正确顺序」传入两个配置类，
        // 因此它**证明不了**顺序保证 —— 把 AutoConfiguration.imports 的行序反转，
        // METCFG-01 照样绿（已用变异验证）。真正被依赖的是 @AutoConfiguration(before=...)，
        // 这里用**反序**装配来钉住它。
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class,
                        IotMicrometerAutoConfiguration.class))
                .withUserConfiguration(RegistryConfiguration.class)
                .run(context -> assertThat(context.getBean(MetricsRecorder.class))
                        .as("反序装配下若回退成 Noop，说明顺序保证并不来自 before 属性")
                        .isInstanceOf(MicrometerMetricsRecorder.class));
    }

    /**
     * 提供 MeterRegistry 的测试配置。
     *
     * @author wenbin
     * @since 2026-09-15
     */
    @Configuration(proxyBeanMethods = false)
    static class RegistryConfiguration {

        @Bean
        MeterRegistry meterRegistry() {
            return new SimpleMeterRegistry();
        }
    }

    /**
     * 宿主自带指标实现的测试配置。
     *
     * @author wenbin
     * @since 2026-09-15
     */
    @Configuration(proxyBeanMethods = false)
    static class HostRecorderConfiguration {

        static final MetricsRecorder HOST_RECORDER = NoopMetricsRecorder.INSTANCE;

        @Bean
        MetricsRecorder hostMetricsRecorder() {
            return HOST_RECORDER;
        }
    }
}
