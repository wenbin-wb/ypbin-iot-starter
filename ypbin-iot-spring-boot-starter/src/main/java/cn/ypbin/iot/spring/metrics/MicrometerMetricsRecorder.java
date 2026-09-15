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
package cn.ypbin.iot.spring.metrics;

import cn.ypbin.iot.core.context.MetricsRecorder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 基于 Micrometer 的指标实现。
 *
 * <p><b>非阻塞约束</b>：{@link MetricsRecorder} 的调用点落在共享 IO 线程（Netty event loop）、
 * 协议库回调线程（HiveMQ / Milo 共享执行器 / digitalpetri）、框架调度器线程与调用方线程上，
 * 一次阻塞会波及同 JVM 内的其它链路。因此本实现只做 Micrometer 的计数与计时
 * （内部为原子操作），<b>不做 IO、不做日志、不设置超时等待</b>。</p>
 *
 * <p><b>刻意不配置分位数与直方图</b>：Percentiles / Histogram 会显著增加单次计时开销与内存，
 * 是否开启应由宿主的 Micrometer 配置（{@code management.metrics.distribution.*}）决定，
 * 本实现不擅自开启。</p>
 *
 * <p><b>标签基数</b>：{@code errorType} 作为标签值必须是<b>有界</b>的 ——
 * 协议实现传的都是各自的 {@code MSG_*} 常量（有限集合）。为此这里用一个小缓存复用
 * 计数器句柄，避免每次错误都重建 MeterId。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
public final class MicrometerMetricsRecorder implements MetricsRecorder {

    private static final String TAG_RESULT = "result";

    private static final String TAG_TYPE = "type";

    private static final String RESULT_SUCCESS = "success";

    private static final String RESULT_FAILURE = "failure";

    private static final String UNKNOWN_TYPE = "unknown";

    private final MeterRegistry registry;

    private final Counter readSuccess;

    private final Counter readFailure;

    private final Timer readTimer;

    private final Counter writeSuccess;

    private final Counter writeFailure;

    private final Timer writeTimer;

    private final Counter subscriptionPoints;

    private final ConcurrentMap<String, Counter> errorCounters = new ConcurrentHashMap<>();

    private final ConcurrentMap<String, GaugeValue> gauges = new ConcurrentHashMap<>();

    /**
     * 创建 Micrometer 指标实现。
     *
     * @param registry 指标注册表
     */
    public MicrometerMetricsRecorder(MeterRegistry registry) {
        this.registry = registry;
        this.readSuccess = operationCounter("read", RESULT_SUCCESS);
        this.readFailure = operationCounter("read", RESULT_FAILURE);
        this.readTimer = Timer.builder("ypbin.iot.read.duration").register(registry);
        this.writeSuccess = operationCounter("write", RESULT_SUCCESS);
        this.writeFailure = operationCounter("write", RESULT_FAILURE);
        this.writeTimer = Timer.builder("ypbin.iot.write.duration").register(registry);
        this.subscriptionPoints = Counter.builder("ypbin.iot.subscription.points")
                .description("投递给宿主的订阅点位数")
                .register(registry);
    }

    /**
     * 瞬时值的可变持有者：Micrometer 的 Gauge 需要一个可读状态对象，
     * 而不能直接注册一个「固定值」。
     *
     * @author wenbin
     * @since 2026-09-15
     */
    private static final class GaugeValue {

        private volatile double value;

        private GaugeValue(double value) {
            this.value = value;
        }

        private double value() {
            return value;
        }

        private void set(double newValue) {
            this.value = newValue;
        }
    }

    private Counter operationCounter(String operation, String result) {
        return Counter.builder("ypbin.iot." + operation)
                .tag(TAG_RESULT, result)
                .description("读取/写入次数（按结果分类）")
                .register(registry);
    }

    @Override
    public void recordRead(Duration elapsed, boolean success) {
        (success ? readSuccess : readFailure).increment();
        if (elapsed != null) {
            readTimer.record(elapsed);
        }
    }

    @Override
    public void recordWrite(Duration elapsed, boolean success) {
        (success ? writeSuccess : writeFailure).increment();
        if (elapsed != null) {
            writeTimer.record(elapsed);
        }
    }

    @Override
    public void recordSubscriptionBatch(int pointCount) {
        if (pointCount > 0) {
            subscriptionPoints.increment(pointCount);
        }
    }

    @Override
    public void gauge(String name, double value) {
        // 同一个名字重复调用必须**更新已有仪表**而不是重复注册：
        // 否则每次上报都会新建一个 Gauge，把注册表撑爆。
        // （名字的「白名单」由框架侧的调用方保证 —— 目前尚无生产调用方，
        //  见 MetricsRecorder#gauge 的契约说明。）
        gauges.computeIfAbsent(name, key -> {
            GaugeValue holder = new GaugeValue(value);
            Gauge.builder(key, holder, GaugeValue::value).register(registry);
            return holder;
        }).set(value);
    }

    @Override
    public void recordError(String errorType) {
        // 错误是冷路径（相对数据面），这里用缓存复用句柄：
        // Micrometer 每次 register 都要构造 MeterId 并做一次查表，热路径上不值得重复。
        errorCounters.computeIfAbsent(errorType == null ? UNKNOWN_TYPE : errorType,
                type -> Counter.builder("ypbin.iot.errors")
                        .tag(TAG_TYPE, type)
                        .description("协议层错误次数（按消息键分类）")
                        .register(registry))
                .increment();
    }
}
