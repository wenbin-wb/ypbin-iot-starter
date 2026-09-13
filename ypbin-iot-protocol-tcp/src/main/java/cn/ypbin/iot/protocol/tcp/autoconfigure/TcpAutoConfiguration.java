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

import cn.ypbin.iot.protocol.tcp.TcpAdapter;
import cn.ypbin.iot.spring.autoconfigure.IotAutoConfiguration;
import cn.ypbin.iot.transport.NettyTransport;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * TCP 透传协议自动配置。
 *
 * <p>装配条件：classpath 存在 Netty（{@link Channel}）+ 全局开关开启 + TCP 协议开关开启。
 * {@code @ConditionalOnClass} 指向的是<b>真实依赖</b>（Netty）而不是本模块自己的类——
 * 后者恒为真，条件形同虚设（DESIGN §3.4 铁律 A7）。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@AutoConfiguration(after = IotAutoConfiguration.class)
@ConditionalOnClass(Channel.class)
@ConditionalOnProperty(prefix = TcpProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(TcpProperties.class)
public class TcpAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(TcpAutoConfiguration.class);

    /**
     * TCP 传输底座。
     *
     * @param properties TCP 配置
     * @return 传输底座
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public NettyTransport iotTcpTransport(TcpProperties properties) {
        int workerThreads = properties.workerThreads() == null ? 0 : properties.workerThreads();
        log.debug("[ypbin-iot] iotTcpTransport configured (framing={}).", properties.framingMode());
        return new NettyTransport(workerThreads, properties.toFramingSpec());
    }

    /**
     * TCP 透传适配器。
     *
     * @param properties TCP 配置
     * @param transport  传输底座
     * @return 协议适配器
     */
    @Bean
    @ConditionalOnMissingBean(TcpAdapter.class)
    public TcpAdapter iotTcpAdapter(TcpProperties properties, NettyTransport transport) {
        log.debug("[ypbin-iot] iotTcpAdapter configured.");
        return new TcpAdapter(transport, properties.toFramingSpec(), properties.idleInterval());
    }
}
