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
package cn.ypbin.iot.core.spi;

import java.util.List;
import java.util.Optional;

/**
 * 字节载荷解码器注册表：把若干 {@link BytePayloadDecoder} 收在一处并按<b>确定顺序</b>选择。
 *
 * <p><b>为什么需要注册表而不是让调用方遍历 Bean</b>：选择逻辑（顺序 + {@code supports} 判定）
 * 必须只有一处实现（SSOT）。散落在各协议模块里会出现「TCP 认第一个、MQTT 认最后一个」
 * 这类不一致，且新增解码器时要改多处。</p>
 *
 * <p><b>顺序契约</b>：装配层先用 Spring 的 {@code @Order}/{@code Ordered}（{@code orderedStream()}）
 * 排好序再传入本注册表；{@link #find(DecodeContext)} 返回<b>第一个</b>认领的解码器。</p>
 *
 * <p><b>同序（都没标 {@code @Order} 或标了相同值）时按 Spring 的 Bean 注册顺序</b>，本注册表
 * <b>不做</b>二次排序。原因：{@code @Order} 可以写在 {@code @Bean} <b>方法</b>上，此时顺序元数据
 * 不在解码器实例里；若拿实例再比较一次，所有「方法级 {@code @Order}」都会被当成同序而被打乱
 * （本仓实测踩过：{@code @Order(1)} 的解码器被排到了 {@code @Order(2)} 后面）。
 * 因此需要确定顺序的宿主请使用 {@code @Order} 或 {@code Ordered} 接口，而不是依赖 {@link
 * BytePayloadDecoder#id()} 的字典序。</p>
 *
 * <p><b>空注册表</b>：{@link #empty()} 表示「宿主没有注册任何解码器」，此时
 * {@link #find(DecodeContext)} 恒为空 ⇒ 调用方按「没有本 SPI 时」的行为处理（原样交付字节），
 * 不静默、不改写值。</p>
 *
 * @author wenbin
 * @since 2026-10-17
 */
public interface BytePayloadDecoderRegistry {

    /**
     * 选择第一个认领该点位的解码器。
     *
     * @param context 点位/设备上下文
     * @return 命中的解码器；没有解码器认领时返回空
     */
    Optional<BytePayloadDecoder> find(DecodeContext context);

    /**
     * 已注册的解码器（只读、按选择顺序）。
     *
     * @return 解码器列表；未注册时返回空列表，不得返回 {@code null}
     */
    List<BytePayloadDecoder> decoders();

    /**
     * 是否为空注册表（未注册任何解码器）。
     *
     * @return 无解码器返回 {@code true}
     */
    default boolean isEmpty() {
        return decoders().isEmpty();
    }

    /**
     * 空注册表：未注册任何解码器时的默认实现。
     *
     * @return 空注册表
     */
    static BytePayloadDecoderRegistry empty() {
        return EmptyRegistry.INSTANCE;
    }

    /**
     * 空注册表单例。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    enum EmptyRegistry implements BytePayloadDecoderRegistry {

        /** 单例。 */
        INSTANCE;

        @Override
        public Optional<BytePayloadDecoder> find(DecodeContext context) {
            return Optional.empty();
        }

        @Override
        public List<BytePayloadDecoder> decoders() {
            return List.of();
        }
    }
}
