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
package cn.ypbin.iot.protocol.modbus.autoconfigure;

import cn.ypbin.iot.protocol.modbus.ModbusAdapter;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import com.digitalpetri.modbus.client.ModbusClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Modbus 自动配置。
 *
 * <p>装配条件：classpath 存在协议库（{@link ModbusClient}）+ 全局开关开启 + Modbus 开关开启。
 * {@code @ConditionalOnClass} 指向的是<b>协议库的类</b>而不是本模块自己的类——
 * 后者恒为真，条件形同虚设（AGENTS R3）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@AutoConfiguration(after = IotAutoConfiguration.class)
@ConditionalOnClass(ModbusClient.class)
@ConditionalOnProperty(prefix = ModbusProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(ModbusProperties.class)
public class ModbusAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModbusAutoConfiguration.class);

    /**
     * Modbus 协议适配器。
     *
     * @param properties Modbus 配置
     * @return 协议适配器
     */
    @Bean
    @ConditionalOnMissingBean(ModbusAdapter.class)
    public ModbusAdapter iotModbusAdapter(ModbusProperties properties) {
        log.debug("[ypbin-iot] iotModbusAdapter configured (defaultUnitId={}).", properties.defaultUnitId());
        return new ModbusAdapter();
    }
}
