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
package cn.ypbin.iot.it;

import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.spi.DataSink;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录型数据出口。
 *
 * <p>集成测试的断言终点就是这个 sink —— <b>数据必须真的到这里</b>，
 * 而不是「provider 没抛异常」。只断言「链路建立成功」是测不出
 * 「数据在出口之前被丢掉」这类问题的。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
final class RecordingSink implements DataSink {

    private final CopyOnWriteArrayList<DataBatch> batches = new CopyOnWriteArrayList<>();

    @Override
    public String name() {
        return "it-recording-sink";
    }

    @Override
    public void write(DataBatch batch) {
        batches.add(batch);
    }

    List<DataBatch> batches() {
        return List.copyOf(batches);
    }

    List<PointValue> points() {
        return batches.stream().flatMap(batch -> batch.points().stream()).toList();
    }
}
