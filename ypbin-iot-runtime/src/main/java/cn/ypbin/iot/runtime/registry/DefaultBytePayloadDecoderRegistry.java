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
package cn.ypbin.iot.runtime.registry;

import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.DecodeContext;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 字节载荷解码器注册表的默认实现：持有<b>已按确定顺序排好</b>的解码器列表，选择第一个认领者。
 *
 * <p><b>为什么排序不在本类做</b>：顺序来自装配层的 {@code @Order}/{@code Ordered}，
 * 而那是 Spring 类型（{@code io.micrometer}、{@code org.springframework}）——
 * 本模块（{@code iot-runtime}）必须零 Spring 依赖（ArchUnit 强制）。因此装配层先排好序，
 * 本类只做「持有 + 顺序遍历」。</p>
 *
 * <p><b>去重</b>：同一解码器实例被重复登记（宿主同时声明了 bean 与手动注册）不应导致
 * 「同一个解码器被问两次」；这里按实例身份去重并<b>保留首次出现的位置</b>，
 * 不改变顺序语义。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
public final class DefaultBytePayloadDecoderRegistry implements BytePayloadDecoderRegistry {

    private final List<BytePayloadDecoder> decoders;

    /**
     * 创建注册表。
     *
     * @param decoders 已按选择顺序排好的解码器；允许 {@code null}（等价于空注册表）
     */
    public DefaultBytePayloadDecoderRegistry(List<BytePayloadDecoder> decoders) {
        if (decoders == null || decoders.isEmpty()) {
            this.decoders = List.of();
            return;
        }
        LinkedHashSet<BytePayloadDecoder> unique = new LinkedHashSet<>();
        for (BytePayloadDecoder decoder : decoders) {
            unique.add(Objects.requireNonNull(decoder, "decoder must not be null"));
        }
        this.decoders = List.copyOf(unique);
    }

    @Override
    public Optional<BytePayloadDecoder> find(DecodeContext context) {
        if (context == null) {
            return Optional.empty();
        }
        for (BytePayloadDecoder decoder : decoders) {
            if (decoder.supports(context)) {
                return Optional.of(decoder);
            }
        }
        return Optional.empty();
    }

    @Override
    public List<BytePayloadDecoder> decoders() {
        return decoders;
    }
}
