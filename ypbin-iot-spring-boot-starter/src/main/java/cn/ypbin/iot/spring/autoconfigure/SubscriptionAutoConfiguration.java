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

import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.spring.subscription.DefaultSubscriptionOrchestrator;
import cn.ypbin.iot.spring.subscription.ProtocolDescriptorSource;
import cn.ypbin.iot.spring.subscription.SubscriptionBackoffPolicy;
import cn.ypbin.iot.spring.subscription.SubscriptionOrchestrator;
import java.time.Clock;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * 订阅编排自动配置（issue #37）。
 *
 * <p>把「订阅编排」作为框架能力装配出来：宿主不再需要自己写重订、部分成功补订、退避与在途去重。
 * 所有 Bean 都带 {@link ConditionalOnMissingBean}，宿主可整体替换（AGENTS R2）。</p>
 *
 * <p><b>{@code @ConditionalOnClass} 的取舍</b>：本装配类只依赖本仓自己的类（
 * {@code SubscriptionOrchestrator} 等），按 AGENTS R3 的要求，条件注解必须指向<b>外部协议库</b>的类，
 * 不得指向本模块的类（后者恒为真、形同虚设）。而订阅编排是<b>协议无关</b>的框架能力，
 * 没有任何外部协议库是它的前置条件，因此这里<b>刻意不加</b> {@code @ConditionalOnClass}：
 * 用 {@code @ConditionalOnProperty(ypbin.iot.enabled)} 与依赖注入（{@code AdapterRegistry}
 * 本身由 {@link IotAutoConfiguration} 保证）表达前置条件，语义更准确。</p>
 *
 * <p><b>顺序</b>：声明 {@code after = IotAutoConfiguration.class}，因为默认实现需要注入
 * {@code AdapterRegistry} 与 {@code Clock}（两者由 {@code IotAutoConfiguration} 提供）。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
@AutoConfiguration(after = IotAutoConfiguration.class)
@ConditionalOnProperty(prefix = IotProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(SubscriptionProperties.class)
public class SubscriptionAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SubscriptionAutoConfiguration.class);

    /**
     * 协议描述符来源：接到适配器注册中心上。
     *
     * <p>编排器只认这个最小接口，从而能在不起 Spring 容器的条件下单测协议矩阵；
     * 这里负责把生产环境的真实来源接上。</p>
     *
     * @param adapterRegistry 适配器注册中心
     * @return 描述符来源
     */
    @Bean
    @ConditionalOnMissingBean
    public ProtocolDescriptorSource iotProtocolDescriptorSource(AdapterRegistry adapterRegistry) {
        log.debug("[ypbin-iot] iotProtocolDescriptorSource configured from AdapterRegistry.");
        // AdapterRegistry.find 给的是适配器实例；编排器只关心「协议怎么声明订阅」，
        // 因此这里映射到描述符，避免把整个适配器泄漏进订阅编排的契约
        return code -> adapterRegistry.find(code).map(ProtocolAdapter::descriptor);
    }

    /**
     * 订阅退避参数：把配置项归一化为纯函数策略，并在被修正时 WARN。
     *
     * <p>非法值不阻断启动（可降级能力），但必须可见——「配了不生效」是本仓反复出现的事故形态。</p>
     *
     * @param properties 配置
     * @return 退避参数
     */
    @Bean
    @ConditionalOnMissingBean
    public SubscriptionBackoffPolicy iotSubscriptionBackoffPolicy(SubscriptionProperties properties) {
        SubscriptionProperties.BackoffProperties backoff = properties.backoff();
        SubscriptionBackoffPolicy policy = new SubscriptionBackoffPolicy(backoff.initialDelay(),
                backoff.maxDelay(), backoff.multiplier());
        if (!backoff.isValidInput()) {
            log.warn("[ypbin-iot] ypbin.iot.subscription.backoff.* 配置非法（initial-delay={}, max-delay={}, "
                            + "multiplier={}）；已归一化为 initial-delay={}, max-delay={}, multiplier={}。"
                            + "要求：两个时长均为正数且 max-delay >= initial-delay，multiplier >= {}。",
                    backoff.initialDelay(), backoff.maxDelay(), backoff.multiplier(),
                    policy.initialDelay(), policy.maxDelay(), policy.multiplier(),
                    SubscriptionBackoffPolicy.MIN_MULTIPLIER);
        }
        log.debug("[ypbin-iot] iotSubscriptionBackoffPolicy configured (initialDelay={}, maxDelay={}, "
                + "multiplier={}).", policy.initialDelay(), policy.maxDelay(), policy.multiplier());
        return policy;
    }

    /**
     * 订阅编排器：默认实现，宿主提供自己的 Bean 即整体替换。
     *
     * @param descriptors   协议描述符来源
     * @param backoffPolicy 退避参数
     * @param clock         时钟（与框架其它组件共用一个，便于测试替换）
     * @return 订阅编排器
     */
    @Bean
    @ConditionalOnMissingBean
    public SubscriptionOrchestrator iotSubscriptionOrchestrator(ProtocolDescriptorSource descriptors,
            SubscriptionBackoffPolicy backoffPolicy, Clock clock) {
        Objects.requireNonNull(descriptors, "descriptors must not be null");
        Objects.requireNonNull(backoffPolicy, "backoffPolicy must not be null");
        log.debug("[ypbin-iot] iotSubscriptionOrchestrator configured (backoff initial={}, max={}).",
                backoffPolicy.initialDelay(), backoffPolicy.maxDelay());
        return new DefaultSubscriptionOrchestrator(descriptors, backoffPolicy, clock);
    }
}
