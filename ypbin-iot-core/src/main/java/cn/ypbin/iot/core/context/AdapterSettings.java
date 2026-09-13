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
import java.util.Map;
import java.util.Optional;

/**
 * 适配器配置门面。
 *
 * <p>设计取舍：<b>框架关心的通用参数显式建模，协议特有参数类型化查表</b>。
 * 前者是框架自己也要用的（重连退避、并发闸门），必须有强类型默认值与校验；
 * 后者数量随协议增长且极不稳定，显式建模会让 core 被协议细节污染。</p>
 *
 * <p>适配器<b>必须</b>在初始化时校验扩展参数：未知 key 要 warn 并指出可用 key，
 * 非法值要 fail-fast——静默忽略拼错的配置项是最难排查的一类线上故障。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface AdapterSettings {

    /**
     * 适配器是否启用。
     *
     * @return 启用返回 {@code true}
     */
    boolean enabled();

    /**
     * 建链超时。
     *
     * @return 建链超时
     */
    Duration connectTimeout();

    /**
     * 单次请求超时。
     *
     * @return 请求超时
     */
    Duration requestTimeout();

    /**
     * 空闲链路保活间隔；{@link Duration#ZERO} 表示不保活。
     *
     * @return 保活间隔
     */
    Duration keepAliveInterval();

    /**
     * 重连初始退避。
     *
     * @return 初始退避
     */
    Duration reconnectInitialDelay();

    /**
     * 重连最大退避。
     *
     * @return 最大退避
     */
    Duration reconnectMaxDelay();

    /**
     * 重连抖动系数（如 0.2 表示 ±20%）。
     *
     * @return 抖动系数
     */
    double reconnectJitter();

    /**
     * 单适配器最大并发链路数。
     *
     * @return 链路上限
     */
    int maxConnections();

    /**
     * 单链路最大在途请求数，用于给串行协议做流水线深度限制。
     *
     * @return 在途请求上限
     */
    int maxPendingRequests();

    /**
     * 按 key 取协议扩展配置。
     *
     * @param key          配置键（去掉 {@code ypbin.iot.protocol.<code>.} 前缀后的剩余部分）
     * @param type         目标类型
     * @param defaultValue 缺省值
     * @param <T>          目标类型
     * @return 转换后的值；缺失时返回 {@code defaultValue}
     */
    <T> T get(String key, Class<T> type, T defaultValue);

    /**
     * 按 key 取协议扩展配置。
     *
     * @param key  配置键
     * @param type 目标类型
     * @param <T>  目标类型
     * @return 配置值；缺失时返回空 Optional
     */
    <T> Optional<T> find(String key, Class<T> type);

    /**
     * 全部协议扩展配置（只读视图），供适配器启动时做未知 key 校验。
     *
     * @return 不可变配置视图
     */
    Map<String, Object> extended();
}
