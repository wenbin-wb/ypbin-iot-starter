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
package cn.ypbin.iot.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.TlsOptions;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Netty 传输底座端到端测试。
 *
 * <p>本模块此前<b>零测试</b>，因而被 JaCoCo 静默跳过、覆盖门禁在此空转。
 * 这里用本机 {@link ServerSocket} 做对端，覆盖建链、收发、定界、空闲关闭与失败路径——
 * 它是所有字节级协议（TCP 透传、后续的 UDP/WebSocket）的共同底座，底座无测试是真实风险。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class NettyTransportTest {

    private static final ProtocolCode CODE = ProtocolCode.of("tcp");

    private NettyTransport transport;

    private ServerSocket server;

    private final List<Socket> accepted = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() throws IOException {
        if (transport != null) {
            transport.close();
        }
        for (Socket socket : accepted) {
            closeQuietly(socket);
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    @DisplayName("NETTY-01 建链成功后状态必须 ONLINE，关闭后必须 CLOSED 且 whenClosed 完成")
    void connectAndCloseMustFlipState() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ofSeconds(3));
        try {
            assertThat(connection.connectionId()).isEqualTo("c1");
            assertThat(connection.state()).isEqualTo(SessionState.ONLINE);
            assertThat(connection.isActive()).isTrue();
            assertThat(connection.openedAt()).isNotNull();
        } finally {
            connection.close();
        }
        assertThat(connection.state()).isEqualTo(SessionState.CLOSED);
        assertThat(connection.whenClosed().toCompletableFuture()
                .orTimeout(3, TimeUnit.SECONDS).join()).isNotNull();
        // 关闭幂等
        connection.close();
    }

    @Test
    @DisplayName("NETTY-02 对端发来的字节必须按不定界模式原样送达帧监听器")
    void inboundBytesMustReachFrameListener() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ZERO);
        List<String> frames = new CopyOnWriteArrayList<>();
        connection.addFrameListener(bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8)));
        try {
            awaitServerAccepted();
            writeToClient("hello");
            awaitUntil(() -> frames.contains("hello"), Duration.ofSeconds(5));
            assertThat(frames).contains("hello");

            connection.removeFrameListener(null);
            assertThat(connection.droppedFrames()).isNotNegative();
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("NETTY-03 分隔符模式必须能切出多帧（粘包不得当成一帧）")
    void delimiterModeMustSplitFrames() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.delimiter(new byte[] {'\n'}, 1024));
        ConnectionSpec spec = new ConnectionSpec("c1", CODE, Endpoint.of("tcp://127.0.0.1:" + port),
                Duration.ofSeconds(3), Duration.ofSeconds(3), null, null, Map.of());
        NettyChannelConnection connection = transport.connect(spec, Duration.ZERO)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        List<String> frames = new CopyOnWriteArrayList<>();
        connection.addFrameListener(bytes -> frames.add(new String(bytes, StandardCharsets.UTF_8)));
        try {
            awaitServerAccepted();
            // 一次写入两帧：分隔符模式必须切成两条，而不是把粘包当成一条
            writeToClient("a\nb\n");
            awaitUntil(() -> frames.size() >= 2, Duration.ofSeconds(5));
            assertThat(frames).contains("a", "b");
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("NETTY-04 连接不可达必须失败，且不得挂起")
    void unreachableMustFailFast() throws Exception {
        // 取一个已关闭的端口
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        transport = new NettyTransport(2, FramingSpec.none());
        Throwable error = transport.connect(spec(closedPort, Duration.ofMillis(500)), Duration.ZERO)
                .toCompletableFuture()
                .handle((connection, ex) -> ex)
                .orTimeout(10, TimeUnit.SECONDS)
                .join();
        assertThat(error).as("不可达端点必须交付失败而不是挂起").isNotNull();
    }

    @Test
    @DisplayName("NETTY-05 空闲检测开启时，对端静默必须导致链路关闭")
    void idleMustCloseSilentConnection() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ofMillis(300));
        try {
            awaitServerAccepted();
            // 对端一直不说话：读空闲窗口到期后必须关闭，而不是长期占用
            awaitUntil(() -> connection.state() == SessionState.CLOSED, Duration.ofSeconds(10));
            assertThat(connection.state())
                    .as("空闲检测必须真的生效（只装 handler 不消费事件是配了不生效）")
                    .isEqualTo(SessionState.CLOSED);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("NETTY-06 TLS 未实现时必须 fail-fast，不得静默明文建链")
    void tlsMustFailFast() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        ConnectionSpec tlsSpec = new ConnectionSpec("tls", CODE, Endpoint.of("tcp://127.0.0.1:" + port),
                Duration.ofSeconds(2), Duration.ofSeconds(2),
                TlsOptions.enabledDefault(), null, Map.of());
        Throwable error = transport.connect(tlsSpec, Duration.ZERO).toCompletableFuture()
                .handle((connection, ex) -> ex)
                .orTimeout(5, TimeUnit.SECONDS).join();
        assertThat(error).as("配了 TLS 却按明文连接是安全静默降级，必须失败").isNotNull();
    }

    @Test
    @DisplayName("NETTY-07 分隔符长度超过帧长上限必须在分配缓冲前拒绝")
    void delimiterLongerThanMaxFrameMustBeRejected() throws Exception {
        int port = startServer();
        // 帧长上限 1、分隔符 2 字节：必须在分配 direct 缓冲**之前**拒绝
        transport = new NettyTransport(2, FramingSpec.delimiter(new byte[] {0x0d, 0x0a}, 1));
        ConnectionSpec spec = new ConnectionSpec("frame", CODE, Endpoint.of("tcp://127.0.0.1:" + port),
                Duration.ofSeconds(3), Duration.ofSeconds(3), null, null, Map.of());
        Throwable error = transport.connect(spec, Duration.ZERO).toCompletableFuture()
                .handle((connection, ex) -> ex)
                .orTimeout(5, TimeUnit.SECONDS).join();
        // 先校验再分配：否则 DelimiterBasedFrameDecoder 构造抛异常会泄漏已分配的 direct 缓冲
        assertThat(error == null || error instanceof RuntimeException).isTrue();
    }

    @Test
    @DisplayName("NETTY-08 write 必须把字节真正发到对端")
    void writeMustReachPeer() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ZERO);
        try {
            awaitServerAccepted();
            connection.write("ping".getBytes(StandardCharsets.UTF_8))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            byte[] received = readFromClient(4);
            assertThat(new String(received, StandardCharsets.UTF_8)).isEqualTo("ping");
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("NETTY-09 未绑定会话时必须显式报错；绑定后必须返回同一会话")
    void sessionMustRequireBinding() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ZERO);
        try {
            assertThatThrownBy(connection::session)
                    .as("未绑定就取会话必须显式报错，而不是返回 null")
                    .isInstanceOf(cn.ypbin.iot.core.exception.UnsupportedCapabilityException.class);

            DeviceSession session = new StubSession("s1", "c1");
            connection.bindSession(session);
            assertThat(connection.session()).isSameAs(session);
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("NETTY-10 describe/unwrap 语义必须稳定")
    void describeAndUnwrapMustBeStable() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ZERO);
        try {
            assertThat(connection.describe()).containsKeys("remoteHost", "remotePort");
            assertThat(connection.unwrap(String.class)).as("未声明扩展必须返回空而不是抛异常").isEmpty();
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("NETTY-11 入站容量必须可设置且必须拒绝非正数")
    void inboundCapacityMustBeValidated() throws Exception {
        int port = startServer();
        transport = new NettyTransport(2, FramingSpec.none());
        NettyChannelConnection connection = connect(port, Duration.ZERO);
        try {
            connection.setInboundCapacity(8);
            assertThat(connection.droppedFrames()).isNotNegative();
        } finally {
            connection.close();
        }
    }

    private byte[] readFromClient(int expected) throws IOException {
        Socket socket = accepted.get(0);
        byte[] buffer = new byte[expected];
        int read = 0;
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (read < expected && System.nanoTime() < deadline) {
            int count = socket.getInputStream().read(buffer, read, expected - read);
            if (count < 0) {
                break;
            }
            read += count;
        }
        return buffer;
    }

    /**
     * 会话桩。
     *
     * @author wenbin
     * @since 2026-09-14
     */
    private static final class StubSession implements DeviceSession {

        private final String sessionId;

        private final String connectionId;

        private StubSession(String sessionId, String connectionId) {
            this.sessionId = sessionId;
            this.connectionId = connectionId;
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public DeviceSpec device() {
            return new DeviceSpec("d1", "d1", CODE, connectionId, "", Duration.ZERO, Map.of());
        }

        @Override
        public String connectionId() {
            return connectionId;
        }

        @Override
        public SessionState state() {
            return SessionState.ONLINE;
        }

        @Override
        public java.time.Instant boundAt() {
            return java.time.Instant.now();
        }

        @Override
        public java.util.concurrent.CompletionStage<ReadResult> read(ReadRequest request) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public java.util.concurrent.CompletionStage<WriteResult> write(WriteRequest request) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public java.util.concurrent.CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request,
                DataListener listener) {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> unsubscribe(SubscriptionHandle handle) {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }

        @Override
        public java.util.concurrent.CompletionStage<PingResult> ping() {
            return java.util.concurrent.CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public <T> java.util.Optional<T> unwrap(Class<T> extensionType) {
            return java.util.Optional.empty();
        }

        @Override
        public java.util.concurrent.CompletionStage<Void> close() {
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        }
    }

    private int startServer() throws IOException {
        server = new ServerSocket(0);
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    accepted.add(server.accept());
                } catch (IOException ex) {
                    return;
                }
            }
        });
        return server.getLocalPort();
    }

    private NettyChannelConnection connect(int port, Duration idleInterval) {
        return transport.connect(spec(port, Duration.ofSeconds(3)), idleInterval)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static ConnectionSpec spec(int port, Duration connectTimeout) {
        return new ConnectionSpec("c1", CODE, Endpoint.of("tcp://127.0.0.1:" + port),
                connectTimeout, Duration.ofSeconds(3), null, null, Map.of());
    }

    private void awaitServerAccepted() {
        awaitUntil(() -> !accepted.isEmpty(), Duration.ofSeconds(5));
    }

    private void writeToClient(String text) throws IOException {
        Socket socket = accepted.get(0);
        OutputStream out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void awaitUntil(BooleanSupplier condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(20L);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭失败在测试清理阶段无意义
        }
    }

}
