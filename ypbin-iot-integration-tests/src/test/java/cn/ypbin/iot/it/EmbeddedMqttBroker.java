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
package cn.ypbin.iot.it;

import io.moquette.BrokerConstants;
import io.moquette.broker.Server;
import io.moquette.broker.config.MemoryConfig;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 集成测试用的嵌入式 MQTT broker。
 *
 * <p>让 IT 不依赖外部中间件即可在本地与 CI 跑起来。若将来要接外部实例，
 * 可在本类上加「外部实例优先」的分支（与母仓 {@code ypbin-starter-test} 同一模式）。</p>
 *
 * @author wenbin
 * @since 2026-09-15
 */
final class EmbeddedMqttBroker implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(EmbeddedMqttBroker.class);

    private final Server broker = new Server();

    private final int port;

    EmbeddedMqttBroker() {
        try {
            this.port = freePort();
            Properties properties = new Properties();
            properties.setProperty(BrokerConstants.HOST_PROPERTY_NAME, "127.0.0.1");
            properties.setProperty(BrokerConstants.PORT_PROPERTY_NAME, String.valueOf(port));
            properties.setProperty(BrokerConstants.ALLOW_ANONYMOUS_PROPERTY_NAME, "true");
            properties.setProperty(BrokerConstants.PERSISTENCE_ENABLED_PROPERTY_NAME, "false");
            properties.setProperty(BrokerConstants.WEB_SOCKET_PORT_PROPERTY_NAME, "disabled");
            broker.startServer(new MemoryConfig(properties));
            log.info("[it] embedded mqtt broker listening on {}", port);
        } catch (IOException ex) {
            throw new UncheckedIOException("无法启动嵌入式 MQTT broker", ex);
        }
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
