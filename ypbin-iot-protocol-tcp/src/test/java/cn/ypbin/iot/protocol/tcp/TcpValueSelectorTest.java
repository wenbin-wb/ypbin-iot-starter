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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.Quality;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link TcpValueSelector} 的语义与失败矩阵测试。
 *
 * @author wenbin
 * @since 2026-10-12
 */
class TcpValueSelectorTest {

    private static final Instant NOW = Instant.parse("2026-10-12T00:00:00Z");

    @Test
    @DisplayName("VALUE-01 一帧多字段按各自 selector 取值")
    void selectsEachPointFromSingleFrame() {
        List<PointAddress> addresses = List.of(
                PointAddress.of("TEMP"), PointAddress.of("HUMI"),
                PointAddress.of("SEQ"), PointAddress.of("BAT"));

        List<PointValue> values = TcpValueSelector.select(addresses,
                "TEMP=23.5,HUMI=55,SEQ=12,BAT=90".getBytes(StandardCharsets.UTF_8), NOW);

        assertThat(values).hasSize(4);
        assertThat(values).allSatisfy(value -> {
            assertThat(value.quality()).isEqualTo(Quality.GOOD);
            assertThat(value.qualityReason()).isNull();
        });
        assertThat(values.get(0).address()).isEqualTo(PointAddress.of("TEMP"));
        assertThat(values.get(0).value()).isEqualTo("23.5");
        assertThat(values.get(1).value()).isEqualTo("55");
        assertThat(values.get(2).value()).isEqualTo("12");
        assertThat(values.get(3).value()).isEqualTo("90");
    }

    @Test
    @DisplayName("VALUE-02 selector 匹配大小写不敏感且两侧去空白；分号/换行也是字段分隔符")
    void matchesSelectorCaseInsensitivelyAndTrims() {
        List<PointValue> values = TcpValueSelector.select(
                List.of(PointAddress.of("temp"), PointAddress.of("SEQ")),
                " TEMP = 23.5 ; seq = 12 \r\n".getBytes(StandardCharsets.UTF_8), NOW);

        assertThat(values).extracting(PointValue::value).containsExactly("23.5", "12");
        assertThat(values).allSatisfy(value -> assertThat(value.quality()).isEqualTo(Quality.GOOD));
    }

    @Test
    @DisplayName("VALUE-03 重复键先出现者生效")
    void firstDuplicateKeyWins() {
        Map<String, String> fields = TcpValueSelector.parseFields("TEMP=1,TEMP=2");
        assertThat(fields).containsEntry("temp", "1");

        List<PointValue> values = TcpValueSelector.select(
                List.of(PointAddress.of("TEMP")), "TEMP=1,TEMP=2".getBytes(StandardCharsets.UTF_8), NOW);
        assertThat(values.get(0).value()).isEqualTo("1");
    }

    @Test
    @DisplayName("VALUE-04 未命中 selector 只让该点位 BAD，不静默丢弃，同帧其它点位照常")
    void missingSelectorYieldsBadForThatPointOnly() {
        List<PointValue> values = TcpValueSelector.select(
                List.of(PointAddress.of("TEMP"), PointAddress.of("MISSING")),
                "TEMP=23.5".getBytes(StandardCharsets.UTF_8), NOW);

        assertThat(values).hasSize(2);
        assertThat(values.get(0).quality()).isEqualTo(Quality.GOOD);
        assertThat(values.get(0).value()).isEqualTo("23.5");
        assertThat(values.get(1).address()).isEqualTo(PointAddress.of("MISSING"));
        assertThat(values.get(1).quality()).isEqualTo(Quality.BAD);
        assertThat(values.get(1).qualityReason()).isEqualTo(TcpAdapter.MSG_SELECTOR_NOT_FOUND);
        assertThat(values.get(1).value()).isNull();
    }

    @Test
    @DisplayName("VALUE-05 字段值为空白时该点位 BAD")
    void emptyFieldValueYieldsBad() {
        List<PointValue> values = TcpValueSelector.select(
                List.of(PointAddress.of("TEMP")), "TEMP=   ".getBytes(StandardCharsets.UTF_8), NOW);

        assertThat(values.get(0).quality()).isEqualTo(Quality.BAD);
        assertThat(values.get(0).qualityReason()).isEqualTo(TcpAdapter.MSG_SELECTOR_VALUE_EMPTY);
    }

    @Test
    @DisplayName("VALUE-06 空帧 / 非键值帧所有点位 BAD，不产出任何 GOOD")
    void malformedFrameYieldsBadForAll() {
        List<PointAddress> addresses = List.of(PointAddress.of("TEMP"), PointAddress.of("SEQ"));

        List<PointValue> empty = TcpValueSelector.select(addresses, new byte[0], NOW);
        assertThat(empty).hasSize(2).allSatisfy(value -> {
            assertThat(value.quality()).isEqualTo(Quality.BAD);
            assertThat(value.qualityReason()).isEqualTo(TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE);
        });

        List<PointValue> notKeyValue = TcpValueSelector.select(addresses,
                "hello world".getBytes(StandardCharsets.UTF_8), NOW);
        assertThat(notKeyValue).hasSize(2).allSatisfy(value -> {
            assertThat(value.quality()).isEqualTo(Quality.BAD);
            assertThat(value.qualityReason()).isEqualTo(TcpAdapter.MSG_PAYLOAD_NOT_KEY_VALUE);
        });
    }

    @Test
    @DisplayName("VALUE-07 非 UTF-8 帧所有点位 BAD，绝不产出 U+FFFD 脏串")
    void invalidUtf8YieldsBadWithoutReplacementCharacter() {
        List<PointValue> values = TcpValueSelector.select(
                List.of(PointAddress.of("TEMP")), new byte[]{(byte) 0xFF, (byte) 0xFE}, NOW);

        assertThat(values.get(0).quality()).isEqualTo(Quality.BAD);
        assertThat(values.get(0).qualityReason()).isEqualTo(TcpAdapter.MSG_PAYLOAD_NOT_UTF8);
        assertThat(values.get(0).value()).isNull();
    }

    @Test
    @DisplayName("VALUE-08 解析器跳过空字段/无等号字段/空键字段")
    void parserSkipsNoiseFields() {
        Map<String, String> fields = TcpValueSelector.parseFields(",=x,,A=1,A=2,KEY=,B=2");
        assertThat(fields).containsExactlyInAnyOrderEntriesOf(Map.of("a", "1", "key", "", "b", "2"));
    }

    @Test
    @DisplayName("VALUE-09 selector 语法非法必须 fail-fast（AddressParseException）")
    void invalidSelectorMustFailFast() {
        for (String invalid : List.of("bad,selector", "bad=selector", "bad;selector",
                "bad\rselector", "bad\nselector")) {
            assertThatExceptionOfType(AddressParseException.class)
                    .isThrownBy(() -> TcpValueSelector.validateSelector(PointAddress.of(invalid)))
                    .satisfies(ex -> {
                        assertThat(ex.getMessageKey()).isEqualTo(AddressParseException.MESSAGE_KEY);
                        assertThat(ex.getRawAddress()).isEqualTo(invalid);
                    });
        }
    }

    @Test
    @DisplayName("VALUE-10 常规 selector 语法合法")
    void validSelectorMustPass() {
        for (String valid : List.of("TEMP", "temp_1", "A.B", "room temp", "中文键")) {
            assertThatCode(() -> TcpValueSelector.validateSelector(PointAddress.of(valid)))
                    .doesNotThrowAnyException();
        }
    }
}
