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

import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.time.Clock;

/**
 * 适配器运行时上下文：框架注入给适配器的<b>全部宿主能力</b>。
 *
 * <p>这是适配器与外界交互的唯一通道。适配器<b>不得</b>直接使用静态单例、直接创建线程、
 * 直接读环境变量或直接访问数据库——所有这些能力都必须经由本接口获取，原因有三：
 * 一是可测试（测试可注入假实现），二是可治理（框架统一限流、埋点、回收），
 * 三是可移植（core 零 Spring，脱离容器也能跑）。</p>
 *
 * <p><b>生命周期</b>：一个 {@code ProtocolAdapter} 实例对应一个 {@code AdapterContext}，
 * 在适配器注册时创建，在容器关闭时随适配器一起失效。实现必须线程安全。</p>
 *
 * <p><b>作用域</b>：上下文是适配器级而非设备级或会话级。设备与会话相关数据请放在
 * {@link cn.ypbin.iot.core.protocol.DeviceSession} 里。</p>
 *
 * <p><b>刻意不提供</b>独立执行器方法：所有执行器统一从 {@link #scheduler()} 获取，
 * 避免出现「两个地方都能拿线程池」从而绕过 native pinning 约束的口子。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface AdapterContext {

    /**
     * 当前适配器的协议标识。
     *
     * @return 协议标识
     */
    ProtocolCode protocol();

    /**
     * 配置门面：框架通用参数已显式建模，协议特有参数走类型化查表。
     *
     * @return 配置门面
     */
    AdapterSettings settings();

    /**
     * 数据与事件出口。
     *
     * @return 数据出口
     */
    DataEgress egress();

    /**
     * 调度能力：周期任务、一次性任务、虚拟线程执行器与平台线程执行器。
     *
     * <p>适配器需要定时轮询、心跳保活、超时控制时一律使用本接口，
     * <b>严禁</b>自行创建线程或线程池。</p>
     *
     * @return 调度器
     */
    TaskScheduler scheduler();

    /**
     * 宿主回调投递器。
     *
     * <p><b>协议实现必须用它来调用宿主回调</b>（{@code DataListener.onData}），
     * 不得在协议库的回调线程上直接执行宿主代码——那等于把任意宿主代码放上 EventLoop/IO 线程。</p>
     *
     * @return 投递器
     */
    DeliveryDispatcher delivery();

    /**
     * 适配器级资源登记处。
     *
     * @return 资源登记处
     */
    ResourceRegistry resources();

    /**
     * 指标埋点门面。
     *
     * @return 指标门面
     */
    MetricsRecorder metrics();

    /**
     * 凭据解析。
     *
     * @return 凭据解析器
     */
    CredentialResolver credentials();

    /**
     * 时钟：所有时间戳取时统一走这里，便于测试注入固定时钟与做时钟漂移校正。
     *
     * @return 时钟
     */
    Clock clock();

    /**
     * 取一个有界地址解析缓存。
     *
     * <p>同一 {@code namespace} 重复调用返回同一实例；不同协议模块应使用不同 namespace
     * （建议用协议 code）以避免类型串扰。</p>
     *
     * @param namespace   缓存命名空间
     * @param maximumSize 最大条目数，超出后按 LRU 淘汰
     * @param <A>         解析后的地址类型
     * @return 有界地址缓存
     */
    <A> BoundedAddressCache<A> addressCache(String namespace, int maximumSize);

    /**
     * 记录一条适配器级结构化日志事件。
     *
     * <p><b>消息内容是消息键而非文案</b>：{@code message} 应为
     * {@code iot.<protocol>.<category>.<detail>} 形式的键，由框架按 i18n 解析；
     * 禁止直接传中文或英文字面量。</p>
     *
     * @param level   日志级别
     * @param message 消息键（占位符 {@code {}}）
     * @param args    消息参数
     */
    void log(LogLevel level, String message, Object... args);
}
