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
package cn.ypbin.iot.core.context;

/**
 * 宿主回调投递器：把宿主代码从协议线程上卸载下来。
 *
 * <p><b>为什么必须有这一层</b>：协议库的回调（MQTT 的 IO 线程、Milo 的共享执行器、
 * 各类 Netty EventLoop）上执行的是<b>任意宿主代码</b>。宿主在 {@code DataListener.onData} 里
 * 顺手做一次落库或 HTTP 调用（这在现场极其常见），就会阻塞整条协议链路：
 * 同一条 Modbus 网关链路上的 200 个从站、或同一 JVM 内所有 Milo 连接一起被拖住。
 * 这是本仓 I4「协议线程上禁止任何阻塞」要防的核心问题。</p>
 *
 * <p><b>必须有界</b>：只把回调丢到执行器是不够的——生产快于消费时任务会无限堆积，
 * 最终把进程内存吃光。因此这里是<b>有界投递</b>：超出上限即丢弃、计数、并按时间窗限流告警，
 * 而不是无限缓冲（也绝不静默丢弃）。</p>
 *
 * <p>实现必须保证：{@link #dispatch} 本身<b>不阻塞</b>（它是在协议线程上调用的）。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
public interface DeliveryDispatcher {

    /**
     * 把一次宿主回调递交到框架执行器。
     *
     * @param task 回调任务
     * @return 已受理返回 {@code true}；因过载被丢弃返回 {@code false}
     */
    boolean dispatch(Runnable task);

    /**
     * 累计因过载被丢弃的回调数。
     *
     * @return 丢弃数
     */
    long droppedCount();

    /**
     * 当前在途（已受理未完成）的回调数。
     *
     * @return 在途数
     */
    int inFlight();
}
