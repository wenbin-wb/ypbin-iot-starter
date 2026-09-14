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

import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT 会话链路：一条 broker 连接承载多台设备（多组主题）。
 *
 * <p><b>与 Modbus 同属 1:N 模型，但「多设备」的切分依据不同</b>：Modbus 靠 unitId，
 * MQTT 靠<b>主题前缀</b>。因此 {@link #session()} 同样不适用，必须走 {@code ProtocolAdapter.bind}。</p>
 *
 * <p>MQTT 的连接是长连接且由 broker 维护会话状态，因此这里不做保活轮询：
 * 客户端的 keepAlive 由协议库处理，断线事件会通过 {@code whenClosed} 上抛。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class MqttConnection implements ProtocolConnection {

    private static final Logger log = LoggerFactory.getLogger(MqttConnection.class);

    private final ConnectionSpec spec;

    private final Mqtt3AsyncClient client;

    private final Instant openedAt = Instant.now();

    private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

    private final Map<String, MqttSession> sessions = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.ONLINE;

    MqttConnection(ConnectionSpec spec, Mqtt3AsyncClient client) {
        this.spec = spec;
        this.client = client;
    }

    Mqtt3AsyncClient client() {
        return client;
    }

    void register(MqttSession session) {
        sessions.put(session.sessionId(), session);
    }

    void unregister(MqttSession session) {
        sessions.remove(session.sessionId());
    }

    int sessionCount() {
        return sessions.size();
    }

    @Override
    public String connectionId() {
        return spec.connectionId();
    }

    @Override
    public Endpoint endpoint() {
        return spec.endpoint();
    }

    @Override
    public SessionState state() {
        if (closed.get()) {
            return SessionState.CLOSED;
        }
        return state;
    }

    @Override
    public Instant openedAt() {
        return openedAt;
    }

    @Override
    public DeviceSession session() {
        throw new UnsupportedCapabilityException(spec.protocol(),
                "session (MQTT is a multi-device link; call ProtocolAdapter.bind with a topic prefix)");
    }

    @Override
    public CompletionStage<CloseReason> whenClosed() {
        return closeReason;
    }

    @Override
    public Map<String, String> describe() {
        Map<String, String> details = new LinkedHashMap<>(spec.endpoint().parameters());
        details.put("endpoint", spec.endpoint().uri());
        details.put("deviceCount", String.valueOf(sessions.size()));
        return Map.copyOf(details);
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> extensionType) {
        return Optional.empty();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = SessionState.CLOSED;
        sessions.values().forEach(MqttSession::onConnectionClosed);
        sessions.clear();
        client.disconnect().whenComplete((ignored, error) -> {
            if (error != null) {
                log.error("[ypbin-iot] failed to disconnect mqtt connection {}", connectionId(), error);
            }
        });
        closeReason.complete(CloseReason.clientRequest(Instant.now()));
    }

    /**
     * 由客户端断线回调：链路失效。
     *
     * @param cause 原因；正常关闭时为 {@code null}
     */
    void onConnectionLost(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = SessionState.FAILED;
        sessions.values().forEach(MqttSession::onConnectionClosed);
        closeReason.complete(new CloseReason(cause == null ? CloseCause.REMOTE_CLOSED : CloseCause.TRANSPORT_ERROR,
                "", cause, Instant.now()));
    }
}
