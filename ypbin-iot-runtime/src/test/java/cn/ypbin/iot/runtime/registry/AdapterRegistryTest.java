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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.DataEgress;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.model.ProbeResult;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AdapterRegistry} 启动期 fail-fast 校验测试。
 *
 * @author wenbin
 * @since 2026-09-13
 */
class AdapterRegistryTest {

    private DefaultTaskScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DefaultTaskScheduler(1, 16);
    }

    @AfterEach
    void tearDown() {
        scheduler.close();
    }

    @Test
    @DisplayName("REG-01 协议 code 冲突必须终止启动（不得后者覆盖前者）")
    void duplicateProtocolCodeMustFailFast() {
        StubAdapter first = new StubAdapter("dup", "0.0.0", "9.9.9", ProtocolCapability.READ);
        StubAdapter second = new StubAdapter("dup", "0.0.0", "9.9.9", ProtocolCapability.READ);
        assertThatThrownBy(() -> new AdapterRegistry(List.of(first, second), contexts(first, second)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate protocol code");
    }

    @Test
    @DisplayName("REG-02 运行时版本不在声明区间内必须终止启动")
    void incompatibleRuntimeVersionMustFailFast() {
        StubAdapter adapter = new StubAdapter("future", "99.0.0", "100.0.0", ProtocolCapability.READ);
        assertThatThrownBy(() -> new AdapterRegistry(List.of(adapter), contexts(adapter)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires iot-runtime");
    }

    @Test
    @DisplayName("REG-03 缺少运行时上下文必须终止启动")
    void missingContextMustFailFast() {
        StubAdapter adapter = new StubAdapter("noctx", "", "", ProtocolCapability.READ);
        assertThatThrownBy(() -> new AdapterRegistry(List.of(adapter), Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing AdapterContext");
    }

    @Test
    @DisplayName("REG-04 正常注册后可按 code 查找适配器与上下文")
    void registryMustExposeAdapterAndContext() {
        StubAdapter adapter = new StubAdapter("ok", "", "", ProtocolCapability.WRITE);
        AdapterRegistry registry = new AdapterRegistry(List.of(adapter), contexts(adapter));
        try {
            assertThat(registry.find(ProtocolCode.of("ok"))).contains(adapter);
            assertThat(registry.contextOf(ProtocolCode.of("ok"))).isPresent();
            assertThat(registry.protocolCodes()).containsExactly(ProtocolCode.of("ok"));
            assertThat(registry.descriptors()).hasSize(1);
            assertThat(registry.adapters()).containsExactly(adapter);
            assertThat(registry.find(ProtocolCode.of("absent"))).isEmpty();
        } finally {
            registry.close();
        }
    }

    @Test
    @DisplayName("REG-05 空适配器列表必须可正常构造（无协议时框架仍可用）")
    void emptyAdapterListMustBeAccepted() {
        AdapterRegistry registry = new AdapterRegistry(List.of(), Map.of());
        try {
            assertThat(registry.descriptors()).isEmpty();
            assertThat(registry.protocolCodes()).isEmpty();
        } finally {
            registry.close();
        }
    }

    private Map<ProtocolCode, AdapterContext> contexts(StubAdapter... adapters) {
        Map<ProtocolCode, AdapterContext> result = new LinkedHashMap<>();
        for (StubAdapter adapter : adapters) {
            ProtocolCode code = adapter.descriptor().code();
            result.put(code, new DefaultAdapterContext(code, DefaultAdapterSettings.defaults(),
                    new NoopEgress(), scheduler, NoopMetricsRecorder.INSTANCE,
                    new EnvCredentialResolver(), Clock.systemUTC(), 16));
        }
        return result;
    }

    /**
     * 最小适配器桩。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class StubAdapter implements ProtocolAdapter {

        private final ProtocolDescriptor descriptor;

        private StubAdapter(String code, String minVersion, String maxVersion,
                ProtocolCapability capability) {
            this.descriptor = ProtocolDescriptor.builder()
                    .code(ProtocolCode.of(code))
                    .name(code)
                    .capabilities(capability)
                    .runtimeVersionRange(minVersion, maxVersion)
                    .build();
        }

        @Override
        public ProtocolDescriptor descriptor() {
            return descriptor;
        }

        @Override
        public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("stub"));
        }

        @Override
        public CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
            return CompletableFuture.completedFuture(ProbeResult.reachable(descriptor, Map.of()));
        }
    }

    /**
     * 无操作数据出口。
     *
     * @author wenbin
     * @since 2026-09-13
     */
    private static final class NoopEgress implements DataEgress {

        @Override
        public void emit(DataBatch batch) {
            // 无操作
        }

        @Override
        public void emit(DeviceEvent event) {
            // 无操作
        }
    }
}
