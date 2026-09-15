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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.CredentialResolver;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.protocol.opcua.autoconfigure.OpcUaProperties;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Key;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.eclipse.milo.opcua.sdk.client.identity.IdentityProvider;
import org.eclipse.milo.opcua.sdk.client.identity.UsernameProvider;
import org.eclipse.milo.opcua.stack.core.security.CertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.DefaultClientCertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.MemoryCertificateQuarantine;
import org.eclipse.milo.opcua.stack.core.security.MemoryTrustListManager;
import org.eclipse.milo.opcua.stack.core.util.validation.ValidationCheck;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 安全材料装配：客户端证书、信任列表与身份。
 *
 * <p><b>为什么非 None 策略必须要有证书</b>：签名与加密都需要客户端私钥；
 * 没有证书时连接会在握手阶段被服务端拒绝，而错误信息看起来像「端点不可达」。
 * 因此这里把「取不到证书」变成<b>装配期的显式失败</b>，而不是等到连接时报一个误导性的错误。</p>
 *
 * <p><b>自签证书是开发路径</b>：未配置 keystore 时按策略要求即时生成自签证书，
 * 并<b>每次都打 WARN</b>——它的指纹每次进程启动都不同，服务端信任列表无法长期固定，
 * 生产环境必须换成受信的 PKCS#12。</p>
 *
 * <p><b>信任列表必须显式配置</b>：既不配置信任目录、又不打开 {@code trust-all} 时直接拒绝。
 * 否则每台服务器证书都会校验失败，宿主的排查方向会被引向「网络不通」。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class OpcUaSecurity {

    private static final Logger log = LoggerFactory.getLogger(OpcUaSecurity.class);

    private static final String KEYSTORE_TYPE = "PKCS12";

    private static final String RSA_ALGORITHM = "RSA";

    private static final int RSA_KEY_SIZE = 2048;

    /** SubjectAlternativeName 中 URI 的类型编号（RFC 5280：uniformResourceIdentifier = 6）。 */
    private static final int SAN_URI_TYPE = 6;

    private static final int CERT_VALIDITY_DAYS = 365;

    private OpcUaSecurity() {
    }

    /**
     * 安全材料。
     *
     * @param keyPair               客户端私钥对；明文策略时为 {@code null}
     * @param certificate           客户端证书；明文策略时为 {@code null}
     * @param certificateValidator  服务端证书校验器；明文策略时为 {@code null}
     * @param identityProvider      身份提供者；未配置凭据时为 {@code null}（匿名）
     * @author wenbin
     * @since 2026-09-14
     */
    record Material(@Nullable KeyPair keyPair, @Nullable X509Certificate certificate,
            @Nullable CertificateValidator certificateValidator,
            @Nullable IdentityProvider identityProvider) {
    }

    /**
     * 准备安全材料。
     *
     * @param properties   协议配置
     * @param context      适配器上下文
     * @param connectionId 链路标识（用于错误定位）
     * @return 安全材料
     * @throws ConnectionException 配置不足或材料加载失败时
     */
    static Material prepare(OpcUaProperties properties, AdapterContext context, String connectionId) {
        if (properties.isPlaintext()) {
            // 明文策略：无证书、无校验；凭据在 open() 入口已被拒绝，这里不会出现
            return new Material(null, null, null, null);
        }
        KeyMaterial keyMaterial = loadKeyPair(properties, context, connectionId);
        if (properties.hasCredentials()) {
            return new Material(keyMaterial.keyPair(), keyMaterial.certificate(),
                    validatorOf(properties, connectionId),
                    new UsernameProvider(properties.username(), resolvePassword(
                            properties.credentialRef(), "user password", context, connectionId)));
        }
        return new Material(keyMaterial.keyPair(), keyMaterial.certificate(),
                validatorOf(properties, connectionId), null);
    }

    /**
     * 客户端私钥与证书。
     *
     * @param keyPair     私钥对
     * @param certificate 证书
     * @author wenbin
     * @since 2026-09-14
     */
    private record KeyMaterial(KeyPair keyPair, X509Certificate certificate) {
    }

    /**
     * 从证书的 SubjectAlternativeName 中取出 application URI。
     *
     * <p><b>为什么必须设置</b>：OPC UA 服务端在 CreateSession 时会校验客户端
     * {@code ApplicationDescription.applicationUri} 与其证书 SAN 中的 URI 是否一致，
     * 不一致直接以 {@code Bad_CertificateUriInvalid} 拒绝会话。
     * 客户端若沿用库自动推导的 URI（形如 {@code urn:<hostname>:...}），
     * 与证书里的 URI 必然不同 —— <b>结果是加密通道能建立、但会话永远建不起来</b>，
     * 而错误信息看起来与信任/网络都无关。这个缺陷只有在真实加密服务端上才能暴露。</p>
     *
     * @param certificate 客户端证书
     * @param connectionId 链路标识（用于错误定位）
     * @return SAN 中的 URI
     * @throws ConnectionException 证书没有 SAN URI 时（该证书不能用于加密会话）
     */
    static String applicationUriOf(X509Certificate certificate, String connectionId) {
        Collection<List<?>> names;
        try {
            names = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException ex) {
            throw new ConnectionException(connectionId, ex, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                    "unreadable subject alternative names");
        }
        if (names != null) {
            for (List<?> entry : names) {
                if (entry.size() >= 2 && Integer.valueOf(SAN_URI_TYPE).equals(entry.get(0))
                        && entry.get(1) instanceof String uri && !uri.isBlank()) {
                    return uri;
                }
            }
        }
        // 没有 SAN URI 的证书无法通过服务端的 ApplicationUri 校验：明确报错，
        // 而不是让它沿用一个必然不匹配的推导 URI 去撞 Bad_CertificateUriInvalid
        throw new ConnectionException(connectionId, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                "certificate has no URI in subject alternative names");
    }

    private static KeyMaterial loadKeyPair(OpcUaProperties properties, AdapterContext context,
            String connectionId) {
        if (properties.clientKeyStore() == null || properties.clientKeyStore().isBlank()) {
            // 刻意**不**自动生成自签证书：它的指纹每次进程启动都不同，服务端信任列表无法长期固定，
            // 结果是「本地看着能连、到现场天天要重新导证书」。宁可在这里明确要求配置 keystore，
            // 也不给一条看起来能用、实际无法固定信任的路径。
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                    String.valueOf(properties.securityPolicy()));
        }
        return readKeyMaterial(properties, context, connectionId);
    }

    private static KeyMaterial readKeyMaterial(OpcUaProperties properties, AdapterContext context,
            String connectionId) {
        // keystore 口令与 OPC UA 用户口令是两回事，**必须分别引用**：
        // 混用同一个 ref 会让「改了用户口令」意外导致证书读不出来。
        // 因此未配置 client-key-store-password-ref 时**显式失败**，而不是回落到 credentialRef
        // （回落会让上面那句注释变成假话，也会把两类口令悄悄绑在一起）。
        char[] password = resolvePassword(properties.clientKeyStorePasswordRef(), "keystore password",
                context, connectionId).toCharArray();
        Path path = Path.of(properties.clientKeyStore());
        if (!Files.isRegularFile(path)) {
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                    properties.clientKeyStore());
        }
        try (InputStream input = Files.newInputStream(path)) {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE_TYPE);
            keyStore.load(input, password);
            Enumeration<String> aliases = keyStore.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (!keyStore.isKeyEntry(alias)) {
                    continue;
                }
                Key key = keyStore.getKey(alias, password);
                if (key instanceof PrivateKey privateKey) {
                    Certificate certificate = keyStore.getCertificate(alias);
                    if (certificate == null) {
                        // 有私钥无证书：签名/加密都无法进行，明确报错而不是带病往下走
                        throw new ConnectionException(connectionId, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                                "key entry '" + alias + "' has no certificate");
                    }
                    return new KeyMaterial(new KeyPair(certificate.getPublicKey(), privateKey),
                            (X509Certificate) certificate);
                }
            }
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                    "no private key entry in " + properties.clientKeyStore());
        } catch (ConnectionException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ConnectionException(connectionId, ex, OpcUaAdapter.MSG_KEYSTORE_MISSING,
                    properties.clientKeyStore());
        }
    }

    private static CertificateValidator validatorOf(OpcUaProperties properties, String connectionId) {
        if (properties.isTrustAll()) {
            log.warn("[ypbin-iot] OPC UA trust-all is ENABLED: server certificates are NOT validated. "
                    + "This is a development-only setting and exposes the link to man-in-the-middle.");
            return (chain, applicationUri, hostname) -> {
                // 开发期显式信任全部：接受任何服务端证书
            };
        }
        if (properties.trustListDir() == null || properties.trustListDir().isBlank()) {
            // 既不配置信任目录、又不打开 trust-all：连接必然在证书校验处失败，
            // 而错误看起来像「网络不通」。这里提前拒绝，把排查方向摆正。
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED,
                    String.valueOf(properties.securityPolicy()));
        }
        MemoryTrustListManager trustList = new MemoryTrustListManager();
        List<X509Certificate> trusted = loadTrustedCertificates(properties.trustListDir(), connectionId);
        trusted.forEach(trustList::addTrustedCertificate);
        CertificateQuarantine quarantine = new MemoryCertificateQuarantine();
        // 校验集：有效性 + 终端实体用途（KeyUsage 与 ExtendedKeyUsage）+ 应用 URI。
        //
        // 这四项都是 OPC UA 规范对**终端实体证书**的要求，因此保留不放宽：
        // 证书缺 KeyUsage 或 EKU 扩展时本连接就不该建立。
        // （注：`keytool -genkeypair` 的默认产物**两个扩展都没有**，用它会直接报
        //  `Bad_CertificateUseNotAllowed: KeyUsage extension not found` ——
        //  这不是框架太严，而是该证书本身不合规。**完整的可用签发参数**见 PROTOCOLS.md；
        //  要点是 KeyUsage 必须含 `nonRepudiation`（只带 digitalSignature+keyEncipherment 会被拒），
        //  自签证书还需 `keyCertSign`，且 SAN 必须含与服务端 declaration 一致的 `uri:`。）
        //
        // 唯一有意放宽的是 **HOSTNAME**：现场服务器证书的 CN/SAN 常与配置的 host 不一致
        // （IP 直连尤其常见），保留它会让大量可用的现场设备连不上。
        //
        // 代价必须写清楚：信任目录里**只能放叶子证书**。放 CA 会让该 CA 签发的任意主体证书通过校验。
        // 是否校验主机名做成**可选项**（默认关）：
        // 现场服务器证书的 CN/SAN 常与配置的 host 不一致（IP 直连尤其常见），
        // 默认打开会让大量可用设备连不上；但安全敏感的部署应当自行打开。
        // 注意它的防护力有限：比对值取自服务端自己 GetEndpoints 返回的端点描述，
        // 对**主动 MITM 无防护**（真正的门闩只有信任列表）。
        Set<ValidationCheck> checks = properties.isVerifyHostname()
                ? Set.of(ValidationCheck.VALIDITY,
                        ValidationCheck.KEY_USAGE_END_ENTITY,
                        ValidationCheck.EXTENDED_KEY_USAGE_END_ENTITY,
                        ValidationCheck.APPLICATION_URI,
                        ValidationCheck.HOSTNAME)
                : Set.of(ValidationCheck.VALIDITY,
                        ValidationCheck.KEY_USAGE_END_ENTITY,
                        ValidationCheck.EXTENDED_KEY_USAGE_END_ENTITY,
                        ValidationCheck.APPLICATION_URI);
        return new DefaultClientCertificateValidator(trustList, checks, quarantine);
    }

    private static List<X509Certificate> loadTrustedCertificates(String directory, String connectionId) {
        List<X509Certificate> certificates = new ArrayList<>();
        Path dir = Path.of(directory);
        if (!Files.isDirectory(dir)) {
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED, directory);
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(Files::isRegularFile).toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    CertificateFactory factory = CertificateFactory.getInstance("X.509");
                    certificates.add((X509Certificate) factory.generateCertificate(input));
                } catch (Exception ex) {
                    log.warn("[ypbin-iot] failed to load trusted certificate from {}; skipping", file, ex);
                }
            }
        } catch (IOException ex) {
            throw new ConnectionException(connectionId, ex, OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED, directory);
        }
        if (certificates.isEmpty()) {
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED,
                    "no usable certificate found in " + directory);
        }
        diagnoseTrustAnchors(certificates, directory, connectionId);
        return certificates;
    }

    /**
     * 诊断信任目录里各证书**能不能当信任锚**，并在配置不可能按预期工作时显式失败。
     *
     * <p>依据 Milo 源码（{@code CertificateValidationUtil.buildTrustedCertPath}）：
     * <b>信任锚只能由自签证书构成</b>；CA 作为中间证书参与路径构建；
     * 路径构建完成后还会校验「路径（含锚）中至少有一个证书在信任列表里」。</p>
     *
     * <p><b>这里有一个反直觉且与安全相关的推论，必须让宿主知道</b>：
     * 「信任目录同时放 CA 与指定叶子」<b>并不能</b>得到精确 pin ——
     * 因为锚是那个 CA，而锚本身就在信任列表里，那道最终校验恒真，
     * 于是<b>该 CA 签发的任何证书都会被接受</b>。精确 pin 只有一种形式：
     * <b>自签的终端实体证书</b>（此时锚就是它自己）。</p>
     *
     * @param certificates 信任目录里读到的证书
     * @param directory    信任目录（用于错误信息）
     * @param connectionId 链路标识
     */
    private static void diagnoseTrustAnchors(List<X509Certificate> certificates, String directory,
            String connectionId) {
        boolean hasAnchor = false;
        for (X509Certificate certificate : certificates) {
            boolean selfSigned = isSelfSigned(certificate);
            boolean ca = certificate.getBasicConstraints() >= 0;
            if (selfSigned) {
                hasAnchor = true;
                if (ca) {
                    log.warn("[ypbin-iot] trust dir {}: {} is a self-signed CA. As a trust anchor it accepts "
                            + "EVERY certificate that CA has issued (not an exact pin). "
                            + "To pin exactly one server, put that server's SELF-SIGNED certificate instead.",
                            directory, certificate.getSubjectX500Principal().getName());
                }
            } else if (!ca) {
                // 非自签、非 CA 的终端实体证书：既不能做锚，也不能做中间证书 → 这份配置永远用不上它
                throw new ConnectionException(connectionId, OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED,
                        directory + " contains a CA-issued end-entity certificate '"
                                + certificate.getSubjectX500Principal().getName()
                                + "' which cannot act as a trust anchor (only self-signed certificates can). "
                                + "Put the issuing CA in the trust dir instead (note: that trusts every "
                                + "certificate the CA issued), or use the server's self-signed certificate.");
            }
        }
        if (!hasAnchor) {
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_TRUST_NOT_CONFIGURED,
                    directory + " contains no self-signed certificate, so no trust anchor can be built; "
                            + "every server certificate would fail validation.");
        }
    }

    /** 证书是否自签（subject == issuer 且用自身公钥验签通过）。 */
    private static boolean isSelfSigned(X509Certificate certificate) {
        if (!certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())) {
            return false;
        }
        try {
            certificate.verify(certificate.getPublicKey());
            return true;
        } catch (Exception ex) {
            // 验签失败即不是自签：这是判断结论而非异常，故不向上抛
            return false;
        }
    }

    private static String resolvePassword(@Nullable String ref, String what,
            AdapterContext context, String connectionId) {
        if (ref == null || ref.isBlank()) {
            // ref 未配置本身就是配置错误：明确说出缺的是哪个配置项，
            // 而不是让它在 CredentialResolver 里变成一个语焉不详的「解析不到」
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_CREDENTIAL_MISSING,
                    what + "：未配置对应的凭据引用（credential-ref / client-key-store-password-ref）");
        }
        Optional<CredentialResolver.Credential> resolved = context.credentials().resolve(ref);
        if (resolved.isEmpty()) {
            // 绝不回落到配置文件里的明文：凭据必须来自宿主的安全存储
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_CREDENTIAL_MISSING,
                    what + " ref=" + ref);
        }
        char[] secret = resolved.get().secret();
        if (secret == null || secret.length == 0) {
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_CREDENTIAL_MISSING,
                    "empty " + what + " for ref=" + ref);
        }
        return new String(secret);
    }

}
