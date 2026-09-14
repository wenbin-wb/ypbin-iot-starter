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
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 设备生命周期与异常事件。
 *
 * <p>与数据流分离：事件是低频控制面信息，宿主通常要落库并触发告警，
 * 不应与高频数据混在一条管道里。</p>
 *
 * @param deviceId   设备标识
 * @param protocol   协议标识
 * @param type       事件类型
 * @param messageKey i18n 消息键
 * @param cause      关联异常；无异常时为 {@code null}
 * @param occurredAt 发生时刻
 * @author wenbin
 * @since 2026-09-13
 */
public record DeviceEvent(
        String deviceId,
        ProtocolCode protocol,
        DeviceEventType type,
        String messageKey,
        @Nullable Throwable cause,
        Instant occurredAt) {

    /**
     * 紧凑构造器：校验必填项。
     */
    public DeviceEvent {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        messageKey = messageKey == null ? "" : messageKey;
    }

    /**
     * 构造不含异常的事件。
     *
     * @param deviceId   设备标识
     * @param protocol   协议标识
     * @param type       事件类型
     * @param messageKey i18n 消息键
     * @param occurredAt 发生时刻
     * @return 设备事件
     */
    public static DeviceEvent of(String deviceId, ProtocolCode protocol, DeviceEventType type,
            String messageKey, Instant occurredAt) {
        return new DeviceEvent(deviceId, protocol, type, messageKey, null, occurredAt);
    }
}
