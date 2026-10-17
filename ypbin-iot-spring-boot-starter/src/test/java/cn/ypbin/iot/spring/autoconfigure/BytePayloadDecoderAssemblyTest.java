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
package cn.ypbin.iot.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.DecodeContext;
import cn.ypbin.iot.core.spi.DecodeOutcome;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;

/**
 * 字节载荷解码 SPI 的装配测试（issue #30）。
 *
 * <p>issue #30 的验收标准是「宿主只需声明一个 {@code @Bean BytePayloadDecoder} 即可扩展，
 * 无需修改底层接入层核心链路」。因此这里必须证明<b>三件真实发生的事</b>：</p>
 * <ol>
 *   <li>默认（不注册解码器）也能启动，且注册表为空 —— 不能因为新增 SPI 而让既有宿主启动失败；</li>
 *   <li>宿主声明的 Bean 被收集进注册表，并能被选择到；</li>
 *   <li>多个解码器时顺序由 {@code @Order} 决定，同序由 {@code id} 兜底 —— 结果确定；
 *       并且注册表<b>真的被透传</b>到 {@code AdapterContext}（否则协议模块拿不到，等于没实现）。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-10-17
 */
class BytePayloadDecoderAssemblyTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class))
            .withPropertyValues("ypbin.iot.devices.enabled=false");

    @Test
    @DisplayName("BPA-01 默认装配必须提供空注册表（不注册解码器也能启动，行为与没有 SPI 一致）")
    void defaultAssemblyMustProvideEmptyRegistry() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(BytePayloadDecoderRegistry.class);
            BytePayloadDecoderRegistry registry = context.getBean(BytePayloadDecoderRegistry.class);
            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.decoders()).isEmpty();
        });
    }

    @Test
    @DisplayName("BPA-02 宿主声明 @Bean BytePayloadDecoder 必须被收集（issue #30 验收标准）")
    void hostDecoderBeanMustBeCollected() {
        runner.withUserConfiguration(SingleDecoderConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            BytePayloadDecoderRegistry registry = context.getBean(BytePayloadDecoderRegistry.class);
            assertThat(registry.decoders()).containsExactly(SingleDecoderConfiguration.DECODER);
            assertThat(registry.find(new DecodeContext("d1", SingleDecoderConfiguration.CODE, "topic/a",
                    "c1"))).containsSame(SingleDecoderConfiguration.DECODER);
        });
    }

    @Test
    @DisplayName("BPA-03 @Order 决定先后；同序按 id 字典序兜底（结果确定，不随注册顺序漂动）")
    void decoderOrderMustBeDeterministic() {
        runner.withUserConfiguration(MultiDecoderConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            BytePayloadDecoderRegistry registry = context.getBean(BytePayloadDecoderRegistry.class);
            assertThat(registry.decoders().stream().map(BytePayloadDecoder::id).toList())
                    .as("@Order(1) 必须排在 @Order(2) 之前").containsExactly("z-first", "a-second");
            assertThat(registry.find(new DecodeContext("d1", SingleDecoderConfiguration.CODE, "topic/a",
                    "c1")))
                    .as("多个解码器都认领时必须取顺序在前的那个")
                    .containsSame(MultiDecoderConfiguration.FIRST);
        });
    }

    @Test
    @DisplayName("BPA-04 注册表必须透传到 AdapterContext（否则协议模块拿不到 SPI）")
    void registryMustReachAdapterContext() {
        runner.withUserConfiguration(SingleDecoderConfiguration.class, AdapterConfiguration.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    AdapterRegistry adapters = context.getBean(AdapterRegistry.class);
                    assertThat(adapters.contextOf(SingleDecoderConfiguration.CODE)).isPresent();
                    assertThat(adapters.contextOf(SingleDecoderConfiguration.CODE).orElseThrow()
                            .payloadDecoders().isEmpty()).isFalse();
                });
    }

    @Test
    @DisplayName("BPA-05 宿主覆盖 BytePayloadDecoderRegistry Bean 时框架必须让位")
    void hostRegistryMustOverrideDefault() {
        runner.withUserConfiguration(RegistryOverrideConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BytePayloadDecoderRegistry.class))
                    .isSameAs(RegistryOverrideConfiguration.HOST_REGISTRY);
        });
    }

    /**
     * 解码器桩：认领一切，返回固定值。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    static class StubDecoder implements BytePayloadDecoder {

        private final String id;

        private final Object value;

        StubDecoder(String id, Object value) {
            this.id = id;
            this.value = value;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public boolean supports(DecodeContext context) {
            return true;
        }

        @Override
        public DecodeOutcome decode(byte[] payload, DecodeContext context) {
            return DecodeOutcome.ok(value);
        }
    }

    /**
     * 单解码器装配。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    @Configuration(proxyBeanMethods = false)
    static class SingleDecoderConfiguration {

        static final ProtocolCode CODE = ProtocolCode.of("stub");

        static final BytePayloadDecoder DECODER = new StubDecoder("single", "decoded");

        @Bean
        BytePayloadDecoder hostBytePayloadDecoder() {
            return DECODER;
        }
    }

    /**
     * 多解码器装配（验证顺序）。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    @Configuration(proxyBeanMethods = false)
    static class MultiDecoderConfiguration {

        static final BytePayloadDecoder FIRST = new StubDecoder("z-first", "first");

        static final BytePayloadDecoder SECOND = new StubDecoder("a-second", "second");

        @Bean
        @Order(1)
        BytePayloadDecoder firstDecoder() {
            return FIRST;
        }

        @Bean
        @Order(2)
        BytePayloadDecoder secondDecoder() {
            return SECOND;
        }
    }

    /**
     * 宿主自定义注册表。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    @Configuration(proxyBeanMethods = false)
    static class RegistryOverrideConfiguration {

        static final BytePayloadDecoderRegistry HOST_REGISTRY = new BytePayloadDecoderRegistry() {

            @Override
            public Optional<BytePayloadDecoder> find(DecodeContext context) {
                return Optional.empty();
            }

            @Override
            public List<BytePayloadDecoder> decoders() {
                return List.of();
            }
        };

        @Bean
        BytePayloadDecoderRegistry hostBytePayloadDecoderRegistry() {
            return HOST_REGISTRY;
        }
    }

    /**
     * 最小适配器（只为验证上下文透传）。
     *
     * @author wenbin
     * @since 2026-10-17
     */
    @Configuration(proxyBeanMethods = false)
    static class AdapterConfiguration {

        @Bean
        ProtocolAdapter stubAdapter() {
            return new ProtocolAdapter() {

                private final ProtocolDescriptor descriptor = ProtocolDescriptor.builder()
                        .code(SingleDecoderConfiguration.CODE)
                        .name("Stub")
                        .transport("TCP")
                        .capabilities()
                        .build();

                @Override
                public ProtocolDescriptor descriptor() {
                    return descriptor;
                }

                @Override
                public CompletionStage<ProtocolConnection> open(ConnectionSpec spec,
                        AdapterContext context) {
                    return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
                }
            };
        }
    }
}
