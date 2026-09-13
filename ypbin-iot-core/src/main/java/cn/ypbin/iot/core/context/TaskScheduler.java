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
import java.util.concurrent.ExecutorService;

/**
 * 调度与执行器分配。
 *
 * <p>框架实现基于分层时间轮而非每设备一个 {@code ScheduledFuture}：
 * 10 万设备的周期采集若各自持有定时任务，仅定时器堆就会带来可观的 GC 压力与精度抖动。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface TaskScheduler {

    /**
     * 注册固定间隔任务。
     *
     * @param task         任务体，异常会被捕获并记录
     * @param initialDelay 首次延迟
     * @param interval     间隔
     * @return 可取消的任务句柄
     */
    ScheduledTask schedule(Runnable task, Duration initialDelay, Duration interval);

    /**
     * 注册一次性延迟任务。
     *
     * @param task  任务体
     * @param delay 延迟
     * @return 可取消的任务句柄
     */
    ScheduledTask scheduleOnce(Runnable task, Duration delay);

    /**
     * 虚拟线程执行器：承载<b>阻塞式、纯 Java</b> 协议栈的调用，按需创建、无池化上限。
     *
     * @return 虚拟线程执行器
     */
    ExecutorService virtualThreadExecutor();

    /**
     * 平台线程执行器：<b>经 JNI 的调用专用</b>，有界池。
     *
     * <p>依据 JEP 444，虚拟线程执行 native 方法时会 pinning 到载体线程，且该限制
     * <b>不可移除</b>（JEP 491 只修复了 {@code synchronized} 那条）。
     * 因此串口（jSerialComm）、CAN（JavaCAN）、媒体（JavaCV/FFmpeg）等调用
     * <b>必须</b>使用本执行器。</p>
     *
     * <p>池大小应按<b>物理资源数</b>（串口数 / CAN 通道数）而非设备数配置。</p>
     *
     * @return 平台线程执行器
     */
    ExecutorService platformThreadExecutor();

    /**
     * 可取消的任务句柄。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    interface ScheduledTask {

        /** 取消任务。 */
        void cancel();

        /**
         * 是否已取消。
         *
         * @return 已取消返回 {@code true}
         */
        boolean isCancelled();
    }
}
