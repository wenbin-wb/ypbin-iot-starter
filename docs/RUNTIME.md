# ypbin-iot-starter · 运行时与运维设计

> 本文是 [总体设计](./DESIGN.md) 的补充，覆盖**运行时语义与运维流程**：
> 设备影子、时钟同步、配置热更新、国际化、版本兼容、限流熔断、协议模拟器、优雅停机、测试策略。
>
> 本文与 DESIGN 的分工：DESIGN 回答「框架长什么样」，本文回答「它跑起来之后怎么表现、怎么运维」。
>
> 返回 [总体设计](./DESIGN.md) ｜ [SPI 契约](./SPI.md) ｜ [协议选型与路线图](./PROTOCOLS.md)

---

## 1. 设备影子（Device Shadow）

### 1.1 定位与边界

**设备影子不在本仓实现**——它属于宿主平台（`ypbin-admin` / 接入平台）的存储职责，
依据是 DESIGN §1.3「本仓只做协议对接」。

但本仓必须**为影子提供明确的数据出口与语义约定**，否则宿主各写一版、语义各不相同。
本节给出的是**宿主实现规范**，不是框架代码。

| 归属 | 内容 |
|---|---|
| **本仓提供** | `DataBatch`（含 `PointValue` 的 `quality` 与 `timestamp`）、`DeviceEvent`、`iot_egress_*` 指标 |
| **宿主实现** | 影子的存储、更新、TTL、查询 |
| **共同约定** | §1.2 ~ §1.5 的语义规则 |

### 1.2 更新策略：默认异步

```
协议线程 ──► DataEgress 有界队列 ──► 微批 ──► DataSink.write(batch)
                                                    │
                                        ┌───────────┴───────────┐
                                        ▼                       ▼
                                  时序库批量写入            影子更新（Redis HSET）
                                  （历史回溯）              （最新值查询）
```

**默认异步**，理由：

| 方案 | 延迟 | 风险 | 结论 |
|---|---|---|---|
| 同步更新影子（在 `emit` 路径上等 Redis 返回） | 低 | **把 Redis 的延迟与抖动直接引入采集路径**；Redis 抖动即导致 egress 队列堆积 | ❌ 不用 |
| **异步更新（egress 消费者侧）** | 影子滞后 = 队列深度 ÷ 消费速率 | 可控、可观测 | ✅ **默认** |

**代价必须可见**：异步意味着影子必然滞后。因此必须暴露 **`iot_shadow_lag_ms`**（当前时间 − 影子中最新的
`timestamp`），并在超过阈值（默认 5s）时告警。**"影子滞后"是设计属性而非故障，但不可观测的滞后是故障。**

### 1.3 一致性：按 `deviceId` 哈希到固定节点

集群场景下，若多个接入节点都写同一设备的影子，会产生**并发覆盖**（后写的赢，但"后"由时钟决定，
而多节点时钟不完全一致）。

**规则**：

1. 影子更新**必须按 `deviceId` 哈希路由到固定节点**（与 DESIGN §5.6 S1 的设备归属分片同源）；
2. 该设备的会话在哪个节点，影子就由哪个节点更新——**归属天然一致，不需要额外协调**；
3. 节点故障接管时，影子更新随设备会话一起迁移，接管完成后可能出现**短暂的旧值反超**，
   由 `timestamp` 比较解决（**只接受更新的 `timestamp`**，拒绝乱序到达的旧值）。

> **为什么用 `timestamp` 而不是"最后写入赢"**：接管期间新旧节点的写入可能交错，
> "最后写入赢"会让一个 3 秒前的旧值覆盖刚采到的新值。**按源时间戳单调递增**才是正确语义。

### 1.4 TTL 与设备删除清理

| 场景 | 处理 |
|---|---|
| 设备长期离线 | 影子**保留**（离线设备的最后值仍有查询价值），但 `quality` 应为 `STALE` 或 `NOT_CONNECTED` |
| 设备被删除 | `DeviceRegistry` 的变更监听中**必须处理删除事件**，同步删除影子 Hash，否则 Redis 会无限增长 |
| 兜底 | 影子 Hash 设置 **TTL 兜底**（默认 30 天，可配）。TTL 只在写入时刷新，因此长期离线设备的影子会自动过期 |

> ⚠️ **Redis 的第一约束是 key 数量**：`HSET shadow:{deviceId} ...` 的形式是
> **一个设备一个 key**（千万设备 = 千万 key），这已经在 Redis 的可承受范围边缘；
> **绝不能退化成一个点位一个 key**（千万设备 × 百点位 = 十亿 key，Redis 直接崩）。
> 这条与 DESIGN §5.7 硬规则 3 是同一条。

### 1.5 影子与命令下发的关系

| 概念 | 归属 | 说明 |
|---|---|---|
| **实际值（reported）** | 影子 | **只反映设备实际状态**，由采集数据更新 |
| **期望值（desired）** | **宿主业务层** | 用户/规则下发的目标状态，**不写入本仓的影子** |

**下行命令流程（推荐）**：

```
用户下发命令 ──► 宿主记录 desired（业务库）
                      │
                      ▼
              经框架 write() 下发到设备
                      │
                      ▼
              设备执行后上报新值 ──► 影子 updated（reported）
                      │
                      ▼
              宿主比对 reported 与 desired 是否一致
                ├─ 一致 → 命令完成
                └─ 超时不一致 → 告警/重试（由宿主策略决定）
```

> **为什么不"先写影子再下发"**：那会让影子变成"期望状态"，失去"设备真实状态"的语义。
> 一旦命令下发失败或设备执行失败，影子就长期处于谎言状态，而所有依赖影子的告警与展示都会失真。
> **影子只有一个职责：忠实反映设备实际状态。**

---

## 2. 时钟同步与时间戳

### 2.1 时间类型的两条边界（详见 DESIGN §1.4.2 A1）

| 层 | 类型 | 语义 |
|---|---|---|
| 协议栈 / `PointValue` / `DataBatch` | **`Instant`** | UTC 绝对时刻，跨时区无损 |
| 宿主实体 / API 契约 | **`LocalDateTime`**（GMT+8） | 遵循母仓序列化规范 |

### 2.2 设备时间 vs 服务器时间

工业设备的时间戳**不可信**：可能是断电后未同步的时钟、可能是本地时间却标了 UTC、可能直接不提供时间戳。

**规则（写死）**：

1. **`PointValue.timestamp` 的含义是「源时间戳」**——设备提供就填，不提供则由适配器用
   `AdapterContext.clock().instant()` 填充；
2. **`DataBatch.producedAt` 恒为服务器接收时间**（框架填充），**永远可信**；
3. 两者都要保留，**不允许用其中一个覆盖另一个**——时序库写入时，用哪个由宿主决定
   （建议：`timestamp` 为业务时间，`producedAt` 为入库时间，两者都存）；
4. 若设备时间与服务器时间偏差超过阈值（默认 5 分钟），产生 **`QUALITY_DEGRADED` 事件**并
   在 `PointValue.qualityReason` 中标注，但**不丢弃数据**（丢掉的是客户的资产）。

### 2.3 时钟漂移的硬性影响（不是省钱的地方）

两个协议**会因时钟偏差直接失败**，且失败信息通常不指向时间，是现场最难排查的一类问题：

| 协议 | 机制 | 容差 |
|---|---|---|
| **OPC UA** | 安全通道的证书验证校验时间戳 | 偏差 > **15 分钟**直接拒绝连接 |
| **ONVIF** | `WS-UsernameToken` 的 `Created` 参与密码摘要 | 偏差过大认证失败（部分设备容差仅 **5 分钟**） |
| **SIP（GB28181）** | `Via`/`Date` 头与 digest 认证 | 偏差过大会被 403 |

**因此**：

1. **生产环境必须部署 NTP**（chrony / systemd-timesyncd），且**必须在部署清单中显式列出**（DESIGN §5.10 已含）；
2. 容器部署时注意 **`CLOCK_REALTIME` 来自宿主机**，不要用 `libfaketime` 之类的调试手段；
3. `ProbeResult` 增加 **`clockSkewMillis`** 诊断项：探测时顺带报告与设备的时钟偏差，
   让"连不上"在诊断阶段就能定位到时钟问题（这是本仓对现场排查的实质贡献）。

---

## 3. 配置热更新

设备增删改是**运行期高频操作**，必须保证不产生「新旧会话双活」这类脏状态。

### 3.1 原子性：设备级排他锁

```
变更事件(DeviceChange) ──► SessionManager
                              │
                              ├─ 1. 获取该 deviceId 的排他锁（按 deviceId 分段，锁粒度=单设备）
                              ├─ 2. 校验新配置（DeviceRegistry.validate）
                              │       └─ 失败 → 拒绝变更，返回原因，原会话不受影响
                              ├─ 3. 关闭旧会话，等待其资源释放完成（有超时）
                              ├─ 4. 用新配置建立新会话
                              └─ 5. 释放锁
```

**为什么必须"先关后建"而不是"先建后关"**：

- 「先建后关」会在切换窗口内让**同一设备出现两条会话**（双活），此时：
  - 采集数据重复上报；
  - 下行命令可能走错会话；
  - 对上位机的连接数配额被双倍占用。
- 「先关后建」的代价是**一次短暂的采集空窗**（毫秒级），远优于双活的语义混乱。

**锁粒度**：按 `deviceId` 分段（如 `striped lock`），**不能全局锁**——10 万设备下全局锁会让
一次单设备变更阻塞全部设备。

### 3.2 变更前校验：`DeviceRegistry.validate`

```java
public interface DeviceRegistry {
    /** 返回当前应接入的全部设备。 */
    List<DeviceSpec> loadAll();

    /**
     * 校验设备配置是否可接入。
     *
     * <p>返回校验结果而非抛异常：校验失败是**正常的业务结果**（用户填错了配置），
     * 不是程序错误。宿主据此向用户展示具体原因。</p>
     *
     * @param device 待校验的设备规格
     * @return 校验结果，含是否通过与逐项原因
     */
    default ValidationResult validate(DeviceSpec device) {
        return ValidationResult.ok();
    }

    /** 注册变更监听，宿主在设备增删改时通知框架做增量上下线。 */
    void addChangeListener(Consumer<DeviceChange> listener);
}
```

**校验项（框架侧通用校验 + 协议侧扩展校验）**：

| 层级 | 校验内容 |
|---|---|
| 框架通用 | `deviceId` 非空且唯一；协议 code 已注册；`connectionId` 指向的连接规格存在；`pollInterval` 合法 |
| 协议特有 | 地址语法可解析；封装类型与端点匹配（Modbus RTU 不能连 TCP 端点）；从站地址在合法范围 |
| 连通性（可选） | 是否先 `probe` 一次再上线（由 `ypbin.iot.devices.probe-before-bind` 控制，默认 `false` 以加快批量导入） |

### 3.3 变更的幂等性

变更事件可能重复投递（消息总线 at-least-once）。因此 `DeviceChange` 必须携带**版本号**：

```java
public record DeviceChange(
        ChangeType type,        // ADD / UPDATE / REMOVE
        DeviceSpec device,
        long revision) { }      // 单调递增的配置版本号
```

- 框架记录每个设备的**最后已应用 revision**，收到 `revision <= lastApplied` 的事件**直接丢弃**（幂等）；
- 这同时解决了「乱序到达」问题——旧事件不会覆盖新配置。

### 3.4 配置回滚

- **不建议在框架内做配置版本快照与回滚**。理由：配置的**权威存储**在宿主（admin 的业务库），
  框架只持有内存副本。在框架内再存一份历史，会产生"两个真相源"。
- **框架提供的能力**：① 变更失败时**保持原配置继续运行**（拒绝变更，不进入降级状态）；
  ② 通过 `/actuator/iot` 暴露**当前生效的配置快照**，宿主据此判断是否需要回滚。
- **回滚由宿主执行**：宿主把旧版本的配置重新推送一次即可（走同一条变更通道）。

---

## 4. 国际化（i18n）

### 4.1 复用母仓机制，不自造（DESIGN §1.4.2 A6）

| 层 | 职责 |
|---|---|
| `iot-core` | **只定义消息键常量** `IotMessageKeys`（纯 `String` 常量，零依赖）；异常携带 `messageKey` + `args`，**不携带已格式化的中文消息** |
| 各协议模块 | 自带 `messages_zh_CN.properties` / `messages_en_US.properties` |
| `-spring-boot-starter` | ① 通过 `IotDefaultsEnvironmentPostProcessor` 把 `ypbin.iot.messages` **追加**到 `spring.messages.basename`；② 复用母仓 `cn.ypbin.starter.i18n.core.I18nUtil.message(code, args)` 解析 |

### 4.2 消息键命名规范

```
iot.<protocol>.<category>.<detail>

iot.common.connection.timeout            连接超时
iot.common.address.parse-failed          地址解析失败
iot.common.capability.unsupported        能力不支持
iot.modbus.address.invalid-basis         Modbus 地址基准配置非法
iot.modbus.exception.illegal-data-value  Modbus 异常码 0x03
iot.opcua.security.certificate-rejected  OPC UA 证书被拒
iot.s7.config.put-get-disabled           S7-1200/1500 未开启 PUT/GET
```

**规则**：

1. 键名全部小写 + 连字符，**不含空格与中文**；
2. `category` 从固定集合取（`connection` / `address` / `config` / `protocol` / `security` / `capability`），
   防止键名自由发挥；
3. 参数用 `{}` 占位（与母仓 `I18nUtil` 一致）：`iot.modbus.exception.code=Modbus 异常码 0x{0}`。

### 4.3 门禁

- **源码规范测试**：禁止在 `iot-core` 与协议模块的实现包中出现**面向用户的中文/英文消息字面量**
  （日志可以，异常消息不可以）——异常必须用消息键。白名单：Javadoc、日志模板、常量名。
- **资源包完整性测试**：`messages_zh_CN` 与 `messages_en_US` 的**键集合必须完全一致**，
  缺键即构建失败（缺英文翻译是海外交付最常见的低级问题）。
- **键存在性测试**：`IotMessageKeys` 中的每个常量**必须**在资源包中能找到对应条目，
  防止代码引用了不存在的键导致运行期输出 `???key???`。

### 4.4 语言的确定

语言的解析复用母仓 `ParamHeaderLocaleResolver`（`ypbin.i18n.param-name` 默认 `lang`，
`header-name` 默认 `Accept-Language`）。

**接入层日志的语言边界**：设备侧日志（协议报文、诊断）**不随请求语言变化**——
它们是运维视角而非用户视角，统一按**部署配置的语言**输出（默认中文），
避免多语言环境下日志检索困难。

---

## 5. 协议模块版本兼容矩阵

### 5.1 问题

21 个协议模块**独立发版**（这是 DESIGN ADR-01 的必然结果）。宿主若不用 BOM、而是逐个手写版本，
可能组合出「协议模块 A:1.2 依赖 `iot-core:1.2`，协议模块 B:1.0 依赖 `iot-core:1.0`」这类组合，
而 Maven 只会**静默取其中一个版本**，运行期表现为难以定位的行为异常。

### 5.2 三道防线

| # | 防线 | 机制 |
|---|---|---|
| **1** | **BOM 是唯一推荐引入方式** | `ypbin-iot-bom` 锁定整套版本；文档与脚手架**只给 BOM 的用法**，不提供逐个写版本的示例 |
| **2** | **构建期收敛校验** | `maven-enforcer-plugin` 的 `dependencyConvergence` + `requireUpperBoundDeps`：版本分歧**直接构建失败**（含 Netty、SLF4J 等同族库） |
| **3** | **运行期区间校验（fail-fast）** | `ProtocolDescriptor` 声明所支持的核心版本区间，启动时校验并**拒绝启动** |

### 5.3 版本区间的表达

```java
/**
 * 协议描述符（补充字段）。
 *
 * @param minimumRuntimeVersion 本适配器要求的最低 iot-runtime 版本（含）
 * @param maximumRuntimeVersion 本适配器支持的最高 iot-runtime 版本（不含）
 */
public record ProtocolDescriptor(
        ProtocolCode code,
        String name,
        String vendor,
        String stackVersion,
        String transport,
        Set<ProtocolCapability> capabilities,
        Set<Class<? extends ProtocolExtension>> extensions,
        String minimumRuntimeVersion,
        String maximumRuntimeVersion,
        Map<String, String> attributes) { }
```

**启动校验逻辑**：

```
AdapterRegistry 初始化
  └─ 对每个 ProtocolAdapter：
      ├─ 取 descriptor().minimumRuntimeVersion / maximumRuntimeVersion
      ├─ 与当前 iot-runtime 版本比对（SemanticVersion 区间判断，闭开区间）
      └─ 不在区间内 → 抛异常终止启动，错误信息包含：
           协议 code、适配器类名、声明区间、实际版本、升级/降级建议
```

**校验失败必须是启动失败而不是 warn**（沿用 ADR-09：fail-fast 而非静默降级）：
版本不兼容的运行期行为不可预测，让它启动起来只会把故障推到更难排查的位置。

### 5.4 兼容矩阵的维护

| 事项 | 做法 |
|---|---|
| 矩阵位置 | `ypbin-iot-bom` 的 `README.md`（人可读）+ `tools/compat-matrix.json`（机器可读） |
| 生成方式 | **构建期自动生成**，不手工维护——沿用母仓「配置参考由构建产物生成」的经验 |
| 内容 | 每个 iot 版本的模块列表 + 各模块版本 + 依赖的 core/runtime 版本区间 + 支持的 JDK 版本 |
| 门禁 | CI 校验生成结果与仓库中的快照一致，**漂移即失败**（母仓 `--check` 模式） |
| 破坏性变更 | 核心 SPI 变更时 `iot-core` 主版本递增，并在矩阵中标注受影响模块 |

---

## 6. 限流与熔断

DESIGN §5.5 给出了**背压**机制（被动承压）；本节给出**主动限流与熔断**（防止故障扩散）。

### 6.1 四级限流

| 级别 | 限什么 | 默认策略 | 超限动作 |
|---|---|---|---|
| **设备级** | 单设备每秒产生的点数 | 由 `pollInterval` × 点位数自然约束；额外上限 `ypbin.iot.limit.per-device-points-per-second`（默认 0 = 不限） | **降频**（拉长该设备的采集周期），并计数 + `QUALITY_DEGRADED` 事件 |
| **协议级** | 单协议的链路数 / 在途请求数 | `ypbin.iot.protocol.<code>.max-connections`、`max-pending-requests` | **拒绝新建链**（抛 `ConnectionException`），计数 |
| **实例级** | 总链路数 | `ypbin.iot.connection.max-connections`（默认 100000） | **拒绝**，计数并告警 |
| **建链速率** | 每秒新建链数 | `ypbin.iot.connection.connect-rate-limit`（默认 500）+ 抖动 | **排队**（令牌桶），不拒绝 |

**为什么设备级选"降频"而不是"丢弃"**：丢弃会让数据出现空洞且不可恢复；
降频只是降低时间分辨率，数据仍在。**除非**宿主明确选择丢弃（`overflow-policy`）。

**指标**：`iot_limit_rejected_total{level, protocol}` —— **被限流的请求数必须可观测**，
否则限流会退化成静默丢数据（违反 ADR-09）。

### 6.2 熔断状态机

```
                 失败率 > 阈值
    CLOSED ──────────────────────► OPEN
      ▲                              │ 冷却期(默认 30s)
      │                              ▼
      └──── 成功 ──────────── HALF_OPEN
              连续 N 次成功          │ 探测请求
                                     └─ 失败 → 回到 OPEN（冷却期翻倍，上限 10min）
```

| 状态 | 行为 |
|---|---|
| **CLOSED** | 正常采集 |
| **OPEN** | **停止该设备的采集**（不再发请求），产生 `DEVICE_OFFLINE` 事件；链路保留（避免重连风暴） |
| **HALF_OPEN** | 以**最低代价请求**探测（优先用 `ping()` 而非完整采集） |

**触发条件**（可配）：滑动窗口（默认 60s）内失败率 > 50% 且样本数 ≥ 10。

### 6.3 恢复必须渐进（防止恢复风暴）

熔断恢复**绝不能一次性全量恢复**，否则设备侧刚恢复就被打挂，进入"熔断—恢复—再熔断"的循环。

```
冷却期结束 ──► HALF_OPEN
                └─ 恢复量按 5% → 20% → 50% → 100% 逐级放大
                   每级观察窗口（默认 30s）内失败率达标才进入下一级
                   任一级不达标 → 回退到上一级或直接 OPEN
```

**集群场景**：恢复速率还要叠加**节点间的抖动**——若 200 个节点同时恢复同一批设备，效果等同于没有渐进。

### 6.4 与背压的区别（别混用）

| 机制 | 方向 | 触发者 | 目的 |
|---|---|---|---|
| **背压**（DESIGN §5.5） | 下游 → 上游 | 消费者（egress 队列满） | 保护自己不被数据淹没 |
| **限流**（本节） | 上游主动 | 框架策略 | 保护设备与网络 |
| **熔断**（本节） | 上游主动 | 错误率 | 隔离故障设备，避免无效重试消耗资源 |

三者都**必须计入指标**，且都**不允许静默生效**。

---

## 7. 协议模拟器

> [`PROTOCOLS.md` §7.3](./PROTOCOLS.md#73-m0-骨架的验收标准可执行定义) 把模拟器列为 M0 交付物。没有它，10 万连接压测与故障注入都无从谈起——
> 而这两个能力恰恰是接入框架与业务系统最大的质量分水岭。

### 7.1 架构：统一平台 + 协议插件

**不采用「每协议一个模拟器容器」**（21 个镜像无法统一管理、无法统一注入故障），
采用**一个模拟器平台 + 协议插件**：

```
ypbin-iot-simulator/                      （不发布，仅测试用）
├── simulator-core/                       统一控制面
│   ├── SimulatedDeviceRegistry           模拟设备注册表（数量、点位、变化规律）
│   ├── FaultInjector                     故障注入引擎（统一策略）
│   ├── ScenarioEngine                    场景编排（启动风暴/网络抖动/时钟漂移）
│   └── control/                          REST + WebSocket 控制接口
├── simulator-plugin-modbus/              Modbus Slave 插件
├── simulator-plugin-opcua/               OPC UA Server 插件（可直接用 Milo Server）
├── simulator-plugin-mqtt/                MQTT Broker 插件（可用 Moquette / HiveMQ CE）
├── simulator-plugin-tcp/                 通用 TCP 回显/脚本插件
└── simulator-plugin-bacnet/              BACnet 虚拟设备（M2 决策后再做）
```

**选型提示**：能复用现成实现就复用，不重复造：
- OPC UA Server → **直接用 Milo 的 `milo-sdk-server`**（与我们客户端同源，行为最一致）；
- MQTT Broker → 用现成 Broker 容器（Testcontainers 起），把"模拟设备"做成客户端批量连接；
- Modbus Slave → **用 `digitalpetri modbus` 的 `modbus-slave-tcp`**（同源）。

### 7.2 模拟设备的数据模型

```yaml
simulation:
  scenarios:
    - name: modbus-gateway-200-slaves
      protocol: modbus-tcp
      listen: 0.0.0.0:502
      devices:
        count: 200                       # 200 个从站，共享一条链路
        unit-id-range: [1, 200]
        points:
          count: 100
          type: HOLDING_REGISTER
          value-pattern: RANDOM_WALK   # 支持 CONSTANT / RANDOM / RANDOM_WALK / SINE / COUNTER / SCRIPT
          update-interval: 1s
      faults: []                         # 见 §7.3
```

**取值模式**必须覆盖真实场景：
- `CONSTANT`：验证"无变化时不应产生上报"（结合 deadband）；
- `RANDOM_WALK`：最接近真实传感器，用于吞吐压测；
- `SINE`：验证趋势与死区过滤；
- `COUNTER`：验证**点位顺序与不丢点**（最容易发现 egress 乱序/丢失）；
- `SCRIPT`：用脚本精确构造边界值（溢出、负值、非法浮点）。

### 7.3 故障注入（模拟器的核心价值）

| 故障类型 | 注入方式 | 验证目标 |
|---|---|---|
| **半包 / 粘包** | 手动分片发送，可控每个分片的大小与间隔 | 编解码器的帧边界处理 |
| **乱序 / 重复响应** | 响应顺序打乱、同一请求发两次响应 | 事务匹配（transaction id）是否正确 |
| **超时** | 收到请求后不响应 | 请求超时、重试、熔断 |
| **断连** | 主动断开 TCP / 停止 UDP 响应 | 重连退避、抖动、会话状态机 |
| **慢响应** | 延迟 N 毫秒响应 | 自适应降频、在途请求上限 |
| **恶意报文** | 超长长度字段、超大嵌套、非法枚举值、截断报文 | **不崩、不 OOM**，诊断信息明确 |
| **错误码** | 按比例返回协议异常码（如 Modbus 0x02/0x03） | 部分失败的点位级标记（不是整批失败） |
| **时钟漂移** | 伪造设备侧时间戳 | `QUALITY_DEGRADED` 事件与时钟偏差诊断 |
| **连接风暴** | 同时启动 N 个设备 | 建链限速与抖动的有效性 |

### 7.4 控制接口

**REST API**（运行期动态调整，无需重启）：

| 端点 | 用途 |
|---|---|
| `POST /sim/devices` | 动态增加模拟设备（**用于验证启动风暴防护**） |
| `DELETE /sim/devices/{id}` | 动态移除 |
| `PATCH /sim/devices/{id}/points` | 修改点位值与变化规律 |
| `POST /sim/faults` | 注入故障（类型 + 目标 + 持续时间） |
| `GET /sim/stats` | 模拟器侧统计：收到的请求数、响应数、当前连接数 |

**WebSocket `/sim/logs`**：实时日志流，用于长稳测试期间观察异常。

**关键**：`GET /sim/stats` 提供**服务端视角的连接数**——这是 DESIGN §4.2 CR-1（单飞建链测试）
断言的依据：**框架声称建了 1 条连接，必须由模拟器侧数出来的连接数来证实。**

### 7.5 M0 必须产出的基准数据

| 指标 | 含义 | 为什么重要 |
|---|---|---|
| 单容器可模拟设备数 | 1 个 2C2G 容器能起多少模拟设备 | 决定压测环境规模（10 万设备需要多少模拟器容器） |
| 单容器可承载连接数 | 受 FD 与内存限制 | 同上 |
| 单容器每秒可处理请求数 | 决定压测天花板 | **若模拟器先于被测系统成为瓶颈，压测结论无效** |
| 资源占用曲线 | CPU/内存/FD 随设备数的变化 | 容量规划 |

> **反直觉但重要**：模拟器本身常常是压测的第一个瓶颈。10 万连接的压测如果模拟器先撑不住，
> 得到的"框架指标"实际上是模拟器的指标。**基准数据必须先于压测产出。**

### 7.6 与 Testcontainers 集成

- 协议模块的集成测试用 `ypbin-starter-test` 基座（**外部实例优先 → Testcontainers 回退 → 条件跳过**，
  沿用母仓机制）；
- 模拟器提供官方镜像 `ypbin/iot-simulator:${revision}`，测试中直接起容器；
- **单测不依赖模拟器**（用进程内的轻量 stub），模拟器用于集成测试与压测。

---

## 8. 优雅停机

### 8.1 停机阶段与超时

| 阶段 | 动作 | 超时（默认） | 超时后 |
|---|---|---|---|
| **S1** | 从注册中心**摘除本节点**（Nacos 下线），停止接收新设备分片 | 5s | 记录 warn，继续 |
| **S2** | 停止所有 `ProtocolAdapter` 的**新设备绑定** | 立即 | — |
| **S3** | 等待在途请求完成（读写/订阅注册） | 10s | 记录未完成请求数，**继续**（不无限等） |
| **S4** | **flush egress**：把队列中剩余数据交给 `DataSink` | 15s | 记录剩余条数并**计入丢弃指标**（不静默丢） |
| **S5** | 关闭全部链路（`ProtocolConnection.close()`） | 10s | 强制关闭 socket |
| **S6** | 关闭时间轮、执行器、适配器级资源 | 5s | 记录并继续 |
| **S7** | 释放 native 资源（串口 / CAN / FFmpeg） | 5s | 记录并继续 |

**总停机预算 ≤ 55s**，与容器编排的 `terminationGracePeriodSeconds`（建议设 60s）配套。

### 8.2 关键顺序规则

1. **S1 必须在 S2 之前**：先摘除注册再停止服务，否则负载均衡会把新请求打到正在关闭的节点；
2. **S4 必须在 S5 之前**：先 flush 数据再断链路——反了会丢数据（链路关了但队列里还有数据）；
3. **S3 不能无限等**：工业设备的请求可能挂很久，**必须有超时**，否则停机卡死；
4. **S5 之后不再产生新数据**：此时 egress 必须是空的（S4 已 flush），否则说明 S4 有遗漏。

### 8.3 信号处理

| 信号 | 行为 |
|---|---|
| **SIGTERM** | 走完整 7 阶段优雅停机（容器编排与 systemd 的默认停止信号） |
| **SIGINT** | 同上（本地 Ctrl+C 调试时行为一致，避免"本地能停、线上停不掉"） |
| **SIGKILL** | 无法捕获，必然丢失队列中数据——**因此 `terminationGracePeriodSeconds` 必须大于停机预算** |

**容器环境的两个坑**：

1. **信号必须能到达 JVM 进程**：若用 `sh -c "java ..."` 启动，SIGTERM 会发给 shell 而非 JVM，
   导致 JVM 被 SIGKILL 强杀。**必须用 `exec java ...` 或直接 `ENTRYPOINT ["java", ...]`**；
2. **`preStop` hook**：K8s 中建议加 `preStop` 睡眠 5s，等待负载均衡摘除生效（与 S1 配合）。

### 8.4 向宿主通知

本仓通过 Spring `ApplicationEvent` 通知宿主（宿主可监听做自己的收尾，如关闭 Sink、提交偏移量）：

| 事件 | 触发时机 | 宿主可做 |
|---|---|---|
| `IotShutdownStartingEvent` | S1 之前 | 停止向框架投递新设备变更 |
| `IotShutdownDrainingEvent` | S3 开始时 | 停止向 `DataSink` 写入以外的旁路逻辑 |
| `IotShutdownCompletedEvent` | S7 之后 | 关闭自己的资源（连接池、Kafka producer） |

> **注意**：宿主的 `DataSink` 关闭由**宿主自己**负责（`DataSink` 是宿主实现的 Bean，
> Spring 会调用它的 `close()`）。框架只在 S4 之前保证不再投递新数据。

### 8.5 部分停机（单协议模块启停）

**支持**，通过 Actuator 端点触发，用于：
- 某协议库出问题需要**单独下线**而不影响其它协议；
- 灰度验证新协议模块；
- 现场排查时临时停掉噪音协议。

| 端点 | 行为 |
|---|---|
| `POST /actuator/iot/protocols/{code}/stop` | 停止该协议：拒绝新绑定 → 关闭该协议全部会话 → flush 其数据 |
| `POST /actuator/iot/protocols/{code}/start` | 启动该协议：按启动风暴防护的限速**分批重建**其设备会话 |

**安全约束**：该端点**必须**受权限保护（默认关闭，`ypbin.iot.actuator.write-enabled=false`），
理由：它能中断生产数据采集，属于高危操作。开启后必须配合母仓的 `@Log` 审计（admin 侧暴露时）。

---

## 9. 测试策略

### 9.1 测试金字塔（本仓的具体形态）

```
                    ┌──────────────────────────┐
                    │  10 万连接压测 / 72h 长稳 │   发布门禁（大版本必过）
                    ├──────────────────────────┤
                    │   集成测试（模拟器容器）   │   每协议模块必备
                    ├──────────────────────────┤
                    │  TCK 一致性测试套件        │   每适配器必备（继承基类即跑）
                    ├──────────────────────────┤
                    │  装配测试（ContextRunner） │   每模块 4 场景
                    ├──────────────────────────┤
                    │   单元测试（JUnit5+AssertJ）│   地址解析/编解码/状态机
                    └──────────────────────────┘
```

### 9.2 各类测试的职责与边界

| 类型 | 依赖 | 覆盖 | 不覆盖 |
|---|---|---|---|
| **单元测试** | 无（纯内存） | 地址解析、帧编解码、状态机迁移、重连退避计算 | 网络、并发 |
| **TCK** | 进程内 stub | **SPI 语义一致性**（见 §9.3） | 真实协议行为 |
| **装配测试** | `ApplicationContextRunner` | 条件装配、Bean 覆盖、注册登记 | 运行期行为 |
| **集成测试** | 模拟器容器 / Testcontainers | 真实协议往返、故障注入 | 规模 |
| **性能基准** | JMH | 单协议 P50/P99/P999 与吞吐 | 规模下的资源行为 |
| **规模压测** | 多容器模拟器 | 10 万连接、200 万点/秒、长稳 | 单协议细节 |

### 9.3 TCK（协议一致性测试套件）的用例集

**这是本仓质量体系的核心**。每个协议模块继承抽象基类即自动跑全部用例：

| 用例集 | 内容 |
|---|---|
| **生命周期** | 建链 → 绑定 → 读写 → 解绑 → 关链，每步状态正确；重复 `close()` **幂等** |
| **能力一致性** | 未声明能力的方法调用**抛 `UnsupportedCapabilityException`**，不返回空结果 |
| **异常语义** | 建链失败抛 `ConnectionException`；超时抛 `ProtocolTimeoutException`；**不得抛裸 `RuntimeException`** |
| **部分失败** | 批量读中部分点位失败 → 逐点位标记 `BAD` + 原因，**Stage 正常完成**；仅链路不可用才异常完成 |
| **订阅语义** | 订阅 → 收到数据 → 取消订阅 → 不再收到；`deliveredCount()` 单调递增 |
| **`unwrap` 机制** | 声明支持的扩展能 `unwrap` 成功，未声明的返回空 `Optional` |
| **并发安全**（新增） | 见 DESIGN §4.2 的 CR-1 ~ CR-4（单飞建链、竞态释放、空闲回收、失败传播） |
| **线程模型** | 所有 `CompletionStage` **不得在调用线程上同步完成阻塞操作**（用断言检测回调线程） |
| **资源释放** | 关闭后无残留线程、无残留 FD、无残留 native 句柄（用 JMX + `/proc/self/fd` 计数断言） |

### 9.4 模糊测试

- 工具：`com.code-intelligence:jazzer-junit:0.30.0`（JUnit 5 集成，无需额外基础设施）；
- 目标：**所有协议报文解析器**（帧解码、地址解析、SDP 解析、XML/SOAP 解析）；
- 断言：无未捕获异常、无 OOM、无无限循环（Jazzer 内建超时检测）；
- **运行位置**：不进常规 CI（耗时长），走**夜间任务 + 发布前长跑**。

### 9.5 性能基准

- 工具：`org.openjdk.jmh:jmh-core:1.37`；
- **必须与基线对比**：每次发布记录各协议基准值，**回归超阈值即人工复核**
  （不自动失败，因为性能抖动有环境噪声；但必须有对比记录）；
- 关键指标：单次读延迟 P50/P99/P999、批量读吞吐（点/秒）、订阅推送延迟、地址解析开销。

### 9.6 兼容性测试矩阵

| 维度 | 内容 |
|---|---|
| **厂商/型号** | S7-1200 vs S7-1500（协议不同！）；不同厂商 Modbus 网关（地址基准不同）；不同品牌 IPC 的 ONVIF（Profile 差异） |
| **协议版本** | Modbus 四种封装；GB28181 2016 vs 2022；BACnet 6.x vs 7.x（若启用） |
| **运行环境** | Linux x86_64 / aarch64；JDK 21（基线）；容器 vs 裸机（FD 与内核参数差异） |
| **依赖版本** | Netty 4.1.x 各补丁版；协议库版本升级 |

**要求**：无法自动化真机测试的项（如各厂商 PLC），必须**留存实测记录**
（型号、固件版本、测试日期、结论），并在模块 README 中列出"已验证型号清单"。

### 9.7 升级测试

从旧版本升级到新版本必须验证：

| 检查项 | 内容 |
|---|---|
| **配置兼容性** | 旧配置在新版本下仍能启动（新增配置项有默认值）；**废弃配置项必须 warn 而非静默忽略** |
| **SPI 兼容性** | 宿主的自定义 `ProtocolAdapter` 无需改动即可编译（核心 SPI 变更必须走主版本） |
| **序列化兼容** | `DataBatch` 结构变更对宿主 `DataSink` 的影响（若是二进制序列化，需版本字段） |
| **行为兼容性** | 相同配置下的连接数、采集周期、默认超时是否与旧版一致（默认值变更必须显式公告） |

---

## 10. 把本文变成门禁

本文的各项约定，若不落到门禁就只是文档。对应关系：

| 本文章节 | 落到的门禁 |
|---|---|
| §1 设备影子 | 宿主侧实现规范；框架提供 `iot_shadow_lag_ms` 指标 |
| §2 时钟同步 | `ProbeResult.clockSkewMillis` 诊断项；部署清单含 NTP |
| §3 配置热更新 | 变更幂等（revision）单测；设备级排他锁并发测试 |
| §4 i18n | 资源包键集合一致性测试；消息键存在性测试；禁硬编码消息的源码规范测试 |
| §5 版本兼容 | enforcer `dependencyConvergence`；启动期区间校验；矩阵漂移检查 |
| §6 限流熔断 | 限流/熔断指标断言；渐进恢复的行为测试 |
| §7 协议模拟器 | M0 交付物；集成测试基座；压测环境 |
| §8 优雅停机 | 停机顺序测试（S1→S2、S4→S5）；数据不丢的断言；单协议启停的权限测试 |
| §9 测试策略 | CI 各阶段配置；发布门禁清单 |
