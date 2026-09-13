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

import java.util.Arrays;

/**
 * IoT 接入层根异常，全部为 unchecked，避免污染适配器签名。
 *
 * <p><b>携带消息键而非格式化文案</b>：{@code messageKey} 形如
 * {@code iot.<protocol>.<category>.<detail>}，由 Web 层或宿主在日志层通过母仓
 * {@code I18nUtil.message(key, args)} 解析为当前语言文案。这样 {@code iot-core}
 * 既不需要依赖 i18n 机制，也能支持多语言。</p>
 *
 * <p>{@link #getMessage()} 返回的是<b>键与原始参数</b>的调试形式（便于日志排查），
 * <b>不可</b>直接展示给最终用户。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class IotException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** i18n 消息键。 */
    private final String messageKey;

    /** 消息参数，供 i18n 格式化使用。 */
    private final Object[] messageArgs;

    /**
     * 以消息键构造异常。
     *
     * @param messageKey i18n 消息键
     * @param args       消息参数
     */
    public IotException(String messageKey, Object... args) {
        super(buildDebugMessage(messageKey, args));
        this.messageKey = messageKey;
        this.messageArgs = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
    }

    /**
     * 以消息键与原因构造异常。
     *
     * @param cause      原始异常
     * @param messageKey i18n 消息键
     * @param args       消息参数
     */
    public IotException(Throwable cause, String messageKey, Object... args) {
        super(buildDebugMessage(messageKey, args), cause);
        this.messageKey = messageKey;
        this.messageArgs = args == null ? new Object[0] : Arrays.copyOf(args, args.length);
    }

    /**
     * i18n 消息键。
     *
     * @return 消息键
     */
    public String getMessageKey() {
        return messageKey;
    }

    /**
     * 消息参数，供 i18n 格式化使用。
     *
     * @return 参数副本
     */
    public Object[] getMessageArgs() {
        return Arrays.copyOf(messageArgs, messageArgs.length);
    }

    private static String buildDebugMessage(String messageKey, Object[] args) {
        if (args == null || args.length == 0) {
            return messageKey;
        }
        return messageKey + " " + Arrays.toString(args);
    }
}
