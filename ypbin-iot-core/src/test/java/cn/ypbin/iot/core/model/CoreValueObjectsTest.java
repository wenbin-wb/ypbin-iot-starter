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
package cn.ypbin.iot.core.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 契约层值对象的边界行为测试。
 *
 * <p>重点是「集合永不返回 null」「枚举 code/desc 完备」「地址与端点的解析边界」——
 * 这些是下游协议模块每天都要依赖的契约，出错的代价会乘以协议数量。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class CoreValueObjectsTest {

    @Test
    @DisplayName("MODEL-01 ProtocolCode 必须校验格式")
    void protocolCodeMustValidateFormat() {
        assertThat(ProtocolCode.of("modbus-tcp").value()).isEqualTo("modbus-tcp");
        assertThatThrownBy(() -> ProtocolCode.of("Modbus"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtocolCode.of("1abc"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtocolCode.of(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ProtocolCode.of(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("MODEL-02 Endpoint 必须正确解析主机/端口/查询参数")
    void endpointMustParseUri() {
        Endpoint endpoint = Endpoint.of("tcp://10.0.0.1:502");
        assertThat(endpoint.scheme()).isEqualTo("tcp");
        assertThat(endpoint.host()).isEqualTo("10.0.0.1");
        assertThat(endpoint.port()).isEqualTo(502);
        assertThat(endpoint.parameters()).isEmpty();

        Endpoint serial = Endpoint.of("serial:///dev/ttyS0?baud=9600&parity=none");
        assertThat(serial.scheme()).isEqualTo("serial");
        assertThat(serial.path()).contains("/dev/ttyS0");
        assertThat(serial.parameters()).containsEntry("baud", "9600").containsEntry("parity", "none");
        assertThat(serial.port()).isEqualTo(Endpoint.NO_PORT);
    }

    @Test
    @DisplayName("MODEL-03 Endpoint 对非法 URI 必须构造期 fail-fast，不得静默接受")
    void endpointMustRejectInvalidUri() {
        // 静默接受非法 URI 会让 scheme()="" / port()=-1 一路传播到运行期，
        // 表现为「配置错了但没人知道」；必须在构造期就暴露。
        assertThatThrownBy(() -> Endpoint.of("not a uri with spaces"))
                .isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> Endpoint.of("   "))
                .isInstanceOf(AddressParseException.class);
    }

    @Test
    @DisplayName("MODEL-04 ConnectionSpec 必须填充默认超时并拒绝 null 必填项")
    void connectionSpecMustNormalizeDefaults() {
        ConnectionSpec spec = ConnectionSpec.of("c1", ProtocolCode.of("tcp"), Endpoint.of("tcp://h:1"));
        assertThat(spec.connectTimeout()).isEqualTo(ConnectionSpec.DEFAULT_CONNECT_TIMEOUT);
        assertThat(spec.requestTimeout()).isEqualTo(ConnectionSpec.DEFAULT_REQUEST_TIMEOUT);
        assertThat(spec.tls().enabled()).isFalse();
        assertThat(spec.properties()).isEmpty();
        assertThatThrownBy(() -> new ConnectionSpec(null, ProtocolCode.of("tcp"), Endpoint.of("tcp://h:1"),
                null, null, null, null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("MODEL-05 DeviceSpec 必须对可空字段归一化")
    void deviceSpecMustNormalizeOptionalFields() {
        DeviceSpec device = new DeviceSpec("d1", null, ProtocolCode.of("tcp"), null, null, null, null);
        assertThat(device.deviceName()).isEqualTo("d1");
        assertThat(device.connectionId()).isEqualTo("d1");
        assertThat(device.localAddress()).isEmpty();
        assertThat(device.pollInterval()).isEqualTo(Duration.ZERO);
        assertThat(device.properties()).isEmpty();
    }

    @Test
    @DisplayName("MODEL-06 PointValue 的 GOOD 质量必须清除原因，bad() 拒绝 GOOD")
    void pointValueMustKeepQualityConsistent() {
        PointAddress address = PointAddress.of("40001");
        PointValue good = PointValue.good(address, 42, Instant.now());
        assertThat(good.isGood()).isTrue();
        assertThat(good.qualityReason()).isNull();

        PointValue bad = PointValue.bad(address, Quality.BAD, "iot.test.reason", Instant.now());
        assertThat(bad.isGood()).isFalse();
        assertThat(bad.qualityReason()).isEqualTo("iot.test.reason");

        assertThatThrownBy(() -> PointValue.bad(address, Quality.GOOD, "x", Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MODEL-07 DataBatch 必须做不可变拷贝且 size/isEmpty 正确")
    void dataBatchMustBeImmutable() {
        PointValue value = PointValue.good(PointAddress.of("p1"), 1, Instant.now());
        DataBatch batch = new DataBatch("d1", ProtocolCode.of("tcp"), null, Instant.now(), List.of(value));
        assertThat(batch.size()).isEqualTo(1);
        assertThat(batch.isEmpty()).isFalse();
        assertThatThrownBy(() -> batch.points().add(value)).isInstanceOf(UnsupportedOperationException.class);

        // connectionId 传 null 以验证归一化
        DataBatch empty = new DataBatch("d1", ProtocolCode.of("tcp"), null, Instant.now(), null);
        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.points()).isEmpty();
        assertThat(empty.connectionId()).isEmpty();
    }

    @Test
    @DisplayName("MODEL-08 ReadRequest/WriteRequest/SubscribeRequest 必须拒绝空集合")
    void requestsMustRejectEmptyCollections() {
        assertThatThrownBy(() -> ReadRequest.of(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WriteRequest(List.of(), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SubscribeRequest.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("MODEL-09 WriteResult 必须正确区分部分失败")
    void writeResultMustTrackPartialFailure() {
        PointAddress ok = PointAddress.of("p1");
        PointAddress failed = PointAddress.of("p2");
        WriteResult result = new WriteResult(List.of(PointWriteStatus.ok(ok),
                PointWriteStatus.fail(failed, "iot.test.reason")), Duration.ofMillis(5));
        assertThat(result.allSuccess()).isFalse();
        assertThat(result.failures()).hasSize(1);
        assertThat(result.failures().get(0).address()).isEqualTo(failed);
        assertThat(PointWriteStatus.ok(ok).reason()).isNull();
    }

    @Test
    @DisplayName("MODEL-10 ReadResult 必须统计失败点位")
    void readResultMustCountFailures() {
        ReadResult result = new ReadResult(List.of(
                PointValue.good(PointAddress.of("p1"), 1, Instant.now()),
                PointValue.bad(PointAddress.of("p2"), Quality.BAD, "iot.test", Instant.now())),
                Duration.ZERO);
        assertThat(result.failureCount()).isEqualTo(1);
        assertThat(new ReadResult(null, null).values()).isEmpty();
    }

    @Test
    @DisplayName("MODEL-11 ProtocolDescriptor 必须归一化可空字段并支持能力查询")
    void protocolDescriptorMustNormalize() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("tcp"))
                .name("TCP")
                .attribute("defaultPort", "0")
                .build();
        assertThat(descriptor.capabilities()).isEmpty();
        assertThat(descriptor.extensions()).isEmpty();
        assertThat(descriptor.vendor()).isEmpty();
        assertThat(descriptor.minimumRuntimeVersion()).isEmpty();
        assertThat(descriptor.supports(ProtocolCapability.READ)).isFalse();
        assertThat(descriptor.attributes()).containsEntry("defaultPort", "0");
        assertThatThrownBy(() -> ProtocolDescriptor.builder().name("x").build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("MODEL-12 枚举必须 code/desc 完备且 fromCode 可用（禁 ordinal）")
    void enumsMustExposeCodeAndDesc() {
        for (Quality quality : Quality.values()) {
            assertThat(quality.getDesc()).isNotBlank();
            assertThat(Quality.fromCode(quality.getCode())).contains(quality);
        }
        assertThat(Quality.fromCode(999)).isEmpty();
        assertThat(SessionState.ONLINE.isUsable()).isTrue();
        assertThat(SessionState.DEGRADED.isUsable()).isTrue();
        assertThat(SessionState.CLOSED.isUsable()).isFalse();
        assertThat(SessionState.fromCode(SessionState.CLOSED.getCode())).contains(SessionState.CLOSED);
        assertThat(CloseCause.fromCode(CloseCause.TIMEOUT.getCode())).contains(CloseCause.TIMEOUT);
        assertThat(DeviceEventType.fromCode(DeviceEventType.DEVICE_ONLINE.getCode()))
                .contains(DeviceEventType.DEVICE_ONLINE);
        assertThat(LogLevel.fromCode(0))
                .contains(LogLevel.DEBUG);
    }

    @Test
    @DisplayName("MODEL-13 ProbeResult/PingResult 成功时必须清除失败原因")
    void resultsMustClearFailureReasonOnSuccess() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(ProtocolCode.of("tcp")).name("TCP").build();
        ProbeResult reachable = ProbeResult.reachable(descriptor, Map.of("k", "v"));
        assertThat(reachable.reachable()).isTrue();
        assertThat(reachable.failureReason()).isNull();
        assertThat(reachable.details()).containsEntry("k", "v");

        ProbeResult unreachable = ProbeResult.unreachable(descriptor, "iot.test.reason");
        assertThat(unreachable.reachable()).isFalse();
        assertThat(unreachable.failureReason()).isEqualTo("iot.test.reason");

        assertThat(PingResult.alive(1L).failureReason()).isNull();
        assertThat(PingResult.dead("iot.test").alive()).isFalse();
    }

    @Test
    @DisplayName("MODEL-14 集合字段必须不可变（防御性拷贝）")
    void collectionFieldsMustBeImmutable() {
        ConnectionSpec spec = new ConnectionSpec("c1", ProtocolCode.of("tcp"), Endpoint.of("tcp://h:1"),
                null, null, null, null, Map.of("a", "b"));
        assertThatThrownBy(() -> spec.properties().put("c", "d"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
