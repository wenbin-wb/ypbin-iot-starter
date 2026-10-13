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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Arrays;
import java.util.Objects;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
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
 * <p><b>为什么 05/06/07 必须引入真实的 Boot 指标自动配置链</b>：
 * {@code @ConditionalOnBean(MeterRegistry.class)} 在**装配期**求值，只看「到此为止已注册的
 * bean 定义」。Boot 4.x 里定义 {@code MeterRegistry} 的是
 * {@link CompositeMeterRegistryAutoConfiguration}，而 {@link MetricsAutoConfiguration}
 * 只定义 {@code Clock} / 后处理器，**并不定义 {@code MeterRegistry}**。
 * 只用手写的 {@code @Configuration} 提供 registry（01/03/04）会让条件在「用户配置早于自动配置」
 * 的时序下恒为真，从而**测不出**这个顺序缺陷；必须让真实自动配置来提供 registry。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
class IotMicrometerAutoConfigurationTest {

    /** Boot 4.x 真实指标链：MetricsAutoConfiguration 提供 Clock，CompositeMeterRegistryAutoConfiguration
     *  定义 MeterRegistry，SimpleMetricsExportAutoConfiguration 给出一个具体的 SimpleMeterRegistry。 */
    private static final Class<?>[] BOOT_METRICS_CHAIN = {
            MetricsAutoConfiguration.class,
            CompositeMeterRegistryAutoConfiguration.class,
            SimpleMetricsExportAutoConfiguration.class
    };

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

    @Test
    @DisplayName("METCFG-05 真实 Boot 指标链下必须绑定 Micrometer（UP-11 回归；修复前本用例红）")
    void realBootMetricsChainMustBindMicrometer() {
        for (boolean iotFirst : new boolean[] {true, false}) {
            bootMetricsRunner(iotFirst).run(context -> {
                assertThat(context.getStartupFailure()).isNull();
                assertThat(context.getBeanProvider(MeterRegistry.class).getIfAvailable())
                        .as("真实 Boot 指标链本应提供 MeterRegistry（iotFirst=%s）", iotFirst)
                        .isNotNull();
                assertThat(context.getBean(MetricsRecorder.class))
                        .as("Boot 已定义 MeterRegistry 却回退成 Noop —— 框架指标会被全部丢弃（iotFirst=%s）",
                                iotFirst)
                        .isInstanceOf(MicrometerMetricsRecorder.class);
            });
        }
    }

    @Test
    @DisplayName("METCFG-06 记录一次 error 后 ypbin.iot.errors 计数 +1 且带 type 标签")
    void errorMustBeCountedWithTypeTag() {
        bootMetricsRunner(true).run(context -> {
            String type = "iot.tcp.selector.not-found";
            MetricsRecorder recorder = context.getBean(MetricsRecorder.class);
            recorder.recordError(type);

            MeterRegistry registry = context.getBean(MeterRegistry.class);
            Counter counter = Objects.requireNonNull(
                    registry.find("ypbin.iot.errors").tag("type", type).counter(),
                    "ypbin.iot.errors{type=" + type + "} 未注册");
            assertThat(counter.count()).isEqualTo(1.0d);

            recorder.recordError(type);
            assertThat(counter.count()).isEqualTo(2.0d);
        });
    }

    @Test
    @DisplayName("METCFG-07 Boot 只提供 Clock（无 MeterRegistry）时必须回退 no-op，不得绑定假指标")
    void bootClockWithoutRegistryFallsBackToNoop() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IotMicrometerAutoConfiguration.class,
                        IotAutoConfiguration.class, MetricsAutoConfiguration.class))
                .run(context -> {
                    assertThat(context.getStartupFailure()).isNull();
                    assertThat(context.getBeanProvider(MeterRegistry.class).getIfAvailable()).isNull();
                    assertThat(context.getBean(MetricsRecorder.class))
                            .isInstanceOf(NoopMetricsRecorder.class);
                });
    }

    private static ApplicationContextRunner bootMetricsRunner(boolean iotFirst) {
        Class<?>[] iot = {IotMicrometerAutoConfiguration.class, IotAutoConfiguration.class};
        Class<?>[] all = iotFirst ? concat(iot, BOOT_METRICS_CHAIN)
                : concat(BOOT_METRICS_CHAIN, iot);
        return new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(all));
    }

    private static Class<?>[] concat(Class<?>[] first, Class<?>[] second) {
        Class<?>[] result = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, result, first.length, second.length);
        return result;
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
