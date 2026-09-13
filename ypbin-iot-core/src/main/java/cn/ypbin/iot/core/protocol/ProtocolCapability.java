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

import java.util.Optional;

/**
 * 协议能力声明。
 *
 * <p>适配器在 {@link ProtocolDescriptor#capabilities()} 中声明自己支持哪些操作；
 * 框架据此决定是否下发对应任务，调用未声明的能力必须抛
 * {@link cn.ypbin.iot.core.exception.UnsupportedCapabilityException}。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum ProtocolCapability {

    /** 支持批量读。 */
    READ(1, "读"),

    /** 支持写。 */
    WRITE(2, "写"),

    /** 支持服务端主动推送订阅（OPC UA Subscription、MQTT、BACnet COV）。 */
    SUBSCRIBE_NATIVE(3, "原生订阅"),

    /** 支持框架轮询式订阅（Modbus、S7、SNMP）。 */
    SUBSCRIBE_POLLING(4, "轮询订阅"),

    /** 支持流式订阅（媒体流、长连接）。 */
    SUBSCRIBE_STREAM(5, "流式订阅"),

    /** 支持目录或节点浏览。 */
    BROWSE(6, "目录浏览"),

    /** 支持历史数据读取。 */
    HISTORY(7, "历史读取"),

    /** 支持设备发现（广播、mDNS、网段扫描）。 */
    DISCOVERY(8, "设备发现"),

    /** 单链路可承载多个逻辑设备。 */
    MULTI_DEVICE_LINK(9, "链路复用");

    private final int code;

    private final String desc;

    ProtocolCapability(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 能力码，用于跨进程传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 能力码
     */
    public int getCode() {
        return code;
    }

    /**
     * 能力描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按能力码查找。
     *
     * @param code 能力码
     * @return 匹配的能力；无匹配时返回空 Optional
     */
    public static Optional<ProtocolCapability> fromCode(int code) {
        for (ProtocolCapability capability : values()) {
            if (capability.code == code) {
                return Optional.of(capability);
            }
        }
        return Optional.empty();
    }
}
