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

import cn.ypbin.iot.core.model.ConnectionSpec;
import java.util.Optional;

/**
 * 物理链路规格来源 SPI：按 {@code connectionId} 提供建链参数（端点、超时、TLS、凭据引用）。
 *
 * <p><b>为什么链路规格不放在 {@link cn.ypbin.iot.core.model.DeviceSpec} 上</b>：
 * 这是「连接 / 设备两级模型」的直接结果——一条 Modbus 网关链路后面挂着 200 个从站，
 * 端点属于<b>链路</b>而不是任一设备。把 endpoint 塞进设备规格会导致 200 个设备
 * 各自携带同一份端点，且一旦修改就要改 200 处。</p>
 *
 * <p><b>运行期约束</b>：与 {@link DeviceRegistry} 一样，实现应当在<b>启动期</b>把链路规格
 * 加载到内存（或直接由配置提供），运行期只读。框架只在设备绑定与重连时查询它，
 * <b>不会</b>在采集路径上反复调用。</p>
 *
 * <p>本接口是 M0 实现过程中识别出的 SPI 缺口：设计文档的 {@code DeviceRegistry} 只覆盖了
 * 「设备从哪来」，未覆盖「链路的建链参数从哪来」。两者职责不同（链路可能被多设备共享、
 * 也可能不挂任何设备而仅用于探测），因此拆成两个接口。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ConnectionSpecProvider {

    /**
     * 按链路标识查找建链规格。
     *
     * @param connectionId 链路标识
     * @return 链路规格；不存在时返回空 Optional
     */
    Optional<ConnectionSpec> find(String connectionId);
}
