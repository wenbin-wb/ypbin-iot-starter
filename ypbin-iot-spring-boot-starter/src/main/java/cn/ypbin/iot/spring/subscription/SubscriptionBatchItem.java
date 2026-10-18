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

import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.protocol.DeviceSession;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 一个设备的订阅输入：设备规格 + 已建链会话 + 待订阅点位 + 数据回调。
 *
 * <p>四者必须一起给出，因为它们是一件事：<b>同一个设备</b>的点位只能订到<b>它的会话</b>上，
 * 而投递回来的数据要交给<b>该设备专属</b>的 listener（宿主靠它把地址映射回点位语义）。</p>
 *
 * <p>{@code session} 由宿主从框架的已建链视图里取（{@code IotLifecycle#sessions()}）。
 * 编排器<b>不</b>负责建链，也不判断「这台设备该不该由我管」——后者带业务语义（租约/归属），
 * 属宿主职责（AGENTS §1.1）。</p>
 *
 * @param device   设备规格
 * @param session  已建链的设备会话
 * @param points   待订阅点位（宿主只给「点位 → 地址 + 寄存器个数」的映射数据）
 * @param listener 点位回调；{@code null} 表示走框架统一出口（{@code AdapterContext#egress()}）
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionBatchItem(DeviceSpec device, DeviceSession session,
        List<SubscriptionPoint> points, @Nullable DataListener listener) {

    /**
     * 紧凑构造器：校验必填项并归一化集合。
     */
    public SubscriptionBatchItem {
        Objects.requireNonNull(device, "device must not be null");
        Objects.requireNonNull(session, "session must not be null");
        points = points == null ? List.of() : List.copyOf(points);
    }
}
