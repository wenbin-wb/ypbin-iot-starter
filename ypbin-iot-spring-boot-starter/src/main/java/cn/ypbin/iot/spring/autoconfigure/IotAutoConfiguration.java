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

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.CredentialResolver;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.core.spi.DeviceEventListener;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.egress.EgressRouter;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * IoT 接入核心自动配置。
 *
 * <p>装配框架级 Bean：调度器、数据出口、连接注册中心、适配器注册中心、生命周期编排。
 * 所有 Bean 均带 {@link ConditionalOnMissingBean}，宿主可整体替换实现。</p>
 *
 * <p>协议模块的装配类必须声明 {@code @AutoConfiguration(after = IotAutoConfiguration.class)}，
 * 以保证此处的基础设施先就绪。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = IotProperties.PREFIX, name = "enabled", havingValue = "true",
        matchIfMissing = true)
@EnableConfigurationProperties(IotProperties.class)
public class IotAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotAutoConfiguration.class);

    /**
     * 调度器：承载周期任务、虚拟线程执行器与平台线程执行器。
     *
     * @param properties 配置
     * @return 调度器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public TaskScheduler iotTaskScheduler(IotProperties properties) {
        IotProperties.PlatformPoolProperties pool = properties.scheduler().platformPool();
        int coreSize = pool.coreSize() == null ? 0 : pool.coreSize();
        int queueCapacity = pool.queueCapacity() == null ? 0 : pool.queueCapacity();
        log.debug("[ypbin-iot] iotTaskScheduler configured.");
        return new DefaultTaskScheduler(coreSize, queueCapacity, properties.scheduler().shutdownTimeout());
    }

    /**
     * 数据出口：有界队列 + 微批合并 + 显式溢出计数。
     *
     * @param properties 配置
     * @param sinks      宿主注册的数据落点
     * @param listeners  宿主注册的设备事件监听器
     * @return 数据出口
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public DataEgress iotDataEgress(IotProperties properties, ObjectProvider<DataSink> sinks,
            ObjectProvider<DeviceEventListener> listeners, Clock clock) {
        IotProperties.EgressProperties egress = properties.egress();
        List<DataSink> sinkList = sinks.orderedStream().toList();
        List<DeviceEventListener> listenerList = listeners.orderedStream().toList();
        if (sinkList.isEmpty()) {
            log.warn("[ypbin-iot] no DataSink bean found; captured data will be dropped. "
                    + "Register a DataSink if this application is expected to receive device data.");
        }
        log.debug("[ypbin-iot] iotDataEgress configured with {} sink(s).", sinkList.size());
        return new EgressRouter(egress.batchSize(), egress.queueCapacity(), egress.overflowPolicy(),
                egress.blockTimeout(), egress.batchInterval(), sinkList, listenerList, clock);
    }

    /**
     * 指标门面：默认无操作实现，宿主可覆盖为 Micrometer 桥接。
     *
     * @return 指标门面
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsRecorder iotMetricsRecorder() {
        log.debug("[ypbin-iot] iotMetricsRecorder configured (noop).");
        return NoopMetricsRecorder.INSTANCE;
    }

    /**
     * 凭据解析器：默认基于环境变量。
     *
     * @return 凭据解析器
     */
    @Bean
    @ConditionalOnMissingBean
    public CredentialResolver iotCredentialResolver() {
        log.debug("[ypbin-iot] iotCredentialResolver configured (env).");
        return new EnvCredentialResolver();
    }

    /**
     * 时钟：统一注入以便测试替换。
     *
     * @return 时钟
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock iotClock() {
        return Clock.systemUTC();
    }

    /**
     * 连接注册中心：单飞建链 + 引用计数 + 空闲回收。
     *
     * @param properties 配置
     * @param scheduler  调度器
     * @param clock      时钟
     * @return 连接注册中心
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ConnectionRegistry iotConnectionRegistry(IotProperties properties, TaskScheduler scheduler,
            Clock clock) {
        log.debug("[ypbin-iot] iotConnectionRegistry configured (maxConnections={}).",
                properties.connection().maxConnections());
        return new ConnectionRegistry(properties.connection().idleTimeout(),
                properties.connection().maxConnections(), properties.connection().connectRateLimit(),
                properties.connection().connectRateJitter(), scheduler, clock);
    }

    /**
     * 适配器注册中心：收集全部适配器 Bean 并做启动期 fail-fast 校验。
     *
     * <p>校验失败<b>终止启动</b>而非 warn：协议 code 冲突或版本不兼容会让运行期行为不可预测。</p>
     *
     * @param properties 配置
     * @param adapters   容器中的全部协议适配器
     * @param egress     数据出口
     * @param scheduler  调度器
     * @param metrics    指标门面
     * @param credentials 凭据解析器
     * @param clock      时钟
     * @return 适配器注册中心
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public AdapterRegistry iotAdapterRegistry(IotProperties properties,
            ObjectProvider<ProtocolAdapter> adapters, DataEgress egress, TaskScheduler scheduler,
            MetricsRecorder metrics, CredentialResolver credentials, Clock clock) {
        List<ProtocolAdapter> adapterList = adapters.orderedStream().toList();
        Map<ProtocolCode, AdapterContext> contexts = new LinkedHashMap<>();
        for (ProtocolAdapter adapter : adapterList) {
            ProtocolCode code = adapter.descriptor().code();
            IotProperties.ProtocolProperties protocolProperties = properties.protocol(code.value());
            DefaultAdapterSettings settings = new DefaultAdapterSettings(
                    protocolProperties.isEnabled(),
                    protocolProperties.connectTimeout(),
                    protocolProperties.requestTimeout(),
                    protocolProperties.keepAliveInterval(),
                    protocolProperties.reconnectInitialDelay(),
                    protocolProperties.reconnectMaxDelay(),
                    protocolProperties.reconnectJitter(),
                    protocolProperties.maxConnections(),
                    protocolProperties.maxPendingRequests(),
                    protocolProperties.extended());
            contexts.put(code, new DefaultAdapterContext(code, settings, egress, scheduler, metrics,
                    credentials, clock, protocolProperties.addressCacheSize()));
        }
        AdapterRegistry registry = new AdapterRegistry(adapterList, contexts);
        log.debug("[ypbin-iot] iotAdapterRegistry configured with {} adapter(s).", adapterList.size());
        return registry;
    }

    /**
     * 接入生命周期编排：容器就绪后建链绑定，关闭时优雅解绑。
     *
     * @param adapterRegistry    适配器注册中心
     * @param connectionRegistry 连接注册中心
     * @param deviceRegistries   设备来源
     * @param specProviders      链路规格来源
     * @param properties         配置
     * @return 生命周期编排器
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public IotLifecycle iotLifecycle(AdapterRegistry adapterRegistry, ConnectionRegistry connectionRegistry,
            ObjectProvider<DeviceRegistry> deviceRegistries,
            ObjectProvider<ConnectionSpecProvider> specProviders, IotProperties properties) {
        List<DeviceRegistry> registries = deviceRegistries.orderedStream().toList();
        List<ConnectionSpecProvider> providers = specProviders.orderedStream().toList();
        log.debug("[ypbin-iot] iotLifecycle configured (deviceRegistries={}, specProviders={}).",
                registries.size(), providers.size());
        return new IotLifecycle(adapterRegistry, connectionRegistry, registries, providers, properties);
    }
}
