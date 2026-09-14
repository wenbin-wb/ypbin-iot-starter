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
package cn.ypbin.iot.protocol.opcua.autoconfigure;

import cn.ypbin.iot.protocol.opcua.OpcUaAdapter;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import org.eclipse.milo.opcua.sdk.client.OpcUaClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * OPC UA 自动配置。
 *
 * @author wenbin
 * @since 2026-09-14
 */
@AutoConfiguration(after = IotAutoConfiguration.class)
@ConditionalOnClass(OpcUaClient.class)
@ConditionalOnProperty(prefix = OpcUaProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(OpcUaProperties.class)
public class OpcUaAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpcUaAutoConfiguration.class);

    /**
     * OPC UA 协议适配器。
     *
     * @param properties 协议配置
     * @return 协议适配器
     */
    @Bean
    @ConditionalOnMissingBean(OpcUaAdapter.class)
    public OpcUaAdapter iotOpcUaAdapter(OpcUaProperties properties) {
        if (!properties.isPlaintext()) {
            log.warn("[ypbin-iot] OPC UA security policy {} / mode {} is configured but not implemented; "
                    + "connections will fail fast instead of silently using plaintext.",
                    properties.securityPolicy(), properties.securityMode());
        }
        return new OpcUaAdapter(properties);
    }
}
