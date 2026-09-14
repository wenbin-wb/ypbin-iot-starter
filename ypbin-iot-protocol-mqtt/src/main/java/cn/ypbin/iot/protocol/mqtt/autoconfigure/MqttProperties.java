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
package cn.ypbin.iot.protocol.mqtt.autoconfigure;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MQTT 协议配置。
 *
 * <p>连接超时、请求超时、保活间隔等通用参数走框架的 {@code ProtocolProperties}；
 * 本类只保留 MQTT 特有项。</p>
 *
 * @param enabled                协议开关
 * @param clientIdPrefix         客户端标识前缀（框架会追加实例唯一后缀，避免多实例互踢）
 * @param cleanSession           是否以干净会话启动（false 时 broker 保留离线消息与订阅）
 * @param qosDefault             默认服务质量等级（0/1/2）
 * @param retainedDefault        发布时是否默认保留
 * @author wenbin
 * @since 2026-09-13
 */
@ConfigurationProperties(prefix = MqttProperties.PREFIX)
public record MqttProperties(
        @Nullable Boolean enabled,
        @Nullable String clientIdPrefix,
        @Nullable Boolean cleanSession,
        @Nullable Integer qosDefault,
        @Nullable Boolean retainedDefault) {

    /**
     * 默认 QoS 的读取入口。
     *
     * <p>组件声明为 {@code @Nullable} 是因为<b>构造参数</b>允许为空（Spring 绑定与
     * {@code defaults()} 都传空表示「未配置」）；紧凑构造器已归一化为非空，
     * 因此访问器给出<b>非空契约</b>——否则可空性会扩散到所有使用点，每处都要重复无意义的判空。</p>
     *
     * @return 归一化后的 QoS（恒非空）
     */
    @Override
    public Integer qosDefault() {
        return qosDefault == null ? DEFAULT_QOS : qosDefault;
    }

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.iot.protocol.mqtt";

    /** 默认 QoS（紧凑构造器与访问器共用，避免两处各写一个裸字面量）。 */
    public static final int DEFAULT_QOS = 1;

    /** 默认客户端标识前缀。 */
    public static final String DEFAULT_CLIENT_ID_PREFIX = "ypbin-iot";

    /** QoS 上限（协议定义）。 */
    public static final int MAX_QOS = 2;

    /**
     * 紧凑构造器：归一化默认值。
     */
    public MqttProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        clientIdPrefix = clientIdPrefix == null || clientIdPrefix.isBlank()
                ? DEFAULT_CLIENT_ID_PREFIX : clientIdPrefix;
        cleanSession = cleanSession == null ? Boolean.TRUE : cleanSession;
        qosDefault = qosDefault == null || qosDefault < 0 || qosDefault > MAX_QOS
                ? DEFAULT_QOS : qosDefault;
        retainedDefault = retainedDefault == null ? Boolean.FALSE : retainedDefault;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 是否干净会话启动。
     *
     * @return 干净启动返回 {@code true}
     */
    public boolean isCleanSession() {
        return Boolean.TRUE.equals(cleanSession);
    }

    /**
     * 是否默认保留消息。
     *
     * @return 保留返回 {@code true}
     */
    public boolean isRetainedDefault() {
        return Boolean.TRUE.equals(retainedDefault);
    }
}
