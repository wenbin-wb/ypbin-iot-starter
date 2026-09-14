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
import org.jspecify.annotations.Nullable;
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
 * @param credentialRef      凭据引用：**OPC UA 用户名对应的口令**（从 {@code CredentialResolver} 取，不落配置文件）
 * @param clientKeyStore     客户端 PKCS#12 证书路径；非 None 策略时必填
 * @param clientKeyStorePasswordRef keystore 口令的凭据引用（**与用户口令分开**，见 {@code credentialRef}）
 * @param trustListDir       信任证书目录（存放服务端证书文件）；非 None 策略且未开 trust-all 时必填
 * @param trustAll           是否信任全部服务端证书（**仅开发环境**，开启会打 WARN）
 * @author wenbin
 * @since 2026-09-14
 */
@ConfigurationProperties(prefix = OpcUaProperties.PREFIX)
public record OpcUaProperties(
        @Nullable Boolean enabled,
        @Nullable String securityPolicy,
        @Nullable String securityMode,
        @Nullable Duration requestTimeout,
        @Nullable Duration publishingInterval,
        @Nullable Integer maxNodesPerRead,
        @Nullable Integer browseMaxDepth,
        @Nullable Integer browseMaxNodes,
        @Nullable String username,
        @Nullable String credentialRef,
        @Nullable String clientKeyStore,
        @Nullable String clientKeyStorePasswordRef,
        @Nullable String trustListDir,
        @Nullable Boolean trustAll) {

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
     * 归一化后的安全策略。
     *
     * <p>组件声明为 {@code @Nullable} 是因为<b>构造参数</b>允许为空（Spring 绑定与
     * {@code new OpcUaProperties(null, ...)} 都传空表示「未配置」）；紧凑构造器已把它们
     * 归一化为非空，因此访问器给出<b>非空契约</b>——否则可空性会扩散到所有使用点。</p>
     *
     * @return 安全策略（恒非空）
     */
    @Override
    public String securityPolicy() {
        return securityPolicy == null ? POLICY_NONE : securityPolicy;
    }

    /**
     * 归一化后的安全模式。
     *
     * @return 安全模式（恒非空）
     */
    @Override
    public String securityMode() {
        return securityMode == null ? MODE_NONE : securityMode;
    }

    /**
     * 归一化后的请求超时。
     *
     * @return 请求超时（恒非空）
     */
    @Override
    public Duration requestTimeout() {
        return requestTimeout == null ? Duration.ofSeconds(10) : requestTimeout;
    }

    /**
     * 归一化后的发布间隔。
     *
     * @return 发布间隔（恒非空）
     */
    @Override
    public Duration publishingInterval() {
        return publishingInterval == null ? Duration.ofMillis(500) : publishingInterval;
    }

    /**
     * 归一化后的单次读上限。
     *
     * @return 单次读最大节点数（恒非空）
     */
    @Override
    public Integer maxNodesPerRead() {
        return maxNodesPerRead == null || maxNodesPerRead <= 0
                ? DEFAULT_MAX_NODES_PER_READ : maxNodesPerRead;
    }

    /**
     * 归一化后的浏览深度上限。
     *
     * @return 浏览最大深度（恒非空）
     */
    @Override
    public Integer browseMaxDepth() {
        return browseMaxDepth == null || browseMaxDepth <= 0 ? 1 : browseMaxDepth;
    }

    /**
     * 归一化后的浏览节点数上限。
     *
     * @return 浏览最大节点数（恒非空）
     */
    @Override
    public Integer browseMaxNodes() {
        return browseMaxNodes == null || browseMaxNodes <= 0 ? 1000 : browseMaxNodes;
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
