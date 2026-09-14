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
import java.security.cert.X509Certificate;
import java.util.ArrayList;
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
    record Material(KeyPair keyPair, X509Certificate certificate,
            CertificateValidator certificateValidator, IdentityProvider identityProvider) {
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
                    new UsernameProvider(properties.username(), resolvePassword(properties, context,
                            connectionId)));
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
        char[] password = resolvePassword(properties, context, connectionId).toCharArray();
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
        //  这不是框架太严，而是该证书本身不合规；签发时必须显式带上
        //  `-ext ku=digitalSignature,keyEncipherment -ext eku=clientAuth,serverAuth`。）
        //
        // 唯一有意放宽的是 **HOSTNAME**：现场服务器证书的 CN/SAN 常与配置的 host 不一致
        // （IP 直连尤其常见），保留它会让大量可用的现场设备连不上。
        //
        // 代价必须写清楚：信任目录里**只能放叶子证书**。放 CA 会让该 CA 签发的任意主体证书通过校验。
        Set<ValidationCheck> checks = Set.of(
                ValidationCheck.VALIDITY,
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
        return certificates;
    }

    private static String resolvePassword(OpcUaProperties properties, AdapterContext context,
            String connectionId) {
        Optional<CredentialResolver.Credential> resolved =
                context.credentials().resolve(properties.credentialRef());
        if (resolved.isEmpty()) {
            // 绝不回落到配置文件里的明文：凭据必须来自宿主的安全存储
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_CREDENTIAL_MISSING,
                    String.valueOf(properties.credentialRef()));
        }
        char[] secret = resolved.get().secret();
        if (secret == null || secret.length == 0) {
            throw new ConnectionException(connectionId, OpcUaAdapter.MSG_CREDENTIAL_MISSING,
                    "empty secret for " + properties.credentialRef());
        }
        return new String(secret);
    }

}
