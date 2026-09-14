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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.CredentialResolver;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.protocol.opcua.autoconfigure.OpcUaProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OPC UA 安全材料装配测试。
 *
 * <p>用 <b>keytool 生成真实的 PKCS#12 与信任证书</b>来覆盖加载路径 —— 只测守卫分支是不够的：
 * 「keystore 能读、证书能取、信任列表能建」这些才是宿主真正会走到的代码。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class OpcUaSecurityTest {

    private static final String STORE_PASSWORD = "changeit";

    private static final String POLICY = "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256";

    @TempDir
    Path tempDir;

    private DefaultTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(2, 16);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    @DisplayName("SEC-01 明文策略必须返回空材料（不加载证书、不建校验器）")
    void plaintextMustReturnEmptyMaterial() {
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(plaintext(), context(ref -> Optional.empty()),
                "c1");
        assertThat(material.keyPair()).isNull();
        assertThat(material.certificate()).isNull();
        assertThat(material.certificateValidator()).isNull();
        assertThat(material.identityProvider()).isNull();
    }

    @Test
    @DisplayName("SEC-02 非 None 策略必须能从 PKCS#12 读出私钥与证书并建出校验器")
    void nonNonePolicyMustLoadKeyStore() throws Exception {
        Path keyStore = generateKeyStore("client");
        Path trustDir = exportTrustedCertificate(keyStore, "client");
        OpcUaProperties properties = secured(keyStore.toString(), trustDir.toString(), null, null);

        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties,
                context(ref -> Optional.of(new CredentialResolver.Credential("u", STORE_PASSWORD.toCharArray(),
                        Map.of()))), "c1");

        assertThat(material.keyPair()).isNotNull();
        assertThat(material.keyPair().getPrivate()).isNotNull();
        assertThat(material.certificate()).isNotNull();
        assertThat(material.certificateValidator()).as("必须建出服务端证书校验器").isNotNull();
        assertThat(material.identityProvider()).as("未配置用户名时必须是匿名").isNull();
    }

    @Test
    @DisplayName("SEC-03 配置用户名时必须产出用户名身份提供者")
    void credentialsMustProduceUsernameIdentity() throws Exception {
        Path keyStore = generateKeyStore("client");
        Path trustDir = exportTrustedCertificate(keyStore, "client");
        OpcUaProperties properties = secured(keyStore.toString(), trustDir.toString(), "operator", null);

        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties,
                context(ref -> Optional.of(new CredentialResolver.Credential("operator",
                        STORE_PASSWORD.toCharArray(), Map.of()))), "c1");

        assertThat(material.identityProvider()).isNotNull();
    }

    @Test
    @DisplayName("SEC-04 trust-all 必须能在没有信任目录时建出校验器（开发路径）")
    void trustAllMustWorkWithoutTrustDirectory() throws Exception {
        Path keyStore = generateKeyStore("client");
        OpcUaProperties properties = secured(keyStore.toString(), null, null, Boolean.TRUE);
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties,
                context(ref -> Optional.of(new CredentialResolver.Credential("u", STORE_PASSWORD.toCharArray(),
                        Map.of()))), "c1");
        assertThat(material.certificateValidator()).isNotNull();
    }

    @Test
    @DisplayName("SEC-05 非 None 策略缺 keystore 必须 fail-fast")
    void missingKeyStoreMustFailFast() {
        OpcUaProperties properties = secured(null, tempDir.toString(), null, null);
        AdapterContext context = context(ref -> Optional.of(new CredentialResolver.Credential("u",
                STORE_PASSWORD.toCharArray(), Map.of())));
        assertThatThrownBy(() -> OpcUaSecurity.prepare(properties, context, "c1"))
                .as("没有私钥就无法签名/加密，必须在这里拒绝而不是等握手失败")
                .isInstanceOf(ConnectionException.class)
                .hasMessageContaining(OpcUaAdapter.MSG_KEYSTORE_MISSING);
    }

    @Test
    @DisplayName("SEC-06 既无信任目录又未开 trust-all 必须 fail-fast")
    void missingTrustConfigurationMustFailFast() throws Exception {
        Path keyStore = generateKeyStore("client");
        OpcUaProperties properties = secured(keyStore.toString(), null, null, null);
        AdapterContext context = context(ref -> Optional.of(new CredentialResolver.Credential("u",
                STORE_PASSWORD.toCharArray(), Map.of())));
        assertThatThrownBy(() -> OpcUaSecurity.prepare(properties, context, "c1"))
                .as("否则每台服务器证书都会校验失败，而宿主会去排查网络")
                .isInstanceOf(ConnectionException.class)
                .hasMessageContaining(OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED);
    }

    @Test
    @DisplayName("SEC-07 取不到凭据必须 fail-fast，绝不回落到明文口令")
    void unresolvableCredentialMustFailFast() throws Exception {
        Path keyStore = generateKeyStore("client");
        OpcUaProperties properties = secured(keyStore.toString(), tempDir.toString(), null, Boolean.TRUE);
        AdapterContext context = context(ref -> Optional.empty());
        assertThatThrownBy(() -> OpcUaSecurity.prepare(properties, context, "c1"))
                .isInstanceOf(ConnectionException.class)
                .hasMessageContaining(OpcUaAdapter.MSG_CREDENTIAL_MISSING);
    }

    private static OpcUaProperties plaintext() {
        return new OpcUaProperties(true, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static OpcUaProperties secured(String keyStore, String trustDir, String username, Boolean trustAll) {
        return new OpcUaProperties(true, POLICY, "SIGN_AND_ENCRYPT", null, null, null, null, null,
                username, username == null ? null : "opcua-ref", keyStore, trustDir, trustAll);
    }

    private AdapterContext context(CredentialResolver credentials) {
        return new DefaultAdapterContext(OpcUaAdapter.PROTOCOL_CODE, DefaultAdapterSettings.defaults(),
                new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE, credentials, Clock.systemUTC(), 16);
    }

    private Path generateKeyStore(String alias) throws Exception {
        Path keyStore = tempDir.resolve(alias + ".p12");
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=ypbin-test", "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD);
        return keyStore;
    }

    private Path exportTrustedCertificate(Path keyStore, String alias) throws Exception {
        Path trustDir = Files.createDirectories(tempDir.resolve("trusted"));
        runKeytool("-exportcert", "-alias", alias, "-keystore", keyStore.toString(),
                "-storepass", STORE_PASSWORD, "-rfc", "-file", trustDir.resolve(alias + ".pem").toString());
        return trustDir;
    }

    private static void runKeytool(String... args) throws Exception {
        String javaHome = System.getProperty("java.home");
        java.util.List<String> command = new java.util.ArrayList<>();
        command.add(Path.of(javaHome, "bin", "keytool").toString());
        java.util.Collections.addAll(command, args);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || process.exitValue() != 0) {
            throw new IllegalStateException("keytool failed: " + output);
        }
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

    /**
     * 未使用的协议码引用（保持 import 一致）。
     *
     * @return 协议码
     */
    static ProtocolCode unusedCode() {
        return OpcUaAdapter.PROTOCOL_CODE;
    }
}
