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
package cn.ypbin.iot.spring.subscription;

import cn.ypbin.iot.core.exception.IotException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.util.Stages;
import java.util.Objects;

/**
 * 一个订阅失败地址及其原因。
 *
 * <p><b>原因用消息键而不是文案</b>（AGENTS I2）：{@code reasonKey} 形如
 * {@code iot.common.subscription.failed}，由宿主在日志/接口层用
 * {@code I18nUtil.message(key, args)} 解析为当前语言文案；
 * {@code detail} 只放原始异常摘要供排查，<b>不得</b>直接展示给最终用户。</p>
 *
 * @param address   失败的地址
 * @param reasonKey 失败原因消息键（{@code iot.*}）
 * @param detail    原始异常摘要；无额外信息时为空串
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionFailure(PointAddress address, String reasonKey, String detail) {

    /**
     * 紧凑构造器：校验必填项并归一化可空字段。
     */
    public SubscriptionFailure {
        Objects.requireNonNull(address, "address must not be null");
        reasonKey = reasonKey == null || reasonKey.isBlank()
                ? IotMessageKeys.SUBSCRIPTION_FAILED : reasonKey;
        detail = detail == null ? "" : detail;
    }

    /**
     * 以异常构造失败项（自动从 {@code IotException} 提取消息键）。
     *
     * @param address 失败地址
     * @param error   原始异常（内部会解包 {@code CompletionException}）
     * @return 失败项
     */
    public static SubscriptionFailure of(PointAddress address, Throwable error) {
        Objects.requireNonNull(error, "error must not be null");
        Throwable unwrapped = Stages.unwrap(error);
        String reasonKey = unwrapped instanceof IotException iotException
                && iotException.getMessageKey() != null && !iotException.getMessageKey().isBlank()
                ? iotException.getMessageKey()
                : IotMessageKeys.SUBSCRIPTION_FAILED;
        String detail = unwrapped.getMessage() == null ? "" : unwrapped.getMessage();
        return new SubscriptionFailure(address, reasonKey, detail);
    }
}
