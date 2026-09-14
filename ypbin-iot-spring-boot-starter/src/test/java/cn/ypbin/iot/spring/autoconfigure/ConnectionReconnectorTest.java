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

import cn.ypbin.iot.core.context.AdapterSettings;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 重连退避调度器测试。
 *
 * <p>重点：① 退避确实指数增长且有上限；② 同一条链路不会重复排定（避免重连风暴）；
 * ③ 取消后不得再发起重连；④ 恢复后必须清除状态；⑤ 关闭后一切停止。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class ConnectionReconnectorTest {

    private static final Duration INITIAL = Duration.ofMillis(30);

    private static final Duration MAX = Duration.ofMillis(120);

    private DefaultTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(2, 64);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    @DisplayName("RC-01 重连必须被真正执行，并在成功后清除状态")
    void reconnectMustRunAndClearState() {
        AtomicBoolean recovered = new AtomicBoolean(false);
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            recovered.set(true);
            return true;
        });
        try {
            reconnector.schedule("c1", settings());
            assertThat(reconnector.activeCount()).isEqualTo(1);
            awaitUntil(recovered::get, Duration.ofSeconds(3));
            awaitUntil(() -> reconnector.activeCount() == 0, Duration.ofSeconds(3));
            assertThat(reconnector.activeCount()).as("恢复后必须清除状态，否则永远不会再重连").isZero();
            assertThat(reconnector.totalAttempts()).isEqualTo(1);
            assertThat(reconnector.totalRecovered()).isEqualTo(1);
        } finally {
            reconnector.close();
        }
    }

    @Test
    @DisplayName("RC-02 失败必须指数退避且封顶在 maxDelay")
    void failureMustBackOffAndCap() {
        List<Long> timestamps = new CopyOnWriteArrayList<>();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            timestamps.add(System.nanoTime());
            return false;
        });
        try {
            reconnector.schedule("c1", settings());
            awaitUntil(() -> timestamps.size() >= 4, Duration.ofSeconds(10));
            assertThat(timestamps.size()).isGreaterThanOrEqualTo(4);
            long firstGap = (timestamps.get(1) - timestamps.get(0)) / 1_000_000L;
            long lastGap = (timestamps.get(timestamps.size() - 1)
                    - timestamps.get(timestamps.size() - 2)) / 1_000_000L;
            assertThat(lastGap)
                    .as("退避必须增长：首间隔 %d ms，末间隔 %d ms", firstGap, lastGap)
                    .isGreaterThan(firstGap);
            // 上限由 maxDelay 决定：末间隔不得超过 maxDelay（含抖动上限 1 倍）
            assertThat(lastGap)
                    .as("退避必须封顶在 maxDelay（含抖动）：%d ms", lastGap)
                    .isLessThanOrEqualTo(MAX.toMillis() * 2 + 200L);
            assertThat(reconnector.totalRecovered()).isZero();
        } finally {
            reconnector.close();
        }
    }

    @Test
    @DisplayName("RC-03 同一条链路重复 schedule 不得重复排定（防重连风暴）")
    void duplicateScheduleMustBeIgnored() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            attempts.incrementAndGet();
            return false;
        });
        try {
            for (int index = 0; index < 20; index++) {
                reconnector.schedule("c1", settings());
            }
            assertThat(reconnector.activeCount()).as("同链路只应有一条重连状态").isEqualTo(1);
            sleep(150L);
            assertThat(attempts.get())
                    .as("20 次 schedule 不应产生 20 次并发重连；实测 %d 次", attempts.get())
                    .isLessThanOrEqualTo(3);
        } finally {
            reconnector.close();
        }
    }

    @Test
    @DisplayName("RC-04 取消后不得再发起重连")
    void cancelMustStopReconnect() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            attempts.incrementAndGet();
            return false;
        });
        try {
            reconnector.schedule("c1", settings());
            assertThat(reconnector.cancel("c1")).isTrue();
            assertThat(reconnector.cancel("c1")).as("重复取消必须返回 false（幂等）").isFalse();
            int afterCancel = attempts.get();
            sleep(400L);
            assertThat(attempts.get())
                    .as("取消后不得再有重连尝试（取消前 %d 次，之后 %d 次）", afterCancel, attempts.get())
                    .isEqualTo(afterCancel);
            assertThat(reconnector.activeCount()).isZero();
        } finally {
            reconnector.close();
        }
    }

    @Test
    @DisplayName("RC-05 多条链路必须各自独立退避")
    void connectionsMustBackOffIndependently() {
        ConcurrentMap<String, AtomicInteger> counts = new ConcurrentHashMap<>();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            counts.computeIfAbsent(connectionId, ignored -> new AtomicInteger()).incrementAndGet();
            return false;
        });
        try {
            reconnector.schedule("good", settings());
            reconnector.schedule("bad", settings());
            reconnector.schedule("worse", settings());
            assertThat(reconnector.activeCount()).isEqualTo(3);
            awaitUntil(() -> counts.size() == 3 && counts.values().stream()
                    .allMatch(count -> count.get() >= 1), Duration.ofSeconds(5));
            // 取消其中一条不应影响其余两条
            assertThat(reconnector.cancel("bad")).isTrue();
            assertThat(reconnector.activeCount()).isEqualTo(2);
        } finally {
            reconnector.close();
        }
    }

    @Test
    @DisplayName("RC-06 重连动作抛异常不得让调度停摆")
    void throwingActionMustNotStopScheduling() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        try {
            reconnector.schedule("c1", settings());
            awaitUntil(() -> attempts.get() >= 3, Duration.ofSeconds(10));
            assertThat(attempts.get())
                    .as("动作抛异常必须被当作一次失败并继续退避，而不是让重连停摆")
                    .isGreaterThanOrEqualTo(3);
        } finally {
            reconnector.close();
        }
    }

    @Test
    @DisplayName("RC-07 关闭后不得再排定或执行重连")
    void closedMustStopEverything() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            attempts.incrementAndGet();
            return false;
        });
        reconnector.schedule("c1", settings());
        reconnector.close();
        assertThat(reconnector.activeCount()).isZero();
        int afterClose = attempts.get();
        reconnector.schedule("c2", settings());
        sleep(300L);
        assertThat(attempts.get())
                .as("关闭后不得再执行任何重连（关闭时 %d 次，之后 %d 次）", afterClose, attempts.get())
                .isEqualTo(afterClose);
        assertThat(reconnector.activeCount()).isZero();
        reconnector.close();
    }

    @Test
    @DisplayName("RC-08 非法退避参数必须回落到安全默认值")
    void invalidBackoffSettingsMustFallBack() {
        AtomicInteger attempts = new AtomicInteger();
        ConnectionReconnector reconnector = new ConnectionReconnector(scheduler, connectionId -> {
            attempts.incrementAndGet();
            return false;
        });
        try {
            // 零/负值/null 退避：必须回落，否则 scheduleOnce(0) 会变成忙循环
            AdapterSettings broken = new DefaultAdapterSettings(true, Duration.ZERO, Duration.ZERO,
                    Duration.ZERO, Duration.ZERO, Duration.ZERO, 5.0D, 8, 64, Map.of());
            // 注意：非法值必须回落到默认（初始 1s / 上限 60s），否则 scheduleOnce(0) 会忙循环
            reconnector.schedule("c1", broken);
            sleep(200L);
            assertThat(attempts.get())
                    .as("非法退避参数必须回落为安全默认值，实测 200ms 内重试 %d 次", attempts.get())
                    .isLessThanOrEqualTo(2);
        } finally {
            reconnector.close();
        }
    }

    private static AdapterSettings settings() {
        // 分量顺序：(enabled, connectTimeout, requestTimeout, keepAliveInterval,
        //           reconnectInitialDelay, reconnectMaxDelay, reconnectJitter, ...)
        return new DefaultAdapterSettings(true, Duration.ofSeconds(5), Duration.ofSeconds(5),
                Duration.ofSeconds(5), INITIAL, MAX, 0.0D, 8, 64, Map.of());
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(10L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
