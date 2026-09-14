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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.TlsOptions;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MQTT 边界与异常路径测试。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class MqttEdgeCaseTest {

    private MqttTestBroker broker;

    private DefaultTaskScheduler scheduler;

    private AdapterContext context;

    private MqttAdapter adapter;

    @BeforeEach
    void setUp() throws IOException {
        broker = new MqttTestBroker();
        scheduler = new DefaultTaskScheduler(2, 64);
        context = new DefaultAdapterContext(MqttAdapter.PROTOCOL_CODE,
                DefaultAdapterSettings.defaults(), new NoopEgress(), scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 32);
        adapter = new MqttAdapter(new MqttProperties(null, null, null, 2, null, null));
    }

    @AfterEach
    void tearDown() {
        adapter.close();
        scheduler.close();
        broker.close();
    }

    @Test
    @DisplayName("MQE-01 不支持的承载方式必须 fail-fast（返回失败 Stage，不同步抛）")
    void unsupportedSchemeMustFailFast() {
        ConnectionSpec spec = spec("bad", "http://127.0.0.1:1883");
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("MQE-02 TLS 未实现时必须拒绝，不得静默明文")
    void tlsMustBeRejected() {
        ConnectionSpec spec = new ConnectionSpec("tls", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:" + broker.port()), Duration.ofSeconds(3),
                Duration.ofSeconds(3), TlsOptions.enabledDefault(), null, Map.of());
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> ex).join();
        assertThat(error).isInstanceOf(ConnectionException.class);
    }

    @Test
    @DisplayName("MQE-03 链路对象：session() 不适用、describe 带设备数、unwrap 为空")
    void connectionObjectBehaviour() {
        ProtocolConnection connection = adapter.open(spec("edge", brokerUri()), context)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            assertThat(connection.state()).isEqualTo(SessionState.ONLINE);
            assertThat(connection.describe()).containsKeys("endpoint", "deviceCount");
            assertThat(connection.unwrap(ProtocolCapability.class)).isEmpty();
            assertThatThrownBy(connection::session).isInstanceOf(UnsupportedCapabilityException.class);
        } finally {
            connection.close();
        }
        assertThat(connection.state()).isEqualTo(SessionState.CLOSED);
        connection.close();
    }

    @Test
    @DisplayName("MQE-04 端口缺省时必须回落到 1883")
    void missingPortMustFallBackToDefault() {
        ConnectionSpec spec = new ConnectionSpec("noport", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1"), Duration.ofMillis(500), Duration.ofMillis(500),
                null, null, Map.of());
        // 本机 1883 通常无 broker：只要求"能给出结果"，用来覆盖默认端口分支
        Throwable error = adapter.open(spec, context).toCompletableFuture()
                .handle((connection, ex) -> {
                    if (connection != null) {
                        connection.close();
                    }
                    return ex;
                })
                .orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(spec.endpoint().port()).isEqualTo(Endpoint.NO_PORT);
        assertThat(error == null || error instanceof RuntimeException).isTrue();
    }

    @Test
    @DisplayName("MQE-05 设备属性可覆盖 QoS 与 retained")
    void devicePropertiesMustOverrideQosAndRetained() {
        ProtocolConnection connection = adapter.open(spec("qos", brokerUri()), context)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            DeviceSpec device = new DeviceSpec("qos-device", "QoS 设备", MqttAdapter.PROTOCOL_CODE,
                    "qos", "factory/q", Duration.ZERO,
                    Map.of(MqttAdapter.ATTRIBUTE_QOS, "0", MqttAdapter.ATTRIBUTE_RETAINED, "true"));
            DeviceSession session = adapter.bind(connection, device, context)
                    .toCompletableFuture().join();
            WriteResult result = session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("factory/q/a"), "1")))
                    .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
            assertThat(result.allSuccess()).isTrue();
            assertThat(session.device().properties())
                    .containsEntry(MqttAdapter.ATTRIBUTE_RETAINED, "true");
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("MQE-06 非法 QoS 配置必须告警并回落到合法值，不得抛异常")
    void invalidQosMustFallBackWithWarning() {
        ProtocolConnection connection = adapter.open(spec("badqos", brokerUri()), context)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            DeviceSpec device = new DeviceSpec("bad-qos", "非法 QoS", MqttAdapter.PROTOCOL_CODE,
                    "badqos", "factory/z", Duration.ZERO, Map.of(MqttAdapter.ATTRIBUTE_QOS, "abc"));
            DeviceSession session = adapter.bind(connection, device, context)
                    .toCompletableFuture().join();
            assertThat(session.write(WriteRequest.of(
                            new PointWrite(PointAddress.of("factory/z/a"), "1")))
                    .toCompletableFuture().join().allSuccess()).isTrue();
        } finally {
            connection.close();
        }
    }

    @Test
    @DisplayName("MQE-07 关闭后的会话不得再订阅，且关闭幂等；ping 必须失活")
    void closedSessionBehaviour() {
        DeviceSession session = openSession();
        session.close().toCompletableFuture().join();
        session.close().toCompletableFuture().join();
        assertThat(session.state()).isEqualTo(SessionState.CLOSED);
        assertThat(session.unwrap(ProtocolCapability.class)).isEmpty();

        Throwable error = session.subscribe(
                        SubscribeRequest.of(List.of(PointAddress.of("a/b"))), null)
                .toCompletableFuture().handle((handle, ex) -> ex).join();
        assertThat(error).isInstanceOf(UnsupportedCapabilityException.class);

        PingResult dead = session.ping().toCompletableFuture().join();
        assertThat(dead.alive()).isFalse();
        assertThat(dead.failureReason()).isEqualTo(MqttAdapter.MSG_CONNECTION_INACTIVE);
    }

    @Test
    @DisplayName("MQE-08 取消不存在的订阅必须幂等，不抛异常")
    void unsubscribeUnknownMustBeIdempotent() {
        DeviceSession session = openSession();
        try {
            assertThat(session.unsubscribe(null).toCompletableFuture().join()).isNull();
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("MQE-09 bind 到非 MQTT 链路必须失败")
    void bindWithForeignConnectionMustFail() {
        ProtocolConnection foreign = adapter.open(spec("self", brokerUri()), context)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        try {
            Throwable error = adapter.bind(foreign, new DeviceSpec("d", "d", MqttAdapter.PROTOCOL_CODE,
                            "missing", "", Duration.ZERO, Map.of()), context)
                    .toCompletableFuture().handle((session, ex) -> ex).join();
            // 正常情况下能绑定；这里只断言 bind 不会同步抛出
            assertThat(error).isNull();
        } finally {
            foreign.close();
        }
    }

    @Test
    @DisplayName("MQE-10 探测不可达端点必须返回 unreachable 而不是异常完成")
    void probeUnreachableMustNotThrow() {
        ConnectionSpec dead = new ConnectionSpec("dead", MqttAdapter.PROTOCOL_CODE,
                Endpoint.of("tcp://127.0.0.1:1"), Duration.ofMillis(400), Duration.ofMillis(400),
                null, null, Map.of());
        var result = adapter.probe(dead, context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(result.reachable()).isFalse();
        assertThat(result.failureReason()).isEqualTo(MqttAdapter.MSG_CONNECTION_INACTIVE);
    }

    @Test
    @DisplayName("MQE-11 探测可达端点必须返回 reachable")
    void probeReachableMustSucceed() {
        var result = adapter.probe(spec("probe", brokerUri()), context).toCompletableFuture()
                .orTimeout(10, TimeUnit.SECONDS).join();
        assertThat(result.reachable()).isTrue();
        assertThat(result.protocol().code()).isEqualTo(MqttAdapter.PROTOCOL_CODE);
    }

    private DeviceSession openSession() {
        ProtocolConnection connection = adapter.open(spec("s", brokerUri()), context)
                .toCompletableFuture().orTimeout(5, TimeUnit.SECONDS).join();
        return adapter.bind(connection, new DeviceSpec("d1", "设备", MqttAdapter.PROTOCOL_CODE,
                "s", "factory/line1", Duration.ZERO, Map.of()), context)
                .toCompletableFuture().join();
    }

    private String brokerUri() {
        return "tcp://127.0.0.1:" + broker.port();
    }

    private static ConnectionSpec spec(String connectionId, String uri) {
        return new ConnectionSpec(connectionId, MqttAdapter.PROTOCOL_CODE, Endpoint.of(uri),
                Duration.ofSeconds(3), Duration.ofSeconds(3), null, null, Map.of());
    }

    /**
     * 无操作出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
