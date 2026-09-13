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
package cn.ypbin.iot.core.spi;

import cn.ypbin.iot.core.model.DeviceSpec;
import java.util.Objects;

/**
 * 设备配置变更事件。
 *
 * <p>可能重复投递（消息总线 at-least-once），因此携带单调递增的 {@code revision}：
 * 框架记录每个设备最后已应用的 revision，收到不大于它的变更直接丢弃（幂等），
 * 同时解决乱序到达问题。</p>
 *
 * @param type     变更类型
 * @param device   变更后的设备规格；{@code REMOVE} 时携带被删除设备的规格（用于清理影子）
 * @param revision 单调递增的配置版本号
 * @author wenbin
 * @since 2026-09-13
 */
public record DeviceChange(ChangeType type, DeviceSpec device, long revision) {

    /**
     * 紧凑构造器：校验必填项。
     */
    public DeviceChange {
        Objects.requireNonNull(type, "type must not be null");
        Objects.requireNonNull(device, "device must not be null");
    }
}
