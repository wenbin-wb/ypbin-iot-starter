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
import cn.ypbin.iot.core.spi.BytePayloadDecoder;
import cn.ypbin.iot.core.spi.BytePayloadDecoderRegistry;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DataSink;
import cn.ypbin.iot.core.spi.DeviceEventListener;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.core.spi.DeviceRevisionAllocator;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.delivery.BoundedDeliveryDispatcher;
import cn.ypbin.iot.runtime.egress.EgressRouter;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import cn.ypbin.iot.runtime.registry.DefaultBytePayloadDecoderRegistry;
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
            ObjectProvider<DeviceEventListener> listeners, Clock clock, TaskScheduler taskScheduler) {
        IotProperties.EgressProperties egress = properties.egress();
        List<DataSink> sinkList = sinks.orderedStream().toList();
        List<DeviceEventListener> listenerList = listeners.orderedStream().toList();
        if (sinkList.isEmpty()) {
            log.warn("[ypbin-iot] no DataSink bean found; captured data will be dropped. "
                    + "Register a DataSink if this application is expected to receive device data.");
        }
        log.debug("[ypbin-iot] iotDataEgress configured with {} sink(s).", sinkList.size());
        // 出口也需要投递器：设备事件回调与 BLOCK 背压等待都必须卸载出调用线程，
        // 否则协议线程上会执行宿主代码 / sleep（I4）
        return new EgressRouter(egress.batchSize(), egress.queueCapacity(), egress.overflowPolicy(),
                egress.blockTimeout(), egress.batchInterval(), sinkList, listenerList, clock,
                // 出口不属于某个具体协议，故用框架默认的在途上限（与 AdapterSettings 同口径）
                new BoundedDeliveryDispatcher(taskScheduler,
                        DefaultAdapterSettings.DEFAULT_MAX_PENDING_REQUESTS));
    }

    /**
     * 指标门面：默认无操作实现，宿主可覆盖为 Micrometer 桥接。
     *
     * <p>走到本方法说明 {@link IotMicrometerAutoConfiguration} 没有注册指标门面：
     * 要么 classpath 上没有 micrometer，要么该装配类被宿主排除/过滤。
     * 若 micrometer 在 classpath 上而没有 MeterRegistry，由
     * {@link IotMicrometerAutoConfiguration} 负责回退并打 WARN —— 不是这里。</p>
     *
     * @return 指标门面
     */
    @Bean
    @ConditionalOnMissingBean
    public MetricsRecorder iotMetricsRecorder() {
        // 不能用 debug：指标被丢弃是宿主应当知情的事，只在 debug 打一行等于静默降级。
        if (isMicrometerOnClasspath()) {
            log.warn("[ypbin-iot] micrometer 在 classpath 上但没有装配 Micrometer 指标桥 —— "
                    + "iotMetricsRecorder 回退到 no-op，框架指标（read/write/subscription/errors）全部丢弃。"
                    + "请确认 IotMicrometerAutoConfiguration 未被排除；若确实不需要指标可忽略本告警。");
        } else {
            log.info("[ypbin-iot] no MeterRegistry found; iotMetricsRecorder falls back to the no-op "
                    + "implementation and metrics are DISCARDED. Add micrometer (e.g. spring-boot-starter-actuator) "
                    + "or provide your own MetricsRecorder bean to collect them.");
        }
        return NoopMetricsRecorder.INSTANCE;
    }

    private static boolean isMicrometerOnClasspath() {
        try {
            Class.forName("io.micrometer.core.instrument.MeterRegistry", false,
                    IotAutoConfiguration.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
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
     * 字节载荷解码器注册表：收集宿主的 {@link BytePayloadDecoder} Bean 并按确定顺序选择
     * （issue #30）。
     *
     * <p><b>空注册表是合法状态</b>：宿主不注册任何解码器时，二进制载荷照旧以 {@code byte[]}
     * 交付（与「没有本 SPI」逐字一致），不会因为多了这个 Bean 而改变既有行为。</p>
     *
     * <p><b>顺序</b>：直接用 {@code orderedStream()} —— 它会读取 {@code @Order}/{@code Ordered}
     * （包括 {@code @Bean} 方法上的注解）并按序返回；同序时保持 Spring 的确定性 Bean 注册顺序。
     * <b>不能再拿解码器实例做二次排序</b>：{@code @Order} 写在 {@code @Bean} 方法上时，
     * 实例本身不带顺序元数据，二次比较会把它当作「同序」从而打乱 {@code orderedStream()} 的结果
     * （本仓实测踩过：{@code @Order(1)} 的解码器被排到了 {@code @Order(2)} 后面）。</p>
     *
     * @param decoders 宿主声明的解码器
     * @return 解码器注册表（恒非空）
     */
    @Bean
    @ConditionalOnMissingBean
    public BytePayloadDecoderRegistry iotBytePayloadDecoderRegistry(
            ObjectProvider<BytePayloadDecoder> decoders) {
        List<BytePayloadDecoder> ordered = decoders.orderedStream().toList();
        if (ordered.isEmpty()) {
            log.debug("[ypbin-iot] no BytePayloadDecoder bean registered; "
                    + "binary payloads are delivered as byte[] unchanged.");
        } else {
            log.debug("[ypbin-iot] iotBytePayloadDecoderRegistry configured with {} decoder(s): {}.",
                    ordered.size(), ordered.stream().map(BytePayloadDecoder::id).toList());
        }
        return new DefaultBytePayloadDecoderRegistry(ordered);
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
     * @param payloadDecoders 宿主字节载荷解码器注册表
     * @return 适配器注册中心
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public AdapterRegistry iotAdapterRegistry(IotProperties properties,
            ObjectProvider<ProtocolAdapter> adapters, DataEgress egress, TaskScheduler scheduler,
            MetricsRecorder metrics, CredentialResolver credentials, Clock clock,
            BytePayloadDecoderRegistry payloadDecoders) {
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
                    credentials, clock, protocolProperties.addressCacheSize(), payloadDecoders));
        }
        AdapterRegistry registry = new AdapterRegistry(adapterList, contexts);
        log.debug("[ypbin-iot] iotAdapterRegistry configured with {} adapter(s).", adapterList.size());
        return registry;
    }

    /**
     * 设备 revision 分配器：宿主不必再自己维护「按设备递增的 revision」（issue #38）。
     *
     * <p>宿主通过 {@code allocator.dispatch(type, device)} 构造 {@link DeviceChange}，
     * 框架按设备原子递增；宿主已有的更大 revision（如数据库配置版本号）走
     * {@code dispatch(type, device, hostRevision)} 仍生效（取 {@code max}）。
     * Bean 带 {@link ConditionalOnMissingBean}：宿主可替换为实现自己的分配策略。</p>
     *
     * @return revision 分配器
     */
    @Bean
    @ConditionalOnMissingBean
    public DeviceRevisionAllocator iotDeviceRevisionAllocator() {
        log.debug("[ypbin-iot] iotDeviceRevisionAllocator configured.");
        return new DeviceRevisionAllocator();
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
            ObjectProvider<ConnectionSpecProvider> specProviders, IotProperties properties,
            TaskScheduler taskScheduler, ObjectProvider<DeviceEventListener> listeners, Clock clock) {
        List<DeviceRegistry> registries = deviceRegistries.orderedStream().toList();
        List<ConnectionSpecProvider> providers = specProviders.orderedStream().toList();
        List<DeviceEventListener> listenerList = listeners.orderedStream().toList();
        log.debug("[ypbin-iot] iotLifecycle configured (deviceRegistries={}, specProviders={}, "
                        + "deviceEventListeners={}, rebind-backoff-initial={}, rebind-max-per-cycle={}).",
                registries.size(), providers.size(), listenerList.size(),
                properties.devices().rebindBackoffInitial(), properties.devices().rebindMaxPerCycle());
        return new IotLifecycle(adapterRegistry, connectionRegistry, registries, providers, properties,
                taskScheduler, listenerList, clock);
    }
}
