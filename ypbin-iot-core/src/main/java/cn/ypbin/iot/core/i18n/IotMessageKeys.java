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

    /** 字节载荷解码失败类消息前缀：后接 {@code DecodeFailure} 的稳定细节标识。 */
    public static final String PAYLOAD_DECODE_FAILED_PREFIX = "iot.common.payload.decode-failed.";

    /** 字节载荷没有解码器认领（原样交付字节；显式计数，不静默）。 */
    public static final String PAYLOAD_DECODER_NOT_FOUND = "iot.common.payload.decoder.not-found";

    /** 出口队列丢弃（有界队列满或已关闭）。 */
    public static final String EGRESS_DROPPED = "iot.common.egress.dropped";

    /** 订阅编排类消息前缀。 */
    public static final String SUBSCRIPTION_PREFIX = "iot.common.subscription.";

    /**
     * 需要多寄存器地址展开，但协议未提供地址算术扩展。
     *
     * <p><b>绝不静默只订基址</b>：只订基址会让多寄存器解码拿到半截字节，质量却仍是 GOOD——
     * 「读到了值但值不对」比直接失败更难排查，因此必须显式失败并给出可操作的原因。</p>
     */
    public static final String SUBSCRIPTION_ADDRESS_EXPANSION_UNSUPPORTED =
            SUBSCRIPTION_PREFIX + "address-expansion-unsupported";

    /** 订阅调用失败（协议层未携带更具体的消息键时的兜底原因）。 */
    public static final String SUBSCRIPTION_FAILED = SUBSCRIPTION_PREFIX + "failed";

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


    /** 能力不支持。 */
    public static final String CAPABILITY_UNSUPPORTED = CAPABILITY_PREFIX + "unsupported";

    /** 配置非法。 */
    public static final String CONFIG_INVALID = CONFIG_PREFIX + "invalid";

    /** 未知配置项。 */
    public static final String CONFIG_UNKNOWN_KEY = CONFIG_PREFIX + "unknown-key";

    private IotMessageKeys() {
    }
}
