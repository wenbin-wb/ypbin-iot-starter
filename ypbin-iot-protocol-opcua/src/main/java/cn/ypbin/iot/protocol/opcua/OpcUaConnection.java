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

import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 会话链路：一条到服务器的连接可承载多台设备（多组节点子树）。
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class OpcUaConnection implements ProtocolConnection {

    private static final Logger log = LoggerFactory.getLogger(OpcUaConnection.class);

    private final ConnectionSpec spec;

    private final OpcUaClient client;

    private final Instant openedAt = Instant.now();

    private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

    private final Map<String, OpcUaSession> sessions = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final AtomicBoolean disconnected = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.ONLINE;

    OpcUaConnection(ConnectionSpec spec, OpcUaClient client) {
        this.spec = spec;
        this.client = client;
    }

    OpcUaClient client() {
        return client;
    }

    /**
     * 本链路的请求超时。
     *
     * @return 请求超时
     */
    Duration endpointRequestTimeout() {
        return spec.requestTimeout();
    }

    void register(OpcUaSession session) {
        sessions.put(session.sessionId(), session);
    }

    void unregister(OpcUaSession session) {
        sessions.remove(session.sessionId());
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
        return closed.get() ? SessionState.CLOSED : state;
    }

    @Override
    public Instant openedAt() {
        return openedAt;
    }

    @Override
    public DeviceSession session() {
        throw new UnsupportedCapabilityException(spec.protocol(),
                "session (OPC UA server connection hosts multiple device subtrees; call ProtocolAdapter.bind)");
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
        // 本协议提供 BrowseExtension，由适配器在 bind 后装配到会话；链路本身不暴露扩展
        return Optional.empty();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = SessionState.CLOSED;
        sessions.values().forEach(OpcUaSession::onConnectionClosed);
        sessions.clear();
        disconnectOnce(null);
        closeReason.complete(CloseReason.clientRequest(Instant.now()));
    }

    /**
     * 连接丢失（由服务端/网络原因触发）。
     *
     * @param cause 原因
     */
    void onConnectionLost(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = SessionState.FAILED;
        sessions.values().forEach(OpcUaSession::onConnectionClosed);
        sessions.clear();
        disconnectOnce(cause);
        closeReason.complete(new CloseReason(
                cause == null ? CloseCause.REMOTE_CLOSED : CloseCause.TRANSPORT_ERROR, "", cause, Instant.now()));
    }

    /**
     * 确保底层客户端被断开一次。
     *
     * <p>{@code close()} 因 CAS 失败会成为空操作，因此断开动作必须独立守门——
     * 否则「先因故障置位、再被注册中心回收」的路径会永久泄漏底层 channel 与线程。</p>
     */
    private void disconnectOnce(Throwable cause) {
        if (!disconnected.compareAndSet(false, true)) {
            return;
        }
        client.disconnectAsync().whenComplete((ignored, error) -> {
            if (error != null) {
                log.error("[ypbin-iot] failed to disconnect opcua connection {}", connectionId(), error);
            } else if (cause != null) {
                log.debug("[ypbin-iot] opcua connection {} disconnected after failure.", connectionId());
            }
        });
    }
}
