# ypbin-iot-starter · SPI 契约定义

> 本文是 `ypbin-iot-core`（零 Spring 依赖的契约层）的完整接口定义。所有类型位于 `cn.ypbin.iot.core`，
> 新增一种协议**只需要实现 `ProtocolAdapter`**，其余全部有默认实现或可选基类。
>
> 返回 [总体设计](./DESIGN.md) ｜ [协议选型与路线图](./PROTOCOLS.md)

---

## 0. 设计原则（先读这一节，再看签名）

| # | 原则 | 具体含义 |
|---|---|---|
| P1 | **异步为纲，同步为目** | 对外契约统一返回 `CompletionStage`，保证 Netty EventLoop 永不被阻塞；同时提供 `BlockingProtocolAdapter` 基类，让只有阻塞式 API 的协议库（占多数）以同步写法零成本接入。 |
| P2 | **不做统一地址抽象** | 工业地址语义（`40001` / `DB1.DBW0` / `ns=2;s=X` / `analogInput:1`）无法无损归一，强行统一必然漏抽象。核心只透传 `raw` 字符串，解析权归协议模块。 |
| P3 | **能力显式声明，不支持即拒绝** | `ProtocolCapabilities` 声明协议支持哪些操作；未声明的能力被调用时抛 `UnsupportedCapabilityException`，**绝不静默返回空结果**。 |
| P4 | **协议特有语义走 `unwrap`** | 目录浏览、历史读、方法调用、文件传输等只属于部分协议的能力，通过 `<T> Optional<T> unwrap(Class<T>)` 暴露扩展接口，不污染主契约。 |
| P5 | **连接与设备两级模型** | 一条物理链路（TCP/串口/DTLS）可承载 N 个逻辑设备（Modbus 网关多从站、KNX 多物理地址）。两级分离让连接复用成为框架能力而非适配器自觉。 |
| P6 | **框架不碰存储** | 核心只做「连接 + 读写 + 订阅」，数据经 `DataEgress` 微批交给宿主；落库、时序库、规则引擎均不在本仓职责内。 |
| P7 | **零 Spring 于 core** | `ypbin-iot-core` 仅依赖 JDK 21 + SLF4J + JSpecify，可脱离 Spring 独立使用。Spring 只出现在 `-spring-boot-starter` 与各协议模块的 `autoconfigure` 子包。 |

> **命名与署名约定**（沿用母仓规范）：所有类顶部带 Apache-2.0 license 头，类级 Javadoc 末尾必带
> `@author wenbin` + `@since <日期>`，禁 `@date`、禁写版本号；禁用内联全限定类名。

### 0.1 本契约遵守的代码级约定（与母仓 `ypbin-starter` 对齐）

| # | 约定 | 说明 |
|---|---|---|
| **C1** | **时间类型用 `Instant`** | `PointValue.timestamp` / `DataBatch.producedAt` / `CloseReason.closedAt` 等协议时序字段一律 `Instant`（UTC 绝对时刻）；宿主的实体与 API 契约用 `LocalDateTime`（GMT+8）。**边界与理由见 `DESIGN.md` §1.4.2 A1** |
| **C2** | **集合永不返回 `null`** | 所有返回 `List`/`Set`/`Map` 的方法在无数据时返回**空集合**；字面量统一 `List.of()` / `Map.of()` / `Set.of()`，**禁用** `Collections.emptyXxx()` / `singletonXxx()`（母仓架构约束测试拦截） |
| **C3** | **枚举必须 `code` + `desc`，禁 `ordinal()`** | 全部枚举（`Quality` / `SessionState` / `ProtocolCapability` / `CloseCause` / `DeviceEventType` / `LogLevel` …）显式声明 `int code` 与 `String desc`，并提供 `fromCode(int)` |
| **C4** | **值对象用 `record`，不用 `@Data`** | SPI 的全部值对象是不可变 `record`——天然满足「实体 `equals/hashCode` 不含集合与关联对象」的母仓红线，也避免 `@Data` 的 `toString` 泄漏凭据 |
| **C5** | **异常体系自带，不依赖母仓 `BusinessException`** | `iot-core` 零 Spring，无法依赖 `ypbin-starter-core`/`-web`。异常全部继承 `IotException`（unchecked），并携带 **`messageKey`**（i18n 消息键）而非格式化文案；由 `-spring-boot-starter` 或宿主在 Web 层映射为 `R.fail(...)` + HTTP 200 |
| **C6** | **消息用消息键，不用字面量** | 异常与 `AdapterContext.log(...)` 传 `iot.<protocol>.<category>.<detail>` 形式的键（见 `RUNTIME.md` §4）；**禁止**中英文字面量，由源码规范门禁拦截 |
| **C7** | **`iot-core` 不提供静态门面** | 母仓铁律 4 的「`volatile` + 双重检查 + `SpringUtils` 委派」范式依赖 Spring 容器，与 `core` 零 Spring 冲突。静态门面（`IotUtils`）**只在 `-spring-boot-starter` 中提供**并严格按母仓范式实现（见 `DESIGN.md` §1.4.2 A4） |
| **C8** | **JSON 只能用 Jackson 3** | 母仓已全面切至 Jackson 3（`tools.jackson`）并移除 Jackson 2 运行时；本仓若需 JSON（如 `DataBatch` 的调试序列化）**必须**用 Jackson 3 |
| **C9** | **`ProtocolCode` 是值对象而非枚举** | 协议集合对外延开放（第三方可新增协议），枚举会逼第三方改 `core`。`ProtocolCode.of("...")` 做格式校验即可 |
| **C10** | **远程调用超时显式化** | `ConnectionSpec.connectTimeout` / `requestTimeout` 为必填语义（有默认值但可覆盖）；框架内部任何 HTTP/网络客户端**不得使用无超时默认值** |
| **C11** | **异常交付必须是原始领域异常** | 所有 SPI 方法异常完成时交付**原始** `IotException` 子类，**不得**交付 `CompletionException` 包装。原因：`CompletableFuture` 的组合算子（`thenApply` 等）会自动包装上游异常，导致调用方 `catch (ConnectionException ex)` 捕获不到。实现方式是在异常路径显式 `completeExceptionally(原始异常)`，或用 `cn.ypbin.iot.core.util.Stages` 工具归一化。**这是 M0 实施中由测试发现的契约缺口** |

---

## 1. 类型全景图

```
cn.ypbin.iot.core
├── protocol/                        ← 协议适配 SPI（新增协议要碰的部分）
│   ├── ProtocolAdapter               ★ 唯一必须实现的接口
│   ├── BlockingProtocolAdapter       ← 阻塞式协议库的桥接基类（可选）
│   ├── ProtocolConnection            物理链路句柄
│   ├── DeviceSession                 逻辑设备句柄（读写订阅入口）
│   ├── ProtocolDescriptor            协议身份（code/name/vendor/version/capabilities）
│   ├── ProtocolCode                  协议标识值对象（开放集合，非枚举）
│   ├── ProtocolCapability            能力枚举
│   └── ProtocolExtension             ★ 协议扩展能力标记接口（browse/history/method…）
├── context/                         ← 运行时上下文（框架注入给适配器）
│   ├── AdapterContext                ★ 适配器运行时上下文
│   ├── AdapterSettings               配置读取门面
│   ├── DataEgress                    ★ 数据/事件出口（宿主实现）
│   ├── TaskScheduler                 调度与执行器分配
│   ├── ResourceRegistry              适配器级资源回收
│   ├── MetricsRecorder               指标埋点门面
│   ├── CredentialResolver            凭据解析（不落明文）
│   ├── BoundedAddressCache           有界地址解析缓存
│   └── LogLevel                      适配器结构化日志级别
├── model/                           ← 不可变值对象（全部 record）
│   ├── Endpoint                      端点 URI
│   ├── ConnectionSpec                物理连接规格
│   ├── DeviceSpec                    逻辑设备规格
│   ├── PointAddress                  点位地址（raw + 协议解释）
│   ├── PointValue                    点位值 + 质量 + 时间戳
│   ├── Quality                       质量枚举
│   ├── DataBatch                     微批数据包
│   ├── DeviceEvent / DeviceEventType 生命周期与异常事件
│   ├── ReadRequest / ReadResult
│   ├── WriteRequest / PointWrite / WriteResult / PointWriteStatus
│   ├── SubscribeRequest / SubscriptionHandle
│   ├── ProbeResult                   连通性探测结果
│   ├── PingResult                    链路保活结果
│   ├── CloseReason / CloseCause      链路关闭原因
│   ├── SessionState                  会话状态枚举
│   └── DataListener                 订阅回调
├── spi/                             ← 宿主侧扩展点（宿主实现，非协议作者）
│   ├── DataSink                      数据落地/转发实现
│   ├── DeviceEventListener           设备事件监听
│   ├── DeviceRegistry                设备来源（启动期全量加载 + 运行期变更推送）
│   ├── ConnectionSpecProvider        链路规格来源（按 connectionId 取建链参数）
│   ├── ValidationResult              设备配置校验结果（record）
│   └── DeviceChange / ChangeType     设备配置变更事件（含 revision，保幂等）
├── i18n/                            ← 消息键常量（纯 String，零依赖）
│   └── IotMessageKeys                iot.<protocol>.<category>.<detail>
└── exception/                       ← 异常体系（全部 unchecked，携带 messageKey）
    ├── IotException                  根异常（含 messageKey + args）
    ├── ConnectionException
    ├── ProtocolException
    ├── ProtocolTimeoutException
    ├── AddressParseException
    └── UnsupportedCapabilityException
```

---

## 2. `ProtocolAdapter` —— 唯一必须实现的接口

```java
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
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.exception.UnsupportedCapabilityException;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jspecify.annotations.Nullable;

/**
 * 协议适配器 SPI —— 新增一种物联网协议的唯一实现入口。
 *
 * <p>适配器是<b>无状态单例</b>：一个协议一个实例，由容器持有并被所有设备共享。
 * 所有与「某条链路 / 某个设备」相关的状态必须存放在 {@link ProtocolConnection} 与
 * {@link DeviceSession} 中，适配器实例本身不得持有可变字段（否则 10 万连接下必然串扰）。</p>
 *
 * <p><b>最小实现</b>：只需实现 {@link #descriptor()} 与
 * {@link #open(ConnectionSpec, AdapterContext)}。{@link #bind} 默认把物理链路当作 1:1 设备会话，
 * {@link #probe} 默认复用 {@code open} 后立即关闭，{@link #close()} 默认为空操作。</p>
 *
 * <p><b>线程模型</b>：本接口全部方法返回 {@link CompletionStage}，实现方必须保证
 * <b>不阻塞调用线程</b>——框架可能在 Netty EventLoop 上发起调用，阻塞 EventLoop 会连带
 * 拖垮该 EventLoop 上的全部连接。若目标协议库只有阻塞式 API，请继承
 * {@link BlockingProtocolAdapter}，由框架用虚拟线程承载阻塞调用。</p>
 *
 * <p><b>示例</b>（伪代码，Modbus TCP 适配器）：</p>
 * <pre>{@code
 * public final class ModbusTcpAdapter implements ProtocolAdapter {
 *
 *     @Override
 *     public ProtocolDescriptor descriptor() {
 *         return ProtocolDescriptor.builder()
 *                 .code(ProtocolCode.of("modbus-tcp"))
 *                 .name("Modbus TCP")
 *                 .capabilities(ProtocolCapability.READ, ProtocolCapability.WRITE,
 *                         ProtocolCapability.SUBSCRIBE_POLLING)
 *                 .build();
 *     }
 *
 *     @Override
 *     public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext ctx) {
 *         // 具体实现见 ypbin-iot-protocol-modbus 模块
 *     }
 * }
 * }</pre>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ProtocolAdapter {

    /**
     * 协议身份与能力声明。
     *
     * <p>该方法的返回值会在适配器注册时被读取一次并<b>缓存</b>，实现方应返回稳定对象
     * （建议在字段中构造一次），不要在方法内做 I/O 或重量级计算。</p>
     *
     * <p>{@code descriptor().code()} 是全局唯一的协议标识，注册中心按它建立索引；
     * 两个适配器声明同一个 code 会导致启动失败（fail-fast，而不是后者覆盖前者）。</p>
     *
     * @return 协议描述符，不得为 {@code null}
     */
    ProtocolDescriptor descriptor();

    /**
     * 该适配器实例支持的能力集合。
     *
     * <p>默认委托 {@link ProtocolDescriptor#capabilities()}。能力集合决定框架侧的行为：
     * 未声明 {@link ProtocolCapability#WRITE} 时，框架不会为设备下发写任务；
     * 宿主直接调用 {@link DeviceSession#write} 会收到 {@link UnsupportedCapabilityException}。</p>
     *
     * @return 不可变能力集合；无任何能力时返回空集合，不得返回 {@code null}
     */
    default Set<ProtocolCapability> capabilities() {
        return descriptor().capabilities();
    }

    /**
     * 打开一条物理链路（TCP 连接 / 串口 / DTLS 会话 / 本地进程通道）。
     *
     * <p>框架负责调用时机与去重：相同 {@link ConnectionSpec#connectionId()} 的并发 {@code open}
     * 请求会被合并为一次真实建链（单飞），并在最后一个设备会话解绑后按空闲超时关闭。
     * 适配器无需自己实现连接池。</p>
     *
     * <p>返回的 {@link ProtocolConnection} 必须已经完成协议层握手（如 OPC UA 的
     * {@code CreateSession}/{@code ActivateSession}、MQTT 的 CONNECT/CONNACK），
     * 即 <b>Stage 完成即代表链路可用</b>。建链失败时以
     * {@link cn.ypbin.iot.core.exception.ConnectionException} 异常完成 Stage，
     * 不要返回一个"半可用"的连接。</p>
     *
     * @param spec    物理连接规格：端点 URI、超时、TLS、凭据引用、协议扩展参数
     * @param context 适配器运行时上下文，提供配置、出站通道、调度器、指标等
     * @return 链路就绪后完成的 Stage；失败时以 {@code ConnectionException} 异常完成
     */
    CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context);

    /**
     * 在已打开的物理链路上绑定一个逻辑设备，得到该设备的读写订阅句柄。
     *
     * <p>默认实现把物理链路当作 1:1 设备会话（`connection.session()`），适用于
     * OPC UA、MQTT、HTTP、GB28181 等「一条链路即一个设备」的协议。</p>
     *
     * <p>对于「一条链路承载 N 个从站」的协议（Modbus 网关多 unitId、BACnet 多设备实例、
     * KNX 多物理地址、S7 多机架），必须覆写本方法：在共享链路上按
     * {@link DeviceSpec#localAddress()} 建立逻辑视图，<b>不得为每个设备重新建链</b>。</p>
     *
     * <p>绑定失败（如从站不存在）时以 {@link ConnectionException} 异常完成 Stage。</p>
     *
     * @param connection 由 {@link #open} 返回且尚未关闭的物理链路
     * @param device     逻辑设备规格：设备 ID、协议内本地地址、采集周期、扩展参数
     * @param context    适配器运行时上下文
     * @return 设备会话就绪后完成的 Stage
     */
    default CompletionStage<DeviceSession> bind(
            ProtocolConnection connection, DeviceSpec device, AdapterContext context) {
        return CompletableFuture.completedFuture(connection.session());
    }

    /**
     * 连通性探测：不改动任何设备状态，仅验证「能否按该规格连上并识别对端」。
     *
     * <p>用于宿主「测试连接」按钮与巡检任务。默认实现复用 {@link #open}：建链成功后立即关闭，
     * 并用 {@link ProtocolConnection#describe()} 的结果填充 {@link ProbeResult}。
     * 若协议的探测语义更轻（如 SNMP GET sysDescr、Modbus 读设备标识），建议覆写以避免建链开销。</p>
     *
     * <p><b>本方法永不抛出异常</b>：探测失败属于正常结果，通过
     * {@link ProbeResult#reachable()} 为 {@code false} 表达，避免宿主对着异常做流程控制。</p>
     *
     * @param spec    待探测的连接规格
     * @param context 适配器运行时上下文
     * @return 探测结果 Stage，永不异常完成
     */
    default CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        throw new UnsupportedCapabilityException(descriptor().code(), "probe");
    }

    /**
     * 适配器级释放：协议库持有全局单例资源（native 库句柄、共享 EventLoopGroup、
     * mDNS 发现线程）时在此关闭。
     *
     * <p>容器关闭时调用一次。设备级与会话级资源应在
     * {@link ProtocolConnection#close()} / {@link DeviceSession#close()} 中释放，
     * 不要堆到这里。</p>
     */
    default void close() {
        // 默认无适配器级资源
    }
}
```

### 2.1 `BlockingProtocolAdapter` —— 阻塞式协议库的桥接基类

绝大多数工业协议库（Modbus、SNMP、部分 PLC 驱动）只有阻塞式 API。要求每个协议作者自己写异步编排
既不现实也容易出错，因此框架提供基类，把同步实现**自动**搬到虚拟线程上执行：

```java
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.context.AdapterContext;
import cn.ypbin.iot.core.model.ConnectionSpec;
import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.ProbeResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 阻塞式协议适配器基类：把同步实现桥接为异步契约。
 *
 * <p>子类只需按同步风格实现三个 {@code *Blocking} 方法，框架用
 * {@link AdapterContext#scheduler()} 提供的<b>虚拟线程</b>执行器承载调用。
 * 每个阻塞调用独占一个虚拟线程，天然满足「同一链路串行、不同链路并行」的工业协议语义，
 * 且不会阻塞 Netty EventLoop。</p>
 *
 * <p><b>必须避开的 JDK 21 pinning 陷阱</b>：虚拟线程在 {@code synchronized} 块内阻塞会钉住
 * 载体线程。子类实现中请使用 {@link java.util.concurrent.locks.ReentrantLock} 而非
 * {@code synchronized}；若底层协议库内部大量使用 {@code synchronized}，请在协议模块文档中
 * 显式记录该风险，并在 {@code ypbin.iot.scheduler.carrier-threads} 上给出建议值。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public abstract class BlockingProtocolAdapter implements ProtocolAdapter {

    /**
     * 本适配器是否必须运行在<b>平台线程</b>上（默认 {@code false}）。
     *
     * <p>凡是实现内部会调用 <b>JNI / native</b> 的适配器，<b>必须</b>覆写为 {@code true}：
     * 依据 JEP 444，虚拟线程执行 native 方法时会 pinning 到载体线程，且该限制
     * <b>不可移除</b>（升级 JDK 也无效，JEP 491 只修复了 {@code synchronized} 那条）。</p>
     *
     * <p>需要覆写为 {@code true} 的典型场景：串口（jSerialComm）、CAN（JavaCAN）、
     * 媒体编解码（JavaCV/FFmpeg）。详见 {@code PROTOCOLS.md} §5.5 的风险分级。</p>
     *
     * <p>返回 {@code true} 时，基类改用
     * {@link AdapterContext#scheduler()} 的 {@code platformThreadExecutor()} 承载阻塞调用。
     * 平台线程池大小时应按<b>物理资源数</b>（串口数 / CAN 通道数）而非设备数配置。</p>
     *
     * @return {@code true} 表示使用平台线程执行器
     */
    protected boolean requiresPlatformThread() {
        return false;
    }

    /**
     * 同步打开物理链路。语义与 {@link ProtocolAdapter#open} 完全一致，只是允许阻塞。
     *
     * @param spec    物理连接规格
     * @param context 适配器运行时上下文
     * @return 已完成握手的链路
     */
    protected abstract ProtocolConnection openBlocking(ConnectionSpec spec, AdapterContext context);

    /**
     * 同步绑定逻辑设备，默认返回 {@code connection.session()}。
     *
     * @param connection 物理链路
     * @param device     逻辑设备规格
     * @param context    适配器运行时上下文
     * @return 设备会话
     */
    protected DeviceSession bindBlocking(
            ProtocolConnection connection, DeviceSpec device, AdapterContext context) {
        return connection.session();
    }

    /**
     * 同步探测，默认委托 {@link #openBlocking} 后立即关闭。
     *
     * @param spec    待探测规格
     * @param context 适配器运行时上下文
     * @return 探测结果
     */
    protected ProbeResult probeBlocking(ConnectionSpec spec, AdapterContext context) {
        ProtocolConnection connection = openBlocking(spec, context);
        try {
            return ProbeResult.reachable(descriptor(), connection.describe());
        } finally {
            connection.close();
        }
    }

    @Override
    public final CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
        return supplyAsync(context, () -> openBlocking(spec, context));
    }

    @Override
    public final CompletionStage<DeviceSession> bind(
            ProtocolConnection connection, DeviceSpec device, AdapterContext context) {
        return supplyAsync(context, () -> bindBlocking(connection, device, context));
    }

    @Override
    public final CompletionStage<ProbeResult> probe(ConnectionSpec spec, AdapterContext context) {
        return supplyAsync(context, () -> probeBlocking(spec, context))
                .exceptionally(ex -> ProbeResult.unreachable(descriptor(), ex));
    }

    private static <T> CompletionStage<T> supplyAsync(AdapterContext context, Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, executor(context));
    }

    /**
     * 按 {@link #requiresPlatformThread()} 选择执行器。
     *
     * <p>这里是「native 调用禁止上虚拟线程」这条硬约束的<b>唯一执行点</b>——
     * 适配器作者只需覆写一个开关，不需要理解 pinning 机制，也不会写错。</p>
     */
    private ExecutorService executor(AdapterContext context) {
        return requiresPlatformThread()
                ? context.scheduler().platformThreadExecutor()
                : context.scheduler().virtualThreadExecutor();
    }
}
```

---

## 3. `AdapterContext` —— 适配器运行时上下文

```java
package cn.ypbin.iot.core.context;

import cn.ypbin.iot.core.model.DeviceEvent;
import cn.ypbin.iot.core.protocol.ProtocolCode;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.jspecify.annotations.Nullable;

/**
 * 适配器运行时上下文：框架注入给适配器的<b>全部宿主能力</b>。
 *
 * <p>这是适配器与外界交互的唯一通道。适配器<b>不得</b>直接使用静态单例、直接 new 线程、
 * 直接读环境变量或直接访问数据库——所有这些能力都必须经由本接口获取，原因有三：
 * 一是可测试（测试可注入假实现），二是可治理（框架统一限流、埋点、回收），
 * 三是可移植（core 零 Spring，脱离容器也能跑）。</p>
 *
 * <p><b>生命周期</b>：一个 {@code ProtocolAdapter} 实例对应一个 {@code AdapterContext}，
 * 在适配器注册时创建，在容器关闭时随适配器一起失效。适配器可在任意线程调用本接口的方法，
 * 实现必须是线程安全的。</p>
 *
 * <p><b>作用域</b>：上下文是<b>适配器级</b>而非设备级或会话级。设备/会话相关的数据请放在
 * {@link cn.ypbin.iot.core.protocol.DeviceSession} 里，不要试图通过上下文传递。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface AdapterContext {

    /**
     * 当前适配器的协议标识，等价于 {@code descriptor().code()}。
     *
     * @return 协议标识
     */
    ProtocolCode protocol();

    /**
     * 配置读取门面：框架通用参数（超时/重连/限流）已显式建模，
     * 协议特有参数走类型化查表。
     *
     * @return 配置门面，永不为 {@code null}
     */
    AdapterSettings settings();

    /**
     * 数据与事件出口。
     *
     * <p>采集到的点位数据、订阅推送、设备上下线事件全部经此交给宿主。
     * 实现内部是有界队列 + 微批聚合，<b>调用不会阻塞</b>；队列满时按配置的丢弃策略处理
     * 并累加丢弃计数，绝不反压到协议链路。</p>
     *
     * @return 数据出口，永不为 {@code null}
     */
    DataEgress egress();

    /**
     * 调度能力：周期任务、一次性任务、通用执行器与虚拟线程执行器。
     *
     * <p>适配器需要定时轮询、心跳保活、超时控制时一律使用本接口，
     * <b>严禁</b>自行 {@code new Thread()} 或创建线程池——10 万设备下这会直接导致
     * 线程数与 FD 数失控。</p>
     *
     * @return 调度器，永不为 {@code null}
     */
    TaskScheduler scheduler();

    /**
     * 适配器级资源登记处：登记的 {@link AutoCloseable} 会在适配器关闭时按登记的逆序统一释放。
     *
     * <p>适用于 native 库句柄、共享连接池、临时目录等跨设备共享的资源。
     * 设备级资源应由适配器在会话关闭时自行释放。</p>
     *
     * @return 资源登记处，永不为 {@code null}
     */
    ResourceRegistry resources();

    /**
     * 指标埋点门面：读写耗时、成功失败计数、点位数、异常分类。
     *
     * <p>Spring 环境下桥接到 Micrometer；非 Spring 环境为无操作实现。
     * 适配器应在读写路径上埋点，但<b>不要</b>在高频点位路径上做字符串拼接
     * （每点位一次指标会直接压垮指标系统）。</p>
     *
     * @return 指标门面，永不为 {@code null}
     */
    MetricsRecorder metrics();

    /**
     * 凭据解析：按 {@link cn.ypbin.iot.core.model.ConnectionSpec#credentialRef()} 取出运行时凭据。
     *
     * <p>凭据本体不随 {@code ConnectionSpec} 流转（避免进入日志与序列化），
     * 只传引用、用时才解析。</p>
     *
     * @return 凭据解析器，永不为 {@code null}
     */
    CredentialResolver credentials();

    /**
     * 时钟：所有时间戳取时统一走这里，便于测试注入固定时钟与做时钟漂移校正。
     *
     * @return 时钟，永不为 {@code null}
     */
    Clock clock();

    /**
     * 取一个有界地址解析缓存。
     *
     * <p>工业地址解析（如 Modbus 的 {@code 4x0001}、S7 的 {@code DB1.DBW0}）不便宜，
     * 而地址集合是<b>有限且稳定</b>的（设备点表），天然适合缓存。但 10 万设备 × 千点位意味着
     * 上亿地址对象，无界 Map 必然 OOM，因此框架只提供有界实现。</p>
     *
     * <p>同一 {@code namespace} 重复调用返回同一实例；不同协议模块应使用不同 namespace
     * （建议用协议 code）以避免类型串扰。</p>
     *
     * @param namespace  缓存命名空间，通常为协议 code 或协议内子域
     * @param maximumSize 最大条目数，超出后按 LRU 淘汰
     * @param <A>        解析后的地址类型
     * @return 有界地址缓存
     */
    <A> BoundedAddressCache<A> addressCache(String namespace, int maximumSize);

    /**
     * 记录一条适配器级结构化日志事件（框架统一带上协议、适配器、设备上下文后输出）。
     *
     * <p>比直接使用 SLF4J 更利于排查：宿主可以把这些事件单独路由到接入层日志管道，
     * 与业务日志分离。适配器仍可直接使用 SLF4J 打调试日志。</p>
     *
     * <p><b>消息内容是消息键而非文案</b>：本接口的 {@code message} 参数应为
     * {@code iot.<protocol>.<category>.<detail>} 形式的<b>消息键</b>（见 {@code RUNTIME.md} §4），
     * 由框架按 {@code I18nUtil} 解析为当前语言文案——禁止直接传中文/英文字面量。</p>
     *
     * @param level   日志级别
     * @param message 消息键（占位符 {@code {}}）
     * @param args    消息参数
     */
    void log(LogLevel level, String message, Object... args);
}
```

> **关于执行器**：本节刻意**不提供**独立的 {@code executor()} 方法——所有执行器统一从
> {@link TaskScheduler} 获取（{@code virtualThreadExecutor()} / {@code platformThreadExecutor()}），
> 避免出现"两个地方都能拿线程池"从而绕过 pinning 约束的口子。

### 3.1 `AdapterSettings` —— 配置门面

```java
package cn.ypbin.iot.core.context;

import java.time.Duration;
import Map;
import java.util.Optional;

/**
 * 适配器配置门面。
 *
 * <p>设计取舍：<b>框架关心的通用参数显式建模，协议特有参数类型化查表</b>。
 * 前者是框架自己也要用的（重连退避、并发闸门），必须有强类型默认值与校验；
 * 后者数量随协议增长且极不稳定，显式建模会让 core 被协议细节污染。</p>
 *
 * <p>协议扩展参数在 Spring 环境下由 {@code ypbin.iot.protocol.<code>.*} 绑定，
 * 非 Spring 环境由宿主构造。适配器<b>必须</b>在初始化时校验扩展参数：
 * 未知 key 要 warn 并指出可用 key，非法值要 fail-fast——
 * 静默忽略拼错的配置项是最难排查的一类线上故障。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface AdapterSettings {

    /** 适配器是否启用（{@code ypbin.iot.protocol.<code>.enabled}）。 */
    boolean enabled();

    /** 建链超时，默认 10s。 */
    Duration connectTimeout();

    /** 单次请求超时，默认 5s。 */
    Duration requestTimeout();

    /** 空闲链路保活间隔，默认 30s；为 0 表示不保活。 */
    Duration keepAliveInterval();

    /** 重连初始退避，默认 1s。 */
    Duration reconnectInitialDelay();

    /** 重连最大退避，默认 60s；退避按指数增长并以抖动打散。 */
    Duration reconnectMaxDelay();

    /** 重连抖动系数，默认 0.2（±20%），避免集群重启时的重连风暴。 */
    double reconnectJitter();

    /** 单适配器最大并发链路数，默认 10000；超出后新建链请求被拒绝并计入指标。 */
    int maxConnections();

    /** 单链路最大在途请求数，默认 64；用于给串行协议做流水线深度限制。 */
    int maxPendingRequests();

    /**
     * 按 key 取协议扩展配置。
     *
     * @param key          配置键（去掉 {@code ypbin.iot.protocol.<code>.} 前缀后的剩余部分）
     * @param type         目标类型，支持基本类型、枚举、{@code Duration}、{@code List<String>}
     * @param defaultValue 缺省值，可为 {@code null}
     * @param <T>          目标类型
     * @return 转换后的值；缺省时返回 {@code defaultValue}
     */
    <T> T get(String key, Class<T> type, T defaultValue);

    /**
     * 按 key 取协议扩展配置，缺失时返回空 Optional。
     *
     * @param key   配置键
     * @param type  目标类型
     * @param <T>   目标类型
     * @return 配置值
     */
    <T> Optional<T> find(String key, Class<T> type);

    /**
     * 全部协议扩展配置（只读视图），供适配器启动时做未知 key 校验。
     *
     * @return 不可变配置视图，永不为 {@code null}
     */
    Map<String, Object> extended();
}
```

### 3.2 `DataEgress` —— 数据出口（宿主实现）

```java
package cn.ypbin.iot.core.context;

import cn.ypbin.iot.core.model.DataBatch;
import cn.ypbin.iot.core.model.DeviceEvent;

/**
 * 数据与事件出口：协议接入层与宿主平台之间的<b>唯一</b>数据通道。
 *
 * <p>宿主实现本接口把数据送去 Kafka / 时序库 / 内存队列 / 规则引擎。
 * 框架保证：</p>
 * <ul>
 *   <li><b>不阻塞</b>：{@code emit} 只做入队，绝不阻塞协议线程；</li>
 *   <li><b>微批</b>：默认按 {@code ypbin.iot.egress.batch-size}（1000 点）与
 *       {@code batch-interval}（200ms）聚合，先到先发，避免每点位一次调用；</li>
 *   <li><b>按设备有序</b>：同一设备的点位在批次内保持采集顺序；</li>
 *   <li><b>背压可见</b>：队列满时按 {@code overflow-policy} 处理（DROP_OLDEST / DROP_NEWEST / BLOCK），
 *       无论哪种策略都累加 {@code iot.egress.dropped} 指标并输出限流日志，<b>绝不静默丢弃</b>。</li>
 * </ul>
 *
 * <p><b>实现注意</b>：{@code emit} 由框架的内部线程调用，实现必须线程安全且快速返回
 * （建议只做一次入队）。耗时逻辑请自行异步化，不要在 {@code emit} 里做网络 I/O。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DataEgress {

    /**
     * 交付一批点位数据。
     *
     * @param batch 非空批次；调用方保证 {@code points} 非空
     */
    void emit(DataBatch batch);

    /**
     * 交付一条设备生命周期/异常事件（上线、离线、重连、协议错误、点位质量劣化）。
     *
     * <p>与数据流分离：事件是低频控制面信息，宿主通常要落库并触发告警，
     * 不应与高频数据混在一条管道里。</p>
     *
     * @param event 设备事件
     */
    void emit(DeviceEvent event);
}
```

### 3.3 其余上下文组件

```java
/**
 * 调度与执行器分配。
 *
 * <p>框架实现基于<b>分层时间轮</b>而非每设备一个 {@code ScheduledFuture}：
 * 10 万设备的周期采集若各自持有定时任务，仅定时器堆就会带来可观的 GC 压力与精度抖动。</p>
 */
public interface TaskScheduler {

    /**
     * 注册固定间隔任务。
     *
     * @param task         任务体，异常会被捕获并记录（不让一个任务拖垮调度器）
     * @param initialDelay 首次延迟
     * @param interval     间隔
     * @return 可取消的任务句柄
     */
    ScheduledTask schedule(Runnable task, Duration initialDelay, Duration interval);

    /**
     * 注册一次性延迟任务。
     *
     * @param task  任务体
     * @param delay 延迟
     * @return 可取消的任务句柄
     */
    ScheduledTask scheduleOnce(Runnable task, Duration delay);

    /**
     * 虚拟线程执行器：承载**阻塞式、纯 Java** 协议栈的调用，无池化上限、按需创建。
     *
     * <p>绝大多数阻塞式协议库（SNMP 同步 API、BACnet、j2mod）用这个执行器即可——
     * 它们阻塞时虚拟线程会优雅卸载，不占用载体线程。</p>
     *
     * @return 虚拟线程执行器
     */
    ExecutorService virtualThreadExecutor();

    /**
     * 平台线程执行器：**经 JNI 的调用专用**，有界池。
     *
     * <p><b>这是硬性要求，不是建议</b>：依据 JEP 444，虚拟线程在执行 <code>native</code>
     * 方法或 foreign function 时会 <b>pinning</b> 到载体线程，且
     * <i>"The second limitation is required for proper interaction with native code"</i>
     * ——即<b>该限制不可移除，升级 JDK 也不会消失</b>（JEP 491 只修复了 <code>synchronized</code> 那条）。</p>
     *
     * <p>因此以下调用<b>必须</b>使用本执行器，否则会钉住载体线程并拖垮整个调度器：</p>
     * <ul>
     *   <li>串口读写（jSerialComm 等 JNI 串口库）</li>
     *   <li>CAN 收发（JavaCAN 等 SocketCAN JNI 封装）</li>
     *   <li>媒体编解码（JavaCV / FFmpeg JNI）</li>
     *   <li>任何其它经 JNI 且会阻塞的调用</li>
     * </ul>
     *
     * <p>池大小应按<b>物理资源数</b>配置（串口数 / CAN 通道数 / 媒体并发），
     * <b>不是</b>按设备数——同一串口上的请求本就串行，池开大没有收益。</p>
     *
     * @return 平台线程执行器
     */
    ExecutorService platformThreadExecutor();

    /** 可取消的任务句柄。 */
    interface ScheduledTask {
        void cancel();
        boolean isCancelled();
    }
}
```

```java
/**
 * 适配器级资源登记处：登记的资源在适配器关闭时按登记的<b>逆序</b>释放，
 * 单个资源释放失败只记录日志并继续释放其余资源（避免一个坏资源导致整批泄漏）。
 */
public interface ResourceRegistry {
    <T extends AutoCloseable> T register(T resource);
    void unregister(AutoCloseable resource);
}
```

```java
/**
 * 指标埋点门面。Spring 环境桥接 Micrometer，非 Spring 环境为无操作实现。
 *
 * <p>刻意不暴露 Micrometer 类型：core 零 Spring 依赖，且指标后端可替换。</p>
 */
public interface MetricsRecorder {

    /** 记录一次读操作。 */
    void recordRead(Duration elapsed, boolean success);

    /** 记录一次写操作。 */
    void recordWrite(Duration elapsed, boolean success);

    /** 记录一次订阅推送的批大小。 */
    void recordSubscriptionBatch(int pointCount);

    /**
     * 记录一次异常。
     *
     * @param errorType 异常分类标签，使用<b>有限枚举值</b>而非异常消息（避免标签基数爆炸）
     */
    void recordError(String errorType);

    /**
     * 记录一个瞬时值（连接数、在途请求数等）。
     *
     * @param name  指标名，框架侧只接受白名单内的名字
     * @param value 当前值
     */
    void gauge(String name, double value);
}
```

```java
/**
 * 凭据解析：把 {@code ConnectionSpec.credentialRef} 解析为运行时凭据。
 *
 * <p>凭据以 {@code char[]} 承载而非 {@code String}，便于使用后清零；
 * 实现方不得把凭据写入日志或异常消息。</p>
 */
public interface CredentialResolver {

    /**
     * 解析凭据引用。
     *
     * @param ref 凭据引用（如 {@code env:IOT_DEVICE_PWD}、{@code vault:secret/plc-01}）
     * @return 凭据；引用不存在时返回空 Optional（由调用方决定是拒绝连接还是匿名连接）
     */
    Optional<Credential> resolve(String ref);

    /** 运行时凭据。 */
    record Credential(String username, char[] secret, Map<String, String> attributes) {
        /** 清零凭据内容。 */
        public void wipe() {
            Arrays.fill(secret, '\0');
        }
    }
}
```

```java
package cn.ypbin.iot.core.context;

import java.util.function.Function;

/**
 * 有界地址解析缓存（LRU + 并发安全）。
 *
 * <p>存在的理由：地址解析有成本，地址集合有限且稳定，但设备规模上来后总量巨大
 * （10 万设备 × 千点位），无界 Map 必然 OOM。本类型把「解析成本」与「内存上限」
 * 这对矛盾收敛到一个可配置的组件里，避免每个协议作者各写一版。</p>
 *
 * @param <A> 解析后的地址类型
 * @author wenbin
 * @since 2026-09-13
 */
public interface BoundedAddressCache<A> {

    /**
     * 取解析结果，未命中时用 {@code loader} 解析并写入缓存。
     *
     * @param raw    原始地址字符串
     * @param loader 解析函数，抛出异常时不写入缓存（避免缓存污染）
     * @return 解析后的地址
     */
    A get(String raw, Function<String, A> loader);

    /** 清空缓存（点表变更时由框架调用）。 */
    void invalidateAll();

    /** 当前条目数。 */
    int size();
}
```

```java
package cn.ypbin.iot.core.context;

/**
 * 适配器结构化日志级别。
 *
 * <p>刻意不复用 SLF4J 的 {@code Level}：框架需要的是「接入层语义级别」而非通用日志级别，
 * 且 core 只依赖 {@code slf4j-api}（不含实现），日志级别由框架统一映射到具体后端。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public enum LogLevel {

    /** 调试：协议报文级细节，生产默认关闭。 */
    DEBUG(0, "调试"),
    /** 信息：建链、断链、订阅建立等状态变更。 */
    INFO(1, "信息"),
    /** 警告：可自愈的异常（重连、点位质量劣化、配置项拼写可疑）。 */
    WARN(2, "警告"),
    /** 错误：需要人工介入的异常（建链持续失败、协议不兼容、配置非法）。 */
    ERROR(3, "错误");

    private final int code;
    private final String desc;

    LogLevel(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    /** 级别码，用于跨进程传输与存库（严禁使用 {@link #ordinal()}）。 */
    public int getCode() {
        return code;
    }

    /** 级别描述。 */
    public String getDesc() {
        return desc;
    }
}
```

---

## 4. 连接与设备句柄

### 4.1 `ProtocolConnection`

```java
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.model.CloseReason;
import cn.ypbin.iot.core.model.Endpoint;
import cn.ypbin.iot.core.model.SessionState;
import java.time.Instant;
import Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 物理链路句柄：一条 TCP/UDP/串口/DTLS 会话。
 *
 * <p>由 {@link ProtocolAdapter#open} 创建，可被多个 {@link DeviceSession} 共享
 * （Modbus 网关、BACnet 路由器等场景）。引用计数与空闲回收由框架的
 * {@code ConnectionRegistry} 负责，适配器只需如实反映链路状态。</p>
 *
 * <p><b>线程安全</b>：同一链路的 {@code whenClosed()} 可能被多线程注册，
 * 实现必须使用并发安全结构。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ProtocolConnection {

    /** 链路唯一标识（框架分配，全局唯一且稳定）。 */
    String connectionId();

    /** 链路端点。 */
    Endpoint endpoint();

    /** 链路状态。 */
    SessionState state();

    /** 建链完成时刻。 */
    Instant openedAt();

    /**
     * 该链路的默认设备会话（1:1 协议的快捷入口）。
     *
     * <p>1:N 协议的实现可以返回一个共享会话，或抛出
     * {@link cn.ypbin.iot.core.exception.UnsupportedCapabilityException} 要求调用方走
     * {@link ProtocolAdapter#bind}。无论哪种，都必须在协议模块文档中写明。</p>
     *
     * @return 设备会话
     */
    DeviceSession session();

    /**
     * 链路关闭后完成的 Stage（正常关闭与异常断开都会完成）。
     *
     * <p>框架据此触发重连：{@code connection.whenClosed().thenRun(this::scheduleReconnect)}。
     * <b>该 Stage 只完成一次</b>；重复调用返回同一 Stage 或其等价视图。</p>
     *
     * @return 关闭原因 Stage
     */
    CompletionStage<CloseReason> whenClosed();

    /**
     * 链路的对端描述信息（型号、固件版本、序列号等），用于「测试连接」展示与资产核对。
     *
     * @return 只读描述信息；未知时返回空 Map，不得返回 {@code null}
     */
    Map<String, String> describe();

    /**
     * 取协议扩展能力（如 OPC UA 的 {@code BrowseService}）。
     *
     * @param extensionType 扩展接口类型
     * @param <T>           扩展类型
     * @return 支持时返回实例，否则返回空 Optional
     */
    <T> Optional<T> unwrap(Class<T> extensionType);

    /**
     * 主动关闭链路。幂等：重复调用与已关闭后调用均为无操作。
     *
     * <p>关闭后 {@link #whenClosed()} 以 {@code CLIENT_REQUEST} 原因完成。
     * 实现必须确保底层 socket / native 句柄被释放。</p>
     */
    void close();
}
```

### 4.2 `DeviceSession`

```java
package cn.ypbin.iot.core.protocol;

import cn.ypbin.iot.core.model.DeviceSpec;
import cn.ypbin.iot.core.model.PingResult;
import cn.ypbin.iot.core.model.ReadRequest;
import cn.ypbin.iot.core.model.ReadResult;
import cn.ypbin.iot.core.model.SessionState;
import cn.ypbin.iot.core.model.SubscribeRequest;
import cn.ypbin.iot.core.model.SubscriptionHandle;
import cn.ypbin.iot.core.model.WriteRequest;
import cn.ypbin.iot.core.model.WriteResult;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 逻辑设备句柄：设备维度的读写订阅入口。
 *
 * <p>由 {@link ProtocolAdapter#bind} 创建，生命周期与设备绑定解绑一致。
 * 一个会话内部通常串行化请求（工业协议多为请求-响应语义），
 * 若协议支持流水线，由适配器自行控制并发深度，但必须遵守
 * {@link cn.ypbin.iot.core.context.AdapterSettings#maxPendingRequests()}。</p>
 *
 * <p><b>能力约束</b>：调用未在
 * {@link ProtocolDescriptor#capabilities()} 中声明的方法时，必须抛出
 * {@link cn.ypbin.iot.core.exception.UnsupportedCapabilityException}，
 * 不得返回空结果或伪造成功——静默降级会让宿主以为设备正常而实际没有数据。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DeviceSession {

    /** 会话唯一标识。 */
    String sessionId();

    /** 设备规格。 */
    DeviceSpec device();

    /** 所属物理链路 ID。 */
    String connectionId();

    /** 会话状态。 */
    SessionState state();

    /** 绑定完成时刻。 */
    Instant boundAt();

    /**
     * 批量读取点位。
     *
     * <p>框架保证 {@code request.addresses()} 非空且不含 {@code null}。
     * <b>协议相关的切分由适配器负责</b>：例如 Modbus 单次最多读 125 个寄存器、
     * 且不连续地址需要拆分为多次 PDU 请求，适配器应内部拆分并合并结果，
     * 对调用方保持「一次请求一次结果」的语义。</p>
     *
     * <p>部分点位读取失败时，<b>不要</b>让整个 Stage 异常完成：
     * 在 {@link ReadResult} 中把这些点位标记为
     * {@link cn.ypbin.iot.core.model.Quality#BAD} 并给出原因，
     * 保留其余成功点位。只有当<b>整条链路不可用</b>时才让 Stage 异常完成。</p>
     *
     * @param request 读请求
     * @return 读结果 Stage
     */
    CompletionStage<ReadResult> read(ReadRequest request);

    /**
     * 批量写入点位。
     *
     * <p>与读同理：部分失败通过
     * {@link cn.ypbin.iot.core.model.PointWriteStatus#success()} 表达，
     * 不让整个 Stage 异常完成。</p>
     *
     * @param request 写请求
     * @return 写结果 Stage
     */
    CompletionStage<WriteResult> write(WriteRequest request);

    /**
     * 订阅点位变化。
     *
     * <p>三种订阅形态由能力枚举区分，适配器按协议实际能力实现：</p>
     * <ul>
     *   <li>{@link ProtocolCapability#SUBSCRIBE_NATIVE}：设备/服务端主动上报
     *       （OPC UA Subscription、MQTT、BACnet COV）；</li>
     *   <li>{@link ProtocolCapability#SUBSCRIBE_POLLING}：框架按
     *       {@code samplingInterval} 轮询后对比变化推送（Modbus、S7、SNMP）；</li>
     *   <li>{@link ProtocolCapability#SUBSCRIBE_STREAM}：长连接流式（WebSocket、GB28181 媒体流）。</li>
     * </ul>
     *
     * <p><b>数据流向</b>：订阅到的数据默认经
     * {@link cn.ypbin.iot.core.context.AdapterContext#egress()} 出口；
     * 若调用方传入 {@code listener}，则由该 listener 消费，不再走 egress
     * （避免同一份数据被投递两次）。</p>
     *
     * @param request  订阅请求：地址集、采样周期、发布周期、死区
     * @param listener 点位回调；为 {@code null} 表示走框架统一出口
     * @return 订阅句柄 Stage
     */
    CompletionStage<SubscriptionHandle> subscribe(
            SubscribeRequest request, DataListener listener);

    /**
     * 取消订阅。
     *
     * @param handle 由 {@link #subscribe} 返回的句柄
     * @return 取消完成后完成的 Stage；句柄已失效时为已完成的 Stage（幂等）
     */
    CompletionStage<Void> unsubscribe(SubscriptionHandle handle);

    /**
     * 链路保活探测：发送协议级心跳或最小代价请求，验证会话仍然可用。
     *
     * <p>与 {@link ProtocolAdapter#probe} 的区别：probe 是「没连接时测试能不能连」，
     * ping 是「已连接时测试还活着吗」。实现应尽量选择代价最小的报文
     * （OPC UA Read ServerStatus、Modbus 读设备标识、SNMP GET sysUpTime）。</p>
     *
     * @return 探测结果 Stage，永不异常完成（超时通过 {@code alive=false} 表达）
     */
    CompletionStage<PingResult> ping();

    /**
     * 取协议扩展能力（如 OPC UA 的 {@code BrowseService}、S7 的块列表服务）。
     *
     * @param extensionType 扩展接口类型，必须是 {@link ProtocolExtension} 的子接口
     * @param <T>           扩展类型
     * @return 支持时返回实例，否则返回空 Optional
     */
    <T> Optional<T> unwrap(Class<T> extensionType);

    /**
     * 解绑设备并释放会话资源。幂等。
     *
     * @return 释放完成后完成的 Stage
     */
    CompletionStage<Void> close();
}
```

---

## 5. 值对象（全部为不可变 record）

> 下列清单为**签名摘要**，为便于阅读省略了 import 与部分样板方法。
> 实际代码一律顶部 `import` 后使用简单类名（`List` / `Map` / `Optional` / `Instant` / `Duration`），
> **禁止内联全限定类名**；每个 record 均带完整 Javadoc 与 `@author wenbin` + `@since`。

```java
/** 协议标识：开放集合值对象而非枚举——协议是外延可扩展的，枚举会逼第三方改 core。 */
public record ProtocolCode(String value) {
    public static ProtocolCode of(String value);
    @Override public String toString();
}

/** 协议能力。 */
public enum ProtocolCapability {
    /** 支持批量读 */
    READ(1, "读"),
    /** 支持写 */
    WRITE(2, "写"),
    /** 支持服务端主动推送订阅 */
    SUBSCRIBE_NATIVE(3, "原生订阅"),
    /** 支持框架轮询式订阅 */
    SUBSCRIBE_POLLING(4, "轮询订阅"),
    /** 支持流式订阅（媒体流/长连接） */
    SUBSCRIBE_STREAM(5, "流式订阅"),
    /** 支持目录/节点浏览 */
    BROWSE(6, "目录浏览"),
    /** 支持历史数据读取 */
    HISTORY(7, "历史读取"),
    /** 支持设备发现（广播/mDNS/扫描） */
    DISCOVERY(8, "设备发现"),
    /** 单链路可承载多个逻辑设备 */
    MULTI_DEVICE_LINK(9, "链路复用");

    private final int code;
    private final String desc;
    // getCode() / getDesc() / fromCode(int)
}

/** 会话/链路状态。 */
public enum SessionState {
    IDLE(0, "未连接"), CONNECTING(1, "连接中"), ONLINE(2, "在线"),
    DEGRADED(3, "降级"), RECONNECTING(4, "重连中"), CLOSED(5, "已关闭"),
    FAILED(6, "失败");
    // code + desc
}

/** 数据质量。与工业协议常见质量语义对齐，取值刻意保持精简。 */
public enum Quality {
    GOOD(0, "正常"),
    UNCERTAIN(1, "不确定"),
    BAD(2, "坏值"),
    STALE(3, "数据陈旧"),
    NOT_CONNECTED(4, "链路未连接"),
    CONFIG_ERROR(5, "配置错误");
    // code + desc
}

/** 端点：统一用 URI 表达，避免字段组合爆炸。 */
public record Endpoint(String uri) {
    /** tcp://10.0.0.1:502 */
    public static Endpoint of(String uri);
    public String scheme();       // 如 tcp / udp / serial / opc.tcp / modbus+tcp
    public String host();
    public int port();
    /** 串口设备路径（serial:///dev/ttyS0?baud=9600&parity=none） */
    public Optional<String> path();
    /** URI 查询参数（波特率、校验位、DTLS 参数等） */
    public Map<String, String> parameters();
}

/** 物理连接规格。 */
public record ConnectionSpec(
        String connectionId,
        ProtocolCode protocol,
        Endpoint endpoint,
        Duration connectTimeout,
        Duration requestTimeout,
        TlsOptions tls,               // TlsOptions.disabled() 表示明文
        String credentialRef,         // 可为 null：匿名连接
        Map<String, String> properties) { }

/** TLS/DTLS 参数。 */
public record TlsOptions(
        boolean enabled,
        String protocol,              // TLSv1.3 / DTLSv1.2
        String keystoreRef,
        String truststoreRef,
        boolean insecureSkipVerify,   // 仅用于调试，启用时必须打 warn 日志
        String cipherSuites) {
    public static TlsOptions disabled();
}

/** 逻辑设备规格。 */
public record DeviceSpec(
        String deviceId,
        String deviceName,
        ProtocolCode protocol,
        String connectionId,
        String localAddress,          // Modbus unitId / BACnet deviceInstance / KNX 物理地址 / SNMP 无
        Duration pollInterval,        // 周期采集间隔；为 ZERO 表示仅订阅不轮询
        Map<String, String> properties) { }

/** 点位地址：raw 字符串 + 协议解释权在适配器。 */
public record PointAddress(String raw) {
    public static PointAddress of(String raw);
}

/** 点位值。 */
public record PointValue(
        PointAddress address,
        Object value,                 // 类型由协议决定：Boolean/Number/String/byte[]/结构化
        Quality quality,
        Instant timestamp,            // 源时间戳（设备侧提供时）
        String qualityReason) {       // 非 GOOD 时的原因，GOOD 时为 null
    public static PointValue good(PointAddress address, Object value, Instant timestamp);
    public static PointValue bad(PointAddress address, Quality quality, String reason, Instant timestamp);
}

/** 微批数据包。 */
public record DataBatch(
        String deviceId,
        ProtocolCode protocol,
        String connectionId,
        Instant producedAt,
        List<PointValue> points) {
    public int size();
    public boolean isEmpty();
}

/** 设备事件类型。 */
public enum DeviceEventType {
    DEVICE_ONLINE(1, "设备上线"), DEVICE_OFFLINE(2, "设备离线"),
    CONNECT_FAILED(3, "建链失败"), RECONNECTING(4, "重连中"),
    PROTOCOL_ERROR(5, "协议错误"), QUALITY_DEGRADED(6, "数据质量劣化"),
    SUBSCRIPTION_LOST(7, "订阅丢失"), CONFIG_INVALID(8, "配置非法");
    // code + desc
}

/** 设备事件。 */
public record DeviceEvent(
        String deviceId,
        ProtocolCode protocol,
        DeviceEventType type,
        String message,
        Throwable cause,              // 可为 null
        Instant occurredAt) { }

/** 读请求。 */
public record ReadRequest(List<PointAddress> addresses, Duration timeout) {
    public static ReadRequest of(PointAddress... addresses);
    public static ReadRequest of(List<PointAddress> addresses);
}

/** 读结果。 */
public record ReadResult(List<PointValue> values, Duration elapsed) { }

/** 单个写项。 */
public record PointWrite(PointAddress address, Object value) { }

/** 写请求。 */
public record WriteRequest(List<PointWrite> writes, Duration timeout) { }

/** 单个写项结果。 */
public record PointWriteStatus(PointAddress address, boolean success, String reason) { }

/** 写结果：逐项状态，部分失败不异常完成。 */
public record WriteResult(List<PointWriteStatus> statuses, Duration elapsed) {
    public boolean allSuccess();
    public List<PointWriteStatus> failures();
}

/** 订阅请求。 */
public record SubscribeRequest(
        List<PointAddress> addresses,
        Duration samplingInterval,     // 采样周期（轮询式必填；原生订阅可忽略）
        Duration publishingInterval,   // 发布周期（原生订阅用；下限由服务端决定）
        Double deadband,               // 死区，null 表示不过滤
        Map<String, String> options) { }

/** 订阅句柄。 */
public interface SubscriptionHandle {
    String subscriptionId();
    List<PointAddress> addresses();
    boolean active();
    /** 已推送点位计数，用于宿主核对订阅是否真的在工作。 */
    long deliveredCount();
}

/** 探测结果。 */
public record ProbeResult(
        boolean reachable,
        ProtocolDescriptor protocol,
        String serverIdentity,        // 型号/固件/序列号，未知为 null
        Map<String, String> details,
        String failureReason,         // reachable=false 时的原因
        Duration elapsed) {
    public static ProbeResult reachable(ProtocolDescriptor protocol, Map<String, String> details);
    public static ProbeResult unreachable(ProtocolDescriptor protocol, Throwable cause);
}

/** 保活结果。 */
public record PingResult(boolean alive, long roundTripMillis, String failureReason) { }

/** 链路关闭原因。 */
public record CloseReason(CloseCause cause, String message, Throwable error, Instant closedAt) { }

/** 关闭原因分类。 */
public enum CloseCause {
    CLIENT_REQUEST(1, "主动关闭"), REMOTE_CLOSED(2, "对端关闭"),
    TIMEOUT(3, "超时"), PROTOCOL_ERROR(4, "协议错误"),
    TRANSPORT_ERROR(5, "传输错误"), SERVER_SHUTDOWN(6, "服务关停");
    // code + desc
}

/** 订阅/点对点数据回调。 */
@FunctionalInterface
public interface DataListener {
    void onData(PointValue value);
}
```

### 5.1 `ProtocolDescriptor`

```java
/**
 * 协议描述符：协议身份与能力声明，由适配器在 {@link ProtocolAdapter#descriptor()} 中提供。
 *
 * <p>全部字段不可变；建议协议模块用静态常量持有单例，避免每次注册重复构造。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public record ProtocolDescriptor(
        ProtocolCode code,
        String name,                     // 展示名，如 "Modbus TCP"
        String vendor,                   // 底层协议栈来源，如 "Apache PLC4X"、"Eclipse Milo"
        String stackVersion,             // 底层协议栈版本，用于问题排查
        String transport,                // TCP / UDP / SERIAL / CAN / SIP / LOCAL
        Set<ProtocolCapability> capabilities,
        Set<Class<? extends ProtocolExtension>> extensions,   // 显式声明的扩展接口（见 §6.1）
        String minimumRuntimeVersion,    // 要求的最低 iot-runtime 版本（含）
        String maximumRuntimeVersion,    // 支持的最高 iot-runtime 版本（不含）
        Map<String, String> attributes) {  // 协议元信息（默认端口、规范编号等）

    public static Builder builder();
    public boolean supports(ProtocolCapability capability);

    /** 是否声明支持某个扩展接口。 */
    public boolean supportsExtension(Class<? extends ProtocolExtension> extensionType);
}
```

**三个新增字段的用途（缺一不可）**：

| 字段 | 解决的问题 |
|---|---|
| `extensions` | **让扩展能力可被发现**——框架与宿主可在不 `unwrap` 试探的前提下知道某协议支持哪些扩展（如 OPC UA 支持 BROWSE/HISTORY）。详见 §6.1 |
| `minimumRuntimeVersion` / `maximumRuntimeVersion` | **让版本不兼容变成启动期报错**，而不是运行期难以定位的行为异常。21 个协议模块独立发版，宿主可能组合出不兼容版本，校验逻辑见 `RUNTIME.md` §5.3 |

---

## 6. 协议扩展接口（`unwrap` 的目标）

只属于部分协议的能力，一律做成**独立接口**并通过 `unwrap` 暴露。这样主契约保持精简，
新增一类协议特有操作不需要动 core。

### 6.1 扩展能力的发现机制（显式声明，不用反射探测）

**问题**：框架与宿主需要知道「这个协议支持哪些扩展」——例如管理界面要决定是否展示"目录浏览"入口、
诊断工具要决定是否调用 `readHistory`。若靠 `unwrap(...)` 逐个试探，
就必须在编译期依赖所有扩展接口类型，且"不支持"与"暂时不可用"无法区分。

**方案**：在 `ProtocolDescriptor.extensions()` 中**显式声明**。

| 规则 | 说明 |
|---|---|
| **显式声明，不反射** | 适配器通过 `descriptor().extensions()` 列出自己实现的扩展接口；框架**不**通过反射扫描类实现来推断 |
| **声明与实现必须一致（fail-fast）** | 注册时校验：声明了 `BrowseExtension` 就必须 `unwrap(BrowseExtension.class)` 返回非空；反之实现了却未声明也报错（见 `DESIGN.md` §4.1 校验项） |
| **声明 `BROWSE` 能力即须声明扩展** | `ProtocolCapability.BROWSE` 与 `BrowseExtension` 必须成对出现，否则视为配置错误 |
| **未声明即返回空** | 对未声明的扩展调用 `unwrap` 返回 `Optional.empty()`，**不抛异常**（区别于能力方法：`read`/`write` 未声明会抛 `UnsupportedCapabilityException`——因为那是"操作失败"，而 `unwrap` 是"能力探测"） |

**第三方扩展接口的注册**：**允许**第三方协议模块定义自己的扩展接口，约束只有两条：

1. 必须继承 `ProtocolExtension` 标记接口；
2. 必须在自己模块的 `descriptor().extensions()` 中声明。

这样 `unwrap` 机制对第三方开放，core 不需要为每个新扩展改动（符合 P4 原则）。

### 6.2 扩展接口的版本管理

**不做**「扩展接口自带 `version()` 方法」这种设计——它把版本兼容的责任推给了每个实现者，
但实现者无从判断调用方需要哪个版本。

**改为**：扩展接口的兼容性由**包名 + 核心版本区间**共同保证：

| 变更类型 | 处理 |
|---|---|
| 新增方法 | 定义为 `default` 方法（提供合理默认），**保持二进制兼容** |
| 修改已有方法签名 | **必须**新建接口（如 `BrowseExtension2`）或提升 `iot-core` 主版本 |
| 删除方法 | 同签名修改 |
| 接口整体弃用 | 保留旧接口标 `@Deprecated`，新增替代接口，在下一个主版本移除 |

宿主若需要适配多个扩展接口版本，用 `unwrap` 的 `Optional` 语义天然兼容：
`session.unwrap(BrowseExtension2.class).or(() -> session.unwrap(BrowseExtension.class).map(adapter::wrap))`。

### 6.3 扩展接口清单

```java
/** 协议扩展能力标记接口：所有通过 unwrap 暴露的扩展都必须继承它。 */
public interface ProtocolExtension {
    ProtocolCode protocol();
}

/** 节点目录浏览（OPC UA、BACnet、KNX 等）。 */
public interface BrowseExtension extends ProtocolExtension {
    CompletionStage<BrowseResult> browse(BrowseRequest request);
}

/** 历史数据读取（OPC UA HistoryRead、时序库回补）。 */
public interface HistoryExtension extends ProtocolExtension {
    CompletionStage<ReadResult> readHistory(HistoryReadRequest request);
}

/** 设备发现（BACnet Who-Is、ONVIF WS-Discovery、mDNS、网段扫描）。 */
public interface DiscoveryExtension extends ProtocolExtension {
    CompletionStage<List<DiscoveredDevice>> discover(DiscoveryRequest request);
}

/** 文件/块传输（S7 块上传下载、FTP 式固件传输、ONVIF 录像检索）。 */
public interface FileTransferExtension extends ProtocolExtension {
    CompletionStage<TransferHandle> upload(TransferRequest request, java.io.InputStream source);
    CompletionStage<Void> download(TransferRequest request, java.io.OutputStream target);
}

/** 远程方法/服务调用（OPC UA Method、SNMP SET 批量、BACnet WriteProperty 多属性）。 */
public interface InvokeExtension extends ProtocolExtension {
    CompletionStage<InvokeResult> invoke(InvokeRequest request);
}
```

> **扩展接口的落地节奏**：MVP 阶段 core 只定义 `ProtocolExtension` 标记接口与
> `BrowseExtension`；其余扩展接口随对应协议模块首批实现时再补进 core，
> 避免一次性设计出没人用的抽象（YAGNI）。第三方协议模块也可以在自己模块内定义扩展接口，
> 只要继承 `ProtocolExtension` 即可被 `unwrap` 机制识别。

---

## 7. 异常体系

```java
/**
 * IoT 接入层根异常，全部为 unchecked，避免污染适配器签名。
 *
 * <p><b>携带消息键而非格式化文案</b>：{@code messageKey} 形如
 * {@code iot.<protocol>.<category>.<detail>}，由 {@code -spring-boot-starter} 在 Web 层
 * 或宿主在日志层通过母仓 {@code I18nUtil.message(key, args)} 解析为当前语言文案。
 * 这样 {@code iot-core} 既不需要依赖 i18n 机制，也能支持多语言。</p>
 *
 * <p>{@link #getMessage()} 返回的是<b>键 + 原始参数</b>的调试形式（便于日志排查），
 * <b>不可</b>直接展示给最终用户。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public class IotException extends RuntimeException {

    /** i18n 消息键（如 {@code iot.common.connection.timeout}）。 */
    public String getMessageKey();

    /** 消息参数，供 i18n 格式化使用。 */
    public Object[] getMessageArgs();
}

/** 建链/绑定/链路断开类错误。消息键 {@code iot.common.connection.*}。 */
public class ConnectionException extends IotException {
    public ConnectionException(String connectionId, String messageKey, Object... args);
}

/** 协议层错误：报文非法、返回码异常、状态机冲突。消息键 {@code iot.common.protocol.*}。 */
public class ProtocolException extends IotException { }

/** 请求超时。超时是工业现场最高频的错误，因此单独成类以便框架做熔断判定。 */
public class ProtocolTimeoutException extends IotException {

    public ProtocolTimeoutException(String deviceId, String address, Duration timeout);

    /** 超时发生在哪个地址上（用于定位是哪个点位拖慢的）。 */
    public String getAddress();

    /** 超时阈值。 */
    public Duration getTimeout();
}

/** 地址解析失败。消息键 {@code iot.<protocol>.address.*}。 */
public class AddressParseException extends IotException {
    public AddressParseException(ProtocolCode protocol, String raw, String reason);
}

/**
 * 能力不支持。调用未声明能力的方法时抛出。
 *
 * <p><b>这是 fail-fast，不是降级</b>：宁可让调用方立刻收到明确异常，
 * 也不要返回空结果让宿主误以为设备正常。</p>
 */
public class UnsupportedCapabilityException extends IotException {
    public UnsupportedCapabilityException(ProtocolCode protocol, String operation);
}
```

> **与母仓异常体系的对接**：`iot-core` 的异常**不继承**母仓 `BusinessException`（core 零 Spring 的必然结果）。
> `-spring-boot-starter` 提供一个 `IotExceptionTranslator`，把 `IotException` 及其子类映射为
> `R.fail(code, msg)`（HTTP 恒 200，遵循母仓铁律 9），业务码分配见该章节。
> 协议模块自身**不得**依赖 `R` 或任何母仓 Web 类型。

---

## 8. 宿主侧扩展点

```java
package cn.ypbin.iot.core.spi;

/**
 * 数据落地 SPI：宿主实现它把微批数据写入 Kafka / 时序库 / 规则引擎。
 *
 * <p>与 {@link cn.ypbin.iot.core.context.DataEgress} 的分工：
 * {@code DataEgress} 是<b>框架侧</b>的出口（负责微批、背压、指标），
 * {@code DataSink} 是<b>宿主侧</b>的落点（负责真正的 I/O）。
 * 框架自带的默认 {@code DataEgress} 实现把批次转交给所有注册的 {@code DataSink}。</p>
 *
 * <p>一个宿主可以注册多个 Sink（如同时写 Kafka 与本地缓冲），
 * 单个 Sink 抛异常不影响其余 Sink——异常被捕获、记录并计入指标。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DataSink {

    /** Sink 名称，用于日志与指标标签。 */
    String name();

    /**
     * 写入一批数据。实现应快速返回或自行异步化，不要在此做长时间阻塞。
     *
     * @param batch 数据批次
     */
    void write(DataBatch batch);

    /** 关闭 Sink，释放资源。 */
    default void close() { }
}

/** 设备事件监听 SPI：宿主据此落库、告警、更新设备在线状态。 */
public interface DeviceEventListener {
    void onEvent(DeviceEvent event);
}
```

```java
package cn.ypbin.iot.core.spi;

import java.util.List;
import java.util.function.Consumer;

/**
 * 设备来源 SPI：宿主从这里提供设备清单与配置变更。
 *
 * <p><b>这是「接入路径零 DB 访问」的唯一入口</b>（见 {@code DESIGN.md} §5.4）：
 * {@link #loadAll()} 在<b>启动期</b>被调用一次并全量加载到内存，此后运行期只读内存；
 * 运行期变更由宿主<b>主动推入</b>（{@link #addChangeListener}），
 * <b>框架不会去轮询数据库</b>。</p>
 *
 * <p>实现者请务必遵守：</p>
 * <ul>
 *   <li>{@code loadAll()} 可以访问 DB（启动期，无并发压力），但必须处理分页——10 万设备不要一次查出来；</li>
 *   <li>变更推送通道<b>不能是 DB 轮询</b>：应由 admin 主动调用框架 API，或经消息总线订阅变更；</li>
 *   <li>变更事件必须携带单调递增的 {@code revision}，框架据此做幂等与乱序保护。</li>
 * </ul>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface DeviceRegistry {

    /**
     * 全量加载设备清单（启动期调用一次）。
     *
     * @return 设备列表；无设备时返回空列表，不得返回 {@code null}
     */
    List<DeviceSpec> loadAll();

    /**
     * 校验设备配置是否可接入。
     *
     * <p>返回校验结果而<b>不抛异常</b>：校验失败是正常的业务结果（用户填错配置），
     * 不是程序错误。宿主据此向用户展示具体原因。</p>
     *
     * <p>默认实现返回通过。框架会在此基础上叠加通用校验（deviceId 唯一、协议已注册、
     * 连接规格存在、采集周期合法），协议模块再叠加协议特有校验（地址可解析、封装与端点匹配）。</p>
     *
     * @param device 待校验的设备规格
     * @return 校验结果，永不为 {@code null}
     */
    default ValidationResult validate(DeviceSpec device) {
        return ValidationResult.ok();
    }

    /**
     * 注册变更监听。
     *
     * <p>框架在启动完成后注册监听器；宿主在设备增删改时调用监听器。
     * 同一 {@code deviceId} 的变更由框架按设备级排他锁串行处理（见 {@code RUNTIME.md} §3.1）。</p>
     *
     * @param listener 变更监听器
     */
    void addChangeListener(Consumer<DeviceChange> listener);
}

/**
 * 设备配置校验结果。
 *
 * @param passed  是否通过
 * @param reasons 未通过时的逐项原因；通过时为空列表
 * @author wenbin
 * @since 2026-09-13
 */
record ValidationResult(boolean passed, List<String> reasons) {

    public static ValidationResult ok();

    public static ValidationResult fail(String... reasons);
}

/**
 * 设备配置变更事件。
 *
 * @param type     变更类型
 * @param device   变更后的设备规格；{@code REMOVE} 时携带被删除设备的规格（用于清理影子）
 * @param revision 单调递增的配置版本号，用于幂等与乱序保护
 * @author wenbin
 * @since 2026-09-13
 */
record DeviceChange(ChangeType type, DeviceSpec device, long revision) {

    /** 变更类型。 */
    enum ChangeType {
        ADD(1, "新增"), UPDATE(2, "修改"), REMOVE(3, "删除");

        private final int code;
        private final String desc;
        // getCode() / getDesc() / fromCode(int)
    }
}
```

### 8.1 设备影子不在本仓（边界声明）

`DeviceRegistry` 的 `REMOVE` 事件是宿主清理设备影子的**唯一可靠时机**——
本仓不实现影子存储（见 `RUNTIME.md` §1），但保证**删除事件一定送达**，
否则 Redis 中的影子数据会永久残留并无限增长。

---

### 8.2 `ConnectionSpecProvider` —— 链路规格来源（M0 新增）

> **这是 M0 实施中识别出的 SPI 缺口**：原设计只有 `DeviceRegistry`（设备从哪来），
> 未回答「链路的建链参数（端点/超时/TLS/凭据引用）从哪来」。
> 两者职责不同且**必须拆开**：一条 Modbus 网关链路可被 200 个从站共享，
> 把 endpoint 放进 `DeviceSpec` 会导致 200 个设备各携带同一份端点、改一次要改 200 处。

```java
package cn.ypbin.iot.core.spi;

import cn.ypbin.iot.core.model.ConnectionSpec;
import java.util.Optional;

/**
 * 物理链路规格来源 SPI：按 {@code connectionId} 提供建链参数。
 *
 * <p>运行期约束同 {@code DeviceRegistry}：实现应在启动期把链路规格加载到内存
 * （或直接由配置提供），运行期只读；框架只在设备绑定与重连时查询它，
 * **不会在采集路径上反复调用**。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
public interface ConnectionSpecProvider {

    /**
     * 按链路标识查找建链规格。
     *
     * @param connectionId 链路标识
     * @return 链路规格；不存在时返回空 Optional
     */
    Optional<ConnectionSpec> find(String connectionId);
}
```

**框架如何使用两者**（`IotLifecycle` 的绑定流程）：

```
DeviceRegistry.loadAll()                    → 得到设备清单（deviceId + protocol + connectionId + localAddress）
  └─ 对每个设备：
      ConnectionSpecProvider.find(connectionId)  → 得到建链参数
        └─ ConnectionRegistry.acquire(adapter, spec, ctx)   → 单飞建链 / 复用
            └─ ProtocolAdapter.bind(connection, device, ctx) → 设备会话
```

两者都返回空则不接入该设备，并**记 warn 日志**（不静默跳过）。

---

## 9. 一个完整的最小协议实现（参考样例）

下面是一个「通用 TCP 透传」协议适配器的完整实现骨架，用于说明**新增协议到底要写多少代码**：

```java
package cn.ypbin.iot.protocol.tcp;

/**
 * 通用 TCP 透传适配器：把设备当作裸 TCP 字节流的读写通道，不做任何协议解释。
 *
 * @author wenbin
 * @since 2026-09-13
 */
public final class TcpPassthroughAdapter extends BlockingProtocolAdapter {

    private static final ProtocolDescriptor DESCRIPTOR = ProtocolDescriptor.builder()
            .code(ProtocolCode.of("tcp"))
            .name("TCP 透传")
            .vendor("Netty")
            .transport("TCP")
            .capabilities(ProtocolCapability.READ, ProtocolCapability.WRITE,
                    ProtocolCapability.SUBSCRIBE_STREAM)
            .build();

    private final NettyTransport transport;   // ypbin-iot-transport 提供

    public TcpPassthroughAdapter(NettyTransport transport) {
        this.transport = transport;
    }

    @Override
    public ProtocolDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    protected ProtocolConnection openBlocking(ConnectionSpec spec, AdapterContext ctx) {
        // 1. 建 TCP 链路（复用 transport 的连接工厂与编解码管线）
        // 2. 包装为 ProtocolConnection，链路断开时完成 whenClosed()
        return transport.open(spec, ctx);
    }
}
```

**结论**：一个「一设备一链路、无需地址解析」的协议，实现量约 30 行；
需要地址解析与链路复用的协议（Modbus、S7、BACnet）约 300~600 行；
需要 SIP 信令与媒体处理的协议（GB28181）则需要独立子系统，但仍通过同一个 `ProtocolAdapter` 接入。
