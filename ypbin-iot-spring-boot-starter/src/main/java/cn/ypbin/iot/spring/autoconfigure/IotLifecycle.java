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
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.spi.ConnectionSpecProvider;
import cn.ypbin.iot.core.spi.DeviceRegistry;
import cn.ypbin.iot.core.spi.ValidationResult;
import cn.ypbin.iot.runtime.registry.AdapterRegistry;
import cn.ypbin.iot.runtime.registry.ConnectionRegistry;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

/**
 * 接入生命周期编排：容器就绪后按设备清单建链绑定，容器关闭时优雅解绑。
 *
 * <p><b>启动期是唯一允许访问设备来源（可能落库）的时刻</b>：{@link DeviceRegistry#loadAll()}
 * 在此被调用一次并全量加载到内存，此后运行期只读内存。这是「接入路径零 DB 访问」约束的落地方式。</p>
 *
 * <p>单个设备失败<b>不</b>影响其余设备：失败被完整记录并计入启动报告。</p>
 *
 * <p><b>设备与链路分两个来源</b>：设备清单来自 {@link DeviceRegistry}（deviceId → protocol + localAddress），
 * 建链参数来自 {@link ConnectionSpecProvider}（connectionId → endpoint/timeout/TLS）。
 * 这一拆分是「连接 / 设备两级模型」的直接结果：一条链路可被 200 个从站共享。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class IotLifecycle implements ApplicationListener<ApplicationReadyEvent>, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(IotLifecycle.class);

    private final AdapterRegistry adapterRegistry;

    private final ConnectionRegistry connectionRegistry;

    private final List<DeviceRegistry> deviceRegistries;

    private final List<ConnectionSpecProvider> specProviders;

    private final IotProperties properties;

    private final Map<String, DeviceSession> sessions = new ConcurrentHashMap<>();

    private final Map<String, ConnectionRegistry.ConnectionHandle> handles = new ConcurrentHashMap<>();

    private final AtomicBoolean closed = new AtomicBoolean(false);

    /**
     * 创建生命周期编排器。
     *
     * @param adapterRegistry    适配器注册中心
     * @param connectionRegistry 连接注册中心
     * @param deviceRegistries   设备来源实现
     * @param specProviders      链路规格来源实现
     * @param properties         配置
     */
    public IotLifecycle(AdapterRegistry adapterRegistry, ConnectionRegistry connectionRegistry,
            List<DeviceRegistry> deviceRegistries, List<ConnectionSpecProvider> specProviders,
            IotProperties properties) {
        this.adapterRegistry = adapterRegistry;
        this.connectionRegistry = connectionRegistry;
        this.deviceRegistries = deviceRegistries == null ? List.of() : List.copyOf(deviceRegistries);
        this.specProviders = specProviders == null ? List.of() : List.copyOf(specProviders);
        this.properties = properties;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!properties.devices().isEnabled()) {
            log.debug("[ypbin-iot] device bootstrap disabled by configuration.");
            return;
        }
        if (deviceRegistries.isEmpty()) {
            log.debug("[ypbin-iot] no DeviceRegistry bean registered; nothing to bind.");
            return;
        }
        List<DeviceSpec> devices = new ArrayList<>();
        for (DeviceRegistry registry : deviceRegistries) {
            try {
                devices.addAll(registry.loadAll());
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] failed to load devices from registry {}",
                        registry.getClass().getName(), ex);
            }
        }
        int bound = 0;
        int failed = 0;
        for (DeviceSpec device : devices) {
            if (bind(device)) {
                bound++;
            } else {
                failed++;
            }
        }
        log.info("[ypbin-iot] device bootstrap finished: total={}, bound={}, failed={}",
                devices.size(), bound, failed);
    }

    /**
     * 接入单个设备。
     *
     * @param device 设备规格
     * @return 成功返回 {@code true}
     */
    public boolean bind(DeviceSpec device) {
        Optional<ProtocolAdapter> adapterOptional = adapterRegistry.find(device.protocol());
        Optional<AdapterContext> contextOptional = adapterRegistry.contextOf(device.protocol());
        if (adapterOptional.isEmpty() || contextOptional.isEmpty()) {
            log.warn("[ypbin-iot] no adapter registered for device {} protocol {}",
                    device.deviceId(), device.protocol());
            return false;
        }
        ValidationResult validation = validate(device);
        if (!validation.passed()) {
            log.warn("[ypbin-iot] device {} rejected by validation: {}",
                    device.deviceId(), validation.reasons());
            return false;
        }
        Optional<ConnectionSpec> specOptional = resolveSpec(device.connectionId());
        if (specOptional.isEmpty()) {
            log.warn("[ypbin-iot] no connection spec found for device {} connectionId {}",
                    device.deviceId(), device.connectionId());
            return false;
        }
        ProtocolAdapter adapter = adapterOptional.get();
        AdapterContext context = contextOptional.get();
        try {
            ConnectionRegistry.ConnectionHandle handle = connectionRegistry
                    .acquire(adapter, specOptional.get(), context)
                    .toCompletableFuture()
                    .join();
            DeviceSession session = adapter.bind(handle.connection(), device, context)
                    .toCompletableFuture()
                    .join();
            sessions.put(device.deviceId(), session);
            handles.put(device.deviceId(), handle);
            log.debug("[ypbin-iot] device {} bound to session {}.", device.deviceId(), session.sessionId());
            return true;
        } catch (RuntimeException ex) {
            log.error("[ypbin-iot] failed to bind device {}", device.deviceId(), ex);
            return false;
        }
    }

    /**
     * 探测一条链路（供诊断使用）。
     *
     * @param spec 链路规格
     * @return 探测结果 Stage
     */
    public CompletableFuture<ProbeResult> probe(ConnectionSpec spec) {
        Optional<ProtocolAdapter> adapter = adapterRegistry.find(spec.protocol());
        Optional<AdapterContext> context = adapterRegistry.contextOf(spec.protocol());
        if (adapter.isEmpty() || context.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(
                    "no adapter registered for protocol " + spec.protocol()));
        }
        return adapter.get().probe(spec, context.get()).toCompletableFuture();
    }

    /**
     * 已绑定的会话数。
     *
     * @return 会话数
     */
    public int sessionCount() {
        return sessions.size();
    }

    /**
     * 已绑定会话的快照（设备标识 → 会话）。
     *
     * @return 不可变视图
     */
    public Map<String, DeviceSession> sessions() {
        return Map.copyOf(sessions);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        sessions.forEach((deviceId, session) -> futures.add(
                session.close().toCompletableFuture().exceptionally(ex -> {
                    log.error("[ypbin-iot] failed to close session of device {}", deviceId, ex);
                    return null;
                })));
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        sessions.clear();
        handles.values().forEach(ConnectionRegistry.ConnectionHandle::release);
        handles.clear();
        log.debug("[ypbin-iot] lifecycle closed, {} session(s) released.", futures.size());
    }

    private Optional<ConnectionSpec> resolveSpec(String connectionId) {
        for (ConnectionSpecProvider provider : specProviders) {
            try {
                Optional<ConnectionSpec> found = provider.find(connectionId);
                if (found.isPresent()) {
                    return found;
                }
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] connection spec provider {} failed for {}",
                        provider.getClass().getName(), connectionId, ex);
            }
        }
        return Optional.empty();
    }

    private ValidationResult validate(DeviceSpec device) {
        for (DeviceRegistry registry : deviceRegistries) {
            ValidationResult result = registry.validate(device);
            if (!result.passed()) {
                return result;
            }
        }
        return ValidationResult.ok();
    }
}
