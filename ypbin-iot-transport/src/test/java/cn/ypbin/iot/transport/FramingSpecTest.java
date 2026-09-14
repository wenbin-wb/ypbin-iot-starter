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
package cn.ypbin.iot.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 帧定界规格测试。
 *
 * <p>本模块是 Netty 底座，此前<b>零测试</b>且被 JaCoCo 静默跳过（无 {@code jacoco.exec}），
 * 等于覆盖门禁在这里空转。本类把可纯单元验证的部分补上。</p>
 *
 * <p>重点：帧定界参数写错的后果是「收到半个包」或「粘包」，两者都表现为业务层解析出垃圾数据，
 * 因此构造期校验必须严格。</p>
 *
 * @author wenbin
 * @since 2026-09-14
 */
class FramingSpecTest {

    @Test
    @DisplayName("FRAME-01 不定界规格必须使用默认帧长且无分隔符")
    void noneMustUseDefaults() {
        FramingSpec spec = FramingSpec.none();
        assertThat(spec.mode()).isEqualTo(FramingMode.NONE);
        assertThat(spec.maxFrameLength()).isEqualTo(FramingSpec.DEFAULT_MAX_FRAME_LENGTH);
        assertThat(spec.delimiter()).isEmpty();
    }

    @Test
    @DisplayName("FRAME-02 长度字段规格必须携带长度字段参数")
    void lengthFieldMustCarryParameters() {
        FramingSpec spec = FramingSpec.lengthField1Byte(1024);
        assertThat(spec.mode()).isEqualTo(FramingMode.LENGTH_FIELD);
        assertThat(spec.lengthFieldLength()).isEqualTo(1);
        assertThat(spec.maxFrameLength()).isEqualTo(1024);
    }

    @Test
    @DisplayName("FRAME-03 分隔符规格必须做防御性拷贝（外部改动不得影响已构造的规格）")
    void delimiterMustBeDefensivelyCopied() {
        byte[] delimiter = {'\r', '\n'};
        FramingSpec spec = FramingSpec.delimiter(delimiter, 512);
        delimiter[0] = 'X';
        assertThat(spec.delimiter()).as("构造后修改入参数组不得影响规格").containsExactly('\r', '\n');
        byte[] exposed = spec.delimiter();
        exposed[0] = 'Y';
        assertThat(spec.delimiter()).as("访问器返回的数组也不得能改到内部状态").containsExactly('\r', '\n');
    }

    @Test
    @DisplayName("FRAME-04 非法参数必须构造期 fail-fast")
    void invalidParametersMustFailFast() {
        assertThatThrownBy(() -> new FramingSpec(FramingMode.NONE, 0, 0, 0, 0, 0, null))
                .as("帧长必须为正")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FramingSpec(FramingMode.LENGTH_FIELD, 1024, 0, 5, 0, 0, null))
                .as("长度字段长度只允许 1/2/3/4/8")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FramingSpec(FramingMode.DELIMITER, 1024, 0, 0, 0, 0, new byte[0]))
                .as("分隔符模式必须给出非空分隔符")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new FramingSpec(null, 1024, 0, 0, 0, 0, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("FRAME-05 相等性必须按值比较（含分隔符内容）")
    void equalityMustBeValueBased() {
        FramingSpec first = FramingSpec.delimiter(new byte[] {'\n'}, 100);
        FramingSpec same = FramingSpec.delimiter(new byte[] {'\n'}, 100);
        FramingSpec other = FramingSpec.delimiter(new byte[] {'\r'}, 100);
        assertThat(first).isEqualTo(same).hasSameHashCodeAs(same);
        assertThat(first).as("分隔符不同即不等——record 默认的数组比较是引用比较，必须覆写").isNotEqualTo(other);
        assertThat(first.toString()).contains("DELIMITER");
    }

    @Test
    @DisplayName("FRAME-06 帧定界模式必须带 code 与 desc（AGENTS R13）")
    void framingModeMustCarryCodeAndDesc() {
        assertThat(FramingMode.NONE.getCode()).isZero();
        assertThat(FramingMode.values()).hasSize(3);
        for (FramingMode mode : FramingMode.values()) {
            assertThat(mode.getDesc()).isNotBlank();
            assertThat(FramingMode.fromCode(mode.getCode())).contains(mode);
        }
        assertThat(FramingMode.fromCode(999)).isEmpty();
    }
}
