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
import java.util.List;
import java.util.Objects;

/**
 * 微批数据包：框架数据出口的最小交付单位。
 *
 * <p>10 万设备 × 100 点位 ÷ 5s 周期 ≈ 200 万点/秒，逐点回调会让框架自身成为瓶颈，
 * 因此出口统一按批交付（默认 1000 点或 200ms 触发）。</p>
 *
 * @param deviceId     设备标识
 * @param protocol     协议标识
 * @param connectionId 链路标识
 * @param producedAt   批次生成时刻（服务器时间，恒可信）
 * @param points       点位值列表
 * @author wenbin
 * @since 2026-09-13
 */
public record DataBatch(
        String deviceId,
        ProtocolCode protocol,
        String connectionId,
        Instant producedAt,
        List<PointValue> points) {

    /**
     * 紧凑构造器：校验必填项并做不可变拷贝。
     */
    public DataBatch {
        Objects.requireNonNull(deviceId, "deviceId must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(producedAt, "producedAt must not be null");
        points = points == null ? List.of() : List.copyOf(points);
        connectionId = connectionId == null ? "" : connectionId;
    }

    /**
     * 点位数。
     *
     * @return 点位数
     */
    public int size() {
        return points.size();
    }

    /**
     * 是否为空批次。
     *
     * @return 无点位时返回 {@code true}
     */
    public boolean isEmpty() {
        return points.isEmpty();
    }
}
