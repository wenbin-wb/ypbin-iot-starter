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
package cn.ypbin.iot.test.tck;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.context.AdapterSettings;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.PointValue;
import cn.ypbin.iot.core.model.PointWrite;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.BrowseExtension;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolAdapter;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolConnection;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.runtime.context.DefaultAdapterContext;
import cn.ypbin.iot.runtime.context.DefaultAdapterSettings;
import cn.ypbin.iot.runtime.context.EnvCredentialResolver;
import cn.ypbin.iot.runtime.context.NoopMetricsRecorder;
import cn.ypbin.iot.runtime.scheduler.DefaultTaskScheduler;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

/**
 * 协议一致性测试套件（TCK）抽象基类。
 *
 * <p><b>这是本仓质量体系的核心</b>：任何协议适配器的测试类只要继承本类并实现四个抽象方法，
 * 就会自动跑完下列一致性用例。它保证的不是「某个协议能用」，而是
 * <b>所有协议在 SPI 层面行为一致</b>——能力不支持要 fail-fast、部分失败要逐项标记、
 * 关闭要幂等、未声明的扩展要返回空 Optional。</p>
 *
 * <p><b>能力驱动</b>：与声明能力无关的用例自动跳过（用 JUnit {@code Assumptions}），
 * 因此一个只支持 write 的透传适配器不会被要求实现 read。</p>
 *
 * <p>子类需要提供一个<b>可连接的</b> {@link ConnectionSpec}（通常是测试内起的模拟服务），
 * 以及对应的 {@link DeviceSpec}。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractProtocolAdapterTckTest {

    /** TCK 默认超时。 */
    protected static final Duration TCK_TIMEOUT = Duration.ofSeconds(10);

    private DefaultTaskScheduler scheduler;

    private RecordingEgress egress;

    private AdapterContext context;

    /** 被测适配器。 */
    protected abstract ProtocolAdapter adapter();

    /** 可连接的链路规格（子类需保证对端已就绪）。 */
    protected abstract ConnectionSpec connectionSpec();

    /** 被测设备规格。 */
    protected abstract DeviceSpec deviceSpec();

    /**
     * TCK 用例中使用的订阅地址。
     *
     * <p><b>为什么需要这个钩子</b>：不同协议的地址语法差异很大——OPC UA 的 NodeId 必须带命名空间
     * （{@code ns=2;s=X}），Modbus 必须带寄存器区（{@code holding:0}），
     * 硬编码一个协议无关字面量（如 {@code tck}）会让这些协议的订阅用例<b>在地址解析处就失败</b>，
     * 而失败原因看起来像被测代码有 bug。默认值保持向后兼容，需要的协议覆写即可。</p>
     *
     * @return 订阅地址
     */
    protected PointAddress subscriptionAddress() {
        return PointAddress.of("tck");
    }

    /**
     * 适配器配置；默认全默认值，子类可覆写调整超时等。
     *
     * @return 配置
     */
    protected AdapterSettings settings() {
        return DefaultAdapterSettings.defaults();
    }

    /**
     * 测试夹具启动钩子。
     *
     * <p>子类在此启动模拟服务、构造适配器。<b>不要</b>在子类里另写 {@code @BeforeAll}——
     * JUnit 的父类 {@code @BeforeAll} 先于子类执行，会导致本基类构建上下文时适配器仍为 null。</p>
     */
    protected void beforeTck() {
        // 默认无夹具
    }

    /**
     * 测试夹具释放钩子。
     */
    protected void afterTck() {
        // 默认无夹具
    }

    @BeforeAll
    void setUpTck() {
        beforeTck();
        scheduler = new DefaultTaskScheduler(4, 1024);
        egress = new RecordingEgress();
        context = new DefaultAdapterContext(adapter().descriptor().code(), settings(), egress, scheduler,
                NoopMetricsRecorder.INSTANCE, new EnvCredentialResolver(), Clock.systemUTC(), 256);
    }

    @AfterAll
    void tearDownTck() {
        if (scheduler != null) {
            scheduler.close();
        }
        afterTck();
    }

    /**
     * 供子类断言出口数据。
     *
     * @return 记录型出口
     */
    protected RecordingEgress egress() {
        return egress;
    }

    /**
     * 供子类取得的运行时上下文。
     *
     * @return 上下文
     */
    protected AdapterContext context() {
        return context;
    }

    /**
     * 打开链路并绑定设备会话。
     *
     * @return 设备会话
     */
    protected DeviceSession openSession() {
        ProtocolConnection connection = adapter().open(connectionSpec(), context)
                .toCompletableFuture()
                .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();
        return adapter().bind(connection, deviceSpec(), context)
                .toCompletableFuture()
                .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                .join();
    }

    // ------------------------------------------------------------------
    // 描述符与能力一致性
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TCK-01 描述符必须非空且字段完备")
    void descriptorMustBeWellFormed() {
        ProtocolDescriptor descriptor = adapter().descriptor();
        assertThat(descriptor).as("descriptor 不得为 null").isNotNull();
        assertThat(descriptor.code()).as("协议 code 不得为 null").isNotNull();
        assertThat(descriptor.name()).as("协议展示名不得为空").isNotBlank();
        assertThat(descriptor.capabilities()).as("能力集合不得为 null").isNotNull();
    }

    @Test
    @DisplayName("TCK-02 capabilities() 必须与描述符一致")
    void capabilitiesMustMatchDescriptor() {
        assertThat(adapter().capabilities()).isEqualTo(adapter().descriptor().capabilities());
    }

    @Test
    @DisplayName("TCK-03 声明 BrowseExtension 必须同时声明 BROWSE 能力")
    void browseExtensionMustPairWithCapability() {
        ProtocolDescriptor descriptor = adapter().descriptor();
        boolean declaresExtension = descriptor.supportsExtension(BrowseExtension.class);
        boolean declaresCapability = descriptor.supports(ProtocolCapability.BROWSE);
        assertThat(declaresExtension)
                .as("BrowseExtension 声明与 BROWSE 能力必须成对出现")
                .isEqualTo(declaresCapability);
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TCK-04 建链 + 绑定后会话状态必须可用")
    void sessionMustBeUsableAfterBind() {
        DeviceSession session = openSession();
        try {
            assertThat(session.sessionId()).isNotBlank();
            assertThat(session.connectionId()).isNotBlank();
            assertThat(session.boundAt()).isNotNull();
            assertThat(session.device().deviceId()).isEqualTo(deviceSpec().deviceId());
            assertThat(session.state().isUsable())
                    .as("绑定完成后会话状态应为 ONLINE 或 DEGRADED，实际为 %s", session.state())
                    .isTrue();
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCK-05 close() 必须幂等")
    void closeMustBeIdempotent() {
        DeviceSession session = openSession();
        session.close().toCompletableFuture().orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        // 第二次与第三次关闭都必须正常返回，不得抛异常
        session.close().toCompletableFuture().orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
        session.close().toCompletableFuture().orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    @Test
    @DisplayName("TCK-06 ping() 必须返回结果而不抛异常")
    void pingMustNotThrow() {
        DeviceSession session = openSession();
        try {
            PingResult result = session.ping().toCompletableFuture()
                    .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
            assertThat(result).isNotNull();
            if (!result.alive()) {
                assertThat(result.failureReason())
                        .as("失活时必须给出消息键原因").isNotBlank();
            }
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    // ------------------------------------------------------------------
    // 能力一致性：未声明能力必须 fail-fast
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TCK-07 未声明 READ 时 read() 必须抛 UnsupportedCapabilityException")
    void readMustFailFastWhenNotDeclared() {
        Assumptions.assumeFalse(hasCapability(ProtocolCapability.READ));
        DeviceSession session = openSession();
        try {
            CompletionStage<ReadResult> stage = session.read(ReadRequest.of(PointAddress.of("tck")));
            Throwable cause = stage.toCompletableFuture()
                    .handle((value, error) -> error)
                    .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            assertThat(cause)
                    .as("未声明 READ 时 read() 必须 fail-fast，而不是返回空结果")
                    .isInstanceOf(UnsupportedCapabilityException.class);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    @Test
    @DisplayName("TCK-08 未声明 WRITE 时 write() 必须抛 UnsupportedCapabilityException")
    void writeMustFailFastWhenNotDeclared() {
        Assumptions.assumeFalse(hasCapability(ProtocolCapability.WRITE));
        DeviceSession session = openSession();
        try {
            CompletionStage<WriteResult> stage = session.write(
                    WriteRequest.of(new PointWrite(PointAddress.of("tck"), "1")));
            Throwable cause = stage.toCompletableFuture()
                    .handle((value, error) -> error)
                    .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            assertThat(cause).isInstanceOf(UnsupportedCapabilityException.class);
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    // ------------------------------------------------------------------
    // 扩展机制
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TCK-09 未声明扩展的 unwrap 必须返回空 Optional（而非抛异常）")
    void unwrapMustReturnEmptyForUndeclaredExtension() {
        Assumptions.assumeFalse(adapter().descriptor()
                .supportsExtension(BrowseExtension.class));
        DeviceSession session = openSession();
        try {
            Optional<BrowseExtension> extension = session.unwrap(BrowseExtension.class);
            assertThat(extension)
                    .as("unwrap 是能力探测：未支持应返回空 Optional，而不是抛异常")
                    .isEmpty();
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    // ------------------------------------------------------------------
    // 订阅：取消后必须幂等
    // ------------------------------------------------------------------

    @Test
    @DisplayName("TCK-10 订阅后取消必须幂等")
    void unsubscribeMustBeIdempotent() {
        Assumptions.assumeTrue(hasAnySubscriptionCapability());
        DeviceSession session = openSession();
        try {
            SubscriptionHandle handle = session.subscribe(
                            SubscribeRequest.of(List.of(subscriptionAddress())), null)
                    .toCompletableFuture()
                    .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                    .join();
            assertThat(handle.subscriptionId()).isNotBlank();
            assertThat(handle.active()).isTrue();
            session.unsubscribe(handle).toCompletableFuture()
                    .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
            // 重复取消必须成功返回（幂等）
            session.unsubscribe(handle).toCompletableFuture()
                    .orTimeout(TCK_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS).join();
            assertThat(handle.active()).as("取消后句柄必须变为非活跃").isFalse();
        } finally {
            session.close().toCompletableFuture().join();
        }
    }

    /**
     * 是否声明了指定能力。
     *
     * @param capability 能力
     * @return 声明返回 {@code true}
     */
    protected final boolean hasCapability(ProtocolCapability capability) {
        Set<ProtocolCapability> capabilities = adapter().capabilities();
        return capabilities.contains(capability);
    }

    /**
     * 是否声明了任意一种订阅能力。
     *
     * @return 声明返回 {@code true}
     */
    protected final boolean hasAnySubscriptionCapability() {
        return hasCapability(ProtocolCapability.SUBSCRIBE_NATIVE)
                || hasCapability(ProtocolCapability.SUBSCRIBE_POLLING)
                || hasCapability(ProtocolCapability.SUBSCRIBE_STREAM);
    }

    /**
     * 断言点位值非空且带时间戳（供子类复用）。
     *
     * @param value 点位值
     */
    protected final void assertPointValueWellFormed(PointValue value) {
        assertThat(value).isNotNull();
        assertThat(value.address()).isNotNull();
        assertThat(value.timestamp()).isNotNull();
        assertThat(value.quality()).isNotNull();
        if (!value.isGood()) {
            assertThat(value.qualityReason())
                    .as("非 GOOD 质量必须给出原因")
                    .isNotBlank();
        }
    }

    /**
     * 会话状态断言辅助：确认状态属于给定集合。
     *
     * @param session 会话
     * @param states  允许的状态
     */
    protected final void assertStateIn(DeviceSession session, SessionState... states) {
        assertThat(session.state()).isIn((Object[]) states);
    }
}
