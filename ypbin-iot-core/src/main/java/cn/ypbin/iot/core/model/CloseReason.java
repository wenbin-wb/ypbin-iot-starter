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

import java.time.Instant;
import java.util.Objects;

/**
 * 链路关闭原因。
 *
 * @param cause    关闭原因分类
 * @param message  补充说明
 * @param error    关联异常；无异常时为 {@code null}
 * @param closedAt 关闭时刻
 * @author wenbin
 * @since 2026-09-13
 */
public record CloseReason(CloseCause cause, String message, Throwable error, Instant closedAt) {

    /**
     * 紧凑构造器：校验必填项。
     */
    public CloseReason {
        Objects.requireNonNull(cause, "cause must not be null");
        Objects.requireNonNull(closedAt, "closedAt must not be null");
        message = message == null ? "" : message;
    }

    /**
     * 构造主动关闭原因。
     *
     * @param closedAt 关闭时刻
     * @return 关闭原因
     */
    public static CloseReason clientRequest(Instant closedAt) {
        return new CloseReason(CloseCause.CLIENT_REQUEST, "", null, closedAt);
    }
}
