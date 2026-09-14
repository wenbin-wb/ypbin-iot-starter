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
package cn.ypbin.iot.runtime.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 有界宿主回调投递器测试。
 *
 * <p>核心不变量：① dispatch 本身不得阻塞调用线程（它在协议线程上被调用）；
 * ② 过载时有界丢弃并计数，而不是无限堆积或把背压传回协议线程；
 * ③ 宿主回调抛异常不得影响后续投递；④ 关闭后一律计数丢弃。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class BoundedDeliveryDispatcherTest {

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
    @DisplayName("DD-01 回调必须在别的线程上执行，且 dispatch 本身不阻塞")
    void dispatchMustBeAsyncAndNonBlocking() throws Exception {
        BoundedDeliveryDispatcher dispatcher = new BoundedDeliveryDispatcher(scheduler, 16);
        CountDownLatch ran = new CountDownLatch(1);
        List<String> threadNames = new CopyOnWriteArrayList<>();
        String caller = Thread.currentThread().getName();
        long started = System.nanoTime();
        boolean accepted = dispatcher.dispatch(() -> {
            threadNames.add(Thread.currentThread().getName());
            ran.countDown();
        });
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();
        assertThat(accepted).isTrue();
        assertThat(elapsedMillis)
                .as("dispatch 在协议线程上被调用，绝不能阻塞（耗时 %d ms）", elapsedMillis)
                .isLessThan(100L);
        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(threadNames).hasSize(1);
        assertThat(threadNames.get(0)).as("回调不得在调用线程上执行").isNotEqualTo(caller);
        awaitUntil(() -> dispatcher.inFlight() == 0, Duration.ofSeconds(3));
    }

    @Test
    @DisplayName("DD-02 过载必须有界丢弃并计数，而不是无限堆积")
    void overloadMustDropAndCount() throws Exception {
        BoundedDeliveryDispatcher dispatcher = new BoundedDeliveryDispatcher(scheduler, 2);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(2);
        try {
            for (int index = 0; index < 2; index++) {
                assertThat(dispatcher.dispatch(() -> {
                    running.countDown();
                    awaitQuietly(release);
                })).isTrue();
            }
            assertThat(running.await(5, TimeUnit.SECONDS)).isTrue();
            for (int index = 0; index < 10; index++) {
                assertThat(dispatcher.dispatch(() -> { })).isFalse();
            }
            assertThat(dispatcher.droppedCount())
                    .as("过载必须被计数，而不是静默丢弃")
                    .isEqualTo(10L);
            assertThat(dispatcher.inFlight()).isEqualTo(2);
        } finally {
            release.countDown();
        }
        awaitUntil(() -> dispatcher.inFlight() == 0, Duration.ofSeconds(5));
        assertThat(dispatcher.maxInFlight()).isEqualTo(2);
    }

    @Test
    @DisplayName("DD-03 宿主回调抛异常不得影响后续投递")
    void hostExceptionMustNotBreakSubsequentDispatch() {
        BoundedDeliveryDispatcher dispatcher = new BoundedDeliveryDispatcher(scheduler, 16);
        dispatcher.dispatch(() -> {
            throw new IllegalStateException("host boom");
        });
        AtomicInteger delivered = new AtomicInteger();
        assertThat(dispatcher.dispatch(delivered::incrementAndGet)).isTrue();
        awaitUntil(() -> delivered.get() == 1, Duration.ofSeconds(3));
        assertThat(delivered.get()).as("前一次宿主异常不得阻断后续回调").isEqualTo(1);
        awaitUntil(() -> dispatcher.inFlight() == 0, Duration.ofSeconds(3));
        assertThat(dispatcher.droppedCount()).as("宿主异常不是丢弃，不应计入").isZero();
    }

    @Test
    @DisplayName("DD-04 关闭后一律计数丢弃，不得让宿主以为回调还在送达")
    void closedMustDropAndCount() {
        BoundedDeliveryDispatcher dispatcher = new BoundedDeliveryDispatcher(scheduler, 16);
        dispatcher.close();
        assertThat(dispatcher.dispatch(() -> { })).isFalse();
        assertThat(dispatcher.dispatch(() -> { })).isFalse();
        assertThat(dispatcher.droppedCount()).isEqualTo(2L);
        dispatcher.close();
    }

    @Test
    @DisplayName("DD-05 非法在途上限必须回落到默认值")
    void invalidCapacityMustFallBack() {
        assertThat(new BoundedDeliveryDispatcher(scheduler, 0).maxInFlight())
                .isEqualTo(BoundedDeliveryDispatcher.DEFAULT_CAPACITY);
        assertThat(new BoundedDeliveryDispatcher(scheduler, -5).maxInFlight())
                .isEqualTo(BoundedDeliveryDispatcher.DEFAULT_CAPACITY);
        assertThat(new BoundedDeliveryDispatcher(scheduler, 7).maxInFlight()).isEqualTo(7);
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleepQuietly(10L);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
