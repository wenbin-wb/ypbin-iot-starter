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
package cn.ypbin.iot.protocol.tcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.TlsOptions;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.protocol.tcp.autoconfigure.TcpProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import cn.ypbin.iot.transport.FramingMode;
import cn.ypbin.iot.transport.FramingSpec;
import cn.ypbin.iot.transport.NettyChannelConnection;
import cn.ypbin.iot.transport.NettyTransport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TCP 透传的写链路与订阅投递测试（TCK 之外的行为）。
 *
 * <p>TCK 覆盖 SPI 一致性；本类覆盖 TCP 特有的语义：写字节到回显服务后能通过订阅收到、
 * 不支持的载荷类型产生逐项失败、能力声明正确。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class TcpSessionBehaviourTest {

    private static final ProtocolCode CODE = ProtocolCode.of("tcp");

    private EchoServer server;

    private NettyTransport transport;

    private TcpAdapter adapter;

    /** payload-format 用例专用的适配器（用例内创建，tearDown 一并关闭）。 */
    private final List<TcpAdapter> formatAdapters = new ArrayList<>();

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private RecordingEgress egress;

    @BeforeEach
    void setUp() throws IOException {
        server = new EchoServer();
        server.start();
        transport = new NettyTransport(1, FramingSpec.none());
        adapter = new TcpAdapter(transport, FramingSpec.none(), Duration.ZERO);
        scheduler = new DefaultTaskScheduler(2, 64);
        egress = new RecordingEgress();
        context = new DefaultAdapterContext(CODE, DefaultAdapterSettings.defaults(), egress, scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 16);
    }

    @AfterEach
    void tearDown() {
        formatAdapters.forEach(TcpAdapter::close);
        adapter.close();
        transport.close();
        scheduler.close();
        server.stop();
    }

    @Test
    @DisplayName("TCP-01 能力声明必须只有 WRITE 与 SUBSCRIBE_STREAM")
    void capabilitiesMustBeNarrow() {
        assertThat(adapter.capabilities())
                .containsExactlyInAnyOrder(ProtocolCapability.WRITE, ProtocolCapability.SUBSCRIBE_STREAM);
        assertThat(adapter.capabilities()).doesNotContain(ProtocolCapability.READ);
        assertThat(adapter.descriptor().code()).isEqualTo(CODE);
        assertThat(adapter.descriptor().transport()).isEqualTo("TCP");
    }

    @Test
    @DisplayName("TCP-02 写入的字节必须经回显服务返回并被订阅收到")
    void writtenBytesMustComeBackThroughSubscription() throws Exception {
        TcpSession session = openSession();
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            DataListener listener = received::add;
            SubscriptionHandle handle = session
                    .subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), listener)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            assertThat(handle.active()).isTrue();

            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("out"), "PING")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            assertThat(result.allSuccess()).isTrue();

            awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(3));
            assertThat(received).isNotEmpty();
            assertThat(new String((byte[]) received.get(0).value(), StandardCharsets.UTF_8))
                    .isEqualTo("PING");
            assertThat(handle.deliveredCount()).isPositive();
            assertThat(received.get(0).address()).isEqualTo(PointAddress.of("stream"));
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCP-03 不支持的载荷类型必须逐项失败，不影响其余写项")
    void unsupportedPayloadMustFailPerItem() {
        TcpSession session = openSession();
        try {
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("a"), 42),
                            new PointWrite(PointAddress.of("b"), "hex:0102")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            assertThat(result.allSuccess()).as("含非法载荷时整体不得报告成功").isFalse();
            assertThat(result.failures()).hasSize(1);
            assertThat(result.failures().get(0).address()).isEqualTo(PointAddress.of("a"));
            assertThat(result.failures().get(0).reason())
                    .isEqualTo(TcpPayloadCodec.MSG_PAYLOAD_UNSUPPORTED);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCP-04 订阅数据默认经 egress 出口（无 listener 时）")
    void subscriptionWithoutListenerMustUseEgress() throws Exception {
        TcpSession session = openSession();
        try {
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), null)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "DATA")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> egress.pointCount() > 0, Duration.ofSeconds(3));
            assertThat(egress.pointCount()).isPositive();
            assertThat(egress.batches()).isNotEmpty();
            assertThat(egress.batches().get(0).deviceId()).isNotBlank();
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCP-05 会话关闭后订阅必须失效且再次关闭幂等")
    void sessionCloseMustInvalidateSubscriptions() {
        TcpSession session = openSession();
        SubscriptionHandle handle = session.subscribe(
                        SubscribeRequest.of(List.of(PointAddress.of("stream"))), null)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
        session.close().toCompletableFuture().join();
        assertThat(handle.active()).isFalse();
        session.close().toCompletableFuture().join();
        assertThat(session.state().isUsable()).isFalse();
    }

    @Test
    @DisplayName("TCP-06 未绑定会话的链路访问必须抛 UnsupportedCapabilityException")
    void unboundConnectionMustFailLoudly() {
        NettyChannelConnection connection = transport.connect(connectionSpec(), Duration.ZERO)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
        try {
            assertThatThrownBy(connection::session)
                    .as("未 bind 就取会话必须按 SPI 契约抛 UnsupportedCapabilityException，而不是返回 null")
                    .isInstanceOf(UnsupportedCapabilityException.class);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("TCP-07 TLS 未实现时必须 fail-fast，不得静默明文建链")
    void tlsMustFailFastInsteadOfSilentPlaintext() {
        ConnectionSpec tlsSpec = new ConnectionSpec("tcp-tls", CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()),
                Duration.ofSeconds(3), Duration.ofSeconds(3), TlsOptions.enabledDefault(), null, Map.of());
        Throwable error = transport.connect(tlsSpec, Duration.ZERO)
                .toCompletableFuture()
                .handle((connection, ex) -> ex)
                .orTimeout(3, TimeUnit.SECONDS)
                .join();
        assertThat(error)
                .as("配置了 TLS 却按明文连接是安全静默降级，必须显式失败")
                .isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("TCP-07 配置默认值与帧定界规格构造")
    void propertiesMustProduceFramingSpec() {
        TcpProperties defaults = new TcpProperties(null, null, null, null, null, null, null, null, null, null, null);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.framingMode()).isEqualTo(FramingMode.NONE);
        assertThat(defaults.maxFrameLength()).isEqualTo(FramingSpec.DEFAULT_MAX_FRAME_LENGTH);
        assertThat(defaults.toFramingSpec().mode()).isEqualTo(FramingMode.NONE);
        assertThat(defaults.payloadFormat())
                .as("payload-format 默认 BINARY（向后兼容 0.1.0）")
                .isEqualTo(TcpPayloadFormat.BINARY);

        TcpProperties lengthField = new TcpProperties(true, FramingMode.LENGTH_FIELD, 4096, 0, 2, -4, 2,
                null, Duration.ofSeconds(10), 2, null);
        FramingSpec lengthSpec = lengthField.toFramingSpec();
        assertThat(lengthSpec.mode()).isEqualTo(FramingMode.LENGTH_FIELD);
        assertThat(lengthSpec.lengthFieldLength()).isEqualTo(2);
        assertThat(lengthSpec.initialBytesToStrip()).isEqualTo(2);
        assertThat(lengthSpec.maxFrameLength()).isEqualTo(4096);

        TcpProperties delimiter = new TcpProperties(true, FramingMode.DELIMITER, 1024, 0, 0, 0, 0, "\r\n",
                Duration.ZERO, 1, TcpPayloadFormat.TEXT);
        FramingSpec delimiterSpec = delimiter.toFramingSpec();
        assertThat(delimiterSpec.mode()).isEqualTo(FramingMode.DELIMITER);
        assertThat(delimiterSpec.delimiter()).containsExactly(13, 10);
        assertThat(delimiter.payloadFormat()).isEqualTo(TcpPayloadFormat.TEXT);
    }

    @Test
    @DisplayName("TCP-08 UP-3：payload-format=text 交付严格 UTF-8 字符串；非法字节帧产出 BAD（不是 U+FFFD 脏串）")
    void payloadFormatTextDeliversStrictUtf8OrBad() throws Exception {
        TcpSession session = openSession(TcpPayloadFormat.TEXT);
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), received::add)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "室温 23.5")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> received.size() >= 1, Duration.ofSeconds(3));

            PointValue good = received.get(0);
            assertThat(good.quality()).isEqualTo(Quality.GOOD);
            assertThat(good.value()).isEqualTo("室温 23.5");

            // 非 UTF-8 帧：0xFF 0xFE 是非法 UTF-8 序列 ⇒ BAD，绝不出现 U+FFFD（变异点：换成 new String(bytes, UTF_8) 必红）
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), new byte[]{(byte) 0xFF, (byte) 0xFE})))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> received.size() >= 2, Duration.ofSeconds(3));

            PointValue bad = received.get(1);
            assertThat(bad.quality()).isEqualTo(Quality.BAD);
            assertThat(bad.qualityReason()).isEqualTo(TcpAdapter.MSG_PAYLOAD_NOT_UTF8);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCP-09 UP-3：payload-format=number 解析数值；非数值产出 BAD")
    void payloadFormatNumberDeliversDoubleOrBad() throws Exception {
        TcpSession session = openSession(TcpPayloadFormat.NUMBER);
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), received::add)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "23.5")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> received.size() >= 1, Duration.ofSeconds(3));
            PointValue good = received.get(0);
            assertThat(good.quality()).isEqualTo(Quality.GOOD);
            assertThat(good.value()).isEqualTo(23.5d);

            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "not-a-number")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> received.size() >= 2, Duration.ofSeconds(3));
            PointValue bad = received.get(1);
            assertThat(bad.quality()).isEqualTo(Quality.BAD);
            assertThat(bad.qualityReason()).isEqualTo(TcpAdapter.MSG_PAYLOAD_NOT_NUMBER);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCP-10 UP-3：payload-format=binary（默认）原样交付 byte[]，与 0.1.0 逐字一致")
    void payloadFormatBinaryDeliversRawBytesByDefault() throws Exception {
        // 默认构造（3 参数）即 binary，验证向后兼容
        TcpSession session = openSession();
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), received::add)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            byte[] frame = new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF};
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), frame)))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> received.size() >= 1, Duration.ofSeconds(3));

            PointValue value = received.get(0);
            assertThat(value.quality()).isEqualTo(Quality.GOOD);
            assertThat((byte[]) value.value()).containsExactly((byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCP-11 UP-4：订阅多个地址 fail-fast（TCP 是单点位透传流，绝不静默只交付第 0 个）")
    void subscribeWithMultipleAddressesMustFailFast() throws Exception {
        TcpSession session = openSession();
        try {
            CompletionStage<SubscriptionHandle> stage = session.subscribe(
                    SubscribeRequest.of(List.of(PointAddress.of("a"), PointAddress.of("b"))),
                    ignored -> { });
            assertThatThrownBy(() -> stage.toCompletableFuture().get(3, TimeUnit.SECONDS))
                    .as("多地址订阅必须显式失败，而不是静默只交付第 0 个点位")
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(TcpAdapter.MSG_MULTI_ADDRESS_UNSUPPORTED);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    private TcpSession openSession() {
        return openSession(null);
    }

    /** 以指定 payload-format 打开会话（null = 适配器默认 binary）。 */
    private TcpSession openSession(TcpPayloadFormat format) {
        TcpAdapter effective = adapter;
        if (format != null) {
            effective = new TcpAdapter(transport, FramingSpec.none(), Duration.ZERO, format);
            formatAdapters.add(effective);
        }
        NettyChannelConnection connection = transport.connect(connectionSpec(), Duration.ZERO)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
        DeviceSpec device = new DeviceSpec("tcp-device", "TCP 设备", CODE, "tcp-conn", "",
                Duration.ZERO, Map.of());
        return (TcpSession) effective.bind(connection, device, context)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
    }

    private ConnectionSpec connectionSpec() {
        return new ConnectionSpec("tcp-conn", CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()),
                Duration.ofSeconds(3), Duration.ofSeconds(3), null, null, Map.of());
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * 记录型出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class RecordingEgress implements DataEgress {

        private final List<DataBatch> batches = new CopyOnWriteArrayList<>();

        @Override
        public void emit(DataBatch batch) {
            batches.add(batch);
        }

        @Override
        public void emit(DeviceEvent event) {
            // 本测试不关心事件
        }

        private int pointCount() {
            return batches.stream().mapToInt(DataBatch::size).sum();
        }

        private List<DataBatch> batches() {
            return new ArrayList<>(batches);
        }
    }

    /**
     * 回显服务（与被测链路不同源，避免同源同错）。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class EchoServer {

        private final AtomicBoolean running = new AtomicBoolean(true);

        private ServerSocket serverSocket;

        private void start() throws IOException {
            serverSocket = new ServerSocket(0);
            Thread.ofPlatform().daemon(true).name("tcp-behaviour-echo").start(this::acceptLoop);
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> echo(socket));
                } catch (IOException ex) {
                    if (running.get()) {
                        throw new IllegalStateException("echo accept failed", ex);
                    }
                    return;
                }
            }
        }

        private void echo(Socket socket) {
            try (socket; InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream()) {
                byte[] buffer = new byte[512];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ex) {
                // 客户端关闭连接属正常路径
            }
        }

        private int port() {
            return serverSocket.getLocalPort();
        }

        private void stop() {
            running.set(false);
            try {
                serverSocket.close();
            } catch (IOException ex) {
                throw new IllegalStateException("failed to stop echo server", ex);
            }
        }
    }
}
