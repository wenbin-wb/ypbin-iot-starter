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
package cn.ypbin.iot.protocol.tcp.autoconfigure;

import cn.ypbin.iot.transport.FramingMode;
import cn.ypbin.iot.transport.FramingSpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * TCP 透传协议配置。
 *
 * @param enabled            协议开关
 * @param framingMode        帧定界模式
 * @param maxFrameLength     单帧最大字节数
 * @param lengthFieldOffset  长度字段偏移
 * @param lengthFieldLength  长度字段字节数
 * @param lengthAdjustment   长度修正值
 * @param initialBytesToStrip 解码后跳过的字节数
 * @param delimiter          分隔符（DELIMITER 模式）
 * @param idleInterval       空闲检测间隔
 * @param workerThreads      Netty worker 线程数
 * @author wenbin
 * @since 2026-09-13
 */
@ConfigurationProperties(prefix = TcpProperties.PREFIX)
public record TcpProperties(
        Boolean enabled,
        FramingMode framingMode,
        Integer maxFrameLength,
        Integer lengthFieldOffset,
        Integer lengthFieldLength,
        Integer lengthAdjustment,
        Integer initialBytesToStrip,
        String delimiter,
        Duration idleInterval,
        Integer workerThreads) {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.iot.protocol.tcp";

    /**
     * 紧凑构造器：归一化默认值，保证开箱即用。
     */
    public TcpProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        framingMode = framingMode == null ? FramingMode.NONE : framingMode;
        maxFrameLength = maxFrameLength == null || maxFrameLength <= 0
                ? FramingSpec.DEFAULT_MAX_FRAME_LENGTH : maxFrameLength;
        lengthFieldOffset = lengthFieldOffset == null ? 0 : lengthFieldOffset;
        lengthFieldLength = lengthFieldLength == null ? 1 : lengthFieldLength;
        lengthAdjustment = lengthAdjustment == null ? 0 : lengthAdjustment;
        initialBytesToStrip = initialBytesToStrip == null ? 0 : initialBytesToStrip;
        idleInterval = idleInterval == null ? Duration.ZERO : idleInterval;
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 构造帧定界规格。
     *
     * @return 规格
     */
    public FramingSpec toFramingSpec() {
        if (framingMode == FramingMode.DELIMITER) {
            return FramingSpec.delimiter(delimiterBytes(), maxFrameLength);
        }
        if (framingMode == FramingMode.LENGTH_FIELD) {
            return new FramingSpec(FramingMode.LENGTH_FIELD, maxFrameLength, lengthFieldOffset,
                    lengthFieldLength, lengthAdjustment, initialBytesToStrip, new byte[0]);
        }
        return FramingSpec.none();
    }

    /**
     * 分隔符字节（UTF-8）。
     *
     * @return 字节数组
     */
    private byte[] delimiterBytes() {
        return delimiter == null ? new byte[0] : delimiter.getBytes(StandardCharsets.UTF_8);
    }
}
