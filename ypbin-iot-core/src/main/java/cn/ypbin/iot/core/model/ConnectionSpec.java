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
package cn.ypbin.iot.core.model;

import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * 物理连接规格：描述一条链路的建立参数。
 *
 * <p>凭据本体<b>不</b>随本规格流转（避免进入日志与序列化），只传引用，用时才解析。</p>
 *
 * @param connectionId   链路标识（框架分配，稳定且全局唯一）
 * @param protocol       协议标识
 * @param endpoint       端点
 * @param connectTimeout 建链超时
 * @param requestTimeout 单次请求超时
 * @param tls            TLS 参数
 * @param credentialRef  凭据引用；匿名连接时为 {@code null}
 * @param properties     协议扩展参数
 * @author wenbin
 * @since 2026-09-13
 */
public record ConnectionSpec(
        String connectionId,
        ProtocolCode protocol,
        Endpoint endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        TlsOptions tls,
        @Nullable String credentialRef,
        Map<String, String> properties) {

    /** 默认建链超时。 */
    public static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(10);

    /** 默认请求超时。 */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /**
     * 紧凑构造器：归一化可空字段并校验必填项。
     */
    public ConnectionSpec {
        Objects.requireNonNull(connectionId, "connectionId must not be null");
        Objects.requireNonNull(protocol, "protocol must not be null");
        Objects.requireNonNull(endpoint, "endpoint must not be null");
        connectTimeout = connectTimeout == null ? DEFAULT_CONNECT_TIMEOUT : connectTimeout;
        requestTimeout = requestTimeout == null ? DEFAULT_REQUEST_TIMEOUT : requestTimeout;
        tls = tls == null ? TlsOptions.disabled() : tls;
        properties = properties == null ? Map.of() : Map.copyOf(properties);
    }

    /**
     * 便捷构造：使用默认超时与明文传输。
     *
     * @param connectionId 链路标识
     * @param protocol     协议标识
     * @param endpoint     端点
     * @return 连接规格
     */
    public static ConnectionSpec of(String connectionId, ProtocolCode protocol, Endpoint endpoint) {
        return new ConnectionSpec(connectionId, protocol, endpoint, DEFAULT_CONNECT_TIMEOUT,
                DEFAULT_REQUEST_TIMEOUT, TlsOptions.disabled(), null, Map.of());
    }
}
