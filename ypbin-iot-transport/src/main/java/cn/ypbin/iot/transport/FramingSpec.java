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

import java.util.Arrays;
import java.util.Objects;

/**
 * 帧定界规格。
 *
 * <p><b>{@code maxFrameLength} 是安全边界，不是可选项</b>：长度字段来自对端，
 * 不设上限意味着一个恶意或错误的长度值就能把进程打 OOM。</p>
 *
 * @param mode              定界模式
 * @param maxFrameLength    单帧最大字节数
 * @param lengthFieldOffset 长度字段偏移（LENGTH_FIELD 模式）
 * @param lengthFieldLength 长度字段字节数（LENGTH_FIELD 模式）
 * @param lengthAdjustment  长度修正值（LENGTH_FIELD 模式）
 * @param initialBytesToStrip 解码后跳过的字节数（LENGTH_FIELD 模式）
 * @param delimiter         分隔符（DELIMITER 模式）
 * @author wenbin
 * @since 2026-09-13
 */
public record FramingSpec(
        FramingMode mode,
        int maxFrameLength,
        int lengthFieldOffset,
        int lengthFieldLength,
        int lengthAdjustment,
        int initialBytesToStrip,
        byte[] delimiter) {

    /** 默认单帧上限：64 KB。 */
    public static final int DEFAULT_MAX_FRAME_LENGTH = 64 * 1024;

    /**
     * 紧凑构造器：校验必填项并做防御性拷贝。
     */
    public FramingSpec {
        Objects.requireNonNull(mode, "mode must not be null");
        if (maxFrameLength <= 0) {
            throw new IllegalArgumentException("maxFrameLength must be positive: " + maxFrameLength);
        }
        if (mode == FramingMode.LENGTH_FIELD && lengthFieldLength != 1 && lengthFieldLength != 2
                && lengthFieldLength != 3 && lengthFieldLength != 4 && lengthFieldLength != 8) {
            throw new IllegalArgumentException(
                    "lengthFieldLength must be 1, 2, 3, 4 or 8 but was " + lengthFieldLength);
        }
        if (mode == FramingMode.DELIMITER && (delimiter == null || delimiter.length == 0)) {
            throw new IllegalArgumentException("delimiter must not be empty in DELIMITER mode");
        }
        delimiter = delimiter == null ? new byte[0] : delimiter.clone();
    }

    /**
     * 不定界规格。
     *
     * @return 规格
     */
    public static FramingSpec none() {
        return new FramingSpec(FramingMode.NONE, DEFAULT_MAX_FRAME_LENGTH, 0, 0, 0, 0, new byte[0]);
    }

    /**
     * 长度字段定界规格（1 字节长度、不跳过长度头）。
     *
     * @param maxFrameLength 单帧上限
     * @return 规格
     */
    public static FramingSpec lengthField1Byte(int maxFrameLength) {
        return new FramingSpec(FramingMode.LENGTH_FIELD, maxFrameLength, 0, 1, 0, 0, new byte[0]);
    }

    /**
     * 分隔符定界规格。
     *
     * @param delimiter      分隔符
     * @param maxFrameLength 单帧上限
     * @return 规格
     */
    public static FramingSpec delimiter(byte[] delimiter, int maxFrameLength) {
        return new FramingSpec(FramingMode.DELIMITER, maxFrameLength, 0, 0, 0, 0, delimiter);
    }

    /**
     * 分隔符字节副本。
     *
     * @return 副本
     */
    @Override
    public byte[] delimiter() {
        return delimiter.clone();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FramingSpec spec)) {
            return false;
        }
        return maxFrameLength == spec.maxFrameLength
                && lengthFieldOffset == spec.lengthFieldOffset
                && lengthFieldLength == spec.lengthFieldLength
                && lengthAdjustment == spec.lengthAdjustment
                && initialBytesToStrip == spec.initialBytesToStrip
                && mode == spec.mode
                && Arrays.equals(delimiter, spec.delimiter);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(mode, maxFrameLength, lengthFieldOffset, lengthFieldLength,
                lengthAdjustment, initialBytesToStrip);
        return 31 * result + Arrays.hashCode(delimiter);
    }

    @Override
    public String toString() {
        return "FramingSpec[mode=" + mode + ", maxFrameLength=" + maxFrameLength + "]";
    }
}
