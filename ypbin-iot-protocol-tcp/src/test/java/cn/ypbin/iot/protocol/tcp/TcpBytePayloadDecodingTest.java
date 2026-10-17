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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.DecodeContext;
import cn.ypbin.iot.core.spi.DecodeOutcome;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.registry.DefaultBytePayloadDecoderRegistry;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TCP {@code payload-format=binary} 与宿主解码 SPI 的接线测试（issue #30）。
 *
 * <p>TCP 透传是最典型的「原始字节」交付路径：未注册解码器时必须逐字保持旧行为
 * （{@code byte[]} 直接交付），注册后必须按点位认领并解码。这里用真实回显服务端跑完整链路，
 * 证明 SPI 真的接在 {@code TcpSession#decodeAll} 上，而不是只存在于 core 的路由函数里。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class TcpBytePayloadDecodingTest {

    private static final ProtocolCode CODE = ProtocolCode.of("tcp");

    private EchoServer server;

    private NettyTransport transport;

    private TcpAdapter adapter;

    private DefaultTaskScheduler scheduler;

    @BeforeEach
    void setUp() throws IOException {
        server = new EchoServer();
        server.start();
        transport = new NettyTransport(1, FramingSpec.none());
        adapter = new TcpAdapter(transport, FramingSpec.none(), Duration.ZERO);
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        transport.close();
        if (scheduler != null) {
            scheduler.close();
        }
        server.stop();
    }

    @Test
    @DisplayName("TBPD-01 未注册解码器：binary 帧原样交付 byte[]（向后兼容锚点）")
    void binaryFrameMustStayUntouchedWithoutDecoder() throws Exception {
        scheduler = new DefaultTaskScheduler(2, 64);
        AdapterContext context = contextWith(BytePayloadDecoderRegistry.empty());
        TcpSession session = openSession(context);
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), received::add)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            byte[] frame = {(byte) 0xDE, (byte) 0xAD};
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), frame)))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(3));

            assertThat(received.get(0).quality()).isEqualTo(Quality.GOOD);
            assertThat((byte[]) received.get(0).value()).containsExactly(frame);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TBPD-02 注册解码器：binary 帧被解码为文本（SPI 真的接进了 TCP 路径）")
    void registeredDecoderMustDecodeBinaryFrame() throws Exception {
        scheduler = new DefaultTaskScheduler(2, 64);
        AdapterContext context = contextWith(new DefaultBytePayloadDecoderRegistry(
                List.of(new Utf8Decoder())));
        TcpSession session = openSession(context);
        try {
            List<PointValue> received = new CopyOnWriteArrayList<>();
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), received::add)
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"),
                            "TEMP=23.5".getBytes(StandardCharsets.UTF_8))))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            awaitUntil(() -> !received.isEmpty(), Duration.ofSeconds(3));

            assertThat(received.get(0).quality()).isEqualTo(Quality.GOOD);
            assertThat(received.get(0).value()).isEqualTo("TEMP=23.5");
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    private AdapterContext contextWith(BytePayloadDecoderRegistry registry) {
        return new DefaultAdapterContext(CODE, DefaultAdapterSettings.defaults(), new NoopEgress(),
                scheduler, NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(),
                Clock.systemUTC(), 16, registry);
    }

    private TcpSession openSession(AdapterContext context) {
        NettyChannelConnection connection = transport.connect(connectionSpec(), Duration.ZERO)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
        DeviceSpec device = new DeviceSpec("tcp-decode-device", "TCP decode", CODE, "tcp-decode-conn",
                "", Duration.ZERO, Map.of());
        return (TcpSession) adapter.bind(connection, device, context)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
    }

    private ConnectionSpec connectionSpec() {
        return new ConnectionSpec("tcp-decode-conn", CODE,
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
     * 参考解码器：把整帧按严格 UTF-8 解成字符串。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class Utf8Decoder implements BytePayloadDecoder {

        @Override
        public String id() {
            return "test-utf8";
        }

        @Override
        public boolean supports(DecodeContext context) {
            return CODE.value().equals(context.protocol().value());
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return DecodeOutcome.ok(new String(payload, StandardCharsets.UTF_8));
        }
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 本测试走 listener 直接消费
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }

    /**
     * 回显服务（与被测链路不同源）。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    private static final class EchoServer {

        private final AtomicBoolean running = new AtomicBoolean(true);

        private ServerSocket serverSocket;

        private void start() throws IOException {
            serverSocket = new ServerSocket(0);
            Thread.ofPlatform().daemon(true).name("tcp-decode-echo").start(this::acceptLoop);
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
                if (running.get()) {
                    throw new IllegalStateException("echo failed", ex);
                }
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
                throw new IllegalStateException("failed to close echo server", ex);
            }
        }
    }
}
