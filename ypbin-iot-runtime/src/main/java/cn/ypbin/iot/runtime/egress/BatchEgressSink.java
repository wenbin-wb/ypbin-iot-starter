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
package cn.ypbin.iot.runtime.egress;

import java.util.List;

/**
 * 微批出口的宿主消费实现（替换缝）。
 *
 * <p>宿主只需实现「如何把一批元素送出去」——写 HTTP、发 Kafka、批量落库都可以；
 * 有界缓冲、并发控制、丢弃计数、优雅停机全部由 {@link BoundedBatchEgressQueue} 兜底。</p>
 *
 * <h2>调用契约</h2>
 * <ul>
 *   <li><b>线程</b>：由框架的<b>唯一</b>消费线程调用；仅在 {@code close()} 的兜底 flush 阶段
 *       改由关闭线程调用，且此时消费线程已确认退出，因此实现<b>不必</b>为与自身并发调用加锁
 *       （但仍可能被不同线程先后调用，跨批状态需自行保证可见性）；</li>
 *   <li><b>批次</b>：参数是 {@link List#copyOf} 产物（不可变、元素非空、长度
 *       {@code 1..batchSize}）；实现<b>不得</b>持有该列表以外的可变假设，也不要在方法内长期持有；</li>
 *   <li><b>异常</b>：抛 {@link RuntimeException} 视为<b>本批投递失败</b>——框架会记录 ERROR 全堆栈、
 *       累加失败计数，然后继续下一批；该批<b>不会重投</b>。需要重试请在自己的 Sink 内做
 *       （并自行限制重试与超时）。</li>
 * </ul>
 *
 * @param <T> 元素类型
 * @author wenbin
 * @since 2026-10-17
 */
@FunctionalInterface
public interface BatchEgressSink<T> {

    /**
     * 消费一批元素。
     *
     * <p>本方法在消费线程上同步执行，<b>其耗时直接决定队列消化速度</b>；阻塞过久会导致队列满，
     * 入队侧按「丢新」处理（可观测，不会反压协议线程）。远程调用必须显式设置超时。</p>
     *
     * @param batch 不可变批次，非空
     */
    void accept(List<T> batch);
}
