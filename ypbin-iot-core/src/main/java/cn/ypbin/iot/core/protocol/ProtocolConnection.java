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

import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.SessionState;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 物理链路句柄：一条 TCP / UDP / 串口 / DTLS 会话。
 *
 * <p>由 {@link ProtocolAdapter#open} 创建，可被多个 {@link DeviceSession} 共享
 * （Modbus 网关、BACnet 路由器等场景）。引用计数与空闲回收由框架的
 * {@code ConnectionRegistry} 负责，适配器只需如实反映链路状态。</p>
 *
 * <p><b>线程安全</b>：{@link #whenClosed()} 可能被多线程注册，实现必须使用并发安全结构。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ProtocolConnection {

    /**
     * 链路唯一标识（框架分配，全局唯一且稳定）。
     *
     * @return 链路标识
     */
    String connectionId();

    /**
     * 链路端点。
     *
     * @return 端点
     */
    Endpoint endpoint();

    /**
     * 链路状态。
     *
     * @return 状态
     */
    SessionState state();

    /**
     * 建链完成时刻。
     *
     * @return 建链时刻
     */
    Instant openedAt();

    /**
     * 该链路的默认设备会话（1:1 协议的快捷入口）。
     *
     * <p>1:N 协议的实现可以返回一个共享会话，或抛出
     * {@link cn.ypbin.iot.core.exception.UnsupportedCapabilityException}
     * 要求调用方走 {@link ProtocolAdapter#bind}。无论哪种，都必须在协议模块文档中写明。</p>
     *
     * @return 设备会话
     */
    DeviceSession session();

    /**
     * 链路关闭后完成的 Stage（正常关闭与异常断开都会完成）。
     *
     * <p>框架据此触发重连。该 Stage <b>只完成一次</b>；重复调用返回同一 Stage 或等价视图。</p>
     *
     * @return 关闭原因 Stage
     */
    CompletionStage<CloseReason> whenClosed();

    /**
     * 链路的对端描述信息（型号、固件版本、序列号等），用于测试连接展示与资产核对。
     *
     * @return 只读描述信息；未知时返回空 Map
     */
    Map<String, String> describe();

    /**
     * 取协议扩展能力。
     *
     * @param extensionType 扩展接口类型
     * @param <T>           扩展类型
     * @return 支持时返回实例，否则返回空 Optional
     */
    <T> Optional<T> unwrap(Class<T> extensionType);

    /**
     * 主动关闭链路。幂等：重复调用与已关闭后调用均为无操作。
     *
     * <p>关闭后 {@link #whenClosed()} 以 {@link cn.ypbin.iot.core.model.CloseCause#CLIENT_REQUEST}
     * 原因完成。实现必须确保底层 socket 与 native 句柄被释放。</p>
     */
    void close();
}
