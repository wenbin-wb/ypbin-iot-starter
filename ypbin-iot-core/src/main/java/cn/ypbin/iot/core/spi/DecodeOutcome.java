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

import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 一次字节载荷解码的结果：成功带规范值、失败带显式原因，二者<b>互斥且必有其一</b>。
 *
 * <p><b>为什么不用 {@code null} 表示失败</b>：无法区分「解码失败」与「解出的就是空值」，
 * 而且会把失败静默伪装成「没有数据」。本类型由紧凑构造器在<b>构造期</b>拒绝两种非法组合，
 * 让实现者的错误立刻暴露，而不是等到现场排查。</p>
 *
 * @param value   规范值（失败时为 {@code null}；成功时非空）
 * @param failure 失败原因（成功时为 {@code null}）
 * @author wenbin
 * @since 2026-10-17
 */
public record DecodeOutcome(@Nullable Object value, @Nullable DecodeFailure failure) {

    /**
     * 紧凑构造器：成功必须有值、失败必须带原因。
     */
    public DecodeOutcome {
        if (failure == null && value == null) {
            throw new IllegalArgumentException(
                    "successful decoding must carry a value; "
                            + "use DecodeOutcome.failed(...) to express an empty result");
        }
        if (failure != null && value != null) {
            throw new IllegalArgumentException("failed decoding must not carry a value");
        }
    }

    /**
     * 成功结果。
     *
     * @param value 规范值（非空）
     * @return 成功结果
     */
    public static DecodeOutcome ok(Object value) {
        return new DecodeOutcome(Objects.requireNonNull(value, "value must not be null"), null);
    }

    /**
     * 失败结果。
     *
     * @param failure 失败原因（非空）
     * @return 失败结果
     */
    public static DecodeOutcome failed(DecodeFailure failure) {
        return new DecodeOutcome(null, Objects.requireNonNull(failure, "failure must not be null"));
    }

    /**
     * 是否成功。
     *
     * @return 成功返回 {@code true}
     */
    public boolean success() {
        return failure == null;
    }
}
