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

/**
 * 协议扩展能力标记接口。
 *
 * <p>只属于部分协议的能力（目录浏览、历史读、方法调用、文件传输等）一律做成独立接口，
 * 由适配器实现并通过 {@link ProtocolConnection#unwrap(Class)} /
 * {@link DeviceSession#unwrap(Class)} 暴露，从而保持主契约精简。</p>
 *
 * <p>第三方协议模块<b>可以</b>定义自己的扩展接口，约束只有两条：
 * ① 必须继承本接口；② 必须在
 * {@link ProtocolDescriptor#extensions()} 中显式声明。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ProtocolExtension {

    /**
     * 扩展所属的协议标识。
     *
     * @return 协议标识
     */
    ProtocolCode protocol();
}
