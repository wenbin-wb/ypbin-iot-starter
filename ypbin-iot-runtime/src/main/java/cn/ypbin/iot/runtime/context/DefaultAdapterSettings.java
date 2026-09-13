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
package cn.ypbin.iot.runtime.context;

import cn.ypbin.iot.core.context.AdapterSettings;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * 适配器配置默认实现。
 *
 * <p>所有字段都有开箱即用的默认值；协议扩展参数走类型化查表，
 * 由 {@link #extended()} 提供全量视图供适配器做未知 key 校验。</p>
 *
 * @param enabled             是否启用
 * @param connectTimeout      建链超时
 * @param requestTimeout      请求超时
 * @param keepAliveInterval   保活间隔
 * @param reconnectInitialDelay 重连初始退避
 * @param reconnectMaxDelay   重连最大退避
 * @param reconnectJitter     重连抖动系数
 * @param maxConnections      最大并发链路数
 * @param maxPendingRequests  单链路最大在途请求数
 * @param extended            协议扩展配置
 * @author wenbin
 * @since 2026-09-13
 */
public record DefaultAdapterSettings(
        boolean enabled,
        Duration connectTimeout,
        Duration requestTimeout,
        Duration keepAliveInterval,
        Duration reconnectInitialDelay,
        Duration reconnectMaxDelay,
        double reconnectJitter,
        int maxConnections,
        int maxPendingRequests,
        Map<String, Object> extended) implements AdapterSettings {

    /** 默认建链超时。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 默认请求超时。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** 默认保活间隔。 */
    public static final Duration DEFAULT_KEEP_ALIVE_INTERVAL = Duration.ofSeconds(30);

    /** 默认重连初始退避。 */
    public static final Duration DEFAULT_RECONNECT_INITIAL_DELAY = Duration.ofSeconds(1);

    /** 默认重连最大退避。 */
    public static final Duration DEFAULT_RECONNECT_MAX_DELAY = Duration.ofSeconds(60);

    /** 默认重连抖动系数。 */
    public static final double DEFAULT_RECONNECT_JITTER = 0.2D;

    /** 默认最大并发链路数。 */
    public static final int DEFAULT_MAX_CONNECTIONS = 10_000;

    /** 默认单链路最大在途请求数。 */
    public static final int DEFAULT_MAX_PENDING_REQUESTS = 64;

    /**
     * 紧凑构造器：归一化可空字段。
     */
    public DefaultAdapterSettings {
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        keepAliveInterval = keepAliveInterval == null ? DEFAULT_KEEP_ALIVE_INTERVAL : keepAliveInterval;
        reconnectInitialDelay =
                reconnectInitialDelay == null ? DEFAULT_RECONNECT_INITIAL_DELAY : reconnectInitialDelay;
        reconnectMaxDelay = reconnectMaxDelay == null ? DEFAULT_RECONNECT_MAX_DELAY : reconnectMaxDelay;
        maxConnections = maxConnections <= 0 ? DEFAULT_MAX_CONNECTIONS : maxConnections;
        maxPendingRequests = maxPendingRequests <= 0 ? DEFAULT_MAX_PENDING_REQUESTS : maxPendingRequests;
        extended = extended == null ? Map.of() : Map.copyOf(extended);
    }

    /**
     * 全部默认值的配置。
     *
     * @return 配置
     */
    public static DefaultAdapterSettings defaults() {
        return new DefaultAdapterSettings(true, DEFAULT_CONNECT_TIMEOUT, DEFAULT_REQUEST_TIMEOUT,
                DEFAULT_KEEP_ALIVE_INTERVAL, DEFAULT_RECONNECT_INITIAL_DELAY, DEFAULT_RECONNECT_MAX_DELAY,
                DEFAULT_RECONNECT_JITTER, DEFAULT_MAX_CONNECTIONS, DEFAULT_MAX_PENDING_REQUESTS, Map.of());
    }

    @Override
    public <T> T get(String key, Class<T> type, T defaultValue) {
        return find(key, type).orElse(defaultValue);
    }

    @Override
    public <T> Optional<T> find(String key, Class<T> type) {
        Object value = extended.get(key);
        if (value == null) {
            return Optional.empty();
        }
        if (type.isInstance(value)) {
            return Optional.of(type.cast(value));
        }
        return convert(value, type);
    }

    @SuppressWarnings("unchecked")
    private static <T> Optional<T> convert(Object value, Class<T> type) {
        String text = String.valueOf(value);
        try {
            if (type == String.class) {
                return Optional.of((T) text);
            }
            if (type == Integer.class) {
                return Optional.of((T) Integer.valueOf(text));
            }
            if (type == Long.class) {
                return Optional.of((T) Long.valueOf(text));
            }
            if (type == Boolean.class) {
                return Optional.of((T) Boolean.valueOf(text));
            }
            if (type == Double.class) {
                return Optional.of((T) Double.valueOf(text));
            }
            if (type == Duration.class) {
                return Optional.of((T) Duration.parse(text));
            }
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("cannot convert config value to " + type.getName() + ": " + text, ex);
        }
        throw new IllegalArgumentException("unsupported config type: " + type.getName());
    }
}
