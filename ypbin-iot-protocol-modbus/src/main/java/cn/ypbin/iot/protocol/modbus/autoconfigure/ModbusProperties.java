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
package cn.ypbin.iot.protocol.modbus.autoconfigure;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Modbus 协议配置。
 *
 * <p>连接超时、请求超时、保活间隔等通用参数走
 * {@code ypbin.iot.protocol.modbus.*}（由框架的 {@code ProtocolProperties} 承载），
 * 本类只保留 Modbus 特有项。</p>
 *
 * @param enabled       协议开关
 * @param defaultUnitId 默认从站地址（设备未配置 {@code localAddress} 时使用）
 * @author wenbin
 * @since 2026-09-13
 */
@ConfigurationProperties(prefix = ModbusProperties.PREFIX)
public record ModbusProperties(Boolean enabled, Integer defaultUnitId) {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.iot.protocol.modbus";

    /**
     * 紧凑构造器：归一化默认值。
     */
    public ModbusProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        defaultUnitId = defaultUnitId == null || defaultUnitId < 0 || defaultUnitId > 247
                ? 1 : defaultUnitId;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }
}
