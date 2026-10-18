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
 * 订阅寻址方式：一次 {@code subscribe} 请求能携带几个点位地址。
 *
 * <p><b>为什么需要它</b>：订阅编排（{@code SubscriptionOrchestrator}）必须决定「整批订阅」还是
 * 「逐点位单订阅」，而这个判据是<b>协议能力</b>，不是业务语义。此前该判据散落在宿主：
 * 宿主按协议码 + {@code payload-format} 字符串硬编码（例如「TCP 且不是 key-value 就逐点位」），
 * 每换一个宿主就要重写一遍，漏写就是运行期故障——典型的形态是「声称订阅了 N 个点位，
 * 实际只交付第 0 个」（本仓 #15 同类缺陷）。</p>
 *
 * <p>现在由协议通过 {@link ProtocolDescriptor#subscriptionAddressing()} <b>显式声明</b>，
 * 框架只读声明、不认识任何协议码字符串。</p>
 *
 * <p><b>为什么默认是 {@link #PER_ADDRESS}</b>：默认可取的两个方向并不对称——</p>
 * <ul>
 *   <li>默认逐点位而协议其实支持整批：只是把 1 次请求拆成 N 次，<b>功能正确</b>，代价是效率；</li>
 *   <li>默认整批而协议其实是单点位流：多地址请求可能被<b>静默截断</b>（只交付第一个地址），
 *       表现为「设备在线但只有点位 0 有数据」，是最难排查的一类数据缺失。</li>
 * </ul>
 * <p>因此未声明的协议按<b>保守侧</b>{@link #DEFAULT} 处理；协议模块应显式声明以拿到效率。</p>
 *
 * <p><b>为什么不复用 {@link ProtocolCapability}</b>：能力集合表达的是「支持/不支持某个操作」，
 * 而本枚举表达的是「多地址请求的粒度契约」，两者正交且都需要存在——例如 MQTT 与 TCP 透传都以
 * {@link ProtocolCapability#SUBSCRIBE_STREAM} 声明流式订阅，但 MQTT 可一次订阅多个主题，
 * 裸 TCP 帧却没有任何寻址语义、一次只能订 1 个地址。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
public enum SubscriptionAddressing {

    /**
     * 整批订阅：一次请求可携带多个地址，且<b>每个地址都独立产出数据</b>。
     *
     * <p>适用于 OPC UA 监控项、MQTT 多主题、Modbus 轮询批量读等。</p>
     */
    BATCH(0, "整批订阅"),

    /**
     * 逐点位单订阅：一次请求只能携带 1 个地址（单点位流）。
     *
     * <p>适用于裸 TCP 帧等没有寻址语义的协议；多地址请求必须由编排器拆成 N 次单地址订阅，
     * 否则协议层只能交付第一个地址。</p>
     */
    PER_ADDRESS(1, "逐点位单订阅");

    /**
     * 未声明时的保守默认值。
     *
     * <p>理由见类注释：默认整批的失败模式是<b>静默数据缺失</b>，默认逐点位的失败模式只是效率。
     * 编排器在协议未声明时按本值处理，并在 debug 日志说明。</p>
     */
    public static final SubscriptionAddressing DEFAULT = PER_ADDRESS;

    private final int code;

    private final String desc;

    SubscriptionAddressing(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /**
     * 寻址方式码，用于传输与存库（严禁使用 {@link #ordinal()}）。
     *
     * @return 寻址方式码
     */
    public int getCode() {
        return code;
    }

    /**
     * 寻址方式描述。
     *
     * @return 描述
     */
    public String getDesc() {
        return desc;
    }

    /**
     * 按寻址方式码查找。
     *
     * @param code 寻址方式码
     * @return 匹配的寻址方式；无匹配时返回空 Optional
     */
    public static Optional<SubscriptionAddressing> fromCode(int code) {
        for (SubscriptionAddressing addressing : values()) {
            if (addressing.code == code) {
                return Optional.of(addressing);
            }
        }
        return Optional.empty();
    }
}
