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

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 订阅编排器：把「点位清单 + 已建链会话」变成协议层的订阅调用，并承担重订、部分成功、退避与去重。
 *
 * <p><b>为什么这项能力必须在框架里</b>（issue #37）：框架此前只提供 {@code SubscribeRequest}
 * 这个数据模型和 {@code DeviceSession#subscribe} 这个单次调用，<b>没有任何订阅编排的承接方</b>——
 * 于是「哪个点位要订、什么时候重订、失败了怎么退避、部分成功怎么补」全留给宿主。
 * 这些逻辑不含任何业务语义（不需要知道点位代表什么值），却需要每个 IoT 宿主重新发明一遍，
 * 漏一条就是运行期故障：漏掉逐点位分流 ⇒ 裸 TCP 只交付第 0 个点位；漏掉只补失败地址 ⇒
 * 每轮全量重订、同一点位重复读数；漏掉按会话实例判重订 ⇒ 重连后订阅不刷新、数据永久停更。</p>
 *
 * <p><b>宿主保留的职责只有两件</b>：①「哪些设备归我」（租约/归属判断，含业务语义）；
 * ②「点位 → 地址范围」的映射数据源（见 {@link SubscriptionPoint}）。</p>
 *
 * <p><b>线程模型</b>：实现必须不阻塞调用线程（可能运行在调度器线程上），
 * 因此全部方法返回 {@link CompletionStage}；协议层的异步语义原样透传。</p>
 *
 * <p><b>可覆盖</b>：自动配置以 {@code @ConditionalOnMissingBean} 注册默认实现，
 * 宿主提供自己的 {@code SubscriptionOrchestrator} Bean 即整体替换（AGENTS R2）。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public interface SubscriptionOrchestrator {

    /**
     * 为单个设备发起订阅（内部完成展开、分流、去重、退避判断与结果汇总）。
     *
     * <p>返回的 Stage <b>不会</b>异常完成：设备级失败通过
     * {@link SubscriptionResult#status()} 与 {@link SubscriptionOutcome#failures()} 表达
     * （部分失败逐项标记，AGENTS I7）。只有调用方违反契约（如传 {@code null}）才同步抛异常。</p>
     *
     * @param item 设备 + 会话 + 点位 + 回调
     * @return 订阅结果 Stage
     */
    CompletionStage<SubscriptionResult> subscribe(SubscriptionBatchItem item);

    /**
     * 为一批设备发起订阅（每台设备独立成败，互不影响）。
     *
     * @param items 设备批次
     * @return 全部设备都有结果后完成的 Stage（顺序与输入一致）
     */
    default CompletionStage<List<SubscriptionResult>> subscribeAll(Collection<SubscriptionBatchItem> items) {
        if (items == null || items.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        List<CompletableFuture<SubscriptionResult>> futures = items.stream()
                .map(item -> subscribe(item).toCompletableFuture())
                .toList();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .thenApply(ignored -> futures.stream().map(CompletableFuture::join).toList());
    }

    /**
     * 忘记某设备的订阅跟踪（设备被移除、归属转移或宿主停止采集时调用）。
     *
     * <p>不清理会留下两类残留：① 同 deviceId 重新出现时被旧的「已成功地址集」挡住；
     * ② 退避窗口跨设备生命周期存活。因此移除设备时必须调用本方法。</p>
     *
     * @param deviceId 设备标识
     */
    void forget(String deviceId);
}
