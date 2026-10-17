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
package cn.ypbin.iot.core.spi;

import cn.ypbin.iot.core.i18n.IotMessageKeys;
import java.util.Optional;

/**
 * 字节载荷解码失败原因（有界枚举，同时是质量原因与指标标签的稳定来源）。
 *
 * <p><b>为什么用枚举而不是自由字符串</b>：失败原因会成为指标 {@code ypbin.iot.errors}
 * 的 {@code type} 标签值。自由字符串会让标签基数随设备/点位爆炸（时序库与 Prometheus 的
 * 经典事故形态），因此取值集合必须<b>编译期有界</b>。</p>
 *
 * <p><b>为什么失败要显式表达</b>：解码器是把「原始字节」变成「平台规范值」的那一层，
 * 它失败了却返回 {@code null} 或默认值，等于把「解析失败」伪装成「设备没数据」——
 * 现场会表现为「点位一直没值」，无从定位。因此 {@link DecodeOutcome} 强制二选一：
 * 要么带值成功，要么带本枚举的某个原因失败。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
public enum DecodeFailure {

    /** 字节长度与声明的数值类型/位宽不匹配（不补零、不截断）。 */
    LENGTH_MISMATCH(1, "length-mismatch", "字节长度与声明的解码规格不匹配"),

    /** 按声明形态解释出的浮点是 NaN/±Inf（不是有效测量，不落库）。 */
    NOT_FINITE(2, "not-finite", "浮点值为 NaN/±Inf"),

    /** 字节串无法按声明的规格解释。 */
    INVALID_VALUE(3, "invalid-value", "字节串无法按声明的规格解释"),

    /** 该点位没有可用的解码配置（解码器被直接调用或配置缺失）。 */
    CONFIG_MISSING(4, "config-missing", "该点位没有可用的解码配置"),

    /** 解码器抛出异常（实现缺陷；框架捕获后显式暴露，不吞异常）。 */
    DECODER_ERROR(5, "decoder-error", "解码器抛出异常（实现缺陷，需修复）");

    private final int code;

    private final String detail;

    private final String desc;

    DecodeFailure(int code, String detail, String desc) {
        this.code = code;
        this.detail = detail;
        this.desc = desc;
    }

    /**
     * 原因码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 原因码
     */
    public int getCode() {
        return code;
    }

    /**
     * 稳定细节标识（小写连字符），用于拼装消息键与日志。
     *
     * @return 细节标识
     */
    public String getDetail() {
        return detail;
    }

    /**
     * 原因描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 本原因对应的 i18n 消息键：{@code iot.common.payload.decode-failed.<detail>}。
     *
     * <p>该键同时用作 BAD 质量的 {@code qualityReason} 与错误指标的 {@code type} 标签值，
     * 因此「日志里看到的键」与「指标上的标签」永远一致。</p>
     *
     * @return 消息键
     */
    public String messageKey() {
        return IotMessageKeys.PAYLOAD_DECODE_FAILED_PREFIX + detail;
    }

    /**
     * 按原因码查找。
     *
     * @param code 原因码
     * @return 匹配的原因；无匹配时返回空 Optional
     */
    public static Optional<DecodeFailure> fromCode(int code) {
        for (DecodeFailure failure : values()) {
            if (failure.code == code) {
                return Optional.of(failure);
            }
        }
        return Optional.empty();
    }
}
