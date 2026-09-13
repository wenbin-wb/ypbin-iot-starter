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
package cn.ypbin.iot.runtime.registry;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.ProtocolExtension;
import cn.ypbin.iot.runtime.RuntimeVersion;
import cn.ypbin.iot.runtime.util.SemanticVersion;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 适配器注册中心：收集全部 {@link ProtocolAdapter} 并做启动期 fail-fast 校验。
 *
 * <p>校验项（任一不过即抛异常终止启动，不做静默兜底）：</p>
 * <ol>
 *   <li>{@code descriptor()} 非空、{@code code} 非空；</li>
 *   <li><b>协议 code 全局唯一</b>——后者覆盖前者会造成极其隐蔽的路由错乱；</li>
 *   <li>能力与扩展声明一致（声明 {@link BrowseExtension} 则能力必须含
 *       {@link ProtocolCapability#BROWSE}，反之亦然）；</li>
 *   <li>{@code descriptor} 声明的 iot-runtime 版本区间包含当前
 *       {@link RuntimeVersion#VERSION}——把不兼容组合从运行期疑难异常变成启动期明确报错。</li>
 * </ol>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class AdapterRegistry implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AdapterRegistry.class);

    private final Map<ProtocolCode, Registration> registrations;

    /**
     * 创建注册中心并完成全部启动期校验。
     *
     * @param adapters 适配器实例列表（可为空）
     * @param contexts 协议 code 到运行时上下文的映射；缺失时该适配器不会被注册
     */
    public AdapterRegistry(List<ProtocolAdapter> adapters, Map<ProtocolCode, AdapterContext> contexts) {
        List<ProtocolAdapter> candidates = adapters == null ? List.of() : adapters;
        Map<ProtocolCode, AdapterContext> contextMap = contexts == null ? Map.of() : contexts;
        Map<ProtocolCode, Registration> result = new LinkedHashMap<>();
        for (ProtocolAdapter adapter : candidates) {
            Objects.requireNonNull(adapter, "adapter must not be null");
            ProtocolDescriptor descriptor = adapter.descriptor();
            if (descriptor == null) {
                throw new IllegalStateException(
                        "adapter " + adapter.getClass().getName() + " returned null descriptor");
            }
            ProtocolCode code = descriptor.code();
            if (result.containsKey(code)) {
                throw new IllegalStateException("duplicate protocol code: " + code
                        + " declared by both " + result.get(code).adapter().getClass().getName()
                        + " and " + adapter.getClass().getName());
            }
            validateCapabilities(descriptor, adapter);
            validateRuntimeVersion(descriptor, adapter);
            AdapterContext context = contextMap.get(code);
            if (context == null) {
                throw new IllegalStateException("missing AdapterContext for protocol code: " + code);
            }
            result.put(code, new Registration(adapter, descriptor, context));
        }
        this.registrations = Collections.unmodifiableMap(result);
        log.info("[ypbin-iot] {} protocol adapter(s) registered: {}", registrations.size(),
                registrations.keySet().stream().map(ProtocolCode::value).toList());
    }

    /**
     * 按协议标识查找适配器。
     *
     * @param code 协议标识
     * @return 适配器；未注册时返回空 Optional
     */
    public Optional<ProtocolAdapter> find(ProtocolCode code) {
        Registration registration = registrations.get(code);
        return registration == null ? Optional.empty() : Optional.of(registration.adapter());
    }

    /**
     * 按协议标识取运行时上下文。
     *
     * @param code 协议标识
     * @return 上下文；未注册时返回空 Optional
     */
    public Optional<AdapterContext> contextOf(ProtocolCode code) {
        Registration registration = registrations.get(code);
        return registration == null ? Optional.empty() : Optional.of(registration.context());
    }

    /**
     * 已注册的全部协议描述符。
     *
     * @return 描述符列表；无适配器时返回空列表
     */
    public List<ProtocolDescriptor> descriptors() {
        return registrations.values().stream().map(Registration::descriptor).toList();
    }

    /**
     * 已注册的协议标识。
     *
     * @return 协议标识集合
     */
    public List<ProtocolCode> protocolCodes() {
        return new ArrayList<>(registrations.keySet());
    }

    /**
     * 已注册的适配器实例。
     *
     * @return 适配器列表
     */
    public List<ProtocolAdapter> adapters() {
        return registrations.values().stream().map(Registration::adapter).toList();
    }

    @Override
    public void close() {
        for (Registration registration : registrations.values()) {
            try {
                registration.adapter().close();
            } catch (RuntimeException ex) {
                log.error("[ypbin-iot] failed to close adapter {}", registration.adapter().getClass().getName(), ex);
            }
        }
    }

    private static void validateCapabilities(ProtocolDescriptor descriptor, ProtocolAdapter adapter) {
        boolean declaresBrowse = descriptor.supportsExtension(BrowseExtension.class);
        boolean supportsBrowse = descriptor.supports(ProtocolCapability.BROWSE);
        if (declaresBrowse != supportsBrowse) {
            throw new IllegalStateException("adapter " + adapter.getClass().getName()
                    + " declares BrowseExtension=" + declaresBrowse + " but BROWSE capability=" + supportsBrowse
                    + "; both must be declared together for protocol " + descriptor.code());
        }
        for (Class<? extends ProtocolExtension> extension : descriptor.extensions()) {
            if (!ProtocolExtension.class.isAssignableFrom(extension)) {
                throw new IllegalStateException("adapter " + adapter.getClass().getName()
                        + " declares a non-ProtocolExtension type: " + extension.getName());
            }
        }
    }

    private static void validateRuntimeVersion(ProtocolDescriptor descriptor, ProtocolAdapter adapter) {
        String minimum = descriptor.minimumRuntimeVersion();
        String maximum = descriptor.maximumRuntimeVersion();
        if ((minimum == null || minimum.isBlank()) && (maximum == null || maximum.isBlank())) {
            return;
        }
        SemanticVersion current;
        try {
            current = SemanticVersion.parse(RuntimeVersion.VERSION);
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("invalid runtime version: " + RuntimeVersion.VERSION, ex);
        }
        if (current.isWithin(minimum, maximum)) {
            return;
        }
        throw new IllegalStateException("adapter " + adapter.getClass().getName() + " (protocol "
                + descriptor.code() + ") requires iot-runtime in [" + minimum + ", " + maximum
                + ") but the running version is " + RuntimeVersion.VERSION);
    }

    /**
     * 注册项。
     *
     * @param adapter    适配器
     * @param descriptor 描述符
     * @param context    运行时上下文
     * @author wenbin
     * @since 2026-09-13
     */
    public record Registration(ProtocolAdapter adapter, ProtocolDescriptor descriptor, AdapterContext context) {
    }
}
