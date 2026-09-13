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
package cn.ypbin.iot.core.i18n;

/**
 * IoT 接入层消息键常量。
 *
 * <p>命名规范：{@code iot.<protocol>.<category>.<detail>}，全小写加连字符，
 * 参数用 {@code {}} 占位（与母仓 {@code I18nUtil} 一致）。
 * 各模块自带 {@code messages_zh_CN.properties} / {@code messages_en_US.properties}，
 * 由 starter 把本仓 basename 追加到 {@code spring.messages.basename}。</p>
 *
 * <p>禁止在代码中出现面向用户的中英文消息字面量——异常与结构化日志一律使用本类常量。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class IotMessageKeys {

    /** 连接类消息前缀。 */
    public static final String CONNECTION_PREFIX = "iot.common.connection.";

    /** 地址类消息前缀。 */
    public static final String ADDRESS_PREFIX = "iot.common.address.";

    /** 配置类消息前缀。 */
    public static final String CONFIG_PREFIX = "iot.common.config.";

    /** 协议类消息前缀。 */
    public static final String PROTOCOL_PREFIX = "iot.common.protocol.";

    /** 能力类消息前缀。 */
    public static final String CAPABILITY_PREFIX = "iot.common.capability.";

    /** 建链超时。 */
    public static final String CONNECTION_TIMEOUT = CONNECTION_PREFIX + "timeout";

    /** 建链失败。 */
    public static final String CONNECTION_FAILED = CONNECTION_PREFIX + "failed";

    /** 链路已关闭。 */
    public static final String CONNECTION_CLOSED = CONNECTION_PREFIX + "closed";

    /** 链路数已达上限。 */
    public static final String CONNECTION_LIMIT_EXCEEDED = CONNECTION_PREFIX + "limit-exceeded";

    /** 请求超时。 */
    public static final String PROTOCOL_TIMEOUT = PROTOCOL_PREFIX + "timeout";

    /** 协议错误。 */
    public static final String PROTOCOL_ERROR = PROTOCOL_PREFIX + "error";

    /** 地址解析失败。 */
    public static final String ADDRESS_PARSE_FAILED = ADDRESS_PREFIX + "parse-failed";

    /** 端点 URI 非法。 */
    public static final String ENDPOINT_INVALID = ADDRESS_PREFIX + "invalid-endpoint";

    /** 能力不支持。 */
    public static final String CAPABILITY_UNSUPPORTED = CAPABILITY_PREFIX + "unsupported";

    /** 配置非法。 */
    public static final String CONFIG_INVALID = CONFIG_PREFIX + "invalid";

    /** 未知配置项。 */
    public static final String CONFIG_UNKNOWN_KEY = CONFIG_PREFIX + "unknown-key";

    private IotMessageKeys() {
    }
}
