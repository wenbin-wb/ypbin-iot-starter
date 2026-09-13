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
package cn.ypbin.iot.core.context;

import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;

/**
 * 数据与事件出口：协议接入层与宿主平台之间的<b>唯一</b>数据通道。
 *
 * <p>框架保证：</p>
 * <ul>
 *   <li><b>不阻塞</b>：{@code emit} 只做入队，绝不阻塞协议线程；</li>
 *   <li><b>微批</b>：按配置的点位数与时间窗口聚合，避免每点位一次调用；</li>
 *   <li><b>背压可见</b>：队列满时按溢出策略处理，并累加丢弃指标与限流日志，
 *       <b>绝不静默丢弃</b>。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DataEgress {

    /**
     * 交付一批点位数据。
     *
     * @param batch 非空批次
     */
    void emit(DataBatch batch);

    /**
     * 交付一条设备生命周期或异常事件。
     *
     * @param event 设备事件
     */
    void emit(DeviceEvent event);
}
