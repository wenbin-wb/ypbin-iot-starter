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

import java.time.Duration;

/**
 * 指标埋点门面。core 零 Spring 依赖（刻意不暴露 Micrometer 类型）。
 *
 * <p><b>实现约束：方法会在共享 IO 线程（Netty event loop）、协议库回调线程（HiveMQ / Milo 共享
 * 执行器 / digitalpetri）、框架调度器线程或调用方线程上被同步调用，实现必须非阻塞。</b>
 * 严禁网络调用、磁盘写入或获取锁；需要异步上报时请在实现内部投递到有界队列并计数丢弃。</p>
 */
public interface MetricsRecorder {

    /**
     * 记录一次读操作。
     *
     * @param elapsed 耗时
     * @param success 是否成功
     */
    void recordRead(Duration elapsed, boolean success);

    /**
     * 记录一次写操作。
     *
     * @param elapsed 耗时
     * @param success 是否成功
     */
    void recordWrite(Duration elapsed, boolean success);

    /**
     * 记录一次订阅推送的批大小。
     *
     * @param pointCount 点位数
     */
    void recordSubscriptionBatch(int pointCount);

    /**
     * 记录一次异常。
     *
     * @param errorType 异常分类标签，应使用有限枚举值而非异常消息（避免标签基数爆炸）
     */
    void recordError(String errorType);

    /**
     * 记录一个瞬时值（连接数、在途请求数等）。
     *
     * @param name  指标名，框架侧只接受白名单内的名字
     * @param value 当前值
     */
    void gauge(String name, double value);
}
