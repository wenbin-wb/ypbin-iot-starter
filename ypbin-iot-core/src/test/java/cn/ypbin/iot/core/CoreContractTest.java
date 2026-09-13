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
package cn.ypbin.iot.core;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.exception.ConnectionException;
import cn.ypbin.iot.core.exception.IotException;
import cn.ypbin.iot.core.exception.ProtocolException;
import cn.ypbin.iot.core.exception.ProtocolTimeoutException;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.BrowseNode;
import cn.ypbin.iot.core.model.BrowseRequest;
import cn.ypbin.iot.core.model.CloseCause;
import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.DeviceEventType;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.ProtocolCapabilityAlias;
import cn.ypbin.iot.core.model.Quality;
import cn.ypbin.iot.core.model.TlsOptions;
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.spi.ChangeType;
import cn.ypbin.iot.core.spi.DeviceChange;
import cn.ypbin.iot.core.spi.ValidationResult;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 契约层异常、扩展接口与校验结果的测试。
 *
 * <p>重点验证 i18n 消息键契约：异常携带的是<b>消息键</b>而不是格式化文案，
 * 且 {@code getMessage()} 只是便于日志排查的调试形式。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class CoreContractTest {

    private static final ProtocolCode CODE = ProtocolCode.of("tck");

    @Test
    @DisplayName("EX-01 根异常必须携带消息键与参数，且 getMessage 不展示给用户")
    void rootExceptionMustCarryMessageKey() {
        IotException exception = new IotException("iot.test.key", "a", 1);
        assertThat(exception.getMessageKey()).isEqualTo("iot.test.key");
        assertThat(exception.getMessageArgs()).containsExactly("a", 1);
        assertThat(exception.getMessage()).contains("iot.test.key").contains("a");

        IotException noArgs = new IotException("iot.test.plain");
        assertThat(noArgs.getMessageArgs()).isEmpty();
        assertThat(noArgs.getMessage()).isEqualTo("iot.test.plain");

        IllegalStateException cause = new IllegalStateException("root");
        IotException withCause = new IotException(cause, "iot.test.cause", 1);
        assertThat(withCause.getCause()).isSameAs(cause);
        assertThat(withCause.getMessageKey()).isEqualTo("iot.test.cause");
    }

    @Test
    @DisplayName("EX-02 消息参数必须做防御性拷贝")
    void messageArgsMustBeCopied() {
        Object[] args = {"x"};
        IotException exception = new IotException("iot.test", args);
        args[0] = "mutated";
        assertThat(exception.getMessageArgs()).containsExactly("x");
    }

    @Test
    @DisplayName("EX-03 各子类必须保留自身语义字段")
    void subclassesMustExposeDomainFields() {
        ConnectionException connection = new ConnectionException("c1", IotMessageKeys.CONNECTION_FAILED);
        assertThat(connection.getConnectionId()).isEqualTo("c1");
        assertThat(connection.getMessageKey()).isEqualTo(IotMessageKeys.CONNECTION_FAILED);

        ConnectionException withCause = new ConnectionException("c2", new RuntimeException("x"),
                IotMessageKeys.CONNECTION_FAILED);
        assertThat(withCause.getCause()).isNotNull();

        ProtocolException protocol = new ProtocolException(IotMessageKeys.PROTOCOL_ERROR);
        assertThat(protocol.getMessageKey()).isEqualTo(IotMessageKeys.PROTOCOL_ERROR);
        assertThat(new ProtocolException(new RuntimeException("x"), IotMessageKeys.PROTOCOL_ERROR).getCause())
                .isNotNull();

        ProtocolTimeoutException timeout =
                new ProtocolTimeoutException("d1", "40001", Duration.ofSeconds(5));
        assertThat(timeout.getDeviceId()).isEqualTo("d1");
        assertThat(timeout.getAddress()).isEqualTo("40001");
        assertThat(timeout.getTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(timeout.getMessageKey()).isEqualTo(IotMessageKeys.PROTOCOL_TIMEOUT);

        AddressParseException address =
                new AddressParseException(CODE, "bad-address", "not numeric");
        assertThat(address.getProtocol()).isEqualTo(CODE);
        assertThat(address.getRawAddress()).isEqualTo("bad-address");

        UnsupportedCapabilityException unsupported =
                new UnsupportedCapabilityException(CODE, "read");
        assertThat(unsupported.getProtocol()).isEqualTo(CODE);
        assertThat(unsupported.getOperation()).isEqualTo("read");
        assertThat(unsupported.getMessageKey()).isEqualTo(IotMessageKeys.CAPABILITY_UNSUPPORTED);
    }

    @Test
    @DisplayName("MSG-01 消息键常量必须符合 iot.<protocol>.<category>.<detail> 规范")
    void messageKeysMustFollowConvention() {
        assertThat(IotMessageKeys.CONNECTION_TIMEOUT).startsWith("iot.common.connection.");
        assertThat(IotMessageKeys.PROTOCOL_TIMEOUT).startsWith("iot.common.protocol.");
        assertThat(IotMessageKeys.ADDRESS_PARSE_FAILED).startsWith("iot.common.address.");
        assertThat(IotMessageKeys.CAPABILITY_UNSUPPORTED).startsWith("iot.common.capability.");
        assertThat(IotMessageKeys.CONFIG_INVALID).startsWith("iot.common.config.");
    }

    @Test
    @DisplayName("EXT-01 扩展接口必须与 BROWSE 能力成对声明")
    void browseExtensionMustPairWithCapability() {
        ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                .code(CODE)
                .name("TCK")
                .capabilities(ProtocolCapability.BROWSE)
                .extensions(BrowseExtension.class)
                .build();
        assertThat(descriptor.supports(ProtocolCapability.BROWSE)).isTrue();
        assertThat(descriptor.supportsExtension(BrowseExtension.class)).isTrue();
        assertThat(descriptor.supportsExtension(ProtocolCapabilityAlias.class)).isFalse();
        assertThat(ProtocolCapabilityAlias.class).isAssignableTo(BrowseExtension.class);
    }

    @Test
    @DisplayName("EXT-02 BrowseRequest/BrowseNode 必须归一化默认值与可空字段")
    void browseTypesMustNormalize() {
        BrowseRequest request = BrowseRequest.of("ns=2;s=Root");
        assertThat(request.maxDepth()).isEqualTo(BrowseRequest.DEFAULT_MAX_DEPTH);
        assertThat(request.maxNodes()).isEqualTo(BrowseRequest.DEFAULT_MAX_NODES);

        BrowseRequest custom = new BrowseRequest("", -1, 0);
        assertThat(custom.maxDepth()).isEqualTo(BrowseRequest.DEFAULT_MAX_DEPTH);
        assertThat(custom.maxNodes()).isEqualTo(BrowseRequest.DEFAULT_MAX_NODES);

        BrowseNode node = new BrowseNode(PointAddress.of("ns=2;s=A"), null, null, true, null);
        assertThat(node.displayName()).isEqualTo("ns=2;s=A");
        assertThat(node.nodeClass()).isEmpty();
        assertThat(node.attributes()).isEmpty();
        assertThat(node.browseable()).isTrue();
    }

    @Test
    @DisplayName("TLS-01 TlsOptions 默认与便捷工厂")
    void tlsOptionsMustProvideDefaults() {
        TlsOptions disabled = TlsOptions.disabled();
        assertThat(disabled.enabled()).isFalse();
        assertThat(disabled.protocol()).isEqualTo(TlsOptions.DEFAULT_PROTOCOL);
        assertThat(disabled.keystoreRef()).isEmpty();

        TlsOptions enabled = TlsOptions.enabledDefault();
        assertThat(enabled.enabled()).isTrue();

        TlsOptions normalized = new TlsOptions(true, null, null, null, false, null);
        assertThat(normalized.protocol()).isEqualTo(TlsOptions.DEFAULT_PROTOCOL);
        assertThat(normalized.insecureSkipVerify()).isFalse();
    }

    @Test
    @DisplayName("SPI-01 ValidationResult 通过时必须清空原因")
    void validationResultMustClearReasonsOnSuccess() {
        ValidationResult ok = ValidationResult.ok();
        assertThat(ok.passed()).isTrue();
        assertThat(ok.reasons()).isEmpty();

        ValidationResult failed = ValidationResult.fail("iot.test.a", "iot.test.b");
        assertThat(failed.passed()).isFalse();
        assertThat(failed.reasons()).containsExactly("iot.test.a", "iot.test.b");
        assertThat(ValidationResult.fail(List.of()).passed()).isFalse();
    }

    @Test
    @DisplayName("SPI-02 DeviceChange 必须携带 revision 并校验必填项")
    void deviceChangeMustCarryRevision() {
        DeviceSpec device = new DeviceSpec("d1", "设备", CODE, "c1", "", Duration.ZERO, Map.of());
        DeviceChange change = new DeviceChange(ChangeType.UPDATE, device, 7L);
        assertThat(change.revision()).isEqualTo(7L);
        assertThat(change.type()).isEqualTo(ChangeType.UPDATE);
        assertThat(ChangeType.fromCode(ChangeType.REMOVE.getCode())).contains(ChangeType.REMOVE);
        assertThat(ChangeType.fromCode(99)).isEmpty();
        assertThat(ChangeType.ADD.getDesc()).isNotBlank();
    }

    @Test
    @DisplayName("EVENT-01 DeviceEvent 必须携带消息键与时刻")
    void deviceEventMustBeWellFormed() {
        DeviceEvent event = DeviceEvent.of("d1", CODE,
                DeviceEventType.DEVICE_OFFLINE,
                IotMessageKeys.CONNECTION_CLOSED, Instant.now());
        assertThat(event.deviceId()).isEqualTo("d1");
        assertThat(event.messageKey()).isEqualTo(IotMessageKeys.CONNECTION_CLOSED);
        assertThat(event.cause()).isNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    @DisplayName("CLOSE-01 CloseReason 便捷工厂必须标为主动关闭")
    void closeReasonMustMarkClientRequest() {
        CloseReason reason = CloseReason.clientRequest(Instant.now());
        assertThat(reason.cause()).isEqualTo(CloseCause.CLIENT_REQUEST);
        assertThat(reason.message()).isEmpty();
        assertThat(reason.error()).isNull();
    }

    @Test
    @DisplayName("QUALITY-01 非 GOOD 点位必须带原因，GOOD 点位不应带原因")
    void qualityReasonMustBeConsistent() {
        PointValue good = new PointValue(PointAddress.of("p"), 1, Quality.GOOD, Instant.now(), "ignored");
        assertThat(good.qualityReason()).as("GOOD 质量必须清空原因").isNull();
        assertThat(good.isGood()).isTrue();
    }
}
