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

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.test.tck.AbstractProtocolAdapterTckTest;
import cn.ypbin.iot.transport.FramingSpec;
import cn.ypbin.iot.transport.NettyTransport;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * TCP 透传适配器的 TCK 一致性测试。
 *
 * <p>继承 {@link AbstractProtocolAdapterTckTest} 即自动跑完全部一致性用例；
 * 本类只负责起一个回显服务并给出连接规格。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class TcpAdapterTckTest extends AbstractProtocolAdapterTckTest {

    private EchoServer echoServer;

    private NettyTransport transport;

    private TcpAdapter adapter;

    @Override
    protected void beforeTck() {
        try {
            echoServer = new EchoServer();
            echoServer.start();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to start echo server", ex);
        }
        transport = new NettyTransport(1, FramingSpec.none());
        adapter = new TcpAdapter(transport, FramingSpec.none(), Duration.ZERO);
    }

    @Override
    protected void afterTck() {
        adapter.close();
        transport.close();
        echoServer.stop();
    }

    @Override
    protected ProtocolAdapter adapter() {
        return adapter;
    }

    @Override
    protected ConnectionSpec connectionSpec() {
        return new ConnectionSpec("tck-tcp", ProtocolCode.of("tcp"),
                Endpoint.of("tcp://127.0.0.1:" + echoServer.port()), Duration.ofSeconds(3),
                Duration.ofSeconds(3), null, null, Map.of());
    }

    @Override
    protected DeviceSpec deviceSpec() {
        return new DeviceSpec("tck-device", "TCK 设备", ProtocolCode.of("tcp"), "tck-tcp", "",
                Duration.ZERO, Map.of());
    }

    /**
     * 极简回显服务：把收到的字节原样写回。
     *
     * <p>刻意不用 Netty 实现——让它与被测链路不是同一套代码，避免"同源同错"。</p>
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class EchoServer {

        private final AtomicBoolean running = new AtomicBoolean(true);

        private ServerSocket serverSocket;

        private Thread acceptThread;

        private void start() throws IOException {
            serverSocket = new ServerSocket(0);
            acceptThread = Thread.ofPlatform().daemon(true).name("tck-echo").start(this::acceptLoop);
        }

        private void acceptLoop() {
            while (running.get()) {
                try {
                    Socket socket = serverSocket.accept();
                    Thread.ofVirtual().start(() -> echo(socket));
                } catch (IOException ex) {
                    if (running.get()) {
                        throw new IllegalStateException("echo server accept failed", ex);
                    }
                    return;
                }
            }
        }

        private void echo(Socket socket) {
            try (socket; InputStream in = socket.getInputStream();
                    OutputStream out = socket.getOutputStream()) {
                byte[] buffer = new byte[1024];
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
                throw new IllegalStateException("failed to close echo server", ex);
            }
        }
    }
}
