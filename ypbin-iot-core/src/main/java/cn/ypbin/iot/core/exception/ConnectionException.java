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

/**
 * 建链、绑定或链路断开类错误。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class ConnectionException extends IotException {

    private static final long serialVersionUID = 1L;

    /** 关联的链路标识。 */
    private final String connectionId;

    /**
     * 构造建链异常。
     *
     * @param connectionId 链路标识
     * @param messageKey   i18n 消息键
     * @param args         消息参数
     */
    public ConnectionException(String connectionId, String messageKey, Object... args) {
        super(messageKey, args);
        this.connectionId = connectionId;
    }

    /**
     * 构造带原因的建链异常。
     *
     * @param connectionId 链路标识
     * @param cause        原始异常
     * @param messageKey   i18n 消息键
     * @param args         消息参数
     */
    public ConnectionException(String connectionId, Throwable cause, String messageKey, Object... args) {
        super(cause, messageKey, args);
        this.connectionId = connectionId;
    }

    /**
     * 链路标识。
     *
     * @return 链路标识
     */
    public String getConnectionId() {
        return connectionId;
    }
}
