# ypbin-iot-starter · 总体设计

> 多协议物联网接入框架 Starter —— 独立仓库、复用母仓 `ypbin-starter` 的 parent/BOM 与构建门禁，
> 面向 **JDK 21 + Spring Boot 4.1 + Netty 4.1**，目标单机 10 万连接、集群千万级。
>
> **职责边界**：本仓**只做协议对接**——连接、读写、订阅、数据出口。
> 设备模型、点位模板、规则引擎、时序落库属于宿主平台（`ypbin-admin` / 独立接入平台）职责。
>
> 返回 [SPI 契约](./SPI.md) ｜ [协议选型与路线图](./PROTOCOLS.md)

---

## 1. 定位与设计原则

### 1.1 一句话定位

> 让「接一种新协议」的边际成本，从「改框架」降为「加一个 Maven 模块 + 实现一个接口」。

### 1.2 六条设计原则

| # | 原则 | 违反后的后果 |
|---|---|---|
| **G1** | **契约层零依赖** —— `iot-core` 只依赖 JDK 21 + SLF4J + JSpecify | 核心被 Spring/Netty 绑架，无法在边缘网关、非 Spring 场景复用 |
| **G2** | **协议即模块** —— 一种协议一个 Maven 模块，模块间零依赖 | 引入 Modbus 却被迫拖进 OPC UA 的 30MB 依赖树 |
| **G3** | **引入即装配** —— 每个协议模块自带 `AutoConfiguration.imports` | 宿主必须手写 `@Import` 清单，新增协议要改宿主代码 |
| **G4** | **异步为纲，同步为目** —— 对外契约 `CompletionStage`，阻塞库走基类桥接 | EventLoop 被阻塞 → 单点卡顿拖垮同 EventLoop 上的数千连接 |
| **G5** | **数据出口单一** —— 所有采集数据经 `DataEgress` 微批出站 | 每点位一次回调 → 百万点/秒场景下框架自身成为瓶颈 |
| **G6** | **失败要可见** —— 不支持的能力抛异常，部分失败逐项标记，丢弃必须计数 | 静默降级让宿主以为设备正常，故障潜伏到业务侧才爆发 |

### 1.3 明确不做的事（防止范围蔓延）

- ❌ 设备台账、点位模板、产品物模型 —— 归宿主
- ❌ 规则引擎、告警、联动 —— 归宿主
- ❌ 数据落库、时序库写入 —— 归宿主（本仓给出选型与容量建议，见 §5.7）
- ❌ 前端可视化、组态 —— 归宿主
- ❌ 设备固件 OTA、远程配置下发 —— 归宿主（可用本仓 `write` 能力自行实现）
- ❌ **OPC DA / PROFINET / LonWorks** —— 需专用硬件或 Windows 桥接环境，**个人项目无法验证**（D2），方案留档但不在路线图内
- ❌ **EtherCAT / IO-Link / DALI / M-Bus / Zigbee / Z-Wave / JT/T 1078** —— 需专用硬件，同理不做（D5）

### 1.4 与 `ypbin-starter` 规范的显式对齐

本仓是独立仓库，但**不是独立规范体系**。母仓 `AGENTS.md` / `starter-dev` 的铁律默认全部继承，
下面只列出**需要特别处理**的条目——包括**刻意偏离**的地方，偏离必须给出理由，否则视为违规。

#### 1.4.1 直接继承（无争议，逐条遵守）

| 规范 | 本仓落实 |
|---|---|
| 类级 Javadoc 必带 `@author wenbin` + `@since <日期>`，禁 `@date`、禁版本号 | 全部源码 |
| 顶部 Apache-2.0 license 头 | 全部源码（header 署名见 1.4.2） |
| 禁内联全限定类名，一律顶部 `import` | 全部源码；ArchUnit 源码级规则强制 |
| 禁静默吞异常；日志必须传完整堆栈；禁 `printStackTrace()` | 全部源码；ArchUnit 强制 |
| 远程调用必须显式 `connectTimeout` / `readTimeout` | 所有协议客户端与 HTTP 客户端 |
| 集合返回禁 `null`；字面量统一 `List.of()/Map.of()/Set.of()`，禁 `Collections.emptyXxx` | SPI 全部返回集合的方法；防回归规则进 ArchUnit |
| 枚举必须显式 `code` + `desc`，**禁 `ordinal()`** | 协议能力/质量/状态/关闭原因等全部枚举 |
| 杜绝魔法值 | 协议 code、超时默认值、能力标识等全部常量化 |
| 实体 `equals/hashCode` 仅基于主键 | 宿主侧实体（本仓不定义实体，但集成文档需提示） |
| `@Data` 仅允许用于 `@ConfigurationProperties`；业务 DTO 用 `@Getter @Setter` | `IotProperties` / `ModbusProperties` 等允许 `@Data`；SPI 值对象用 `record`（天然不可变，无 `@Data` 问题） |
| 禁参考项目品牌词 | 全仓代码、注释、文档 |
| `@AutoConfiguration` + `AutoConfiguration.imports`，禁 `spring.factories` | 全部装配类（见 §3） |
| `@Bean` 必带 `@ConditionalOnMissingBean` | 全部装配类（ArchUnit 强制） |
| 配置项统一 `ypbin.*` 前缀 + `@ConfigurationProperties` + `PREFIX` 常量 | 统一 `ypbin.iot.*` |
| 构建门禁：spotless `check` 绑定 `process-test-classes` | 继承母仓 parent 的插件配置 |

#### 1.4.2 必须显式处理的六处（含 3 处刻意偏离）

| # | 事项 | 母仓规范 | 本仓处理 | 性质 |
|---|---|---|---|---|
| **A1** | **时间类型** | 时间字段统一 `LocalDateTime`，序列化锁 `yyyy-MM-dd HH:mm:ss`（GMT+8），禁散落裸 `Date` | **`iot-core` 的协议时序模型用 `Instant`**；`LocalDateTime` 用于宿主侧实体/API | ⚠️ **刻意偏离（有边界）** |
| **A2** | **JSON 库** | 已全面切换 **Jackson 3**（`tools.jackson`），Jackson 2 运行时已移除 | 本仓如需 JSON，**只能用 Jackson 3**；禁止引入 Jackson 2 | 继承（强制） |
| **A3** | **`BaseEnum<V>`** | 通用枚举契约在 `ypbin-starter-core`，需实现 `getValue()` / `getDescription()` | **iot 枚举不实现 `BaseEnum`** | ⚠️ **刻意偏离（技术必然）** |
| **A4** | **静态门面 Utils** | `volatile` + 双重检查 + `SpringUtils` 委派 | **仅 `-spring-boot-starter` 模块可提供**；`core` / `runtime` / `transport` **零静态门面** | ⚠️ **刻意偏离（分层要求）** |
| **A5** | **装配日志前缀** | 统一 `[ypbin-starter]` | 统一 **`[ypbin-iot]`** | ✅ 合理偏离（独立仓） |
| **A6** | **i18n** | 已有 `ypbin-starter-i18n`：`I18nUtil.message(code, args)` + 容器 `MessageSource` | **复用母仓，不自造 `MessageResolver`** | 继承（复用） |

**A1 时间类型——为什么必须用 `Instant`（最重要的一条）**

母仓「统一 `LocalDateTime`」这条规范的对象是**业务实体与 API 契约**：
DB 列 = 实体字段 = DTO 字段 = 前端 JSON，全程同名同格式，避免时区歧义。这条在业务域完全正确。

但**协议时序数据不是业务时间字段**：

| 维度 | 业务时间字段 | 协议点位时序 |
|---|---|---|
| 语义 | 本地业务时刻（"这单是 3 点下的"） | **UTC 绝对时刻**（"传感器在 T 采样"） |
| 精度 | 秒 | 毫秒 ~ 微秒（工业采样间隔可到 ms 级） |
| 时区 | 固定 GMT+8 且业务无歧义 | 跨时区设备接入，**必须无损** |
| 消费方 | 前端展示、报表 | 时序库、跨设备时序对齐、窗口计算 |

用 `LocalDateTime` 承载协议时间戳会在跨时区接入时**永久丢失时区信息**，且 10 万设备下无法做全局时序对齐。

**边界与转换规则（写死，不留解释空间）**：

```
协议栈 / DeviceSession / PointValue / DataBatch   →  Instant（UTC，含毫秒/微秒精度）
        │
        └─ 宿主 DataSink / 实体 / API 契约         →  LocalDateTime（GMT+8，yyyy-MM-dd HH:mm:ss）
```

- `AdapterContext.clock()` 返回 `Clock`，适配器取时**必须**经它（便于测试注入与时钟漂移校正）；
- `PointValue.timestamp` 为 `Instant`，表示**源时间戳**（设备侧提供时）；
- 宿主落库/出 API 时按母仓规范转 `LocalDateTime`，**转换由宿主负责**，本仓不代劳；
- `-spring-boot-starter` 提供一个 `IotTimeUtils.toLocalDateTime(Instant)` 便利方法，避免宿主各写一遍。

**A3 `BaseEnum` 偏离的技术必然性**：`BaseEnum` 位于 `ypbin-starter-core`，而该模块依赖
`spring-boot-autoconfigure`。`iot-core` 要实现它就必须引入 Spring，直接违反 §1.2 G1 与 A1（DESIGN §3.4）。
**这不是偷懒，是分层约束的必然结果**。若宿主需要统一处理，由 `-spring-boot-starter` 提供
`BaseEnum` 适配器（把 iot 枚举的 `code`/`desc` 包装成 `BaseEnum`），但**不下沉到 core**。

**A4 静态门面偏离的理由**：母仓铁律 4 的范式依赖 `SpringUtils`（Spring 容器）。
`core`/`runtime`/`transport` 三层刻意零 Spring，**不具备**提供静态门面的前提。
本仓的静态门面（如 `IotUtils`）**只在 `-spring-boot-starter` 中提供**，且严格按母仓范式实现：
`volatile` 字段 + 双重检查锁 + 委派给 `IotAdminService` 接口，Javadoc 注明「Spring 组件应直接注入 `IotAdminService`」。

**A6 i18n——复用而非自造**

母仓 `ypbin-starter-i18n` 已解决「容器 `MessageSource` → 静态 `I18nUtil`」的桥接。
本仓**不新增 `MessageResolver` SPI**（那会造出第二套 i18n 机制），而是：

| 层 | 做法 |
|---|---|
| `iot-core` | 只定义**消息键常量**（`IotMessageKeys`，纯 String 常量，零依赖）与异常中的 `messageKey` 字段 |
| 各协议模块 | 自带 `messages_zh_CN.properties` / `messages_en_US.properties`（键为 `iot.<protocol>.<error>`） |
| `-spring-boot-starter` | 通过 `IotDefaultsEnvironmentPostProcessor` 把本仓 basename **追加**到 `spring.messages.basename`；异常 → 消息由 `I18nUtil.message(key, args)` 解析 |

> **`EnvironmentPostProcessor` 的 Boot 4.1 陷阱（母仓已踩过，直接继承经验）**：
> 接口包已从 `org.springframework.boot.env.*` 迁到 `org.springframework.boot.*`，但
> **`META-INF/spring.factories` 的注册键也必须同步改**为 `org.springframework.boot.EnvironmentPostProcessor`。
> 只改接口不改键**不会报错**，但默认值会**静默失效**。本仓必须同时加两道门禁：
> 源码级注册校验 + `SpringFactoriesLoader` 运行时可见性断言。

#### 1.4.3 `R<T>` 与 HTTP 200 的适用边界

母仓铁律 9 要求「凡产出 HTTP 响应的组件一律走 `R<T>`，HTTP 恒 200」。本仓的边界：

| 组件 | 是否走 `R<T>` | 理由 |
|---|---|---|
| `/actuator/iot` 诊断端点 | ❌ **不走** | Actuator 有自身的端点契约（健康/指标/条件报告），套 `R` 会破坏 Spring Boot 生态工具（Prometheus 抓取、健康探针、`conditions` 报告）的可解析性 |
| 协议模块暴露的任何 HTTP 接口 | ✅ 走 | 属于业务接口 |
| 宿主的 IoT 管理接口（admin 侧） | ✅ 走 | 由宿主实现，遵循 admin 规范：`@SaCheckPermission` + `@Idempotent` + `@Log`，`R.ok()` / `R.fail(code, msg)` |
| `iot-core` 异常体系 | 不涉及 HTTP | `IotException` 体系自带（core 零 Spring，无法依赖 `BusinessException`）；由 `-spring-boot-starter` 或宿主在 Web 层映射为 `R.fail(...)` + HTTP 200 |

#### 1.4.4 集成到 `ypbin-admin` 的规范对接（M2 之后）

admin 侧接入本仓时，除实现 `DeviceRegistry` / `DataSink` 外，必须遵循 admin 现行规范：

| 事项 | 要求 |
|---|---|
| Controller | **禁 `extends BaseController`**（该类已删除）；用 `R.ok()` + `UserContext` + `WebRequestUtils` |
| 写操作 | 必带 `@SaCheckPermission` + `@Idempotent` + `@Log` 三件套 |
| 实体/DTO | 禁 `@Data`，用 `@Getter @Setter`；`Long` 转字符串输出由基类处理 |
| 状态字段 | 设备/通道的启停状态复用母仓 `ypbin-starter-data` 的 `EntityStatus`（ENABLED/DISABLED），不新造枚举 |
| 时间字段 | 设备台账等业务字段用 `LocalDateTime`（与 A1 的 `Instant` 边界严格区分） |
| 事务 | `@Transactional` 必须显式 `rollbackFor = Exception.class` |
| 前端契约 | 字段名与 DB 列名严格同名，**禁 `@Mapping` 转译** |
| 模块归属 | IoT 能力以 `ypbin-iot-*` 依赖进入 admin，**不把协议代码写进 admin 业务模块** |

---

## 2. 模块划分

### 2.1 仓库坐标与版本治理

```xml
<groupId>cn.ypbin</groupId>
<artifactId>ypbin-iot-starter</artifactId>   <!-- 聚合 POM -->
<version>${revision}</version>               <!-- flatten-maven-plugin 展开 -->
```

父 POM 直接继承母仓的依赖管理层，**复用**其 Boot 4.1 BOM、spotless 门禁、JaCoCo、failsafe 与发布 profile：

```xml
<parent>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-starter-dependencies</artifactId>
    <version>2.2.3</version>          <!-- 跟随母仓发布版；升级只改这一处 -->
    <relativePath/>                   <!-- 空值：从仓库解析而非本地目录 -->
</parent>
```

> ⚠️ **必须覆盖 license header**：母仓 `ypbin-starter-dependencies` 的 spotless 配置内联了
> `Copyright (c) 2024-present ypbin-starter authors.`，子仓需在自己的根 POM 的
> `pluginManagement` 里覆盖为 `ypbin-iot-starter authors`，否则所有文件会被强制署上母仓名字。
>
> ⚠️ **不要**让 `ypbin-iot-*` 反向进入母仓的 `<modules>`：独立仓的目的是独立发版节奏
> （协议库版本更新远比基础库频繁），共用 parent 已足够保证风格一致。

### 2.2 模块清单（29 个）

#### 契约与底座（4）

| 模块 | 职责 | 关键依赖 | 发布 |
|---|---|---|---|
| `ypbin-iot-core` | SPI 契约、值对象、异常体系。**零 Spring、零 Netty** | JDK 21 · slf4j-api · jspecify | ✅ |
| `ypbin-iot-transport` | Netty 4.1 传输底座：TCP/UDP/串口/WebSocket 的连接工厂、编解码基座、空闲检测、流量整形 | core · netty-* ·（epoll native 可选） | ✅ |
| `ypbin-iot-runtime` | 运行时内核：适配器注册中心、连接注册中心、会话管理、重连退避、分层时间轮调度、微批出口 | core（**不含 Netty / Spring**） | ✅ |
| `ypbin-iot-spring-boot-starter` | Spring 装配层：条件装配、`ypbin.iot.*` 配置、Actuator 端点、健康指示器、生命周期编排；指标默认无操作（**Micrometer 桥未实现**，宿主自行提供 `MetricsRecorder`）| runtime · transport · spring-boot-autoconfigure | ✅ |

#### 协议模块（21）

| 模块 | 覆盖协议 | 额外依赖 |
|---|---|---|
| `ypbin-iot-protocol-modbus` | Modbus TCP / RTU / RTU over TCP / **ASCII（自研帧层，M3）** | `digitalpetri modbus` + transport |
| `ypbin-iot-protocol-opcua` | OPC UA（Client，可选 Server） | Eclipse Milo |
| `ypbin-iot-protocol-mqtt` | MQTT 3.1.1 / 5.0（设备接入向） | Paho v5 |
| `ypbin-iot-protocol-tcp` | 通用 TCP 透传 | transport |
| `ypbin-iot-protocol-udp` | 通用 UDP 透传 | transport |
| `ypbin-iot-protocol-http` | HTTP 轮询采集 / Webhook 接收 | transport 或 JDK HttpClient |
| `ypbin-iot-protocol-websocket` | WebSocket 客户端 | transport |
| `ypbin-iot-protocol-s7` | 西门子 S7（S7comm / S7comm-plus） | PLC4X 或自研 |
| `ypbin-iot-protocol-bacnet` | BACnet/IP | BACnet 库或自研 |
| `ypbin-iot-protocol-snmp` | SNMP v1 / v2c / v3 | SNMP4J |
| `ypbin-iot-protocol-coap` | CoAP / CoAPS | Eclipse Californium |
| `ypbin-iot-protocol-ethernetip` | EtherNet/IP（CIP） | PLC4X 或自研 |
| `ypbin-iot-protocol-knx` | KNXnet/IP | Calimero |
| `ypbin-iot-protocol-opcda` | OPC DA（Windows COM 桥） | 本地桥接进程 |
| `ypbin-iot-protocol-can` | SocketCAN / CANopen / J1939 | SocketCAN JNI |
| `ypbin-iot-protocol-hart` | HART-IP | 自研（协议简单） |
| `ypbin-iot-protocol-gb28181` | 视频国标（信令 + 流式订阅） | SIP 栈 |
| `ypbin-iot-protocol-onvif` | ONVIF（设备发现 / PTZ / 媒体） | SOAP 客户端 |
| `ypbin-iot-protocol-gbt26875` | 消防远程监控（GB/T 26875） | 自研（协议简单） |
| `ypbin-iot-protocol-profinet` | PROFINET（可行性受限） | 见 §8 风险 R3 |
| `ypbin-iot-protocol-lonworks` | LonWorks（可行性受限） | 见 §8 风险 R3 |

#### 治理与测试（3，不发布）

| 模块 | 职责 |
|---|---|
| `ypbin-iot-dependencies` | IoT 专属三方库版本集中管理 + 全模块 parent |
| `ypbin-iot-architecture-tests` | ArchUnit 架构约束（zero-Spring 校验、分层、协议互不依赖、命名规范） |
| `ypbin-iot-test` | **协议一致性测试套件（TCK）** + 协议模拟器（Modbus Slave / OPC UA Server Mock / MQTT Broker Mock） |

#### BOM（1）

| 模块 | 职责 |
|---|---|
| `ypbin-iot-bom` | 对外统一 BOM：宿主 `import` 一次即可管理全部 `ypbin-iot-*` 版本 |

> **为什么把 `dependencies` 与 `bom` 分开**：沿用母仓模式。`dependencies` 面向内部
> （含三方协议库版本），`bom` 面向外部（只暴露 `cn.ypbin` 自身坐标），
> 避免协议库版本号泄漏到宿主 BOM 引发冲突。

### 2.3 依赖关系图

```
┌───────────────────────────────────────────────────────────────────────────────┐
│                      cn.ypbin:ypbin-starter-dependencies:2.x                   │
│            （母仓父 POM：Boot 4.1 BOM + spotless + Jacoco + 发布 profile）        │
└───────────────────────────────────┬───────────────────────────────────────────┘
                                    │ parent
┌───────────────────────────────────▼───────────────────────────────────────────┐
│                    cn.ypbin:ypbin-iot-starter（聚合 POM, ${revision}）          │
│                                                                                │
│  ┌──────────────────────┐   ┌──────────────────────┐   ┌────────────────────┐  │
│  │ iot-dependencies     │   │      iot-bom         │   │ iot-architecture-  │  │
│  │ 三方版本管理 + parent │   │  对外 BOM（不发父POM）│   │ tests（不发布）     │  │
│  └──────────┬───────────┘   └──────────────────────┘   └────────────────────┘  │
│             │ parent                                                           │
│  ═══════════▼══════════════════════════════════════════════════════════════     │
│                                                                                │
│   ┌──────────────────────────┐                                                 │
│   │      iot-core            │  ← 契约层：SPI / 值对象 / 异常                    │
│   │  零 Spring · 零 Netty     │     仅 JDK 21 + slf4j-api + jspecify            │
│   └───────┬──────────┬───────┘                                                 │
│           │          │                                                         │
│           │          └──────────────────┐                                      │
│           │                             │                                      │
│  ┌────────▼───────────┐      ┌──────────▼──────────┐                           │
│  │  iot-runtime       │      │   iot-transport     │                           │
│  │  注册/会话/调度/出口 │      │  Netty 4.1 传输底座  │                           │
│  │  零 Spring · 零 Netty│      │  TCP/UDP/串口/WS     │                           │
│  └────────┬───────────┘      └──────────┬──────────┘                           │
│           │                             │                                      │
│           └──────────┬──────────────────┘                                      │
│                      │                                                         │
│           ┌──────────▼───────────────────────┐                                 │
│           │  iot-spring-boot-starter         │  ← Spring 装配层（唯一含 Spring 的底座）│
│           │  条件装配 / Actuator（指标默认无操作）│                                 │
│           └──────────┬───────────────────────┘                                 │
│                      │ compile（传递引入）                                       │
│  ┌───────────────────▼────────────────────────────────────────────────────┐    │
│  │                    协议模块 × 21（互相之间零依赖）                        │    │
│  │                                                                        │    │
│  │  modbus  opcua  mqtt  tcp  udp  http  websocket  s7  bacnet  snmp      │    │
│  │  coap  ethernetip  knx  opcda  can  hart  gb28181  onvif              │    │
│  │  gbt26875  profinet  lonworks                                          │    │
│  │                                                                        │    │
│  │  每个模块结构：                                                          │    │
│  │    protocol/        ← 适配器实现（纯 Java，零 Spring 注解）               │    │
│  │    autoconfigure/   ← @AutoConfiguration + Properties                   │    │
│  │    META-INF/spring/org.springframework.boot.autoconfigure.               │    │
│  │                     AutoConfiguration.imports                           │    │
│  └────────────────────────────────────────────────────────────────────────┘    │
└───────────────────────────────────────────────────────────────────────────────┘
```

**宿主视角的引入方式**：

```xml
<!-- 只需要 Modbus + OPC UA，其余协议库一个字节都不进 classpath -->
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-modbus</artifactId>
</dependency>
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-opcua</artifactId>
</dependency>
```

### 2.4 协议模块标准骨架

每个协议模块的结构**完全一致**，`tools/ypbin-iot-init.mjs` 可一键生成（沿用母仓的模块生成器思路）：

```
ypbin-iot-protocol-modbus/
├── pom.xml
└── src/
    ├── main/
    │   ├── java/cn/ypbin/iot/protocol/modbus/
    │   │   ├── ModbusAdapter.java              ← 实现 ProtocolAdapter（纯 Java，零 Spring 注解）
    │   │   ├── ModbusConnection.java           ← 实现 ProtocolConnection
    │   │   ├── ModbusDeviceSession.java        ← 实现 DeviceSession（按 unitId 绑定从站）
    │   │   ├── ModbusAddress.java              ← 地址解析（4x0001 / 0x / 1x / coil 等）
    │   │   ├── ModbusAddressCodec.java         ← 地址编解码 + BoundedAddressCache 使用
    │   │   ├── ModbusRegisterType.java         ← 枚举（含 code + desc）
    │   │   └── ModbusFunctionCode.java         ← 枚举（含 code + desc）
    │   │   └── autoconfigure/                  ← ★ 唯一允许出现 Spring 类型的地方
    │   │       ├── ModbusAutoConfiguration.java
    │   │       └── ModbusProperties.java       ← @ConfigurationProperties("ypbin.iot.protocol.modbus")
    │   └── resources/
    │       ├── META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
    │       └── META-INF/spring-configuration-metadata.json   ← 构建期生成，IDE 可补全
    └── test/java/cn/ypbin/iot/protocol/modbus/
        ├── ModbusAddressCodecTest.java         ← 单元测试（无 Spring）
        ├── ModbusAdapterTckTest.java           ← ★ 继承 TCK 抽象基类，一行不写即跑全部一致性用例
        └── ModbusAutoConfigurationTest.java    ← ApplicationContextRunner 装配测试
```

对应的 `pom.xml` 关键片段：

```xml
<parent>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-dependencies</artifactId>
    <version>${revision}</version>
</parent>

<artifactId>ypbin-iot-protocol-modbus</artifactId>

<dependencies>
    <!-- 传递引入：宿主只加本模块即拿到 runtime + transport + Spring 装配骨架 -->
    <dependency>
        <groupId>cn.ypbin</groupId>
        <artifactId>ypbin-iot-spring-boot-starter</artifactId>
    </dependency>

    <!-- 协议库：必须 compile 传递（A13） -->
    <dependency>
        <groupId>com.digitalpetri.modbus</groupId>
        <artifactId>modbus-tcp</artifactId>
    </dependency>

    <!-- Spring 仅编译期需要，不向下游传递 -->
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-autoconfigure</artifactId>
        <optional>true</optional>
    </dependency>

    <!-- TCK -->
    <dependency>
        <groupId>cn.ypbin</groupId>
        <artifactId>ypbin-iot-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

### 2.5 为什么协议模块自带 `autoconfigure`（而不是集中装配）

这是本设计最关键的取舍，两条路线对比：

| 方案 | A：协议模块纯 SPI，starter 集中装配 | **B：协议模块自带 autoconfigure（选定）** |
|---|---|---|
| 协议模块是否需要 Spring | ❌ 完全不需要 | ⚠️ 仅 `autoconfigure` 子包需要（`spring-boot-autoconfigure` 声明为 `optional`） |
| **可单独引入** | ❌ 做不到。starter 必须 compile 依赖全部 21 个协议模块，引入即全家桶 | ✅ 引入一个协议模块即自动装配，其余协议零依赖 |
| 新增协议是否改宿主/核心 | ❌ 要改 starter 的 `@Import` 清单 | ✅ 加一个模块即可，零侵入 |
| 与 Boot 3+/4 机制一致 | ❌ 退回 Boot 2 集中注册风格 | ✅ 每个 jar 自带 `AutoConfiguration.imports` |
| 协议实现类零 Spring | ✅ | ✅（`protocol/` 包禁 Spring 注解，由 ArchUnit 强制） |

**结论**：选 B。要求「`iot-core` 零 Spring」的本意是**契约层可脱离 Spring 复用**，
而不是「协议实现不能是 Spring starter」——后者会直接摧毁「引入即装配」这条更重要的能力。
用构建期约束（ArchUnit）保证 `protocol/` 子包不出现任何 Spring 类型，两边目标同时达成。

---

## 3. 自动配置的条件装配策略

### 3.1 装配分层与开关层级

配置键统一 `ypbin.iot.*` 前缀，三级开关自上而下收敛：

```yaml
ypbin:
  iot:
    enabled: true                    # ① 总开关：关掉后整个接入框架不装配（含 transport/runtime）
    scheduler:
      carrier-threads: 0             # 虚拟线程载体线程数，0 = 用 JDK 默认（= CPU 核数）
      tick-duration: 100ms           # 时间轮 tick 精度
    egress:
      batch-size: 1000               # 微批最大点数
      batch-interval: 200ms          # 微批最大等待
      queue-capacity: 100000         # 有界队列容量
      overflow-policy: DROP_OLDEST   # DROP_OLDEST / DROP_NEWEST / BLOCK
    connection:
      max-connections: 100000        # 单节点链路上限（超出拒绝并计数）
      idle-timeout: 5m               # 无设备绑定的链路空闲回收
      connect-rate-limit: 500        # 每秒最大建链数（防启动风暴）
      connect-rate-jitter: 0.3       # 建链抖动系数
    protocol:
      modbus:                        # ② 协议开关：以协议 code 为键
        enabled: true
        connect-timeout: 10s
        request-timeout: 5s
        max-connections: 20000       # ③ 协议级配额（隔离爆炸半径）
        extended:                    # 协议特有参数
          default-unit-id: 1
          max-registers-per-read: 125
      opcua:
        enabled: true
        extended:
          security-policy: Basic256Sha256
          endpoint-discovery: true
```

**装配条件矩阵**：

| 层级 | 条件注解 | 行为 |
|---|---|---|
| 总开关 | `@ConditionalOnProperty(prefix="ypbin.iot", name="enabled", matchIfMissing=true)` | 关闭则整套不装配 |
| 库存在性 | `@ConditionalOnClass(<协议库核心类>.class)` | 协议库被 exclude 时静默不装配（**这是按需生效，不是静默降级**） |
| 协议开关 | `@ConditionalOnProperty(prefix="ypbin.iot.protocol.<code>", name="enabled", matchIfMissing=true)`；布尔开关优先用 Boot 4.1 的 `@ConditionalOnBooleanProperty` | 单协议启停 |
| 实现选择 | `@ConditionalOnProperty(prefix="ypbin.iot.protocol.<code>", name="impl", havingValue="...", matchIfMissing=true)` | 同协议多实现（如两套 Modbus 库）二选一 |
| 可覆盖 | 每个 `@Bean` 带 `@ConditionalOnMissingBean` | 宿主自定义同类型 Bean 时框架让位 |
| 顺序 | `@AutoConfiguration(after = IotAutoConfiguration.class)` | 协议装配在核心 Bean 就绪之后 |

### 3.2 核心装配类（`ypbin-iot-spring-boot-starter`）

```java
/**
 * IoT 接入核心自动配置：装配 runtime / transport 的框架级 Bean。
 *
 * @author wenbin
 * @since 2026-09-13
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = IotProperties.PREFIX, name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(IotProperties.class)
public class IotAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(IotAutoConfiguration.class);

    /** 分层时间轮调度器：承载全部周期采集与心跳任务。 */
    @Bean(destroyMethod = "shutdown")
    @ConditionalOnMissingBean
    public TaskScheduler iotTaskScheduler(IotProperties properties) { ... }

    /** 微批数据出口：有界队列 + 批量聚合 + 溢出计数。 */
    @Bean
    @ConditionalOnMissingBean
    public DataEgress iotDataEgress(IotProperties properties,
                                    ObjectProvider<DataSink> sinks,
                                    ObjectProvider<DeviceEventListener> listeners) {
        // 未注册任何 DataSink 时装配日志提示（不是错误：宿主可能只用命令下行）
    }

    /** 连接注册中心：单飞建链 + 引用计数 + 空闲回收。 */
    @Bean
    @ConditionalOnMissingBean
    public ConnectionRegistry iotConnectionRegistry(TaskScheduler scheduler, IotProperties properties) { ... }

    /** 适配器注册中心：收集全部 ProtocolAdapter Bean，做 code 唯一性与能力一致性校验。 */
    @Bean
    @ConditionalOnMissingBean
    public AdapterRegistry iotAdapterRegistry(ObjectProvider<ProtocolAdapter> adapters,
                                              TaskScheduler scheduler,
                                              IotProperties properties) {
        // fail-fast：code 重复 / descriptor 为空 / 能力声明与实现不符 → 抛异常终止启动
    }

    /** 接入生命周期编排：ApplicationReadyEvent 后按限速分批建连，关闭时优雅停机。 */
    @Bean
    @ConditionalOnMissingBean
    public IotLifecycle iotLifecycle(AdapterRegistry adapters, DeviceRegistry deviceRegistry,
                                     ConnectionRegistry connections, IotProperties properties) { ... }
}
```

### 3.3 协议模块装配类（以 Modbus 为例）

```java
/**
 * Modbus 协议自动配置。
 *
 * <p>装配条件：classpath 存在协议库 + 全局开关开启 + Modbus 协议开关开启。</p>
 *
 * @author wenbin
 * @since 2026-09-13
 */
@AutoConfiguration(after = IotAutoConfiguration.class)
@ConditionalOnClass(ModbusMaster.class)          // ← 指向协议库的类，不是本模块的类
@ConditionalOnProperty(prefix = "ypbin.iot.protocol.modbus", name = "enabled",
        havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(ModbusProperties.class)
public class ModbusAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ModbusAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public ProtocolAdapter modbusTcpAdapter(IotProperties iot, ModbusProperties modbus,
                                            ObjectProvider<NettyTransport> transport) {
        log.debug("[ypbin-iot] modbus adapter configured: transports=tcp,rtu.");
        return new ModbusAdapter(iot, modbus, transport.getIfAvailable());
    }
}
```

```
# ypbin-iot-protocol-modbus/src/main/resources/META-INF/spring/
#     org.springframework.boot.autoconfigure.AutoConfiguration.imports
cn.ypbin.iot.protocol.modbus.autoconfigure.ModbusAutoConfiguration
```

### 3.4 关键装配规则（十五条铁律，纳入 ArchUnit 门禁）

| # | 规则 | 理由 |
|---|---|---|
| A1 | `iot-core` 的 classpath 中不得出现 `org.springframework.*` | G1 契约层可脱离 Spring |
| A2 | `iot-core` / `iot-runtime` 不得依赖 `io.netty.*` | 协议不一定走 Netty（串口、native、Milo 自带栈） |
| A3 | 协议模块之间**零依赖**（不得 import 彼此的类） | G2 按需引入 |
| A4 | 协议模块的 `protocol/` 包不得 import Spring 类型 | 协议实现可脱容器单测 |
| A5 | 每个 `@AutoConfiguration` 类必须在 `AutoConfiguration.imports` 中登记 | 否则静默不生效 |
| A6 | 每个 `@Bean` 必须带 `@ConditionalOnMissingBean` | 宿主可覆盖 |
| A7 | `@ConditionalOnClass` 必须指向**协议库**的类，不得指向本模块的类 | 后者永远为真，条件形同虚设 |
| A8 | 每个协议 `@AutoConfiguration` 必须 `after = IotAutoConfiguration.class` | 核心 Bean 先就绪 |
| A9 | 禁止 `@ConditionalOnBean` 用于协议库存在性判断（顺序敏感易踩坑） | 用 `@ConditionalOnClass` |
| A10 | 协议 code 全局唯一，注册时 fail-fast | 后者覆盖前者会造成极其隐蔽的路由错乱 |
| A11 | 配置项必须落在 `ypbin.iot.*` 命名空间，禁止 `@Value` 散读 | 配置可发现、可文档化 |
| A12 | 禁止 `spring.factories` | Boot 3+ 已废弃 |
| A13 | 协议模块的协议库依赖必须 `compile` 传递（不得 `optional`） | 「引入模块即可用」的前提 |
| A14 | 每个协议模块必须有装配测试（`ApplicationContextRunner`） | 验证条件装配与覆盖行为 |
| A15 | 每个协议适配器必须通过 TCK 一致性测试套件 | 否则无法保证 SPI 语义一致 |
| A16 | 协议模块的 `@AutoConfiguration` 类**重命名/换包**时，必须同步维护 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.replacements` | 该类可能被宿主的 `before`/`after`/`excludes` 引用；只改 imports 会让宿主的排序与排除**静默失效** |
| A17 | 包名规范固定：装配类必须在 `cn.ypbin.iot.protocol.<code>.autoconfigure`，协议实现必须在 `cn.ypbin.iot.protocol.<code>`；**`<code>` 必须等于 `descriptor().code()`** | 让「协议 code ↔ 包名 ↔ 配置键 ↔ 模块名」四者一一对应，任何人看一处就能推断出另外三处 |
| A18 | 每个模块的 `README.md` 若引入 GPL/LGPL/MPL 类库，必须含许可边界声明 | 见 `PROTOCOLS.md` §3.3 的 KNX 模板 |

> **ArchUnit 规则必须做「有效性自检」——母仓的三条血泪经验，直接继承**
>
> 母仓的 `ypbin-starter-architecture-tests` 用「合成违规反向验证」发现过三条**规则本身写错**的情况。
> 本仓的 A1~A18 会大量复现同类陷阱，因此**每条规则都必须配一个合成的反向违规样本**，
> 证明规则真的能报错（而不是永远为真、悄悄放行）：
>
> | 陷阱 | 后果 | 应对 |
> |---|---|---|
> | `callMethod(Throwable.class, "printStackTrace")` | **全部漏判**——调用点的 owner 是子类（如 `RuntimeException`），不匹配 `Throwable` | 改用 `callMethod` 的通配形式或 `callCodeUnitWhere` 匹配方法名 |
> | `switch(enum)` | javac 会把它编译成 `ordinal()` 查表 → **禁 ordinal 的规则必然误报** | 规则须排除编译器合成代码，或在源码级而非字节码级校验 |
> | Lombok `@Data` | 是 **SOURCE 保留**，字节码不可见 → 字节码规则**永远不报** | 只能在**源码级**规则中校验 |
>
> 特别地，**A4（`protocol/` 包禁 Spring 类型）这条规则必须自检**：
> 在测试源码里写一个 `import org.springframework.context.annotation.Bean` 的类放进 `protocol/` 包，
> 断言规则报错——因为这条规则是「core 零 Spring」承诺的唯一执行者，写错了没人会发现。

> **依据**：A5/A16 的机制来自 Spring Boot 官方文档
> [Creating Your Own Auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html)——
> 自动配置**只能**通过 imports 文件加载，且**绝不能**成为组件扫描的目标；
> 需要排序时用 `@AutoConfiguration(before/after)`；替换类用 `AutoConfiguration.replacements` 声明。
> 本仓的 `RegistrationDiscoveryTest` 用 `ImportCandidates.load(AutoConfiguration.class, ...)`
> 做**运行时可见性**断言——源码扫描只能证明「键写对了」，加载一遍才能证明「Boot 真的找得到」。

### 3.5 设备实例的运行时装配

条件装配解决的是「**协议能力**是否上线」，设备实例的上下线是**运行时**行为，两者分离：

```java
/** 设备来源 SPI：宿主从这里提供设备清单（配置文件 / 数据库 / 注册中心）。 */
public interface DeviceRegistry {
    /** 返回当前应接入的全部设备；实现需处理分页或全量差异。 */
    List<DeviceSpec> loadAll();
    /** 注册变更监听，宿主在设备增删改时通知框架做增量上下线。 */
    void addChangeListener(Consumer<DeviceChange> listener);
}
```

框架自带三个实现（`@ConditionalOnMissingBean` 可覆盖）：

| 实现 | 场景 | 装配条件 |
|---|---|---|
| `NoopDeviceRegistry` | 宿主完全用 API 手动管理会话 | 默认 |
| `YamlDeviceRegistry` | 中小规模、配置即设备清单 | `ypbin.iot.devices.enabled=true` |
| `SpiDeviceRegistry` | 由宿主提供 `DeviceRegistry` Bean | 宿主自定义 |

**启动风暴防护**（10 万设备场景的必备能力）：

```
IotLifecycle.onReady()
  └─ 按 connect-rate-limit（默认 500/s）令牌桶分批建连
      └─ 每批附加 connect-rate-jitter（默认 ±30%）随机抖动
          └─ 建连失败进入指数退避队列，不阻塞其余设备
              └─ 全部设备的建连结果汇总为启动报告（成功/失败/跳过 + 失败原因 Top-N）
```

> **为什么必须做**：10 万设备若在 `ApplicationReadyEvent` 里一次性建连，
> 会在数秒内产生 10 万次 TCP 握手 + 10 万个虚拟线程 + 10 万次 DNS 解析，
> 结果是本地 FD/SYN 队列打满，同时可能冲击对端 PLC 的并发连接上限——
> 工业设备对此极其敏感，**打挂对端**是真实事故。限速与抖动是必需品，不是优化项。

### 3.6 装配可观测性

三条手段，解决「明明引了模块却不生效」这类最高频的支持问题：

1. **启动装配日志**：装配完成后打印一行矩阵
   `[ypbin-iot] adapters registered: modbus-tcp(READ,WRITE,SUBSCRIBE_POLLING), opcua(READ,WRITE,BROWSE) | protocols disabled: coap, knx`
2. **Actuator 端点** `/actuator/iot`：返回已注册适配器、能力矩阵、连接数、会话数、egress 队列深度与丢弃计数。
3. **`IotDiagnostics` 诊断端点**：对单个设备执行 `probe` 并返回完整诊断链（DNS → TCP → TLS → 协议握手 → 首读），
   这是现场排查的刚需——工业现场最耗时的工作就是回答「到底是网络不通还是协议配错了」。

---

## 4. 运行时内核设计（`iot-runtime`）

### 4.1 适配器注册中心 `AdapterRegistry`

```
register(ProtocolAdapter)
  ├─ 校验 descriptor() 非空、code 非空
  ├─ 校验 code 全局唯一        → 冲突：抛 IllegalStateException（fail-fast）
  ├─ 校验 capabilities 与实现一致（声明 READ 则 session 不得对 read 抛 Unsupported）
  ├─ 校验扩展接口声明一致（实现 BrowseExtension 则 capabilities 必须含 BROWSE）
  └─ 建索引 code → AdapterHandle{adapter, context, metrics, settings}
```

### 4.2 连接注册中心 `ConnectionRegistry`

工业现场的核心复用场景：**一台 Modbus 网关后面挂 200 个从站，只应有一条 TCP 链路**。

```
acquire(ConnectionSpec spec)
  ├─ key = spec.connectionId()（协议 + 端点 + 关键参数的稳定哈希）
  ├─ 已有活跃链路 → refCount++ ，直接返回          （复用）
  ├─ 建链中       → 挂到同一个 CompletableFuture   （单飞，避免并发重复建链）
  └─ 无           → 发起 open()，成功后 refCount=1
release(connectionId)
  ├─ refCount-- 
  └─ refCount==0 → 标记空闲，idle-timeout（默认 5min）后真正关闭
```

**为什么必须有单飞**：设备批量上线时，200 个从站的 `bind` 会并发触发同一条链路的
`open`，没有单飞就会建出 200 条 TCP 连接，把网关打挂。这类问题在压测前几乎不会暴露。

**`ConnectionRegistry` 是全仓最容易写出并发 bug 的组件**，因此它的行为由**四个专项测试**锁定，
且这些用例归属 TCK 的「并发安全」集合（所有协议适配器都要跑）：

| # | 测试 | 断言 |
|---|---|---|
| **CR-1** | **单飞建链**：200 个线程并发 `acquire()` 同一 `connectionId` | 底层 `open()` **只被调用 1 次**，只建立 **1 条** TCP 连接（用模拟服务端计数） |
| **CR-2** | **引用计数与竞态释放**：建链过程中最后一个引用释放 | 建链完成后**立即进入空闲回收**，不产生「已无引用但仍活跃」的泄漏连接 |
| **CR-3** | **空闲回收边界**：refCount 归零后到达 `idle-timeout` | 连接被关闭且 `whenClosed()` 以 `CLIENT_REQUEST` 完成；再次 `acquire()` 能正常重建 |
| **CR-4** | **失败传播**：建链失败时所有等待者 | 全部收到同一个 `ConnectionException`（**不是**"一个失败一个挂起"），且失败后计数归零、无残留状态 |

> **为什么 CR-1 要用真实连接计数而不是 mock**：单飞的经典 bug 是「第一次检查通过后、写入 map 前」
> 的窗口期产生了第二个 builder。这类 bug 用 mock 极难稳定复现，必须用真实 TCP 连接数做断言，
> 否则测试会在 CI 上时绿时红，最后被标注 `@Disabled` 而失去意义。

### 4.3 会话管理 `SessionManager`

- 会话状态机：`IDLE → CONNECTING → ONLINE ⇄ DEGRADED → RECONNECTING → CLOSED/FAILED`
- 每会话串行执行队列（工业协议多为请求-响应语义），在途请求上限由 `maxPendingRequests` 控制
- **重连退避**：`delay = min(initial × 2^n, max) × (1 ± jitter)`，抖动是必需的——
  集群重启时无抖动的指数退避会让所有设备在同一时刻重连（thundering herd）
- **熔断**：连续失败 N 次进入 `DEGRADED`，降频探测；探活成功回到 `ONLINE`
- **会话状态本地化**：会话状态**不跨节点共享**，需要全局视图时由宿主落库（见 §5.6）

### 4.4 采集调度 `PollingScheduler`

```
分层时间轮（tick 100ms，5 层，覆盖 100ms ~ 数小时）
  ├─ 每个 tick 只处理到期的 bucket，O(1) 摊销
  ├─ 设备按 pollInterval 哈希到 bucket，天然打散（避免同一毫秒惊群）
  ├─ 慢设备自适应降频：滑动窗口内的响应延迟超过阈值 → 临时拉长周期
  └─ 任务异常被捕获并记录，绝不中断时间轮
```

> **为什么不用 `ScheduledThreadPoolExecutor`**：10 万个周期任务会在堆里形成
> 10 万个 `ScheduledFuture` + 一个 10 万节点的优先队列，每次出入队 O(log n)，
> 且定时精度相互干扰。时间轮把调度开销降到与任务数无关的量级。

### 4.5 数据出口 `EgressRouter`

```
emit(PointValue)
  └─ 按 deviceId 哈希到 N 个有界环形缓冲（避免全局锁）
      └─ 满足 batch-size 或 batch-interval 到期 → 组装 DataBatch
          └─ 交给单线程（或少量）消费者线程池
              └─ 依次调用全部 DataSink（单 Sink 异常隔离，记录 + 计数）
```

**背压策略**（`ypbin.iot.egress.overflow-policy`）：

| 策略 | 行为 | 适用 |
|---|---|---|
| `DROP_OLDEST`（默认） | 丢最旧数据，保最新 | 监控类数据，最新值最重要 |
| `DROP_NEWEST` | 丢新数据，保完整性 | 计费/计量类数据 |
| `BLOCK` | 阻塞生产者 | ⚠️ 仅非 EventLoop 场景可用；在 EventLoop 上会死锁，框架会检测并拒绝该配置 |

**三种策略都累加 `iot_egress_dropped_total` 指标并输出限流日志**——丢弃可以，静默丢弃不行。

---

## 5. 千万级并发的关键技术点

> 目标拆解：**单机 10 万连接** 是工程优化问题；**集群千万级** 是架构分片问题。
> 两者对策完全不同，混在一起谈必然失焦。

### 5.1 容量测算模型（先算账，再优化）

| 项 | 单连接/单点开销 | 10 万连接合计 |
|---|---|---|
| Netty Channel + Pipeline | ~1.5 KB | 150 MB |
| ByteBuf 池摊销（读缓冲 1KB/连接） | ~1 KB | 100 MB（堆外） |
| 会话对象 + 状态机 | ~0.5 KB | 50 MB |
| 虚拟线程栈（懒分配，均值远低于上限） | ~0.5~1 KB | 50~100 MB |
| 采集任务与定时器 | ~0.2 KB | 20 MB |
| **合计** | **~4 KB/连接** | **~400 MB** |

**建议部署规格**：

| 场景 | 规格 | 连接数 | 说明 |
|---|---|---|---|
| 开发/小规模 | 2C4G | ≤ 5,000 | 默认参数即可 |
| 中等规模 | 4C8G | 20,000 ~ 50,000 | 需调 FD 上限与直接内存 |
| 大规模单节点 | 8C16G | 50,000 ~ 100,000 | 需分协议 EventLoopGroup + 精细调优 |
| 千万级 | 200+ 节点 × 5 万 | 1000 万 | **必须水平扩展**，见 §5.6 |

**数据吞吐测算**（决定 egress 设计）：10 万设备 × 100 点位 ÷ 5s 周期 = **200 万点/秒**。

- 逐点回调：200 万次/秒方法调用 → 框架自身即瓶颈 ❌
- 微批（1000 点/批）：**2000 批/秒** → 单节点可承受 ✅
- 序列化：JSON 约 150 字节/点 = 300 MB/s ❌ ；Protobuf 约 25 字节/点 = **50 MB/s** ✅

> 结论：**「微批 + 二进制」不是优化选项，而是 200 万点/秒能否成立的前提**。
> 若宿主的 Sink 只能吃 JSON，请在 Sink 内部自行异步转换，不要拉慢框架出口线程。

### 5.2 接入层（Netty）关键点

| # | 技术点 | 要点 |
|---|---|---|
| N1 | **主从 Reactor** | `bossGroup` 1~2 线程只做 accept；`workerGroup` = 2×CPU 核数处理 I/O |
| N2 | **Epoll 而非 NIO** | Linux 上用 `EpollEventLoopGroup` + `EpollServerSocketChannel`，减少空轮询与系统调用 |
| N3 | **`SO_REUSEPORT` 多监听** | 内核级负载均衡，消除 accept 单点；需 native epoll transport |
| N4 | **内核参数** | `somaxconn` / `tcp_max_syn_backlog` / `tcp_rmem` / `tcp_wmem` / `file-max` —— 具体取值见 §5.10 |
| N5 | **FD 上限** | 10 万连接至少 20 万 FD；systemd `LimitNOFILE` 与启动脚本 `ulimit -n` **必须双设**，取值见 §5.10 |
| N6 | **池化直接内存** | `PooledByteBufAllocator`（4.1 默认）+ 显式 `-XX:MaxDirectMemorySize`（建议 = 堆大小） |
| N7 | **写水位** | `WriteBufferWaterMark` 高低水位 + `Channel.isWritable()` 做背压，防 OOM |
| N8 | **`TCP_NODELAY=true`** | 工业报文小（几字节到几十字节），Nagle 算法会引入 40ms 级延迟 |
| N9 | **空闲检测** | `IdleStateHandler` 做协议级心跳；`ReadTimeoutHandler` 兜底 |
| N10 | **引用计数纪律** | `SimpleChannelInboundHandler` 自动释放；`ResourceLeakDetector` 在预发开 `PARANOID`，生产开 `SIMPLE` |
| N11 | **帧长上限** | `LengthFieldBasedFrameDecoder.maxFrameLength` 必须设上限，否则一个恶意长度字段即 OOM |
| N12 | **协议隔离** | 每协议独立 `EventLoopGroup`（bulkhead）：一个协议的编解码 bug 只影响自己 |
| N13 | **`autoRead=false`** | 高负载时手动 `read()` 做精确背压（默认 `autoRead=true` 会在处理慢时无限读） |
| N14 | **优雅停机** | `shutdownGracefully(quietPeriod, timeout)`；先停 accept，再等在途，最后关链路 |

### 5.3 虚拟线程关键点

| # | 技术点 | 要点 |
|---|---|---|
| V1 | **承载模型** | 每会话一个虚拟线程执行阻塞式协议栈调用（10 万虚拟线程 ≈ 百 MB 级，完全可行） |
| V2 | **EventLoop 铁律** | EventLoop 上**只允许**编解码与状态机推进；任何阻塞调用（含日志同步写、DB、锁等待）都是事故 |
| V3 | **Pinning 情形一：`synchronized`** | 虚拟线程在 `synchronized` 块/方法内阻塞会钉住载体，**且调度器不会扩容补偿**。[JEP 444](https://openjdk.org/jeps/444) 明确列出的两种 pinning 情形之一。对策：① 自研代码一律用 `ReentrantLock`（ArchUnit 禁止 `synchronized` 方法/块）；② 第三方库内部无法改 → 监控 + 必要时退回平台线程。**JEP 491（JDK 24）已修复此条**，是未来升级的主要收益 |
| V3.1 | **Pinning 情形二：`native` 方法** | **经 JNI 的调用同样 pinning，且在 JDK 21 与未来版本上都不可移除**——JEP 444 原文：*"The second limitation is required for proper interaction with native code"*。这直接决定了本仓的架构：**串口（jSerialComm）、CAN（JavaCAN）、媒体（JavaCV/FFmpeg）必须跑在平台线程池**，不能指望升级 JDK 解决。详见 `PROTOCOLS.md` §5.5 的分级表与落地规则 |
| V4 | **调度器两个参数，别搞混** | ① `jdk.virtualThreadScheduler.parallelism`：调度用平台线程数，**默认 = CPU 核数**；② `jdk.virtualThreadScheduler.maxPoolSize`：**默认 256**，是「占用 OS 线程但不 pinning」的阻塞操作（文件 I/O、`Object.wait()`）**临时扩容**的上限——**它不补偿 pinning**。暴露为 `ypbin.iot.scheduler.parallelism` / `-max-pool-size`，默认值保持 JDK 默认，**不预设调优** |
| V5 | **ThreadLocal 陷阱** | 虚拟线程按线程复制 ThreadLocal → 10 万份副本内存放大。协议栈**禁止**用 ThreadLocal 做上下文缓存 |
| V6 | **不要池化虚拟线程** | `newVirtualThreadPerTaskExecutor` 即用即建；池化虚拟线程会退化为平台线程池并引入排队 |
| V7 | **`ScopedValue` 优先** | 需要传递求值上下文（设备/租户）时用 `ScopedValue`（JDK 21 预览）而非 ThreadLocal |
| V8 | **Boot 4.1 默认虚拟线程** | Boot 4.1 在 JDK 21+ 下自动用虚拟线程替换 Tomcat 工作线程。**这与接入层无关**——但意味着宿主 Web 线程也是虚拟线程，**Web 侧的 DB 访问同样受 §5.4 约束** |

### 5.4 虚拟线程 × 连接池：冲突本质与对策（重点）

**冲突本质**：HikariCP 等连接池的设计假设是「线程数有限且稳定，线程与连接近似 1:1」。
虚拟线程把「并发单元」从「几百个平台线程」放大到「几十万个虚拟线程」，
这个假设被彻底打破：

```
10 万设备会话 ──> 10 万虚拟线程 ──> 每个都想拿 DB 连接
                                        │
                                   HikariCP(默认 max=10)
                                        │
                                   connectionTimeout = 30s 内排队
                                        │
                        虚拟线程不阻塞载体线程 → 排队者不释放任何资源
                                        │
                        「等待」的成本几乎为零 → 排队长度无自然上限
                                        │
                              → 超时雪崩 + 池饥饿 + 内存累积
```

关键危险在于：**平台线程池时代，排队本身是一种背压**（线程池满了会拒绝）；
虚拟线程时代，排队几乎免费，于是背压消失了，系统会一直堆积到内存耗尽。

**五条对策**：

| # | 对策 | 说明 |
|---|---|---|
| **C1** | **架构层根治：接入层不碰 DB** | 本方案的协议接入路径上**没有任何数据库访问**：连接参数来自 `DeviceRegistry`（启动时加载/变更时推送），采集数据经 `DataEgress` 有界队列出站。**这是最有效的一条**——冲突被架构规避，而不是被参数调优掩盖 |
| **C2** | **强制闸门** | 若确需 DB，所有访问必须经 `Semaphore(poolSize)` 显式限流。`Semaphore` 对虚拟线程友好（阻塞时释放载体线程），而 `ThreadPoolExecutor` 的队列会失效 |
| **C3** | **池参数对齐** | `maximumPoolSize` = 闸门许可数；`connectionTimeout` 从默认 30s 收紧到 1~3s（**虚拟线程下等待成本极低，超时必须由业务侧快速失败来控制**）；`maxLifetime` < DB 侧 `wait_timeout` |
| **C4** | **持久化用平台线程池** | JDBC 驱动内部（MySQL Connector/J、pgjdbc 的部分路径）使用 `synchronized`，虚拟线程执行会 pinning。数据落库线程池**固定为平台线程**，彻底规避 |
| **C5** | **拒绝回调内同步查询** | 协议回调（`DataListener`）与 `DataSink.write` 中禁止同步 DB 调用，由框架在 API 层通过线程身份校验直接抛异常拦截 |

> **给宿主的明确建议**：如果宿主的 `DataSink` 实现要写 DB，请使用
> **「有界队列 + 固定平台线程消费者 + 批量插入」** 三段式，
> 而不是「每个批次一个虚拟线程直接写库」。前者吞吐稳定可控，后者会在数据库抖动时无限堆积。

> **这不是本方案的一家之言**：Spring Boot 4.1 落地虚拟线程后，
> 「虚拟线程开启后 HikariCP 连接池崩掉」已成为社区反复出现的高频问题
> （参见 [Spring Boot 4.1 虚拟线程与连接池的实践讨论](https://www.cnblogs.com/uniqueDong/p/20249176)）。
> 主流结论与本方案一致：**连接池参数调优解决不了设计假设冲突，必须靠架构隔离 + 显式并发闸门**。
> 本仓的选择是更进一步——**让接入路径根本不碰数据库**（C1），
> 把这个问题从「运行期调参」降级为「架构上不存在」。

**C1 的边界：接入路径上「允许 / 禁止」的操作对照表**

「零 DB 访问」需要一个可判定的边界，否则会被理解成「什么都不能做」或「偶尔查一下也行」。判定标准是
**该操作是否运行在协议线程 / 回调线程上**：

| 操作 | 位置 | 允许？ | 说明 |
|---|---|---|---|
| 加载设备清单 | 启动期（`ApplicationReadyEvent` 之前） | ✅ **允许** | `DeviceRegistry.loadAll()` 全量加载到内存，此后运行期只读内存 |
| 接收设备变更 | 运行期，**由宿主推入** | ✅ **允许** | admin 通过框架 API 推变更，或经消息总线订阅。**禁止框架去轮询 DB** |
| 解析凭据 | 建链时 | ⚠️ **看来源** | 走环境变量 / Vault → 允许；**走 DB → 必须过 C2 的 `Semaphore` 闸门** |
| 写点位数据 | `DataSink.write` | ✅ **允许**（但只能入队/异步） | 允许做一次非阻塞入队；**禁止**在 `write` 内同步写库 |
| 查 DB（任何形式） | `DeviceSession.read/write/subscribe` 调用栈内 | ❌ **禁止** | 协议线程上绝不允许 |
| 查 DB | `AdapterContext` 的任何回调内 | ❌ **禁止** | 同上 |
| 调远程 HTTP/RPC | 协议线程 / 回调线程 | ❌ **禁止** | 需要时必须投递到宿主自己的异步管道 |
| 日志同步写盘 | 协议线程 | ⚠️ **谨慎** | 高频路径禁止逐点位打日志；日志框架的异步 appender 优先 |
| 阻塞队列 `take()` | EventLoop | ❌ **禁止** | EventLoop 上任何阻塞都是事故（V2） |
| 阻塞队列 `take()` | 虚拟线程（协议会话线程） | ✅ **允许** | 虚拟线程会被优雅卸载 |

**执行方式（不靠自觉，靠拦截）**：

1. **`AdapterContext` 不暴露任何数据访问能力** —— 没有 `DataSource`、没有 `JdbcTemplate`、没有 `RestClient`。
   适配器想查库在 API 层面就做不到（**这是最有效的一层**）。
2. **`ThreadIdentityGuard` 运行期校验**：`DataSink.write` / `DataListener.onData` 入口检查当前线程身份，
   若在协议线程上检测到同步 DB/HTTP 调用（通过包装的 `DataSource` 代理标记），**直接抛异常**而非记日志放过。
3. **`DataSink` 的参考实现只给一种形状**：有界队列 + 固定平台线程消费者 + 批量落库（见下方建议），
   让宿主「照抄就对」，不给自由发挥的空间。

> **为什么这条边界值得单独成表**：宿主开发者（包括我们自己在 admin 里）最容易犯的错，
> 就是图省事在 `DataSink.write` 里写一句 `mapper.insert(...)`。单机 10 万连接下，
> 这一句会让数据库连接池在数秒内被压垮，而故障现象表现为「IoT 框架卡死」，排查方向完全被带偏。

### 5.5 背压与流量治理

| 位置 | 机制 |
|---|---|
| 设备 → 接入层 | 协议级在途请求上限、自适应降频、慢设备隔离 |
| Netty 写路径 | `WriteBufferWaterMark` + `isWritable()`；不可写时暂停采集 |
| 会话队列 | 有界队列 + 拒绝策略（拒绝即记录 + 指标，不静默丢弃） |
| Egress | 有界环形缓冲 + 微批 + 显式溢出策略 + `iot_egress_dropped_total` |
| 建链 | 令牌桶限速 + 抖动（防启动风暴与重连风暴） |
| 宿主 Sink | 由宿主负责；框架提供队列深度指标供其告警 |

### 5.6 集群千万级：分片而非堆机器

单机 10 万已是工程极限，千万级（= 100 × 10 万）**只能靠水平分片**：

```
                          ┌─────────────────────────┐
                          │   接入网关 / LB 层        │
                          │  按 deviceId 一致性哈希    │
                          └───────────┬─────────────┘
                                      │
        ┌───────────────┬─────────────┼─────────────┬───────────────┐
        │               │             │             │               │
   ┌────▼────┐    ┌────▼────┐   ┌────▼────┐   ┌────▼────┐    ┌────▼────┐
   │ 接入节点 │    │ 接入节点 │   │ 接入节点 │   │ 接入节点 │    │ 接入节点 │  × 200+
   │  5万连接 │    │  5万连接 │   │  5万连接 │   │  5万连接 │    │  5万连接 │
   └────┬────┘    └────┬────┘   └────┬────┘   └────┬────┘    └────┬────┘
        │              │             │             │              │
        └──────────────┴─────────────┼─────────────┴──────────────┘
                                     │
                    ┌────────────────▼────────────────┐
                    │  Kafka（数据总线，按 deviceId 分区）│
                    └────────────────┬────────────────┘
                                     │
        ┌────────────────┬───────────┼───────────┬────────────────┐
        │                │           │           │                │
   ┌────▼─────┐   ┌──────▼────┐ ┌────▼─────┐ ┌───▼──────┐  ┌──────▼─────┐
   │ 时序库集群 │   │ 元数据库   │ │ 设备影子  │ │ 规则引擎  │  │ 命令下行服务 │
   │(IoTDB/CK) │   │ (PG/MySQL)│ │ (Redis)  │ │          │  │            │
   └──────────┘   └───────────┘ └──────────┘ └──────────┘  └────────────┘
```

| # | 技术点 | 要点 |
|---|---|---|
| S1 | **设备归属分片** | `nodeId = hash(deviceId) mod N`；节点注册到 Nacos（母仓 L3 已有），网关据此路由 |
| S2 | **会话状态本地化** | 会话/链路状态**只存本地内存**，不做跨节点共享——跨节点同步 10 万会话的状态是灾难 |
| S3 | **下行命令路由** | 必须投递到持有该设备会话的节点：网关查「设备→节点」路由表（Redis，带本地缓存与失效订阅） |
| S4 | **故障接管** | 节点下线 → 一致性哈希环上的后继节点接管；接管必须**限速**（否则接管瞬间即雪崩，重演启动风暴） |
| S5 | **上下线事件广播** | 走 Kafka（按 deviceId 分区保序），不做 RPC 广播 |
| S6 | **数据总线分区** | 按 `deviceId` 分区保证单设备有序；消费者按分区并行，标签不要用 deviceId（基数爆炸） |
| S7 | **无共享状态优先** | 任何需要跨节点共享的东西（订阅关系、最新值）一律外置到 Redis，且必须能容忍短暂不一致 |
| S8 | **接入节点无状态化** | 除会话外不持有任何不可重建状态——重启后能从元数据库完整重建，这是可运维性的底线 |
| S9 | **分级部署** | 接入层（长连接、内存型）与业务层（计算、存储）物理隔离，避免 GC 与 CPU 争抢 |
| S10 | **容量水位** | 单节点连接数超过 70% 即触发扩容告警，绝不允许撑到 100% |

### 5.7 存储层设计（宿主侧建议，本仓不实现）

| 层 | 选型 | 关键设计 |
|---|---|---|
| **元数据** | PostgreSQL / MySQL | 设备台账、点位表、连接配置。**接入节点启动时全量加载到内存**，运行期只读本地缓存 + 变更推送，运行期零 DB 查询 |
| **最新值（设备影子）** | Redis（Cluster + Hash 分片） | 每设备一个 Hash（`HSET shadow:{deviceId} pointId value ts`），读多写多。**避免每点位一个 key**（千万设备 × 百点位 = 十亿 key，Redis 直接崩） |
| **时序数据** | Apache IoTDB / ClickHouse / TDengine（三选一） | 必须**批量攒批写入**（≥1000 点/批或 100ms 攒批），严禁逐点写；按时间分区 + TTL 自动过期；标签列与测点列分离 |
| **冷数据归档** | 对象存储 + Parquet | 时序库 TTL 到期前导出，成本降一个数量级 |
| **消息总线** | Kafka | 按 `deviceId` 分区保序；压缩用 `lz4`/`zstd`；保留期按业务定 |
| **缓存** | Redis（母仓 `ypbin-starter-cache` 已有） | 复用母仓能力，不重复造 |

**三条硬规则**：

1. **接入层运行期不查 DB**（连接配置启动加载 + 变更推送）。
2. **时序写入必须攒批**，逐点写入在 200 万点/秒下必然击穿任何时序库。
3. **设备影子用 Hash 不用独立 key**，key 数量是 Redis 的第一约束。

### 5.8 JVM 与可观测性

| # | 技术点 | 要点 |
|---|---|---|
| J1 | **分代 ZGC** | JDK 21 已支持，亚毫秒停顿；10 万连接下比 G1 的尾延迟表现更稳定 |
| J2 | **堆外内存显式化** | `-XX:MaxDirectMemorySize` = 堆大小；Netty 泄漏检测 `SIMPLE`（预发 `PARANOID`） |
| J3 | **JFR 常开** | 开销 <1%，重点事件：`jdk.VirtualThreadPinned`、`jdk.SocketRead/Write`、`jdk.JavaMonitorEnter` |
| J4 | **指标白名单** | 只暴露有限标签集。**禁止**以 `deviceId` / `address` 作指标标签（Prometheus 基数爆炸） |
| J5 | **诊断端点** | `/actuator/iot` 装配与连接总览；`/actuator/iot/{deviceId}` 单设备诊断链 |
| J6 | **长稳测试** | 72 小时 × 10 万连接，观察 FD/直接内存/虚拟线程数/GC 是否收敛 |
| J7 | **优雅停机顺序** | 摘除注册 → 停 accept → 等待在途请求 → flush egress → 关链路 → 停时间轮 |
| J8 | **日志分级** | 接入层高频日志（每点位）必须可关闭；设备级日志走独立 logger 便于路由 |

### 5.9 安全关键点

| # | 技术点 | 要点 |
|---|---|---|
| X1 | **传输加密** | TLS 1.3（TCP/HTTP/WS）、DTLS 1.2（CoAP/UDP）、OPC UA 证书、SNMPv3、MQTT over TLS |
| X2 | **凭据管理** | 凭据只传引用（`credentialRef`），用时解析；`char[]` 承载便于清零；**禁止**进入日志与序列化 |
| X3 | **接入准入** | 设备白名单、IP 绑定、每设备速率限制 |
| X4 | **协议库漏洞** | 每个协议模块都带三方依赖，必须 SBOM（母仓已有 `-Psbom`）+ Dependabot |
| X5 | **不安全默认值拦截** | 检测到 `insecureSkipVerify=true`、默认口令、匿名访问时**启动期 warn + 运行期周期告警** |
| X6 | **反序列化边界** | 协议报文解析必须设长度上限与嵌套深度上限，防止恶意报文构造 OOM |

### 5.10 内核与 JVM 参数基线（10 万连接，可直接复制）

> 这一节是**部署清单**，不是建议——`§5.2 N4/N5` 与 `§5.8 J1/J2` 提到的参数在此给出具体取值。
> 未按此配置时，10 万连接压测必然在某一项上先失败，且失败现象往往与"性能"无关（表现为建连随机失败）。

**Linux 内核参数**（`/etc/sysctl.d/99-iot.conf`）：

```ini
# 连接队列：10 万连接下 accept 队列与会话队列都不能是默认值
net.core.somaxconn            = 65535     # 默认 4096，listen backlog 上限
net.ipv4.tcp_max_syn_backlog  = 262144    # 默认 1024，SYN 队列
net.core.netdev_max_backlog   = 262144    # 默认 1000，网卡收包队列

# 端口与连接
net.ipv4.ip_local_port_range  = 10000 65535
net.ipv4.tcp_tw_reuse         = 1         # 客户端侧长连接重建时复用 TIME_WAIT
net.ipv4.tcp_fin_timeout      = 15

# 缓冲区：小报文高并发场景要收紧单连接缓冲，避免内存被 10 万连接摊薄
net.core.rmem_max             = 16777216
net.core.wmem_max             = 16777216
net.ipv4.tcp_rmem             = 4096 65536 16777216
net.ipv4.tcp_wmem             = 4096 65536 16777216
net.ipv4.tcp_mem              = 786432 1048576 26777216

# 文件句柄：10 万连接至少需要 2 倍以上的 FD 余量
fs.file-max                   = 2097152
fs.nr_open                    = 2097152
```

**systemd 服务限制**（`LimitNOFILE` 与 `ulimit -n` 必须双设，只设一个不生效）：

```ini
[Service]
LimitNOFILE=1000000
LimitNPROC=1000000
# 需要串口/CAN 时追加：SupplementaryGroups=dialout
```

```bash
# 容器部署时同样需要（--ulimit 与 --sysctl 不会被镜像继承）
docker run --ulimit nofile=1000000:1000000 \
           --sysctl net.core.somaxconn=65535 \
           --cap-add=CAP_NET_ADMIN \      # 仅 CAN/串口模块需要，不要用 --privileged
           ...
```

**JVM 参数基线**（8C16G，10 万连接）：

```bash
java -XX:+UseZGC -XX:+ZGenerational \          # J1：分代 ZGC，尾延迟最稳
     -Xms8g -Xmx8g \                            # 固定堆，避免动态扩缩带来的抖动
     -XX:MaxDirectMemorySize=8g \               # J2：显式等于堆大小，Netty 直接内存的主要去处
     -XX:+HeapDumpOnOutOfMemoryError \
     -XX:HeapDumpPath=/var/log/iot/ \
     -XX:StartFlightRecording=disk=true,maxsize=512m,dumponexit=true \  # J3：JFR 常开
     -Dio.netty.allocator.type=pooled \         # N6：池化 ByteBuf
     -Dio.netty.leakDetection.level=simple \    # 预发改 paranoid
     -Dio.netty.maxDirectMemory=0 \             # 由 MaxDirectMemorySize 统一管控，避免两套账
     -Dio.netty.noPreferDirect=false \
     -Djdk.virtualThreadScheduler.parallelism=16 \      # V4：调度平台线程数，默认 = CPU 核数（此处仅为显式化）
     -Djdk.virtualThreadScheduler.maxPoolSize=256 \     # V4：默认 256；补偿"占 OS 线程但不 pinning"的阻塞，**不补偿 pinning**
     -jar app.jar
```

**三个容易漏的点**：

1. **`LimitNOFILE` 只改 systemd 不够**：Java 进程若经 shell 脚本启动，脚本自身的 `ulimit -n` 会覆盖；
   必须在启动脚本里显式 `ulimit -n 1000000`，并在启动日志中**打印实际生效值**（`OperatingSystemMXBean` 或读取 `/proc/self/limits`）。
2. **容器的 `--sysctl` 限制**：`net.core.somaxconn` 等 per-namespace 参数可以设置，
   但 `fs.file-max`、`vm.*` 等全局参数在容器内不可写——必须在宿主机设置，
   否则会出现「容器内看起来配好了、实际没生效」的假象。
3. **native 协议模块必须配平台线程池**（本项目特有，最容易漏）：
   串口 / CAN / 媒体类适配器走 `AdapterContext.scheduler().platformThreadExecutor()`，
   其线程数需按**物理串口数 / CAN 通道数**配置（不是按设备数），典型值个位数到几十：

   ```yaml
   ypbin:
     iot:
       scheduler:
         platform-pool:
           core-size: 8            # 建议 >= 串口数 + CAN 通道数 + 媒体任务并发
           max-size: 32
           queue-capacity: 10000   # 有界：满了要拒绝并计数，不能无限排队
   ```

**pinning 的诊断与监控（生产必备）**：

| 手段 | 用途 | 开销 |
|---|---|---|
| `jdk.VirtualThreadPinned` JFR 事件（**默认开启，阈值 20ms**） | 生产监控：pinned 次数与累计时长，超阈值告警 | 低，常开 |
| `-Djdk.tracePinnedThreads=short` | **预发定位**：打印 pinning 时的关键栈帧，直接指出是哪段 `synchronized` 或 native 帧 | 高，**生产禁用** |
| `-Djdk.traceVirtualThreadLocals=true` | 排查 ThreadLocal 内存放大（10 万虚拟线程 × 每份副本） | 高，仅诊断用 |

> **注意 pinning 与「占住 OS 线程」是两回事**：后者（文件 I/O、`Object.wait()`）调度器会
> 临时扩容 `ForkJoinPool` 补偿，上限 `jdk.virtualThreadScheduler.maxPoolSize`（默认 256）；
> **pinning 不被补偿**。因此看到 `maxPoolSize` 到达上限时，要区分是普通阻塞把池撑满了，
> 还是 pinning 把载体占住了——两者的处置完全不同。

> **验收方式**：M0 压测时把本节参数**逐条对照 `/proc/self/limits`、`sysctl -a`、`jcmd VM.flags` 的实际输出**，
> 形成一份「假设 vs 实测」对照表归档。参数没生效就压测，得到的结论一定是错的。

---

## 6. 构建、测试与门禁

| 门禁 | 内容 |
|---|---|
| **格式化** | 继承母仓 spotless（`process-test-classes` 阶段 `check`），license header 覆盖为 iot 署名 |
| **架构约束** | `ypbin-iot-architecture-tests`：§3.4 的 A1~A5 全部落成会失败的规则，含**规则有效性自检**（合成违规反向验证） |
| **协议一致性** | `ypbin-iot-test` 的 TCK：一套抽象测试用例（连接、读、写、订阅、超时、关闭幂等、并发安全），每个协议模块必须全绿 |
| **装配测试** | 每模块 `ApplicationContextRunner` 验证：默认装配、开关关闭不装配、宿主 Bean 覆盖生效、协议库缺失不装配 |
| **集成测试** | 母仓 `ypbin-starter-test` 基座 + 协议模拟器（Modbus Slave / OPC UA Server / MQTT Broker，用 Testcontainers 起真容器） |
| **压测门禁** | 发布前必须通过：单节点 10 万连接 + 200 万点/秒持续 1 小时，FD/内存无增长趋势 |
| **长稳门禁** | 大版本发布前 72 小时长稳 |
| **供应链** | SBOM 归档 + 依赖漏洞审计 |

---

## 7. 与母仓 `ypbin-starter` 的关系

| 关注点 | 处理 |
|---|---|
| **版本治理** | 继承 `ypbin-starter-dependencies` 作为 parent，复用 Boot 4.1 BOM 与插件配置；母仓升级 Boot 版本时 iot 仓只需改 parent 版本号 |
| **功能重叠** | `ypbin-starter-messaging` 已有基于 Paho 的 MQTT **发布/订阅能力**（面向应用消息推送）。本仓的 `protocol-mqtt` 面向**设备接入**（海量连接、点位模型、订阅汇聚），语义与生命周期完全不同。**两者并存不合并**：宿主若都用到，二者都引，互不干扰 |
| **功能重叠** | `ypbin-starter-async` 已提供虚拟线程执行器。iot 仓的 `TaskScheduler` 面向**10 万级定时采集**（时间轮），与通用 `@Async` 执行器不是一回事，各自独立 |
| **代码复用** | 复用母仓的 `SpringUtils`、`R`、`BusinessException`、`ContextPropagator` 等（仅限 `-spring-boot-starter` 模块，core/runtime 不复用以免引入 Spring） |
| **同步机制** | 母仓新增通用能力（如新的缓存/日志规范）通过 parent 版本升级带入；iot 仓不得反向依赖母仓的具体能力模块 |
| **集成到 admin** | admin 引入 `ypbin-iot-bom` + 所需协议模块，实现宿主的 `DeviceRegistry`（从 `sys_device` 表加载）与 `DataSink`（写时序库/Kafka）即可，无需改框架代码 |

---

## 8. 技术风险与应对

| # | 风险 | 等级 | 影响 | 应对方案 |
|---|---|---|---|---|
| **R1** | **协议库许可证**：部分工业协议 Java 库为 GPL（BACnet4J、Calimero、OpenMUC 尤甚）。**个人项目下使用无碍**，但若将来用于闭源分发会传染整个分发物 | 🟢 低（当前）/ 🟡 中（将来商用） | 将来商用时的合规返工 | ① 构建期扫描完整依赖树并**自动生成 `LICENSE-RISK.md`**（D1 修订：记录而非阻断）；② GPL 依赖引入时打印显著警示 + 模块 README 标注；③ 选型结论与许可事实记录在 `PROTOCOLS.md` §5；④ **自研模块只参考协议规范，不参考实现代码** |
| **R2** | **OPC DA 无法原生 Java 访问**：COM/DCOM 是 Windows 专有，JVM 内无原生通道 | 🟢 低（已按 D2 移出规划） | 无（不做即无风险） | **从路线图移除**（D2）：个人项目无 Windows 桥接环境与 OPC DA 服务器，无法验证。方案留档在 `PROTOCOLS.md` §2.4：① Windows 侧部署轻量桥接进程（COM → 本地 TCP/gRPC）+ Java 侧普通 TCP 适配器；② 或建议迁 OPC UA |
| **R3** | **PROFINET / LonWorks 无成熟 Java 库**：二者都强依赖实时以太网/专有链路层，Java 生态基本空白 | 🔴 高 | 该协议可能无法交付 | ① 明确**降级为「通过网关设备间接接入」**：客户用硬件网关把 PROFINET/LonWorks 转成 Modbus/OPC UA/MQTT，本框架只接网关；② 若必须直连，则需要 native 库（C/C++）+ JNI，成本极高，需单独立项评估；③ 在路线图中置于最后，且标注「需商务确认」 |
| **R4** | **虚拟线程 pinning**：JDK 21 下 `synchronized` 内阻塞与 **`native` 方法调用**都会钉住载体线程，而第三方协议库大量使用二者，无法改造 | 🟠 中高 | 载体线程耗尽 → 全局停顿 | ① 自研代码一律 `ReentrantLock`（ArchUnit 禁止 `synchronized`）；② **区分两类 pinning**：`synchronized` 类（JEP 491 / JDK 24 可修复，属过渡性风险）与 **`native` 类（不可修复，属永久性约束）**；③ native 路径（串口 / CAN / 媒体）**强制平台线程池**，不寄望于升级 JDK；④ 生产常开 `jdk.VirtualThreadPinned` JFR 事件并设告警，预发用 `-Djdk.tracePinnedThreads=short` 定位；⑤ 若 pinning 告警持续超阈值，触发「JDK 24/25 升级评估」——但**升级只解决 `synchronized` 那一半**，线程池分工设计必须长期保留 |
| **R5** | **协议库质量参差**：大量工业协议 Java 库是个人项目，无测试、非线程安全、长期不维护 | 🟠 中高 | 线上偶发错乱、难以定位 | ① **TCK 一致性测试**强制每个适配器通过统一语义测试；② 适配器**无状态单例 + 会话私有状态**（禁用共享可变对象）；③ 对所有三方调用做**故障注入测试**（乱序、半包、超时、断连）；④ 关键协议（Modbus/S7/BACnet）**预留自研协议栈**的备选路径 |
| **R6** | **10 万连接的验证成本高**：真实环境难以搭建，容易被"小规模测试通过"误导 | 🟠 中高 | 上线即故障 | ① 自建**协议模拟器**（可起 10 万个 Modbus/OPC UA 从站容器）；② 压测门禁写进发布流程（§6）；③ 内建**连接数/内存增长趋势**指标，长稳测试作为大版本必过项 |
| **R7** | **集群会话归属与接管风暴**：节点故障时，其上 5 万设备的重连会瞬间压垮接管节点，并可能击穿对端 PLC | 🟠 中高 | 级联雪崩 | ① 接管**限速**（复用启动风暴防护的令牌桶 + 抖动）；② 一致性哈希 + 虚节点保证负载均衡；③ 对端设备侧连接数上限需在方案阶段与客户确认；④ 提供「接管进度」指标 |
| **R8** | **HikariCP × 虚拟线程冲突**（用户已识别） | 🟠 中 | 连接池雪崩、超时风暴 | 见 §5.4 的 C1~C5。**核心是 C1：接入路径零 DB 访问**，把冲突从架构上规避而不是靠调参掩盖 |
| **R9** | **数据出口成为瓶颈**：200 万点/秒若逐点回调或走 JSON，框架自身即瓶颈 | 🟠 中 | 吞吐不达标、内存堆积 | ① 强制微批 + 有界队列 + 显式溢出策略；② 默认提供 Protobuf 序列化，JSON 仅作调试；③ 提供 egress 队列深度与丢弃指标，让宿主能看见背压 |
| **R10** | **GB28181 复杂度失控**：SIP 信令 + SDP 协商 + RTP/PS 解复用 + 转封装，JVM 内做媒体处理成本极高，且国标各版本（2016/2022）差异大 | 🟠 中 | 模块膨胀，工期不可控 | ① **只做信令面**：SIP 注册/目录/邀请/云台控制，媒体流**不落地、不解码**，只做转推或旁路给专用媒体服务器；② 媒体处理独立为子系统，绝不进 `iot-core`；③ 明确支持的国标版本，不做"兼容所有厂商私有扩展"的承诺 |
| **R11** | **地址语义无法统一**：各协议地址表达差异巨大，统一抽象必然漏抽象 | 🟡 中 | 抽象反复推翻 | 设计上**主动放弃统一**（SPI 的 P2 原则）：core 只透传 `raw`，解析权归协议模块；宿主侧的点位模板由 admin 维护，框架不介入 |
| **R12** | **配置复杂度**：每个协议各有参数，宿主配置易错 | 🟡 中 | 支持成本高 | ① 每个模块必须有 `spring-configuration-metadata`（IDE 补全）；② 未知配置 key 必须 warn 并列出可用 key（禁静默忽略）；③ 复用母仓的配置参考自动生成机制（`tools/export-config-metadata.mjs`） |
| **R13** | **框架与宿主职责边界模糊**：宿主容易把业务逻辑塞进 `DataSink`/`DataListener`，导致接入层被拖慢 | 🟡 中 | 性能退化、故障扩散 | ① 文档与 Javadoc 明确边界；② 框架层校验：`DataSink.write` 内部出现同步 DB/HTTP 调用时，通过线程身份校验直接抛异常拦截；③ 提供标准三段式 Sink 参考实现 |
| **R14** | **母仓升级倒逼**：Boot 4.x 迭代快，parent 升级可能破坏协议模块 | 🟡 中 | 构建失败 | ① iot 仓 parent 版本**显式锁定**，不自动跟随；② 升级走独立 PR + 全量回归；③ 协议模块与 Spring 的耦合面刻意压到最小（只有 `autoconfigure` 子包） |
| **R15** | **人力与工期**：每个协议 300~600 行适配 + 测试，个人项目靠业余时间推进 | 🟠 中高 | 长期做不完 | ① 范围已按 D5 收敛到**核心档 5 个**；② 每个协议独立发版，不阻塞主版本；③ **协议立项前置条件**：能在本机验证（`PROTOCOLS.md` §7.5），否则不开始写 |
| **R16** | **GPL 依赖的将来约束被遗忘**：D1 修订后 BACnet4J 可用，但将来若用于闭源分发会传染整个分发物 | 🟡 中 | 将来商用时的返工与合规问题 | ① 构建自动生成 `LICENSE-RISK.md`，GPL 依赖引入时打印显著警示；② 模块 README 同步标注；③ 成本极低，收益是将来不用翻历史 |
| **R17** | **协议模块独立发版导致的版本兼容矩阵失控** | 🟡 中 | 运行期出现难以定位的行为异常 | ① `ypbin-iot-bom` 是唯一推荐引入方式，锁定整套版本；② `ProtocolDescriptor` 增加运行时版本区间声明，启动时校验并 fail-fast；③ 见 `RUNTIME.md` §5 |
| **R20** | **写了无法验证的协议适配器**（个人项目的头号风险）：无硬件、无模拟器，代码没有反馈回路 | 🔴 **高** | 长期携带未知 bug，且误以为可用 | ① **协议立项前置条件：必须能在本机验证**（`PROTOCOLS.md` §7.5 矩阵）；② 表里找不到验证手段的协议**不开始写**（D2/D5）；③ 模拟器与 M0 同步开工 |
| **R18** | **国际化缺失导致海外项目不可用**：错误消息/诊断信息若为中文硬编码，无法交付海外 | 🟡 中 | 海外项目需返工 | ① **复用母仓 `ypbin-starter-i18n`**，不自造机制（DESIGN §1.4.2 A6）；② `iot-core` 只定义消息键常量，各模块自带中英资源包；③ 禁止消息硬编码字符串，由源码规范门禁拦截 |
| **R19** | **协议模拟器缺失导致质量门禁空转**：没有模拟器，10 万连接压测与故障注入都无从谈起 | 🟠 中高 | 质量门禁形同虚设，上线即故障 | ① **模拟器与 M0 同步开工**，列为 M0 交付物而非"后续补"；② 统一模拟器平台 + 协议插件（见 `RUNTIME.md` §7）；③ M0 基准数据（单容器可模拟设备数/资源占用）必须产出 |

---

## 9. 关键设计决策记录（ADR 摘要）

| ID | 决策 | 备选 | 结论与理由 |
|---|---|---|---|
| ADR-01 | 独立仓 + 复用母仓 parent | 并入母仓 / 完全独立 | **独立仓**：协议库版本更新频繁，独立发版节奏；复用 parent 保证风格一致 |
| ADR-02 | 协议模块自带 autoconfigure | 集中式 starter 装配 | **自带**：否则"可单独引入"无法实现（§2.5） |
| ADR-03 | SPI 异步为主 | SPI 全同步 | **异步**：EventLoop 不可阻塞；同步库用 `BlockingProtocolAdapter` 桥接，两全 |
| ADR-04 | 连接 / 设备两级模型 | 单一 session 模型 | **两级**：Modbus 网关多从站是刚需，连接复用必须成为框架能力 |
| ADR-05 | 不做统一地址抽象 | 统一点位地址模型 | **不做**：必然漏抽象；core 只透传 raw，解析权归协议模块 |
| ADR-06 | 数据出口微批 + 有界队列 | 逐点回调 | **微批**：200 万点/秒下逐点回调框架即瓶颈 |
| ADR-07 | 会话状态本地化 | 分布式会话 | **本地**：状态同步成本远超收益；全局视图由宿主落库 |
| ADR-08 | 时间轮调度 | ScheduledThreadPool | **时间轮**：10 万定时任务的堆与精度开销不可接受 |
| ADR-09 | fail-fast 而非静默降级 | 尽量返回可用结果 | **fail-fast**：不支持的能力抛异常、协议 code 冲突终止启动——静默降级让故障潜伏 |
| ADR-10 | 接入路径零 DB 访问 | 运行时查库 | **零 DB**：从根本上规避虚拟线程 × 连接池冲突（§5.4 C1） |
| ADR-11 | **协议时序用 `Instant`**，业务时间用 `LocalDateTime` | 全部统一 `LocalDateTime`（母仓规范） | **分离**：协议时间戳是 UTC 绝对时刻，承载跨时区设备与时序对齐语义，用 `LocalDateTime` 会永久丢失时区；边界与转换规则见 §1.4.2 A1 |
| ADR-12 | **native 协议走平台线程池** | 统一走虚拟线程 | **分离**：JEP 444 明确 native 调用的 pinning **不可移除**，升级 JDK 也无效；串口/CAN/媒体必须平台线程（`PROTOCOLS.md` §5.5） |
| ADR-13 | **许可证记录而非阻断**（D1，2026-09 修订） | 原「从严、灰名单构建失败」 | **修订为记录**：本项目是个人自研项目、无商业交付约束，原决策依据失效。门禁改为「扫描 + 分类 + 生成 `LICENSE-RISK.md` + 显著警示」，**构建永不因许可证失败**；BACnet4J 解禁、`sip-*` 恢复使用。**代价是将来若商用需重新评估，因此「记录」这一步不能省**（`PROTOCOLS.md` §5.2） |
| ADR-14 | **i18n 复用母仓，不自造 `MessageResolver`** | 在 core 定义新的消息 SPI | **复用**：母仓 `ypbin-starter-i18n` 已解决 `MessageSource → I18nUtil` 桥接，再造一套会产生两套 i18n 机制与两处配置（§1.4.2 A6） |
| ADR-15 | **协议模块绑定 `autoConfiguration.imports` + 版本区间自校验** | 只靠 BOM 锁版本 | **双保险**：BOM 是"推荐引入方式"而非强制，宿主仍可单独引某模块；运行期版本校验把不兼容组合从"难以定位的异常"变成"启动期明确报错"（`RUNTIME.md` §5） |
| ADR-16 | **Modbus ASCII 用 j2mod，不投入自研**（D4，2026-09 修订） | 原「自研帧层」 | **修订为用现成库**：原决策理由是架构纯粹性（避免两套协议栈 + 无 log4j 污染），这些理由仍然成立，但**自研需自己写约 700 行并长期维护**；对个人项目而言「少写代码」权重更高。ASCII 放到最后按需做，届时直接引 `j2mod:3.4.0` 并排除其 `log4j-core`。自研方案的技术边界完整保留在 `PROTOCOLS.md` §2.6 供将来切换 |

---

## 10. 下一步

1. 评审本设计与 [SPI 契约](./SPI.md)，确认边界与抽象。
2. 按 [路线图](./PROTOCOLS.md#7-mvp-路线图) 落地 M0（骨架 + 通用 TCP + TCK + 架构门禁）。
3. M0 完成后立即做一次**单节点 10 万连接**基准压测——把规模目标从"设计假设"变成"已验证事实"，
   再决定是否铺开协议数量。
