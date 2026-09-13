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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 端点：统一用 URI 表达，避免字段组合爆炸。
 *
 * <p>约定示例：</p>
 * <ul>
 *   <li>{@code tcp://10.0.0.1:502} —— Modbus TCP</li>
 *   <li>{@code serial:///dev/ttyS0?baud=9600&parity=none} —— 串口</li>
 *   <li>{@code opc.tcp://10.0.0.1:4840} —— OPC UA</li>
 * </ul>
 *
 * <p>协议特有参数一律放 URI 查询串，由各协议模块自行解释与校验。</p>
 *
 * @param uri 端点 URI
 * @author wenbin
 * @since 2026-09-13
 */
public record Endpoint(String uri) {

    /** 未指定端口的占位值。 */
    public static final int NO_PORT = -1;

    /**
     * 紧凑构造器：校验 URI 合法性。
     */
    public Endpoint {
        Objects.requireNonNull(uri, "uri must not be null");
        if (uri.isBlank()) {
            throw new IllegalArgumentException("endpoint uri must not be blank");
        }
    }

    /**
     * 由 URI 字符串构造端点。
     *
     * @param uri 端点 URI
     * @return 端点
     */
    public static Endpoint of(String uri) {
        return new Endpoint(uri);
    }

    /**
     * 端点协议（如 {@code tcp}、{@code udp}、{@code serial}、{@code opc.tcp}）。
     *
     * @return 协议名；无法解析时返回空字符串
     */
    public String scheme() {
        String value = parsed().map(URI::getScheme).orElse(null);
        return value == null ? "" : value;
    }

    /**
     * 主机名或 IP。
     *
     * @return 主机名；无主机时返回空字符串
     */
    public String host() {
        String value = parsed().map(URI::getHost).orElse(null);
        return value == null ? "" : value;
    }

    /**
     * 端口。
     *
     * @return 端口；未指定时返回 {@link #NO_PORT}
     */
    public int port() {
        return parsed().map(URI::getPort).orElse(NO_PORT);
    }

    /**
     * 路径部分（串口设备路径、OPC UA 路径等）。
     *
     * @return 路径
     */
    public Optional<String> path() {
        return parsed().map(URI::getPath).filter(value -> !value.isEmpty());
    }

    /**
     * URI 查询参数。
     *
     * @return 不可变参数视图；无参数时返回空 Map
     */
    public Map<String, String> parameters() {
        String query = parsed().map(URI::getQuery).orElse(null);
        if (query == null || query.isEmpty()) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int index = pair.indexOf('=');
            if (index < 0) {
                result.put(pair, "");
            } else {
                result.put(pair.substring(0, index), pair.substring(index + 1));
            }
        }
        return Map.copyOf(result);
    }

    private Optional<URI> parsed() {
        try {
            return Optional.of(new URI(uri));
        } catch (URISyntaxException ex) {
            return Optional.empty();
        }
    }

    @Override
    public String toString() {
        return uri;
    }
}
