# ypbin-iot-starter · 协议选型与 MVP 路线图

> 本文给出各协议的三方库选型、许可证管理、跨协议工程约束与分批落地节奏。
> **定位：个人自研项目**（无商业交付约束），决策口径见 §1.3；**范围已按可验证性收敛**，见 §7.1。
> **所有版本号都以检索时点（2026-09）的一手元数据为准**（`maven-metadata.xml` / 制品 POM / GitHub Release），
> 未验证项显式标注。原始调研存档见 [research/](./research/)。
>
> 返回 [总体设计](./DESIGN.md) ｜ [SPI 契约](./SPI.md)

---

## 1. 选型总览

### 1.1 版本取值纪律（先读）

Maven Central 的 Solr 检索接口（`search.maven.org/solrsearch`）**索引明显滞后**——
本次实测它报 `snmp4j 3.9.5` 而权威元数据实为 `3.13.1`，报 `javacv 1.5.11` 而实为 `1.5.14`。
因此本仓选型一律遵守：

1. 版本号只认 `https://repo1.maven.org/maven2/<path>/maven-metadata.xml`、制品 POM、GitHub Release/Tag；
2. 所有版本集中声明在 `ypbin-iot-dependencies` 的 `<properties>`，子模块**不写死版本**；
3. 引入前必须核对许可证，非白名单许可证直接拒绝进入构建（见 §5）；
4. 不在 Maven Central 的制品一律经**自建 Nexus 代理冻结**，禁止直接依赖 `master-SNAPSHOT`。

### 1.2 选型决策矩阵（按「推荐度」排序）

**「批次」列反映 §7 的落地节奏**；⛔/~~删除线~~ 表示按 D2 已移出规划。

| 协议 | 选型倾向 | 批次 | 关键约束 |
|---|---|---|---|
| Modbus TCP/RTU | 🟢 纯 Java 库成熟 | **M1** | 见 §2.6 |
| Modbus ASCII | 🟡 **用 j2mod（D4 修订）**，不投入自研 | 待定 | 见 §2.6 |
| OPC UA | 🟢 纯 Java 库成熟（EPL-2.0） | **M1** | 见 §2.3 |
| MQTT | 🟢 纯 Java 库成熟 | **M1** | 见 §4.2 |
| TCP / UDP / HTTP / WebSocket | 🟢 自研（Netty 底座） | M0 / M2 | 见 §4.4 |
| SNMP | 🟢 纯 Java，Apache-2.0 | **M2** | 见 §3.8 |
| S7 | 🟡 可用；需 docker 化 S7 模拟器验证 | **M2** | 见 §2.5 |
| CoAP | 🟢 纯 Java（锁 3.14.0 稳定线） | M3 | 见 §4.3 |
| EtherNet/IP | 🟡 可用但需真机实测 | M3 | 见 §2.7 |
| **BACnet** | 🟡 GPL-3.0（D1 修订：**个人项目可用**）+ 非 Central 制品 | **M3** | 见 §3.2；许可已解禁，仅需登记 |
| KNX | 🟢 GPL-2.0+CPE；**`calimero-testnetwork` 可免硬件验证** | M4+ 按需 | 见 §3.3 |
| ~~OPC DA~~ | ⛔ **无免硬件验证手段** | **不做**（D2） | 见 §2.4，方案留档 |
| CAN | 🟢 native + Linux，**`vcan` 可免硬件验证** | **M3** | 见 §2.9 |
| HART | 🟠 需自研 HART-IP；**需 HART 网关，无法免硬件验证** | 待定 | 见 §2.10 |
| GB28181 | 🟡 用现成 `sip-*` 框架（D1 修订），**媒体外置** | M4+ 按需 | 见 §3.6；信令可测、**媒体面无法免硬件验证** |
| ONVIF | 🟡 无官方库，CXF 自生成 WSDL；**无成熟模拟器，自动验证困难** | M4+ 按需 | 见 §3.7 |
| GB/T 26875 | 🟠 必须自研（纯报文层，**可免硬件验证**）+ 标准正在换版 | M4+ 按需 | 见 §3.5；**换版双轨** |
| ~~PROFINET~~ | 🔴 无 Java 库 + 无免硬件验证 | **不做**（D2） | 见 §2.8，方案留档 |
| ~~LonWorks~~ | 🔴 无 Java 库 + 生态退场 + 无免硬件验证 | **不做**（D2） | 见 §3.4，方案留档 |
| EtherCAT / IO-Link / DALI / M-Bus / JT/T 1078 / Zigbee / Z-Wave | 🔴 无成熟 Java 库 + 需专用硬件 | **不做**（D2/D5） | 见 §2.11 |
| gRPC / AMQP 1.0 | 🟡 库成熟但「设备接入」语义待定 | 有需求再说 | 见 §2.11 |

### 1.3 决策口径（**个人项目定位，2026-09 修订**）

**决策口径：个人项目定位（2026-09 修订）**

> **定位说明**：本项目是**个人自研项目**，用于以后做物联网项目时直接上手，**无商业交付约束**。
> 原先基于「闭源商用交付」制定的许可证决策**依据已失效**，本节整体修订。
> 原则从「**合规优先，宁可不做**」转为「**能跑优先，风险显式记录**」。

| 维度 | 商业项目口径（旧） | **个人项目口径（新）** |
|---|---|---|
| 许可证 | 非白名单**构建失败** | **只记录与警示，不阻断构建**；GPL 类依赖在 `LICENSE-RISK.md` 显式登记 |
| 协议范围 | 21 个模块全规划 | **只做能纯软件验证的**，其余按需（D5） |
| 不可直连协议 | 「不承诺纯 Java」（交付话术） | **直接不做**（无硬件与桥接环境，无法验证） |
| 架构纯粹性 | 优先（两套协议栈不可接受） | **能跑优先**，代码量让位于可维护性（D4 重估） |
| 规模目标 | 客户验收指标 | **保留架构能力，收敛验收门槛**（D6） |

| # | 决策 | 落地口径 | 状态 |
|---|---|---|---|
| **D1** | **许可证：记录而非阻断** | ① 门禁从「构建失败」改为「**扫描 + 分类 + 生成 `LICENSE-RISK.md`**」，构建不因许可证失败；② **BACnet4J（GPL-3.0）可以用**，但必须在模块 README 与 `LICENSE-RISK.md` 标注「引入即受 GPL-3.0 约束，将来若用于闭源项目需重新评估」；③ **`io.github.lunasaw:sip-*` 恢复使用**——其 POM=Apache-2.0 / README=MIT **两者都是宽松许可**，原先的「冲突」只影响法务确定性，不影响个人使用；④ Calimero（GPL-2.0+CPE）正常使用，仅记录 | 🔄 修订 |
| **D2** | **OPC DA / PROFINET / LonWorks 不规划** | ① 三者都需要**专用硬件或 Windows 桥接环境**，个人项目既无设备也无验收场景，**无法验证的代码等于没有代码**；② 从路线图移除，文档保留（§2.4 / §2.8 / §3.4）作为「将来有环境再做」的说明；③ 方案已就绪：OPC DA 走 Windows 桥接进程，PROFINET / LonWorks 走硬件网关 | 🔄 修订 |
| **D3** | **GB/T 26875 按换版双轨设计** | ① 编解码层与业务层彻底解耦；② 协议版本用策略 + 版本号路由，2011 版与新版共存；③ 标准附录样例做**黄金报文测试**；④ **禁止**把 2011 版字段写死进实体与数据库；⑤ 接受「成本高于单版本实现」这一前置投入 | ✅ 保留 |
| **D4** | **Modbus ASCII：优先用 j2mod，不投入自研** | ① ASCII **不进 M1**，放到最后按需做；② 一旦要做，**优先直接引入 `com.ghgande:j2mod:3.4.0`**（Apache-2.0，`ModbusASCIITransport` 现成）；③ 原「自研帧层」方案**降级为备选**——它省掉第二套协议栈与 log4j 污染，但要自己写约 700 行并长期维护，**对个人项目而言「少写代码」权重更高**；④ 引入 j2mod 必须 `<exclusions>` 掉 `log4j-core` + `slf4j-reload4j`；⑤ 自研方案的技术边界（digitalpetri 的 PDU/基类可复用、串口通道要自建、**不规避 LGPL**）仍完整记录在 §2.6，供将来切换时参考 | 🔄 修订 |
| **D5** | **范围收敛：先做能纯软件验证的协议** | ① **不做 21 个模块**。第一优先级是「**能在本机跑起来并验证**」，而不是协议覆盖数；② 分三档：**核心档**（M1，5 个）、**扩展档**（M2，4 个）、**按需档**（有需求且能验证时再做）；③ 清单与依据见 §7.1 | 🆕 新增 |
| **D6** | **规模目标：保留架构能力，收敛验收门槛** | ① 架构仍按 10 万连接设计（设计成本几乎为零，且是主要学习价值）；② **日常门禁降为 1 万连接**（个人开发机可跑），10 万连接作为「云服务器上的一次性验证」而非每次发布必过；③ 72 小时长稳降为 **8 小时**（足以看出 GC 与内存增长趋势） | 🆕 新增 |

---

## 2. 工业协议选型

### 2.1 总览

| 协议 | 推荐库（坐标） | 最新稳定版 | 许可证 | 形态 | 结论 |
|---|---|---|---|---|---|
| **OPC UA** | `org.eclipse.milo:milo-sdk-client`（+ `milo-sdk-server` / `milo-stack-core` / `milo-transport`） | **`1.1.7`** | **EPL-2.0** | 客户端 + 服务端，Netty 底层，全异步 | ✅ 首选 |
| **OPC DA** | 无 Java 实现；桥接候选 `org.jinterop:j-interop` | `2.0.4` | 未验证（J-Interop 通常 LGPL） | Windows COM/DCOM | 🟠 必须外置桥接进程 |
| **S7** | `org.apache.plc4x:plc4j-driver-s7` | **`1.0.0`** | **Apache-2.0** | 客户端，全异步 | ✅ 首选 |
| **Modbus**（TCP/RTU） | `com.digitalpetri.modbus:modbus-tcp` / `modbus-serial` | **`2.1.6`** | **EPL-2.0** | 客户端 + 服务端，**基于 Netty** | ✅ 默认首选 |
| **Modbus**（ASCII） | **自研帧层**（复用 digitalpetri 的 PDU 与基类） | — | 自有 | ASCII 帧 + `sendAsync` + 串口通道 | 🟡 M3，见 §2.6 D4 |
| **EtherNet/IP** | `org.apache.plc4x:plc4j-driver-eip` | **`1.0.0`** | **Apache-2.0** | 客户端，全异步 | 🟡 可用，需实测 |
| **PROFINET** | **无** | — | — | — | 🔴 只能网关转换 |
| **CAN** | `tel.schich:javacan-core` / `javacan-tools` | **`3.5.2`** | **MIT** | Linux SocketCAN 的 JNI 封装 | 🟠 必须 Linux + native |
| **HART** | **无**（Central `numFound=0`） | — | — | — | 🟠 自研 HART-IP + 物理层网关 |

> 上表版本均于 **2026-09-13** 从 `repo1.maven.org` 的 `maven-metadata.xml` 与制品 POM 直接核验。

### 2.2 必须先知道的三件事

#### ① PLC4X 已发布 1.0.0 GA（2026-09-07）

`org.apache.plc4x:plc4j-api:1.0.0` 及 **s7 / modbus / opcua / eip / can / ads / ab-eth** 等驱动同步发布 1.0.0。
这是工业协议 Java 生态的重要节点，但**我们只在「协议实现库」层面用它，不让它成为框架抽象层**：

| 维度 | 判断 |
|---|---|
| 能否作为 `ProtocolAdapter` 的抽象基础 | ❌ **不能**。PLC4X 是**采集侧驱动**抽象，不覆盖 MQTT / BACnet / SNMP / GB28181 / ONVIF / KNX 等本仓目标协议，也不提供连接复用、会话生命周期、订阅汇聚、微批出口等我们需要的语义 |
| 能否作为部分协议的实现库 | ✅ **可以**。S7、EtherNet/IP、CAN 首选 PLC4X 驱动；Modbus 亦有驱动但不如 digitalpetri 贴合 Netty 生态 |
| API 是否会泄漏到 `iot-core` | ❌ **严禁**。PLC4X 的 `PlcConnection` / `PlcReadRequest` 等类型只允许出现在对应协议模块的 `protocol/` 包内，由 ArchUnit 强制 |
| 吸收的**思路**（非代码） | 统一驱动抽象、地址语法（`%DB1.DBW0:INT`）、连接池与连接缓存、驱动自动发现 |

> ⚠️ **BACnet 驱动没有跟进 1.0.0**：`plc4j-driver-bacnet` 仍停在 `0.13.1`（2025-08-29）。
> 因此 BACnet 仍面临「BACnet4J 的 GPL-3.0 + 非 Central 制品」的约束（见 §3.2）。

#### ② OPC UA 的 Milo 坐标已迁移（最容易踩的坑）

| 坐标 | 最新版本 | 状态 |
|---|---|---|
| `org.eclipse.milo:sdk-client` | `0.6.16`（2025-04-21） | ⛔ **旧坐标，已冻结** |
| `org.eclipse.milo:milo-sdk-client` | **`1.1.7`**（2026-09-09） | ✅ **新坐标，使用它** |

网络上的大量教程与示例仍在使用 `sdk-client`，照抄会拿到一个一年半前冻结的版本。
同类新坐标：`milo-sdk-server` / `milo-sdk-core` / `milo-stack-core` / `milo-transport` / `milo-bom`（均 `1.1.7`）。

#### ③ Modbus 与 Netty 的契合度决定选型

`com.digitalpetri.modbus:modbus-tcp:2.1.6` 的 POM 显示其依赖
`netty-buffer` / `netty-codec` / `netty-handler` + `netty-channel-fsm`，内部 Netty 版本为 `4.1.136.Final`
—— 与本仓选定的 `4.1.138.Final` 同一大版本线，由我们的 BOM 统一收敛，**不存在版本冲突**。
这意味着 Modbus 适配器可以直接复用本仓 `transport` 的 EventLoop 与 ByteBuf 池，不引入第二套网络栈。

### 2.3 OPC UA

**选型**：Eclipse Milo `1.1.7`（EPL-2.0）。

| 项 | 结论 |
|---|---|
| 模块 | `milo-sdk-client`（客户端）、`milo-sdk-server`（服务端，可做**协议模拟器**）、`milo-stack-core`、`milo-transport`、`milo-bom`（统一版本） |
| 架构 | 基于 Netty，**全异步**（`CompletableFuture`），与本仓异步 SPI 天然对齐，**无需** `BlockingProtocolAdapter` 桥接 |
| 能力 | 客户端 + 服务端；Browse / HistoryRead / Method Call / 订阅（MonitoredItem）/ 事件订阅 / 证书安全策略全覆盖 |
| 维护 | 活跃（`1.1.7` 发布于 2026-09-09） |

**对本仓的意义**：OPC UA 是**唯一一个能一次点亮全部 SPI 扩展接口**的协议——
`BrowseExtension`（目录浏览）、`HistoryExtension`（历史读）、`InvokeExtension`（Method 调用）、
`SUBSCRIBE_NATIVE`（原生订阅）都以它为首个实现者。因此 **M1 必须包含 OPC UA**：
它是检验 SPI 扩展机制是否成立的试金石。

**关键坑**：

1. 坐标迁移（见 §2.2 ②）；
2. 安全策略与证书：`Basic256Sha256` 以上需要双向证书交换，现场首次连接失败多因证书信任列表或时间戳超窗（**时间偏差 > 15 分钟直接拒绝**）；
3. Endpoint 发现返回的 URL 常是设备内网地址，跨 NAT 接入时必须显式覆盖（`EndpointUtil` / 手动指定 endpoint URL）；
4. MonitoredItem 的数量与采样间隔受服务端限制，批量订阅要分批并处理 `BadTooManyMonitoredItems`；
5. 大节点树浏览必须分页 + 限深，否则一次 Browse 可能拉回十万节点。

### 2.4 OPC DA

**结论：Java 无法原生访问，必须外置桥接进程。**

- COM/DCOM 是 Windows 专有，JVM 内没有原生通道。
- 桥接候选 `org.jinterop:j-interop:2.0.4`（DCOM over pure Java），但版本陈旧、维护停滞，
  **许可证本次未验证**（J-Interop 历史版本为 LGPL），引入前必须法务确认。

**推荐方案（方案 A）**：

```
PLC/OPC DA Server (Windows)
        │  COM/DCOM
        ▼
 Windows 侧桥接进程（独立部署，非本仓代码）
        │  本地 TCP / gRPC / OPC UA
        ▼
 ypbin-iot-protocol-opcda  ← 本质是一个普通 TCP 适配器
```

**备选方案（方案 B）**：直接建议客户迁移到 OPC UA（业界主流，多数 DCS/PLC 已支持）。

> ⛔ **按 D2 已移出规划**：OPC DA 需要在 Windows 侧部署 COM 桥接进程，个人项目既无 Windows 环境也无 OPC DA 服务器，**无法验证**。
> 方案（方案 A 外置桥接 / 方案 B 建议迁 OPC UA）完整保留于此，将来有环境时可直接实施。

### 2.5 西门子 S7

**选型**：`org.apache.plc4x:plc4j-driver-s7:1.0.0`（Apache-2.0）。

| 备选 | 版本 | 评价 |
|---|---|---|
| `com.github.s7connector:s7connector` | `2.1`（**2018-08-06**） | ⛔ 已停更 8 年，不使用 |
| 自研 S7comm | — | 仅当 PLC4X 实测不满足时的兜底（S7comm 有公开规范） |

**关键坑**：

1. **S7-1200/1500 必须显式开启「允许来自远程对象的 PUT/GET 通信访问」**，且数据块必须**关闭「优化的块访问」**——
   这两条是现场最常见的连不上原因，必须在文档中前置说明并做成诊断提示；
2. S7-1200/1500 使用 **S7comm-plus**（带 TLS 与认证），与 S7-300/400 的 S7comm 是两套协议，
   能力声明与配置项需区分；
3. 地址语法（PLC4X 风格 `%DB1.DBW0:INT`）需在适配器内做一层归一，宿主侧仍以 admin 的点位模板为准；
4. 连接数上限：S7-1500 的并发连接数有限（通常 8~16），**同一 PLC 必须走连接复用**——
   这正是本仓「连接/设备两级模型」的价值所在（§DESIGN 2.4 / ADR-04）。

### 2.6 Modbus

**选型**：`com.digitalpetri.modbus:modbus-tcp:2.1.6` + `modbus-serial:2.1.6`（EPL-2.0）。

| 备选 | 坐标 | 版本 | 许可 | 评价 |
|---|---|---|---|---|
| j2mod | `com.ghgande:j2mod` | `3.4.0`（2026-09-06） | **Apache-2.0** | ⚠️ 活跃、许可宽松，且**是唯一支持 ASCII 的现成库**；但按 **D4 未采用**（理由见下方三选项对比） |
| PLC4X | `org.apache.plc4x:plc4j-driver-modbus` | `1.0.0` | Apache-2.0 | ✅ 可用，但与 Netty 生态契合度不如 digitalpetri |
| jamod | `net.wimpi:jamod` | `1.2`（**2012**） | — | ⛔ 停更 14 年 |

**首选理由**：基于 Netty、异步 API、作者与 Milo 同一人（Kevin Herron），代码风格与质量一致；
对本仓而言可以直接复用 EventLoop 与 ByteBuf 池。

**必须覆盖的四种封装**：Modbus TCP（MBAP）、Modbus RTU（串口）、**Modbus RTU over TCP**（现场极常见）、Modbus ASCII。
`SUBSCRIBE_POLLING` 能力由框架轮询实现，适配器负责**地址连续性切分**
（单次读上限 125 寄存器 / 2000 线圈，不连续地址需拆分为多次 PDU 请求后合并结果）。

**⚠️ ASCII 支持是选型的决定性分水岭（实测结论）**

我们对两个候选库的**源码制品**做了直接核查，结论与常见说法不一致：

| 库 | 版本 | ASCII 支持 | 核查方式 |
|---|---|---|---|
| `com.digitalpetri.modbus:modbus` | `2.1.6` | ❌ **不支持** | 拉取 `modbus-2.1.6-sources.jar`，89 个源文件中**零处**包含 `ASCII`；模块清单只有 `modbus-tcp` / `modbus-serial`（RTU），**无 ASCII 模块** |
| `com.ghgande:j2mod` | `3.4.0` | ✅ **支持** | 116 个源文件中 9 处包含 `ASCII`，存在 `com.ghgande.j2mod.modbus.io.ModbusASCIITransport` |

**因此 Modbus 有三种可选路径，不是只有"引两套库"一条**（详见下方"三选项"）。

**digitalpetri 的复用边界（源码实测，决定自研成本）**

我们进一步拆了 `modbus:2.1.6` 的源码，边界非常清楚：

| 层 | 能否复用 | 依据 |
|---|---|---|
| **PDU 模型**（全部功能码 Request/Response） | ✅ 完全复用 | 模块含 `ModbusPdu` / `ModbusRequestPdu` / `ModbusResponsePdu` + `ReadHoldingRegisters*` / `WriteSingleRegister*` 等全部功能码类（2.x 已把 1.x 的 `modbus-codec` 合并进来，`modbus-codec` 只存在于 ≤1.2.2） |
| **PDU 编解码** | ✅ **可插拔** | `ModbusRtuClient` 内 `<code>config.responseSerializer().decode(functionCode, buffer)</code>` —— 序列化器由 `ModbusClientConfig` 提供 |
| **类型化便捷方法** | ✅ 完全复用 | `readHoldingRegisters(...)` 等 10 个方法定义在抽象基类 `ModbusClient`，统一委托 `send()` |
| **异常体系 / 执行队列** | ✅ 完全复用 | `ModbusTimeoutException` / `ModbusResponseException` / `ExecutionQueue` |
| **帧构造与事务匹配** | ❌ 必须自己写 | 帧构造在子类：`ModbusRtuClient.sendAsync` 内 `transport.send(new ModbusRtuFrame(unitId, pdu, crc))`；`ModbusClient` 的 `send()` 只做同步包装，`sendAsync` 是抽象点 |
| **串口字节通道** | ❌ 必须自己写 | `SerialPortClientTransport` 硬编码 `ModbusRtuResponseFrameParser` 与 `ModbusRtuFrame`，无法直接改作 ASCII |
| 传输层接口 | ✅ 天然可扩展 | `ModbusClientTransport<T>`（`connect`/`disconnect`/`isConnected`/`send(T)`/`receive(Consumer<T>)`）**按帧类型泛型化**，实现 `ModbusClientTransport<ModbusAsciiFrame>` 即可接入 |

**三个选项对比**

| 选项 | 做法 | 生产代码量 | 优点 | 缺点 |
|---|---|---|---|---|
| **A. 引 j2mod** | TCP/RTU 用 digitalpetri，ASCII 用 j2mod | ~0（开箱即用） | `com.ghgande.j2mod.modbus.io.ModbusASCIITransport` 现成 | ① **一个模块内两套 Modbus 协议栈**，事务语义/超时/异常码映射不一致，TCK 要写两套；② j2mod 是阻塞式 API → ASCII 被迫走 `BlockingProtocolAdapter`，与 TCP/RTU 的异步风格**分裂**；③ 必须排除 `log4j-core:2.25.4` + `slf4j-reload4j` |
| **B. 自研 ASCII 传输层** | 复用 digitalpetri 的 PDU/异常/基类，只实现 ASCII 帧、`sendAsync`、串口通道 | **~500 行**（+ ~200 行测试） | ① 与 TCP/RTU **共享同一套 PDU、异常与事务语义**，TCK 一套即过；② 不引入第二套协议栈；③ 无 log4j 污染；④ 保持异步风格统一 | ① 要实现 ASCII 帧状态机（`:` 起始 + hex + LRC + CRLF + **1s 字符间超时**，与 RTU 的 3.5 字符静默完全不同）；② 要自己封装 jSerialComm 串口通道 |
| **C. 现场切 RTU** | 不做 ASCII | 0 | 最省事；**大多数设备同时支持 RTU 与 ASCII**，改设备配置即可 | 依赖现场设备可配置 |

**选项 B 的工作量拆分（估）**：

| 组件 | 行数 |
|---|---|
| `ModbusAsciiFrame` record（`unitId` + `pdu` + `lrc`） | ~30 |
| ASCII 编解码（PDU → hex → `:`…CRLF）+ LRC 计算 | ~60 |
| ASCII 帧状态机（累积器 + 字符间超时 + 异常恢复） | ~120 |
| `ModbusAsciiClientTransport`（jSerialComm 串口通道 + 状态机 + `ExecutionQueue`） | ~180 |
| `ModbusAsciiClient extends ModbusClient`（实现 `sendAsync`） | ~100 |
| **生产代码合计** | **~500** |

> **✅ 决策 D4（已拍板）：M1 只做 TCP/RTU；ASCII 延到 M3，按选项 B 自研实现，不引 j2mod。**
> 对外口径：**优先建议现场切 RTU**（选项 C），ASCII 作为兼容能力而非主推。
>
> 理由：M1 的目标是「打透 SPI 抽象」，而 ASCII **不贡献任何新抽象维度**（它与 RTU 共享 PDU，
> 差异只在帧壳），把它塞进 M1 只会增加风险而不增加信息量。
> 而一旦要做，选 B 优于选 A——**在一个模块里维护两套 Modbus 协议栈的长期成本，
> 远高于一次性写 500 行帧层**。

**⚠️ 一处必须澄清的因果（修正早期表述）**：

> 「自研」**并不能**规避 `jSerialComm` 的 LGPL-3.0——串口 I/O 无论如何都要用串口库
> （`SerialPortClientTransport` 用的就是 `com.fazecast.jSerialComm`，`j2mod` 亦然）。
> 自研 ASCII 的真正收益是**架构一致性**（共享 PDU 与事务语义、不引入第二套协议栈、无 log4j 污染），
> **不是许可**。若目标是零 LGPL，唯一途径是连串口 I/O 也自研（JNI 直调 termios），成本极高，需商务确认。

**⚠️ 串口许可约束是被依赖锁定的，不是自由选择**

无论选 A 还是选 B，串口实现**都直接依赖 `com.fazecast:jSerialComm:2.11.4`（LGPL-3.0）**——
选项 B 自研 ASCII 时，串口通道仍要基于它：

```
digitalpetri modbus-serial 2.1.6  ──►  com.fazecast:jSerialComm
j2mod 3.4.0                       ──►  com.fazecast:jSerialComm:2.11.4
选项 B（自研 ASCII 传输层）        ──►  com.fazecast:jSerialComm（自己封装）
```

这意味着 §4.5 中「默认 purejavacomm（BSD-3）以规避 LGPL」的想法**在 Modbus 路径上无法实现**。
**决策**：接受 jSerialComm 的 LGPL-3.0（动态链接闭源分发可接受），登记进许可证白名单的「LGPL 动态链接」档；
若客户对 LGPL 零容忍，则 Modbus RTU/ASCII 单独立项（连串口 I/O 一起自研），成本显著上升，需商务确认。

**仅在选 A（引 j2mod）时才需要处理的传递依赖**：`j2mod:3.4.0` 会带入 `log4j-core:2.25.4` + `slf4j-reload4j`。
本仓统一 SLF4J，若引入 j2mod **必须 `<exclusions>` 掉 log4j-core 与 slf4j-reload4j**，
否则会引入第二套日志实现（且是历史上出过重大漏洞的那一套）。
按 **D4 决策我们不引 j2mod**，因此该风险在 M1/M3 均不出现——但仍记录于此，供将来重新评估时参考。

**关键坑**：

1. **地址基准（0-based / 1-based）与寄存器区标识混乱**：`40001` / `400001` / `0` / `holding:0`
   各厂商文档与工具写法不同，必须在适配器内置显式基准配置项，**不做自动猜测**；
2. 从站地址（Unit ID）是**设备级**参数，一条 TCP 链路承载多从站 —— 这是连接复用的主场景；
3. 网关设备常见「TCP 连接数上限 1~4」，多设备必须复用同一条链路；
4. 串口场景**不能并发**：同一串口上的请求必须串行化（RS-485 半双工），
   恰好由本仓会话级串行队列 + `maxPendingRequests` 覆盖；
5. **RTU over TCP** 与 **RTU** 的差别只在承载层，编解码相同，但**不能与 MBAP 混用同一个端口**，
   封装类型必须显式配置（选错的现象是「连上了但一直超时」）。

### 2.7 EtherNet/IP

**选型**：`org.apache.plc4x:plc4j-driver-eip:1.0.0`（Apache-2.0）。

> ⚠️ **别用错坐标**：`plc4j-driver-ethernet-ip` 是旧名，**停在 `0.6.0`（2020-03-01）**；
> 1.0.0 线的正确坐标是 `plc4j-driver-eip`。

**关键坑**：

1. CIP 对象模型是 `Class / Instance / Attribute` 三元组，与「点位」不是一一对应，
   PLC4X 提供了标签式与原生双语法，宿主侧点位模板需明确采用哪一套；
2. 标签访问（symbolic）依赖设备支持且需要 EDS 文件或在线标签发现，
   不同厂商（Rockwell / Omron / Schneider）实现差异大，**必须真机实测**；
3. 路由路径（`Backplane / Slot`）在 ControlLogix 机架上是必填项，配置缺失是最常见失败原因。

### 2.8 PROFINET

**结论：无可行 Java 方案，只能网关转换。**

- Maven Central 无 PROFINET 主站/从站 Java 实现，PLC4X 也没有 profinet 驱动。
- PROFINET 的实时性依赖以太网链路层（RT/IRT 需要专用硬件与交换机配置），JVM 无法满足确定性时延。
- **推荐做法**：现场经 **PROFINET ↔ Modbus TCP** 或 **PROFINET ↔ OPC UA** 网关汇聚，本仓只接网关。
- 在对外口径上，PROFINET 标注为「**需硬件网关，本仓不直连**」，不做技术承诺。

### 2.9 CAN / CANopen / J1939

**选型**：`tel.schich:javacan-core:3.5.2` + `tel.schich:javacan-tools:3.5.2`（**MIT**）。

| 备选 | 坐标 | 版本 | 许可 | 评价 |
|---|---|---|---|---|
| PLC4X CAN | `org.apache.plc4x:plc4j-driver-can` | `1.0.0` | Apache-2.0 | ✅ 可用，能力偏「原始帧收发」 |

**关键坑**：

1. **必须 Linux**：JavaCAN 是 Linux 内核 SocketCAN API 的 JNI 封装，Windows/macOS 不可用；
2. 需要先由 `can-utils` / `ip link` 配置好 `can0` / `vcan0`，容器内需要 `--privileged`
   或 `CAP_NET_ADMIN`（生产建议 `CAP_NET_ADMIN` + 显式设备挂载，不要用 `--privileged`）；
3. **CANopen 与 J1939 是应用层协议，Java 侧没有成熟库**——
   本仓只提供 CAN 帧收发与适配器骨架，CANopen 对象字典（SDO/PDO/NMT）与 J1939 参数组需按需自研或引入 C 栈；
4. native 库加载失败必须给出明确诊断（内核未启用 SocketCAN / 无权限 / 缺 native），
   **不得**让 `UnsatisfiedLinkError` 裸奔到用户面前（见 DESIGN §6 E6）。

### 2.10 HART

**结论：Central 零制品，自研 HART-IP；物理层走网关。**

- Maven Central 检索 `hart` → **`numFound = 0`**。
- **HART-IP**（基于 UDP/TCP 的 HART 承载）报文结构简单（定长头 + 命令号 + 数据域 + 状态字节），
  自研成本与 GB/T 26875 相当，属于可控范围。
- **HART 的 FSK 物理层**（4–20 mA 回路上的 Bell 202 频移键控）需要 HART 调制解调器硬件
  （如 HART 猫 / HART-IP 网关），Java 侧只能经串口或 HART-IP 接入，**不做物理层**。
- 工程对策：先做 HART-IP 适配器（覆盖主流 HART-IP 网关与多路复用器），
  串口调制解调器场景按客户需求再评估（需接入 §4.5 的串口能力 + HART FSK 帧时序）。

### 2.11 长尾协议清单（按需评估，不进标准 Starter）

这些协议**当前不立项**，登记在此的目的是：客户提出需求时不必从零调研，且能第一时间给出「能不能做」的明确答复。

**A 组：无 Java 实现，只能网关转换**（与 PROFINET 同理）

| 协议 | 领域 | 现实情况 | 推荐接入路径 |
|---|---|---|---|
| **EtherCAT** | 工业 | 无 Java 主站/从站实现。EtherCAT 依赖专用 ESC 芯片与实时以太网，JVM 无法满足确定性时延 | EtherCAT ↔ Modbus TCP / OPC UA 硬件网关 |
| **IO-Link** | 工业 | 无成熟 Java 库。IO-Link 是点对点传感器总线，需 IO-Link Master 硬件 | IO-Link Master ↔ PROFINET / EtherNet/IP / OPC UA，再经本仓接入 |
| **DALI** | 楼宇 | 无成熟 Java 库。DALI 是照明专用总线，需 DALI 网关/控制器 | DALI-2 网关 ↔ KNX / BACnet |
| **M-Bus** | 楼宇（计量） | `org.openmuc:jmbus:3.3.0` 存在但**停更于 2020-06-17**，且为 **MPL-2.0**（弱 copyleft，需入白名单） | 优先 M-Bus Master ↔ Modbus / OPC UA；若必须直连，评估 jmbus 并接受停更风险 |
| **Zigbee / Z-Wave** | 通用 | 无成熟 Java 库。二者都需要专用射频协调器 | 协调器网关 ↔ MQTT / HTTP |
| **JT/T 1078** | 监控（车载） | 无成熟 Java 库，与 GB28181 并列的行业标准（道路运输车辆卫星定位系统视频通信协议） | 自研（协议结构与 GB28181 信令面相似，可复用其 SIP/RTP 基础设施） |

> ⚠️ **M-Bus 的许可提醒**：MPL-2.0 是**文件级弱 copyleft**——修改 MPL 覆盖的源文件需回馈该文件，
> 但可以作为更大作品的一部分分发。许可证门禁需为 MPL-2.0 单列一档。

**B 组：库成熟，但「是否属于设备接入」需要先定义清楚**

| 协议 | 候选坐标与版本 | 许可 | 待澄清的问题 |
|---|---|---|---|
| **gRPC** | `io.grpc:grpc-netty-shaded:1.84.0` | Apache-2.0 | 与已规划的 HTTP/WebSocket 适配器**能力重叠**。只有当客户设备确实以 gRPC 作为原生接入协议（如部分边缘网关）时才立项，否则属于重复建设 |
| **AMQP 1.0 / 0.9.1** | `com.rabbitmq:amqp-client:5.35.0`（0.9.1）、`org.apache.qpid:qpid-jms-client:2.11.0`（1.0） | Apache-2.0 | 部分 IoT 平台用 AMQP 做设备接入。**但本仓已有 MQTT 覆盖同类场景**，且 AMQP 的「设备」语义（队列/交换机 vs 点位地址）与点位模型差异大，需要先明确映射规则再立项 |

**C 组：作为既有协议的补充说明（不新增模块）**

| 事项 | 处理 |
|---|---|
| **RTSP** | **不新增模块**，在 §3.7 ONVIF 内补充「RTSP 流地址的获取与分发」说明（ONVIF 的 `GetStreamUri` 返回 RTSP 地址，取流本身交给外置流媒体服务） |
| **LoRaWAN** | **不新增模块**，在 §4.2 MQTT 内补充「LoRaWAN 网关的典型接入方式」（LoRaWAN 网关通常以 MQTT/HTTP 上行，本仓经 MQTT/HTTP 适配器接入即可） |
| **Modbus ASCII** | 在 §2.6 显式标注串口线（`modbus-serial`）的 ASCII 支持情况 |

---

## 3. 楼宇 / 消防 / 视频监控协议选型

> 完整论证与一手证据见 [`research/2026-09-survey-lobby-fire-video.md`](./research/2026-09-survey-lobby-fire-video.md)。

### 3.1 总览

| 协议 | 推荐库（坐标） | 最新稳定版 | 许可证 | 在 Central | 结论 |
|---|---|---|---|---|---|
| **BACnet/IP** | `com.infiniteautomation:bacnet4j` | `6.1.1`（`7.0.0-alpha.5` 预发布） | **GPL-3.0 + 可购商业授权** | ❌ 需 RadixIoT 私服 | 能力最全，但许可证与非 Central 双重约束 |
| **KNX** | `com.github.calimero:calimero-core` / `-server` / `-device` | `2.6` | **GPL-2.0 + Classpath Exception** | ✅ | Java 侧事实唯一选择 |
| **LonWorks** | **无** | — | — | — | 只能网关转换 |
| **GB/T 26875** | **无** | — | — | — | 必须自研，且标准正在换版 |
| **GB28181** | 信令：`javax.sip:jain-sip-ri` 或 `io.github.lunasaw:sip-common`+`sip-gb28181` | `1.3.0-91` / `1.8.7` | 前者未声明、后者 POM=Apache-2.0 / README=MIT（**冲突**） | ✅ | 只做信令，媒体外置 |
| **ONVIF** | 路线 A：CXF 自生成；路线 B：`fpompermaier/onvif`；路线 C：`org.homio:onvif` | `4.2.3` / 无 Central 版本 / `1.1.1` | Apache-2.0 / Apache-2.0 / MIT | A、C ✅，B ❌ | 长寿命项目选 CXF 自生成 |
| **SNMP** | `org.snmp4j:snmp4j`（+ `snmp4j-agent`） | `3.13.1`（agent `3.10.1`） | **Apache-2.0** | ✅ | 唯一无需犹豫的选择 |

### 3.2 BACnet/IP

**推荐**：`com.infiniteautomation:bacnet4j`（上游主线由 RadixIoT 维护），坐标在 5.0 之前为 `com.serotonin:bacnet4j`（已过时）。

| 项 | 结论 |
|---|---|
| 版本 | 稳定最新 `6.1.1`；官方 metadata 的 `<release>` 为 `7.0.0-alpha.5`（预发布） |
| 制品仓库 | **不在 Maven Central**，需加 `https://maven.mangoautomation.net/repository/ias-release/` |
| 许可证 | **GPL-3.0**，README 明示可购商业授权（闭源商用**必须**购买） |
| 能力 | 纯 Java；BACnet/IP v4、IPv6、MS/TP；含 LocalDevice 与完整对象模型（可做服务端）、BBMD、Foreign Device、COV |
| API 风格 | 3.0 起全面改为 Promise/Future + 监听器，非阻塞 |
| JDK 基线 | **官方未声明**（README 用 `var`，推 ≥10）。需在 JDK 21 上先做冒烟实测 |

**⚠️ 版本锁 6.1.1，暂缓 7.0.0**：7.0.0 的 release note 明示大量破坏性变更——
对象必须显式 `localDevice.addObject(...)`、`Unsigned32` 取代 `UnsignedInteger`（既有取数代码会 `ClassCastException`）、
分段传输按 135-2020ch-1 重写、`Max_Segments_Accepted` 默认值变更、解码更严格。
6.x 是成熟稳定线，7.x 才是对齐 135-2024 / BACnet-SC 的新规范线。

**备选**：

- `org.apache.plc4x:plc4j-driver-bacnet`（**Apache-2.0**，Central 有）—— 但 PLC4X 定位是采集侧驱动，
  **未见服务端/设备对象模型能力**。若只做数据采集且必须 Apache-2.0，可评估。
- ⛔ **不要用** `net.solarnetwork.external:...bacnet4j`（POM 自称 Apache-2.0 但上游是 GPL-3.0，
  **许可声明冲突，法务风险高**）；`org.code-house.bacnet4j:api` 只是 OSGi wrapper，不是替代品。

**✅ 落地决策（D1 修订：个人项目下解禁，可用）**

个人项目无闭源交付约束，因此 **BACnet4J 的 GPL-3.0 不再构成拒绝理由**，可以直接使用。
但仍需做三件事（成本极低，收益是将来不踩雷）：

| 事项 | 动作 |
|---|---|
| **许可登记** | 模块 `README.md` 与自动生成的 `LICENSE-RISK.md` 标注「本模块引入 GPL-3.0 依赖」 |
| **制品获取** | 仍不在 Maven Central，需加 `https://maven.mangoautomation.net/repository/ias-release/`；建议**自建 Nexus 代理冻结版本**（避免上游删版导致构建不可复现——这与法律无关，是工程可复现性） |
| **版本锁定** | 锁 **`6.1.1`**，暂缓 `7.0.0`（破坏性变更多，见上方版本说明） |

**⚠️ 一条必须记住的将来约束**：若某天把这个项目用于**闭源发布或商用**，
BACnet4J 的 GPL-3.0 会让整个分发物受 GPL-3.0 约束。届时只有三条路：
按 GPL 开源、去掉该模块、或购买商业授权（RadixIoT 提供）。
**现在记在 `LICENSE-RISK.md` 里，比那时才发现便宜得多。**

> **为什么仍然不自研 BACnet 协议栈**：BACnet/IP 的复杂度集中在**对象模型与 BIBB 服务**
> （数百个对象类型与属性、COV 订阅、BBMD/Foreign Device 路由、分段传输），而不是报文编解码。
> 自研的隐性成本远高于直接用 BACnet4J，且难以通过 BTL 认证测试。

### 3.3 KNX / KNXnet/IP

**推荐**：Calimero，客户端与服务端能力都齐。

| 坐标 | 用途 |
|---|---|
| `com.github.calimero:calimero-core:2.6` | KNXnet/IP 客户端、TP/FT1.2/RF/USB 接入、cEMI、DPT 编解码 |
| `com.github.calimero:calimero-server:2.6` | **KNXnet/IP 服务端**（KNX IP / USB / FT1.2 / TP-UART） |
| `com.github.calimero:calimero-device:2.6` | 设备/组对象模型（虚拟 KNX 设备） |
| `com.github.calimero:calimero-rxtx:2.6` | 串口接入，**带 jrxtx native 依赖，平台相关** |

- 许可证：**GPL-2.0 + Classpath Exception**（弱 copyleft：链接使用一般可接受，修改库体需回馈源码）。是否有官方商业授权渠道**未验证**。
- JDK 基线：**JDK 17**（Gradle module metadata 明确 `org.gradle.jvm.version = 17`）。JDK 21 未官方声明。
- 维护：活跃（2026-09-13 仍有提交）。

**✅ 落地决策（D1）：可引入，但必须显式声明许可边界。**

`ypbin-iot-protocol-knx/README.md` **必须**包含以下声明（缺一不可）：

1. **Calimero 采用 GPL-2.0 + Classpath Exception**；
2. **Classpath Exception 的含义**：链接使用 Calimero 的应用可以采用任何许可证（含闭源），不要求开源；
3. **禁止修改 Calimero 库源码**——修改后受 GPL-2.0 传染，必须回馈源码；
   需要定制时改为在外层包装（本仓的适配器模式天然支持）；
4. **Calimero 是否有官方商业授权渠道未经验证**，闭源商用前建议自行确认。

**⚠️ 一处必须澄清的常见错误**：有说法称「Calimero 的 C/C++ JNI 串口部分采用 LGPL」——
**该说法不成立**。实测 `calimero-rxtx:2.6` 的 POM 声明的仍是
`GNU General Public License, version 2, with the Classpath Exception`；
它实际依赖的是 `com.neuronrobotics:nrjavaserial:5.2.1`（**Apache-2.0**），而**不是 jrxtx**。
LGPL-2.1 的 `org.openmuc:jrxtx:1.0.1` 属于 **OpenMUC** KNX 驱动路径（`openmuc-driver-knx`，GPL-3.0），
与 Calimero 无关。**选型时一律以制品 POM 为准，不采信二手描述。**

**接入方式必须在配置上显式区分**：

| 模式 | 说明 | 适用 |
|---|---|---|
| **tunneling** | 单播，连 KNXnet/IP 网关 | ✅ 跨网段 / 云侧接入**必须**选它 |
| **routing** | 多播 `224.0.23.12` | 仅同网段可用，跨网段常被禁 |

**备选**：`li.pitschmann:knx-core:0.5.1`（**已停滞 4 年**）；`org.openmuc.framework:openmuc-driver-knx`（**GPL-3.0** 且依赖更旧的 Calimero 2.3，仅作参照）。
若要求纯 Apache/MIT 许可，Java 侧**没有合格替代品**。

### 3.4 LonWorks

**结论：放弃 Java 直连，改为网关转换。**

- Java 实现：Maven Central 检索 `lonworks` → **零制品**。
- 唯一活跃的开源 LON 栈是 C 语言（`izot/lon-stack-dx`，MIT，实现 ISO/IEC 14908），配套 `izot/lon-driver`。
- **生态退场证据**：Gesytec 2025-11 客户公告——因「客户普遍计划中期淘汰 LONWorks」且
  Echelon/Renesas 的收发器等关键器件停产，**将退出 LON 业务**；含原始 Neuron 芯片的产品停产；
  例外供货窗口最长至 2029 年。
- 历史路径（均不建议）：i.LON 100/SmartServer 的 SOAP API（现行可采购性未验证）；
  OpenLNS/OpenLDV COM API（仅 Windows 32 位，Java 需 JACOB/JNA）；Tridium Niagara（商业闭源）。

**推荐做法**：现场 LON 设备经 **LON ↔ BACnet/IP** 或 **LON ↔ Modbus TCP** 网关汇聚，
Java 侧只对接 BACnet（§3.2）或 Modbus（§2）。把 LON 的复杂度与硬件生命周期风险留在网关厂商侧。
**不建议**新项目自研 LON 栈，也不建议在 Windows 上重建 COM 桥。

### 3.5 GB/T 26875（城市消防远程监控）

**结论：必须自研，且必须按「换版双轨」设计。**

- 开源实现：Maven Central 检索 `26875` → **零制品**。GitHub 全站仅 32 个相关仓库，绝大多数无关。
- 可参考（**只借结构，不借代码**）：
  - Gitee `yanboot-iot-community/analysis-of-gbt26875-protocol`（Java + MIT，**仅 3 次提交、0 star**）→ 报文结构参考；
  - GitHub `shootingfans/codec_gb26875_3_2011`（**Go + GPL-3.0**）→ 字段交叉验证，语言与许可都不合适。

**⚠️ 最大风险：标准族正在整体换版**

| 标准号 | 名称 | 状态 | 关键日期 |
|---|---|---|---|
| GB/T 26875.3-2011 | 报警传输网络通信协议 | **现行** | 2025-07-01 复审结论为「**修订**」 |
| 20252122-T-906 | 第3部分：用户信息传输装置与应用支撑平台通信协议（修订计划，名称范围已变） | 正在审查 | — |
| **GB 26875.9-2026** | 第9部分：用户信息传输装置（**强制性**） | 即将实施，**全部代替 GB 26875.1-2011** | **2026-02-27 发布 / 2027-03-01 实施** |
| GB/T 26875.1-2026 | 第1部分：通用技术要求 | 已发布 | — |
| GB/T 26875.10-2026 | 第10部分：消防设施信息采集装置及接口要求 | 已发布 | — |

**工程对策**：

1. 编解码层与业务层**彻底解耦**；
2. 协议版本**可插拔**（策略 + 版本号路由），2011 版与新版共存；
3. 用标准附录样例做**黄金报文测试**；
4. **不要**把 2011 版字段写死进实体与数据库——否则 2027-03-01 强制实施时需重写业务。

### 3.6 GB28181（视频监控国标）

**架构结论（最重要）：Java 进程只做 SIP 信令，媒体面外置。**

主流国标平台的 Java 侧**零媒体依赖**，RTP 收流与 PS 解复用交给独立流媒体服务（C/C++）。
这是最稳的路线，天然规避 native 与 HEVC 问题。

| 层 | 选型 | 版本 / 许可 | 关键坑 |
|---|---|---|---|
| **SIP 栈** | `javax.sip:jain-sip-ri` | `1.3.0-91`（**2021 年后事实停更**）；许可证**未声明** | ① 依赖 `jain-sip-api` 为 `provided` → **必须显式引入 API 包**，否则运行期 `NoClassDefFoundError`；② 依赖 **Log4j 1.2.14** → 需 `log4j-over-slf4j` 桥接；③ 字节码 1.7 |
| **现成国标框架** | ✅ `io.github.lunasaw:sip-common` / `sip-gb28181`（**D1 修订：采用**） | `1.8.7` | ① POM=Apache-2.0 / README=MIT **两者都是宽松许可**，不影响个人使用；② 传递依赖很重（jain-sip + dom4j + Spring Cache + Micrometer + SkyWalking + Guava + Caffeine）→ **必须 `<exclusions>` 裁剪**；③ **必须与业务同 JVM**（`ServerTransaction`/`Dialog` 不可序列化），多节点强依赖 Redis |
| **媒体（默认）** | 外置流媒体服务 | — | ✅ 推荐 |
| 媒体（纯 Java 兜底） | `org.jcodec:jcodec` | `0.2.5`（**2019 后停更**） | 支持 MPEG-PS/TS demux，**无 H.265/HEVC**，而 2022 版国标与新型 IPC 大量使用 H.265 |
| 媒体（native 兜底） | `org.bytedeco:javacv` + `ffmpeg` | `1.5.14`（内含 FFmpeg 8.1.2） | **必须按平台裁剪**，`-platform` 聚合包约 **1.30 GB** |
| RTP | **Netty 自建** | `4.1.138.Final` | Java 侧无权威且仍在维护的通用 RTP 库；国标只需 RTP 头解析 + 抖动缓冲 + 丢包统计 + PS 组装 |

**协议差异坑**：

- 2016 与 GB/T 28181-2022 在命令集、SDP、TCP 主动/被动、H.265 要求上均有差异 →
  **以 2022 版为基线并保留 2016 兼容开关**；
- NAT 场景必须正确设置 `external-ip`/`external-port` 写入 `Via`/`Contact`；
- 设备侧 INVITE 受 **Timer B = 32s** 约束，业务回包要控制在 30s 内或先回 180/200。

**✅ 落地决策（D1 修订：恢复使用现成框架，不自建信令层）**

原先「暂不使用」的理由是**许可声明冲突**（POM=Apache-2.0 / README=MIT）。
修订后：**这两个都是宽松许可，"冲突"只影响法务确定性，不影响个人使用**。
自建 SIP 信令层能省下的只有法务确定性，却要花 4 周左右——**不值得**。

**因此 M4 的 GB28181 直接使用 `io.github.lunasaw:sip-common` + `sip-gb28181:1.8.7`**，工期回到 4~6 周。

引入时必须做的三件事：

| 项 | 动作 |
|---|---|
| **依赖裁剪** | `sip-common` 会带进 Spring Cache + Micrometer + SkyWalking + Guava + Caffeine，用 `<exclusions>` 裁掉非必需项 |
| **日志桥接** | 它依赖 JAIN-SIP，而 JAIN-SIP 自带 Log4j 1.2.14 → 必须加 `log4j-over-slf4j` |
| **补 API 包** | 若走裸 `jain-sip-ri` 路线，`jain-sip-api` 被标为 `provided`，不补则运行期 `NoClassDefFoundError` |

**媒体面仍然外置**（这一点与许可无关，是架构决定）：Java 只做 SIP 信令，
RTP 收流与 PS 解复用交给独立流媒体服务，理由见 §3.6 表。

> **若将来重新考虑自建**：原自建方案（`jain-sip-ri` + 显式补 `jain-sip-api` + `log4j-over-slf4j`，
> 覆盖 REGISTER/目录/INVITE/BYE/云台/报警/SDP）的完整拆解仍保留在上方表格中，可直接复用。

### 3.7 ONVIF

Java 侧**没有官方库**。三条路线：

| 路线 | 方案 | 许可 | 适用 |
|---|---|---|---|
| **A（推荐长寿命项目）** | Apache CXF 从 ONVIF WSDL 生成客户端：`org.apache.cxf:cxf-rt-frontend-jaxws:4.2.3`；WS-Discovery 用 JDK `MulticastSocket`/Netty 组播 `239.255.255.250:3702` | Apache-2.0 | 需要事件订阅、回放、多品牌兼容 |
| B（开箱即用但未上 Central） | `fpompermaier/onvif` | Apache-2.0 | 需 fork + 自建私服锁版本，**禁止依赖 `master-SNAPSHOT`** |
| C（Central 直取，社区极小） | `org.homio:onvif:1.1.1`（MIT，Java 21，仅 1 star） | MIT | 只需「发现 + PTZ + 取流地址」的轻功能 |

**关键坑**：

1. Profile 差异（S/T/G/M）与厂商实现偏离 → 能力发现必须走 `GetCapabilities`，**不要硬编码**；
2. **WS-UsernameToken**：`Base64(SHA1(nonce + created + password))`，
   **设备与客户端时间偏差过大会直接认证失败**（现场高频问题）；
3. WS-Discovery 多网卡主机必须显式指定组播网卡/IP；
4. 部分设备 `GetSnapshotUri` 走 HTTP Digest；
5. **`javax.*` vs `jakarta.*`**：JDK 17+ / Boot 4 用 Jakarta，老 JAXB/JAXWS 2.x 库会冲突；
6. ONVIF 时间字段 `UTCDateTime` 与本地时间混用。

**补充：RTSP 流地址的获取与分发（不新增模块）**

RTSP 与 ONVIF 是**配合关系而非并列关系**，因此**不建 `ypbin-iot-protocol-rtsp` 模块**：

| 环节 | 由谁负责 | 说明 |
|---|---|---|
| 获取流地址 | **ONVIF 适配器** | `GetStreamUri` 返回 RTSP 地址（形如 `rtsp://user:pwd@host:554/Streaming/Channels/101`），这是 ONVIF 的标准能力 |
| 播放/转发流 | **外置流媒体服务** | 本仓**不消费 RTSP 媒体流**——与 GB28181 同样的边界（§3.6） |
| 快照 | ONVIF 适配器 | `GetSnapshotUri`（部分设备走 HTTP Digest），可作为一次性 `read` 结果回传 |
| 云台控制 | ONVIF 适配器 | `PTZ` 服务，映射为 `InvokeExtension` |

> **若客户需要「取流」而不是「拿地址」**：那是流媒体平台的职责（如外置流媒体服务），
> 不在本仓范围内。本仓只保证「能通过标准接口拿到正确的流地址，并把地址交给宿主」。
>
> **RTSP over TCP 的一个现场坑**：部分设备默认 UDP 传输，在跨网段场景丢包严重；
> `GetStreamUri` 时需显式通过 `Transport` 参数协商 `RTSP/TCP`，并在配置项中暴露该开关。

### 3.8 SNMP

**推荐**：`org.snmp4j:snmp4j:3.13.1`（`snmp4j-agent:3.10.1` 用于 Trap/Agent 场景）。

- 许可 **Apache-2.0**；字节码基线 **Java 9** → 可直接跑在 JDK 21。
- 维护活跃（2026-08-02 发版）。
- 同时提供同步（`Snmp.get`）与异步（`send` + `ResponseListener`）API。

**关键坑**：

1. **v3 必须先建 USM 用户与本地引擎 ID**（`MPv3.createLocalEngineID()`），
   认证/加密协议（MD5/SHA/SHA-2、DES/AES128…）必须与设备逐项对齐，
   否则报 `Unknown user name` / `Authentication failure`；
   → 配置层必须把「版本 + 认证协议 + 加密协议 + 引擎 ID」做成**显式参数**，不做默认猜测。
2. Trap/Inform 接收需绑 **162 端口**（Linux 上 <1024 需 root 或 `setcap`）。
3. **SNMP4J 2.x → 3.x 是破坏性 API 变更**。
4. 工业设备常只支持 v2c 与小 PDU，超时/重试/PDU 大小要与之匹配。
5. 批量采集用 `GETBULK`（v2c/v3）而非逐个 `GET`。

**不推荐**：`westhawk:snmp`（2005）、`org.opendaylight.snmp`（2018，EPL）、`org.mobicents.*`（2017 前）
——均已停更十年量级；`org.sentrysoftware:snmp`（**LGPL-3.0**）许可更严。

---

## 4. 通用协议与基础组件选型

### 4.1 网络底座：Netty

| 项 | 结论 |
|---|---|
| **4.1 线（选定）** | `io.netty:netty-bom:4.1.138.Final` —— 4.1 线最新补丁版 |
| 4.2 线 | `4.2.18.Final`（**已 GA**），作为未来升级路径 |
| 5.0 线 | `5.0.0.Alpha2` —— ⛔ 不可用于生产 |
| native epoll | `io.netty:netty-transport-native-epoll:4.1.138.Final` + classifier `linux-x86_64` / `linux-aarch_64` |
| TLS 加速 | `io.netty:netty-tcnative-boringssl-static:2.0.84.Final` |
| 统一版本 | 协议模块**只引 `netty-bom`** 做 import，`netty-*` 各 artifact 不写版本 |

**决策理由**：

1. **锁 4.1.138.Final**：`com.digitalpetri.modbus:2.1.6` 内部用 `4.1.136.Final`，
   同线收敛无冲突；4.2 虽已 GA 但生态（含部分协议库的 native 依赖）尚在跟进，
   接入层作为长期运行组件不做首发尝鲜；
2. **4.2 / 5.0 的升级路径必须预埋**：`ypbin-iot-transport` 是唯一直接依赖 Netty 的模块，
   升级面被限制在一个模块内——这是分层带来的直接收益；
3. **native epoll 必开**：10 万连接下 NIO 的空轮询与系统调用开销不可接受（见 DESIGN §5.2 N2/N3）；
4. **`SO_REUSEPORT` 依赖 native epoll**，NIO 下无法使用。

### 4.2 MQTT

| 候选 | 坐标 | 版本 | 许可 | 评价 |
|---|---|---|---|---|
| **HiveMQ Client（推荐）** | `com.hivemq:hivemq-mqtt-client` | **`1.4.0`** | Apache-2.0 | ✅ 基于 Netty，支持 MQTT 5 / 背压 / 自动重连 / 共享订阅，吞吐最高 |
| Paho v5 | `org.eclipse.paho:org.eclipse.paho.mqttv5.client` | `1.2.5` | EPL-2.0 | ✅ 官方实现，功能全 |
| Paho v3 | `org.eclipse.paho:org.eclipse.paho.client.mqttv3` | `1.2.5` | EPL-2.0 | ⚠️ 仅作 MQTT 3.1.1 兼容兜底；**母仓 `ypbin-starter-messaging` 已使用它** |

**首选 HiveMQ 的理由**：设备接入侧的核心诉求是「海量 Topic × 高频点位」，HiveMQ 客户端基于 Netty、
背压内建、无 Paho 的锁竞争问题；而 Paho v3 的 `MqttClient` 是同步阻塞式，`MqttAsyncClient` 的
回调模型在十万级订阅下开销明显。

**保留 Paho v3 的理由**：① 老设备 / 老 Broker 只支持 MQTT 3.1.1；② admin 若已用母仓 messaging 的 MQTT 能力，
不必为了兼容再引入第二个 MQTT 客户端。因此**做成 `ypbin.iot.protocol.mqtt.impl` 二选一开关**。

**关键坑**：

1. **连接模型是 1 链路 N 设备**：Topic 即地址。但 MQTT 的 **Topic 与「点位地址」不是一一对应**——
   通配符订阅（`+` / `#`）会收到不属于任何已注册点位的数据，适配器必须做 Topic → 点位的路由与丢弃计数，
   否则一个 `#` 订阅就会把整个 Broker 的流量灌进 egress；
2. **QoS 选择**：QoS 2 的握手成本高，海量点位场景默认 QoS 0/1，QoS 2 做成设备级可配；
3. **会话保持**：`cleanSession=false`（MQTT 3）/ `cleanStart=false` + `sessionExpiryInterval`（MQTT 5）
   决定断线期间的消息是否补发，直接影响「重连后是否会数据洪峰」——必须显式配置并做洪峰保护；
4. **遗嘱消息（LWT）**：是设备离线检测的重要补充，但**不能替代本仓的会话状态机**；
5. 大报文（>256KB）在部分 Broker 被拒，需在适配器做分片或拒绝策略（明确报错，不静默截断）。

**补充：LoRaWAN 网关的典型接入方式（不新增模块）**

LoRaWAN 有完整的分层规范（终端 ⌁ 网关 ⌁ 网络服务器 ⌁ 应用服务器），
**Java 侧需要对接的其实是「网络服务器的北向接口」，而不是 LoRa 射频协议本身** —— 因此**不建独立模块**：

| 场景 | 接入方式 |
|---|---|
| 客户已有 LoRaWAN 网络服务器（如 ChirpStack / TTN / 运营商平台） | 经其 **MQTT 北向接口**接入本仓 `-mqtt` 适配器；点位地址映射为 `application/{appId}/devices/{devEui}/...` 形式的 Topic |
| 网络服务器提供 HTTP Webhook 上行 | 经本仓 `-http` 适配器的 Webhook 接收端接入 |
| 私有 LoRa 网关（非 LoRaWAN 规范） | 通常直接以 MQTT/TCP 上行，按对应适配器处理 |

**关键坑**：LoRaWAN 上行的 `devEui` / `fPort` / Base64 载荷需要**应用层解码**（payload decoder）。
该解码属于**业务语义**而非协议语义，因此由**宿主的 `DataSink` 侧处理**，本仓只负责把原始载荷透传——
不在 MQTT 适配器里内置任何 LoRaWAN 私有解码逻辑。

### 4.3 CoAP

| 候选 | 坐标 | 版本 | 许可 | 评价 |
|---|---|---|---|---|
| **Californium（推荐）** | `org.eclipse.californium:californium-core` | **`3.14.0`** | EPL-2.0 + Apache-2.0 | ✅ 事实标准，客户端 + 服务端 |
| DTLS | `org.eclipse.californium:scandium` | `3.14.0` | EPL-2.0 + Apache-2.0 | CoAPS（DTLS 1.2）必需 |
| 连接器抽象 | `org.eclipse.californium:element-connector` | `3.14.0` | — | 随 core 传递引入 |

> ⚠️ **不要用 4.0.0**：`californium-core` 的 4.0.0 线目前只到 **`4.0.0-M6`**（里程碑版），
> 生产必须锁 **3.14.0** 这条稳定线。

**关键坑**：

1. CoAP 跑在 **UDP** 上，与 TCP 的会话语义完全不同：
   「连接」是逻辑概念，`ProtocolConnection` 的实现需要把 UDP 端点 + 消息 ID 管理封装成会话；
2. **DTLS 的会话恢复与 MTU 分片**是现场高频问题，`scandium` 的配置项很多，需要收敛暴露；
3. **Observe（观察模式）**是 CoAP 的订阅机制，对应 `SUBSCRIBE_NATIVE`；
   但 Observe 关系会因设备重启而丢失，需要重新注册（对应 `SUBSCRIPTION_LOST` 事件）；
4. **LwM2M 是 CoAP 之上的应用层协议**（对象/资源模型 + 注册/管理接口），
   Java 侧无成熟实现 —— 本仓只做 CoAP 传输层，LwM2M 对象模型按客户需求单独立项；
5. 无连接协议下 `ping()` 的语义是「发送可确认消息」而非 TCP 保活，需在实现中明确。

### 4.4 TCP / UDP / HTTP / WebSocket（自研透传）

这四个协议**不引三方库**，直接基于 `ypbin-iot-transport` 的 Netty 底座实现，
它们是「万能兜底」：任何没有专用适配器的设备，只要能收发字节流，就能通过它们接入。

| 协议 | 实现要点 | 能力声明 |
|---|---|---|
| **TCP** | Netty 客户端；编解码由宿主提供的脚本/规则或纯透传 | `READ` `WRITE` `SUBSCRIBE_STREAM` |
| **UDP** | Netty `NioDatagramChannel` / `EpollDatagramChannel`；无连接会话抽象 | `READ` `WRITE` `SUBSCRIBE_STREAM` |
| **HTTP** | **JDK 21 内置 `java.net.http.HttpClient`**（零依赖）做轮询采集；同时提供 Webhook 接收端 | `READ` `SUBSCRIBE_POLLING`（主动推入时 `SUBSCRIBE_STREAM`） |
| **WebSocket** | **Netty 原生 WebSocket**（已在依赖内）；备选 `org.java-websocket:Java-WebSocket:1.6.0` | `READ` `WRITE` `SUBSCRIBE_STREAM` |

**HTTP 客户端的选型边界**：

| 场景 | 选型 | 理由 |
|---|---|---|
| 本仓 `transport` 层（非 Spring） | JDK 21 `HttpClient` | **零三方依赖**，core/transport 保持干净；支持 HTTP/2 与异步 |
| Spring 宿主内的业务性调用 | Spring Framework 7 `RestClient` / `WebClient` | 复用容器的消息转换器与超时配置 |
| 需要连接池 / 复杂鉴权 / 代理链 | `org.apache.httpcomponents.client5:httpclient5:5.6.4` | ⚠️ `5.7-alpha1` 是 alpha，**锁 5.6.4** |

> **超时是硬性要求**：本仓所有 HTTP/WebSocket 客户端**必须显式配置 connect / read 超时**，
> 严禁使用无超时的默认客户端（沿用母仓铁律）。

**关键坑**：

1. HTTP 作为设备接入协议时，**语义方向决定能力声明**：
   「我们轮询设备」（`SUBSCRIBE_POLLING`）与「设备推给我们」（`SUBSCRIBE_STREAM`）是两种完全不同的实现，
   配置上必须显式区分，不能靠猜；
2. WebSocket 的分片消息（continuation frame）与 ping/pong 保活需要显式处理，
   否则大报文会被误判为协议错误；
3. TCP 透传的**粘包/半包**问题：默认提供 `LengthFieldBasedFrameDecoder` 与分隔符两种开箱方案，
   自定义编解码必须通过 SPI 扩展点，**不允许宿主在适配器里写裸 `ByteBuf` 处理逻辑**；
4. UDP 的乱序与去重：工业 UDP 场景（如某些私有协议）常需要应用层序号，属于适配器职责。

### 4.5 串口（Modbus RTU / HART / 部分楼宇协议共用）

| 候选 | 坐标 | 版本 | 许可 | 评价 |
|---|---|---|---|---|
| **jSerialComm（事实强制）** | `com.fazecast:jSerialComm` | **`2.11.4`** | ⚠️ **LGPL-3.0** | 活跃、跨平台、功能最全；**被 `digitalpetri modbus-serial` 直接依赖，且 D4 自研 ASCII 的串口通道同样要用它——无法回避** |
| purejavacomm | `com.github.purejavacomm:purejavacomm` | `1.0.2.RELEASE` | ✅ **BSD-3-Clause** | 许可宽松，但版本停在 `1.0.2`，新内核/新 JDK 上问题较多 |
| Calimero rxtx | `com.github.calimero:calimero-rxtx` | `2.6` | ⚠️ **GPL-2.0 + Classpath Exception** | 仅 KNX 串口接入场景；其 JNI 串口实现实际依赖 `com.neuronrobotics:nrjavaserial:5.2.1`（**Apache-2.0**） |

**决策（含一处对早期设想的修正）**：**默认 jSerialComm**，并把 **LGPL-3.0 登记为「动态链接可接受」白名单项**
（LGPL 允许闭源程序动态链接使用，但不得修改库本体后闭源分发，且需保留版权声明）。

> ⚠️ **修正**：早期设想「默认 purejavacomm（BSD-3）以规避 LGPL」**不可行**——
> `digitalpetri modbus-serial:2.1.6` 与 `j2mod:3.4.0` 的 POM 都直接依赖 `jSerialComm`，
> 串口能力被依赖锁死。
>
> 更关键的是：**「自研」也不能规避它**（D4 澄清）。自研 ASCII 传输层时，串口 I/O 仍要基于 `jSerialComm`
> （`digitalpetri` 自己的 `SerialPortClientTransport` 就是 `SerialPort.getCommPort(...)` + `SerialPortDataListener`）。
> 要真正规避 LGPL，唯一途径是连串口 I/O 也自研（JNI 直调 termios），成本极高，需商务确认。
> **自研 ASCII 的收益是架构一致性（共享 PDU 与事务语义、不引第二套协议栈、无 log4j 污染），不是许可。**
>
> 另注：`calimero-rxtx:2.6` 的 POM 声明的是 **GPL-2.0 + Classpath Exception**，
> **不是** LGPL。LGPL 的那个（`org.openmuc:jrxtx:1.0.1`，LGPL-2.1 + linking exception）
> 属于 OpenMUC KNX 驱动路径，与 Calimero 无关——这两者常被混淆，选型时以 POM 为准。

**关键坑**：

1. **串口是独占资源且必须串行化**：同一条串口上的 Modbus 请求**不能并发**，
   适配器必须在会话层做串行队列（本仓 `maxPendingRequests` 与串行执行队列正好覆盖）；
2. 波特率 / 数据位 / 校验位 / 停止位 / 流控必须显式配置，**不做默认值猜测**（猜错表现为「偶尔能通」）；
3. 串口设备的响应超时与 TCP 不同，通常需要更长（RS-485 半双工转向有额外延迟）；
4. 容器/裸机需要设备权限（`dialout` 组或 udev 规则）；
5. USB 转串口设备的热插拔会导致 `device not found`，需要重连退避覆盖。

### 4.6 宿主侧组件选型建议（本仓**不引入**）

这些属于宿主平台职责（见 DESIGN §5.7），列出供 admin / 接入平台选型参考：

| 层 | 候选 | 坐标与版本 | 许可 |
|---|---|---|---|
| **时序库** | Apache IoTDB | `org.apache.iotdb:iotdb-session:2.0.11` / `iotdb-jdbc:2.0.11` | Apache-2.0 |
| | TDengine | `com.taosdata.jdbc:taos-jdbcdriver:3.9.1` | Apache-2.0 |
| | ClickHouse | `com.clickhouse:clickhouse-jdbc:0.10.0` | Apache-2.0 |
| | QuestDB | `org.questdb:questdb:10.0.1` | Apache-2.0 |
| | InfluxDB 3.x | `com.influxdb:influxdb-client-java:8.0.0` | MIT |
| **消息总线** | Kafka | `org.apache.kafka:kafka-clients:4.3.1` | Apache-2.0 |
| | Pulsar | `org.apache.pulsar:pulsar-client:5.0.0-M1` | ⚠️ **里程碑版，非 GA** |
| **缓存 / 影子** | Lettuce | `io.lettuce:lettuce-core:7.7.0.RELEASE` | Apache-2.0 |
| | Redisson | `org.redisson:redisson:4.7.0` | Apache-2.0 |
| **媒体（GB28181 外置流媒体）** | JavaCV + FFmpeg | `org.bytedeco:javacv:1.5.14` + `org.bytedeco:ffmpeg-platform:8.1.2-1.5.14` | Apache-2.0（**必须按平台裁剪，聚合包约 1.3 GB**） |
| **SOAP（ONVIF 路线 A）** | Apache CXF | `org.apache.cxf:cxf-rt-frontend-jaxws:4.2.3` | Apache-2.0 |

> **时序库选型建议**：优先 **Apache IoTDB**（国产、工业场景、树形测点模型与本仓 deviceId/pointId 层次天然对齐、
> Apache-2.0）；如团队已有 ClickHouse 运维能力则用 ClickHouse（写入吞吐更高，但需自建测点模型）。
> **无论选哪个，都必须批量攒批写入**（见 DESIGN §5.7 硬规则 2）。

### 4.7 通用协议模块的版本收敛原则

| 原则 | 说明 |
|---|---|
| **只引 BOM，不写版本** | Netty / 协议库版本统一在 `ypbin-iot-dependencies` 的 `<properties>` 声明 |
| **同族库统一版本线** | Modbus 与 Milo 同为 EPL-2.0 且作者相同，Netty 版本线必须一致 |
| **禁用 alpha / milestone** | Californium `4.0.0-M6`、Pulsar `5.0.0-M1`、Netty `5.0.0.Alpha2`、HttpClient `5.7-alpha1` **一律不用** |
| **传递依赖必须审** | `io.github.lunasaw:sip-common` 会带进 Spring Cache + Micrometer + SkyWalking + Guava + Caffeine，需 `<exclusions>` 裁剪 |
| **日志统一 SLF4J** | 遇 Log4j 1.x 依赖（如 JAIN-SIP）必须加 `log4j-over-slf4j` 桥接 |

---

## 5. 许可证风险与供应链约束

### 5.1 许可证风险总表

| 库 / 协议模块 | 许可证 | 对闭源商用的影响 | 处置 |
|---|---|---|---|
| **BACnet4J**（`-bacnet`） | **GPL-3.0**（可购商业授权） | 🔴 **必须**购授权，或放弃闭源 | **引入前必须完成法务评审**；否则降级为「网关转换」方案 |
| **Calimero**（`-knx`） | **GPL-2.0 + Classpath Exception** | 🟠 链接使用一般可接受；修改库体需回馈源码 | 法务确认后引入；**不得修改库源码** |
| **jSerialComm**（`-modbus` / `-hart` 串口） | **LGPL-3.0** | 🟠 动态链接闭源分发可接受；修改库体需回馈 | 登记为「LGPL 动态链接白名单」；**注意它被 `digitalpetri modbus-serial` 强制依赖，D4 自研 ASCII 也绕不开**（§2.6） |
| **`org.openmuc:jmbus`**（M-Bus 长尾） | **MPL-2.0** | 🟢 文件级弱 copyleft：改 MPL 覆盖的源文件需回馈该文件，可作为更大作品的一部分分发 | 需为 MPL-2.0 单列白名单档；该库**停更于 2020-06**，仅在 M-Bus 直连硬需求时评估 |
| `com.github.purejavacomm:purejavacomm` | **BSD-3-Clause** | 🟢 | 可用，但**无法用于 Modbus 串口路径**（被依赖锁死，见 §4.5） |
| `com.neuronrobotics:nrjavaserial`（Calimero 串口传递依赖） | **Apache-2.0** | 🟢 | 可用 |
| `io.grpc:grpc-netty-shaded` / `com.rabbitmq:amqp-client` / `org.apache.qpid:qpid-jms-client` | **Apache-2.0** | 🟢 | 许可无碍；**是否立项取决于业务定义**（§2.11 B 组） |
| `org.jinterop:j-interop`（OPC DA 桥） | **未验证**（历史版本为 LGPL） | 🟠 | 引入前必须确认；优先走「外置桥接进程」避免引入 |
| `io.github.lunasaw:sip-*`（`-gb28181`） | **POM=Apache-2.0，README=MIT（不一致）** | 🟠 声明冲突 | ⚠️ 商用前**必须**与上游确认，或改用 jain-sip-ri 自建信令层 |
| `javax.sip:jain-sip-ri` | **未声明** | 🟠 需按 JAIN-SIP/NIST 原始条款确认 | 引入前确认 |
| `net.solarnetwork.external:...bacnet4j` | POM 自称 Apache-2.0，**上游实为 GPL-3.0** | 🔴 声明冲突 | ⛔ **禁止引入** |
| `org.sentrysoftware:snmp` | LGPL-3.0 | 🟠 | 不采用（已有 Apache-2.0 的 SNMP4J） |
| `org.openmuc.framework:openmuc-driver-knx` | **GPL-3.0** | 🔴 高风险 | ⛔ 仅作参照，不引入 |
| `shootingfans/codec_gb26875_3_2011` | **GPL-3.0**（且为 Go） | 🔴 | ⛔ 只读思路，不引入 |
| **PLC4X**（`-s7` / `-ethernetip` / `-can`） | **Apache-2.0** | 🟢 | 可用（**逐项核验**） |
| **Milo**（`-opcua`） | **EPL-2.0** | 🟢 弱 copyleft，链接使用无义务；修改 EPL 部分需回馈 | 可用；**不得修改 Milo 源码后再分发** |
| **digitalpetri modbus**（`-modbus`） | **EPL-2.0** | 🟢 同上 | 可用 |
| **JavaCAN**（`-can`） | **MIT** | 🟢 | 可用 |
| **j2mod**（Modbus ASCII 备选，**按 D4 未采用**） | **Apache-2.0** | 🟢 许可无碍 | 曾作为 ASCII 的现成方案；**D4 决定改为自研帧层**，理由见 §2.6。若将来重新评估，须排除其 `log4j-core` 传递依赖 |
| **SNMP4J** / **CXF** / **Netty** / **HiveMQ Client** / **Californium** | Apache-2.0 / EPL-2.0 | 🟢 | 可用 |
| `org.homio:onvif` | MIT | 🟢 | 可用 |
| `org.jcodec:jcodec` | README 声明 FreeBSD（POM 未核验） | 🟢 | 用前补核验 |

> **EPL-2.0 的边界**：Milo 与 digitalpetri modbus 均为 EPL-2.0。EPL 是**弱 copyleft**——
> 以库的形式链接使用（不修改库源码）对闭源产品无传染义务，但**修改过的 EPL 源码文件必须回馈**。
> 因此本仓的一条硬约束是：**不得 fork 后修改 Milo / digitalpetri modbus 的源码**；
> 需要定制时改为在外层包装（本仓的适配器模式天然支持这种做法）。

### 5.2 许可证管理（D1 决策：**记录而非阻断**）

**个人项目不需要构建失败式的许可证门禁**——那会在你想快速试一个库时反复挡住你。
但**也不能不记录**：将来若把这个项目用于闭源场景，GPL 依赖会突然成为问题。
所以改为「**自动扫描 + 分类 + 生成风险清单 + 显著警示**」，构建永远不因许可证失败。

```
mvn verify（或独立 goal：mvn license:aggregate-report）
  └─ 扫描完整依赖树的 license 字段（含 optional 与 runtime 作用域）
      │
      ├─ 【宽松】Apache-2.0 · MIT · BSD · EPL-2.0 · MPL-2.0 · LGPL（动态链接）
      │     → 静默通过
      │
      ├─ 【弱 copyleft / 需注明】GPL-2.0-with-Classpath-Exception · LGPL
      │     → 构建时打印一行提示；写入 LICENSE-RISK.md 的「需注明」区
      │
      ├─ 【强 copyleft】GPL-2.0（无 CPE）· GPL-3.0 · AGPL · SSPL
      │     → **构建通过**，但打印显著警示；写入 LICENSE-RISK.md 的「高风险」区
      │       ⚠️ 警示文案：本依赖为强 copyleft，若将本项目用于闭源分发需重新评估
      │
      └─ 【未声明 / 来源冲突】
            → 构建通过；写入 LICENSE-RISK.md 的「待确认」区
```

**`LICENSE-RISK.md` 由构建自动生成**（不手工维护），每行含：制品坐标、版本、许可证、
引入它的模块、免责提示。这样任何时刻你都能一眼看到「我引入了哪些有约束的库」。

**四个档位与具体处置**：

| 档位 | 判据 | 处置 | 本仓实例 |
|---|---|---|---|
| **宽松** | OSI 认可且宽松，或弱 copyleft 仅以库形式链接 | 静默通过 | Netty / PLC4X / SNMP4J / CXF / HiveMQ（Apache-2.0）；JavaCAN / purejavacomm（MIT/BSD）；Milo / digitalpetri modbus / Californium（EPL-2.0）；`jmbus`（MPL-2.0） |
| **需注明** | 弱 copyleft，链接使用无传染但需保留声明 | 构建提示 + 清单登记 | jSerialComm（LGPL-3.0）；Calimero（GPL-2.0+CPE）；`io.github.lunasaw:sip-*`（宽松但声明不一致） |
| **高风险** | 强 copyleft，组合分发会传染 | **构建通过 + 显著警示 + 清单登记** | **BACnet4J（GPL-3.0）——按 D1 已解禁可用**；OpenMUC KNX 驱动（GPL-3.0） |
| **待确认** | 无法确定许可证 | 构建通过 + 清单登记 | `javax.sip:jain-sip-ri`（POM 无 `<licenses>`） |

**仍然要做的两件事**（与商业无关，纯属工程卫生）：

1. **扫传递依赖而非只扫直接依赖**：GPL 常常是"三级传递依赖"带进来的，只扫直接依赖等于没扫。
2. **同制品多来源一致性记录**：`net.solarnetwork.external:...bacnet4j`（POM 自称 Apache-2.0 / 上游 GPL-3.0）
   这类**声明冲突**要记进「待确认」区——不是为了阻断，而是为了将来真要商用时知道去哪里查。

> **与旧口径的区别**：旧口径是「黑名单直接构建失败」。个人项目下这条会带来实际摩擦
> （想试 BACnet4J 却被自己的门禁挡住），而且**它的保护价值在无商业交付时接近于零**。
> 改为记录后：日常开发零摩擦，将来需要商用评估时，`LICENSE-RISK.md` 已经是一份现成的输入。
>
> **⚠️ 一条不能省的提醒**：如果你将来打算把基于本仓的项目**闭源发布或商用**，
> 那么引入 BACnet4J（GPL-3.0）会让整个分发物受 GPL-3.0 约束。
> 到那时要么按 GPL 开源，要么去掉该模块、要么购买商业授权——**现在记着，比那时才发现便宜得多**。

### 5.3 制品分发风险

| 风险 | 制品 | 处置 |
|---|---|---|
| **不在 Maven Central** | BACnet4J（RadixIoT 私服）、`fpompermaier/onvif`（JitPack） | 自建 Nexus 代理并**冻结版本**；禁止 `master-SNAPSHOT` |
| **带 native，平台相关** | JavaCV/FFmpeg（按平台裁剪，聚合包 ~1.3 GB）、`calimero-rxtx`（jrxtx 串口） | 生产只引目标平台 artifact，禁止 `-platform` 聚合包 |
| **必须显式补依赖** | `jain-sip-api`（被 `jain-sip-ri` 标为 `provided`） | 协议模块 pom 显式声明 |
| **日志桥接** | `log4j-over-slf4j`（JAIN-SIP 自带 Log4j 1.2.14） | 协议模块显式声明 |
| **传递依赖过重** | `io.github.lunasaw:sip-common`（带 Spring Cache + Micrometer + SkyWalking + Guava + Caffeine） | 引入时用 `<exclusions>` 裁掉非必需项 |
| **坐标已迁移，旧坐标仍被广泛引用** | Milo：`sdk-client`（冻结 `0.6.16`）→ `milo-sdk-client:1.1.7`；PLC4X：`plc4j-driver-ethernet-ip`（冻结 `0.6.0`）→ `plc4j-driver-eip:1.0.0` | 在 `ypbin-iot-dependencies` 中只声明新坐标；旧坐标进**禁止依赖黑名单**，由 `maven-enforcer-plugin` 拦截 |
| **Netty 版本被多个协议库各自钉住** | `digitalpetri modbus` 钉 `4.1.136.Final`；其他协议库亦可能带入自己的 Netty | 由 `netty-bom:4.1.138.Final` 统一收敛；构建期用 `maven-enforcer-plugin` 的 `dependencyConvergence` 强制校验，**版本分歧直接构建失败** |
| **同一库的 alpha/milestone 混在 release 列表中** | Californium `4.0.0-M6`、Netty `5.0.0.Alpha2`、HttpClient `5.7-alpha1`（Central 的 `<release>` 字段会照实返回） | 版本号**显式写死在 BOM**，禁止用 `LATEST`/`RELEASE`/范围版本；CI 加规则拦截 `-M`/`-alpha`/`-Alpha` 结尾的版本 |

### 5.4 本次未能验证的事项（显式记录）

1. BACnet4J 的**最低 JDK 版本**与**线程安全承诺**（官方 README/POM 均未声明）。
2. `plc4j-driver-bacnet` 是否具备服务端/设备对象模型能力。
3. Calimero 是否有**商业授权渠道**；KNX Secure（IP Secure / Data Secure）支持情况；JDK 21 官方兼容声明。
4. Tridium Niagara 的 LON 驱动可用性与版本。
5. i.LON 100 / SmartServer 2.0 的现行销售与生命周期状态。
6. SNMP4J 官方页面的 v1/v2c/v3 支持原文（`snmp4j.org` 抓取失败；仅核验 POM/版本/许可）。
7. 各 ONVIF 库的线程安全与异步语义细节。
8. `org.jcodec:jcodec` 的 Maven POM 许可证字段。
9. GB/T 26875 修订版（20252122-T-906）的具体发布/实施时间。
10. `org.jinterop:j-interop` 的许可证字段（POM 未核验；历史版本为 LGPL）。
11. 各协议库的**线程安全承诺**（Milo / digitalpetri modbus / Calimero / BACnet4J / PLC4X 驱动均未在 POM 中声明）。
12. PLC4X 各驱动在 JDK 21 上的**字节码基线**与实机兼容性（`plc4j-api:1.0.0` 已核验版本与许可，未核验字节码版本）。
13. **所有库在 JDK 21 上的实机运行验证**（本次为元数据核验，未做编译与运行实测）。
14. `org.openmuc:jmbus:3.3.0` 的 JDK 21 兼容性与实际可用度（**停更于 2020-06**，M-Bus 直连场景才需评估）。
15. `plc4j-driver-bacnet:0.13.1` 在 PLC4X 1.0.0 时代的维护状态（它是否会被放弃或补发 1.x）。
16. `io.grpc:grpc-netty-shaded:1.84.0` 与 `com.rabbitmq:amqp-client:5.35.0` 的「设备接入」语义适配成本
    （两者版本已核验，但**是否立项取决于业务定义**，见 §2.11 B 组）。
17. 各长尾协议网关的实际可采购型号与生命周期（EtherCAT / IO-Link / DALI / M-Bus / Zigbee / Z-Wave）
    —— 属硬件选型范畴，本仓只给接入路径建议。

> **落地纪律**：进入 M0/M1 的第一件事，就是把上表所列的「第一批协议依赖」逐一做
> **编译 + 冒烟运行**验证，把「未验证」清零。版本号可核验，运行时兼容性不可臆测。
>
> **本次已通过实证推翻的两条二手说法**（记录以免再次踩坑）：
> ① `digitalpetri modbus` 支持 Modbus ASCII —— **错**，源码 89 个文件中零处 ASCII；
> ② `calimero-rxtx` 的 JNI 部分为 LGPL —— **错**，POM 声明为 GPL-2.0+CPE，且实际依赖 `nrjavaserial`（Apache-2.0）。

### 5.5 JDK 21 虚拟线程 pinning 风险分级（按库定策略）

**先把机制说准**（依据 [JEP 444](https://openjdk.org/jeps/444) 原文，避免按直觉误判）：

| 情形 | 是否 pinning | 说明 |
|---|---|---|
| `synchronized` 块/方法内阻塞 | ✅ **pinning** | 载体线程被占住，**调度器不会扩容补偿**；JEP 491（JDK 24）已移除该限制 |
| **`native` 方法或 foreign function 内阻塞** | ✅ **pinning** | JEP 444 原文：*"The second limitation is required for proper interaction with native code"* ——**该限制不可移除，JDK 24 也不会消失** |
| 文件系统操作、`Object.wait()` 等 | ❌ 不 pinning，但**占住** OS 线程 | 调度器会**临时扩容** `ForkJoinPool` 补偿，上限由 `jdk.virtualThreadScheduler.maxPoolSize` 控制（默认 256） |
| `java.net.Socket` / `DatagramSocket` / `LockSupport` / `Semaphore` / 阻塞队列 | ❌ 优雅卸载 | JDK 21 已重写为虚拟线程友好 |

> **两个必须记住的结论**：
> ① **pinning 不被补偿，占住 OS 线程不被补偿**，两者机制不同、后果不同；
> ② **native pinning 无解**——任何经 JNI 的协议库（串口、CAN、FFmpeg）在虚拟线程上都会钉住载体，
> 在 JDK 21 与未来版本上都一样。**唯一对策是不要把这类调用放在虚拟线程上。**

**据此对协议库分级**（关键判据不是"用了多少 synchronized"，而是**我们是否会让它跑在虚拟线程上**）：

| 库 / 协议 | 我们如何使用它 | 是否上虚拟线程 | pinning 风险 | 策略 |
|---|---|---|---|---|
| **Milo**（OPC UA） | 原生异步 API，Stage 在 Netty EventLoop 完成 | ❌ **根本不经过虚拟线程** | 🟢 无 | 直接用异步 SPI |
| **digitalpetri modbus** | 原生异步 API（Netty） | ❌ 不经过 | 🟢 无 | 直接用异步 SPI |
| **HiveMQ MQTT Client** | 原生异步 API（Netty） | ❌ 不经过 | 🟢 无 | 直接用异步 SPI |
| **PLC4X 驱动**（S7 / EIP / CAN） | API 为 `CompletableFuture`，但驱动内部**可能阻塞** | ⚠️ 视驱动而定 | 🟡 需逐驱动实测 | 默认走 `BlockingProtocolAdapter`（虚拟线程），实测后对个别驱动调整 |
| **SNMP4J** | 有同步（`Snmp.get`）与异步（`send`+listener）双 API | ⚠️ 同步路径会 | 🟡 中 | 优先用异步 API；同步 API 走虚拟线程并监控 pinned 事件 |
| **BACnet4J** | Promise/Future 为主 | ⚠️ 部分 | 🟡 中 | 同上（该库 M2 才评估） |
| **Calimero**（KNX） | 事件驱动 + 显式连接管理，传统同步请求 API 较多 | ✅ 会 | 🟠 较高 | 走虚拟线程 + 强制监控；若 pinned 事件持续超阈值，退回平台线程池 |
| ~~**j2mod**（Modbus ASCII）~~ | ~~纯阻塞式 API~~ | — | — | **按 D4 未采用**；若将来改用，走 `BlockingProtocolAdapter` 并实测 pinned 率 |
| **jSerialComm**（串口） | **JNI native 阻塞读** | ✅ 会 | 🔴 **高且无解** | **强制平台线程池**，禁止在虚拟线程上调用 |
| **JavaCAN**（CAN） | **JNI native** | ✅ 会 | 🔴 **高且无解** | **强制平台线程池** |
| **JavaCV / FFmpeg**（GB28181 媒体） | **JNI native 解码** | ✅ 会 | 🔴 **高且无解** | **强制平台线程池**；本仓默认媒体外置，本不涉及 |

**引擎侧的三条落地规则**：

1. **`AdapterContext.scheduler()` 必须同时提供两种执行器**：`virtualThreadExecutor()`（阻塞式 Java 协议栈）
   与 `platformThreadExecutor()`（**native 调用专用**，有界平台线程池）。
   SPI 文档中明确标注：**任何经 JNI 的调用必须使用后者**（这条已回写进 `SPI.md` §3.3）。
2. **`BlockingProtocolAdapter` 增加一个 `requiresPlatformThread()` 开关**：CAN / 串口 / 媒体类适配器覆写为 `true`，
   基类据此选择执行器，避免每个协议作者各写一遍、写错一遍。
3. **生产必开 `jdk.VirtualThreadPinned` JFR 事件**（默认开启，阈值 20ms），
   并设告警：pinned 次数或累计时长超阈值即视为容量风险。
   预发环境用 `-Djdk.tracePinnedThreads=short` 定位具体栈（生产禁用，开销大）。

**关于 JDK 24 的 JEP 491**：它只解决 `synchronized` 那条（上表 Calimero 一类的风险会下降），
**不解决 native 那条**（串口 / CAN / 媒体类永远是平台线程）。
因此「升级到 JDK 24/25 就不用管 pinning 了」是**错误结论**，本仓的线程池分工设计在升级后仍需保留。

---

## 6. 跨协议工程约束

| # | 约束 | 说明 |
|---|---|---|
| E1 | **超时必须显式** | 所有远程交互（SIP/RTP/SOAP/SNMP/BACnet/Modbus）必须显式配置 connect/read 超时、重试与降级，**禁止无超时的默认客户端** |
| E2 | **能网关化就网关化** | LonWorks 必须网关转换；KNX 视网络条件选 tunneling；PROFINET 无 Java 方案 |
| E3 | **信令与媒体分离** | GB28181 的经验适用于所有视频类需求：Java 只做信令与控制，媒体面外置 |
| E4 | **版本可插拔** | GB/T 26875（换版中）、GB28181（2016/2022）、BACnet（6.x/7.x）都必须支持协议版本路由 |
| E5 | **优先 Apache/MIT 且活跃的库** | SNMP4J、CXF、Netty、PLC4X；GPL 系（BACnet4J、Calimero）作为「许可已谈定才引入」项 |
| E6 | **native 依赖隔离** | 带 native 的协议模块（CAN、JavaCV、calimero-rxtx）必须可选，且其 native 加载失败要给出明确诊断而非 `UnsatisfiedLinkError` 裸奔 |
| E7 | **不照抄开源实现** | 仅参考协议规范与公开文档；自研模块（GB/T 26875、HART、通用透传）不引入任何参考项目的代码与命名 |
| E8 | **native 调用禁止上虚拟线程** | 经 JNI 的调用（jSerialComm 串口、JavaCAN、JavaCV/FFmpeg）**必须**走 `platformThreadExecutor()`。依据 JEP 444：native pinning 不可移除，JDK 24 的 JEP 491 也只修 `synchronized` 那条（见 §5.5） |
| E9 | **许可判定以制品 POM 为准** | 不采信 README、博客、二手描述。本项目已实证两处错误说法：`calimero-rxtx` 被传为 LGPL（实为 GPL-2.0+CPE）、`digitalpetri modbus` 被传支持 ASCII（实测零支持）。**引入任何库前先拉 POM 与源码制品核对** |
| E10 | **依赖冲突必须显式裁剪** | 引入协议库时同步审查传递依赖：`j2mod` 带 `log4j-core`（若启用必须排除）、`lunasaw:sip-*` 带 Spring Cache + SkyWalking + Guava（必须裁剪）、`jain-sip-ri` 带 Log4j 1.2.14（必须桥接） |

---

## 7. MVP 路线图

### 7.1 分批策略（D5：范围收敛，**可验证性优先**）

个人项目的第一判据**不是**「协议是否热门」，也不是「抽象是否打透」，而是
**「我能不能在本机把它跑起来并验证」**——写一个无法验证的适配器，等于写了一段没有反馈的代码。

因此增加一列「**可验证性**」（详细矩阵见 §7.5）：

| 档 | 批次 | 协议 | 可验证性 | 预估 |
|---|---|---|---|---|
| — | **M0 骨架** | 通用 TCP（透传） | 🟢 纯本机（回显服务） | 2 周 |
| **核心档** | **M1** | **Modbus TCP/RTU** · **OPC UA** · **MQTT** | 🟢 全部可本机验证：Modbus 用 `modbus-slave-tcp` 模拟器 · **OPC UA 直接用 Milo Server** · MQTT 用 Testcontainers 起 Broker | 4 周 |
| **扩展档** | **M2** | UDP · HTTP · WebSocket · **SNMP** · **S7** | 🟢 UDP/HTTP/WS 本机；SNMP 用 `snmpd` 容器；S7 需 docker 化的 S7 模拟器（如 Snap7 server） | 3~4 周 |
| **扩展档** | **M3** | **CoAP** · **CAN** · **BACnet**（D1 已解禁） | 🟢 CoAP 用 Californium 自带 server；**CAN 用 Linux `vcan` 虚拟总线（无需硬件）**；BACnet 用虚拟设备库 | 4 周 |
| **按需档** | M4+ | **GB28181** · ONVIF · GB/T 26875 · KNX · EtherNet/IP | 🟡 信令与协议栈可本机跑，**但无真实摄像机/楼宇设备，端到端验证受限** | 按需 |
| **不做** | — | OPC DA · PROFINET · LonWorks · EtherCAT · IO-Link · DALI · M-Bus · Zigbee/Z-Wave · JT/T 1078 | 🔴 **需要专用硬件或 Windows 桥接环境，个人项目无法验证**（D2） | 不规划 |
| **待定** | — | Modbus ASCII · HART · gRPC · AMQP | 🟡 ASCII 用 j2mod 即可（D4）；HART 需 HART 网关；gRPC/AMQP 语义边界待定（§2.11） | 有需求再说 |

**相比旧路线图的四处变化**：

| 变化 | 原因 |
|---|---|
| **S7 从 M2 保留、BACnet 进 M3 且解禁** | D1 修订：BACnet4J 的 GPL-3.0 在个人项目下不再是障碍，可直接用 |
| **OPC DA / PROFINET / LonWorks 从 M3/M5 移除** | D2 修订：无硬件与桥接环境，**无法验证**，写了也是死代码 |
| **GB28181 从"信令自建 8~10 周"回到"按需，用现成框架"** | D1 修订：`io.github.lunasaw:sip-*` 的 POM/README 许可都是宽松许可，"冲突"不影响个人使用；自建 SIP 信令层省下的只有法务确定性，**不值得花 4 周** |
| **总量从 21 个模块收敛到 5 + 5 + 3** | D5：个人项目的瓶颈是**时间和验证能力**，不是协议覆盖数 |

> **M0→M1 是唯一的"不可跳过"路径**：M0 骨架 + 通用 TCP 适配器 + TCK + 架构门禁
> 一旦跑通，后面每个协议都是"按模板填空"。**M0 没跑通之前不要碰任何真协议。**
>
> **M4+ 的口径**：这一档**默认不做**。它们的共同点是「能写但验证不充分」——
> 个人项目里，一个验证不充分的协议适配器会长期带着你不知道的 bug 存在，
> 反而比"没有这个协议"更糟（因为你以为它能用）。有真实需求时再按 §3.6/§3.7 的方案做。

### 7.2 为什么第一批选 Modbus + OPC UA + MQTT

这三个协议的组合恰好覆盖了 SPI 的**全部抽象维度**，是性价比最高的一组：

| 维度 | Modbus | OPC UA | MQTT |
|---|---|---|---|
| 传输 | TCP / 串口 | TCP（自带栈） | TCP |
| 连接模型 | **1 链路 : N 设备**（网关多从站） | 1 链路 : 1 设备 | 1 链路 : N 设备（Topic 即地址） |
| 地址语义 | 寄存器地址 + 功能码 | NodeId 字符串 | Topic 通配符 |
| 订阅 | 轮询式 | **原生订阅** | **原生推送** |
| 写 | 支持（线圈/寄存器） | 支持 | 支持（发布） |
| 浏览 | 无 | **目录浏览** | 无 |
| 库风格 | 阻塞式 | **异步（CompletableFuture）** | 阻塞 + 异步回调 |
| 消息量 | 小报文、高频 | 中报文 | **大吞吐、海量 Topic** |

三条路径同时把**阻塞桥接**与**原生异步**两种实现风格、**轮询**与**推送**两种数据来源、
**1:1** 与 **1:N** 两种连接模型全部压到 SPI 上——如果这三者都实现得干净，
后面每个协议就只是「按模板填空」。

### 7.3 M0 骨架的验收标准（可执行定义，D6 已按个人项目收敛）

M0 不是「搭个空壳」，而是**把"能跑"这件事变成可验证事实**。

**功能验收（硬门槛）**

- [ ] `ypbin-iot-core` / `iot-runtime` / `iot-transport` 编译产物中**不存在** `org.springframework` / `io.netty` 的类引用（ArchUnit 强制）
- [ ] **ArchUnit 规则有效性自检通过**：每条规则都有合成的反向违规样本，验证规则真能报错（含"在 `protocol/` 包里写一个 import Spring 的类"这一条）
- [ ] 通用 TCP 适配器可通过**全部 TCK 用例**
- [ ] **`ConnectionRegistry` 单飞建链并发测试通过**：200 线程并发 `acquire()` 同一 `connectionId`，**只建立 1 条 TCP 连接**
- [ ] **`ConnectionRegistry` 竞态测试通过**：建链过程中最后一个引用释放，建链完成后立即进入空闲回收，**不泄漏**
- [ ] `ApplicationContextRunner` 装配测试：默认装配 / 开关关闭 / 宿主覆盖 / 协议库缺失 四种场景全绿
- [ ] `/actuator/iot` 可返回适配器矩阵与连接数
- [ ] 许可证扫描能生成 `LICENSE-RISK.md`（人为引入一个 GPL 依赖能被记录并警示）
- [ ] **`jdk.VirtualThreadPinned` 事件已采集并可从 `/actuator/iot` 观察**

**性能验收（D6 收敛后的门槛）**

| 指标 | 日常门禁（每次发布必过） | 一次性验证（云服务器跑一次） |
|---|---|---|
| 连接数 | **1 万**（个人开发机可跑） | **10 万**（租一台 8C16G 跑一次存档） |
| 数据吞吐 | **20 万点/秒** | **200 万点/秒** |
| 持续时间 | **1 小时**（无 FD/内存增长趋势） | 1 小时 |
| 长稳 | **8 小时**（足以看出 GC 与内存趋势） | — |

- [ ] 内核参数与 JVM 参数在**你的实际机器上**逐条核对生效（`/proc/self/limits`、`sysctl -a`、`jcmd VM.flags`），形成对照表
- [ ] 协议模拟器的基准数据已产出（单容器可模拟设备数 / 连接数 / 请求数），**确认模拟器不是瓶颈**

> **为什么把 10 万降为一次性验证**：日常门禁必须能在你的开发机上跑完。
> 一个跑不动的门禁等于没有门禁——它会立刻被 `@Disabled` 掉，然后连 1 万连接的回归也一起消失。
> **架构仍按 10 万设计**（设计成本几乎为零），只是不要求每次提交都验证它。
>
> **M0 不达标就不做 M1**：不是要求性能达标，而是要求**机制全通**——
> 连接复用、微批出口、线程分工、TCK、架构门禁这五件事跑通之后，后面每个协议都是按模板填空。

### 7.3.1 M0 实施结果（2026-09-13，已完成）

| 项 | 结果 |
|---|---|
| 模块 | 10 个（dependencies / bom / core / runtime / transport / spring-boot-starter / protocol-tcp / test / architecture-tests + 根聚合） |
| 构建 | `mvn clean test` **全模块 BUILD SUCCESS**（含 spotless 与 JaCoCo 门禁） |
| 测试 | **107 个用例**全绿（core 31 / runtime 25 / protocol-tcp 23 / starter 17 / 架构 11） |
| 覆盖率 | core **84.2%** · runtime **82.4%** · protocol-tcp **86.0%** · starter **92.4%**——**均超过母仓 80% 门禁，未下调任何阈值** |
| 规模 | 主源码 87 文件 / 9537 行；测试 3644 行 |

**M0 期间发现并修复的三个真实缺陷**（都由测试暴露，非推测）：

| # | 问题 | 性质 |
|---|---|---|
| 1 | **`CompletionStage` 异常交付契约缺口**：`thenApply` 会把领域异常包成 `CompletionException`，调用方 `catch (ConnectionException)` 捕获不到 | 契约问题 → 新增 `cn.ypbin.iot.core.util.Stages` 归一化 + SPI 约定 C11 |
| 2 | **`EnvironmentPostProcessor` 默认值注入语义错误**：用 `addLast` 注入「追加后的合并值」，导致用户已配置的原值优先、合并结果永不生效 | 功能缺陷 → 改 `addFirst`（母仓用 `addLast` 是因为它注入纯默认值，语义不同） |
| 3 | **transport 会话绑定时机错误**：原设计在 `open` 阶段就要求会话（需伪造 `DeviceSpec` 占位），与「1:N 链路共享」模型冲突 | 设计缺陷 → 改为**会话延迟绑定**（`bindSession`），并把该能力上移到 `ProtocolAdapter.bind` |

**M0 期间识别出的一个 SPI 缺口**：原 `DeviceRegistry` 只覆盖「设备从哪来」，未覆盖「链路建链参数从哪来」。
已补齐 `ConnectionSpecProvider`（详见 `SPI.md` §8.2）——两者必须拆开，否则 device 级会携带链路级参数。

**M0 独立代码审核结果（3 个方向 + 1 轮对抗性复审）**

共派 4 个独立审核 agent：SPI 契约一致性、并发与资源安全、规范合规、P0 修复对抗性复审。
累计发现 **9 个 P0** 与 30+ 条 P1/P2，已修复项见下（剩余项列于本节末尾）。

| 来源 | P0 问题 | 修复 |
|---|---|---|
| 并发审核 | 上限拒绝分支不完成 Future → 并发等待者永久挂起 → **容器启动死锁** | 拒绝路径也走统一交付通道；新增 CR-5 回归测试 |
| 并发审核 | 空闲回收「判定+关闭」跨临界区 → acquire 可拿到正在关闭的链路（TOCTOU） | 判定+翻转+摘除收进同一临界区 + 可失败 `tryRetain`；新增 CR-6 |
| 并发审核 | `closeAll`/`acquire` 无生命周期锁 → 关闭后仍建出无人关闭的孤儿链路 | 读写锁保护「closed 检查+入表+建链」；新增 CR-7 |
| 并发复审 | **重试耗尽的判定位于建链之后** → 最后一次真的建链入表却返回失败 → 永久孤儿 + 单次 acquire 放大 4 次建链 | 判定移到方法入口 |
| SPI 审核 | `probe` 默认实现抛 `UnsupportedOperationException`（非 IotException、无消息键），且未归一化 → 违反「probe 永不异常完成」 | 改为文档承诺的「复用 open」+ `Stages.normalize` 归一化 |
| SPI 审核 | `IotLifecycle.bind` 失败不归还句柄 → 引用计数永不归零、空闲回收永久失效（**实测 activeCount 恒为 1**） | 失败即 `handle.release()`；新增 LIFE-09 |
| SPI 审核 | `ConnectionSpec.tls` 从未被消费 → **配置 TLS 静默明文建链** | transport 层 fail-fast 拒绝；新增 TCP-07 回归 |
| SPI 审核 | `DeviceRegistry.addChangeListener` 整条未接线 → 运行期增删设备完全无效 | 落地 ADD/UPDATE/REMOVE + revision 幂等 + 设备级排他锁；新增 LIFE-11 |
| 合规审核 | `Endpoint` 静默吞 `URISyntaxException` 且 Javadoc 谎称校验（**R11 铁律违规**，且被测试固化成契约） | 构造期 fail-fast 抛 `AddressParseException` + scheme 必需校验 |

**审核过程本身暴露的一个流程问题（已记录）**：首次 P0 批量修复脚本因一处锚点未命中而
`SystemExit` 提前退出，导致其后所有补丁未执行，但提交信息按脚本意图描述了修复内容 ——
即**提交信息与代码不一致**。该问题由对抗性复审通过 `git show --name-only` 发现并纠正。
**教训：批量补丁脚本不应在单个锚点未命中时终止整个批次；提交前必须核对实际变更文件列表。**

**M0 阶段刻意保留的两处实现简化**（有明确理由与触发条件）：

| 简化 | 现状 | 触发升级的条件 |
|---|---|---|
| 调度器 | 用 `ScheduledExecutorService`，未实现 DESIGN §4.4 的分层时间轮 | 通过 1 万连接门禁、准备冲击 10 万连接时（M0 门槛下堆开销与精度完全够用） |
| Netty 传输 | 用 `NioEventLoopGroup`，未切 `EpollEventLoopGroup` | 同上（届时可拿到 `SO_REUSEPORT` 与更低系统调用开销） |

**OPC UA 已落地（2026-09-14）**

上一轮曾因「覆盖率 13%、四条正向路径一条都没跑起来」而**主动撤回**该模块。本轮按当时的结论
**先建服务端 harness、再写模块**，结果一次到位：

| 项 | 结果 |
|---|---|
| 模块 | `ypbin-iot-protocol-opcua`（Milo 1.1.7）|
| 能力 | READ + WRITE + **SUBSCRIBE_NATIVE** + **BROWSE** + MULTI_DEVICE_LINK（M1 三模块中能力最全）|
| 覆盖率 | **81.7%**（母仓 80% 门禁，无阈值下调）|
| 测试 | TCK + 14 条端到端行为用例（对自建 Milo 服务端）|

**「先 harness 后模块」的顺序本身就是本轮最大的收获** —— 端到端测试第一次运行就抓到
`URI.getScheme()` 对 `opc.tcp://` 返回 **`opc.tcp`（含点号）** 而非 `opc`，
导致**所有连接被拒**。这个缺陷在上一轮那种「写完无法验证」的状态下会直接进仓库。

**`BrowseExtension` 终于有了第一个真实实现**，同时暴露了 TCK 自身的一个缺陷：
TCK-10 硬编码订阅地址 `"tck"`，而 OPC UA 的 NodeId 必须带命名空间 → 已为 TCK 增加
`subscriptionAddress()` 可覆写钩子（默认值保持向后兼容）。**模块化 TCK 的价值正在于此**：
只有真正接入一个有地址语法的协议，才会发现「地址是协议无关字面量」这个隐含假设是错的。

**Milo 1.x 的六处「按名字猜必错」已全部实测确认并归档**（见下），
其中 `OpcUaMonitoredItem$DataValueListener` 是**两参数**方法、`Namespace` 接口**没有生命周期**
（节点在构造器建 + `AddressSpaceManager.register`）、服务端传输实现在独立制品 `milo-transport`。

**OPC UA 的第四轮审核（对刚落地模块本身）发现的 2 个 P0 + 5 个 P1，已全部修复**

| 级别 | 问题 | 实证 | 修复 |
|---|---|---|---|
| **P0** | **断线永不上报**：`onConnectionLost` 是死代码 → `whenClosed()` 永不完成、`state()` 恒 ONLINE | 关掉服务端后 `read()` 仍正常完成返回全 BAD、`whenClosed().isDone=false` | 接线 `addSessionActivityListener().onSessionInactive` |
| **P0** | **`open()` 同步阻塞调用线程**：`OpcUaClient.create()` 内部做端点发现，是同步网络调用，`orTimeout` 包不住它 | `connectTimeout=1s` 实测 `open()` 耗时 **10243ms**；失败后仍有 2 条连接保持 open 且客户端仍在发心跳 | 整体搬到平台线程池 + 失败分支显式 `disconnectAsync()` |
| P1 | **写结果错位**：用 `indexOf` 定位结果，重复 NodeId 时全命中首个下标 | `write([Pressure=901L, Pressure="not-a-number"])` → **`[true,true]`（假成功）**；反向 → `[false,false]`（假失败） | 改用位置索引；新增 OPC-14 回归用例 |
| P1 | **订阅假成功**：丢弃 `createMonitoredItems()` 的逐项结果 | `subscribe(ns=2;s=DoesNotExist)` 成功返回 active 句柄 | 校验逐项结果，全失败即异常完成；新增 OPC-06c |
| P1 | **浏览 BFS 断裂、`maxDepth` 完全无效**：同步 `while` 在任何异步回调前就把队列抽干 | `browse(i=84, maxDepth=3)` 只回 3 个节点，`Temperature` 不可达；depth=1/2/3 返回**同样 6 个** | 重写为递归异步遍历；新增 OPC-08b |
| P1 | 每条链路的 `spec.requestTimeout()` 从未被消费 | 全模块 grep 无引用 | 优先取链路级超时 |
| P1 | `dispatch()` 在 Milo **JVM 全局共享执行器**上同步执行宿主回调 | 实测线程 `milo-shared-thread-pool-N`（core=0/max=Integer.MAX_VALUE）—— 非 Netty EventLoop（如实修正）但仍属 I4 精神违反 | 仍待修（列入遗留） |

**本轮最重要的认知修正（复审第 20 条）**：上一轮把 `nativeSubscriptionMustReceivePush` 标为「harness 无法驱动服务端值变更、需经 AttributeService」而跳过。
复审用**原生 Milo 客户端绕过产品代码**直连同一 harness，证明「不推送」发生在**服务端 harness**；再只补一处 observer 接线
（`onDataItemsCreated` 里 `node.addAttributeObserver(...)` → `item.setValue(...)`），产品订阅**立刻收到推送**。
即：**产品的订阅投递链路本身是好的，我上一轮的因果解释是错的**——真因是 harness 覆盖了 `onDataItemsCreated` 却只把 `DataItem` 存进一张「只写不读」的 map，从未建立观察者。
修复 harness 后该用例直接通过，并补了「取消后不得再投递」。

> **教训**：把「我没验证出来」归因成「环境/工具做不到」之前，必须先用**独立于产品代码的方式**复现一次。
> 这次如果不去证伪，一个可修的工具缺陷就会被写成产品的永久限制。

**门禁补强**：SRC-01 原先只匹配本仓包名（`cn.ypbin.iot.`），导致 `java.util.concurrent.*`、`org.eclipse.milo.*`
这类**第三方/JDK 内联 FQCN 结构性漏判**。已扩展为覆盖 `cn.ypbin|java|javax|jakarta|org|com|io` 前缀并补自检样本，
补强后**当场抓到** `NettyTransport` 里早先复审指出的那处漏判。

**显式标记的未验证项**：`nativeSubscriptionMustReceivePush` 以 `Assumptions.abort` 标记为未验证 ——
（上一轮遗留，本轮已解决）—— 经复审证明是 harness 缺陷而非产品限制，已修复 harness 并启用该用例。
当前仍未实现：非 None 安全策略、认证（用户名/密码、证书，`credentialRef` 全模块零引用）、
方法调用与事件订阅；`dispatch()` 仍在 Milo 共享执行器上同步执行宿主回调。

**M1 已落地协议模块（2026-09-13）**

| 模块 | 协议库 | 能力 | 测试 | 覆盖率 |
|---|---|---|---|---|
| `ypbin-iot-protocol-modbus` | digitalpetri modbus 2.1.6（EPL-2.0）| READ / WRITE / SUBSCRIBE_POLLING / MULTI_DEVICE_LINK | 46 用例 + 独立手写 MBAP 应答器 | 84.9% |
| `ypbin-iot-protocol-mqtt` | HiveMQ client 1.4.0（Apache-2.0）| WRITE / SUBSCRIBE_NATIVE / MULTI_DEVICE_LINK | 41 用例 + 嵌入式 Moquette broker | 89.5% |

**框架侧配套能力**：
- `PollingSubscriptionManager`（`SUBSCRIBE_POLLING`）：上一轮未结束不叠加下一轮（慢设备天然自适应降频）；
  单轮失败按退避重试而不让订阅死掉。S7/SNMP 可直接复用。
- `ConnectionRegistry` 建链速率限制（令牌桶 + 抖动）：10 万连接启动风暴的硬闸门。

**M1 过程中由测试抓出的真实缺陷（均已修）**：

| 缺陷 | 发现方式 |
|---|---|
| `open()` 对非法串口端点**同步抛异常**而非返回失败 Stage（违反 SPI 契约）| Modbus 边界测试 |
| `ModbusSession.ping()` 查协议库 `isConnected()`，而断开是异步的 → 已关闭会话仍报存活 | Modbus 边界测试 |
| 测试模拟器 MBAP 头按 6 字节读（实际 7 字节含 unitId）、响应漏写 unitId | 端到端测试 |
| MQTT **忽略了 `ConnectionSpec.connectTimeout`**，连不可达 broker 会一直重试 | MQTT 边界测试 |
| MQTT 客户端标识不唯一 → broker 互踢 | MQTT 专项测试 |
| 协议码在两处维护（`modbus-tcp` vs `modbus`）漂移 | 地址解析测试 |

**OPC UA（Milo）API 调研结论（2026-09-13 实测，供下轮直接落地，避免重复调研）**

坐标确认（**注意与旧文档不同**）：`org.eclipse.milo:milo-sdk-client:1.1.7`（不是 `sdk-client`，
后者冻结在 0.6.x）；服务端模拟器为 `org.eclipse.milo:milo-sdk-server:1.1.7`。

已验证可用的关键签名（`javap` 实测，非文档推测）：

| 用途 | 签名 |
|---|---|
| 建客户端 | `OpcUaClient.create(String endpointUrl, Function<List<EndpointDescription>, Optional<EndpointDescription>>, Consumer<OpcTcpClientTransportConfigBuilder>, Consumer<OpcUaClientConfigBuilder>)` |
| 连接 | `CompletableFuture<OpcUaClient> connectAsync()` / `disconnectAsync()` |
| 批量读 | `CompletableFuture<List<DataValue>> readValuesAsync(double maxAge, TimestampsToReturn, List<NodeId>)` |
| 批量写 | `CompletableFuture<List<StatusCode>> writeValuesAsync(List<NodeId>, List<DataValue>)` |
| 浏览 | `CompletableFuture<List<ReferenceDescription>> browseAsync(NodeId[, AddressSpace.BrowseOptions])`、`browseNodesAsync(...)` 返回 `UaNode` |
| 订阅 | `new OpcUaSubscription(client[, double publishingInterval])` → `createAsync()` → `addMonitoredItem(...)` → `createMonitoredItems()`（返回逐项结果）；`deleteAsync()` 释放 |

**待落地时的注意点**：
- `OpcUaMonitoredItem` 的构造签名需再确认（本轮未实测），订阅部分是 OPC UA 模块的主要不确定点。
- `AddressSpace.getBrowseOptions()` / `modifyBrowseOptions(...)` 是 **`synchronized` 方法**（Milo 自身实现），
  调用它们不违反本仓「禁 synchronized」约定（约束的是我们自己的代码），但**不要在虚拟线程上调用**。
- 读取用 `readValuesAsync` 批量接口：OPC UA 的 `Read` 服务天然支持一次请求多个 NodeId，
  不应逐点位调用（这正是协议相对 Modbus 的优势），部分失败由每个 `DataValue.statusCode` 表达。
- `BrowseExtension` 是本仓**唯一尚未被任何协议实现的扩展点**；OPC UA 的 `browse` 是其天然落点，
  落地时应同时验证「扩展点从 `ProtocolConnection.unwrap` 取出」这条链路。

**M1 第二轮独立审核结果（2 份报告，7 个 P0，已全部修复）**

审核方式：审核 agent 用 `/tmp` 下的探针 harness **直接调用仓库已编译的真实类**做实证复现（仓库零改动），
因此下列结论都带可复现证据，而非静态推断。

| 来源 | P0 问题 | 实证证据 | 修复 |
|---|---|---|---|
| 框架 | **令牌桶永不封顶**：`Math.min(capacity, ...)` 的结果算完丢弃，只有溢出为负才回写 | 空闲 5s 后 500 令牌变 **3024**；1200 次取令牌 **7ms** 跑完（应 ≥1400ms）。Spring 启动耗时数秒即触发，正好命中「启动风暴」这个唯一目标场景 | `updateAndGet(v -> min(capacity, v + refill))` |
| 框架 | **令牌等待持读锁** → `closeAll()`（需写锁）无界阻塞 | 6 并发 / 2 每秒即阻塞 **2958ms**；10 万 / 500 每秒外推 ≈ **200s** | 改三段式：入表 → 释放锁等待 → 重新取锁复核条目仍是自己的 |
| 框架 | `refillTokens` 定点乘法溢出 → 永久卡死 | rate=200000、1 天空闲 → 取令牌**永不返回**，桶恒 0 | 改「整秒 + 余数纳秒」的溢出安全写法 |
| Modbus | **忽略 `spec.tls()` → 配了 TLS 仍明文建链** | 实测 `TlsOptions.enabledDefault()` + `tcp://` → **OPENED over PLAINTEXT** | 独立再拦一次 + 防复发用例 MBE-01b |
| Modbus | **静默假死不可检测**：keep-alive 只看本端 `isConnected()`；`read()` 任何情况都不异常完成 | 对端 accept 后永不响应 → `read` 正常完成、全 BAD、`state()=ONLINE`、`ping().alive=true` | keep-alive 改发真实协议请求；read 在「链路不可用且全部点位失败」时异常完成 |
| MQTT | **非法发布主题打挂整批写** | 批量 `[ok, 坏(+), ok]` → 整批异常完成、第 3 项未执行；空主题 → **同步抛出** | 主题校验 + builder 段 try/catch → 逐项失败 |
| MQTT | **scheme 与真实承载不符** | `ssl/mqtts/ws/wss` 全部 **OPENED against 明文 broker**，与 README 承诺直接矛盾 | 移出白名单 |
| MQTT | **断线永不通知框架**：`onConnectionLost` 是死代码 | broker 永久不可达时 `whenClosed` 永不完成、`state` 永为 ONLINE、`ping` 永远 alive | 注册 `addDisconnectedListener` 接线 |

**本轮修复动作自身引入、并被自查/复审发现的问题（已修）**：
- `ModbusSession.read` 在 `thenApply` 内 throw → 被包成 `CompletionException`，违反 C11 → 加 `Stages.normalize`
- `MqttAdapter` 用实例字段 `pendingConnection` 承载断线回调是**共享可变状态**（适配器是单例、可服务多条链路，
  A 断线会把 B 标记为 FAILED）→ 改用 `open()` 内的局部 holder
- Modbus 链路级失败判定会误伤「全部地址都写错」的配置错误 → 收紧为「本来有合法点位」

> **流程教训（第二次同类）**：本轮又出现「修复动作本身引入新缺陷」。上一轮的教训是「提交信息与代码不一致」，
> 这一轮是「改完不复审就会引入新问题」——两轮都说明：**改动之后必须再跑一次独立复审，且不能由改动者自己判定完成**。

**第三轮（对抗性复审 7 个 P0 修复本身）结果 —— 又发现 5 项，含 1 个我在修复中引入的 P0**

审核 agent 用探针实测出**修复动作本身引入的回归**，这是本里程碑第三次同类问题：

| 级别 | 问题 | 实证 |
|---|---|---|
| **P0（我引入）** | Modbus 保活探针「任何 error 都判链路死亡」。真实 PLC 对未映射地址（探针读的 0x0000）回异常码 02 是常态，而这是**合法且健康**的响应 | 实测：链路被判死、会话关闭、订阅取消、采集永久停止，而**通道根本没断（数据仍在流）** |
| P1 | MQTT 断线后 `onConnectionLost` 不 disconnect，而 `close()` 因 `closed` 已置位变成空操作 | 实测 2.5s 后客户端仍 `DISCONNECTED_RECONNECT` → 无人持有的孤儿客户端持续重连 |
| P1 | MQTT 部分成功订阅留下**僵尸订阅**：失败时不回滚已生效的过滤器，且句柄永不入表 | 实测宿主被告知订阅失败，却仍收到 1 条数据，且拿不到句柄取消 |
| P1 | `MqttSession.subscribe` 透传 `CompletionException`，违反 C11 | 实测交付 `CompletionException(IllegalArgumentException)` |
| P1 | 停机仍被在途令牌等待拖住：`closeAll` 快照含未完成的 `fresh`，要等它们拿到令牌 | 实测 6 并发/2 每秒仍阻塞 **2.8s**（上一轮只把等待移出读锁，未解决这一层） |

前四项已修复并新增 MBE-16 等回归用例；最后一项通过「`acquireConnectToken` 检测到 closed 立即中止等待」修复。

> **流程教训（第三次）**：本里程碑三轮审核，每一轮都发现「上一轮修复引入的新问题」。
> 这不是审核过度，而是**改动本身就有风险**的客观证据。结论：**修复动作必须与原始改动同等对待**，
> 改完必须再复审，且不能由改动者自己判定完成。此外，复审还指出两处「测试通过得很便宜」——
> 例如 MQE-02c 的 4 个样例全被前置校验拦下，真正的 try/catch 修复分支**完全没被验证到**。
> 这类「绿灯但没测到点」的用例比缺用例更危险。

**尚未修复（复审提出，按优先级列入下轮）**：MQTT `dispatch()` 在 broker IO 线程同步执行宿主 `DataListener`
（DESIGN 承诺的 `ThreadIdentityGuard` 全仓不存在）· `PollingSubscriptionManager` 的 `deliver()` 抛异常会让订阅
静默停摆、`samplingInterval<=0` 无校验（实测 200ms 内 6.5~7.9 万次轮询独占单定时器线程）· 空闲关闭后无重连/重绑定
（`reconnect*` 配置零消费，框架自报「已绑定」而实际不可用）· `IotLifecycle.bind` 未持设备级锁（并发 bind 泄漏
session 与 handle）· SRC-01 门禁漏判第三方包名内联 FQCN（`NettyTransport` 现存一处 R10 违规）。

**M1 的取舍与实测结论**：
- **MQTT 基线定在 3.1.1 而非 5.0**：测试过程中发现 Moquette 只支持 3.1.1，
  进而复核了工业现场的实际分布——绝大多数 broker 与设备只支持 3.1.1，5.0 专属能力在现场几乎用不上。
  因此定为 3.1.1 基线（兼容性优先），并**移除了 MQTT 5 专属配置项**（留着就是「配置了不生效」）。
- **Modbus 地址不做自动猜测**：各厂商文档对同一寄存器的写法从 `40001` 到 `0` 到 `holding:1` 不等，
  猜错的表现是「读到了值但值不对（整体偏移一位）」，比直接报错难排查得多。
- **MQTT 载荷不做 JSON 解析**：载荷是不透明字节，协议层不做结构假设。

**M0 已知未完成项（复审发现，不影响骨架可用性，列入 M1 前置）**

| 级别 | 项 | 说明 |
|---|---|---|
| P1 | 空闲检测未接线 | `IdleStateHandler` 已装入但无 `IdleStateEvent` 消费点，`idle-interval` 配了不生效；且缺 `ReadTimeoutHandler`。M0 默认值为 0（不启用），故不构成现行故障 |
| P1 | 建链速率限制未实现 | `ypbin.iot.connection.connect-rate-limit` / `connect-rate-jitter` 已声明但无执行点——**10 万连接的建链风暴目前没有闸门**，属 M1 必做项 |
| P1 | 部分配置项零消费 | `scheduler.shutdown-timeout`、`devices.probe-before-bind`、`devices.default-poll-interval` 已声明未消费（本仓自列的「配置静默失效」坑） |
| P1 | 英文异常字面量 | 约 80 处 JDK 异常仍用英文字面量（`IllegalArgumentException` 等），未走消息键；需先裁决「编程错误类是否豁免」 |
| P1 | 架构门禁缺 4 类规则 | 内联 FQCN / `@Bean` 条件 / `ordinal` / autoconfig 登记注册，文档称已强制但实际未实现；且 `AGENTS.md` 引用的 `RegistrationDiscoveryTest` 本仓不存在（注册可见性断言目前只在 starter 的 CFG-08 中） |
| P2 | `closeAll()` 终止性语义 | 调用后注册中心永久失效且无 reopen；名字暗示「可继续用」，需在迁移说明中标注 |
| P2 | `maxFrameLength < delimiter` 校验位置 | 已在 transport 抛 `IllegalArgumentException`，但更应前移到 `FramingSpec` 构造期 |
| P2 | `AdapterSettings.extended()` 未知 key 校验 | 三处 Javadoc 承诺「未知 key 必须 warn」，无执行点 |

### 7.4 M1 之后的质量门禁

每个协议模块合入前必须满足（缺一不可）：

1. 通过 TCK 一致性测试套件（含 §7.3 的并发安全用例集）；
2. `ApplicationContextRunner` 装配测试覆盖四种场景（默认装配 / 开关关闭 / 宿主覆盖 / 协议库缺失）；
3. 至少一个真机或容器化的集成测试（协议模拟器）；
4. **故障注入测试**：半包 / 乱序 / 超时 / 断连 / 恶意长度字段，全部不崩且诊断信息明确；
5. **模糊测试**：对协议报文解析器跑 `com.code-intelligence:jazzer-junit:0.30.0`，无未捕获异常、无 OOM；
6. **性能基准**：用 `org.openjdk.jmh:jmh-core:1.37` 给出该协议的 P50/P99/P999 延迟与吞吐基线，纳入回归对比；
7. **pinning 检查**：跑一遍压测，`jdk.VirtualThreadPinned` 事件次数与累计时长在阈值内（§5.5）；
8. 许可证门禁通过（§5.2），且**传递依赖已按 E10 完成裁剪**；
9. 协议特有坑写入模块 README（如 SNMP v3 的 USM、ONVIF 的时间偏差与时钟要求、KNX 的 tunneling/routing、
   Modbus 的地址基准、S7 的 PUT/GET 与优化块访问）；
10. **若该协议引入了 GPL/LGPL 类库，模块 README 必须包含许可边界声明**（KNX 的示例见 §3.3）。

### 7.5 无硬件验证矩阵（个人项目的关键一节）

**个人项目最大的现实约束不是技术，是"没有设备"**。所以每个协议在立项前必须先回答：
**我拿什么验证它？** 下表给出每个协议的免硬件验证手段（均已核实制品存在）。

| 协议 | 免硬件验证手段 | 说明 |
|---|---|---|
| **通用 TCP / UDP** | 自写回显服务（几十行） | 无依赖 |
| **HTTP / WebSocket** | WireMock / OkHttp `MockWebServer` / 一个 Spring Boot 小应用 | 无依赖 |
| **Modbus TCP** | **`ModbusTcpServer`（digitalpetri 同库自带）**——已核实 2.1.6 源码含 `server/ModbusTcpServer.java`<br>（注意：`modbus-slave-tcp` 制品只到 **1.2.2**，2.x 用 `modbus` 核心里的 `ModbusTcpServer`） | 与被测端**同库同源**，行为最一致 |
| **Modbus RTU** | **`socat` 造虚拟串口对**：<br>`socat -d -d pty,raw,echo=0 pty,raw,echo=0` → 得到两个 `/dev/pts/N` 互连 | 无需串口硬件；Windows 下用 com0com |
| **OPC UA** | **Milo `milo-sdk-server`**（同库自带，能力完整） | 与客户端同源，可实现完整 AddressSpace、订阅、历史 |
| **MQTT** | Testcontainers 起 **Mosquitto / EMQX / HiveMQ CE** | 一条 `@Container` 即可 |
| **SNMP** | Testcontainers 起 **`net-snmp`（snmpd）** 容器，或用 **SNMP4J 的 Agent API** 自建 | 后者可编程构造任意 OID 与 Trap |
| **CoAP** | **Californium 的 `californium-core` 自带 server** | 同库自带 |
| **CAN / CANopen** | **Linux `vcan` 虚拟总线**（无需硬件）：<br>`sudo modprobe vcan && sudo ip link add dev vcan0 type vcan && sudo ip link set up vcan0` | 这是 CAN 类协议最大的便利——**完全不需要 CAN 硬件** |
| **BACnet/IP** | **BACnet4J 自身的 `LocalDevice` + 对象模型**（可做虚拟设备） | 我们只做客户端，用它的服务端能力做对端 |
| **KNX** | **`com.github.calimero:calimero-testnetwork:2.6`**（已核实存在）+ `calimero-server` | Calimero 官方测试网络，专为无硬件测试设计 |
| **S7** | Docker 化的 S7 模拟器（Snap7 server 镜像 / `python-snap7` 起 server） | 验证 PLC4X 驱动的连通与读写；**但无法验证真机型号差异** |
| **EtherNet/IP** | **`org.apache.plc4x:plc4j-driver-simulated:1.0.0`**（已核实存在） | ⚠️ 只能验证**框架管路**，不能验证真实 CIP 语义 |
| **GB28181** | SIP 侧用 `sipp` / PJSIP 做对端；**媒体面无法免硬件验证** | 信令可测，媒体不可测 → 这正是「媒体外置」的另一个理由 |
| **ONVIF** | 无成熟模拟器（可用 ONVIF Device Manager 手动验证） | ⚠️ 自动化验证困难 |
| **GB/T 26875** | **纯报文层，用标准附录样例做黄金报文测试** | 无需硬件（协议本身是字符/字节报文） |
| **OPC DA / PROFINET / LonWorks** | ❌ **无免硬件手段** | 已按 D2 移出规划 |

**三条从这张表得出的结论**：

1. **核心档（M1）的三个协议全部可 100% 免硬件验证**——OPC UA 与 Modbus 甚至能用到**与被测端同源**的模拟器，
   这是它们被选为第一批的又一个理由（原先的理由是"抽象维度覆盖全"，见 §7.2）。
2. **CAN 与 KNX 比想象中好做**——`vcan` 与 `calimero-testnetwork` 让它们不需要任何硬件，
   所以 CAN 被提到 M3（而不是"等有硬件"）。
3. **真正做不了的是 OPC DA / PROFINET / LonWorks**，以及**验证不充分的** ONVIF 与 GB28181 媒体面——
   这与 D2/D5 的判断一致。

> **对个人项目的建议**：**先把「模拟器/验证手段」作为立项前置条件**。
> 一个协议如果在表里找不到免硬件验证方式，就**不要开始写**——写出来的代码没有反馈，
> 只会在将来第一次接真设备时集中爆发问题，而那时你已经忘了当初的实现细节。

---

### 7.6 里程碑依赖与并行

```
M0 骨架 ──┬──> M1 打透抽象 ──┬──> M2 通用与楼宇 ──┬──> M3 行业扩展 ──> M4 音视频与消防
          │                    │                    │
          └──> 压测基线 ────────┴──> 持续压测门禁 ───┴──> 长稳门禁
          
并行轨道（不阻塞协议开发）：
  · 可观测性：Actuator 端点 / Micrometer 指标 / 诊断链
  · 协议模拟器：Modbus Slave / OPC UA Server / MQTT Broker / BACnet 虚拟设备
  · 文档：站点同步（复用母仓 ypbin-site 的同步机制）
  · 集群：设备归属分片 / 下行路由 / 故障接管限速（M2 后启动）
```

> **并行原则**：协议模拟器必须与 M0 同步开工——没有模拟器，10 万连接与故障注入测试都无从谈起，
> 而这两个能力恰恰是接入框架与业务系统最大的质量分水岭。
