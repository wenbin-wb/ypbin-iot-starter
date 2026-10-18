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

/**
 * 订阅编排：把「点位清单 + 已建链会话」变成协议层订阅调用，并承担重订、部分成功、退避与去重。
 *
 * <p><b>为什么在框架里</b>：这些逻辑不含业务语义（不需要知道点位代表什么值），
 * 却需要每个 IoT 宿主重新发明一遍，漏一条就是运行期故障（详见 issue #37 的一手证据）。
 * 宿主保留的只有「哪些设备归我」与「点位 → 地址范围」两件事。</p>
 *
 * <p>本包属于装配层，可依赖 Spring；但 {@code SubscriptionOrchestrator} 的默认实现刻意
 * 只用 JDK + core 类型，从而能脱离容器做协议矩阵的单测。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
package cn.ypbin.iot.spring.subscription;
