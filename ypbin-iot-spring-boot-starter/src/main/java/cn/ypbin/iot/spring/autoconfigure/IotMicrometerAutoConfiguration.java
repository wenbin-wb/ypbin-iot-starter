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
import cn.ypbin.iot.spring.metrics.MicrometerMetricsRecorder;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Micrometer 指标桥装配。
 *
 * <p><b>为什么单独一个装配类 + {@code before}</b>：{@link IotAutoConfiguration} 里有一个
 * {@code @ConditionalOnMissingBean} 的「无操作」回退。若两者写在同一个类里，
 * 谁先生效取决于方法声明顺序（不保证），Micrometer 版可能被回退挡掉。
 * 拆成独立类并用 {@code @AutoConfiguration(before = IotAutoConfiguration.class)} 声明顺序 ——
 * <b>顺序由这个属性保证，与 {@code AutoConfiguration.imports} 的文件行序无关</b>
 * （`METCFG-04` 用反序装配验证了这一点）。</p>
 *
 * <p>仅在宿主已提供 {@link MeterRegistry}（例如引入 actuator）时生效；
 * 否则由 {@link IotAutoConfiguration} 回退到无操作实现并打 INFO 提示。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
@AutoConfiguration(before = IotAutoConfiguration.class)
@ConditionalOnClass(MeterRegistry.class)
public class IotMicrometerAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotMicrometerAutoConfiguration.class);

    /**
     * 基于 Micrometer 的指标实现。
     *
     * @param registry 指标注册表
     * @return 指标门面
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean(MetricsRecorder.class)
    public MetricsRecorder iotMetricsRecorder(MeterRegistry registry) {
        log.info("[ypbin-iot] iotMetricsRecorder bound to Micrometer ({}).",
                registry.getClass().getSimpleName());
        return new MicrometerMetricsRecorder(registry);
    }
}
