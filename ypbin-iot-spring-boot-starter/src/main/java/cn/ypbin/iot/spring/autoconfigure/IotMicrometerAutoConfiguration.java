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

import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.spring.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer 指标桥装配。
 *
 * <p><b>为什么单独一个装配类 + {@code before}</b>：{@link IotAutoConfiguration} 里有一个
 * {@code @ConditionalOnMissingBean} 的「无操作」回退。若两者写在同一个类里，
 * 谁先生效取决于方法声明顺序（不保证），Micrometer 版可能被回退挡掉。
 * 拆成独立类并用 {@code @AutoConfiguration(before = IotAutoConfiguration.class)} 声明顺序，
 * Micrometer 桥先注册，回退自然让位（`METCFG-04` 用反序装配验证了这一点）。</p>
 *
 * <p><strong>为什么不判 {@code @ConditionalOnBean(MeterRegistry.class)}（UP-11 根因）</strong>：
 * {@code @ConditionalOnBean} 在<b>装配期</b>（解析 bean 定义时）求值，只计入「到此为止已注册的
 * bean 定义」，因此对自动配置的先后顺序极其敏感。Boot 4.x 里定义 {@code MeterRegistry} 的是
 * {@code CompositeMeterRegistryAutoConfiguration}；{@code MetricsAutoConfiguration}
 * 只定义 {@code Clock} / {@code MeterRegistryPostProcessor} / {@code PropertiesMeterFilter}，
 * <b>并不定义 {@code MeterRegistry}</b>。所以把本装配锚在 {@code afterName=MetricsAutoConfiguration}
 * 之后依然可能比 {@code CompositeMeterRegistryAutoConfiguration} 先求值条件 —— 宿主
 * 有 actuator（{@code /actuator/metrics} 正常）却仍回退 Noop，框架指标全部丢弃
 * （真实宿主 ypbin-access 每次启动都打 WARN，见 METCFG-05 回归用例）。</p>
 *
 * <p><b>修法：不依赖任何 Boot 自动配置名单，改用 {@link ObjectProvider} 在 bean 实例化期解析
 * {@code MeterRegistry}</b>。实例化发生在所有自动配置的 bean 定义注册完毕之后，因此结果与
 * 自动配置顺序无关：有 registry ⇒ 必然绑定 Micrometer 实现；确实没有 ⇒ 本类回退 no-op
 * 并打 WARN（不静默降级）。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
@AutoConfiguration(before = IotAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
public class IotMicrometerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotMicrometerAutoConfiguration.class);

    /**
     * 指标门面：有 {@link MeterRegistry} 时绑定 Micrometer 实现，否则回退无操作实现并打提示。
     *
     * @param meterRegistries 容器中的指标注册表（实例化期解析，与自动配置顺序无关）
     * @return 指标门面
     */
    @Bean
    @ConditionalOnMissingBean(MetricsRecorder.class)
    public MetricsRecorder iotMetricsRecorder(ObjectProvider<MeterRegistry> meterRegistries) {
        MeterRegistry registry = meterRegistries.getIfAvailable();
        if (registry == null) {
            // 不静默降级：指标被丢弃是宿主应当知情的事，必须打 WARN 而不是 debug。
            log.warn("[ypbin-iot] micrometer 在 classpath 上但没有可用的 MeterRegistry bean —— "
                    + "iotMetricsRecorder 回退到 no-op，框架指标（read/write/subscription/errors）全部丢弃。"
                    + "请检查 Boot 的指标自动配置为何未生效（如 actuator 依赖缺失、"
                    + "CompositeMeterRegistryAutoConfiguration 被排除，或未引入任何指标导出器）。");
            return NoopMetricsRecorder.INSTANCE;
        }
        log.info("[ypbin-iot] iotMetricsRecorder bound to Micrometer ({}).",
                registry.getClass().getSimpleName());
        return new MicrometerMetricsRecorder(registry);
    }
}
