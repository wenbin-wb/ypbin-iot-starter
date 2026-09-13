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

import java.util.List;

/**
 * 设备配置校验结果。
 *
 * <p>返回结果而非抛异常：校验失败是<b>正常的业务结果</b>（用户填错了配置），
 * 不是程序错误。宿主据此向用户展示具体原因。</p>
 *
 * @param passed  是否通过
 * @param reasons 未通过时的逐项原因；通过时为空列表
 * @author wenbin
 * @since 2026-09-13
 */
public record ValidationResult(boolean passed, List<String> reasons) {

    /**
     * 紧凑构造器：保证集合非空且不可变；通过时强制清空原因。
     */
    public ValidationResult {
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        if (passed) {
            reasons = List.of();
        }
    }

    /**
     * 校验通过。
     *
     * @return 校验结果
     */
    public static ValidationResult ok() {
        return new ValidationResult(true, List.of());
    }

    /**
     * 校验失败。
     *
     * @param reasons 失败原因（i18n 消息键）
     * @return 校验结果
     */
    public static ValidationResult fail(String... reasons) {
        return new ValidationResult(false, List.of(reasons));
    }

    /**
     * 校验失败。
     *
     * @param reasons 失败原因列表
     * @return 校验结果
     */
    public static ValidationResult fail(List<String> reasons) {
        return new ValidationResult(false, reasons);
    }
}
