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
package cn.ypbin.iot.protocol.mqtt;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttAutoConfiguration;
import cn.ypbin.iot.protocol.mqtt.autoconfigure.MqttProperties;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import com.hivemq.client.mqtt.mqtt3.Mqtt3Client;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MQTT 装配测试。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class MqttAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(IotAutoConfiguration.class, MqttAutoConfiguration.class))
            .withPropertyValues("ypbin.iot.devices.enabled=false");

    @Test
    @DisplayName("MQCFG-01 默认装配必须注册 MQTT 适配器并登记到注册中心")
    void defaultAutoConfigurationMustRegisterAdapter() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MqttAdapter.class);
            AdapterRegistry registry = context.getBean(AdapterRegistry.class);
            assertThat(registry.protocolCodes()).contains(MqttAdapter.PROTOCOL_CODE);
        });
    }

    @Test
    @DisplayName("MQCFG-02 协议开关关闭时不得装配适配器")
    void disabledSwitchMustSkipAdapter() {
        runner.withPropertyValues("ypbin.iot.protocol.mqtt.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MqttAdapter.class);
        });
    }

    @Test
    @DisplayName("MQCFG-03 协议库缺失时不得装配（按需生效）")
    void missingProtocolLibraryMustSkipAutoConfiguration() {
        runner.withClassLoader(new FilteredClassLoader(Mqtt3Client.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MqttAdapter.class);
        });
    }

    @Test
    @DisplayName("MQCFG-04 宿主自定义适配器必须覆盖默认实现")
    void hostAdapterMustOverrideDefault() {
        runner.withUserConfiguration(HostAdapterConfiguration.class).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MqttAdapter.class))
                    .isSameAs(HostAdapterConfiguration.HOST_ADAPTER);
        });
    }

    @Test
    @DisplayName("MQCFG-05 配置默认值与边界归一化")
    void propertiesMustNormalizeDefaults() {
        MqttProperties defaults = new MqttProperties(null, null, null, null, null);
        assertThat(defaults.isEnabled()).isTrue();
        assertThat(defaults.isCleanSession()).isTrue();
        assertThat(defaults.isRetainedDefault()).isFalse();
        assertThat(defaults.qosDefault()).isEqualTo(1);
        assertThat(defaults.clientIdPrefix()).isEqualTo(MqttProperties.DEFAULT_CLIENT_ID_PREFIX);

        assertThat(new MqttProperties(true, "  ", true, 9, null).qosDefault())
                .as("QoS 超出 0~2 必须回落到默认值而不是带病运行")
                .isEqualTo(1);
        assertThat(new MqttProperties(true, null, false, 0, false).isCleanSession()).isFalse();
        assertThat(new MqttProperties(false, null, null, 2, null).isEnabled()).isFalse();
    }

    /**
     * 宿主覆盖配置。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    @Configuration(proxyBeanMethods = false)
    static class HostAdapterConfiguration {

        static final MqttAdapter HOST_ADAPTER = new MqttAdapter(null);

        @Bean
        MqttAdapter hostMqttAdapter() {
            return HOST_ADAPTER;
        }
    }
}
