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
package cn.ypbin.iot.protocol.tcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * TCP 载荷编解码测试。
 *
 * <p>重点：不做隐式转换（数字不会被猜测字节序）、非法十六进制必须显式失败。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
class TcpPayloadCodecTest {

    @Test
    @DisplayName("CODEC-01 byte[] 原样传递")
    void byteArrayMustPassThrough() {
        byte[] payload = {1, 2, 3};
        TcpPayloadCodec.Encoded encoded = TcpPayloadCodec.encode(payload);
        assertThat(encoded.success()).isTrue();
        assertThat(encoded.payload()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("CODEC-02 字符串按 UTF-8 编码")
    void stringMustUseUtf8() {
        TcpPayloadCodec.Encoded encoded = TcpPayloadCodec.encode("AB");
        assertThat(encoded.success()).isTrue();
        assertThat(new String(encoded.payload(), StandardCharsets.UTF_8)).isEqualTo("AB");
        assertThat(TcpPayloadCodec.encode("中文").payload())
                .isEqualTo("中文".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("CODEC-03 hex: 前缀按十六进制解析（允许空格）")
    void hexPrefixMustBeDecoded() {
        TcpPayloadCodec.Encoded encoded = TcpPayloadCodec.encode("hex:01 0A FF");
        assertThat(encoded.success()).isTrue();
        assertThat(encoded.payload()).containsExactly(1, 10, -1);
    }

    @Test
    @DisplayName("CODEC-04 非法十六进制必须显式失败")
    void invalidHexMustFail() {
        assertThat(TcpPayloadCodec.encode("hex:0").messageKey())
                .isEqualTo(TcpPayloadCodec.MSG_HEX_INVALID);
        assertThat(TcpPayloadCodec.encode("hex:ZZ").messageKey())
                .isEqualTo(TcpPayloadCodec.MSG_HEX_INVALID);
        assertThat(TcpPayloadCodec.encode("hex:").messageKey())
                .isEqualTo(TcpPayloadCodec.MSG_HEX_INVALID);
    }

    @Test
    @DisplayName("CODEC-05 不支持的载荷类型必须显式失败，不做隐式转换")
    void unsupportedTypeMustFail() {
        assertThat(TcpPayloadCodec.encode(42).messageKey())
                .isEqualTo(TcpPayloadCodec.MSG_PAYLOAD_UNSUPPORTED);
        assertThat(TcpPayloadCodec.encode(null).messageKey())
                .isEqualTo(TcpPayloadCodec.MSG_PAYLOAD_UNSUPPORTED);
        assertThat(TcpPayloadCodec.encode(3.14D).success()).isFalse();
    }

    @Test
    @DisplayName("CODEC-06 Encoded 工厂方法必须保持一致性")
    void encodedFactoriesMustBeConsistent() {
        TcpPayloadCodec.Encoded ok = TcpPayloadCodec.Encoded.ok(new byte[] {1});
        assertThat(ok.success()).isTrue();
        assertThat(ok.messageKey()).isNull();

        TcpPayloadCodec.Encoded failure = TcpPayloadCodec.Encoded.failure("iot.test");
        assertThat(failure.success()).isFalse();
        assertThat(failure.payload()).isNull();
    }
}
