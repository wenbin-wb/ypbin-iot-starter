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
package cn.ypbin.iot.protocol.mqtt;

import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 嵌入式 MQTT broker（测试用）。
 *
 * <p>MQTT 必须有 broker 才能做端到端验证；用嵌入式 broker 而不是 Testcontainers，
 * 是为了让测试在没有 Docker 的机器上也能跑（本仓 CI 不保证有 Docker）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class MqttTestBroker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(MqttTestBroker.class);

    private final Server broker = new Server();

    private final int port;

    MqttTestBroker() throws IOException {
        this.port = freePort();
        Properties properties = new Properties();
        properties.setProperty(BrokerConstants.HOST_PROPERTY_NAME, "127.0.0.1");
        properties.setProperty(BrokerConstants.PORT_PROPERTY_NAME, String.valueOf(port));
        properties.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");
        properties.setProperty(BrokerConstants.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
        // 关闭 WebSocket 端口，避免与宿主环境端口冲突
        properties.setProperty(BrokerConstants.WEB_SOCKET_PORT_PROPERTY_NAME, "disabled");
        broker.startServer(new MemoryConfig(properties));
        log.debug("[test] embedded mqtt broker listening on {}", port);
    }

    int port() {
        return port;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() {
        broker.stopServer();
    }
}
