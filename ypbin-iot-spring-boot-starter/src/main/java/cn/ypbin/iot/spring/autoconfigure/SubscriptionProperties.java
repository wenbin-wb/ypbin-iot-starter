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

import cn.ypbin.iot.spring.subscription.SubscriptionBackoffPolicy;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 订阅编排配置（{@code ypbin.iot.subscription.*}）。
 *
 * <p><b>为什么退避是配置而不是常量</b>：订阅失败的合理重试节奏取决于<b>设备侧的代价</b>——
 * 电池设备、受限网关不能被每 30 秒敲一次；而强实时场景希望更快重试。
 * 框架默认值只保证「开箱可用且不会打爆设备」，具体节奏由宿主按现场调整。</p>
 *
 * <p>非法配置<b>不会让应用起不来</b>（这是可降级能力），但会被归一化并在装配期 WARN，
 * 绝不让「配了不生效」静默发生。</p>
 *
 * @param backoff 失败退避参数
 * @author wenbin
 * @since 2026-10-18
 */
@ConfigurationProperties(prefix = SubscriptionProperties.PREFIX)
public record SubscriptionProperties(BackoffProperties backoff) {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.iot.subscription";

    /**
     * 紧凑构造器：缺失时用默认值。
     */
    public SubscriptionProperties {
        backoff = backoff == null ? BackoffProperties.defaults() : backoff;
    }

    /**
     * 全默认配置。
     *
     * @return 配置
     */
    public static SubscriptionProperties defaults() {
        return new SubscriptionProperties(BackoffProperties.defaults());
    }

    /**
     * 失败退避参数：按「设备 + 会话实例」生效。
     *
     * <p><b>为什么紧凑构造器不做归一化</b>：装配层需要在归一化<b>之前</b>判断「配置是否非法」，
     * 才能打 WARN 指出被修正的项。若构造器直接覆盖非法值，事后无法区分「本来合法」与「被修正过」，
     * 「配了不生效」就又变成静默的。因此这里保留原始（可空）值，由访问器给出归一化后的非空契约，
     * 由 {@link #isValidInput()} 给出原始值是否合法。</p>
     *
     * @param initialDelay 首次失败后的退避时长（默认 30s）
     * @param maxDelay     退避上限（默认 2min）
     * @param multiplier   每次失败后的放大倍率（默认 2.0；&lt; 1 会被归一化为默认值）
     * @author wenbin
     * @since 2026-10-18
     */
    public record BackoffProperties(@Nullable Duration initialDelay, @Nullable Duration maxDelay,
            @Nullable Double multiplier) {

        /** 默认初始退避。 */
        public static final Duration DEFAULT_INITIAL_DELAY = SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY;

        /** 默认退避上限。 */
        public static final Duration DEFAULT_MAX_DELAY = SubscriptionBackoffPolicy.DEFAULT_MAX_DELAY;

        /** 默认放大倍率。 */
        public static final double DEFAULT_MULTIPLIER = SubscriptionBackoffPolicy.DEFAULT_MULTIPLIER;

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static BackoffProperties defaults() {
            return new BackoffProperties(DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MULTIPLIER);
        }

        /**
         * 原始配置值是否合法（归一化之前判定）。
         *
         * @return 三个值都合法时返回 {@code true}；未配置（{@code null}）视为合法
         */
        public boolean isValidInput() {
            if (initialDelay == null && maxDelay == null && multiplier == null) {
                // 完全未配置 = 用默认值，不是错误配置
                return true;
            }
            return SubscriptionBackoffPolicy.isValidInput(initialDelay(), maxDelay(), multiplier());
        }

        /**
         * 归一化后的初始退避。
         *
         * <p>组件声明 {@code @Nullable} 是因为<b>构造参数</b>允许为空（Spring 绑定会传空）；
         * 访问器给出<b>非空契约</b>，否则可空性会扩散到所有消费点（NullAway 会在装配类上报违规）。</p>
         *
         * @return 初始退避（恒非空）
         */
        @Override
        public Duration initialDelay() {
            return initialDelay == null ? DEFAULT_INITIAL_DELAY : initialDelay;
        }

        /**
         * 归一化后的退避上限。
         *
         * @return 退避上限（恒非空）
         */
        @Override
        public Duration maxDelay() {
            return maxDelay == null ? DEFAULT_MAX_DELAY : maxDelay;
        }

        /**
         * 归一化后的放大倍率。
         *
         * @return 放大倍率（恒非空）
         */
        @Override
        public Double multiplier() {
            return multiplier == null ? DEFAULT_MULTIPLIER : multiplier;
        }
    }
}
