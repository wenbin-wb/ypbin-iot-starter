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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ExponentialBackoff} 边界测试。
 *
 * <p><b>为什么边界要逐个钉死</b>（issue #39 c）：同一段
 * {@code base × 2^(attempt-1)} 在业务仓被手写过两次，边界处理已经分叉
 * （{@code attempts - 1} vs {@code Math.max(attempts, 1) - 1}、一处封顶一处不封顶）。
 * 上收到框架后，指数的起点、封顶、溢出与「配置写反」都由本测试固定，
 * 宿主不用再各自猜测语义。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class ExponentialBackoffTest {

    private static final Duration INITIAL = Duration.ofSeconds(1);

    private static final Duration MAX = Duration.ofSeconds(10);

    @Test
    @DisplayName("BO-01 attempt=0 与 attempt=1 都返回 initial（已失败次数为 0 时不应报错）")
    void firstAttemptsMustEqualInitial() {
        assertThat(ExponentialBackoff.nextDelay(0, INITIAL, MAX))
                .as("调用方常用「已失败次数」字段初值 0 直接传入，必须等价于第 1 次")
                .isEqualTo(INITIAL);
        assertThat(ExponentialBackoff.nextDelay(1, INITIAL, MAX)).isEqualTo(INITIAL);
    }

    @Test
    @DisplayName("BO-02 之后每次翻倍，且封顶在 max")
    void delaysMustDoubleUntilCapped() {
        assertThat(ExponentialBackoff.nextDelay(2, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(2));
        assertThat(ExponentialBackoff.nextDelay(3, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(4));
        assertThat(ExponentialBackoff.nextDelay(4, INITIAL, MAX)).isEqualTo(Duration.ofSeconds(8));
        assertThat(ExponentialBackoff.nextDelay(5, INITIAL, MAX))
                .as("1s × 2^4 = 16s 超过上限 10s，必须封顶")
                .isEqualTo(MAX);
        assertThat(ExponentialBackoff.nextDelay(100, INITIAL, MAX)).isEqualTo(MAX);
    }

    @Test
    @DisplayName("BO-03 极大 attempt 不得溢出为负值或 0（否则退避会变成忙循环）")
    void hugeAttemptMustNotOverflow() {
        assertThat(ExponentialBackoff.nextDelay(Integer.MAX_VALUE, INITIAL, MAX))
                .as("对端持续失败数天后 attempt 会很大：溢出成负数会让 scheduleOnce 立刻触发")
                .isEqualTo(MAX);
        // 指数按 2^20 封顶：initial 为 1ms 时 1ms × 2^20 = 1_048_576ms ≈ 17.5 分钟
        // （小于 1 天上限，因此结果为该值）：关键是「正数 + 不超上限 + 可重复」
        Duration huge = ExponentialBackoff.nextDelay(Integer.MAX_VALUE, Duration.ofMillis(1),
                Duration.ofDays(1));
        assertThat(huge).isEqualTo(Duration.ofMillis(1L << 20));
        assertThat(huge.toMillis()).isPositive().isLessThanOrEqualTo(Duration.ofDays(1).toMillis());
        assertThat(ExponentialBackoff.nextDelay(Integer.MAX_VALUE, Duration.ofMillis(1),
                Duration.ofDays(1)))
                .as("纯函数：同一入参必须得出同一结果")
                .isEqualTo(ExponentialBackoff.nextDelay(Integer.MAX_VALUE, Duration.ofMillis(1),
                        Duration.ofDays(1)));
    }

    @Test
    @DisplayName("BO-04 initial > max 时以上限为准（配置写反不得让退避更长）")
    void initialGreaterThanMaxMustReturnMax() {
        assertThat(ExponentialBackoff.nextDelay(1, Duration.ofMinutes(5), Duration.ofSeconds(1)))
                .as("initial > max 时若返回 initial，实际退避会比声明上限更长")
                .isEqualTo(Duration.ofSeconds(1));
        assertThat(ExponentialBackoff.nextDelay(3, Duration.ofSeconds(2), Duration.ofSeconds(2)))
                .as("initial == max 时恒为 max")
                .isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    @DisplayName("BO-05 非法参数必须 fail-fast，不得静默返回 0 退避")
    void invalidArgumentsMustFailFast() {
        assertThatThrownBy(() -> ExponentialBackoff.nextDelay(-1, INITIAL, MAX))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempt");
        assertThatThrownBy(() -> ExponentialBackoff.nextDelay(1, Duration.ZERO, MAX))
                .as("initial 为 0 会退化成忙循环")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExponentialBackoff.nextDelay(1, INITIAL, Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExponentialBackoff.nextDelay(1, null, MAX))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> ExponentialBackoff.nextDelay(1, INITIAL, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("BO-06 nextDelayMillis 与 nextDelay 口径一致")
    void millisVariantMustMatchDuration() {
        assertThat(ExponentialBackoff.nextDelayMillis(3, INITIAL, MAX))
                .isEqualTo(ExponentialBackoff.nextDelay(3, INITIAL, MAX).toMillis())
                .isEqualTo(4_000L);
        assertThat(ExponentialBackoff.nextDelayMillis(0, Duration.ofMillis(250), Duration.ofSeconds(1)))
                .isEqualTo(250L);
    }
}
