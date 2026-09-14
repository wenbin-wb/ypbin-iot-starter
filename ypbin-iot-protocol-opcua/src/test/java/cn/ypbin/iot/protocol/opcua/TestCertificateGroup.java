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

import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.security.CertificateFactory;
import org.eclipse.milo.opcua.stack.core.security.CertificateGroup;
import org.eclipse.milo.opcua.stack.core.security.CertificateValidator;
import org.eclipse.milo.opcua.stack.core.security.RsaSha256CertificateFactory;
import org.eclipse.milo.opcua.stack.core.security.TrustListManager;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;

/**
 * 测试用证书组：承载单个服务端密钥对 + 证书，并持有信任列表。
 *
 * <p>Milo 的 stack-core <b>没有提供现成的 {@code CertificateGroup} 实现</b>，
 * 而服务端的签名与解密必须拿到<b>私钥</b>——{@code EndpointConfig} 只接受证书，
 * 私钥只能经 {@code OpcUaServerConfigBuilder.setCertificateManager(...)} 提供。
 * 因此测试侧需要一个最小实现。</p>
 *
 * <p>关键点来自 Milo 官方源码（{@code DefaultCertificateManager}）：证书组按
 * {@code getCertificateGroupId()} 注册、按<b>证书类型</b> NodeId 取密钥，而 RSA-SHA256
 * 应用实例证书的类型 ID 是 {@link NodeIds#RsaSha256ApplicationCertificateType}，
 * 对应的工厂是 {@link RsaSha256CertificateFactory}。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class TestCertificateGroup implements CertificateGroup {

    private static final NodeId GROUP_ID = NodeIds.RsaSha256ApplicationCertificateType;

    private final KeyPair keyPair;

    private final X509Certificate[] chain;

    private final TrustListManager trustListManager;

    private final CertificateValidator certificateValidator;

    private final CertificateFactory certificateFactory;

    TestCertificateGroup(KeyPair keyPair, X509Certificate certificate, TrustListManager trustListManager,
            CertificateValidator certificateValidator) {
        this.keyPair = keyPair;
        this.chain = new X509Certificate[] {certificate};
        this.trustListManager = trustListManager;
        this.certificateValidator = certificateValidator;
        // createRsaSha256CertificateChain 是留给**应用**实现的抽象方法（服务端自签链的生成方式
        // 由应用决定）；测试里直接复用已生成的证书，不做第二次签发。
        this.certificateFactory = new RsaSha256CertificateFactory() {
            @Override
            protected X509Certificate[] createRsaSha256CertificateChain(KeyPair ignored) {
                return chain.clone();
            }
        };
    }

    @Override
    public NodeId getCertificateGroupId() {
        return GROUP_ID;
    }

    @Override
    public List<NodeId> getSupportedCertificateTypeIds() {
        return List.of(GROUP_ID);
    }

    @Override
    public TrustListManager getTrustListManager() {
        return trustListManager;
    }

    @Override
    public List<Entry> getCertificateEntries() {
        return List.of(new Entry(GROUP_ID, GROUP_ID, chain));
    }

    @Override
    public Optional<KeyPair> getKeyPair(NodeId certificateTypeId) {
        return GROUP_ID.equals(certificateTypeId) ? Optional.of(keyPair) : Optional.empty();
    }

    @Override
    public Optional<X509Certificate[]> getCertificateChain(NodeId certificateTypeId) {
        return GROUP_ID.equals(certificateTypeId) ? Optional.of(chain.clone()) : Optional.empty();
    }

    @Override
    public void updateCertificate(NodeId certificateTypeId, KeyPair newKeyPair,
            X509Certificate[] newChain) {
        // 测试用证书组是只读的：运行期换证书不是本测试要验证的能力
        throw new UnsupportedOperationException("test certificate group is immutable");
    }

    @Override
    public CertificateFactory getCertificateFactory() {
        return certificateFactory;
    }

    @Override
    public CertificateValidator getCertificateValidator() {
        return certificateValidator;
    }
}
