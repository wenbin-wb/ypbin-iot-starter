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

import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 连通性探测结果。
 *
 * <p>探测失败属于正常结果，通过 {@link #reachable()} 为 {@code false} 表达，
 * 避免宿主对着异常做流程控制。</p>
 *
 * @param reachable          是否可达
 * @param protocol           协议描述符
 * @param serverIdentity     对端身份（型号、固件、序列号）；未知时为 {@code null}
 * @param details            诊断细节
 * @param failureReason      失败原因（i18n 消息键）；成功时为 {@code null}
 * @param clockSkewMillis    与对端的时钟偏差（毫秒）；无法测量时为 {@code null}
 * @param elapsed            探测耗时
 * @author wenbin
 * @since 2026-09-13
 */
public record ProbeResult(
        boolean reachable,
        ProtocolDescriptor protocol,
        String serverIdentity,
        Map<String, String> details,
        String failureReason,
        Long clockSkewMillis,
        Duration elapsed) {

    /**
     * 紧凑构造器：归一化可空字段。
     */
    public ProbeResult {
        Objects.requireNonNull(protocol, "protocol must not be null");
        details = details == null ? Map.of() : Map.copyOf(details);
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        if (reachable) {
            failureReason = null;
        }
    }

    /**
     * 构造可达结果。
     *
     * @param protocol 协议描述符
     * @param details  诊断细节
     * @return 探测结果
     */
    public static ProbeResult reachable(ProtocolDescriptor protocol, Map<String, String> details) {
        return new ProbeResult(true, protocol, null, details, null, null, Duration.ZERO);
    }

    /**
     * 构造不可达结果。
     *
     * @param protocol 协议描述符
     * @param reason   失败原因（i18n 消息键）
     * @return 探测结果
     */
    public static ProbeResult unreachable(ProtocolDescriptor protocol, String reason) {
        return new ProbeResult(false, protocol, null, Map.of(), reason, null, Duration.ZERO);
    }
}
