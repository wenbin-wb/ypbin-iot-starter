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

import cn.ypbin.iot.core.exception.AddressParseException;
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
 * <p><b>构造期即校验 URI 合法性</b>：非法 URI 在构造时抛
 * {@link AddressParseException}，而不是被静默接受后让 {@code scheme()} 返回空串、
 * {@code port()} 返回 -1 一路传播到运行期——那种「配置错了但没人知道」的失败最难排查。</p>
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
            throw new AddressParseException(null, uri, "endpoint uri must not be blank");
        }
        try {
            URI parsed = new URI(uri);
            if (parsed.getScheme() == null) {
                // 仅校验语法不够："garbage" 与 "/dev/ttyS0" 都是合法相对 URI，
                // 但作为端点它们没有承载协议，接受下来会表现为 scheme()="" / port()=-1 的静默形态。
                throw new AddressParseException(null, uri, "endpoint requires a scheme, e.g. tcp://host:port");
            }
        } catch (URISyntaxException ex) {
            throw new AddressParseException(null, uri, ex.getReason());
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
        String value = parsed().getScheme();
        return value == null ? "" : value;
    }

    /**
     * 主机名或 IP。
     *
     * @return 主机名；无主机时返回空字符串
     */
    public String host() {
        String value = parsed().getHost();
        return value == null ? "" : value;
    }

    /**
     * 端口。
     *
     * @return 端口；未指定时返回 {@link #NO_PORT}
     */
    public int port() {
        return parsed().getPort();
    }

    /**
     * 路径部分（串口设备路径、OPC UA 路径等）。
     *
     * @return 路径
     */
    public Optional<String> path() {
        return Optional.ofNullable(parsed().getPath()).filter(value -> !value.isEmpty());
    }

    /**
     * URI 查询参数。
     *
     * @return 不可变参数视图；无参数时返回空 Map
     */
    public Map<String, String> parameters() {
        String query = parsed().getQuery();
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

    /**
     * 解析 URI。
     *
     * <p>构造期已校验，这里不会失败；若真的失败，说明 record 被绕过构造器创建，
     * 此时<b>显式抛异常</b>而不是返回空——静默返回空会让调用方以为「这个端点没有 scheme」。</p>
     */
    private URI parsed() {
        try {
            return new URI(uri);
        } catch (URISyntaxException ex) {
            throw new AddressParseException(null, uri, ex.getReason());
        }
    }

    @Override
    public String toString() {
        return uri;
    }
}
