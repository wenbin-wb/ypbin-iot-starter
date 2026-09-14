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
package cn.ypbin.iot.core.exception;

import cn.ypbin.iot.core.protocol.ProtocolCode;
import org.jspecify.annotations.Nullable;

/**
 * 能力不支持：调用未在 {@code ProtocolDescriptor.capabilities()} 中声明的方法时抛出。
 *
 * <p><b>这是 fail-fast，不是降级</b>：宁可让调用方立刻收到明确异常，
 * 也不要返回空结果让宿主误以为设备正常。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class UnsupportedCapabilityException extends IotException {

    private static final long serialVersionUID = 1L;

    /** iot.common.capability.unsupported 消息键。 */
    public static final String MESSAGE_KEY = "iot.common.capability.unsupported";

    @Nullable
    private final ProtocolCode protocol;

    private final String operation;

    /**
     * 构造能力不支持异常。
     *
     * @param protocol  协议标识
     * @param operation 被调用的操作名
     */
    public UnsupportedCapabilityException(@Nullable ProtocolCode protocol, String operation) {
        super(MESSAGE_KEY, protocol == null ? "" : protocol.value(), operation);
        this.protocol = protocol;
        this.operation = operation;
    }

    /**
     * 协议标识。
     *
     * @return 协议标识
     */
    public @Nullable ProtocolCode getProtocol() {
        return protocol;
    }

    /**
     * 被调用的操作名。
     *
     * @return 操作名
     */
    public String getOperation() {
        return operation;
    }
}
