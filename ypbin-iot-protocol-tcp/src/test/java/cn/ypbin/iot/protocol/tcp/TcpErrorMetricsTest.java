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
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import cn.ypbin.iot.spring.metrics.MicrometerMetricsRecorder;
import cn.ypbin.iot.transport.FramingSpec;
import cn.ypbin.iot.transport.NettyChannelConnection;
import cn.ypbin.iot.transport.NettyTransport;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 协议层错误指标的<b>契约测试</b>：真实触发一次失败，断言
 * {@code ypbin.iot.errors{type=<错误码>}} 计数 +1。
 *
 * <p>这是 issue #27 的回归防线：此前 {@code MetricsRecorder#recordError} 在生产代码零调用，
 * 于是文档承诺的 {@code iot.tcp.*} 指标在宿主的 {@code /actuator/metrics} 里永远 404。
 * 只断言「值变成 BAD」会漏掉本缺陷——BAD 一直都有，缺的是计数。</p>
 *
 * @author wenbin
 * @since 2026-10-14
 */
class TcpErrorMetricsTest {

    private static final ProtocolCode CODE = ProtocolCode.of("tcp");

    private EchoServer server;

    private NettyTransport transport;

    private TcpAdapter adapter;

    /** payload-format 用例内创建的适配器，tearDown 一并关闭。 */
    private final List<TcpAdapter> formatAdapters = new ArrayList<>();

    private DefaultTaskScheduler scheduler;

    private SimpleMeterRegistry registry;

    private AdapterContext context;

    @BeforeEach
    void setUp() throws IOException {
        server = new EchoServer();
        server.start();
        transport = new NettyTransport(1, FramingSpec.none());
        adapter = new TcpAdapter(transport, FramingSpec.none(), Duration.ZERO);
        scheduler = new DefaultTaskScheduler(2, 64);
        registry = new SimpleMeterRegistry();
        context = new DefaultAdapterContext(CODE, DefaultAdapterSettings.defaults(), new NoopEgress(),
                scheduler, new MicrometerMetricsRecorder(registry), new EnvCredentialResolver(),
                Clock.systemUTC(), 16);
    }

    @AfterEach
    void tearDown() {
        formatAdapters.forEach(TcpAdapter::close);
        adapter.close();
        transport.close();
        scheduler.close();
        registry.close();
        server.stop();
    }

    @Test
    @DisplayName("ERR-TCP-01 selector 未命中：BAD 之外必须记 iot.tcp.selector.not-found")
    void selectorNotFoundMustBeCounted() throws Exception {
        TcpSession session = openSession(TcpPayloadFormat.KEY_VALUE);
        try {
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("TEMP"), PointAddress.of("MISSING"))),
                            ignored -> { })
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "TEMP=23.5")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            awaitError(TcpAdapter.MSG_SELECTOR_NOT_FOUND);
            assertThat(errorCount(TcpAdapter.MSG_SELECTOR_NOT_FOUND))
                    .as("selector 未命中必须计入 ypbin.iot.errors{type=iot.tcp.selector.not-found}")
                    .isEqualTo(1.0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("ERR-TCP-02 非键值帧：必须记 iot.tcp.payload.not-key-value")
    void nonKeyValueFrameMustBeCounted() throws Exception {
        TcpSession session = openSession(TcpPayloadFormat.KEY_VALUE);
        try {
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("TEMP"))), ignored -> { })
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "no-equals-sign-here")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            awaitError(TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE);
            assertThat(errorCount(TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE)).isEqualTo(1.0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("ERR-TCP-03 非 UTF-8 帧：必须记 iot.tcp.payload.not-utf8")
    void invalidUtf8FrameMustBeCounted() throws Exception {
        TcpSession session = openSession(TcpPayloadFormat.TEXT);
        try {
            session.subscribe(SubscribeRequest.of(List.of(PointAddress.of("stream"))), ignored -> { })
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"),
                            new byte[]{(byte) 0xFF, (byte) 0xFE})))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();

            awaitError(TcpAdapter.MSG_PAYLOAD_NOT_UTF8);
            assertThat(errorCount(TcpAdapter.MSG_PAYLOAD_NOT_UTF8)).isEqualTo(1.0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("ERR-TCP-04 十六进制非法：写编码失败必须记 iot.tcp.payload.hex-invalid")
    void invalidHexWriteMustBeCounted() throws Exception {
        TcpSession session = openSession(null);
        try {
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), "hex:0G")))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            assertThat(errorCount(TcpPayloadCodec.MSG_HEX_INVALID)).isEqualTo(1.0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("ERR-TCP-05 不支持的值类型：写编码失败必须记 iot.tcp.payload.unsupported")
    void unsupportedPayloadWriteMustBeCounted() throws Exception {
        TcpSession session = openSession(null);
        try {
            session.write(WriteRequest.of(new PointWrite(PointAddress.of("out"), 42)))
                    .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
            assertThat(errorCount(TcpPayloadCodec.MSG_PAYLOAD_UNSUPPORTED)).isEqualTo(1.0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("ERR-TCP-06 非 key-value 多地址订阅：必须记 iot.tcp.subscribe.multi-address-unsupported")
    void multiAddressSubscribeMustBeCounted() throws Exception {
        TcpSession session = openSession(null);
        try {
            assertThatThrownBy(() -> session.subscribe(SubscribeRequest.of(
                            List.of(PointAddress.of("a"), PointAddress.of("b"))), ignored -> { })
                    .toCompletableFuture().get(3, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class);
            assertThat(errorCount(TcpAdapter.MSG_MULTI_ADDRESS_UNSUPPORTED)).isEqualTo(1.0);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    private double errorCount(String type) {
        var counter = registry.find("ypbin.iot.errors").tag("type", type).counter();
        return counter == null ? 0.0 : counter.count();
    }

    private void awaitError(String type) {
        awaitUntil(() -> errorCount(type) >= 1.0, Duration.ofSeconds(3));
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

    private TcpSession openSession(TcpPayloadFormat format) {
        TcpAdapter effective = adapter;
        if (format != null) {
            effective = new TcpAdapter(transport, FramingSpec.none(), Duration.ZERO, format);
            formatAdapters.add(effective);
        }
        NettyChannelConnection connection = transport.connect(connectionSpec(), Duration.ZERO)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
        DeviceSpec device = new DeviceSpec("tcp-metrics-device", "TCP metrics", CODE, "tcp-metrics-conn", "",
                Duration.ZERO, Map.of());
        return (TcpSession) effective.bind(connection, device, context)
                .toCompletableFuture().orTimeout(3, TimeUnit.SECONDS).join();
    }

    private ConnectionSpec connectionSpec() {
        return new ConnectionSpec("tcp-metrics-conn", CODE,
                Endpoint.of("tcp://127.0.0.1:" + server.port()),
                Duration.ofSeconds(3), Duration.ofSeconds(3), null, null, Map.of());
    }

    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 本测试只关心指标
        }

        @Override
        public void emit(DeviceEvent event) {
            // 本测试只关心指标
        }
    }

    /** 回显服务：订阅失败路径需要真实帧回流。 */
    private static final class EchoServer {

        private final AtomicBoolean running = new AtomicBoolean(true);

        private ServerSocket serverSocket;

        private void start() throws IOException {
            serverSocket = new ServerSocket(0);
            Thread.ofPlatform().daemon(true).name("tcp-error-metrics-echo").start(this::acceptLoop);
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
                // 关闭失败不影响测试结论
            }
        }
    }
}
