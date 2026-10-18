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

import java.time.Duration;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 订阅失败退避参数（纯数据 + 纯算术，无状态、无时钟、可单测）。
 *
 * <p><b>为什么退避按「设备 + 会话实例」而不是全局</b>：全局退避会让一台坏设备把其它设备的订阅
 * 节奏一起拖慢；而只按设备退避又会在框架重连（换会话实例）后继续把「设备已恢复」挡在窗口外。
 * 会话实例变化即视为新上下文、退避作废，是与重连节奏对齐的唯一可靠判据。</p>
 *
 * <p><b>为什么默认起点比建链退避长</b>：订阅失败通常是「协议层接受了调用但远端拒绝/超时」，
 * 立即重试只会把同样的失败再打一遍；而建链失败可以更快重试（网络抖动恢复快）。</p>
 *
 * @param initialDelay 首次失败后的退避时长
 * @param maxDelay     退避上限（指数增长到此为止）
 * @param multiplier   每次失败后的放大倍率
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionBackoffPolicy(Duration initialDelay, Duration maxDelay, double multiplier) {

    /** 默认初始退避。 */
    public static final Duration DEFAULT_INITIAL_DELAY = Duration.ofSeconds(30);

    /** 默认退避上限。 */
    public static final Duration DEFAULT_MAX_DELAY = Duration.ofMinutes(2);

    /** 默认放大倍率。 */
    public static final double DEFAULT_MULTIPLIER = 2.0D;

    /** 放大倍率下限：小于 1 会让退避越试越短，等于没有退避。 */
    public static final double MIN_MULTIPLIER = 1.0D;

    /**
     * 紧凑构造器：归一化非法值而不是抛异常。
     *
     * <p>配置错误不该让应用起不来（这是可降级的能力），但必须<b>可见</b>——由
     * {@link SubscriptionAutoConfiguration} 在装配时打 WARN 指出被修正的项。</p>
     */
    public SubscriptionBackoffPolicy {
        initialDelay = initialDelay == null || initialDelay.isNegative() || initialDelay.isZero()
                ? DEFAULT_INITIAL_DELAY : initialDelay;
        maxDelay = maxDelay == null || maxDelay.isNegative() || maxDelay.isZero()
                ? DEFAULT_MAX_DELAY : maxDelay;
        multiplier = multiplier < MIN_MULTIPLIER ? DEFAULT_MULTIPLIER : multiplier;
        // 上限不得小于初始值：否则「指数退避」第一步就被钳到比初始还短，语义颠倒
        if (maxDelay.compareTo(initialDelay) < 0) {
            maxDelay = initialDelay;
        }
    }

    /**
     * 全默认参数。
     *
     * @return 参数
     */
    public static SubscriptionBackoffPolicy defaults() {
        return new SubscriptionBackoffPolicy(DEFAULT_INITIAL_DELAY, DEFAULT_MAX_DELAY, DEFAULT_MULTIPLIER);
    }

    /**
     * 第 {@code attempt} 次失败后的退避时长（{@code attempt} 从 1 开始）。
     *
     * <p>纯函数：同样的 {@code attempt} 永远给出同样的值（不含抖动——抖动由调用方按需叠加，
     * 否则单测无法断言「上限」）。指数用位移实现并钳到 2^20，避免 {@code attempt} 很大时溢出成负数。</p>
     *
     * @param attempt 连续失败次数，≥ 1
     * @return 退避时长（不超过 {@link #maxDelay()}）
     */
    public Duration delayFor(int attempt) {
        if (attempt <= 1) {
            return initialDelay;
        }
        double raw = initialDelay.toMillis() * Math.pow(multiplier, attempt - 1);
        if (raw >= maxDelay.toMillis()) {
            return maxDelay;
        }
        return Duration.ofMillis(Math.max(initialDelay.toMillis(), (long) raw));
    }

    /**
     * 校验「原始配置值」是否合法（归一化之前调用）。
     *
     * <p><b>必须在校验前调用</b>：紧凑构造器已把非法值替换成默认值，事后无法再从字段区分
     * 「本来合法」与「被修正过」。装配层因此先用本方法判定，再决定是否打 WARN。</p>
     *
     * @param initialDelay 原始初始退避（可为 {@code null}）
     * @param maxDelay     原始上限（可为 {@code null}）
     * @param multiplier   原始倍率（可为 {@code null}）
     * @return 三个值都合法时返回 {@code true}
     */
    public static boolean isValidInput(Duration initialDelay, Duration maxDelay,
            @Nullable Double multiplier) {
        if (initialDelay == null || initialDelay.isZero() || initialDelay.isNegative()) {
            return false;
        }
        if (maxDelay == null || maxDelay.isZero() || maxDelay.isNegative()) {
            return false;
        }
        if (maxDelay.compareTo(initialDelay) < 0) {
            return false;
        }
        return multiplier != null && multiplier >= MIN_MULTIPLIER;
    }

    /**
     * 断言参数非空（用于构造点明确失败而非静默取默认）。
     *
     * @param policy 参数
     * @return 原参数
     */
    public static SubscriptionBackoffPolicy requireNonNull(SubscriptionBackoffPolicy policy) {
        return Objects.requireNonNull(policy, "policy must not be null");
    }
}
