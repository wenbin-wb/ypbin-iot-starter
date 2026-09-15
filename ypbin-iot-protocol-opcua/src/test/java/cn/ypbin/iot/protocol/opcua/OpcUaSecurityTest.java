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
import static org.assertj.core.api.Assertions.assertThatCode;
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
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
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

    /** keystore 口令的凭据引用（与 OPC UA 用户口令的 ref 分开）。 */
    private static final String STORE_REF = "keystore-ref";

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
        OpcUaProperties properties = secured(keyStore.toString(), trustDir.toString(), null, null, null);

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
        OpcUaProperties properties = secured(keyStore.toString(), trustDir.toString(), "operator", null, null);

        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties,
                context(ref -> Optional.of(new CredentialResolver.Credential("operator",
                        STORE_PASSWORD.toCharArray(), Map.of()))), "c1");

        assertThat(material.identityProvider()).isNotNull();
    }

    @Test
    @DisplayName("SEC-04 trust-all 必须能在没有信任目录时建出校验器（开发路径）")
    void trustAllMustWorkWithoutTrustDirectory() throws Exception {
        Path keyStore = generateKeyStore("client");
        OpcUaProperties properties = secured(keyStore.toString(), null, null, Boolean.TRUE, null);
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties,
                context(ref -> Optional.of(new CredentialResolver.Credential("u", STORE_PASSWORD.toCharArray(),
                        Map.of()))), "c1");
        assertThat(material.certificateValidator()).isNotNull();
    }

    @Test
    @DisplayName("SEC-05 非 None 策略缺 keystore 必须 fail-fast")
    void missingKeyStoreMustFailFast() {
        OpcUaProperties properties = secured(null, tempDir.toString(), null, null, null);
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
        OpcUaProperties properties = secured(keyStore.toString(), null, null, null, null);
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
        OpcUaProperties properties = secured(keyStore.toString(), tempDir.toString(), null, Boolean.TRUE, null);
        AdapterContext context = context(ref -> Optional.empty());
        assertThatThrownBy(() -> OpcUaSecurity.prepare(properties, context, "c1"))
                .isInstanceOf(ConnectionException.class)
                .hasMessageContaining(OpcUaAdapter.MSG_CREDENTIAL_MISSING);
    }

    @Test
    @DisplayName("SEC-08 信任列表必须真的约束：受信证书通过、未受信证书被拒")
    void trustListMustActuallyConstrain() throws Exception {
        // 这一条直接验证「安全材料装配出来的校验器是否真的在校验」——
        // 比验证「装配成功」重要得多：一个恒返回成功的校验器也能让装配测试全绿。
        Path trustedStore = generateKeyStore("trusted");
        Path trustDir = exportTrustedCertificate(trustedStore, "trusted");
        Path untrustedStore = generateKeyStore("untrusted");

        OpcUaProperties properties = secured(trustedStore.toString(), trustDir.toString(), null, null, null);
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(properties,
                context(ref -> Optional.of(new CredentialResolver.Credential("u",
                        STORE_PASSWORD.toCharArray(), Map.of()))), "c1");

        X509Certificate trustedCert = readCertificate(trustedStore);
        X509Certificate untrustedCert = readCertificate(untrustedStore);
        CertificateValidator validator = material.certificateValidator();

        // 受信证书必须通过（同时验证我们选的 checks 不会误拒 keytool 默认证书——
        // 曾经放进去的 EXTENDED_KEY_USAGE_END_ENTITY 就会，因为默认证书没有 EKU 扩展）
        assertThatCode(() -> validator.validateCertificateChain(List.of(trustedCert),
                "urn:ypbin:iot:test-server", new String[] {"127.0.0.1"}))
                .as("受信证书必须通过；若抛错说明 checks 选错了（例如要求了证书没有的 EKU）")
                .doesNotThrowAnyException();

        // 未受信证书必须被拒——否则「信任列表」形同虚设，等于中间人可任意替换服务端
        // 必须钉住**拒绝原因**：只断言「抛了某个 Exception」会让任何异常（含 NPE）都算通过
        assertThatThrownBy(() -> validator.validateCertificateChain(List.of(untrustedCert),
                "urn:ypbin:iot:test-server", new String[] {"127.0.0.1"}))
                .as("未受信证书必须因「找不到可信路径」被拒，否则信任列表是装饰")
                .hasMessageContaining("unable to find valid certification path");
    }

    @Test
    @DisplayName("SEC-09 trust-all 必须真的放行（开发路径语义）")
    void trustAllMustAcceptAnyCertificate() throws Exception {
        Path keyStore = generateKeyStore("client");
        Path otherStore = generateKeyStore("other");
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(
                secured(keyStore.toString(), null, null, Boolean.TRUE, null),
                context(ref -> Optional.of(new CredentialResolver.Credential("u",
                        STORE_PASSWORD.toCharArray(), Map.of()))), "c1");
        assertThatCode(() -> material.certificateValidator()
                .validateCertificateChain(List.of(readCertificate(otherStore)),
                        "urn:whatever", new String[] {"10.0.0.1"}))
                .as("trust-all 必须放行任意服务端证书（仅开发用途）")
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-13 主机名校验必须是可选开关：默认关闭，开启后仍能通过（证书 SAN 含该地址）")
    void hostnameVerificationIsOptional() throws Exception {
        Path keyStore = generateKeyStore("client");
        Path trustDir = exportTrustedCertificate(keyStore, "client");
        AdapterContext ctx = context(ref -> Optional.of(new CredentialResolver.Credential("u",
                STORE_PASSWORD.toCharArray(), Map.of())));

        // 默认关闭：测试证书的 SAN 含 127.0.0.1，但这里传入一个不匹配的 host，仍应通过 ——
        // 证明默认行为确实是「不校验主机名」（现场 IP 直连很常见）
        OpcUaSecurity.Material lenient = OpcUaSecurity.prepare(
                secured(keyStore.toString(), trustDir.toString(), null, null, null), ctx, "c1");
        assertThatCode(() -> lenient.certificateValidator().validateCertificateChain(
                List.of(readCertificate(keyStore)), "urn:ypbin:iot:test-server",
                new String[] {"10.9.9.9"})).doesNotThrowAnyException();

        // 开启主机名校验：地址匹配时必须通过（否则这个开关会变成「一开就连不上」）
        OpcUaSecurity.Material strict = OpcUaSecurity.prepare(
                secured(keyStore.toString(), trustDir.toString(), null, null, Boolean.TRUE), ctx, "c1");
        assertThatCode(() -> strict.certificateValidator().validateCertificateChain(
                List.of(readCertificate(keyStore)), "urn:ypbin:iot:test-server",
                new String[] {"127.0.0.1"})).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SEC-10 证书缺 KeyUsage 的 nonRepudiation 必须被拒（规范要求，不是可选）")
    void missingNonRepudiationMustBeRejected() throws Exception {
        // 企业 PKI 里常见只签 digitalSignature+keyEncipherment 的证书；OPC UA 要求更多。
        // 这条负向用例与 SEC-12/SEC-13 一起，使「四项 checks」真的被门禁覆盖 ——
        // 复审曾用变异测试证明：把 checks 改成 Set.of()（等于全删）后，原有用例仍 9/9 全绿。
        assertRejectedFor("ku=digitalSignature,keyEncipherment",
                "-ext", "eku=clientAuth,serverAuth",
                "-ext", "san=ip:127.0.0.1,uri:urn:ypbin:iot:test-server");
    }

    @Test
    @DisplayName("SEC-11 证书 SAN 缺匹配 URI 必须被拒（APPLICATION_URI 校验生效）")
    void missingApplicationUriMustBeRejected() throws Exception {
        assertRejectedFor("ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment,"
                        + "keyCertSign,cRLSign",
                "-ext", "eku=clientAuth,serverAuth",
                "-ext", "san=ip:127.0.0.1");
    }

    @Test
    @DisplayName("SEC-12 自签证书缺 keyCertSign 必须被拒（自签作锚时的额外要求）")
    void selfSignedWithoutKeyCertSignMustBeRejected() throws Exception {
        assertRejectedFor("ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment",
                "-ext", "eku=clientAuth,serverAuth",
                "-ext", "san=ip:127.0.0.1,uri:urn:ypbin:iot:test-server");
    }

    @Test
    @DisplayName("SEC-14 开启主机名校验后地址不匹配必须被拒（钉住那个开关真的接线了）")
    void hostnameMismatchMustBeRejectedWhenEnabled() throws Exception {
        // SEC-13 曾经是假绿：secured() 的函数体把 verifyHostname 写成了 null，
        // 「开启」与「关闭」构造出的 properties 逐字段相同 —— 两次断言的是同一件事。
        Path keyStore = generateKeyStore("client");
        Path trustDir = exportTrustedCertificate(keyStore, "client");
        AdapterContext ctx = context(ref -> Optional.of(new CredentialResolver.Credential("u",
                STORE_PASSWORD.toCharArray(), Map.of())));

        OpcUaSecurity.Material strict = OpcUaSecurity.prepare(
                secured(keyStore.toString(), trustDir.toString(), null, null, Boolean.TRUE), ctx, "c1");
        assertThatThrownBy(() -> strict.certificateValidator().validateCertificateChain(
                List.of(readCertificate(keyStore)), "urn:ypbin:iot:test-server",
                new String[] {"10.9.9.9"}))
                .as("开启主机名校验后，地址不在证书 SAN 里就必须被拒 —— 否则这个开关是装饰")
                .isInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("SEC-15 信任目录只有 CA 签发的叶子时必须显式失败（它当不了信任锚）")
    void caIssuedLeafAloneMustFailFast() throws Exception {
        // Milo 的信任锚**只能由自签证书构成**（CertificateValidationUtil.buildTrustedCertPath），
        // 因此把一张 CA 签发的叶子单独放进信任目录是**永远用不上**的配置 ——
        // 以前它会以一句「trustAnchors must be non-empty」失败，宿主根本猜不到原因。
        Path caKeyStore = generateCaKeyStore();
        Path leafKeyStore = generateCaIssuedLeafKeyStore(caKeyStore);
        Path trustDir = tempDir.resolve("trusted-leaf-only");
        Files.createDirectories(trustDir);
        exportCertificate(leafKeyStore, "leaf", trustDir.resolve("leaf.pem"));

        AdapterContext ctx = context(ref -> Optional.of(new CredentialResolver.Credential("u",
                STORE_PASSWORD.toCharArray(), Map.of())));
        assertThatThrownBy(() -> OpcUaSecurity.prepare(
                secured(leafKeyStore.toString(), trustDir.toString(), null, null, null), ctx, "c1"))
                .as("必须在装配期就说清「这张证书当不了锚、请改放 CA 或自签证书」")
                .isInstanceOf(ConnectionException.class)
                .hasMessageContaining("cannot act as a trust anchor");
    }

    @Test
    @DisplayName("SEC-16 信任目录放 CA 时可用，但它会接受该 CA 签发的**任何**证书（不是精确 pin）")
    void caAnchorTrustsEveryCertItIssued() throws Exception {
        // 这条用例把「CA 作锚」的真实语义钉住：它**不是**精确 pin。
        // 反直觉之处在于：即使把「CA + 指定叶子」都放进信任目录，结论也一样 ——
        // 锚是那个 CA，而锚本身就在信任列表里，最终那道「路径须含信任证书」的校验恒真。
        // 想精确 pin 只有一种形式：放**自签的终端实体证书**。
        Path caKeyStore = generateCaKeyStore();
        Path leafKeyStore = generateCaIssuedLeafKeyStore(caKeyStore);
        Path otherKeyStore = generateCaIssuedLeafKeyStore(caKeyStore, "other-leaf", "other");
        Path trustDir = Files.createDirectories(tempDir.resolve("trusted-ca"));
        exportCertificate(caKeyStore, "ca", trustDir.resolve("ca.pem"));

        AdapterContext ctx = context(ref -> Optional.of(new CredentialResolver.Credential("u",
                STORE_PASSWORD.toCharArray(), Map.of())));
        OpcUaSecurity.Material material = OpcUaSecurity.prepare(
                secured(leafKeyStore.toString(), trustDir.toString(), null, null, null), ctx, "c1");

        // 该 CA 签发的叶子必须通过（证明 CA 配置可用）
        assertThatCode(() -> material.certificateValidator().validateCertificateChain(
                List.of(readCertificate(leafKeyStore, "leaf")), "urn:ypbin:iot:test-server",
                new String[] {"127.0.0.1"})).doesNotThrowAnyException();

        // 同一 CA 签发的**另一张**叶子也会通过 —— 这就是「CA 作锚 = 信任该 CA 的一切」的证据
        assertThatCode(() -> material.certificateValidator().validateCertificateChain(
                List.of(readCertificate(otherKeyStore, "other-leaf")), "urn:ypbin:iot:test-server",
                new String[] {"127.0.0.1"}))
                .as("CA 作锚会接受该 CA 签发的任何证书；想精确 pin 必须用自签终端实体证书")
                .doesNotThrowAnyException();
    }

    /** 生成自签 CA（basicConstraints CA:true）。 */
    private Path generateCaKeyStore() throws Exception {
        Path keyStore = tempDir.resolve("ca.p12");
        runKeytool("-genkeypair", "-alias", "ca", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=ypbin-test-ca", "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-ext", "bc:critical=ca:true,pathlen:0",
                "-ext", "ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment,keyCertSign,cRLSign");
        return keyStore;
    }

    /** 生成由给定 CA 签发的叶子证书 keystore（别名默认 {@code leaf}）。 */
    private Path generateCaIssuedLeafKeyStore(Path caKeyStore) throws Exception {
        return generateCaIssuedLeafKeyStore(caKeyStore, "leaf", "ypbin-test");
    }

    private Path generateCaIssuedLeafKeyStore(Path caKeyStore, String alias, String commonName)
            throws Exception {
        Path leafKeyStore = tempDir.resolve(alias + ".p12");
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=" + commonName, "-keystore", leafKeyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-ext", "ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment",
                "-ext", "eku=clientAuth,serverAuth",
                "-ext", "san=ip:127.0.0.1,uri:urn:ypbin:iot:test-server");
        Path csr = tempDir.resolve(alias + ".csr");
        runKeytool("-certreq", "-alias", alias, "-keystore", leafKeyStore.toString(),
                "-storepass", STORE_PASSWORD, "-file", csr.toString());
        Path signed = tempDir.resolve(alias + ".cer");
        runKeytool("-gencert", "-alias", "ca", "-keystore", caKeyStore.toString(),
                "-storepass", STORE_PASSWORD, "-infile", csr.toString(), "-outfile", signed.toString(),
                "-validity", "365",
                "-ext", "ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment",
                "-ext", "eku=clientAuth,serverAuth",
                "-ext", "san=ip:127.0.0.1,uri:urn:ypbin:iot:test-server");
        // 把 CA 与签名后的叶子证书都导入，替换掉原先的自签叶子
        Path caCer = tempDir.resolve("ca.cer");
        exportCertificate(caKeyStore, "ca", caCer);
        runKeytool("-importcert", "-noprompt", "-alias", "ca", "-file", caCer.toString(),
                "-keystore", leafKeyStore.toString(), "-storepass", STORE_PASSWORD);
        runKeytool("-importcert", "-noprompt", "-alias", alias, "-file", signed.toString(),
                "-keystore", leafKeyStore.toString(), "-storepass", STORE_PASSWORD);
        return leafKeyStore;
    }

    /** 从 keystore 导出某别名的证书（PEM）。 */
    private void exportCertificate(Path keyStore, String alias, Path target) throws Exception {
        runKeytool("-exportcert", "-alias", alias, "-keystore", keyStore.toString(),
                "-storepass", STORE_PASSWORD, "-rfc", "-file", target.toString());
    }

    /**
     * 用给定的扩展参数签发一张自签证书（别名固定 {@code client}）、放进信任目录，断言校验器**拒绝**它。
     *
     * @param kuValue  keytool 的 {@code -ext} 取值（形如 {@code ku=digitalSignature,...}）
     * @param extraExt 其余 {@code -ext} 参数（按 {@code -ext, value} 成对给出）
     */
    private void assertRejectedFor(String kuValue, String... extraExt) throws Exception {
        Path keyStore = tempDir.resolve("variant-" + java.util.UUID.randomUUID() + ".p12");
        Path trustDir = Files.createDirectories(tempDir.resolve("trusted-" + java.util.UUID.randomUUID()));
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of(
                "-genkeypair", "-alias", "client", "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=ypbin-test", "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-ext", kuValue));
        java.util.Collections.addAll(args, extraExt);
        runKeytool(args.toArray(new String[0]));
        runKeytool("-exportcert", "-alias", "client", "-keystore", keyStore.toString(),
                "-storepass", STORE_PASSWORD, "-rfc",
                "-file", trustDir.resolve("client.pem").toString());

        OpcUaSecurity.Material material = OpcUaSecurity.prepare(
                secured(keyStore.toString(), trustDir.toString(), null, null, null),
                context(ref -> Optional.of(new CredentialResolver.Credential("u",
                        STORE_PASSWORD.toCharArray(), Map.of()))), "c1");
        X509Certificate certificate = readCertificate(keyStore);
        assertThatThrownBy(() -> material.certificateValidator().validateCertificateChain(
                List.of(certificate), "urn:ypbin:iot:test-server", new String[] {"127.0.0.1"}))
                .as("不合规的证书必须被拒；若通过说明对应的检查项没生效（变异测试可复现）")
                .isInstanceOf(Exception.class);
    }

    private X509Certificate readCertificate(Path keyStore, String alias) throws Exception {
        char[] password = STORE_PASSWORD.toCharArray();
        try (InputStream input = Files.newInputStream(keyStore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, password);
            return (X509Certificate) store.getCertificate(alias);
        }
    }

    private X509Certificate readCertificate(Path keyStore) throws Exception {
        char[] password = STORE_PASSWORD.toCharArray();
        try (InputStream input = Files.newInputStream(keyStore)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(input, password);
            return (X509Certificate) store.getCertificate(store.aliases().nextElement());
        }
    }

    private static OpcUaProperties plaintext() {
        return new OpcUaProperties(true, null, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private static OpcUaProperties secured(String keyStore, String trustDir, String username,
            Boolean trustAll, Boolean verifyHostname) {
        return new OpcUaProperties(true, POLICY, "SIGN_AND_ENCRYPT", null, null, null, null, null, username, username == null ? null : "opcua-ref", keyStore, STORE_REF, trustDir, trustAll,
                verifyHostname);
    }

    private AdapterContext context(CredentialResolver credentials) {
        return new DefaultAdapterContext(OpcUaAdapter.PROTOCOL_CODE, DefaultAdapterSettings.defaults(),
                new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE, credentials, Clock.systemUTC(), 16);
    }

    private Path generateKeyStore(String alias) throws Exception {
        Path keyStore = tempDir.resolve(alias + ".p12");
        // 必须显式带上 KeyUsage 与 EKU：OPC UA 规范要求终端实体证书具备这两个扩展，
        // 而 keytool 的默认产物两个都没有（缺任一个都会被证书校验拒绝）。
        runKeytool("-genkeypair", "-alias", alias, "-keyalg", "RSA", "-keysize", "2048",
                "-validity", "365", "-dname", "CN=ypbin-test", "-keystore", keyStore.toString(),
                "-storetype", "PKCS12", "-storepass", STORE_PASSWORD, "-keypass", STORE_PASSWORD,
                "-ext", "ku=digitalSignature,nonRepudiation,keyEncipherment,dataEncipherment,keyCertSign,cRLSign",
                "-ext", "eku=clientAuth,serverAuth",
                "-ext", "san=ip:127.0.0.1,dns:localhost,uri:urn:ypbin:iot:test-server");
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
