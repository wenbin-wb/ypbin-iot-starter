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
package cn.ypbin.iot.runtime.context;

import cn.ypbin.iot.core.context.MetricsRecorder;
import java.time.Duration;

/**
 * 无操作指标实现：非 Spring 环境或未接入指标后端时使用。
 *
 * <p>不做任何记录的<b>同时也不吞掉语义</b>：指标本身是可选的横切能力，
 * 不记录不等于静默降级。Spring 环境下由 starter 装配 Micrometer 桥接实现覆盖它。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class NoopMetricsRecorder implements MetricsRecorder {

    /** 单例。 */
    public static final NoopMetricsRecorder INSTANCE = new NoopMetricsRecorder();

    private NoopMetricsRecorder() {
    }

    @Override
    public void recordRead(Duration elapsed, boolean success) {
        // 无操作实现
    }

    @Override
    public void recordWrite(Duration elapsed, boolean success) {
        // 无操作实现
    }

    @Override
    public void recordSubscriptionBatch(int pointCount) {
        // 无操作实现
    }

    @Override
    public void recordError(String errorType) {
        // 无操作实现
    }

    @Override
    public void gauge(String name, double value) {
        // 无操作实现
    }
}
