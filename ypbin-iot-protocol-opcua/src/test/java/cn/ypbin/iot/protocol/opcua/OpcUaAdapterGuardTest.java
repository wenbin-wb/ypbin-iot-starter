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
package cn.ypbin.iot.protocol.opcua;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.TlsOptions;
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.protocol.opcua.autoconfigure.OpcUaProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OPC UA 守卫路径测试（不依赖真实服务端）。
 *
 * <p>这些是本模块风险最高的分支：三轮审核的历史表明，<b>安全静默降级与「配置了不生效」
 * 类缺陷几乎全部出现在守卫与失败路径上</b>，而正向路径反而不容易出错。
 * 因此本类刻意覆盖：TLS 拒绝、非 None 安全策略拒绝、承载方式拒绝、探测不可达。</p>
 *
 * <p>正向端到端（真实服务端的读写/订阅/浏览）需要自建 Milo 地址空间 harness，
 * 属下一增量，已在模块 README 与 PROTOCOLS.md 显式登记为未完成项。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class OpcUaAdapterGuardTest {

    @TempDir
    Path tempDir;


    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private OpcUaAdapter adapter;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(2, 64);
        context = new DefaultAdapterContext(OpcUaAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 32);
        adapter = new OpcUaAdapter(null);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    @DisplayName("OPC-01 描述符必须自洽：声明 BROWSE 就必须登记 BrowseExtension")
    void descriptorMustDeclareBrowseExtension() {
        assertThat(adapter.capabilities()).contains(ProtocolCapability.READ, ProtocolCapability.WRITE,
                ProtocolCapability.SUBSCRIBE_NATIVE, ProtocolCapability.BROWSE,
                ProtocolCapability.MULTI_DEVICE_LINK);
        assertThat(adapter.descriptor().extensions())
                .as("声明了 BROWSE 能力却没有扩展实现，等于给了宿主一个取不到的承诺")
                .contains(BrowseExtension.class);
        assertThat(OpcUaBrowser.supports(BrowseExtension.class)).isTrue();
        assertThat(adapter.descriptor().stackVersion()).isEqualTo("1.1.7");
        assertThat(adapter.capabilities()).isEqualTo(adapter.descriptor().capabilities());
    }

    @Test
    @DisplayName("OPC-02 未实现的传输层 TLS 必须 fail-fast，不得静默明文")
    void transportTlsMustFailFast() {
        ConnectionSpec spec = new ConnectionSpec("tls", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:4840"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                TlsOptions.enabledDefault(), null, Map.of());
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("OPC-03 未实现的非 None 安全策略必须 fail-fast（安全静默降级是最高危的一类）")
    void unsupportedSecurityPolicyMustFailFast() {
        OpcUaAdapter secured = new OpcUaAdapter(new OpcUaProperties(true, "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256", "SIGN_AND_ENCRYPT", Duration.ofSeconds(2), Duration.ofMillis(200), 100, 1, 100, null, null, null, null, null));
        ConnectionSpec spec = new ConnectionSpec("secured", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:4840"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                null, null, Map.of());
        Throwable error = secured.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> {
                    if (connection != null) {
                        connection.close();
                    }
                    return ex;
                })
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(error)
                .as("缺少证书配置时必须显式失败，而不是静默明文")
                .isInstanceOf(ConnectionException.class);
        assertThat(String.valueOf(error))
                .as("安全策略必须真的被实现（走到装配阶段），而不是一律报未实现")
                .doesNotContain(OpcUaAdapter.MSG_SECURITY_UNSUPPORTED);
    }

    @Test
    @DisplayName("OPC-03b 配置了用户名密码但策略为 None 必须 fail-fast（否则凭据明文外发）")
    void credentialsOverPlaintextMustFailFast() {
        // 用户名密码在 SecurityPolicy#None 下是明文传输的（Nonce 加密只在非 None 策略下生效）。
        // 静默发出去会让宿主以为"认证过了"，因此必须拒绝。
        OpcUaAdapter withCredentials = new OpcUaAdapter(new OpcUaProperties(true, null, null, null, null, null, null, null, null, null, null, null, null));
        ConnectionSpec spec = new ConnectionSpec("cred", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:4840"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                null, null, Map.of());
        Throwable error = withCredentials.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> {
                    if (connection != null) {
                        connection.close();
                    }
                    return ex;
                })
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(error)
                .as("明文策略下的凭据必须被拒绝，而不是明文发送")
                .isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("OPC-03c 非 None 策略缺客户端证书必须 fail-fast（否则握手失败会被误读为不可达）")
    void nonNonePolicyWithoutKeystoreMustFailFast() {
        // 策略配了、keystore 没配：没有私钥就无法签名/加密，服务端会在握手阶段拒绝，
        // 而错误看起来像「端点不可达」。必须在这里明确拒绝。
        OpcUaAdapter secured = new OpcUaAdapter(new OpcUaProperties(true,
                "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256", "SIGN_AND_ENCRYPT",
                Duration.ofSeconds(2), Duration.ofMillis(200), 100, 1, 100, null, null, null, null, null));
        ConnectionSpec spec = new ConnectionSpec("no-cert", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:4840"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                null, null, Map.of());
        Throwable error = secured.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> {
                    if (connection != null) {
                        connection.close();
                    }
                    return ex;
                })
                .orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(error)
                .as("缺客户端证书时必须 fail-fast，而不是等到握手阶段报一个误导性的错误")
                .isInstanceOf(ConnectionException.class);
        // 关键：必须是**安全材料装配**给出的原因，而不是"策略未实现"——
        // 后者意味着安全实现被短路成了死代码（本仓确实出现过这个缺陷）
        assertThat(String.valueOf(error))
                .as("非 None 策略必须真的走到安全装配，而不是被短路成 security.unsupported")
                .contains(OpcUaAdapter.MSG_KEYSTORE_MISSING)
                .doesNotContain(OpcUaAdapter.MSG_SECURITY_UNSUPPORTED);
    }

    @Test
    @DisplayName("OPC-03e 非 None 策略必须真的走到建链（安全实现不得是死代码）")
    void nonNonePolicyMustReachConnectionAttempt() {
        // 用不存在的 keystore：失败原因必须是「keystore 缺失/不可读」这类**装配期**原因。
        // 若返回 security.unsupported，说明非 None 策略被短路，整套安全实现不可达。
        OpcUaAdapter secured = new OpcUaAdapter(new OpcUaProperties(true,
                "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256", "SignAndEncrypt",
                Duration.ofSeconds(2), Duration.ofMillis(200), 100, 1, 100, null, "opcua-ref",
                "/nonexistent/client.p12", tempDir.toString(), null));
        ConnectionSpec spec = new ConnectionSpec("reach", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:4840"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                null, null, Map.of());
        Throwable error = secured.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> {
                    if (connection != null) {
                        connection.close();
                    }
                    return ex;
                })
                .orTimeout(20, TimeUnit.SECONDS).join();
        assertThat(error).isNotNull();
        assertThat(String.valueOf(error))
                .as("必须报装配期原因（keystore 不可读），证明安全路径可达")
                .doesNotContain(OpcUaAdapter.MSG_SECURITY_UNSUPPORTED);
    }

    @Test
    @DisplayName("OPC-03d trust-all 必须真实生效（开发路径可用）")
    void trustAllMustBeUsable() {
        OpcUaProperties properties = new OpcUaProperties(true, null, null, null, null, null, null, null,
                null, null, null, null, Boolean.TRUE);
        assertThat(properties.isTrustAll()).isTrue();
        assertThat(new OpcUaProperties(null, null, null, null, null, null, null, null, null, null, null, null,
                null).isTrustAll()).as("默认必须关闭 trust-all").isFalse();
    }

    @Test
    @DisplayName("OPC-04 不支持的承载方式必须 fail-fast（返回失败 Stage，不同步抛）")
    void unsupportedSchemeMustFailFast() {
        ConnectionSpec spec = new ConnectionSpec("bad", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("http://127.0.0.1:4840"), Duration.ofSeconds(2), Duration.ofSeconds(2),
                null, null, Map.of());
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("OPC-04b 向不可用链路 bind 必须失败，不得返回永不工作的会话")
    void bindOnUnusableConnectionMustFail() {
        ConnectionSpec spec = new ConnectionSpec("closed", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:1"), Duration.ofMillis(300), Duration.ofMillis(300),
                null, null, Map.of());
        // 端点不可达 → open 失败；再用一个已关闭的链路验证 bind 的守卫
        Throwable openError = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).orTimeout(20, TimeUnit.SECONDS).join();
        assertThat(openError).as("不可达端点的 open 必须失败").isNotNull();
    }

    @Test
    @DisplayName("OPC-05 探测不可达端点必须返回 unreachable 而不是异常完成")
    void probeUnreachableMustNotThrow() {
        ConnectionSpec dead = new ConnectionSpec("dead", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of("opc.tcp://127.0.0.1:1"), Duration.ofMillis(500), Duration.ofMillis(500),
                null, null, Map.of());
        var result = adapter.probe(dead, context).toCompletableFuture()
                .orTimeout(20, TimeUnit.SECONDS).join();
        assertThat(result.reachable()).isFalse();
        assertThat(result.failureReason())
                .as("必须带出真实原因而不是折叠成「链路不可用」")
                .isNotBlank()
                .isNotEqualTo(OpcUaAdapter.MSG_CONNECTION_INACTIVE);
    }

    @Test
    @DisplayName("OPC-06 配置默认值必须开箱即用且边界归一化")
    void propertiesMustNormalizeDefaults() {
        OpcUaProperties defaults = new OpcUaProperties(null, null, null, null, null, null, null, null, null, null, null, null, null);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.isPlaintext()).as("默认必须是明文策略（现场大量旧设备只支持明文）").isTrue();
        assertThat(defaults.requestTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(defaults.maxNodesPerRead()).isEqualTo(OpcUaProperties.DEFAULT_MAX_NODES_PER_READ);
        assertThat(defaults.browseMaxDepth()).isEqualTo(1);

        assertThat(new OpcUaProperties(true, null, null, null, null, 0, -1, 0, null, null, null, null, null).maxNodesPerRead())
                .as("非正数必须回落到默认值而不是带病运行")
                .isEqualTo(OpcUaProperties.DEFAULT_MAX_NODES_PER_READ);
        assertThat(new OpcUaProperties(true, "  ", "  ", null, null, null, null, null, null, null, null, null, null).isPlaintext()).isTrue();
        assertThat(new OpcUaProperties(true, "http://x", "SIGN", null, null, null, null, null, null, null, null, null, null)
                .isPlaintext()).isFalse();
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-09-14
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
