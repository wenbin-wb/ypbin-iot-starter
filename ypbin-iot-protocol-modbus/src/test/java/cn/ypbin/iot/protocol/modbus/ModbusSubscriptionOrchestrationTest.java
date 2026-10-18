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

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.protocol.AddressExpansionExtension;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import cn.ypbin.iot.spring.subscription.DefaultSubscriptionOrchestrator;
import cn.ypbin.iot.spring.subscription.ProtocolDescriptorSource;
import cn.ypbin.iot.spring.subscription.SubscriptionBackoffPolicy;
import cn.ypbin.iot.spring.subscription.SubscriptionBatchItem;
import cn.ypbin.iot.spring.subscription.SubscriptionOrchestrator;
import cn.ypbin.iot.spring.subscription.SubscriptionPoint;
import cn.ypbin.iot.spring.subscription.SubscriptionResult;
import cn.ypbin.iot.spring.subscription.SubscriptionStatus;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅编排 × 真实 Modbus 扩展的端到端测试（issue #37 阻塞缺口 1）。
 *
 * <p><b>为什么这条用例必须存在</b>：`SubscriptionOrchestratorTest` 用的是测试桩扩展，
 * 只能证明「编排器会调扩展」。它无法回答验收真正关心的问题——
 * <b>用仓库里真实的 Modbus 适配器时，{@code wordCount=2} 的点位会不会真的被展开成两个地址、
 * 并真的从设备读到两个寄存器的数据</b>。因此这里跑真实 Modbus 适配器 + 真实 TCP 测试服务器，
 * 走完整链路：编排器 → 展开 → 轮询订阅 → 协议读 → 数据回投。</p>
 *
 * <p><b>为什么放在 modbus 模块而不是 starter</b>：依赖方向不可逆（starter 是协议模块的上游）。
 * 让 starter 的测试反向依赖 protocol-modbus 会形成「上游测试依赖下游」的环，
 * 所以端到端用例归协议模块。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class ModbusSubscriptionOrchestrationTest {

    private static final int UNIT_A = 1;

    /** 基址偏移：与 wordCount=2 点位配合，验证 base+1 也被真正读到。 */
    private static final int BASE_OFFSET = 10;

    private ModbusTcpTestServer server;

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private ModbusAdapter adapter;

    private ProtocolConnection connection;

    @BeforeEach
    void setUp() throws IOException {
        server = new ModbusTcpTestServer();
        server.start();
        scheduler = new DefaultTaskScheduler(2, 64);
        context = new DefaultAdapterContext(ModbusAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 32);
        adapter = new ModbusAdapter();
        connection = adapter.open(new ConnectionSpec("orch",
                        ModbusAdapter.PROTOCOL_CODE,
                        Endpoint.of("tcp://127.0.0.1:" + server.port()), Duration.ofSeconds(3),
                        Duration.ofSeconds(3), null, null, Map.of()), context)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    @AfterEach
    void tearDown() {
        if (connection != null) {
            connection.close();
        }
        adapter.close();
        scheduler.close();
        server.close();
    }

    @Test
    @DisplayName("MSO-01 真实 Modbus 会话必须暴露 AddressExpansionExtension，且描述符声明 BATCH")
    void realSessionMustExposeExpansionExtension() {
        DeviceSession session = bindSession();
        try {
            assertThat(session.unwrap(AddressExpansionExtension.class))
                    .as("迁移后多寄存器点位依赖这个扩展；缺失会让整点位订阅失败")
                    .isPresent();
            ProtocolDescriptor descriptor = adapter.descriptor();
            assertThat(descriptor.supportsExtension(AddressExpansionExtension.class))
                    .as("扩展必须同时声明在描述符里（否则注册期一致性与能力发现都看不到）")
                    .isTrue();
            assertThat(descriptor.subscriptionAddressing())
                    .isEqualTo(SubscriptionAddressing.BATCH);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MSO-02 wordCount=2 的点位经编排器订阅后，必须真的从设备读到 base 与 base+1 两个寄存器")
    void twoRegisterPointMustReadBothRegistersThroughOrchestrator() {
        // 两个寄存器的值不同：若只订了基址，第二个值永远不会出现（这正是迁移后的真实故障形态）
        server.setRegister(UNIT_A, BASE_OFFSET, 1111);
        server.setRegister(UNIT_A, BASE_OFFSET + 1, 2222);
        DeviceSession session = bindSession();
        try {
            SubscriptionOrchestrator orchestrator = orchestrator();
            RecordingListener listener = new RecordingListener();
            SubscriptionResult result = orchestrator.subscribe(new SubscriptionBatchItem(
                            session.device(), session,
                            List.of(SubscriptionPoint.of("holding:" + BASE_OFFSET, 2)),
                            listener))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            assertThat(result.status())
                    .as("真实扩展存在时不得落入「扩展缺失 ⇒ 整点位失败」分支")
                    .isEqualTo(SubscriptionStatus.SUBSCRIBED);
            assertThat(result.outcome().subscribed())
                    .extracting(PointAddress::raw)
                    .as("wordCount=2 必须展开成 base 与 base+1 两个地址")
                    .containsExactlyInAnyOrder("holding:" + BASE_OFFSET,
                            "holding:" + (BASE_OFFSET + 1));
            assertThat(result.outcome().failures()).isEmpty();

            // 数据面证据：两个寄存器的真实值都通过订阅投递回来
            assertThat(awaitValues(listener, 2)).isTrue();
            assertThat(listener.values()).extracting(value -> value.address().raw())
                    .containsExactlyInAnyOrder("holding:" + BASE_OFFSET,
                            "holding:" + (BASE_OFFSET + 1));
            assertThat(listener.values()).filteredOn(value ->
                            value.address().raw().equals("holding:" + BASE_OFFSET))
                    .extracting(PointValue::value)
                    .as("基址寄存器必须读到设备侧的 1111")
                    .containsExactly(1111);
            assertThat(listener.values()).filteredOn(value ->
                            value.address().raw().equals("holding:" + (BASE_OFFSET + 1)))
                    .extracting(PointValue::value)
                    .as("兄弟寄存器必须读到设备侧的 2222（只订基址时这里永远没有数据）")
                    .containsExactly(2222);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MSO-03 wordCount=4 的点位必须展开成 4 个地址并读到 4 个寄存器")
    void fourRegisterPointMustReadAllFourRegisters() {
        int base = 20;
        for (int delta = 0; delta < 4; delta++) {
            server.setRegister(UNIT_A, base + delta, 5000 + delta);
        }
        DeviceSession session = bindSession();
        try {
            RecordingListener listener = new RecordingListener();
            SubscriptionResult result = orchestrator().subscribe(new SubscriptionBatchItem(
                            session.device(), session,
                            List.of(SubscriptionPoint.of("holding:" + base, 4)), listener))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
            assertThat(result.outcome().subscribed()).extracting(PointAddress::raw)
                    .containsExactlyInAnyOrder("holding:20", "holding:21", "holding:22", "holding:23");
            assertThat(awaitValues(listener, 4)).isTrue();
            assertThat(listener.values()).extracting(PointValue::value)
                    .containsExactlyInAnyOrder(5000, 5001, 5002, 5003);
        } finally {
            closeQuietly(session);
        }
    }

    @Test
    @DisplayName("MSO-04 越界点位必须显式失败（扩展抛 AddressParseException），绝不回绕读别的寄存器")
    void outOfRangePointMustFailExplicitly() {
        DeviceSession session = bindSession();
        try {
            SubscriptionResult result = orchestrator().subscribe(new SubscriptionBatchItem(
                            session.device(), session,
                            List.of(SubscriptionPoint.of("holding:65535", 2)), null))
                    .toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).join();

            assertThat(result.status()).isEqualTo(SubscriptionStatus.FAILED);
            assertThat(result.outcome().failures()).hasSize(1);
            assertThat(result.outcome().failures().get(0).reasonKey())
                    .isEqualTo(AddressParseException.MESSAGE_KEY);
        } finally {
            closeQuietly(session);
        }
    }

    // ------------------------------------------------------------------
    // 帮助方法
    // ------------------------------------------------------------------

    private SubscriptionOrchestrator orchestrator() {
        return new DefaultSubscriptionOrchestrator(
                ProtocolDescriptorSource.of(List.of(adapter.descriptor())),
                SubscriptionBackoffPolicy.defaults(), Clock.systemUTC());
    }

    private DeviceSession bindSession() {
        // 轮询周期取短值，让测试在合理时间内收到数据；生产中由设备规格决定
        DeviceSpec device = new DeviceSpec("modbus-orch", "编排测试从站", ModbusAdapter.PROTOCOL_CODE,
                "orch", String.valueOf(UNIT_A), Duration.ofMillis(50), Map.of());
        return adapter.bind(connection, device, context).toCompletableFuture()
                .orTimeout(5, TimeUnit.SECONDS).join();
    }

    private static boolean awaitValues(RecordingListener listener, int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (listener.values().size() >= expected) {
                return true;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return listener.values().size() >= expected;
    }

    private static void closeQuietly(DeviceSession session) {
        session.close().toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
    }

    /**
     * 记录订阅投递的点位值（线程安全：投递经框架投递器，可能来自别的线程）。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class RecordingListener implements DataListener {

        private final List<PointValue> received = new CopyOnWriteArrayList<>();

        @Override
        public void onData(PointValue value) {
            received.add(value);
        }

        private List<PointValue> values() {
            return List.copyOf(received);
        }
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作（本测试用 listener 接收数据）
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
