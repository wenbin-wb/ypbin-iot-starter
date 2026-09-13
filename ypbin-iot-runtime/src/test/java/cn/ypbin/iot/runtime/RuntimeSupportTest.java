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
package cn.ypbin.iot.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.BoundedAddressCache;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.DefaultResourceRegistry;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import cn.ypbin.iot.runtime.util.LruAddressCache;
import cn.ypbin.iot.runtime.util.SemanticVersion;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 运行时支撑组件测试：版本比较、配置门面、地址缓存、资源回收、调度器、上下文。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class RuntimeSupportTest {

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
    @DisplayName("SUP-01 SemanticVersion 解析与区间判断")
    void semanticVersionMustCompareAndMatchRange() {
        assertThat(SemanticVersion.parse("1.2.3")).isEqualTo(new SemanticVersion(1, 2, 3));
        assertThat(SemanticVersion.parse("1.2")).isEqualTo(new SemanticVersion(1, 2, 0));
        assertThat(SemanticVersion.parse("1")).isEqualTo(new SemanticVersion(1, 0, 0));
        assertThat(SemanticVersion.parse("1.2.3-SNAPSHOT")).isEqualTo(new SemanticVersion(1, 2, 3));
        assertThat(SemanticVersion.parse("1.2.3")).isGreaterThan(SemanticVersion.parse("1.2.2"));
        assertThat(SemanticVersion.parse("2.0.0")).isGreaterThan(SemanticVersion.parse("1.9.9"));

        SemanticVersion version = SemanticVersion.parse("0.1.0");
        assertThat(version.isWithin("0.1.0", "1.0.0")).isTrue();
        assertThat(version.isWithin("", "")).isTrue();
        assertThat(version.isWithin(null, null)).isTrue();
        assertThat(version.isWithin("0.2.0", "1.0.0")).isFalse();
        assertThat(version.isWithin("0.1.0", "0.1.0")).as("区间上界为开区间").isFalse();

        assertThatThrownBy(() -> SemanticVersion.parse("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticVersion.parse("a.b.c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SemanticVersion.parse(null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("SUP-02 DefaultAdapterSettings 默认值与类型化查表")
    void adapterSettingsMustProvideDefaultsAndTypedLookup() {
        DefaultAdapterSettings defaults = DefaultAdapterSettings.defaults();
        assertThat(defaults.enabled()).isTrue();
        assertThat(defaults.connectTimeout()).isEqualTo(DefaultAdapterSettings.DEFAULT_CONNECT_TIMEOUT);
        assertThat(defaults.maxPendingRequests()).isEqualTo(DefaultAdapterSettings.DEFAULT_MAX_PENDING_REQUESTS);
        assertThat(defaults.extended()).isEmpty();
        assertThat(defaults.find("absent", String.class)).isEmpty();
        assertThat(defaults.get("absent", String.class, "fallback")).isEqualTo("fallback");

        DefaultAdapterSettings settings = new DefaultAdapterSettings(true, Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofSeconds(1),
                0.1D, 10, 10, Map.of("unit-id", 1, "flag", true, "text", "abc", "timeout", "PT3S"));
        assertThat(settings.find("unit-id", Integer.class)).contains(1);
        assertThat(settings.find("flag", Boolean.class)).contains(true);
        assertThat(settings.find("text", String.class)).contains("abc");
        assertThat(settings.find("timeout", Duration.class)).contains(Duration.ofSeconds(3));
        assertThatThrownBy(() -> settings.find("unit-id", Duration.class))
                .as("类型无法转换必须抛出而不是静默返回空")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.find("text", Double.class))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("SUP-03 LruAddressCache 命中/淘汰/清空/容量校验")
    void lruCacheMustCacheAndEvict() {
        LruAddressCache<String> cache = new LruAddressCache<>(2);
        AtomicInteger loads = new AtomicInteger();
        Function<String, String> loader = raw -> {
            loads.incrementAndGet();
            return "parsed:" + raw;
        };
        assertThat(cache.get("a", loader)).isEqualTo("parsed:a");
        assertThat(cache.get("a", loader)).isEqualTo("parsed:a");
        assertThat(loads.get()).as("第二次命中不应再解析").isEqualTo(1);
        assertThat(cache.size()).isEqualTo(1);

        cache.get("b", loader);
        cache.get("c", loader);
        assertThat(cache.size()).as("超出容量必须淘汰").isEqualTo(2);

        cache.invalidateAll();
        assertThat(cache.size()).isZero();

        assertThatThrownBy(() -> new LruAddressCache<String>(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> cache.get("x", raw -> null))
                .as("解析器返回 null 必须报错而不是缓存 null")
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("SUP-04 ResourceRegistry 逆序释放且单资源失败不影响其余")
    void resourceRegistryMustReleaseInReverseOrderAndIsolateFailures() {
        DefaultResourceRegistry registry = new DefaultResourceRegistry();
        List<String> closed = new ArrayList<>();
        registry.register(new TrackingResource("first", closed, false));
        registry.register(new TrackingResource("second", closed, true));
        registry.register(new TrackingResource("third", closed, false));
        registry.unregister(registry.register(new TrackingResource("temp", closed, false)));

        registry.close();
        assertThat(closed).containsExactly("third", "second", "first");
        registry.close();
        assertThat(closed).as("重复 close 必须幂等").hasSize(3);
    }

    @Test
    @DisplayName("SUP-05 EnvCredentialResolver 只识别 env: 前缀")
    void credentialResolverMustOnlyHandleEnvPrefix() {
        EnvCredentialResolver resolver = new EnvCredentialResolver();
        assertThat(resolver.resolve(null)).isEmpty();
        assertThat(resolver.resolve("vault:secret")).isEmpty();
        assertThat(resolver.resolve("env:")).isEmpty();
        assertThat(resolver.resolve("env:YPBIN_IOT_ABSENT_VARIABLE")).isEmpty();

        String path = System.getenv("PATH");
        assertThat(path).as("测试环境必须有 PATH 变量").isNotNull();
        assertThat(resolver.resolve("env:PATH:admin")).hasValueSatisfying(credential -> {
            assertThat(credential.username()).isEqualTo("admin");
            assertThat(new String(credential.secret())).isEqualTo(path);
        });
        assertThat(resolver.resolve("env:PATH")).hasValueSatisfying(credential -> {
            assertThat(credential.username()).isEmpty();
            assertThat(credential.secret()).isNotEmpty();
            credential.wipe();
            assertThat(credential.secret()).containsOnly('\0');
        });
    }

    @Test
    @DisplayName("SUP-06 调度器周期任务、一次性任务、取消与关闭")
    void schedulerMustRunTasksAndIsolateFailures() throws Exception {
        AtomicInteger ticks = new AtomicInteger();
        TaskScheduler.ScheduledTask periodic = scheduler.schedule(ticks::incrementAndGet,
                Duration.ZERO, Duration.ofMillis(20));
        awaitUntil(() -> ticks.get() >= 2, Duration.ofSeconds(2));
        periodic.cancel();
        assertThat(periodic.isCancelled()).isTrue();

        AtomicInteger once = new AtomicInteger();
        TaskScheduler.ScheduledTask onceTask = scheduler.scheduleOnce(once::incrementAndGet, Duration.ZERO);
        awaitUntil(() -> once.get() == 1, Duration.ofSeconds(2));
        assertThat(once.get()).isEqualTo(1);
        assertThat(onceTask.isCancelled()).isFalse();

        // 抛异常的任务必须被隔离，不得中断调度器
        scheduler.scheduleOnce(() -> {
            throw new IllegalStateException("by design");
        }, Duration.ZERO);
        AtomicInteger afterFailure = new AtomicInteger();
        scheduler.scheduleOnce(afterFailure::incrementAndGet, Duration.ofMillis(50));
        awaitUntil(() -> afterFailure.get() == 1, Duration.ofSeconds(2));
        assertThat(afterFailure.get()).as("失败任务不得影响后续调度").isEqualTo(1);

        assertThat(scheduler.virtualThreadExecutor()).isNotNull();
        assertThat(scheduler.platformThreadExecutor()).isNotNull();
        assertThat(scheduler.virtualThreadExecutor()
                .submit(() -> Thread.currentThread().isVirtual()).get()).isTrue();

        scheduler.close();
        assertThatThrownBy(() -> scheduler.scheduleOnce(() -> { }, Duration.ZERO))
                .as("关闭后调度必须显式失败")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("SUP-07 DefaultAdapterContext 缓存复用、时钟与日志级别")
    void adapterContextMustMemoizeCachesAndLog() {
        AdapterContext context = new DefaultAdapterContext(ProtocolCode.of("tck"),
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 8);
        BoundedAddressCache<String> first = context.addressCache("modbus", 8);
        BoundedAddressCache<String> second = context.addressCache("modbus", 8);
        assertThat(first).as("同一 namespace 必须返回同一实例").isSameAs(second);
        assertThat(context.addressCache("other", 8)).isNotSameAs(first);
        assertThat(context.addressCache("huge", 0)).isNotNull();

        assertThat(context.protocol()).isEqualTo(ProtocolCode.of("tck"));
        assertThat(context.clock()).isNotNull();
        assertThat(context.clock().instant()).isNotNull();
        assertThat(context.scheduler()).isSameAs(scheduler);
        assertThat(context.resources()).isNotNull();
        assertThat(context.metrics()).isSameAs(NoopMetricsRecorder.INSTANCE);
        assertThat(context.credentials()).isNotNull();
        assertThat(context.settings()).isNotNull();
        assertThat(context.egress()).isNotNull();

        context.log(LogLevel.DEBUG, "iot.test.debug");
        context.log(LogLevel.INFO, "iot.test.info {}", 1);
        context.log(LogLevel.WARN, "iot.test.warn {}", 1);
        context.log(LogLevel.ERROR, "iot.test.error {}", 1);
    }

    @Test
    @DisplayName("SUP-08 NoopMetricsRecorder 必须可安全调用且不抛异常")
    void noopMetricsMustBeSafe() {
        NoopMetricsRecorder recorder = NoopMetricsRecorder.INSTANCE;
        recorder.recordRead(Duration.ZERO, true);
        recorder.recordWrite(Duration.ZERO, false);
        recorder.recordSubscriptionBatch(10);
        recorder.recordError("iot.test");
        recorder.gauge("queued", 1.0D);
        assertThat(recorder).isNotNull();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 可追踪的资源。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class TrackingResource implements AutoCloseable {

        private final String name;

        private final List<String> closed;

        private final boolean failOnClose;

        private TrackingResource(String name, List<String> closed, boolean failOnClose) {
            this.name = name;
            this.closed = closed;
            this.failOnClose = failOnClose;
        }

        @Override
        public void close() {
            closed.add(name);
            if (failOnClose) {
                throw new IllegalStateException("close failure by design");
            }
        }
    }

    /**
     * 无操作数据出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
