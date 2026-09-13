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
package cn.ypbin.iot.core.model;

import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 逻辑设备规格。
 *
 * <p>{@code localAddress} 是<b>协议内</b>的本地地址，由协议自行解释：
 * Modbus 的 unitId、BACnet 的 device instance、KNX 的物理地址等。
 * 一条链路可承载多个逻辑设备，这是连接复用的基础。</p>
 *
 * @param deviceId      设备标识
 * @param deviceName    设备名称
 * @param protocol      协议标识
 * @param connectionId  所属链路标识
 * @param localAddress  协议内本地地址
 * @param pollInterval  采集周期；{@link Duration#ZERO} 表示仅订阅不轮询
 * @param properties    协议扩展参数
 * @author wenbin
 * @since 2026-09-13
 */
public record DeviceSpec(
        String deviceId,
        String deviceName,
        ProtocolCode protocol,
        String connectionId,
        String localAddress,
        Duration pollInterval,
        Map<String, String> properties) {

    /**
     * 紧凑构造器：归一化可空字段并校验必填项。
     */
    public DeviceSpec {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        deviceName = deviceName == null ? deviceId : deviceName;
        connectionId = connectionId == null ? deviceId : connectionId;
        localAddress = localAddress == null ? "" : localAddress;
        pollInterval = pollInterval == null ? Duration.ZERO : pollInterval;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }
}
