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
package cn.ypbin.iot.protocol.opcua.autoconfigure;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OPC UA 协议配置。
 *
 * @param enabled            协议开关
 * @param securityPolicy     安全策略 URI；默认 {@code None}（明文）。生产环境必须改为签名/加密策略
 * @param securityMode       安全模式（NONE / SIGN / SIGN_AND_ENCRYPT）
 * @param requestTimeout     单次读写请求超时
 * @param publishingInterval 订阅发布间隔
 * @param maxNodesPerRead    单次 Read 服务最多携带的 NodeId 数（服务端 OperationLimits 可约束）
 * @param browseMaxDepth     浏览最大深度
 * @param browseMaxNodes     浏览最大节点数（防止在大型地址空间上失控）
 * @param username           用户名；配置后表示使用用户名密码认证
 * @param credentialRef      凭据引用（从 {@code CredentialResolver} 取密码/keystore 口令，不落配置文件）
 * @param clientKeyStore     客户端 PKCS#12 证书路径；非 None 策略时必填
 * @param trustListDir       信任证书目录（存放服务端证书文件）；非 None 策略且未开 trust-all 时必填
 * @param trustAll           是否信任全部服务端证书（**仅开发环境**，开启会打 WARN）
 * @author wenbin
 * @since 2026-09-14
 */
@ConfigurationProperties(prefix = OpcUaProperties.PREFIX)
public record OpcUaProperties(
        Boolean enabled,
        String securityPolicy,
        String securityMode,
        Duration requestTimeout,
        Duration publishingInterval,
        Integer maxNodesPerRead,
        Integer browseMaxDepth,
        Integer browseMaxNodes,
        String username,
        String credentialRef,
        String clientKeyStore,
        String trustListDir,
        Boolean trustAll) {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.iot.protocol.opcua";

    /** 明文安全策略 URI（默认；生产必须替换）。 */
    public static final String POLICY_NONE = "http://opcfoundation.org/UA/SecurityPolicy#None";

    /** 默认安全模式。 */
    public static final String MODE_NONE = "NONE";

    /** 单次 Read 的默认上限。 */
    public static final int DEFAULT_MAX_NODES_PER_READ = 500;

    /**
     * 紧凑构造器：归一化默认值。
     */
    public OpcUaProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        securityPolicy = securityPolicy == null || securityPolicy.isBlank() ? POLICY_NONE : securityPolicy;
        securityMode = securityMode == null || securityMode.isBlank() ? MODE_NONE : securityMode;
        requestTimeout = requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
        publishingInterval = publishingInterval == null ? Duration.ofMillis(500) : publishingInterval;
        maxNodesPerRead = maxNodesPerRead == null || maxNodesPerRead <= 0
                ? DEFAULT_MAX_NODES_PER_READ : maxNodesPerRead;
        browseMaxDepth = browseMaxDepth == null || browseMaxDepth <= 0 ? 1 : browseMaxDepth;
        browseMaxNodes = browseMaxNodes == null || browseMaxNodes <= 0 ? 1000 : browseMaxNodes;
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
     * 是否使用明文安全策略。
     *
     * @return 明文返回 {@code true}
     */
    public boolean isPlaintext() {
        return POLICY_NONE.equals(securityPolicy) && MODE_NONE.equalsIgnoreCase(securityMode);
    }

    /**
     * 是否信任全部服务端证书。
     *
     * @return 开启返回 {@code true}
     */
    public boolean isTrustAll() {
        return Boolean.TRUE.equals(trustAll);
    }

    /**
     * 是否配置了用户名密码认证。
     *
     * @return 配置了用户名返回 {@code true}
     */
    public boolean hasCredentials() {
        return username != null && !username.isBlank();
    }
}
