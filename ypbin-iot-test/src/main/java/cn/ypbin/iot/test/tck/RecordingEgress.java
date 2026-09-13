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
package cn.ypbin.iot.test.tck;

import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 记录型数据出口：供 TCK 断言「数据是否真的从出口出来了」。
 *
 * <p>不做任何丢弃——测试场景下数据量极小，记录是唯一目的。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class RecordingEgress implements DataEgress {

    private final List<DataBatch> batches = new CopyOnWriteArrayList<>();

    private final List<DeviceEvent> events = new CopyOnWriteArrayList<>();

    @Override
    public void emit(DataBatch batch) {
        batches.add(batch);
    }

    @Override
    public void emit(DeviceEvent event) {
        events.add(event);
    }

    /**
     * 已记录的数据批次。
     *
     * @return 批次列表副本
     */
    public List<DataBatch> batches() {
        return new ArrayList<>(batches);
    }

    /**
     * 已记录的设备事件。
     *
     * @return 事件列表副本
     */
    public List<DeviceEvent> events() {
        return new ArrayList<>(events);
    }

    /**
     * 已记录的点位总数。
     *
     * @return 点位数
     */
    public int pointCount() {
        return batches.stream().mapToInt(DataBatch::size).sum();
    }

    /** 清空记录。 */
    public void reset() {
        batches.clear();
        events.clear();
    }
}
