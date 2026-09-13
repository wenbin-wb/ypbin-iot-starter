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
 * 协议层错误：报文非法、返回码异常、状态机冲突。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class ProtocolException extends IotException {

    private static final long serialVersionUID = 1L;

    /**
     * 以消息键构造协议异常。
     *
     * @param messageKey i18n 消息键
     * @param args       消息参数
     */
    public ProtocolException(String messageKey, Object... args) {
        super(messageKey, args);
    }

    /**
     * 以消息键与原因构造协议异常。
     *
     * @param cause      原始异常
     * @param messageKey i18n 消息键
     * @param args       消息参数
     */
    public ProtocolException(Throwable cause, String messageKey, Object... args) {
        super(cause, messageKey, args);
    }
}
