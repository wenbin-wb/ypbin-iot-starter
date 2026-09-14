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

import cn.ypbin.iot.protocol.opcua.autoconfigure.OpcUaAutoConfiguration;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OPC UA 装配测试。
 *
 * @author wenbin
 * @since 2026-09-14
 */
class OpcUaAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class,
                    OpcUaAutoConfiguration.class))
            .withPropertyValues("ypbin.iot.devices.enabled=false");

    @Test
    @DisplayName("OPCCFG-01 默认装配必须注册适配器并登记到注册中心")
    void defaultAutoConfigurationMustRegisterAdapter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(OpcUaAdapter.class);
            AdapterRegistry registry = context.getBean(AdapterRegistry.class);
            assertThat(registry.protocolCodes()).contains(OpcUaAdapter.PROTOCOL_CODE);
        });
    }

    @Test
    @DisplayName("OPCCFG-02 协议开关关闭时不得装配适配器")
    void disabledSwitchMustSkipAdapter() {
        runner.withPropertyValues("ypbin.iot.protocol.opcua.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpcUaAdapter.class);
        });
    }

    @Test
    @DisplayName("OPCCFG-03 协议库缺失时不得装配（按需生效）")
    void missingProtocolLibraryMustSkipAutoConfiguration() {
        runner.withClassLoader(new FilteredClassLoader(OpcUaClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(OpcUaAdapter.class);
        });
    }

    @Test
    @DisplayName("OPCCFG-04 宿主自定义适配器必须覆盖默认实现")
    void hostAdapterMustOverrideDefault() {
        runner.withUserConfiguration(HostAdapterConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(OpcUaAdapter.class))
                    .isSameAs(HostAdapterConfiguration.HOST_ADAPTER);
        });
    }

    /**
     * 宿主覆盖配置。
     *
     * @author wenbin
     * @since 2026-09-14
     */
    @Configuration(proxyBeanMethods = false)
    static class HostAdapterConfiguration {

        static final OpcUaAdapter HOST_ADAPTER = new OpcUaAdapter(null);

        @Bean
        OpcUaAdapter hostOpcUaAdapter() {
            return HOST_ADAPTER;
        }
    }
}
