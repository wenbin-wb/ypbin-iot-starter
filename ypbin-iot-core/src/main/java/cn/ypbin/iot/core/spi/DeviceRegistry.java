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

import cn.ypbin.iot.core.model.DeviceSpec;
import java.util.List;
import java.util.function.Consumer;

/**
 * 设备来源 SPI：宿主从这里提供设备清单与配置变更。
 *
 * <p><b>这是「接入路径零 DB 访问」的唯一入口</b>：{@link #loadAll()} 在<b>启动期</b>被调用
 * 一次并全量加载到内存，此后运行期只读内存；运行期变更由宿主<b>主动推入</b>
 * （{@link #addChangeListener}），<b>框架不会去轮询数据库</b>。</p>
 *
 * <p>实现约束：</p>
 * <ul>
 *   <li>{@code loadAll()} 可以访问数据库（启动期无并发压力），但必须处理分页；</li>
 *   <li>变更推送通道<b>不能是数据库轮询</b>；</li>
 *   <li>变更事件必须携带单调递增的 {@code revision}。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DeviceRegistry {

    /**
     * 全量加载设备清单（启动期调用一次）。
     *
     * @return 设备列表；无设备时返回空列表，不得返回 {@code null}
     */
    List<DeviceSpec> loadAll();

    /**
     * 校验设备配置是否可接入。
     *
     * <p>默认实现返回通过。框架会在此基础上叠加通用校验（deviceId 唯一、协议已注册、
     * 连接规格存在、采集周期合法），协议模块再叠加协议特有校验。</p>
     *
     * @param device 待校验的设备规格
     * @return 校验结果，永不为 {@code null}
     */
    default ValidationResult validate(DeviceSpec device) {
        return ValidationResult.ok();
    }

    /**
     * 注册变更监听。
     *
     * <p>框架在启动完成后注册监听器；宿主在设备增删改时调用监听器。
     * 同一 {@code deviceId} 的变更由框架按设备级排他锁串行处理。</p>
     *
     * @param listener 变更监听器
     */
    void addChangeListener(Consumer<DeviceChange> listener);
}
