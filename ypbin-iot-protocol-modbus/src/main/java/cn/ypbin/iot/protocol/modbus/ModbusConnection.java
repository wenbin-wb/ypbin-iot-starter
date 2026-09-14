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
package cn.ypbin.iot.protocol.modbus;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.runtime.subscription.PollingSubscriptionManager;
import com.digitalpetri.modbus.client.ModbusClient;
import java.time.Duration;
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
 * Modbus 物理链路：一条 TCP / 串口链路，可承载多个从站（unitId）。
 *
 * <p><b>这是「连接 / 设备两级模型」的典型场景</b>：一台 Modbus 网关后面挂 200 个从站，
 * 只有一条 TCP 链路；每个从站是一个 {@link ModbusSession}，按 {@code localAddress}（unitId）区分。
 * 因此 {@link #session()} 对本协议<b>不适用</b>，必须走
 * {@link ModbusAdapter#bind}——这是文档要求「1:N 协议必须覆写 bind」的实例。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class ModbusConnection implements ProtocolConnection {

    private static final Logger log = LoggerFactory.getLogger(ModbusConnection.class);

    private final ConnectionSpec spec;

    private final ModbusClient client;

    /** 轮询订阅管理器：Modbus 无推送能力，订阅由框架周期读实现。 */
    private final PollingSubscriptionManager polling;

    /** 保活任务：定期检查链路是否仍然连接，断开则触发注册中心重连。 */
    private final TaskScheduler.ScheduledTask keepAliveTask;

    private final Instant openedAt = Instant.now();

    private final CompletableFuture<CloseReason> closeReason = new CompletableFuture<>();

    private final Map<String, ModbusSession> sessions = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private volatile SessionState state = SessionState.ONLINE;

    ModbusConnection(ConnectionSpec spec, ModbusClient client, AdapterContext context) {
        this.spec = spec;
        this.client = client;
        this.polling = new PollingSubscriptionManager(context.scheduler(), context);
        Duration keepAlive = context.settings().keepAliveInterval();
        // 远程静默断开不会产生任何回调，只能主动探活：这也是 DESIGN §5.2 N9 要求的保活
        this.keepAliveTask = keepAlive == null || keepAlive.isZero() || keepAlive.isNegative()
                ? null
                : context.scheduler().schedule(this::checkAlive, keepAlive, keepAlive);
    }

    /**
     * 轮询订阅管理器（供会话使用）。
     *
     * @return 管理器
     */
    PollingSubscriptionManager polling() {
        return polling;
    }

    private void checkAlive() {
        if (closed.get()) {
            return;
        }
        if (!client.isConnected()) {
            log.warn("[ypbin-iot] modbus connection {} is no longer connected; notifying registry",
                    connectionId());
            onConnectionLost(null);
        }
    }

    ModbusClient client() {
        return client;
    }

    void register(ModbusSession session) {
        sessions.put(session.localAddressKey(), session);
    }

    void unregister(ModbusSession session) {
        sessions.remove(session.localAddressKey());
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
        if (closed.get() || !client.isConnected()) {
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
        // 1:N 协议：一条链路对应多个从站，必须经 ProtocolAdapter.bind 取得设备会话
        throw new UnsupportedCapabilityException(spec.protocol(),
                "session (Modbus is a multi-device link; call ProtocolAdapter.bind with a unitId)");
    }

    @Override
    public CompletionStage<CloseReason> whenClosed() {
        return closeReason;
    }

    @Override
    public Map<String, String> describe() {
        Map<String, String> details = new LinkedHashMap<>(spec.endpoint().parameters());
        details.put("endpoint", spec.endpoint().uri());
        details.put("slaveCount", String.valueOf(sessions.size()));
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
        if (keepAliveTask != null) {
            keepAliveTask.cancel();
        }
        polling.close();
        sessions.values().forEach(ModbusSession::onConnectionClosed);
        sessions.clear();
        if (client.isConnected()) {
            client.disconnectAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    log.error("[ypbin-iot] failed to disconnect modbus connection {}", connectionId(), error);
                }
            });
        }
        closeReason.complete(CloseReason.clientRequest(Instant.now()));
    }

    /**
     * 由传输层/适配器回调：链路异常断开。
     *
     * @param cause 原因；正常关闭时为 {@code null}
     */
    void onConnectionLost(Throwable cause) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        state = SessionState.FAILED;
        if (keepAliveTask != null) {
            keepAliveTask.cancel();
        }
        polling.close();
        sessions.values().forEach(ModbusSession::onConnectionClosed);
        closeReason.complete(new CloseReason(cause == null ? CloseCause.REMOTE_CLOSED : CloseCause.TRANSPORT_ERROR,
                "", cause, Instant.now()));
    }
}
