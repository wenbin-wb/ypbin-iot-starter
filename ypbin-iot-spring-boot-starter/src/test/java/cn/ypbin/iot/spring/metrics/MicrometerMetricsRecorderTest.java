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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Micrometer 指标实现测试。
 *
 * <p>重点不是「调用不抛异常」（那是它本来就该做到的），而是
 * <b>指标真的被记进了注册表、且维度与语义正确</b> ——
 * 一个只做空操作、或把标签写错的实现也能让「不抛异常」的断言通过。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
class MicrometerMetricsRecorderTest {

    @Test
    @DisplayName("MET-01 读写计数必须按结果分维度记录")
    void readWriteCountersMustBeTaggedByResult() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordRead(Duration.ofMillis(5), true);
        recorder.recordRead(Duration.ofMillis(5), true);
        recorder.recordRead(Duration.ofMillis(7), false);
        recorder.recordWrite(Duration.ofMillis(3), true);

        assertThat(registry.get("ypbin.iot.read").tag("result", "success").counter().count())
                .as("成功读必须计到 success 维度").isEqualTo(2.0);
        assertThat(registry.get("ypbin.iot.read").tag("result", "failure").counter().count())
                .as("失败读必须计到 failure 维度，不能混进 success").isEqualTo(1.0);
        assertThat(registry.get("ypbin.iot.write").tag("result", "success").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("MET-02 计时器必须真的收到耗时样本")
    void timersMustRecordElapsed() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordRead(Duration.ofMillis(10), true);
        recorder.recordRead(Duration.ofMillis(30), true);

        assertThat(registry.get("ypbin.iot.read.duration").timer().count())
                .as("计时器样本数必须等于调用次数").isEqualTo(2L);
        assertThat(registry.get("ypbin.iot.read.duration").timer().totalTime(TimeUnit.MILLISECONDS))
                .as("总耗时必须是 40ms（10+30）").isEqualTo(40.0);
    }

    @Test
    @DisplayName("MET-03 订阅批次必须按点位数累加（不是按批次数）")
    void subscriptionBatchMustCountPoints() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordSubscriptionBatch(3);
        recorder.recordSubscriptionBatch(7);
        recorder.recordSubscriptionBatch(0);

        assertThat(registry.get("ypbin.iot.subscription.points").counter().count())
                .as("按点位数累加得 10；按批次数会是 2").isEqualTo(10.0);
    }

    @Test
    @DisplayName("MET-04 错误必须按类型分维度，且 null 不得产生空标签值")
    void errorsMustBeTaggedByType() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        recorder.recordError("iot.tcp.write.failed");
        recorder.recordError("iot.tcp.write.failed");
        recorder.recordError(null);

        assertThat(registry.get("ypbin.iot.errors").tag("type", "iot.tcp.write.failed").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("ypbin.iot.errors").tag("type", "unknown").counter().count())
                .as("null 必须落到 unknown 而不是空字符串标签").isEqualTo(1.0);
    }

    @Test
    @DisplayName("MET-05 同一名字的 gauge 必须更新而不是重复注册（否则注册表会被撑爆）")
    void repeatedGaugeMustUpdateInPlace() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        for (int index = 1; index <= 100; index++) {
            recorder.gauge("ypbin.iot.connections", index);
        }

        List<Meter> gauges = registry.getMeters().stream()
                .filter(meter -> meter.getId().getName().equals("ypbin.iot.connections"))
                .toList();
        assertThat(gauges).as("100 次上报只能有 1 个仪表").hasSize(1);
        assertThat(registry.get("ypbin.iot.connections").gauge().value())
                .as("值必须是最后一次上报的 100").isEqualTo(100.0);
    }

    @Test
    @DisplayName("MET-06 传 null 耗时不得丢计数、也不得抛（调用方允许不传耗时）")
    void nullElapsedMustNotLoseCount() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerMetricsRecorder recorder = new MicrometerMetricsRecorder(registry);

        assertThatCode(() -> recorder.recordRead(null, true)).doesNotThrowAnyException();
        assertThat(registry.get("ypbin.iot.read").tag("result", "success").counter().count())
                .as("耗时为 null 时计数仍必须累加").isEqualTo(1.0);
        assertThat(registry.get("ypbin.iot.read.duration").timer().count())
                .as("耗时为 null 时不应产生计时样本").isZero();
    }
}
