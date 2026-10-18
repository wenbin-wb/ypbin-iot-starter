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
package cn.ypbin.iot.spring.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import cn.ypbin.iot.core.exception.AddressParseException;
import cn.ypbin.iot.core.exception.ProtocolException;
import cn.ypbin.iot.core.i18n.IotMessageKeys;
import cn.ypbin.iot.core.model.DataListener;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.PointAddress;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import cn.ypbin.iot.core.protocol.AddressExpansionExtension;
import cn.ypbin.iot.core.protocol.DeviceSession;
import cn.ypbin.iot.core.protocol.ProtocolCapability;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import cn.ypbin.iot.core.protocol.ProtocolDescriptor;
import cn.ypbin.iot.core.protocol.SubscriptionAddressing;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 订阅编排测试（issue #37 验收标准 1）。
 *
 * <p>覆盖验收要求的六个方面，每条用例都对应一个<b>真实故障形态</b>（而不是覆盖率的陪衬）：</p>
 * <ol>
 *   <li>(a) 整批 vs 逐点位单订阅分流 —— 判据必须来自 {@code ProtocolDescriptor} 的声明；</li>
 *   <li>(b) 多寄存器地址展开 —— 兄弟地址由协议声明的地址算术给出，而不是框架猜字符串；</li>
 *   <li>(c) 部分成功只补失败地址 —— 变异点：改成全量重试后本组用例必须转红；</li>
 *   <li>(d) 并发同设备同会话只发一次（在途去重）；</li>
 *   <li>(e) 换会话实例后触发重订 —— 用<b>字符串相同但实例不同</b>的会话验证；</li>
 *   <li>(f) 退避上限与重置。</li>
 * </ol>
 *
 * <p>测试不依赖 Spring 容器：编排器只用 JDK + core 类型，会话/协议声明都用可控桩。</p>
 *
 * @author wenbin
 * @since 2026-10-18
 */
class SubscriptionOrchestratorTest {

    private static final ProtocolCode MODBUS = ProtocolCode.of("modbus");

    private static final ProtocolCode TCP = ProtocolCode.of("tcp");

    private static final String DEVICE_ID = "dev-1";

    private static final Duration WAIT = Duration.ofSeconds(5);

    // ------------------------------------------------------------------
    // (a) 整批 vs 逐点位分流
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ORCH-01(a) 协议声明整批订阅时：一次 subscribe 携带全部地址（declaration 驱动，不是按协议码硬编码）")
    void batchDeclaredProtocolMustSubscribeInOneCall() {
        StubSession session = new StubSession(device(MODBUS), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());

        SubscriptionResult result = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("holding:0"), SubscriptionPoint.of("holding:10")))));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(session.requests()).hasSize(1);
        assertThat(session.requests().get(0).addresses()).extracting(PointAddress::raw)
                .containsExactly("holding:0", "holding:10");
        assertThat(result.outcome().subscribed()).extracting(PointAddress::raw)
                .containsExactlyInAnyOrder("holding:0", "holding:10");
    }

    @Test
    @DisplayName("ORCH-02(a) 协议声明逐点位单订阅时：N 个地址拆成 N 次单地址请求")
    void perAddressDeclaredProtocolMustSubscribeOneAddressPerCall() {
        StubSession session = new StubSession(device(TCP), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());

        SubscriptionResult result = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("a"), SubscriptionPoint.of("b"), SubscriptionPoint.of("c")))));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(session.requests()).hasSize(3);
        assertThat(session.requests()).allSatisfy(request ->
                assertThat(request.addresses()).hasSize(1));
        assertThat(session.requests()).extracting(request -> request.addresses().get(0).raw())
                .containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("ORCH-03(a) 未声明订阅寻址的协议按保守侧（逐点位）处理，并只 WARN 一次")
    void undeclaredProtocolFallsBackToPerAddress() {
        StubSession session = new StubSession(device(TCP), "s-1");
        // 不注册任何描述符 ⇒ 查不到声明
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(null, Clock.systemUTC());

        SubscriptionResult result = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("a"), SubscriptionPoint.of("b")))));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(session.requests())
                .as("未声明时必须按逐点位单订阅（默认整批的失败模式是静默只订第 0 个地址）")
                .hasSize(2);
    }

    @Test
    @DisplayName("ORCH-04(a) 同一 raw 地址重复出现只订阅一次（相邻点位展开重叠时不去重会重复读数）")
    void duplicateRawAddressesMustBeSubscribedOnce() {
        StubSession session = new StubSession(device(MODBUS), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());

        await(orchestrator.subscribe(item(session, List.of(SubscriptionPoint.of("holding:0"),
                SubscriptionPoint.of("holding:0")))));

        assertThat(session.requests()).hasSize(1);
        assertThat(session.requests().get(0).addresses()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // (b) 多寄存器展开
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ORCH-05(b) wordCount=2 必须展开 base+1；展开规则来自协议扩展而不是框架猜字符串")
    void multiRegisterPointMustExpandSiblingAddresses() {
        StubSession session = new StubSession(device(MODBUS), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());

        SubscriptionResult result = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("holding:0", 2)))));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(session.requests().get(0).addresses()).extracting(PointAddress::raw)
                .as("2 个寄存器 ⇒ 基址 + base+1")
                .containsExactly("holding:0", "holding:1");
    }

    @Test
    @DisplayName("ORCH-06(b) wordCount=4 必须展开 base+1..base+3（大 wordCount 不只 +1，与宿主 639 行实现同口径）")
    void largeWordCountMustExpandAllSiblings() {
        StubSession session = new StubSession(device(MODBUS), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());

        await(orchestrator.subscribe(item(session, List.of(SubscriptionPoint.of("holding:10", 4)))));

        assertThat(session.requests().get(0).addresses()).extracting(PointAddress::raw)
                .containsExactly("holding:10", "holding:11", "holding:12", "holding:13");
    }

    @Test
    @DisplayName("ORCH-07(b) 协议未声明地址展开扩展时，多寄存器点位必须显式失败而不是只订基址")
    void multiRegisterWithoutExpansionExtensionMustFailExplicitly() {
        // 会话不提供 AddressExpansionExtension
        StubSession session = new StubSession(device(MODBUS), "s-1", false);
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());

        SubscriptionResult result = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("holding:0", 2)))));

        assertThat(result.status()).as("只剩展开失败，没有任何地址可发").isEqualTo(SubscriptionStatus.FAILED);
        assertThat(session.requests()).as("绝不能只订基址（会让多寄存器解码拿到半截字节）").isEmpty();
        assertThat(result.outcome().failures()).hasSize(1);
        assertThat(result.outcome().failures().get(0).reasonKey())
                .isEqualTo(IotMessageKeys.SUBSCRIPTION_ADDRESS_EXPANSION_UNSUPPORTED);
    }

    @Test
    @DisplayName("ORCH-08(b) 展开越界（协议抛 AddressParseException）必须记为失败地址，不扩散成设备级异常")
    void expansionOverflowMustBeRecordedAsAddressFailure() {
        StubSession session = new StubSession(device(MODBUS), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());

        // 基址已在上界：+1 会越界
        SubscriptionResult result = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("holding:65535", 2)))));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.FAILED);
        assertThat(result.outcome().failures()).hasSize(1);
        assertThat(result.outcome().failures().get(0).reasonKey())
                .isEqualTo(AddressParseException.MESSAGE_KEY);
        assertThat(session.requests())
                .as("缺寄存器的点位不订基址：半截字节 + GOOD 质量比直接失败更难排查")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // (c) 部分成功只补失败地址 —— 变异点
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ORCH-09(c) 逐点位部分成功后：下一轮只重发失败地址，已成功地址绝不重复订阅")
    void partialSuccessMustRetryOnlyFailedAddresses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        StubSession session = new StubSession(device(TCP), "s-1");
        session.failAddresses("b");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), clock, new SubscriptionBackoffPolicy(
                        Duration.ofSeconds(10), Duration.ofSeconds(40), 2.0D));
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("a"),
                SubscriptionPoint.of("b"), SubscriptionPoint.of("c")));

        SubscriptionResult first = await(orchestrator.subscribe(item));
        assertThat(first.status()).isEqualTo(SubscriptionStatus.PARTIAL);
        assertThat(first.outcome().subscribed()).extracting(PointAddress::raw)
                .containsExactlyInAnyOrder("a", "c");
        assertThat(first.outcome().retryAddresses()).extracting(PointAddress::raw)
                .as("只补失败地址的入口必须只含失败项").containsExactly("b");
        int requestsAfterFirstRound = session.requests().size();
        assertThat(requestsAfterFirstRound).isEqualTo(3);

        // 第二轮：会话不变，只应补发 b（先走出退避窗口——部分失败也会退避，避免每轮反复敲坏地址）
        clock.advance(Duration.ofSeconds(11));
        session.healAll();
        SubscriptionResult second = await(orchestrator.subscribe(item));

        assertThat(second.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        List<SubscribeRequest> retried = session.requests().subList(requestsAfterFirstRound,
                session.requests().size());
        assertThat(retried)
                .as("部分成功后必须**只补失败地址**；全量重试会让 a/c 重复挂监听 ⇒ 同一点位重复读数")
                .hasSize(1);
        assertThat(retried.get(0).addresses()).extracting(PointAddress::raw).containsExactly("b");
    }

    @Test
    @DisplayName("ORCH-10(c) 整批调用失败时该批全部地址都记为失败，且下一轮不丢地址（不会只重发第一个）")
    void batchCallFailureMustRecordEveryAddressOfTheCall() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        StubSession session = new StubSession(device(MODBUS), "s-1");
        session.failAddresses("holding:1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), clock, new SubscriptionBackoffPolicy(
                        Duration.ofSeconds(10), Duration.ofSeconds(40), 2.0D));
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("holding:0"),
                SubscriptionPoint.of("holding:1")));

        SubscriptionResult first = await(orchestrator.subscribe(item));
        assertThat(first.status())
                .as("整批是一次调用：失败时无法归因到单个地址，只能如实记为整批失败")
                .isEqualTo(SubscriptionStatus.FAILED);
        assertThat(first.outcome().retryAddresses()).extracting(PointAddress::raw)
                .as("补订集合必须覆盖该批全部地址，不能漏地址")
                .containsExactlyInAnyOrder("holding:0", "holding:1");

        clock.advance(Duration.ofSeconds(11));
        session.healAll();
        SubscriptionResult second = await(orchestrator.subscribe(item));

        assertThat(second.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(session.requests()).hasSize(2);
        assertThat(session.requests().get(1).addresses()).extracting(PointAddress::raw)
                .containsExactlyInAnyOrder("holding:0", "holding:1");
    }

    @Test
    @DisplayName("ORCH-10b(c) 整批模式下「部分点位展开失败」必须只补失败点位，不重发已成功地址")
    void batchModePartialExpansionFailureMustRetryOnlyFailedPoint() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        StubSession session = new StubSession(device(MODBUS), "s-1");
        session.disableExpansion();
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), clock, new SubscriptionBackoffPolicy(
                        Duration.ofSeconds(10), Duration.ofSeconds(40), 2.0D));
        // holding:0 单寄存器（可订）；holding:10 占 2 个寄存器但协议暂不提供地址算术 ⇒ 该点位失败
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("holding:0"),
                SubscriptionPoint.of("holding:10", 2)));

        SubscriptionResult first = await(orchestrator.subscribe(item));
        assertThat(first.status()).isEqualTo(SubscriptionStatus.PARTIAL);
        assertThat(first.outcome().subscribed()).extracting(PointAddress::raw)
                .containsExactly("holding:0");
        assertThat(first.outcome().retryAddresses()).extracting(PointAddress::raw)
                .as("补订集合只含失败点位").containsExactly("holding:10");

        clock.advance(Duration.ofSeconds(11));
        session.enableExpansion();
        await(orchestrator.subscribe(item));

        List<SubscribeRequest> retried = session.requests().subList(1, session.requests().size());
        assertThat(retried).as("第二轮只发起失败点位的订阅").hasSize(1);
        assertThat(retried.get(0).addresses()).extracting(PointAddress::raw)
                .as("已成功的 holding:0 不得重发（会重复挂监听 ⇒ 重复读数）")
                .containsExactly("holding:10", "holding:11");
    }

    @Test
    @DisplayName("ORCH-11(c) 全部成功后再轮询不得再发订阅（幂等），状态为 ALREADY_SUBSCRIBED")
    void fullySubscribedDeviceMustNotResubscribe() {
        StubSession session = new StubSession(device(MODBUS), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(MODBUS,
                SubscriptionAddressing.BATCH), Clock.systemUTC());
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("holding:0")));

        await(orchestrator.subscribe(item));
        SubscriptionResult second = await(orchestrator.subscribe(item));

        assertThat(second.status()).isEqualTo(SubscriptionStatus.ALREADY_SUBSCRIBED);
        assertThat(session.requests()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // (d) 在途去重
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ORCH-12(d) 并发同设备同会话只发起一次协议层订阅（慢订阅不得被下一轮重复发起）")
    void concurrentSubscriptionsForSameDeviceAndSessionMustDispatchOnce() {
        StubSession session = new StubSession(device(TCP), "s-1");
        session.blockSubscribe();
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("a"),
                SubscriptionPoint.of("b")));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CompletableFuture<SubscriptionResult> first =
                    CompletableFuture.supplyAsync(() -> orchestrator.subscribe(item), pool)
                            .thenCompose(stage -> stage.toCompletableFuture());
            // 等第一次确实发起（进入阻塞点）
            assertThat(session.awaitSubscribedInvocations(1, WAIT)).isTrue();
            CompletableFuture<SubscriptionResult> second =
                    CompletableFuture.supplyAsync(() -> orchestrator.subscribe(item), pool)
                            .thenCompose(stage -> stage.toCompletableFuture());

            session.releaseSubscribe();
            await(first);
            SubscriptionResult secondResult = await(second);

            assertThat(session.subscribeInvocations())
                    .as("同设备同会话的并发订阅必须只发一次")
                    .isEqualTo(2);  // 两个地址各一次（逐点位形态），但第二轮不得新增调用
            assertThat(secondResult.status()).isIn(SubscriptionStatus.SUBSCRIBED,
                    SubscriptionStatus.ALREADY_SUBSCRIBED);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ORCH-13(d) 同步抛异常的订阅调用必须收敛为失败，并摘掉在途标记（否则该设备永久无法订阅）")
    void synchronousSubscribeFailureMustReleaseInFlightMark() {
        StubSession session = new StubSession(device(TCP), "s-1");
        session.throwOnSubscribe(true);
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("a")));

        SubscriptionResult first = await(orchestrator.subscribe(item));
        assertThat(first.status()).isEqualTo(SubscriptionStatus.FAILED);
        assertThat(first.outcome().failures()).hasSize(1);

        // 修好之后立刻可重试：若在途标记没摘，这里会一直返回上一次的失败结果
        session.throwOnSubscribe(false);
        session.healAll();
        SubscriptionResult second = await(orchestrator.subscribe(item));
        assertThat(second.status()).as("退避窗口内应明确告知，而不是复用上一轮的失败结果")
                .isEqualTo(SubscriptionStatus.SKIPPED_BACKOFF);
        assertThat(second.nextRetryAt()).isNotNull();
    }

    // ------------------------------------------------------------------
    // (e) 换会话实例后触发重订
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ORCH-14(e) 会话实例变化必须触发重订（即使 sessionId 字符串完全相同）")
    void sessionInstanceChangeMustTriggerResubscribe() {
        DeviceSpec device = device(TCP);
        StubSession firstSession = new StubSession(device, "same-id");
        StubSession secondSession = new StubSession(device, "same-id");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());
        List<SubscriptionPoint> points = List.of(SubscriptionPoint.of("a"));

        await(orchestrator.subscribe(item(firstSession, points)));
        assertThat(firstSession.requests()).hasSize(1);

        // 框架重连后新建会话实例，但协议模块的 sessionId 由「设备+链路」拼成、字符串可能相同
        SubscriptionResult result = await(orchestrator.subscribe(item(secondSession, points)));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(secondSession.requests())
                .as("换会话实例后必须在新会话上重订，否则表现为「链路恢复但数据永久停更」")
                .hasSize(1);
        assertThat(firstSession.requests()).hasSize(1);
    }

    @Test
    @DisplayName("ORCH-15(e) 会话实例未变时不得重订（避免每轮 tick 都重复订阅）")
    void sameSessionInstanceMustNotResubscribe() {
        StubSession session = new StubSession(device(TCP), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("a")));

        await(orchestrator.subscribe(item));
        await(orchestrator.subscribe(item));
        await(orchestrator.subscribe(item));

        assertThat(session.requests()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // (f) 退避上限与重置
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ORCH-16(f) 连续失败按轮指数退避，且到达上限后不再增长")
    void backoffMustGrowExponentiallyAndCapAtMax() {
        SubscriptionBackoffPolicy policy = new SubscriptionBackoffPolicy(Duration.ofSeconds(10),
                Duration.ofSeconds(40), 2.0D);
        assertThat(policy.delayFor(1)).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.delayFor(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.delayFor(3)).isEqualTo(Duration.ofSeconds(40));
        assertThat(policy.delayFor(4)).as("到上限后不再增长").isEqualTo(Duration.ofSeconds(40));
        assertThat(policy.delayFor(100)).isEqualTo(Duration.ofSeconds(40));
        assertThat(policy.delayFor(0)).as("非法 attempt 归一到初始值").isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("ORCH-17(f) 失败后进入退避窗口：窗口内不再发起；窗口过去后可重试")
    void failureMustArmBackoffAndSkipUntilWindowPasses() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        StubSession session = new StubSession(device(TCP), "s-1");
        session.failAddresses("a");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), clock, new SubscriptionBackoffPolicy(
                        Duration.ofSeconds(10), Duration.ofSeconds(40), 2.0D));
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("a")));

        SubscriptionResult failed = await(orchestrator.subscribe(item));
        assertThat(failed.status()).isEqualTo(SubscriptionStatus.FAILED);
        assertThat(failed.nextRetryAt()).isEqualTo(clock.instant().plusSeconds(10));
        assertThat(orchestrator.backoffDeviceCount()).isEqualTo(1);

        // 窗口内（+9s）：跳过且不发新请求
        clock.advance(Duration.ofSeconds(9));
        SubscriptionResult skipped = await(orchestrator.subscribe(item));
        assertThat(skipped.status()).isEqualTo(SubscriptionStatus.SKIPPED_BACKOFF);
        assertThat(session.requests()).as("退避窗口内绝不发起协议调用").hasSize(1);

        // 窗口外（+11s）：允许重试
        clock.advance(Duration.ofSeconds(2));
        session.healAll();
        SubscriptionResult retried = await(orchestrator.subscribe(item));
        assertThat(retried.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(session.requests()).hasSize(2);
        assertThat(orchestrator.backoffDeviceCount()).as("成功后必须清除退避").isZero();
    }

    @Test
    @DisplayName("ORCH-18(f) 换会话实例必须重置退避（否则一次失败会把「设备恢复」挡在窗口外）")
    void sessionInstanceChangeMustResetBackoff() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        DeviceSpec device = device(TCP);
        StubSession failing = new StubSession(device, "same-id");
        failing.failAddresses("a");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), clock, new SubscriptionBackoffPolicy(
                        Duration.ofMinutes(5), Duration.ofMinutes(10), 2.0D));
        List<SubscriptionPoint> points = List.of(SubscriptionPoint.of("a"));

        await(orchestrator.subscribe(item(failing, points)));
        assertThat(orchestrator.backoffDeviceCount()).isEqualTo(1);

        // 框架重连：新会话实例，但字符串 id 相同，仍在原退避窗口内
        StubSession fresh = new StubSession(device, "same-id");
        SubscriptionResult result = await(orchestrator.subscribe(item(fresh, points)));

        assertThat(result.status()).as("会话实例变了就必须立刻重试，不受旧退避窗口约束")
                .isEqualTo(SubscriptionStatus.SUBSCRIBED);
        assertThat(fresh.requests()).hasSize(1);
    }

    @Test
    @DisplayName("ORCH-19(f) 一设备一轮 N 个地址同时失败只记一次退避（否则第一轮就冲到上限）")
    void oneRoundFailureMustCountBackoffOnce() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        StubSession session = new StubSession(device(TCP), "s-1");
        session.failAddresses("a", "b", "c");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), clock, new SubscriptionBackoffPolicy(
                        Duration.ofSeconds(10), Duration.ofSeconds(40), 2.0D));

        await(orchestrator.subscribe(item(session, List.of(SubscriptionPoint.of("a"),
                SubscriptionPoint.of("b"), SubscriptionPoint.of("c")))));

        assertThat(orchestrator.backoffDeviceCount()).isEqualTo(1);
        clock.advance(Duration.ofSeconds(9));
        SubscriptionResult skipped = await(orchestrator.subscribe(item(session,
                List.of(SubscriptionPoint.of("a"), SubscriptionPoint.of("b"), SubscriptionPoint.of("c")))));
        assertThat(skipped.status()).isEqualTo(SubscriptionStatus.SKIPPED_BACKOFF);
    }

    @Test
    @DisplayName("ORCH-20(f) forget 设备后清除跟踪：同 id 重新出现必须重新订阅，且清掉退避")
    void forgetMustClearTrackingAndBackoff() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-18T00:00:00Z"));
        StubSession session = new StubSession(device(TCP), "s-1");
        session.failAddresses("a");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), clock);
        SubscriptionBatchItem item = item(session, List.of(SubscriptionPoint.of("a")));

        await(orchestrator.subscribe(item));
        assertThat(orchestrator.backoffDeviceCount()).isEqualTo(1);
        orchestrator.forget(DEVICE_ID);
        assertThat(orchestrator.trackedDeviceCount()).isZero();
        assertThat(orchestrator.backoffDeviceCount()).as("设备移除必须清掉退避").isZero();

        session.healAll();
        SubscriptionResult result = await(orchestrator.subscribe(item));
        assertThat(result.status()).isEqualTo(SubscriptionStatus.SUBSCRIBED);
    }

    @Test
    @DisplayName("ORCH-21(f) 非法退避配置必须被归一化（不阻断启动），且最大退避不得小于初始值")
    void illegalBackoffConfigurationMustBeNormalized() {
        // 非法输入必须在构造前就能被识别（构造后字段已被归一化为默认值，无法再区分）
        assertThat(SubscriptionBackoffPolicy.isValidInput(Duration.ofSeconds(-1), Duration.ZERO, 0.5D))
                .isFalse();
        SubscriptionBackoffPolicy negative = new SubscriptionBackoffPolicy(Duration.ofSeconds(-1),
                Duration.ZERO, 0.5D);
        assertThat(negative.initialDelay()).isEqualTo(SubscriptionBackoffPolicy.DEFAULT_INITIAL_DELAY);
        assertThat(negative.maxDelay()).isEqualTo(SubscriptionBackoffPolicy.DEFAULT_MAX_DELAY);
        assertThat(negative.multiplier()).isEqualTo(SubscriptionBackoffPolicy.DEFAULT_MULTIPLIER);

        assertThat(SubscriptionBackoffPolicy.isValidInput(Duration.ofSeconds(60), Duration.ofSeconds(5), 2.0D))
                .as("maxDelay < initialDelay 属非法输入").isFalse();
        SubscriptionBackoffPolicy inverted = new SubscriptionBackoffPolicy(Duration.ofSeconds(60),
                Duration.ofSeconds(5), 2.0D);
        assertThat(inverted.maxDelay())
                .as("maxDelay < initialDelay 时钳到 initialDelay，否则第一步就比初始值还短")
                .isEqualTo(Duration.ofSeconds(60));
        assertThat(SubscriptionBackoffPolicy.isValidInput(Duration.ofSeconds(1), Duration.ofSeconds(2), 2.0D))
                .as("合法输入必须判为合法").isTrue();
    }

    @Test
    @DisplayName("ORCH-22 设备无可订阅点位时不得发起订阅，状态为 NO_ADDRESSES")
    void deviceWithoutPointsMustNotSubscribe() {
        StubSession session = new StubSession(device(TCP), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());

        SubscriptionResult result = await(orchestrator.subscribe(item(session, List.of())));

        assertThat(result.status()).isEqualTo(SubscriptionStatus.NO_ADDRESSES);
        assertThat(session.requests()).isEmpty();
    }

    @Test
    @DisplayName("ORCH-23 订阅请求必须携带设备的采集周期（轮询式订阅靠它工作）")
    void subscribeRequestMustCarryPollInterval() {
        StubSession session = new StubSession(device(TCP), "s-1");
        DefaultSubscriptionOrchestrator orchestrator = orchestrator(descriptor(TCP,
                SubscriptionAddressing.PER_ADDRESS), Clock.systemUTC());
        DeviceSpec polling = new DeviceSpec(DEVICE_ID, "dev", TCP, "conn-1", "", Duration.ofSeconds(7),
                Map.of());
        StubSession sessionWithPoll = new StubSession(polling, "s-1");

        await(orchestrator.subscribe(new SubscriptionBatchItem(polling, sessionWithPoll,
                List.of(SubscriptionPoint.of("a")), null)));

        assertThat(sessionWithPoll.requests().get(0).samplingInterval()).isEqualTo(Duration.ofSeconds(7));
        assertThat(session.requests()).isEmpty();
    }

    // ------------------------------------------------------------------
    // 帮助方法
    // ------------------------------------------------------------------

    private static SubscriptionResult await(CompletionStage<SubscriptionResult> stage) {
        return stage.toCompletableFuture().orTimeout(WAIT.toMillis(), TimeUnit.MILLISECONDS).join();
    }

    private static DefaultSubscriptionOrchestrator orchestrator(ProtocolDescriptor descriptor,
            Clock clock) {
        return orchestrator(descriptor, clock, SubscriptionBackoffPolicy.defaults());
    }

    private static DefaultSubscriptionOrchestrator orchestrator(ProtocolDescriptor descriptor,
            Clock clock, SubscriptionBackoffPolicy policy) {
        ProtocolDescriptorSource source = descriptor == null
                ? ProtocolDescriptorSource.empty()
                : ProtocolDescriptorSource.of(List.of(descriptor));
        return new DefaultSubscriptionOrchestrator(source, policy, clock);
    }

    private static ProtocolDescriptor descriptor(ProtocolCode code, SubscriptionAddressing addressing) {
        return ProtocolDescriptor.builder()
                .code(code)
                .name(code.value())
                .capabilities(ProtocolCapability.SUBSCRIBE_NATIVE)
                .subscriptionAddressing(addressing)
                .extensions(TestAddressExpansion.class)
                .build();
    }

    private static DeviceSpec device(ProtocolCode code) {
        return new DeviceSpec(DEVICE_ID, "dev", code, "conn-1", "", Duration.ZERO, Map.of());
    }

    private static SubscriptionBatchItem item(DeviceSession session, List<SubscriptionPoint> points) {
        return new SubscriptionBatchItem(session.device(), session, points, null);
    }

    /**
     * 可流逝时钟：让退避窗口断言不依赖挂钟（本仓同一条教训：不用 sleep 断言时间）。
     *
     * @author wenbin
     * @since 2026-10-18
     */
    static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        private void advance(Duration duration) {
            this.now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * 测试用地址算术扩展：只认 {@code type:offset} 的显式写法（越界抛 {@link AddressParseException}）。
     *
     * <p>刻意不复制 Modbus 的全部写法：本测试验证的是「展开由协议扩展驱动」这条链路，
     * 具体语法由协议模块自己的测试守。</p>
     *
     * @author wenbin
     * @since 2026-10-18
     */
    static final class TestAddressExpansion implements AddressExpansionExtension {

        private static final int MAX_OFFSET = 65535;

        @Override
        public ProtocolCode protocol() {
            return MODBUS;
        }

        @Override
        public PointAddress offset(PointAddress base, int delta) {
            String raw = base.raw();
            int colon = raw.indexOf(':');
            if (colon <= 0 || delta < 1) {
                throw new AddressParseException(MODBUS, raw, "not an explicit type:offset form");
            }
            String type = raw.substring(0, colon);
            int offset;
            try {
                offset = Integer.parseInt(raw.substring(colon + 1));
            } catch (NumberFormatException ex) {
                throw new AddressParseException(MODBUS, raw, "offset is not a number");
            }
            if (offset + delta > MAX_OFFSET) {
                throw new AddressParseException(MODBUS, raw, "offset out of range: " + (offset + delta));
            }
            return PointAddress.of(type + ":" + (offset + delta));
        }
    }

    /**
     * 会话桩：记录每次 subscribe 的请求，可按地址注入失败/阻塞/同步抛异常。
     *
     * <p>会话是 {@link DeviceSession} 的实现，但只有 {@code subscribe} 有意义——
     * 这正是编排器对会话的最小依赖面。</p>
     *
     * @author wenbin
     * @since 2026-10-18
     */
    static final class StubSession implements DeviceSession {

        private final DeviceSpec device;

        private final String sessionId;

        private volatile boolean supportsExpansion;

        private final List<SubscribeRequest> requests =
                Collections.synchronizedList(new ArrayList<>());

        private final Set<String> failing = ConcurrentHashMap.newKeySet();

        private final AtomicInteger subscribeInvocations = new AtomicInteger();

        private volatile CountDownLatch subscribeGate;

        private volatile boolean throwOnSubscribe;

        StubSession(DeviceSpec device, String sessionId) {
            this(device, sessionId, true);
        }

        StubSession(DeviceSpec device, String sessionId, boolean supportsExpansion) {
            this.device = device;
            this.sessionId = sessionId;
            this.supportsExpansion = supportsExpansion;
        }

        private void failAddresses(String... raw) {
            failing.addAll(List.of(raw));
        }

        private void healAll() {
            failing.clear();
        }

        private void disableExpansion() {
            this.supportsExpansion = false;
        }

        private void enableExpansion() {
            this.supportsExpansion = true;
        }

        private void blockSubscribe() {
            this.subscribeGate = new CountDownLatch(1);
        }

        private void releaseSubscribe() {
            CountDownLatch gate = this.subscribeGate;
            if (gate != null) {
                gate.countDown();
            }
        }

        private boolean awaitSubscribedInvocations(int expected, Duration timeout) {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                if (subscribeInvocations.get() >= expected) {
                    return true;
                }
                Thread.onSpinWait();
            }
            return subscribeInvocations.get() >= expected;
        }

        private void throwOnSubscribe(boolean value) {
            this.throwOnSubscribe = value;
        }

        private int subscribeInvocations() {
            return subscribeInvocations.get();
        }

        private List<SubscribeRequest> requests() {
            synchronized (requests) {
                return List.copyOf(requests);
            }
        }

        @Override
        public String sessionId() {
            return sessionId;
        }

        @Override
        public DeviceSpec device() {
            return device;
        }

        @Override
        public String connectionId() {
            return device.connectionId();
        }

        @Override
        public SessionState state() {
            return SessionState.ONLINE;
        }

        @Override
        public Instant boundAt() {
            return Instant.EPOCH;
        }

        @Override
        public CompletionStage<ReadResult> read(ReadRequest request) {
            throw new UnsupportedOperationException("read not used by subscription orchestrator");
        }

        @Override
        public CompletionStage<WriteResult> write(WriteRequest request) {
            throw new UnsupportedOperationException("write not used by subscription orchestrator");
        }

        @Override
        public CompletionStage<SubscriptionHandle> subscribe(SubscribeRequest request,
                DataListener listener) {
            subscribeInvocations.incrementAndGet();
            requests.add(request);
            CountDownLatch gate = subscribeGate;
            if (gate != null) {
                // 模拟「慢订阅」：调用线程阻塞在这里，验证在途去重
                try {
                    gate.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return CompletableFuture.failedFuture(ex);
                }
            }
            if (throwOnSubscribe) {
                throw new IllegalStateException("subscribe threw by design");
            }
            for (PointAddress address : request.addresses()) {
                if (failing.contains(address.raw())) {
                    return CompletableFuture.failedFuture(
                            new ProtocolException(IotMessageKeys.SUBSCRIPTION_FAILED, device.deviceId(),
                                    address.raw()));
                }
            }
            return CompletableFuture.completedFuture(handleOf(request));
        }

        private SubscriptionHandle handleOf(SubscribeRequest request) {
            return new SubscriptionHandle() {

                @Override
                public String subscriptionId() {
                    return "stub-" + sessionId;
                }

                @Override
                public List<PointAddress> addresses() {
                    return request.addresses();
                }

                @Override
                public boolean active() {
                    return true;
                }

                @Override
                public long deliveredCount() {
                    return 0L;
                }
            };
        }

        @Override
        public CompletionStage<Void> unsubscribe(SubscriptionHandle handle) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<PingResult> ping() {
            return CompletableFuture.completedFuture(PingResult.alive(0L));
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Optional<T> unwrap(Class<T> extensionType) {
            if (supportsExpansion && extensionType == AddressExpansionExtension.class) {
                return Optional.of((T) new TestAddressExpansion());
            }
            return Optional.empty();
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
    }

}
