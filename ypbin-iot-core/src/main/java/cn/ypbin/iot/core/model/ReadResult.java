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

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * 读结果。
 *
 * <p>部分点位读取失败时<b>不</b>异常完成，而在点位级以
 * {@link Quality#BAD} 加原因表达，保留其余成功点位；
 * 只有整条链路不可用时才异常完成。</p>
 *
 * @param values  点位值列表
 * @param elapsed 耗时
 * @author wenbin
 * @since 2026-09-13
 */
public record ReadResult(List<PointValue> values, Duration elapsed) {

    /**
     * 紧凑构造器：保证集合非空且不可变。
     */
    public ReadResult {
        values = values == null ? List.of() : List.copyOf(values);
        elapsed = elapsed == null ? Duration.ZERO : elapsed;
        Objects.requireNonNull(values, "values must not be null");
    }

    /**
     * 失败点位数。
     *
     * @return 质量非 GOOD 的点位数
     */
    public long failureCount() {
        return values.stream().filter(value -> !value.isGood()).count();
    }
}
