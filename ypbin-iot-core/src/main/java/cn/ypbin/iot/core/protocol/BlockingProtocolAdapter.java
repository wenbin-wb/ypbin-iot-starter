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
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

/**
 * 阻塞式协议适配器基类：把同步实现桥接为异步契约。
 *
 * <p>子类只需按同步风格实现 {@code *Blocking} 方法，框架用
 * {@link AdapterContext#scheduler()} 提供的执行器承载调用。</p>
 *
 * <p><b>执行器选择</b>：默认使用虚拟线程执行器（阻塞时优雅卸载，不占载体线程）。
 * 若实现内部会调用 <b>JNI / native</b>，必须覆写 {@link #requiresPlatformThread()}
 * 返回 {@code true}——依据 JEP 444，虚拟线程执行 native 方法时会 pinning 到载体线程，
 * 且该限制<b>不可移除</b>。</p>
 *
 * <p><b>必须避开的 JDK 21 pinning 陷阱</b>：虚拟线程在 {@code synchronized} 块内阻塞会钉住
 * 载体线程。子类实现中请使用 {@link java.util.concurrent.locks.ReentrantLock} 而非
 * {@code synchronized}。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public abstract class BlockingProtocolAdapter implements ProtocolAdapter {

    /**
     * 本适配器是否必须运行在平台线程上（默认 {@code false}）。
     *
     * <p>凡是实现内部会调用 JNI / native 的适配器<b>必须</b>覆写为 {@code true}：
     * 串口（jSerialComm）、CAN（JavaCAN）、媒体编解码（JavaCV/FFmpeg）等。</p>
     *
     * @return {@code true} 表示使用平台线程执行器
     */
    protected boolean requiresPlatformThread() {
        return false;
    }

    /**
     * 同步打开物理链路。语义与 {@link ProtocolAdapter#open} 一致，只是允许阻塞。
     *
     * @param spec    物理连接规格
     * @param context 适配器运行时上下文
     * @return 已完成握手的链路
     */
    protected abstract ProtocolConnection openBlocking(ConnectionSpec spec, AdapterContext context);

    /**
     * 同步绑定逻辑设备，默认返回 {@code connection.session()}。
     *
     * @param connection 物理链路
     * @param device     逻辑设备规格
     * @param context    适配器运行时上下文
     * @return 设备会话
     */
    protected DeviceSession bindBlocking(
            ProtocolConnection connection, DeviceSpec device, AdapterContext context) {
        return connection.session();
    }

    /**
     * 同步探测，默认以不支持能力异常结束。
     *
     * @param spec    待探测规格
     * @param context 适配器运行时上下文
     * @return 探测结果
     */
    protected ProbeResult probeBlocking(ConnectionSpec spec, AdapterContext context) {
        throw new UnsupportedOperationException(
                "probeBlocking not implemented for " + descriptor().code());
    }

    @Override
    public final CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
        return supplyAsync(context, () -> openBlocking(spec, context));
    }

    @Override
    public final CompletionStage<DeviceSession> bind(
            ProtocolConnection connection, DeviceSpec device, AdapterContext context) {
        return supplyAsync(context, () -> bindBlocking(connection, device, context));
    }

    @Override
    public final CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        return supplyAsync(context, () -> probeBlocking(spec, context));
    }

    private <T> CompletionStage<T> supplyAsync(AdapterContext context, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor(context));
    }

    /**
     * 按 {@link #requiresPlatformThread()} 选择执行器。
     *
     * <p>这里是「native 调用禁止上虚拟线程」这条硬约束的<b>唯一执行点</b>——
     * 适配器作者只需覆写一个开关，不需要理解 pinning 机制，也不会写错。</p>
     */
    private ExecutorService executor(AdapterContext context) {
        return requiresPlatformThread()
                ? context.scheduler().platformThreadExecutor()
                : context.scheduler().virtualThreadExecutor();
    }
}
