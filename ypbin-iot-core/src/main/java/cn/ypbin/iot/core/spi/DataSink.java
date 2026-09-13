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
package cn.ypbin.iot.core.spi;

import cn.ypbin.iot.core.model.DataBatch;

/**
 * 数据落地 SPI：宿主实现它把微批数据写入 Kafka、时序库或规则引擎。
 *
 * <p>与 {@link cn.ypbin.iot.core.context.DataEgress} 的分工：{@code DataEgress} 是框架侧的
 * 出口（负责微批、背压、指标），{@code DataSink} 是宿主侧的落点（负责真正的 I/O）。
 * 默认实现把批次转交给所有注册的 {@code DataSink}。</p>
 *
 * <p><b>实现约束</b>：{@code write} 由框架内部线程调用，实现必须线程安全且快速返回
 * （建议只做一次入队）。<b>禁止</b>在此同步访问数据库或发起远程调用——
 * 那会把数据库连接池的延迟与抖动引入采集路径（见 {@code DESIGN.md} §5.4）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DataSink {

    /**
     * Sink 名称，用于日志与指标标签。
     *
     * @return 名称
     */
    String name();

    /**
     * 写入一批数据。
     *
     * @param batch 数据批次
     */
    void write(DataBatch batch);

    /** 关闭 Sink，释放资源。 */
    default void close() {
        // 默认无资源
    }
}
