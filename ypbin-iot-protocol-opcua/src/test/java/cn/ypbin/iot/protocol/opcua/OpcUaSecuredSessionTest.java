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
import cn.ypbin.iot.core.context.CredentialResolver;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.protocol.opcua.autoconfigure.OpcUaProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.eclipse.milo.opcua.stack.core.util.SelfSignedCertificateGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * OPC UA <b>签名加密会话</b>端到端测试。
 *
 * <p>这是本仓第一条真正跑在 {@code Basic256Sha256 + SignAndEncrypt} 上的用例：
 * 之前只证明了「安全材料装配正确」「校验器会拒绝未受信证书」，但**从未与一台
 * 加密服务端完成握手**。本用例补齐这一环——服务端提供自签证书与信任列表，
 * 客户端用 {@code OpcUaSecurity} 的材料完成签名加密会话并成功 read。</p>
 *
 * <p>证书全部由 Milo 的 {@link SelfSignedCertificateGenerator} 程序化生成：
 * 它生成的扩展（KeyUsage 含 nonRepudiation/keyCertSign、EKU 含 serverAuth/clientAuth、
 * SAN 含 applicationUri）恰好满足 OPC UA 对应用实例证书的要求。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class OpcUaSecuredSessionTest {

    private static final String STORE_PASSWORD = "changeit";

    private static final String POLICY = "http://opcfoundation.org/UA/SecurityPolicy#Basic256Sha256";

    private static final int RSA_KEY_SIZE = 2048;

    private static final String SIGNATURE_ALGORITHM = "SHA256withRSA";

    @TempDir
    Path tempDir;

    private OpcUaTestServer server;

    private OpcUaAdapter adapter;

    private DefaultTaskScheduler scheduler;

    @BeforeEach
    void setUp() throws Exception {
        KeyPair clientKeyPair = SelfSignedCertificateGenerator.generateRsaKeyPair(RSA_KEY_SIZE);
        X509Certificate clientCertificate = generateClientCertificate(clientKeyPair);
        Path keyStore = writeKeyStore(clientKeyPair, clientCertificate);

        server = new OpcUaTestServer(clientCertificate);

        // 客户端信任目录：放入服务端证书
        Path trustDir = Files.createDirectories(tempDir.resolve("trusted"));
        Files.copy(server.serverCertificateFile(), trustDir.resolve("server.der"));

        OpcUaProperties properties = new OpcUaProperties(true, POLICY, "SignAndEncrypt", null, null,
                null, null, null, null, "opcua-ref", keyStore.toString(), trustDir.toString(), null);
        scheduler = new DefaultTaskScheduler(2, 64);
        adapter = new OpcUaAdapter(properties);
    }

    @AfterEach
    void tearDown() {
        if (adapter != null) {
            adapter.close();
        }
        if (server != null) {
            server.close();
        }
        if (scheduler != null) {
            scheduler.close();
        }
    }

    @Test
    @DisplayName("SEC-E2E-01 必须在 Basic256Sha256/SignAndEncrypt 上完成签名加密会话并成功读取")
    void securedSessionMustWorkEndToEnd() {
        ConnectionSpec spec = new ConnectionSpec("secured", OpcUaAdapter.PROTOCOL_CODE,
                Endpoint.of(server.endpointUrl()), Duration.ofSeconds(20), Duration.ofSeconds(20),
                null, "opcua-ref", Map.of());

        ProtocolConnection connection = adapter.open(spec, context()).toCompletableFuture()
                .orTimeout(60, TimeUnit.SECONDS).join();
        try {
            assertThat(connection.state())
                    .as("加密握手必须真正成功（而不是退回明文或报错）")
                    .isEqualTo(SessionState.ONLINE);

            DeviceSession session = adapter.bind(connection, device(), context())
                    .toCompletableFuture().orTimeout(30, TimeUnit.SECONDS).join();
            ReadResult result = session.read(new ReadRequest(
                    List.of(PointAddress.of(server.nodeAddress("Temperature"))), Duration.ofSeconds(20)))
                    .toCompletableFuture().orTimeout(30, TimeUnit.SECONDS).join();

            assertThat(result.values()).hasSize(1);
            assertThat(result.values().get(0).quality())
                    .as("加密会话上的读取必须成功；若为 BAD 说明会话建立但数据面不可用")
                    .isEqualTo(Quality.GOOD);
        } finally {
            connection.close();
        }
    }

    private AdapterContext context() {
        return new DefaultAdapterContext(OpcUaAdapter.PROTOCOL_CODE, DefaultAdapterSettings.defaults(),
                new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE,
                ref -> Optional.of(new CredentialResolver.Credential("unused",
                        STORE_PASSWORD.toCharArray(), Map.of())),
                Clock.systemUTC(), 16);
    }

    private static DeviceSpec device() {
        return new DeviceSpec("secured-device", "OPC UA 加密设备", OpcUaAdapter.PROTOCOL_CODE, "secured",
                "", Duration.ZERO, Map.of());
    }

    private static X509Certificate generateClientCertificate(KeyPair keyPair) throws Exception {
        long now = System.currentTimeMillis();
        return new SelfSignedCertificateGenerator().generateSelfSigned(keyPair,
                new Date(now - Duration.ofDays(1).toMillis()),
                new Date(now + Duration.ofDays(365).toMillis()),
                "ypbin-iot-test-client", "ypbin-iot", null, null, null, null,
                "urn:ypbin:iot:test-client", List.of("localhost"), List.of("127.0.0.1"),
                SIGNATURE_ALGORITHM);
    }

    private Path writeKeyStore(KeyPair keyPair, X509Certificate certificate) throws Exception {
        Path file = tempDir.resolve("client.p12");
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, null);
        keyStore.setKeyEntry("client", keyPair.getPrivate(), STORE_PASSWORD.toCharArray(),
                new Certificate[] {certificate});
        try (OutputStream out = Files.newOutputStream(file)) {
            keyStore.store(out, STORE_PASSWORD.toCharArray());
        }
        return file;
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
