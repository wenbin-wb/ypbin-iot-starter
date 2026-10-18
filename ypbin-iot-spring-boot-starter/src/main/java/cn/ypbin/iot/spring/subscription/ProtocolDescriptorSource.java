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
package cn.ypbin.iot.spring.subscription;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 协议描述符来源：订阅编排据此读取协议<b>自己声明的</b>订阅寻址方式与扩展能力。
 *
 * <p><b>为什么不直接依赖 {@code AdapterRegistry}</b>：编排逻辑要在「不装 Spring 容器、不起全部适配器」
 * 的条件下可单测——而分支最密集的正是这部分。因此编排器只依赖这个最小函数式接口，
 * 由自动配置在生产环境把它接到 {@code AdapterRegistry} 上，测试直接给桩。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
@FunctionalInterface
public interface ProtocolDescriptorSource {

    /**
     * 按协议标识查描述符。
     *
     * @param code 协议标识
     * @return 描述符；该协议未注册时返回空 Optional
     */
    Optional<ProtocolDescriptor> find(ProtocolCode code);

    /**
     * 空来源：任何协议都查不到。
     *
     * <p>刻意不返回 {@code null}（AGENTS R12）；编排器查到空时按保守侧
     * {@link cn.ypbin.iot.core.protocol.SubscriptionAddressing#DEFAULT} 处理并打 WARN。</p>
     *
     * @return 空来源
     */
    static ProtocolDescriptorSource empty() {
        return code -> Optional.empty();
    }

    /**
     * 由一组描述符构造固定来源（测试与静态装配用）。
     *
     * @param descriptors 描述符
     * @return 固定来源
     */
    static ProtocolDescriptorSource of(Iterable<ProtocolDescriptor> descriptors) {
        Map<ProtocolCode, ProtocolDescriptor> index = new LinkedHashMap<>();
        for (ProtocolDescriptor descriptor : descriptors) {
            index.put(descriptor.code(), descriptor);
        }
        return code -> Optional.ofNullable(index.get(code));
    }

    /**
     * 便捷方法：某设备所属协议的描述符。
     *
     * @param device 设备规格
     * @return 描述符
     */
    default Optional<ProtocolDescriptor> find(DeviceSpec device) {
        return find(device.protocol());
    }
}
