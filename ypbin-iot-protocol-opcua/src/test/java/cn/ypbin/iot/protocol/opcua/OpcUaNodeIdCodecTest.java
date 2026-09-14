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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import cn.ypbin.iot.core.exception.AddressParseException;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OPC UA NodeId 解析测试。
 *
 * <p>重点：只接受标准写法、不做猜测。裸标识符（如 {@code Temperature}）必须被拒绝——
 * 命名空间索引是 NodeId 的组成部分，猜错的表现是「解析成功但读到 BadNodeIdUnknown」。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class OpcUaNodeIdCodecTest {

    @Test
    @DisplayName("NID-01 带命名空间的三种标识类型都必须解析正确")
    void mustParseNamespacedForms() {
        NodeId string = OpcUaNodeIdCodec.parse("ns=2;s=Device.Temperature");
        assertThat(string.getNamespaceIndex().intValue()).isEqualTo(2);
        assertThat(string.getIdentifier()).isInstanceOf(String.class);
        assertThat(string.getIdentifier()).isEqualTo("Device.Temperature");

        NodeId numeric = OpcUaNodeIdCodec.parse("ns=3;i=1234");
        assertThat(numeric.getNamespaceIndex().intValue()).isEqualTo(3);
        assertThat(String.valueOf(numeric.getIdentifier())).isEqualTo("1234");

        NodeId guid = OpcUaNodeIdCodec.parse("ns=4;g=8f01e5f0-1a2b-4c3d-9e8f-0a1b2c3d4e5f");
        assertThat(guid.getNamespaceIndex().intValue()).isEqualTo(4);
        assertThat(OpcUaNodeIdCodec.parse("ns=5;b=AAEC").getNamespaceIndex().intValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("NID-02 省略命名空间时按 ns=0（标准地址空间）解析")
    void mustDefaultToNamespaceZero() {
        NodeId numeric = OpcUaNodeIdCodec.parse("i=2258");
        assertThat(numeric.getNamespaceIndex().intValue()).isZero();
        NodeId string = OpcUaNodeIdCodec.parse("s=MyVariable");
        assertThat(string.getNamespaceIndex().intValue()).isZero();
    }

    @Test
    @DisplayName("NID-03 裸标识符必须被拒绝（不得猜测命名空间）")
    void bareIdentifierMustBeRejected() {
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("Temperature"))
                .isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("Device.Temperature"))
                .isInstanceOf(AddressParseException.class);
    }

    @Test
    @DisplayName("NID-04 非法输入必须 fail-fast 且带上下文")
    void invalidInputMustFailFast() {
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("   ")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse(null)).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("ns=abc;s=x"))
                .isInstanceOf(AddressParseException.class);

        AddressParseException ex = catchThrowableOfType(
                () -> OpcUaNodeIdCodec.parse("Temperature"), AddressParseException.class);
        assertThat(ex.getProtocol()).isEqualTo(OpcUaAdapter.PROTOCOL_CODE);
        assertThat(ex.getRawAddress()).isEqualTo("Temperature");
        assertThat(ex.getMessageKey()).isNotBlank();
    }

    @Test
    @DisplayName("NID-06 空标识符必须被拒绝（会被解析成无意义 NodeId）")
    void emptyIdentifierMustBeRejected() {
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("ns=2;s="))
                .as("空标识符解析成功但读回 BadNodeIdUnknown，比直接报错难排查")
                .isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("s=")).isInstanceOf(AddressParseException.class);
        assertThatThrownBy(() -> OpcUaNodeIdCodec.parse("i=")).isInstanceOf(AddressParseException.class);
    }

    @Test
    @DisplayName("NID-05 格式化必须可往返")
    void formatMustRoundTrip() {
        NodeId nodeId = OpcUaNodeIdCodec.parse("ns=2;s=Device.Temperature");
        String formatted = OpcUaNodeIdCodec.format(nodeId);
        assertThat(OpcUaNodeIdCodec.parse(formatted)).isEqualTo(nodeId);
    }
}
