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

import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 逻辑设备句柄：设备维度的读写订阅入口。
 *
 * <p>由 {@link ProtocolAdapter#bind} 创建，生命周期与设备绑定解绑一致。
 * 一个会话内部通常串行化请求（工业协议多为请求-响应语义）；若协议支持流水线，
 * 由适配器自行控制并发深度，但必须遵守
 * {@link cn.ypbin.iot.core.context.AdapterSettings#maxPendingRequests()}。</p>
 *
 * <p><b>能力约束</b>：调用未在 {@link ProtocolDescriptor#capabilities()} 中声明的能力方法时，
 * 必须抛出 {@link cn.ypbin.iot.core.exception.UnsupportedCapabilityException}，
 * 不得返回空结果或伪造成功。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DeviceSession {

    /**
     * 会话唯一标识。
     *
     * @return 会话标识
     */
    String sessionId();

    /**
     * 设备规格。
     *
     * @return 设备规格
     */
    DeviceSpec device();

    /**
     * 所属物理链路标识。
     *
     * @return 链路标识
     */
    String connectionId();

    /**
     * 会话状态。
     *
     * @return 状态
     */
    SessionState state();

    /**
     * 绑定完成时刻。
     *
     * @return 绑定时刻
     */
    Instant boundAt();

    /**
     * 批量读取点位。
     *
     * <p>协议相关的切分由适配器负责，对调用方保持「一次请求一次结果」的语义。</p>
     *
     * <p>部分点位读取失败时<b>不要</b>让整个 Stage 异常完成：在 {@link ReadResult} 中把这些点位
     * 标记为 {@link cn.ypbin.iot.core.model.Quality#BAD} 并给出原因，保留其余成功点位。
     * 只有当整条链路不可用时才让 Stage 异常完成。</p>
     *
     * @param request 读请求
     * @return 读结果 Stage
     */
    CompletionStage<ReadResult> read(ReadRequest request);

    /**
     * 批量写入点位。
     *
     * <p>与读同理：部分失败通过
     * {@link cn.ypbin.iot.core.model.PointWriteStatus#success()} 表达，
     * 不让整个 Stage 异常完成。</p>
     *
     * @param request 写请求
     * @return 写结果 Stage
     */
    CompletionStage<WriteResult> write(WriteRequest request);

    /**
     * 订阅点位变化。
     *
     * <p><b>数据流向</b>：订阅到的数据默认经
     * {@link cn.ypbin.iot.core.context.AdapterContext#egress()} 出口；
     * 若调用方传入非空 {@code listener}，则由该 listener 消费，不再走 egress，
     * 避免同一份数据被投递两次。</p>
     *
     * @param request  订阅请求
     * @param listener 点位回调；为 {@code null} 表示走框架统一出口
     * @return 订阅句柄 Stage
     */
    CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener);

    /**
     * 取消订阅。幂等：句柄已失效时返回已完成的 Stage。
     *
     * @param handle 订阅句柄
     * @return 取消完成后完成的 Stage
     */
    CompletionStage<Void> unsubscribe(SubscriptionHandle handle);

    /**
     * 链路保活探测：发送协议级心跳或最小代价请求，验证会话仍然可用。
     *
     * <p>与 {@link ProtocolAdapter#probe} 的区别：probe 是「没连接时测试能不能连」，
     * ping 是「已连接时测试还活着吗」。</p>
     *
     * @return 探测结果 Stage，永不异常完成（超时通过 {@code alive=false} 表达）
     */
    CompletionStage<PingResult> ping();

    /**
     * 取协议扩展能力。
     *
     * @param extensionType 扩展接口类型，必须是 {@link ProtocolExtension} 的子接口
     * @param <T>           扩展类型
     * @return 支持时返回实例，否则返回空 Optional
     */
    <T> Optional<T> unwrap(Class<T> extensionType);

    /**
     * 解绑设备并释放会话资源。幂等。
     *
     * @return 释放完成后完成的 Stage
     */
    CompletionStage<Void> close();
}
