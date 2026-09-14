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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.exception.ProtocolException;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.PointWriteStatus;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.util.Stages;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.eclipse.milo.opcua.sdk.client.subscriptions.MonitoredItemServiceOperationResult;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaMonitoredItem;
import org.eclipse.milo.opcua.sdk.client.subscriptions.OpcUaSubscription;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MonitoringMode;
import org.eclipse.milo.opcua.stack.core.types.enumerated.TimestampsToReturn;
import org.eclipse.milo.opcua.stack.core.types.structured.ReadValueId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OPC UA 设备会话。
 *
 * <p><b>相对 Modbus/MQTT 的两点协议优势必须用上</b>：</p>
 * <ol>
 *   <li><b>Read/Write 服务天然批量</b>：一次请求可携带多个 NodeId，因此不逐点位调用，
 *       而是分批提交（单批上限由 {@code max-nodes-per-read} 与服务端 OperationLimits 共同约束）；</li>
 *   <li><b>原生订阅</b>：由服务器按 {@code publishingInterval} 主动推送，
 *       不占框架的轮询调度资源（{@code SUBSCRIBE_NATIVE}）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-14
 */
final class OpcUaSession implements DeviceSession {

    private static final Logger log = LoggerFactory.getLogger(OpcUaSession.class);

    /** ServerStatus 节点：ping 用它做一次真实协议读。 */
    private static final NodeId SERVER_STATUS_NODE = NodeId.parse("i=2256");

    private final String sessionId;

    private final DeviceSpec device;

    private final OpcUaConnection connection;

    private final AdapterContext context;

    private final int maxNodesPerRead;

    private final Duration publishingInterval;

    private final long requestTimeoutMillis;

    private final Instant boundAt;

    private final AtomicBoolean closed = new AtomicBoolean(false);

    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    OpcUaSession(DeviceSpec device, OpcUaConnection connection, AdapterContext context,
            int maxNodesPerRead, Duration publishingInterval) {
        this.device = device;
        this.connection = connection;
        this.context = context;
        this.maxNodesPerRead = maxNodesPerRead;
        this.publishingInterval = publishingInterval;
        // 优先用链路级 requestTimeout：宿主按链路配的超时必须生效
        Duration timeout = connection.endpointRequestTimeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            timeout = context.settings().requestTimeout();
        }
        this.requestTimeoutMillis = timeout == null || timeout.isZero() || timeout.isNegative()
                ? OpcUaAdapter.DEFAULT_REQUEST_TIMEOUT.toMillis() : timeout.toMillis();
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
        Instant started = context.clock().instant();
        List<PointAddress> addresses = request.addresses();
        List<NodeId> nodeIds = new ArrayList<>(addresses.size());
        Map<PointAddress, PointValue> preFailed = new ConcurrentHashMap<>();
        Instant now = context.clock().instant();
        for (PointAddress address : addresses) {
            try {
                nodeIds.add(OpcUaNodeIdCodec.parse(address.raw()));
            } catch (RuntimeException ex) {
                // 单个地址写错只让该点位失败：点位表里一个错别字不该让整台设备的采集全灭
                preFailed.put(address, PointValue.bad(address, Quality.CONFIG_ERROR,
                        OpcUaAdapter.MSG_ADDRESS_INVALID, now));
                context.metrics().recordError(OpcUaAdapter.MSG_ADDRESS_INVALID);
            }
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        Map<NodeId, DataValue> collected = new ConcurrentHashMap<>();
        for (int offset = 0; offset < nodeIds.size(); offset += maxNodesPerRead) {
            List<NodeId> chunk = List.copyOf(nodeIds.subList(offset,
                    Math.min(offset + maxNodesPerRead, nodeIds.size())));
            chain = chain.thenCompose(ignored -> connection.client()
                    .readValuesAsync(0.0D, TimestampsToReturn.Both, chunk)
                    .orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                    .handle((values, error) -> {
                        if (error != null) {
                            context.metrics().recordError(isTimeout(error)
                                    ? OpcUaAdapter.MSG_READ_TIMEOUT : OpcUaAdapter.MSG_READ_FAILED);
                            log.debug("[ypbin-iot] opcua read chunk failed for device {}: {}",
                                    device.deviceId(), error.toString());
                            return null;
                        }
                        for (int index = 0; index < chunk.size() && values != null
                                && index < values.size(); index++) {
                            collected.put(chunk.get(index), values.get(index));
                        }
                        return null;
                    }));
        }
        return Stages.normalize(chain.thenApply(ignored -> {
            Instant finished = context.clock().instant();
            List<PointValue> values = new ArrayList<>(addresses.size());
            for (PointAddress address : addresses) {
                PointValue preFailure = preFailed.get(address);
                if (preFailure != null) {
                    values.add(preFailure);
                    continue;
                }
                DataValue dataValue = collected.get(OpcUaNodeIdCodec.parse(address.raw()));
                values.add(toPointValue(address, dataValue, finished));
            }
            // SPI §4.2：整条链路不可用且全部点位失败时必须异常完成，
            // 否则宿主无法区分「个别点位坏」与「链路已死」
            if (!nodeIds.isEmpty() && values.stream().noneMatch(PointValue::isGood)
                    && !connection.state().isUsable()) {
                throw new ProtocolException(OpcUaAdapter.MSG_CONNECTION_INACTIVE, device.deviceId());
            }
            Duration elapsed = Duration.between(started, finished);
            context.metrics().recordRead(elapsed, true);
            return new ReadResult(values, elapsed);
        }));
    }

    private PointValue toPointValue(PointAddress address, DataValue dataValue, Instant now) {
        if (dataValue == null) {
            return PointValue.bad(address, Quality.BAD, OpcUaAdapter.MSG_NO_RESULT, now);
        }
        StatusCode statusCode = dataValue.getStatusCode();
        if (statusCode == null || !statusCode.isGood()) {
            // 原始 StatusCode 只进日志与指标维度，不进 qualityReason：
            // qualityReason 是 i18n 消息键（I2/C6），塞原始字符串会让宿主无法按码分类
            context.metrics().recordError(OpcUaAdapter.MSG_STATUS_BAD);
            log.debug("[ypbin-iot] opcua node {} returned status {}", address.raw(), statusCode);
            return PointValue.bad(address, Quality.BAD, OpcUaAdapter.MSG_STATUS_BAD, now);
        }
        Instant sourceTime = dataValue.getSourceTime() == null ? now : dataValue.getSourceTime().getJavaInstant();
        return PointValue.good(address, dataValue.getValue() == null ? null : dataValue.getValue().getValue(),
                sourceTime);
    }

    @Override
    public CompletionStage<WriteResult> write(WriteRequest request) {
        Instant started = context.clock().instant();
        List<PointWrite> writes = request.writes();
        List<NodeId> nodeIds = new ArrayList<>(writes.size());
        List<DataValue> dataValues = new ArrayList<>(writes.size());
        List<PointWriteStatus> statuses = new ArrayList<>();
        for (PointWrite write : writes) {
            try {
                nodeIds.add(OpcUaNodeIdCodec.parse(write.address().raw()));
                dataValues.add(new DataValue(new Variant(write.value())));
                statuses.add(null);
            } catch (RuntimeException ex) {
                nodeIds.add(null);
                dataValues.add(null);
                statuses.add(PointWriteStatus.fail(write.address(), OpcUaAdapter.MSG_ADDRESS_INVALID));
            }
        }
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (int offset = 0; offset < nodeIds.size(); offset += maxNodesPerRead) {
            int from = offset;
            int to = Math.min(offset + maxNodesPerRead, nodeIds.size());
            List<NodeId> chunkNodes = new ArrayList<>();
            List<DataValue> chunkValues = new ArrayList<>();
            // 记录每个请求项在本次 chunk 内的**位置**：用 indexOf 定位在重复 NodeId 时
            // 会全部命中首个下标 → 写失败被报成功、写成功被报失败（已有实证）
            Map<Integer, Integer> positionOf = new LinkedHashMap<>();
            for (int index = from; index < to; index++) {
                if (nodeIds.get(index) != null) {
                    positionOf.put(index, chunkNodes.size());
                    chunkNodes.add(nodeIds.get(index));
                    chunkValues.add(dataValues.get(index));
                }
            }
            if (chunkNodes.isEmpty()) {
                continue;
            }
            chain = chain.thenCompose(ignored -> connection.client()
                    .writeValuesAsync(chunkNodes, chunkValues)
                    .orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                    .handle((results, error) -> {
                        for (int index = from; index < to; index++) {
                            PointWrite write = writes.get(index);
                            if (nodeIds.get(index) == null) {
                                continue;
                            }
                            Integer position = positionOf.get(index);
                            if (error != null || results == null || position == null
                                    || position >= results.size()) {
                                statuses.set(index, PointWriteStatus.fail(write.address(),
                                        OpcUaAdapter.MSG_WRITE_FAILED));
                                continue;
                            }
                            StatusCode statusCode = results.get(position);
                            statuses.set(index, statusCode != null && statusCode.isGood()
                                    ? PointWriteStatus.ok(write.address())
                                    : PointWriteStatus.fail(write.address(), statusCode == null
                                            ? OpcUaAdapter.MSG_WRITE_FAILED : OpcUaAdapter.MSG_STATUS_BAD));
                        }
                        return null;
                    }));
        }
        return Stages.normalize(chain.thenApply(ignored -> {
            Duration elapsed = Duration.between(started, context.clock().instant());
            context.metrics().recordWrite(elapsed, statuses.stream().allMatch(PointWriteStatus::success));
            return new WriteResult(statuses, elapsed);
        }));
    }

    @Override
    public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request, DataListener listener) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                    new ProtocolException(OpcUaAdapter.MSG_SESSION_CLOSED, device.deviceId()));
        }
        List<NodeId> nodeIds = new ArrayList<>(request.addresses().size());
        for (PointAddress address : request.addresses()) {
            try {
                nodeIds.add(OpcUaNodeIdCodec.parse(address.raw()));
            } catch (RuntimeException ex) {
                return CompletableFuture.failedFuture(ex);
            }
        }
        double interval = request.samplingInterval() == null || request.samplingInterval().isZero()
                ? publishingInterval.toMillis()
                : Math.max(1L, request.samplingInterval().toMillis());
        Subscription subscription = new Subscription("opcua-sub-" + sequence.incrementAndGet(),
                request, listener);
        CompletableFuture<SubscriptionHandle> result = new CompletableFuture<>();
        OpcUaClient client = connection.client();
        OpcUaSubscription uaSubscription = new OpcUaSubscription(client, interval);
        try {
            // 先建订阅，再逐个挂监控项：监控项创建结果逐项返回，便于定位哪个节点不被服务端接受
            uaSubscription.createAsync().toCompletableFuture()
                    .orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS).join();
            List<OpcUaMonitoredItem> items = new ArrayList<>(nodeIds.size());
            for (int index = 0; index < nodeIds.size(); index++) {
                NodeId nodeId = nodeIds.get(index);
                OpcUaMonitoredItem item = new OpcUaMonitoredItem(new ReadValueId(nodeId,
                        AttributeId.Value.uid(), null, null), MonitoringMode.Reporting);
                item.setSamplingInterval(interval);
                final PointAddress address = request.addresses().get(index);
                item.setDataValueListener((monitoredItem, dataValue) ->
                        dispatch(subscription, address, dataValue));
                items.add(item);
            }
            uaSubscription.addMonitoredItems(items);
            List<MonitoredItemServiceOperationResult> createResults =
                    uaSubscription.createMonitoredItems();
            long failed = createResults.stream().filter(item -> !item.isGood()).count();
            if (failed > 0) {
                // 订阅不存在的节点时服务端会回 Bad_NodeIdUnknown：不校验就会"假成功"，
                // 宿主以为订上了却永远收不到数据（禁静默假成功）
                context.log(LogLevel.WARN, OpcUaAdapter.MSG_SUBSCRIBE_PARTIAL_FAILED,
                        device.deviceId(), String.valueOf(failed), String.valueOf(items.size()));
            }
            if (failed == createResults.size()) {
                uaSubscription.deleteAsync().toCompletableFuture().join();
                throw new ProtocolException(OpcUaAdapter.MSG_SUBSCRIBE_FAILED, device.deviceId());
            }
            subscription.attach(uaSubscription);
            subscriptions.put(subscription.subscriptionId(), subscription);
            context.log(LogLevel.DEBUG, OpcUaAdapter.MSG_SUBSCRIBED, device.deviceId(),
                    String.valueOf(nodeIds.size()));
            result.complete(subscription);
        } catch (RuntimeException ex) {
            subscription.active.set(false);
            uaSubscription.deleteAsync().whenComplete((ignored, error) -> {
                if (error != null) {
                    log.warn("[ypbin-iot] failed to roll back opcua subscription of device {}",
                            device.deviceId(), error);
                }
            });
            result.completeExceptionally(Stages.unwrap(ex));
        }
        return result;
    }

    private void dispatch(Subscription subscription, PointAddress address, DataValue dataValue) {
        if (!subscription.active.get()) {
            return;
        }
        PointValue point = toPointValue(address, dataValue, context.clock().instant());
        subscription.delivered.incrementAndGet();
        context.metrics().recordSubscriptionBatch(1);
        if (subscription.listener != null) {
            // dispatch() 在 Milo 的 JVM 全局共享执行器上被调用：必须经投递器卸载宿主代码（I4），
            // 否则宿主阻塞会占用该共享执行器的线程并波及同 JVM 内所有 Milo 连接
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
            return CompletableFuture.completedFuture(null);
        }
        subscription.active.set(false);
        OpcUaSubscription uaSubscription = subscription.uaSubscription;
        if (uaSubscription == null) {
            return CompletableFuture.completedFuture(null);
        }
        return uaSubscription.deleteAsync().toCompletableFuture()
                .orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .handle((ignored, error) -> {
                    if (error != null) {
                        log.warn("[ypbin-iot] failed to delete opcua subscription {}",
                                handle.subscriptionId(), error);
                    }
                    return (Void) null;
                });
    }

    @Override
    public CompletionStage<PingResult> ping() {
        long started = System.nanoTime();
        if (closed.get() || !connection.state().isUsable()) {
            return CompletableFuture.completedFuture(PingResult.dead(OpcUaAdapter.MSG_CONNECTION_INACTIVE));
        }
        // 必须发一次真实协议请求：ServerStatus 是标准地址空间中必然存在的节点
        return connection.client()
                .readValuesAsync(0.0D, TimestampsToReturn.Neither, List.of(SERVER_STATUS_NODE))
                .orTimeout(requestTimeoutMillis, TimeUnit.MILLISECONDS)
                .handle((values, error) -> {
                    DataValue dataValue = values == null || values.isEmpty() ? null : values.get(0);
                    long roundTrip = Duration.ofNanos(System.nanoTime() - started).toMillis();
                    if (error != null || dataValue == null
                            || dataValue.getStatusCode() == null || !dataValue.getStatusCode().isGood()) {
                        return PingResult.dead(OpcUaAdapter.MSG_CONNECTION_INACTIVE);
                    }
                    return PingResult.alive(roundTrip);
                });
    }

    @Override
    public <T> Optional<T> unwrap(Class<T> extensionType) {
        return OpcUaBrowser.supports(extensionType)
                ? Optional.of(extensionType.cast(new OpcUaBrowser(connection.client(), context)))
                : Optional.empty();
    }

    @Override
    public CompletionStage<Void> close() {
        if (!closed.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<?>[] futures = subscriptions.values().stream()
                .map(subscription -> {
                    subscription.active.set(false);
                    return unsubscribe(subscription).toCompletableFuture();
                })
                .toArray(CompletableFuture[]::new);
        return CompletableFuture.allOf(futures).thenRun(() -> {
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

    private static boolean isTimeout(Throwable error) {
        return Stages.unwrap(error) instanceof TimeoutException;
    }

    /**
     * 单次订阅。
     *
     * @author wenbin
     * @since 2026-09-14
     */
    private final class Subscription implements SubscriptionHandle {

        private final String subscriptionId;

        private final SubscribeRequest request;

        private final DataListener listener;

        private final AtomicBoolean active = new AtomicBoolean(true);

        private final AtomicLong delivered = new AtomicLong();

        private volatile OpcUaSubscription uaSubscription;

        private Subscription(String subscriptionId, SubscribeRequest request, DataListener listener) {
            this.subscriptionId = subscriptionId;
            this.request = request;
            this.listener = listener;
        }

        private void attach(OpcUaSubscription subscription) {
            this.uaSubscription = subscription;
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
