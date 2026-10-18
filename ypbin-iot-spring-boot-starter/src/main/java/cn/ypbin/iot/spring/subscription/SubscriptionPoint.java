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

import cn.ypbin.iot.core.model.PointAddress;
import java.util.Objects;

/**
 * 一个待订阅点位的地址范围声明。
 *
 * <p><b>为什么要把「点位」和「地址」分开</b>：宿主只知道「这个点位从
 * {@code holding:0} 开始占 2 个寄存器」，而「{@code holding:1} 也要一起订」是协议地址算术。
 * 前者是宿主的映射数据，后者是框架该做的事。因此宿主只声明基址 + 寄存器个数，
 * 兄弟地址由 {@link SubscriptionOrchestrator} 通过协议声明的
 * {@link cn.ypbin.iot.core.protocol.AddressExpansionExtension} 展开——
 * 展开规则来自协议自己，框架不按字符串猜，避免「偏移错一位、读到的值不对」这种最难排查的故障。</p>
 *
 * @param address   基址
 * @param wordCount 点位占用的寄存器（寻址单元）个数；{@code 1} 表示单寄存器点位
 * @author wenbin
 * @since 2026-10-18
 */
public record SubscriptionPoint(PointAddress address, int wordCount) {

    /** 单寄存器点位（默认）。 */
    public static final int DEFAULT_WORD_COUNT = 1;

    /**
     * 紧凑构造器：校验必填项与寄存器个数。
     */
    public SubscriptionPoint {
        Objects.requireNonNull(address, "address must not be null");
        if (wordCount < DEFAULT_WORD_COUNT) {
            throw new IllegalArgumentException("wordCount must be >= " + DEFAULT_WORD_COUNT + ": " + wordCount);
        }
    }

    /**
     * 由原始地址构造单寄存器点位。
     *
     * @param raw 原始地址字符串（协议自行解释）
     * @return 点位
     */
    public static SubscriptionPoint of(String raw) {
        return new SubscriptionPoint(PointAddress.of(raw), DEFAULT_WORD_COUNT);
    }

    /**
     * 由原始地址与寄存器个数构造点位。
     *
     * @param raw       原始地址字符串（协议自行解释）
     * @param wordCount 寄存器个数，≥ 1
     * @return 点位
     */
    public static SubscriptionPoint of(String raw, int wordCount) {
        return new SubscriptionPoint(PointAddress.of(raw), wordCount);
    }

    /**
     * 是否需要展开兄弟寄存器地址。
     *
     * @return 占用多于 1 个寄存器时返回 {@code true}
     */
    public boolean requiresExpansion() {
        return wordCount > DEFAULT_WORD_COUNT;
    }
}
