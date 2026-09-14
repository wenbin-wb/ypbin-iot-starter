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
package cn.ypbin.iot.protocol.modbus;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.protocol.modbus.autoconfigure.ModbusAutoConfiguration;
import cn.ypbin.iot.protocol.modbus.autoconfigure.ModbusProperties;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import com.digitalpetri.modbus.client.ModbusClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Modbus 装配测试：默认装配 / 开关关闭 / 宿主覆盖 / 协议库缺失 四种场景。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class ModbusAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class, ModbusAutoConfiguration.class))
            .withPropertyValues("ypbin.iot.devices.enabled=false");

    @Test
    @DisplayName("MBCFG-01 默认装配必须注册 Modbus 适配器并登记到适配器注册中心")
    void defaultAutoConfigurationMustRegisterAdapter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ModbusAdapter.class);
            AdapterRegistry registry = context.getBean(AdapterRegistry.class);
            assertThat(registry.protocolCodes()).contains(ModbusAdapter.PROTOCOL_CODE);
            assertThat(registry.descriptors().get(0).vendor()).isEqualTo("digitalpetri modbus");
        });
    }

    @Test
    @DisplayName("MBCFG-02 协议开关关闭时不得装配适配器（但框架仍可用）")
    void disabledSwitchMustSkipAdapter() {
        runner.withPropertyValues("ypbin.iot.protocol.modbus.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ModbusAdapter.class);
            assertThat(context).hasSingleBean(AdapterRegistry.class);
            assertThat(context.getBean(AdapterRegistry.class).protocolCodes()).isEmpty();
        });
    }

    @Test
    @DisplayName("MBCFG-03 协议库缺失时不得装配（按需生效，而不是启动失败）")
    void missingProtocolLibraryMustSkipAutoConfiguration() {
        runner.withClassLoader(new FilteredClassLoader(ModbusClient.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ModbusAdapter.class);
        });
    }

    @Test
    @DisplayName("MBCFG-04 宿主自定义适配器必须覆盖框架默认实现")
    void hostAdapterMustOverrideDefault() {
        runner.withUserConfiguration(HostAdapterConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ProtocolAdapter.class))
                    .isSameAs(HostAdapterConfiguration.HOST_ADAPTER);
        });
    }

    @Test
    @DisplayName("MBCFG-05 配置默认值必须开箱即用且校验边界")
    void propertiesMustProvideDefaults() {
        ModbusProperties defaults = new ModbusProperties(null, null);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.defaultUnitId()).isEqualTo(1);

        assertThat(new ModbusProperties(false, 5).isEnabled()).isFalse();
        assertThat(new ModbusProperties(true, 248).defaultUnitId())
                .as("超出 0~247 的从站地址必须回落到默认值而不是带病运行")
                .isEqualTo(1);
        assertThat(new ModbusProperties(true, -1).defaultUnitId()).isEqualTo(1);
        assertThat(new ModbusProperties(true, 247).defaultUnitId()).isEqualTo(247);
    }

    /**
     * 宿主覆盖配置。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    @Configuration(proxyBeanMethods = false)
    static class HostAdapterConfiguration {

        static final ModbusAdapter HOST_ADAPTER = new ModbusAdapter();

        @Bean
        ModbusAdapter hostModbusAdapter() {
            return HOST_ADAPTER;
        }
    }
}
