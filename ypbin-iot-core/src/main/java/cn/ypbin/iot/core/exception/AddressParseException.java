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

/**
 * 地址解析失败。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class AddressParseException extends IotException {

    private static final long serialVersionUID = 1L;

    /** iot.common.address.parse-failed 消息键。 */
    public static final String MESSAGE_KEY = "iot.common.address.parse-failed";

    private final ProtocolCode protocol;

    private final String rawAddress;

    /**
     * 构造地址解析异常。
     *
     * @param protocol 协议标识
     * @param raw      原始地址字符串
     * @param reason   失败原因
     */
    public AddressParseException(ProtocolCode protocol, String raw, String reason) {
        super(MESSAGE_KEY, protocol == null ? null : protocol.value(), raw, reason);
        this.protocol = protocol;
        this.rawAddress = raw;
    }

    /**
     * 协议标识。
     *
     * @return 协议标识
     */
    public ProtocolCode getProtocol() {
        return protocol;
    }

    /**
     * 原始地址字符串。
     *
     * @return 原始地址
     */
    public String getRawAddress() {
        return rawAddress;
    }
}
