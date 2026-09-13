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

import java.util.function.Function;

/**
 * 有界地址解析缓存（LRU + 并发安全）。
 *
 * <p>存在的理由：地址解析有成本，地址集合有限且稳定，但设备规模上来后总量巨大
 * （10 万设备 × 千点位），无界 Map 必然 OOM。本类型把「解析成本」与「内存上限」
 * 这对矛盾收敛到一个可配置的组件里，避免每个协议作者各写一版。</p>
 *
 * @param <A> 解析后的地址类型
 * @author wenbin
 * @since 2026-09-13
 */
public interface BoundedAddressCache<A> {

    /**
     * 取解析结果，未命中时用 {@code loader} 解析并写入缓存。
     *
     * @param raw    原始地址字符串
     * @param loader 解析函数，抛出异常时不写入缓存（避免缓存污染）
     * @return 解析后的地址
     */
    A get(String raw, Function<String, A> loader);

    /** 清空缓存（点表变更时由框架调用）。 */
    void invalidateAll();

    /**
     * 当前条目数。
     *
     * @return 条目数
     */
    int size();
}
