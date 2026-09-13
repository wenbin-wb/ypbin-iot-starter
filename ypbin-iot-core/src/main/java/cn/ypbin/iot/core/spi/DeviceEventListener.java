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

import cn.ypbin.iot.core.model.DeviceEvent;

/**
 * 设备事件监听 SPI：宿主据此落库、告警、更新设备在线状态。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DeviceEventListener {

    /**
     * 处理一条设备事件。
     *
     * <p>实现应快速返回；耗时逻辑请自行异步化。</p>
     *
     * @param event 设备事件
     */
    void onEvent(DeviceEvent event);
}
