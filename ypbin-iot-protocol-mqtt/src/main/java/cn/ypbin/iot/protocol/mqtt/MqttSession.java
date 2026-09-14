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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.exception.ProtocolException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.PointWriteStatus;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.util.Stages;
import com.hivemq.client.mqtt.datatypes.MqttQos;
import com.hivemq.client.mqtt.mqtt3.message.publish.Mqtt3Publish;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * MQTT 设备会话：一台设备对应一组主题。
 *
 * <p><b>能力实现方式</b>：</p>
 * <ul>
 *   <li>{@code subscribe}：<b>原生推送</b>（{@code SUBSCRIBE_NATIVE}），由 broker 主动下发，
 *       不像 Modbus 那样占框架的轮询调度资源；</li>
 *   <li>{@code write}：发布到主题；</li>
 *   <li>{@code read}：<b>不支持</b>。MQTT 是发布/订阅模型，没有请求-响应语义；
 *       声称支持读就必须自己实现一整套请求主题 + 关联 id + 超时匹配的伪协议，
 *       那是宿主业务层的事，不该伪装成协议能力。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
final class MqttSession implements DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(MqttSession.class);

    private final String sessionId;

    private final DeviceSpec device;

    private final MqttConnection connection;

    private final AdapterContext context;

    private final int defaultQos;

    private final boolean retainedDefault;

    private final Instant boundAt;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    /** 无请求超时配置时的兜底上限。 */
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 创建会话。
     *
     * @param device           设备规格（{@code localAddress} 为主题前缀，用于诊断与默认订阅）
     * @param connection       链路
     * @param context          适配器上下文
     * @param defaultQos       默认 QoS
     * @param retainedDefault  发布是否默认保留
     */
    MqttSession(DeviceSpec device, MqttConnection connection, AdapterContext context, int defaultQos,
            boolean retainedDefault) {
        this.device = device;
        this.connection = connection;
        this.context = context;
        this.defaultQos = defaultQos;
        this.retainedDefault = retainedDefault;
        this.boundAt = context.clock().instant();
        this.sessionId = device.deviceId() + "@" + connection.connectionId();
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    @Override
    public DeviceSpec device() {
        return device;
    }

    @Override
    public String connectionId() {
        return connection.connectionId();
    }

    @Override
    public SessionState state() {
        return closed.get() ? SessionState.CLOSED : connection.state();
    }

    @Override
    public Instant boundAt() {
        return boundAt;
    }

    @Override
    public CompletionStage<ReadResult> read(ReadRequest request) {
        // MQTT 无请求-响应语义：必须显式声明不支持，而不是返回一批 BAD 值让宿主以为"读了但没数据"
        return CompletableFuture.failedFuture(new UnsupportedCapabilityException(
                MqttAdapter.PROTOCOL_CODE, "read (MQTT is publish/subscribe; use subscribe)"));
    }

    @Override
    public CompletionStage<WriteResult> write(WriteRequest request) {
        Instant started = context.clock().instant();
        List<PointWriteStatus> statuses = new ArrayList<>(request.writes().size());
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (PointWrite write : request.writes()) {
            chain = chain.thenCompose(ignored -> publishOne(write, statuses));
        }
        return chain.thenApply(ignored -> {
            Duration elapsed = Duration.between(started, context.clock().instant());
            context.metrics().recordWrite(elapsed, statuses.stream().allMatch(PointWriteStatus::success));
            return new WriteResult(statuses, elapsed);
        });
    }

    private CompletionStage<Void> publishOne(PointWrite write, List<PointWriteStatus> statuses) {
        String topic = write.address().raw();
        // 发布主题必须是具体主题：通配符/空/超长都不能用来发布。
        // 不校验的后果是协议库**同步抛 IllegalArgumentException**，在批量写里会把整批打断，
        // 且异常被 thenCompose 包成 CompletionException（违反逐项失败与 C11 两条契约）。
        if (!MqttTopicMatcher.isValidFilter(topic) || topic.indexOf('+') >= 0 || topic.indexOf('#') >= 0) {
            statuses.add(PointWriteStatus.fail(write.address(), MqttAdapter.MSG_TOPIC_INVALID));
            return CompletableFuture.completedFuture(null);
        }
        byte[] payload;
        try {
            payload = toPayload(write.value());
        } catch (RuntimeException ex) {
            statuses.add(PointWriteStatus.fail(write.address(), MqttAdapter.MSG_VALUE_INVALID));
            return CompletableFuture.completedFuture(null);
        }
        CompletionStage<?> stage;
        try {
            // builder 段整体纳入 try：协议库对非法参数是同步抛，同步抛会绕过 Stage 契约
            stage = connection.client().publishWith()
                    .topic(topic)
                    .qos(qosOf(write))
                    .retain(retainedOf(write))
                    .payload(payload)
                    .send();
        } catch (RuntimeException ex) {
            statuses.add(PointWriteStatus.fail(write.address(), MqttAdapter.MSG_PUBLISH_FAILED));
            return CompletableFuture.completedFuture(null);
        }
        return stage
                // 发布必须显式超时：断网时客户端会把请求排队等重连，返回永不完成的 future，
                // 批量写里第一项挂住后续项就永不执行（R15）
                .toCompletableFuture()
                .orTimeout(Math.max(1L, requestTimeoutMillis()), TimeUnit.MILLISECONDS)
                .handle((result, error) -> {
                    if (error == null) {
                        statuses.add(PointWriteStatus.ok(write.address()));
                    } else {
                        statuses.add(PointWriteStatus.fail(write.address(), MqttAdapter.MSG_PUBLISH_FAILED));
                        log.debug("[ypbin-iot] mqtt publish failed for device {} topic {}: {}",
                                device.deviceId(), topic, error.toString());
                    }
                    return null;
                });
    }

    private long requestTimeoutMillis() {
        Duration timeout = context.settings().requestTimeout();
        return timeout == null || timeout.isZero() || timeout.isNegative()
                ? DEFAULT_REQUEST_TIMEOUT.toMillis() : timeout.toMillis();
    }

    /**
     * 该写操作使用的 QoS。
     *
     * <p>取值优先级：设备属性 {@code qos} → 协议默认值。放在设备属性而不是点位属性上，
     * 因为 QoS 是「这条链路对这个 broker 的投递保证」级别的参数，按点位配只会让运维困惑。</p>
     */
    private MqttQos qosOf(PointWrite write) {
        int qos = defaultQos;
        String configured = device.properties().get(MqttAdapter.ATTRIBUTE_QOS);
        if (configured != null && !configured.isBlank()) {
            try {
                qos = Integer.parseInt(configured.trim());
            } catch (NumberFormatException ex) {
                context.log(LogLevel.WARN, MqttAdapter.MSG_QOS_INVALID, write.address().raw(), configured);
            }
        }
        return MqttQos.fromCode(Math.min(Math.max(qos, 0), MqttAdapter.MAX_QOS));
    }

    private boolean retainedOf(PointWrite write) {
        String configured = device.properties().get(MqttAdapter.ATTRIBUTE_RETAINED);
        if (configured != null && !configured.isBlank()) {
            return Boolean.parseBoolean(configured.trim());
        }
        return retainedDefault;
    }

    private static byte[] toPayload(Object value) {
        if (value instanceof byte[] bytes) {
            return bytes;
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value).getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("unsupported mqtt payload type: " + value.getClass().getName());
    }

    @Override
    public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new ProtocolException(MqttAdapter.MSG_SESSION_CLOSED, device.deviceId()));
        }
        List<String> filters = new ArrayList<>(request.addresses().size());
        for (PointAddress address : request.addresses()) {
            String filter = address.raw();
            if (!MqttTopicMatcher.isValidFilter(filter)) {
                return CompletableFuture.failedFuture(
                        new AddressParseException(MqttAdapter.PROTOCOL_CODE, filter,
                                "invalid topic filter; wildcards must occupy a whole level and '#' must be last"));
            }
            filters.add(filter);
        }
        Subscription subscription = new Subscription("mqtt-sub-" + sequence.incrementAndGet(),
                filters, listener, request);
        CompletableFuture<SubscriptionHandle> result = new CompletableFuture<>();
        MqttQos qos = MqttQos.fromCode(Math.min(Math.max(defaultQos, 0), MqttAdapter.MAX_QOS));
        // 逐个过滤器订阅：MQTT 5 允许一次订阅多主题，但逐个订阅能让部分失败可见（哪个过滤器非法一目了然）
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String filter : filters) {
            chain = chain.thenCompose(ignored -> connection.client().subscribeWith()
                    .topicFilter(filter)
                    .qos(qos)
                    .callback(publish -> dispatch(subscription, publish))
                    .send()
                    .orTimeout(requestTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .thenApply(ignoredResult -> null));
        }
        chain.whenComplete((ignored, error) -> {
            if (error != null) {
                // 必须回滚已成功的过滤器：否则它们已在 broker 侧生效并持续投递，
                // 而宿主被告知"订阅失败"且拿不到任何句柄去取消（僵尸订阅）
                rollback(subscription, filters);
                result.completeExceptionally(Stages.unwrap(error));
                return;
            }
            subscriptions.put(subscription.subscriptionId(), subscription);
            context.log(LogLevel.DEBUG, MqttAdapter.MSG_SUBSCRIBED, device.deviceId(),
                    String.join(",", filters));
            result.complete(subscription);
        });
        return result;
    }

    /**
     * 回滚一次失败订阅中已经生效的过滤器。
     *
     * <p>逐个 UNSUBSCRIBE，失败只记日志：回滚本身失败不应掩盖原始失败原因。</p>
     */
    private void rollback(Subscription subscription, List<String> filters) {
        subscription.active.set(false);
        for (String filter : filters) {
            connection.client().unsubscribeWith()
                    .topicFilter(filter)
                    .send()
                    .orTimeout(requestTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        log.warn("[ypbin-iot] failed to roll back subscription of {} on {}",
                                subscription.subscriptionId(), filter, error);
                        return null;
                    });
        }
    }

    /**
     * 分发一条推送。
     *
     * @param subscription 订阅
     * @param publish      broker 推送的消息
     */
    private void dispatch(Subscription subscription, Mqtt3Publish publish) {
        if (!subscription.active.get()) {
            return;
        }
        String topic = publish.getTopic().toString();
        String matched = MqttTopicMatcher.firstMatch(
                subscription.filters.toArray(new String[0]), topic);
        if (matched == null) {
            return;
        }
        context.log(LogLevel.DEBUG, MqttAdapter.MSG_MATCHED, matched, topic);
        // 投递值的地址用「实际主题」而不是订阅过滤器：
        // 宿主需要知道这条数据具体来自哪个主题，而 '#'/'+' 过滤器本身不是有效主题
        String payload = new String(publish.getPayloadAsBytes(), StandardCharsets.UTF_8);
        PointValue point = PointValue.good(PointAddress.of(topic), payload, context.clock().instant());
        subscription.delivered.incrementAndGet();
        context.metrics().recordSubscriptionBatch(1);
        if (subscription.listener != null) {
            // dispatch() 在 HiveMQ 的 IO 线程上被调用：必须经投递器卸载宿主代码（I4），
            // 否则宿主一次落库就会阻塞该客户端的 IO 线程并波及同链路所有设备
            context.delivery().dispatch(() -> subscription.listener.onData(point));
            return;
        }
        context.egress().emit(new DataBatch(device.deviceId(), device.protocol(),
                connection.connectionId(), context.clock().instant(), List.of(point)));
    }

    @Override
    public CompletionStage<Void> unsubscribe(SubscriptionHandle handle) {
        if (handle == null) {
            return CompletableFuture.completedFuture(null);
        }
        Subscription subscription = subscriptions.remove(handle.subscriptionId());
        if (subscription == null) {
            // 幂等：重复取消不得报错
            return CompletableFuture.completedFuture(null);
        }
        subscription.active.set(false);
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (String filter : subscription.filters) {
            chain = chain.thenCompose(ignored -> connection.client().unsubscribeWith()
                    .topicFilter(filter)
                    .send()
                    .orTimeout(requestTimeoutMillis(), TimeUnit.MILLISECONDS)
                    .thenApply(ignoredResult -> null));
        }
        return chain.exceptionally(error -> {
            log.warn("[ypbin-iot] failed to unsubscribe {} from {}", subscription.subscriptionId(),
                    String.join(",", subscription.filters), error);
            return null;
        });
    }

    @Override
    public CompletionStage<PingResult> ping() {
        long started = System.nanoTime();
        if (!closed.get() && connection.state().isUsable()) {
            return CompletableFuture.completedFuture(
                    PingResult.alive(Duration.ofNanos(System.nanoTime() - started).toMillis()));
        }
        return CompletableFuture.completedFuture(PingResult.dead(MqttAdapter.MSG_CONNECTION_INACTIVE));
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> extensionType) {
        return Optional.empty();
    }

    @Override
    public CompletionStage<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.allOf(subscriptions.values().stream()
                        .map(subscription -> {
                            subscription.active.set(false);
                            return unsubscribe(subscription).toCompletableFuture();
                        })
                        .toArray(CompletableFuture[]::new))
                .thenRun(() -> {
                    subscriptions.clear();
                    connection.unregister(this);
                });
    }

    /** 链路断开时由连接回调。 */
    void onConnectionClosed() {
        closed.set(true);
        subscriptions.values().forEach(subscription -> subscription.active.set(false));
        subscriptions.clear();
    }

    /**
     * 单次订阅。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private final class Subscription implements SubscriptionHandle {

        private final String subscriptionId;

        private final List<String> filters;

        private final DataListener listener;

        private final SubscribeRequest request;

        private final AtomicBoolean active = new AtomicBoolean(true);

        private final AtomicLong delivered = new AtomicLong();

        private Subscription(String subscriptionId, List<String> filters, DataListener listener,
                SubscribeRequest request) {
            this.subscriptionId = subscriptionId;
            this.filters = List.copyOf(filters);
            this.listener = listener;
            this.request = request;
        }

        @Override
        public String subscriptionId() {
            return subscriptionId;
        }

        @Override
        public List<PointAddress> addresses() {
            return request.addresses();
        }

        @Override
        public boolean active() {
            return active.get();
        }

        @Override
        public long deliveredCount() {
            return delivered.get();
        }
    }
}
