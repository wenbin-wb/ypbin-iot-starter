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
package cn.ypbin.iot.runtime.context;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.AdapterSettings;
import cn.ypbin.iot.core.context.BoundedAddressCache;
import cn.ypbin.iot.core.context.CredentialResolver;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.context.LogLevel;
import cn.ypbin.iot.core.context.MetricsRecorder;
import cn.ypbin.iot.core.context.ResourceRegistry;
import cn.ypbin.iot.core.context.TaskScheduler;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.runtime.util.LruAddressCache;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器运行时上下文默认实现。
 *
 * <p><b>刻意不暴露任何数据访问能力</b>（无 DataSource、无 RestClient、无 JdbcTemplate）——
 * 这是「接入路径零 DB 访问」约束最有效的一层执行：适配器想查库在 API 层面就做不到。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class DefaultAdapterContext implements AdapterContext {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdapterContext.class);

    private final ProtocolCode protocol;

    private final AdapterSettings settings;

    private final DataEgress egress;

    private final TaskScheduler scheduler;

    private final ResourceRegistry resources;

    private final MetricsRecorder metrics;

    private final CredentialResolver credentials;

    private final Clock clock;

    private final Map<String, BoundedAddressCache<?>> addressCaches = new ConcurrentHashMap<>();

    private final int defaultAddressCacheSize;

    /**
     * 创建上下文。
     *
     * @param protocol                协议标识
     * @param settings                适配器配置
     * @param egress                  数据出口
     * @param scheduler               调度器
     * @param metrics                 指标门面
     * @param credentials             凭据解析器
     * @param clock                   时钟
     * @param defaultAddressCacheSize 地址缓存默认容量
     */
    public DefaultAdapterContext(ProtocolCode protocol, AdapterSettings settings, DataEgress egress,
            TaskScheduler scheduler, MetricsRecorder metrics, CredentialResolver credentials, Clock clock,
            int defaultAddressCacheSize) {
        this.protocol = Objects.requireNonNull(protocol, "protocol must not be null");
        this.settings = Objects.requireNonNull(settings, "settings must not be null");
        this.egress = Objects.requireNonNull(egress, "egress must not be null");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler must not be null");
        this.metrics = Objects.requireNonNull(metrics, "metrics must not be null");
        this.credentials = Objects.requireNonNull(credentials, "credentials must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.resources = new DefaultResourceRegistry();
        this.defaultAddressCacheSize = defaultAddressCacheSize <= 0 ? 4096 : defaultAddressCacheSize;
    }

    @Override
    public ProtocolCode protocol() {
        return protocol;
    }

    @Override
    public AdapterSettings settings() {
        return settings;
    }

    @Override
    public DataEgress egress() {
        return egress;
    }

    @Override
    public TaskScheduler scheduler() {
        return scheduler;
    }

    @Override
    public ResourceRegistry resources() {
        return resources;
    }

    @Override
    public MetricsRecorder metrics() {
        return metrics;
    }

    @Override
    public CredentialResolver credentials() {
        return credentials;
    }

    @Override
    public Clock clock() {
        return clock;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <A> BoundedAddressCache<A> addressCache(String namespace, int maximumSize) {
        Objects.requireNonNull(namespace, "namespace must not be null");
        String key = protocol.value() + "/" + namespace;
        int size = maximumSize <= 0 ? defaultAddressCacheSize : maximumSize;
        return (BoundedAddressCache<A>) addressCaches.computeIfAbsent(key, ignored -> new LruAddressCache<>(size));
    }

    @Override
    public void log(LogLevel level, String message, Object... args) {
        // 刻意不使用 switch-on-enum：javac 会把它编译成 ordinal() 查表，
        // 使「禁 ordinal」的架构规则产生误报（母仓已踩过该坑）。
        if (level == LogLevel.ERROR) {
            log.error("[ypbin-iot][{}] " + message, prepend(protocol.value(), args));
        } else if (level == LogLevel.WARN) {
            log.warn("[ypbin-iot][{}] " + message, prepend(protocol.value(), args));
        } else if (level == LogLevel.INFO) {
            log.info("[ypbin-iot][{}] " + message, prepend(protocol.value(), args));
        } else {
            log.debug("[ypbin-iot][{}] " + message, prepend(protocol.value(), args));
        }
    }

    /** 释放适配器级资源。 */
    public void close() {
        ((DefaultResourceRegistry) resources).close();
    }

    private static Object[] prepend(String head, Object[] args) {
        Object[] merged = new Object[(args == null ? 0 : args.length) + 1];
        merged[0] = head;
        if (args != null && args.length > 0) {
            System.arraycopy(args, 0, merged, 1, args.length);
        }
        return merged;
    }
}
