# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

当前开发版：`0.4.1-SNAPSHOT`。

已发布：**`0.1.0`（2026-09-21）**、**`0.2.0`（2026-09-29）**、**`0.3.0`（2026-10-12）** 与
**`0.4.0`（2026-10-17）**，均已发布到 Maven Central；其中 `0.1.0` 由 `ypbin-access`
作为首个真实宿主接入验证。

## [未发布]

### 新增

- **订阅编排下沉到框架 `SubscriptionOrchestrator`（issue #37）**：框架此前只提供 `SubscribeRequest`
  数据模型与 `DeviceSession#subscribe` 单次调用，**没有任何订阅编排的承接方**——「哪个点位要订、
  什么时候重订、失败了怎么退避、部分成功怎么补」全留给宿主（下游 `AccessSubscriptionPlanner`
  为此写了 639 行），而漏一条就是运行期故障：漏掉逐点位分流 ⇒ 裸 TCP 只交付第 0 个点位；
  漏掉只补失败地址 ⇒ 每轮全量重订、同一点位重复读数；漏掉按会话实例判重订 ⇒ 重连后数据永久停更。
  现在 `ypbin-iot-spring-boot-starter` 新增 `cn.ypbin.iot.spring.subscription.SubscriptionOrchestrator`
  与 `SubscriptionOutcome` / `SubscriptionPoint` / `SubscriptionResult` / `SubscriptionStatus` /
  `SubscriptionBackoffPolicy` / `ProtocolDescriptorSource`，内建六项能力：
  - **整批 vs 逐点位分流**：判据来自 `ProtocolDescriptor.subscriptionAddressing()` 的**协议声明**
    （新增 `SubscriptionAddressing` 枚举），不再由宿主按协议码 + `payload-format` 字符串硬编码；
    未声明时按保守侧 `PER_ADDRESS` 处理（默认整批的失败模式是静默数据缺失，默认逐点位只损失效率）；
  - **多寄存器地址展开**：点位只声明 `wordCount`，`base+1..base+N-1` 由协议声明的
    **新增扩展接口 `AddressExpansionExtension`** 计算（框架不猜地址语法，避免偏移错一位）；
    协议未声明该扩展时该点位**显式失败**而不是只订基址（半截字节 + GOOD 质量比直接失败更难排查）；
  - **在途去重**：同设备同会话的并发订阅合并为一次真实调用；
  - **部分成功只补失败地址**：`SubscriptionOutcome.retryAddresses()` 是唯一入口，已成功地址绝不重发；
  - **按「设备 + 会话实例」指数退避**，配置项 `ypbin.iot.subscription.backoff.{initial-delay,max-delay,multiplier}`；
    会话实例变了（重连新建会话）即视为新上下文、退避作废并立刻重订——判据用**对象身份**而非
    `sessionId()` 字符串（协议模块的 sessionId 由「设备+链路」拼成，重连后可能完全相同）；
  - **`@ConditionalOnMissingBean` 可被宿主整体覆盖**，并接入 `AutoConfiguration.imports`。
  `ProtocolDescriptor` 新增字段带**旧 10 参数构造器重载**（委托为新构造器的「未声明」语义），
  既有构造调用点零改动。测试：`SubscriptionOrchestratorTest` ORCH-01~23（分流/展开/只补失败/
  并发去重/换会话重订/退避上限与重置）、`SubscriptionOrchestratorWiringTest` ORCHW-01~06
  （imports 登记/默认装配/宿主覆盖/配置绑定/非法配置归一化/描述符来源接线）。变异验证：
  把「只补失败地址」改为「全量重试」后 4 个对应用例转红，已还原。

- **订阅寻址升级为「协议级默认 + 按形态细分」：TCP key-value 也能整批订阅（issue #37 技术债反哺）**：
  上一版把订阅寻址做成**协议级单一值**，于是 TCP 只能声明 `PER_ADDRESS`——但 key-value 形态
  （`payload-format=key-value`）下订阅地址是帧内字段 selector、一帧可服务多个点位，本可整批。
  被迫取交集的后果是：宿主为拿回性能**在业务仓自建退避表、绕开框架编排器**，通用能力被焊在业务仓
  （全局规范 1.6）。本次把表达能力补齐：
  - 新增 `SubscriptionAddressingPolicy`：协议级默认值 + `variant 键 → 寻址方式` 映射 +
    `VariantResolver`（形态解析器）。`ProtocolDescriptor` 新增 `addressingPolicy` /
    `variantResolver` 两个组件，并提供唯一解析入口 `resolveAddressing(DeviceSpec)`
    ——编排器只调这一个方法，**不认识任何形态字符串**（避免「宿主硬编码 `payload-format`」
    的问题换个地方复发）。
  - **未命中语义**：协议声明了细分表但当前形态不在表内 ⇒ **兜底 `PER_ADDRESS` 并 WARN 一次**
    （按协议 + 设备去重，不刷屏）。刻意<b>不回落协议级默认值</b>——那可能就是 `BATCH`，
    把「未知形态」当「支持多地址」的失败模式是静默数据缺失。
  - **TCP 适配器**按新机制声明：`key-value ⇒ BATCH`，`binary/text/number ⇒ PER_ADDRESS`；
    形态键取自 `TcpPayloadFormat#variant()`（协议自己定义），适配器实例的描述符由
    实例的 `payload-format` 构造。**宿主不再需要为 key-value 自建退避表。**
  - **向后兼容**：旧 10 参构造器、上一版 11 参构造器继续可用（分别映射为「未声明」与
    「策略 = 该单选值、无细分」）；builder 不声明细分时行为与 0.4.x 逐字一致；
    未声明的第三方协议仍按保守侧 `PER_ADDRESS`。回归证据：
    `SubscriptionAddressingPolicyTest` SAP-06/07、`SubscriptionOrchestratorTest` ORCH-V1。
  - 测试：`SubscriptionAddressingPolicyTest` SAP-01~12（未声明/命中/未命中/取不到形态/
    旧构造器兼容/解析器抛异常/防御性拷贝）、`SubscriptionOrchestratorTest` ORCH-V1~V6
    （a 未声明回归、b 同协议两形态分流、c 未命中兜底 + WARN 断言、d WARN 去重、
    e 核心不变量回归：只补失败地址 / 在途去重）、`TcpSessionBehaviourTest` TCP-10b/10c/10d
    （真实适配器 + 真实回显服务 + 真实编排器：key-value 一次订阅 3 个 selector、全部收到数据）。
  变异验证：把「未命中兜底 PER_ADDRESS」改成「兜底 BATCH」后 2 个用例转红
  （`undeclaredVariantMustFallBackToConservativeSide`、`descriptorMustResolveVariantFromResolver`），已还原。

- **官方协议补齐订阅声明：Modbus 实现地址算术扩展，四个协议显式声明寻址方式（issue #37 缺口修复）**：
  首版只有声明机制、没有协议实现，导致迁移后**多寄存器点位 100% 失败**（`wordCount>1` 时
  找不到 `AddressExpansionExtension` 即整点位失败、连基址都不订），且四个官方协议全部落在默认
  保守侧 `PER_ADDRESS`、订阅调用数按点位数放大。本次补齐：
  - **Modbus 新增 `ModbusAddressExpansion`**（协议模块包内，无 Spring 注解）实现
    `AddressExpansionExtension`，`ModbusSession.unwrap` 返回它、`ModbusAdapter` 描述符登记
    `.extensions(AddressExpansionExtension.class)`。地址语义与宿主原实现一致
    （`holding:0` → `holding:1`），传统 5 位编号基址（`40001`）归一为规范 `type:offset`
    再偏移；**越界（offset+delta > 65535）显式抛 `AddressParseException` 而不回绕**
    ——回绕会把「越界配置」变成「悄悄读到另一个寄存器的值」；`delta<1` 判非法；
    `offset+delta` 用 `long` 运算，避免 int 溢出成负偏移绕过上界判断。
  - **Modbus / MQTT / OPC UA 显式声明 `subscriptionAddressing = BATCH`**，TCP 显式声明
    `PER_ADDRESS`。取舍：**默认值仍取保守侧**（未声明的第三方协议安全），官方协议按各自
    `subscribe(request)` 的实测语义显式表态。每处声明都配了「一次请求多地址、每个地址都产出
    数据」的实测用例，不凭协议印象声明：
    `ModbusSubscriptionOrchestrationTest` MSO-01~04（真实适配器 + 真实 TCP 测试服务器，
    `wordCount=2` 点位必须读到设备侧两个不同寄存器的值 `1111`/`2222`）、
    `MqttMultiAddressSubscriptionTest` MPF-06/07（真实嵌入式 broker，一次请求 2 个主题、
    两个主题都必须有数据）、`OpcUaAdapterTckTest` OPC-06c（一次请求 2 个 NodeId、两个节点
    都必须被推送）、`TcpSessionBehaviourTest` TCP-10b（声明与 TCP-11 多地址 fail-fast 行为一致）。
  - **TCP 的形态差异已由「按 variant 细分」表达**（见上一条）：`key-value ⇒ BATCH`、
    其余 ⇒ `PER_ADDRESS`。此前「key-value 只能由宿主自己分流」的限制**已解除**，
    宿主可删除自建的 key-value 退避表/绕行逻辑。
  变异验证：把 `ModbusAddressExpansion.offset` 改成不做偏移（直接返回基址）后
  **7 个用例转红**（含端到端 `MSO-02`/`MSO-03` 与 `MAE-02~05`），已还原。

### 变更

- **母 pom 父链 `ypbin-starter-dependencies` 3.8.0 → 4.0.0（issue #34）**：本仓**代码不依赖**母仓任何模块
  （`grep cn.ypbin.starter` 零命中），父子关系只承袭「依赖版本管理 + 构建门禁（spotless/jacoco/nullaway/
  dep-convergence/enforcer）」。
  - **收益**：下游宿主（如 `ypbin-iot`）不再需要靠「先声明 `ypbin-starter-bom`、再声明 `ypbin-iot-bom`」的
    **声明顺序**来防止版本被顶掉——两个 BOM 现在来自同一基线；也不再出现「业务仓 4.0.0 / iot-starter 按 3.8.0 编译」
    的双基线。
  - **无 API 变更**：本仓源码一行未改，`mvn clean verify` 468 用例全绿、`tools/preflight.sh` 8/8 全过。

- **对外 BOM 固化 + 显式属性（issue #36）**：`ypbin-iot-bom` 原先用 `${revision}` 声明 8 个 IoT 模块版本，
  宿主 `import` 本 BOM 时 Maven 会用**导入方上下文**展开它；而父链 `ypbin-starter` 恰好把
  `ypbin-starter-*` 的版本写成 `${revision}`，于是宿主的 starter 版本被顶成 IoT 的版本号
  （实测：期望 `ypbin-starter-core` 4.0.0，实际 **0.4.0**）——编译能过、运行期行为漂移，且完全静默。
  - 修法两条（都实测必需）：① BOM 自身的依赖版本改用显式属性（`${iot-starter.version}` /
    显式 `import cn.ypbin:ypbin-starter-bom:${ypbin.starter.version}`）；② BOM 用
    `flatten-maven-plugin` 的 `flattenMode=bom` **固化**后安装——安装出去的 pom 不带 `<parent>`，
    继承来的 `${revision}` 条目整体消失，本地属性被解析成字面量。
  - **宿主无需改任何 POM**：只需 `import cn.ypbin:ypbin-iot-bom`，「只引 iot-bom、不引 starter-bom」
    也能解析到正确的 `ypbin-starter-*` 4.0.0（已用 `help:effective-pom` 逐项留证）。
  - 新增发布门禁 `tools/check-bom-no-revision.sh`（接入 `tools/preflight.sh` 第 1 步）：
    断言已安装的 `ypbin-iot-bom-*.pom` 中 `${revision}` 出现次数为 0，否则发布前直接失败。

- **设备 revision 由框架分配（issue #38 其一）**：新增 `iot-core` 的 `DeviceRevisionAllocator`
  （按设备 `AtomicLong` 原子递增；`dispatch(type, device)` 构造 `DeviceChange`；
  `dispatch(type, device, hostRevision)` 取 `max(分配值, hostRevision)` 兼容宿主既有自增）。
  宿主不再需要自己维护「按设备严格递增的 revision」——漏递增此前会让该设备的变更被**永久丢弃**且无日志。
  starter 自动注册 `@Bean @ConditionalOnMissingBean DeviceRevisionAllocator`，宿主可替换。
  测试：`DeviceRevisionAllocatorTest`（REV-01~07，含 8 线程 × 200 次并发不重号）。

- **设备级 bind 退避重试（issue #38 其二）**：链路级 `ConnectionReconnector` 只覆盖「已绑定链路断开」，
  「bind 本身失败」此前只记一行 WARN 就放弃 ⇒ 设备在配置变更后**永久离线**（宿主只能自己补一套重试）。
  现在 `IotLifecycle` 区分**瞬时失败**（建链失败/探测不可达/acquire-bind 超时 ⇒ 退避重试）与
  **永久失败**（无适配器/校验不通过/链路规格缺失/生命周期已关闭 ⇒ 立即放弃并如实记录），
  重试按设备独立退避、逐轮上限、耗尽即停。
  - 新配置（全部在 `ypbin.iot.devices.*`，均可热改）：`rebind-backoff-initial`（默认 `30s`）、
    `rebind-backoff-max`（默认 `2min`）、`rebind-max-per-cycle`（默认 `20`）、
    `rebind-max-attempts`（默认 `5`）。
  - 耗尽时**记 WARN 带完整堆栈**并通过 `DeviceEventListener` 通知宿主（`CONNECT_FAILED` 事件，
    cause 为解包后的真实原因）——框架不落库，宿主只能靠该事件更新设备状态/告警。
  - 启动 bootstrap、`onDeviceChange`、显式 `bind()`、链路 `reconnect()` 四条路径统一接入；
    `unbind`/REMOVE/`close()` 会取消待重试（避免把已解绑设备重新绑回来）。
  - 测试：`LifecycleBindRetryTest`（BRT-01~08，含「每轮不超上限」「耗尽 WARN 带堆栈 + 事件通知」
    「unbind 取消重试」「永久失败不重试」）。

- **过期 revision 丢弃日志不再是 debug（issue #38 其三）**：`onDeviceChange` 丢弃
  `revision <= 已应用值` 的变更时，日志级别由 `ypbin.iot.devices.stale-revision-log-level`
  控制（默认 **INFO**；可选 DEBUG/INFO/WARN/ERROR）。日志内容包含设备、收到的 revision、
  已应用 revision，并直接给出修复方向（改用 `DeviceRevisionAllocator`）。原因：这条日志对应
  「设备永久零数据」的根因，固定在 debug 等于生产上不存在。

- **指数退避上收为纯函数（issue #39 c）**：新增 `iot-core` 的 `ExponentialBackoff`
  （`nextDelay(attempt, initial, max)` / `nextDelayMillis`）。业务仓此前在两处手写
  `base × 2^(attempts-1)`，边界已经分叉（`attempts - 1` vs `Math.max(attempts,1) - 1`、
  一处封顶一处不封顶）。现在起点（`attempt=0/1` 均为 initial）、翻倍、封顶、指数上界（2^20 防溢出）、
  `initial > max`、非法参数 fail-fast 全部由框架钉死。测试：`ExponentialBackoffTest`（BO-01~06）。

### 移交业务仓（`ypbin-iot`，本仓不改）

以下三项经核实属于业务仓职责（本仓无对应代码），按 issue #39 约定**只做移交登记**：

- **a) `MqttBinaryNumberDecoder`**：业务仓的 MQTT 二进制数值解码器应改为复用本仓
  `BytePayloadDecoder` SPI（`0.4.0` 已提供），不必自建一套解码 + 注册机。
- **b) 业务仓重复 `@Bean` 声明**：业务仓重复注册了本仓已自动装配的 Bean，应删除宿主侧声明，
  改用本仓的 `@ConditionalOnMissingBean` 默认实现（需要定制时再覆盖）。
- **d) 业务仓指标埋点**：错误码/指标的 `recordError` 调用点应在业务仓统一到本仓 `IotMessageKeys`
  口径，避免同一失败在两处被记成不同错误码。

## [0.4.0] - 2026-10-17

### 新增

- **宿主可插字节载荷解码 SPI（issue #30）**：二进制交付路径（TCP `payload-format=binary`、MQTT
  `payload-format=binary`、OPC UA `ByteString`）此前只能把原始字节交给宿主，框架没有统一扩展点，
  宿主被迫各自造一套 `BytePayloadDecoder` + 注册机（换宿主就重写一遍，失败信号也各写各的）。
  现在 `iot-core` 定义 SPI（`BytePayloadDecoder` / `DecodeContext` / `DecodeOutcome` /
  `DecodeFailure` / `BytePayloadDecoderRegistry`）与路由门面 `BytePayloadDecoding`，
  `iot-runtime` 提供 `DefaultBytePayloadDecoderRegistry`，starter 收集宿主 `@Bean BytePayloadDecoder`
  并按 `@Order` 选择第一个认领者、注入每个 `AdapterContext`；协议模块在交付字节前调
  `context.decodePayload(...)` 一行接入。行为矩阵：**空注册表 = 与没有本 SPI 逐字一致**（不加计数）；
  非空但未认领 → 原样交付 + `iot.common.payload.decoder.not-found`；认领后失败/解码器抛异常 →
  **BAD** + `iot.common.payload.decode-failed.<原因>`（绝不把垃圾字节当 GOOD）。
  OPC UA 另把 Milo `ByteString` 归一为 `byte[]`（宿主解码器不再被迫依赖 Milo）。
  测试：`BytePayloadDecodingTest`（BPD-01~12）、`DefaultBytePayloadDecoderRegistryTest`
  （BPR-01~10）、`BytePayloadDecoderAssemblyTest`（BPA-01~05）、`MqttBytePayloadDecodingTest`
  （MBPD-01~03）、`TcpBytePayloadDecodingTest`（TBPD-01~02）、`OpcUaBytePayloadDecodingTest`
  （OBPD-01~06）；契约见 [`docs/SPI.md`](docs/SPI.md) §9。

- **Modbus 寄存器词法解码工具 `ModbusRegisterDecoder`（issue #31）**：把宿主自建的纯算术解码下沉到
  `ypbin-iot-protocol-modbus`——单寄存器 16 位（有/无符号）、双寄存器 32 位（ABCD/CDAB 字序）、
  IEEE754 单/双精度、多寄存器严格 UTF-8（去尾部 NUL、保留中间 NUL）、位域，全部零 IO、无状态、
  无外部依赖。字序口径与宿主历史实现一致（`big`=高字在前、`little`=低字在前/整体翻转），
  不支持 BADC 等字内字节交换（属于设备方言）。测试：`ModbusRegisterDecoderTest` MRD-01~14
  （含 8 组经典字序矩阵与边界），下游可直接以该测试作为迁移对照表。

- **有界微批出口队列 `BoundedBatchEgressQueue<T>`（issue #32）**：在 `iot-runtime` 沉淀流式采集
  缓冲出口的通用基座——`offer` 用 `ArrayBlockingQueue#offer` **绝不阻塞协议线程**，队满/已关闭
  返回 `false` 并计数、上报 `iot.common.egress.dropped`、限流 WARN；单平台 daemon 线程按
  `batchSize` + `flushInterval` 微批消费；Sink 抛异常只损失本批（ERROR 全堆栈 + 失败计数，
  线程继续）；`close()` 有界等待后把剩余元素按批循环 flush（不静默丢在内存里），
  消费线程卡死时不并发调 Sink。宿主只需实现 `BatchEgressSink<T>`（写 HTTP / 发 Kafka / 批量落库），
  缓冲、并发、丢弃可见性与停机排空由基座兜底。测试 `BoundedBatchEgressQueueTest` BEG-01~12
  （切批不丢、时间窗口、队列满计数、关闭 flush/drain、故障隔离、并发不丢不重、fail-fast、
  gauge 上报、卡死 Sink 的有界等待）。

### 破坏性变更

- **OPC UA 的 `ByteString` 节点现在交付 `byte[]` 而不是 Milo `ByteString` 对象（issue #30）**：
  为了使宿主解码 SPI（输入契约为 `byte[]`）能处理 OPC UA 二进制节点，且避免每个宿主都被迫依赖
  Milo，`OpcUaSession` 现在把 `ByteString` 归一为 `byte[]`（null 值保持 `null`，不伪造空数组）。
  **迁移方式**：若宿主此前按 `(ByteString) value` 消费该点位，改为 `(byte[]) value`；
  若只需原样落库，`byte[]` 与 `ByteString.bytes()` 内容一致。
  该变更**与是否注册解码器无关**（空注册表也归一），因为「交付类型稳定」比「零改动」更重要。

### 修复

- **`BoundedBatchEgressQueue` 的 offer 与 close 竞态会静默丢元素（issue #32，独立验收发现）**：
  `offer` 的「检查 `closed` → 入队」与 `close` 的「置位 → interrupt → join → 兜底 flush」之间
  原本无双同步，存在窗口：offer 读到 `closed=false`，close 完成排空并返回，offer 才入队成功
  —— 该元素既未送达也未计入 `dropped`，永久滞留内存，直接违反「关闭后不得静默接受」的承诺。
  修法：用 `ReentrantLock` 把「检查 + 入队」与「置关闭标志」串行化（禁 `synchronized`），
  且锁内只做 O(1) 操作（不计日志）。回归用例 `BEG-12`（400 轮 × 4 生产者并发 offer + close，
  断言 `accepted <= delivered + dropped` 且 `pending == 0`），**回退修复即变红**。
  另修正 `BEG-05` 的等待条件（原只等 `sink.received.size()`，与随后断言的 `deliveredItems`
  存在竞态，实测会偶发红）。

- **`BytePayloadDecoder#supports()` 抛异常会逃到协议线程（issue #30，独立验收发现）**：
  `BytePayloadDecoding.apply` 的 `registry.find(context)` 在 try 之外，宿主解码器在 `supports()`
  里抛异常（SPI 明令禁止，但禁止不等于不会发生）会沿 MQTT/OPC UA 的协议库回调栈逃逸
  （TCP 有传输层兜底，MQTT/OPC UA 没有）。修法：把 `find` 包进 try/catch，按 `decoder-error`
  处理（BAD + 指标 + ERROR 全堆栈），与 `decode()` 抛异常的行为一致。
  回归用例 `BPD-13`（core）与 `OBPD-06`（OPC UA 真实会话）。

- **SDK 指标桥在真实宿主里从未生效（UP-11 二次修复）**：Boot 4.x 定义 `MeterRegistry` 的自动配置是
  `CompositeMeterRegistryAutoConfiguration`，而 `MetricsAutoConfiguration` 只定义 `Clock` /
  `MeterRegistryPostProcessor` / `PropertiesMeterFilter`，并不定义 `MeterRegistry`。原实现用
  `@AutoConfiguration(afterName = "...MetricsAutoConfiguration")` + 方法级
  `@ConditionalOnBean(MeterRegistry.class)` 判存在性，而 `@ConditionalOnBean` 在**装配期**求值、
  只计入「到此为止已注册的 bean 定义」：本装配比 Boot 定义 registry 的自动配置先求值条件，
  恒判假。结果宿主 `ypbin-access`（有 actuator、`/actuator/metrics` 93 条指标）每次启动都回退
  no-op 并打 WARN，`ypbin.iot.errors`（含 `type=iot.tcp.selector.not-found`）永远查不到。
  修法：删除 `afterName` 与 `@ConditionalOnBean`，改为在 bean 方法注入
  `ObjectProvider<MeterRegistry>`、于**实例化期**解析（此时全部自动配置的 bean 定义已注册），
  与自动配置顺序无关；没有 registry 时回退 no-op 并打 **WARN**（不静默降级）。
  指标名/标签契约不变（`ypbin.iot.errors` + `type`）。回归用例 `METCFG-05/06/07` 用真实 Boot
  指标链构造上下文，修复前红、修复后绿；设计说明见 `docs/DESIGN.md` §3.7。

- **协议层错误指标从不出现（issue #27）**：`MetricsRecorder#recordError` 在协议层无生产调用点，
  文档承诺的 `iot.tcp.*` 等错误计数永远不会注册，宿主
  `/actuator/metrics/ypbin.iot.errors` 恒 404（与指标桥是否修好无关）。修法：在 TCP / Modbus /
  MQTT / OPC-UA 的失败路径上调用 `context.metrics().recordError(<既有 MSG_* 错误码>)`
  （只加计数，不改返回值/状态语义；同一失败只计一次，未知原因不产生标签）。补 13 条真实路径
  契约测试（`SimpleMeterRegistry`，触发一次 → `ypbin.iot.errors{type=<码>}` +1），并新增
  `MetricsCallSiteGateTest` 双向门禁（`docs/METRICS.md` 错误码表 ↔ 协议层 `recordError` 调用点，
  删掉任一调用点即红）；错误码清单见新增的 [`docs/METRICS.md`](docs/METRICS.md)。

## [0.3.0] - 2026-10-12

### 新增

- **契约显式化：同一设备每次 ADD 都产生新的 `DeviceSession` 实例、且旧会话先被关闭**
  （`IotLifecycleTest#everyAddMustCloseOldSessionAndRebindNewInstance`，LIFE-13）。
  宿主 `ypbin-access` 的订阅对账以「会话实例是否变化」决定要不要重新订阅（`subscribedSessions` 用 `==` 比较），
  此前这条语义只存在于 `bind()` 的实现细节、无测试覆盖；现提升为**有测试支撑的行为承诺**（见 `CONTRACT.md` §四 第 7 条），
  防止「改为复用会话实例」导致宿主静默退化成「改了点位却永不重新订阅」。

- **TCP 透传支持 `payload-format=key-value` 的帧内字段选择（valueSelector，#15 期望 A）**：
  订阅地址（`PointAddress.raw`）被解释为 `KEY=VALUE` 键值帧的字段 selector，一次订阅可含多个
  地址，同一帧为各点位分别产出 `PointValue`（值为 `String`，类型转换仍归宿主物模型）。
  原「多地址 fail-fast」约束仅在非 `key-value` 格式保留；`key-value` 下 selector 语法非法在
  订阅期 `AddressParseException` fail-fast，运行期未命中只让该点位 BAD
  （`iot.tcp.selector.not-found`），不静默丢弃、不把整帧当 GOOD；帧非 UTF-8 / 无键值字段时
  全部点位 BAD。语义、失败矩阵、`raw_address` 角色与迁移指引见
  [`docs/TCP-VALUE-SELECTOR.md`](docs/TCP-VALUE-SELECTOR.md)，行为承诺见 `CONTRACT.md` §四 第 8 条。
  **边界**：只支持扁平 `KEY=VALUE`，不支持 JSONPath/嵌套/引号转义/逐点位分隔符；不做数值类型转换。

### 构建

- 根 POM parent 由 `ypbin-starter-dependencies` `3.6.0` 升级到 **`3.8.0`**，与母仓最新发布基线对齐；
  离线 `mvn -o -DskipTests compile` 与 `mvn -o test` 均通过（Central 制品已在本地解析）。

## [0.2.0] - 2026-09-29

### 修复

- **TCP 透传整帧当值、曲线画不出（UP-3，#13）**：新增 `ypbin.iot.protocol.tcp.payload-format`
  （`binary`（默认，向后兼容）/`text`（严格 UTF-8）/`number`），`TcpSession` 按格式解码入站帧——
  非法字节/非数值帧产出 **BAD 质量 + 明确消息键**（不再是 `[B@<hash>` 字面文本、也不再静默 GOOD）。
- **TCP 订阅 N 点位只投递第 0 个（UP-4，#15）**：明示「单点位透传流」契约——`subscribe()`
  对多地址 **fail-fast**（拒绝「声称订阅 N 个点位却静默只交付 1 个」）；一帧多点位的
  `valueSelector` 语义列为后续演进项。
- **建链超时死键（UP-2，#14）**：`ypbin.iot.protocol.<code>.connect-timeout` 从「有绑定、无消费」
  变为活键——宿主未显式给建链超时（恰为默认值）时用配置值覆盖（`ConnectionSpec.withConnectTimeout`）；
  绑定编排的 `probe/acquire/bind` 超时随之生效（connect-timeout=2s 时一趟绑定最坏 2s，不再恒为 10s 饿死租约）。
- **iotMetricsRecorder bean 竞争（UP-11，#16）**：`IotMicrometerAutoConfiguration` 声明
  `afterName=org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration`，
  修正 `@ConditionalOnBean(MeterRegistry)` 求值时机；classpath 有 micrometer 却仍回退 Noop 时日志升 **WARN**。
- **辅助缓冲无消费者噪声（UP-12，#17）**：`NettyChannelConnection` 的入站缓冲**惰性启用**——
  无 `drainFrames()` 消费者时不入队、不计数、不告警；缓冲溢出首帧 WARN、其后 DEBUG，
  计数语义注明「淘汰的是辅助缓冲副本，不代表数据面投递丢失」。

## [0.1.0] - 2026-09-21

### 新增

- **多协议适配 SPI**（core）：`ProtocolAdapter` / `ProtocolConnection` / `DeviceSession` 三层契约，
  以及 `AdapterContext`、`CredentialResolver`、`DataEgress`、`MetricsRecorder`、`DeliveryDispatcher`、
  `TaskScheduler` 等框架门面。契约详见 `docs/SPI.md`。
- **运行时**（runtime）：`ConnectionRegistry`（单飞建链 + 引用计数 + 空闲回收 + 建链限速）、
  `PollingSubscriptionManager`（`SUBSCRIBE_POLLING` 的框架侧实现）、
  `BoundedDeliveryDispatcher`（宿主回调有界投递）、`EgressRouter`（有界出口 + 溢出策略）。
- **传输底座**（transport）：基于 Netty 的 TCP 连接与三种帧定界（不定界 / 长度字段 / 分隔符）。
- **Spring Boot starter**（spring-boot-starter）：`ypbin.iot.*` 条件装配、`IotLifecycle` 设备生命周期编排、
  **连接意外断开后的指数退避重连与重绑定**、i18n 消息键（中/英）。
- **协议模块**：`protocol-tcp`（透传）、`protocol-modbus`（TCP/RTU，含 TLS fail-fast）、
  `protocol-mqtt`（MQTT 3.1.1）、`protocol-opcua`（含**安全策略与用户名密码认证**、
  浏览、原生订阅）。
- **集成测试体系**（`ypbin-iot-integration-tests`，不发布）：完整 Spring Boot 上下文 + 嵌入式 broker
  的端到端场景（自动装配 → 生命周期绑定 → 建链 → 订阅 → 中间件推送 → 出口），
  由 `-Pit`（failsafe）执行、CI 有独立作业。**该体系上线即抓到「MQTT 二进制负载被静默损坏」这一真实缺陷。**
- **OPC UA 信任模型与装配期诊断**：三条部署语义（自签证书=精确 pin / 自签 CA=信任该 CA 的一切 /
  CA 签发的叶子不可用）经源码级确认，并在装配期给出诊断（`SEC-15`/`SEC-16` 反向验证）。
- **工程门禁**：ArchUnit 架构约束（含规则有效性自检）、源码规范扫描、JaCoCo 指令 ≥0.80 与
  **分支 ≥0.64**、spotless、依赖版本收敛、模块发布边界门禁、配置元数据漂移门禁
  （含「模块集合不得静默缩小」）、**覆盖率快照由构建产物生成**（`tools/export-coverage.mjs`）、
  CI 覆盖率归档前的存在性断言。

### 已知限制

- **尚未发布**：`0.1.0-SNAPSHOT` 只在本地/CI 构建，Maven Central 上无此坐标。
- **OPC UA 用户名密码认证**：已在 `Basic256Sha256 + SignAndEncrypt` 上完成端到端验证
  （`SEC-E2E-02`），并有「错误口令必须被拒」的反向用例（`SEC-E2E-03`）守着他。
- **OPC UA**：非 None 安全策略下，信任目录放「CA 签发的叶子证书」**不可用**
  （Milo 只把自签证书当信任锚），需放自签叶子或整个 CA；两个可选校验
  （`HOSTNAME` / `APPLICATION_URI`）的比对值来自服务端自述，**对主动 MITM 无防护**。
- **MQTT**：基线为 MQTT 3.1.1（MQTT 5 专属配置不支持）；不声明 `READ` 能力。
  负载需按 `payload-format` 配置解码：`text`（默认，非法 UTF-8 产出 BAD 而非损坏字符串）、
  `number`、`binary`（原样交付 `byte[]`）。
- **Modbus RTU**：无真实硬件验证（仅协议层与串口配置校验）。
- **指标**：宿主提供 `MeterRegistry`（例如引入 `spring-boot-starter-actuator`）时
  自动装配 **Micrometer 指标桥**（读写计数按结果分维度、读写耗时计时器、订阅点位数、
  按消息键分类的错误数、瞬时值 gauge）；没有 `MeterRegistry` 时回退到无操作实现并打 INFO。
