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
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.util.Stages;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * 协议适配器 SPI —— 新增一种物联网协议的唯一实现入口。
 *
 * <p>适配器是<b>无状态单例</b>：一个协议一个实例，由容器持有并被所有设备共享。
 * 所有与「某条链路 / 某个设备」相关的状态必须存放在 {@link ProtocolConnection} 与
 * {@link DeviceSession} 中，适配器实例本身不得持有可变字段（否则 10 万连接下必然串扰）。</p>
 *
 * <p><b>最小实现</b>：只需实现 {@link #descriptor()} 与
 * {@link #open(ConnectionSpec, AdapterContext)}。{@link #bind} 默认把物理链路当作
 * 1:1 设备会话。</p>
 *
 * <p><b>线程模型</b>：本接口全部方法返回 {@link CompletionStage}，实现方必须保证
 * <b>不阻塞调用线程</b>——框架可能在 Netty EventLoop 上发起调用，阻塞 EventLoop 会连带
 * 拖垮该 EventLoop 上的全部连接。若目标协议库只有阻塞式 API，请继承
 * {@link BlockingProtocolAdapter}，由框架用虚拟线程（或平台线程）承载阻塞调用。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ProtocolAdapter {

    /**
     * 协议身份与能力声明。
     *
     * <p>该方法的返回值会在适配器注册时被读取并<b>缓存</b>，实现方应返回稳定对象
     * （建议在字段中构造一次），不要在方法内做 I/O 或重量级计算。</p>
     *
     * @return 协议描述符，不得为 {@code null}
     */
    ProtocolDescriptor descriptor();

    /**
     * 该适配器实例支持的能力集合。
     *
     * @return 不可变能力集合；无任何能力时返回空集合，不得返回 {@code null}
     */
    default Set<ProtocolCapability> capabilities() {
        return descriptor().capabilities();
    }

    /**
     * 打开一条物理链路。
     *
     * <p>框架负责调用时机与去重：相同 {@link ConnectionSpec#connectionId()} 的并发
     * {@code open} 请求会被合并为一次真实建链（单飞），并在最后一个设备会话解绑后
     * 按空闲超时关闭。适配器无需自己实现连接池。</p>
     *
     * <p>返回的 {@link ProtocolConnection} 必须已完成协议层握手，
     * 即 <b>Stage 完成即代表链路可用</b>。建链失败时以
     * {@link cn.ypbin.iot.core.exception.ConnectionException} 异常完成 Stage，
     * 不要返回一个半可用的连接。</p>
     *
     * @param spec    物理连接规格
     * @param context 适配器运行时上下文
     * @return 链路就绪后完成的 Stage
     */
    CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context);

    /**
     * 在已打开的物理链路上绑定一个逻辑设备。
     *
     * <p>默认实现把物理链路当作 1:1 设备会话，适用于 OPC UA、MQTT、HTTP 等
     * 「一条链路即一个设备」的协议。</p>
     *
     * <p>对于「一条链路承载 N 个从站」的协议（Modbus 网关多 unitId、BACnet 多设备实例、
     * KNX 多物理地址），必须覆写本方法：在共享链路上按
     * {@link DeviceSpec#localAddress()} 建立逻辑视图，<b>不得为每个设备重新建链</b>。</p>
     *
     * @param connection 由 {@link #open} 返回且尚未关闭的物理链路
     * @param device     逻辑设备规格
     * @param context    适配器运行时上下文
     * @return 设备会话就绪后完成的 Stage
     */
    default CompletionStage<DeviceSession> bind(
            ProtocolConnection connection, DeviceSpec device, AdapterContext context) {
        return CompletableFuture.completedFuture(connection.session());
    }

    /**
     * 连通性探测：不改动任何设备状态，仅验证能否按该规格连上并识别对端。
     *
     * <p>默认实现不提供探测能力，返回 {@link UnsupportedCapabilityException} 异常完成的 Stage。
     * 实现方若覆写，<b>本方法永不抛出异常</b>：探测失败属于正常结果，通过
     * {@link ProbeResult#reachable()} 为 {@code false} 表达。</p>
     *
     * @param spec    待探测的连接规格
     * @param context 适配器运行时上下文
     * @return 探测结果 Stage
     */
    default CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        return Stages.failed(new UnsupportedCapabilityException(descriptor().code(), "probe"));
    }

    /**
     * 适配器级释放：协议库持有全局单例资源（native 库句柄、共享 EventLoopGroup、
     * mDNS 发现线程）时在此关闭。
     *
     * <p>容器关闭时调用一次。设备级与会话级资源应在
     * {@link ProtocolConnection#close()} / {@link DeviceSession#close()} 中释放。</p>
     */
    default void close() {
        // 默认无适配器级资源
    }
}
