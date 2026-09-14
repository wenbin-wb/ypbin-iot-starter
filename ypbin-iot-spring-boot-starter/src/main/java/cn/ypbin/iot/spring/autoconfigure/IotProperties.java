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

import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.egress.EgressOverflowPolicy;
import java.time.Duration;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * IoT 接入框架配置。
 *
 * <p>三级开关自上而下收敛：{@code ypbin.iot.enabled}（总开关）→
 * {@code ypbin.iot.protocol.<code>.enabled}（协议开关）→ 设备级启停（运行期）。</p>
 *
 * <p>所有字段都有开箱即用的默认值：约定优于配置，零配置即可启动。</p>
 *
 * @param enabled    总开关，默认开启
 * @param scheduler  调度与线程池
 * @param egress     数据出口
 * @param connection 连接管理
 * @param devices    设备接入
 * @param protocol   协议级配置，键为协议 code
 * @author wenbin
 * @since 2026-09-13
 */
@ConfigurationProperties(prefix = IotProperties.PREFIX)
public record IotProperties(
        Boolean enabled,
        SchedulerProperties scheduler,
        EgressProperties egress,
        ConnectionProperties connection,
        DeviceProperties devices,
        Map<String, ProtocolProperties> protocol) {

    /** 配置前缀。 */
    public static final String PREFIX = "ypbin.iot";

    /**
     * 紧凑构造器：把缺失配置归一化为默认值，保证下游无需判空。
     */
    public IotProperties {
        enabled = enabled == null ? Boolean.TRUE : enabled;
        scheduler = scheduler == null ? SchedulerProperties.defaults() : scheduler;
        egress = egress == null ? EgressProperties.defaults() : egress;
        connection = connection == null ? ConnectionProperties.defaults() : connection;
        devices = devices == null ? DeviceProperties.defaults() : devices;
        protocol = protocol == null ? Map.of() : Map.copyOf(protocol);
    }

    /**
     * 是否启用。
     *
     * @return 启用返回 {@code true}
     */
    public boolean isEnabled() {
        return Boolean.TRUE.equals(enabled);
    }

    /**
     * 取指定协议配置，缺失时返回全默认值。
     *
     * @param code 协议 code
     * @return 协议配置
     */
    public ProtocolProperties protocol(String code) {
        ProtocolProperties found = protocol.get(code);
        return found == null ? ProtocolProperties.defaults() : found;
    }

    /**
     * 调度与线程池配置。
     *
     * <p><b>注意</b>：虚拟线程调度器的 {@code jdk.virtualThreadScheduler.parallelism} 与
     * {@code maxPoolSize} 是 <b>JVM 系统属性</b>，无法通过 Spring 配置生效，
     * 因此不在此声明——它们的推荐取值见 {@code DESIGN.md} §5.10。</p>
     *
     * @param platformPool  平台线程池：承载 JNI / native 调用
     * @param workerThreads Netty worker 线程数
     * @param shutdownTimeout 停机时等待在途任务的上限
     * @author wenbin
     * @since 2026-09-13
     */
    public record SchedulerProperties(
            PlatformPoolProperties platformPool, @Nullable Integer workerThreads,
            Duration shutdownTimeout) {

        /**
         * 紧凑构造器：归一化默认值。
         */
        public SchedulerProperties {
            platformPool = platformPool == null ? PlatformPoolProperties.defaults() : platformPool;
            shutdownTimeout = shutdownTimeout == null ? Duration.ofSeconds(5) : shutdownTimeout;
        }

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static SchedulerProperties defaults() {
            return new SchedulerProperties(PlatformPoolProperties.defaults(), null, Duration.ofSeconds(5));
        }
    }

    /**
     * 平台线程池配置：承载 JNI / native 调用。
     *
     * <p>大小应按<b>物理资源数</b>（串口数 / CAN 通道数 / 媒体并发）而非设备数配置——
     * 同一串口上的请求本就串行，池开大没有收益。</p>
     *
     * @param coreSize      核心线程数
     * @param maxSize       最大线程数
     * @param queueCapacity 队列容量（有界：满了要拒绝并计数，不能无限排队）
     * @author wenbin
     * @since 2026-09-13
     */
    public record PlatformPoolProperties(@Nullable Integer coreSize, @Nullable Integer maxSize,
            Integer queueCapacity) {

        /** 默认队列容量。 */
        public static final int DEFAULT_QUEUE_CAPACITY = 10_000;

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static PlatformPoolProperties defaults() {
            return new PlatformPoolProperties(null, null, DEFAULT_QUEUE_CAPACITY);
        }
    }

    /**
     * 数据出口配置。
     *
     * @param batchSize      合并后的单批最大点数
     * @param batchInterval  合并时间窗口
     * @param queueCapacity  队列容量（按点位数计量）
     * @param overflowPolicy 溢出策略
     * @param blockTimeout   {@code BLOCK} 策略的等待上限
     * @author wenbin
     * @since 2026-09-13
     */
    public record EgressProperties(
            Integer batchSize,
            Duration batchInterval,
            Integer queueCapacity,
            EgressOverflowPolicy overflowPolicy,
            Duration blockTimeout) {

        /** 默认批大小。 */
        public static final int DEFAULT_BATCH_SIZE = 1000;

        /** 默认批间隔。 */
        public static final Duration DEFAULT_BATCH_INTERVAL = Duration.ofMillis(200);

        /** 默认队列容量（点位数）。 */
        public static final int DEFAULT_QUEUE_CAPACITY = 100_000;

        /**
         * 紧凑构造器：归一化默认值。
         */
        public EgressProperties {
            batchSize = batchSize == null || batchSize <= 0 ? DEFAULT_BATCH_SIZE : batchSize;
            batchInterval = batchInterval == null ? DEFAULT_BATCH_INTERVAL : batchInterval;
            queueCapacity = queueCapacity == null || queueCapacity <= 0
                    ? DEFAULT_QUEUE_CAPACITY : queueCapacity;
            overflowPolicy = overflowPolicy == null ? EgressOverflowPolicy.DROP_OLDEST : overflowPolicy;
            blockTimeout = blockTimeout == null ? Duration.ofSeconds(1) : blockTimeout;
        }

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static EgressProperties defaults() {
            return new EgressProperties(DEFAULT_BATCH_SIZE, DEFAULT_BATCH_INTERVAL,
                    DEFAULT_QUEUE_CAPACITY, EgressOverflowPolicy.DROP_OLDEST, Duration.ofSeconds(1));
        }
    }

    /**
     * 连接管理配置。
     *
     * @param maxConnections    单节点最大并发链路数
     * @param idleTimeout       无设备绑定的链路空闲回收超时
     * @param connectRateLimit  每秒最大建链数（防启动风暴）
     * @param connectRateJitter 建链抖动系数（防重连风暴）
     * @author wenbin
     * @since 2026-09-13
     */
    public record ConnectionProperties(
            Integer maxConnections,
            Duration idleTimeout,
            Integer connectRateLimit,
            Double connectRateJitter) {

        /** 默认最大链路数。 */
        public static final int DEFAULT_MAX_CONNECTIONS = 100_000;

        /** 默认空闲回收超时。 */
        public static final Duration DEFAULT_IDLE_TIMEOUT = Duration.ofMinutes(5);

        /** 默认建链速率（每秒）。 */
        public static final int DEFAULT_CONNECT_RATE_LIMIT = 500;

        /**
         * 紧凑构造器：归一化默认值。
         */
        public ConnectionProperties {
            maxConnections = maxConnections == null || maxConnections <= 0
                    ? DEFAULT_MAX_CONNECTIONS : maxConnections;
            idleTimeout = idleTimeout == null ? DEFAULT_IDLE_TIMEOUT : idleTimeout;
            connectRateLimit = connectRateLimit == null || connectRateLimit <= 0
                    ? DEFAULT_CONNECT_RATE_LIMIT : connectRateLimit;
            connectRateJitter = connectRateJitter == null ? 0.3D : connectRateJitter;
        }

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static ConnectionProperties defaults() {
            return new ConnectionProperties(DEFAULT_MAX_CONNECTIONS, DEFAULT_IDLE_TIMEOUT,
                    DEFAULT_CONNECT_RATE_LIMIT, 0.3D);
        }
    }

    /**
     * 设备接入配置。
     *
     * @param enabled              是否在启动时自动接入设备
     * @param defaultPollInterval  默认采集周期
     * @param probeBeforeBind      绑定前是否先探测
     * @author wenbin
     * @since 2026-09-13
     */
    public record DeviceProperties(Boolean enabled, Duration defaultPollInterval, Boolean probeBeforeBind) {

        /** 默认采集周期。 */
        public static final Duration DEFAULT_POLL_INTERVAL = Duration.ofSeconds(5);

        /**
         * 紧凑构造器：归一化默认值。
         */
        public DeviceProperties {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            defaultPollInterval = defaultPollInterval == null ? DEFAULT_POLL_INTERVAL : defaultPollInterval;
            probeBeforeBind = probeBeforeBind == null ? Boolean.FALSE : probeBeforeBind;
        }

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static DeviceProperties defaults() {
            return new DeviceProperties(Boolean.TRUE, DEFAULT_POLL_INTERVAL, Boolean.FALSE);
        }

        /**
         * 是否自动接入。
         *
         * @return 自动接入返回 {@code true}
         */
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }

        /**
         * 是否绑定前探测。
         *
         * @return 需要探测返回 {@code true}
         */
        public boolean isProbeBeforeBind() {
            return Boolean.TRUE.equals(probeBeforeBind);
        }
    }

    /**
     * 单个协议的配置。
     *
     * <p>通用参数显式建模，协议特有参数走 {@code extended} 类型化查表；
     * 适配器<b>必须</b>校验未知 key 并 warn，不得静默忽略。</p>
     *
     * @param enabled             协议开关
     * @param connectTimeout      建链超时
     * @param requestTimeout      请求超时
     * @param keepAliveInterval   保活间隔
     * @param maxConnections      协议级链路配额（隔离爆炸半径）
     * @param maxPendingRequests  单链路在途请求上限
     * @param reconnectInitialDelay 重连初始退避
     * @param reconnectMaxDelay   重连最大退避
     * @param reconnectJitter     重连抖动系数
     * @param addressCacheSize    地址解析缓存容量
     * @param extended            协议特有参数
     * @author wenbin
     * @since 2026-09-13
     */
    public record ProtocolProperties(
            Boolean enabled,
            Duration connectTimeout,
            Duration requestTimeout,
            Duration keepAliveInterval,
            @Nullable Integer maxConnections,
            @Nullable Integer maxPendingRequests,
            @Nullable Duration reconnectInitialDelay,
            @Nullable Duration reconnectMaxDelay,
            @Nullable Double reconnectJitter,
            Integer addressCacheSize,
            Map<String, Object> extended) {

        /** 默认地址缓存容量。 */
        public static final int DEFAULT_ADDRESS_CACHE_SIZE = 4096;

        /**
         * 紧凑构造器：归一化默认值。
         */
        public ProtocolProperties {
            enabled = enabled == null ? Boolean.TRUE : enabled;
            connectTimeout = connectTimeout == null
                    ? DefaultAdapterSettings.DEFAULT_CONNECT_TIMEOUT : connectTimeout;
            requestTimeout = requestTimeout == null
                    ? DefaultAdapterSettings.DEFAULT_REQUEST_TIMEOUT : requestTimeout;
            keepAliveInterval = keepAliveInterval == null
                    ? DefaultAdapterSettings.DEFAULT_KEEP_ALIVE_INTERVAL : keepAliveInterval;
            maxConnections = maxConnections == null || maxConnections <= 0
                    ? DefaultAdapterSettings.DEFAULT_MAX_CONNECTIONS : maxConnections;
            maxPendingRequests = maxPendingRequests == null || maxPendingRequests <= 0
                    ? DefaultAdapterSettings.DEFAULT_MAX_PENDING_REQUESTS : maxPendingRequests;
            reconnectInitialDelay = reconnectInitialDelay == null
                    ? DefaultAdapterSettings.DEFAULT_RECONNECT_INITIAL_DELAY : reconnectInitialDelay;
            reconnectMaxDelay = reconnectMaxDelay == null
                    ? DefaultAdapterSettings.DEFAULT_RECONNECT_MAX_DELAY : reconnectMaxDelay;
            reconnectJitter = reconnectJitter == null
                    ? DefaultAdapterSettings.DEFAULT_RECONNECT_JITTER : reconnectJitter;
            addressCacheSize = addressCacheSize == null || addressCacheSize <= 0
                    ? DEFAULT_ADDRESS_CACHE_SIZE : addressCacheSize;
            extended = extended == null ? Map.of() : Map.copyOf(extended);
        }

        /**
         * 全默认配置。
         *
         * @return 配置
         */
        public static ProtocolProperties defaults() {
            return new ProtocolProperties(Boolean.TRUE, ConnectionSpec.DEFAULT_CONNECT_TIMEOUT,
                    ConnectionSpec.DEFAULT_REQUEST_TIMEOUT, DefaultAdapterSettings.DEFAULT_KEEP_ALIVE_INTERVAL,
                    null, null, null, null, null, DEFAULT_ADDRESS_CACHE_SIZE, Map.of());
            // 上面五处 null 表示「未配置，用框架默认」——对应组件已标 @Nullable
        }

        /**
         * 是否启用。
         *
         * @return 启用返回 {@code true}
         */
        public boolean isEnabled() {
            return Boolean.TRUE.equals(enabled);
        }
    }
}
