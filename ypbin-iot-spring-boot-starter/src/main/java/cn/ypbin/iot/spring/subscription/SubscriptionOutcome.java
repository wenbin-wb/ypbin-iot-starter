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

import cn.ypbin.iot.core.model.PointAddress;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 一次订阅尝试的结果：成功地址集合 + 失败地址及原因。
 *
 * <p><b>为什么必须是「部分成功」而不是全有全无</b>：逐点位单订阅形态下，一个设备的 N 个地址是
 * N 次独立调用，中途失败的语义只能是「M 个成功、N-M 个失败」。若把它折叠成一次布尔或抛异常，
 * 宿主只能二选一：<b>全量重试</b>（已成功地址被重复订阅 ⇒ 同一点位重复读数、监听器重复挂）
 * 或<b>放弃剩余地址</b>（数据永久缺失）。因此结果必须逐地址表达（AGENTS I7），
 * 并让「只补失败地址」成为唯一自然的下一步。</p>
 *
 * <p>集合一律不可变、恒非空（AGENTS R12）：无失败时 {@link #failures()} 返回空列表而不是 {@code null}。</p>
 *
 * @param subscribed 成功的地址（已去重）
 * @param failures   失败的地址与原因；无失败时为空列表
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionOutcome(Set<PointAddress> subscribed, List<SubscriptionFailure> failures) {

    /**
     * 紧凑构造器：归一化为不可变且恒非空的集合。
     */
    public SubscriptionOutcome {
        subscribed = subscribed == null ? Set.of() : Set.copyOf(subscribed);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }

    /**
     * 全部成功的结果。
     *
     * @param addresses 成功地址
     * @return 结果
     */
    public static SubscriptionOutcome success(Set<PointAddress> addresses) {
        return new SubscriptionOutcome(addresses, List.of());
    }

    /**
     * 全部失败的结果。
     *
     * @param failures 失败项
     * @return 结果
     */
    public static SubscriptionOutcome failed(List<SubscriptionFailure> failures) {
        return new SubscriptionOutcome(Set.of(), failures);
    }

    /**
     * 合并两次结果（成功取并集、失败按地址去重并保留首次出现的原因）。
     *
     * <p>用于把「同一轮里多次单地址订阅」与「按失败地址补订」的成败汇总成一份设备级视图。</p>
     *
     * @param other 另一次结果；{@code null} 视为空结果
     * @return 合并后的结果
     */
    public SubscriptionOutcome merge(SubscriptionOutcome other) {
        if (other == null) {
            return this;
        }
        Set<PointAddress> mergedSubscribed = new LinkedHashSet<>(subscribed);
        mergedSubscribed.addAll(other.subscribed);
        Set<PointAddress> seenFailures = new LinkedHashSet<>();
        List<SubscriptionFailure> mergedFailures = new ArrayList<>(failures.size() + other.failures.size());
        for (SubscriptionFailure failure : failures) {
            if (seenFailures.add(failure.address())) {
                mergedFailures.add(failure);
            }
        }
        for (SubscriptionFailure failure : other.failures()) {
            if (seenFailures.add(failure.address())) {
                mergedFailures.add(failure);
            }
        }
        return new SubscriptionOutcome(mergedSubscribed, mergedFailures);
    }

    /**
     * 需要补订的地址（即失败地址）。
     *
     * <p><b>「部分成功只补失败地址」的唯一入口</b>：下一轮把本集合交给编排器，
     * 已成功地址不会被重复订阅。刻意不提供「取全部地址」的便捷方法——那会让调用方顺手写成全量重试。</p>
     *
     * @return 失败地址集合（按失败列表顺序）
     */
    public Set<PointAddress> retryAddresses() {
        Set<PointAddress> retry = new LinkedHashSet<>();
        for (SubscriptionFailure failure : failures) {
            retry.add(failure.address());
        }
        return Set.copyOf(retry);
    }

    /**
     * 是否全部成功。
     *
     * @return 无失败项时返回 {@code true}
     */
    public boolean isFullSuccess() {
        return failures.isEmpty();
    }

    /**
     * 是否全部失败（无任何成功地址）。
     *
     * @return 成功集为空且存在失败项时返回 {@code true}
     */
    public boolean isTotalFailure() {
        return subscribed.isEmpty() && !failures.isEmpty();
    }
}
