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
package cn.ypbin.iot.runtime.subscription;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.protocol.DeviceSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 轮询式订阅管理器：把「周期性读」实现为订阅。
 *
 * <p>适用于没有推送能力的协议（Modbus、S7、SNMP 等），对应
 * {@link cn.ypbin.iot.core.protocol.ProtocolCapability#SUBSCRIBE_POLLING}。</p>
 *
 * <p><b>为什么放在框架而不是各协议模块</b>：轮询订阅的难点不在协议，而在于
 * ① 周期任务的调度成本（10 万设备不能各持一个 {@code ScheduledFuture}）；
 * ② 上一轮未完成时不得叠加下一轮（否则慢设备会堆积请求，把链路压垮）；
 * ③ 单轮失败不能让订阅悄悄死掉。这三件事对所有轮询协议完全一致，
 * 各协议模块各写一遍必然写错。</p>
 *
 * <p><b>不叠加轮询</b>：用 {@code inFlight} 标志保证「上一轮结束后再排下一轮」，
 * 天然实现自适应降频——设备变慢时实际采样周期自动拉长，而不是堆积。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class PollingSubscriptionManager {

    private static final Logger log = LoggerFactory.getLogger(PollingSubscriptionManager.class);

    /** 单轮读失败后的最小重试间隔，避免设备不可用时高频空转。 */
    private static final Duration FAILURE_BACKOFF = Duration.ofSeconds(5);

    private final TaskScheduler scheduler;

    private final AdapterContext context;

    private final ConcurrentMap<String, PollingSubscription> subscriptions = new ConcurrentHashMap<>();

    private final AtomicLong sequence = new AtomicLong();

    /**
     * 创建轮询订阅管理器。
     *
     * @param scheduler 调度器
     * @param context   适配器上下文
     */
    public PollingSubscriptionManager(TaskScheduler scheduler, AdapterContext context) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.context = Objects.requireNonNull(context, "context must not be null");
    }

    /**
     * 创建订阅。
     *
     * @param session  设备会话
     * @param request  订阅请求（{@code samplingInterval} 即轮询周期）
     * @param listener 点位回调；为 {@code null} 时走框架 egress
     * @return 订阅句柄
     */
    public SubscriptionHandle subscribe(DeviceSession session, SubscribeRequest request,
            DataListener listener) {
        String subscriptionId = "poll-" + sequence.incrementAndGet();
        PollingSubscription subscription = new PollingSubscription(subscriptionId, session, request, listener);
        subscriptions.put(subscriptionId, subscription);
        subscription.start();
        context.log(LogLevel.DEBUG, "iot.common.protocol.polling-subscribed", subscriptionId,
                request.samplingInterval());
        return subscription;
    }

    /**
     * 取消订阅（幂等）。
     *
     * @param handle 订阅句柄
     */
    public void unsubscribe(SubscriptionHandle handle) {
        if (handle == null) {
            return;
        }
        PollingSubscription subscription = subscriptions.remove(handle.subscriptionId());
        if (subscription != null) {
            subscription.cancel();
        }
    }

    /**
     * 取消某会话的全部订阅（会话关闭时调用）。
     *
     * @param sessionId 会话标识
     */
    public void cancelAll(String sessionId) {
        subscriptions.entrySet().removeIf(entry -> {
            if (entry.getValue().sessionId().equals(sessionId)) {
                entry.getValue().cancel();
                return true;
            }
            return false;
        });
    }

    /**
     * 当前活跃订阅数。
     *
     * @return 订阅数
     */
    public int activeCount() {
        return subscriptions.size();
    }

    /** 关闭全部订阅。 */
    public void close() {
        subscriptions.values().forEach(PollingSubscription::cancel);
        subscriptions.clear();
    }

    /**
     * 单个轮询订阅。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private final class PollingSubscription implements SubscriptionHandle {

        private final String subscriptionId;

        private final DeviceSession session;

        private final SubscribeRequest request;

        private final DataListener listener;

        private final AtomicLong delivered = new AtomicLong();

        private final AtomicBoolean active = new AtomicBoolean(true);

        private final AtomicBoolean inFlight = new AtomicBoolean(false);

        private volatile TaskScheduler.ScheduledTask scheduled;

        private PollingSubscription(String subscriptionId, DeviceSession session, SubscribeRequest request,
                DataListener listener) {
            this.subscriptionId = subscriptionId;
            this.session = session;
            this.request = request;
            this.listener = listener;
        }

        private String sessionId() {
            return session.sessionId();
        }

        private void start() {
            scheduleNext(request.samplingInterval());
        }

        /**
         * 排定下一轮。
         *
         * @param delay 延迟；失败退避时传入更长的间隔
         */
        private void scheduleNext(Duration delay) {
            if (!active.get()) {
                return;
            }
            scheduled = scheduler.scheduleOnce(this::pollOnce, delay);
        }

        private void pollOnce() {
            if (!active.get() || !inFlight.compareAndSet(false, true)) {
                // 上一轮尚未结束：跳过本轮而不是叠加，避免慢设备上请求堆积
                return;
            }
            CompletionStage<ReadResult> stage;
            try {
                stage = session.read(new ReadRequest(request.addresses(), request.samplingInterval()));
            } catch (RuntimeException ex) {
                inFlight.set(false);
                log.error("[ypbin-iot] polling subscription {} failed to issue read", subscriptionId, ex);
                scheduleNext(FAILURE_BACKOFF);
                return;
            }
            stage.whenComplete((result, error) -> {
                inFlight.set(false);
                if (!active.get()) {
                    return;
                }
                if (error != null) {
                    // 单轮失败不让订阅死掉，但必须可见：记日志 + 拉长间隔
                    log.warn("[ypbin-iot] polling subscription {} read failed; backing off",
                            subscriptionId, error);
                    scheduleNext(FAILURE_BACKOFF);
                    return;
                }
                deliver(result);
                scheduleNext(request.samplingInterval());
            });
        }

        private void deliver(ReadResult result) {
            if (result == null || result.values().isEmpty()) {
                return;
            }
            Instant now = context.clock().instant();
            for (PointValue value : result.values()) {
                if (!value.isGood()) {
                    context.log(LogLevel.DEBUG, "iot.common.protocol.polling-bad-quality",
                            subscriptionId, value.address().raw(), value.quality().getCode());
                }
            }
            delivered.addAndGet(result.values().size());
            context.metrics().recordSubscriptionBatch(result.values().size());
            if (listener != null) {
                for (PointValue value : result.values()) {
                    // 必须经投递器：本方法运行在协议库的回调线程或调度器线程上，
                    // 直接调用宿主代码等于把任意宿主逻辑放上这些线程（I4）
                    context.delivery().dispatch(() -> listener.onData(value));
                }
                return;
            }
            DeviceSpec device = session.device();
            context.egress().emit(new DataBatch(device.deviceId(), device.protocol(),
                    session.connectionId(), now, result.values()));
        }

        private void cancel() {
            active.set(false);
            TaskScheduler.ScheduledTask current = scheduled;
            if (current != null) {
                current.cancel();
            }
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
