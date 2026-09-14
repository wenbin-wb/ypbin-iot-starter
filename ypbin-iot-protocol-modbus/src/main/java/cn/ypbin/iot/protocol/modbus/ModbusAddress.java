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
package cn.ypbin.iot.protocol.modbus;

import java.util.Objects;

/**
 * Modbus 点位地址：寄存器区类型 + 偏移。
 *
 * @param type   寄存器区类型
 * @param offset 偏移（0-based）
 * @param raw    原始地址字符串（保留用于日志与回显）
 * @author wenbin
 * @since 2026-09-13
 */
record ModbusAddress(ModbusRegisterType type, int offset, String raw) {

    /**
     * 紧凑构造器：校验必填项与偏移范围。
     */
    ModbusAddress {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(raw, "raw must not be null");
        if (offset < 0 || offset > ModbusRegisterType.MAX_OFFSET) {
            throw new IllegalArgumentException("offset out of range for " + type.getCode() + ": " + offset);
        }
    }

    /**
     * 是否与另一个地址落在同一连续块内（含端点）。
     *
     * @param other 另一个地址
     * @param maxSpan 允许的最大跨度
     * @return 同类型且跨度不超过给定值返回 {@code true}
     */
    boolean mergesWith(ModbusAddress other, int maxSpan) {
        if (other == null || other.type != type) {
            return false;
        }
        int low = Math.min(offset, other.offset);
        int high = Math.max(offset, other.offset);
        return high - low + 1 <= maxSpan;
    }
}
