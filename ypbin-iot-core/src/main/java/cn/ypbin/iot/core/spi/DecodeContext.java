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

import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 字节载荷解码的点位/设备上下文：解码 SPI 的<b>稳定输入契约</b>。
 *
 * <p>字段全部来自框架既有事实（协议回调 + 设备规格 + 点位地址），不引入任何宿主业务语义
 * （物模型类型、缩放系数等仍归宿主；解码器需要额外信息时按 {@code rawAddress} 自行查表，
 * 就像 MQTT 解码器按主题查自己的规格）。</p>
 *
 * <p>实现必须自行判空：{@code connectionId} 允许为空串，但 {@code deviceId} /
 * {@code protocol} / {@code rawAddress} 由框架保证非空。</p>
 *
 * @param deviceId     设备标识
 * @param protocol     协议标识
 * @param rawAddress   协议原始地址（MQTT 主题 / OPC UA NodeId 字符串 / TCP 流地址…）
 * @param connectionId 链路标识（可能为空串）
 * @author wenbin
 * @since 2026-10-17
 */
public record DecodeContext(
        String deviceId,
        ProtocolCode protocol,
        String rawAddress,
        @Nullable String connectionId) {

    /**
     * 紧凑构造器：校验必填项并把可空字段归一化为空串。
     */
    public DecodeContext {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(rawAddress, "rawAddress must not be null");
        connectionId = connectionId == null ? "" : connectionId;
    }
}
