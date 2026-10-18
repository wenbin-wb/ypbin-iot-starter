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
package cn.ypbin.iot.core.util;

import java.time.Duration;
import java.util.Objects;

/**
 * 指数退避纯函数：{@code base × 2^(attempt-1)}，封顶 {@code max}。
 *
 * <p><b>为什么框架要提供它</b>（issue #39 c）：宿主侧此前各自手写
 * {@code base.toMillis() * (1L << min(attempts - 1, 20))}，两处实现的边界处理已经分叉
 * （一处 {@code attempts - 1}、另一处 {@code Math.max(attempts, 1) - 1}；一处封顶、另一处没有），
 * 这类「各写一遍必然分叉」的重复实现应当上收到框架，由一处实现 + 一组边界用例固定语义。</p>
 *
 * <p><b>语义（带边界定义，不是「大概指数」）</b>：</p>
 * <ul>
 *   <li>{@code attempt} 是「第几次重试」，从 1 开始；{@code attempt = 0} 按 1 处理
 *       （调用方常用「已失败次数字段初值 0」直接传入，不应报错）；</li>
 *   <li>结果为 {@code min(initial × 2^(min(attempt, 1..) - 1), max)}，即第 1 次重试等 {@code initial}，
 *       之后每次翻倍，超过 {@code max} 后恒为 {@code max}；</li>
 *   <li>{@code initial > max} 时直接返回 {@code max}（上限优先，避免「配置写反后实际退避更短」的意外）；</li>
 *   <li>指数用 2^20 封顶，且乘法先按 {@code max} 做溢出前置判断 —— {@code attempt = Integer.MAX_VALUE}
 *       （可能是对端持续失败数天）也不会溢出成负值、不会回绕成「退避变 0 就忙循环」。</li>
 * </ul>
 *
 * <p><b>纯函数</b>：无状态、无 IO、无线程，可在任意模块（含 {@code iot-core}）复用；
 * 抖动（jitter）不在此实现——抖动依赖随机源与「只向更长方向抖」等策略，
 * 由需要它的调度器自行叠加（框架内 {@code ConnectionReconnector} 即如此）。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public final class ExponentialBackoff {

    /**
     * 指数上限：2^20 次翻倍（约 100 万倍）。
     *
     * <p>取 20 与宿主历史实现一致：{@code long} 左移 20 位不会溢出，
     * 而 {@code initial} 最小时（1ms）也足以覆盖到「已远超 max」的区间，
     * 再大的指数对结果没有意义（早已被 {@code max} 封顶）。</p>
     */
    private static final int MAX_EXPONENT = 20;

    private ExponentialBackoff() {
    }

    /**
     * 计算第 {@code attempt} 次重试的退避时长。
     *
     * @param attempt 第几次重试（从 1 开始；0 按 1 处理）
     * @param initial 首次重试的退避时长（必须为正）
     * @param max     退避上限（必须为正）
     * @return 本次退避时长，不小于 {@code initial}、不大于 {@code max}
     * @throws IllegalArgumentException {@code attempt} 为负、或 {@code initial}/{@code max} 非正
     */
    public static Duration nextDelay(int attempt, Duration initial, Duration max) {
        Objects.requireNonNull(initial, "initial must not be null");
        Objects.requireNonNull(max, "max must not be null");
        if (attempt < 0) {
            throw new IllegalArgumentException("attempt must not be negative: " + attempt);
        }
        if (initial.isZero() || initial.isNegative()) {
            throw new IllegalArgumentException("initial must be positive: " + initial);
        }
        if (max.isZero() || max.isNegative()) {
            throw new IllegalArgumentException("max must be positive: " + max);
        }
        // 上限优先：配置写反（initial > max）时不得返回比 max 更长的等待
        if (initial.compareTo(max) >= 0) {
            return max;
        }
        int exponent = Math.max(attempt, 1) - 1;
        // 唯一一处「指数封顶」实现（issue #39 验收：全仓只应命中这一行）
        long multiplier = 1L << Math.min(exponent, MAX_EXPONENT);
        long initialMillis = initial.toMillis();
        long maxMillis = max.toMillis();
        // 溢出前置判断：initialMillis * multiplier 可能超出 long，也必然超出 max，直接封顶
        if (initialMillis > maxMillis / multiplier) {
            return max;
        }
        long millis = initialMillis * multiplier;
        return millis >= maxMillis ? max : Duration.ofMillis(millis);
    }

    /**
     * 计算第 {@code attempt} 次重试的退避毫秒数（{@link #nextDelay} 的毫秒版本，便于直接喂给调度器）。
     *
     * @param attempt 第几次重试（从 1 开始；0 按 1 处理）
     * @param initial 首次重试的退避时长（必须为正）
     * @param max     退避上限（必须为正）
     * @return 退避毫秒数
     */
    public static long nextDelayMillis(int attempt, Duration initial, Duration max) {
        return nextDelay(attempt, initial, max).toMillis();
    }
}
