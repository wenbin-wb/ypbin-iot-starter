# 楼宇 / 消防 / 视频监控类协议 Java 库选型表（截至 2026-09 检索）

> 检索时点：本次会话（2026-09）。
> **版本号取值规则**：本次实测发现 Maven Central 的 Solr 检索接口（search.maven.org/solrsearch）索引明显滞后（例：Solr 报 SNMP4J `3.9.5`，而权威 `maven-metadata.xml` 实为 `3.13.1`；Solr 报 JavaCV `1.5.11`，实为 `1.5.14`）。因此**所有版本号一律以 `repo1.maven.org/.../maven-metadata.xml`、制品 POM、GitHub Release/Tag 与厂商仓库为准**，Solr 结果仅作交叉参考。
> **标注约定**：凡本次未能直接落到一手来源的结论，一律写明「未验证」。

---

## 0. 总览表

| # | 协议 | 推荐库（坐标） | 最新稳定版本 | 许可 | 活跃度 | JDK 17/21 | 形态 |
|---|---|---|---|---|---|---|---|
| 1 | BACnet/IP | `com.infiniteautomation:bacnet4j`（RadixIoT 私服） | `6.1.1`（`7.0.0-alpha.5` 为预发布） | **GPL-3.0 + 商业授权** | 高（2026-09-02 仍有提交） | 未声明（未验证） | 客户端 + 服务端 |
| 2 | KNX / KNXnet/IP | `com.github.calimero:calimero-core` `calimero-server` `calimero-device` | `2.6` | **GPL-2.0 + Classpath Exception** | 高（2026-09-13 仍有提交） | 是（Gradle 元数据声明 17） | 客户端 + KNXnet/IP 服务端 |
| 3 | LonWorks / LONtalk | **无（不存在 Java 实现）** | — | — | 生态退场中 | — | 只能走网关转换 |
| 4 | GB/T 26875 消防 | **无（Maven Central 零制品）** | — | — | 仅 1 个 MIT 玩具仓库 | — | 需自研（Netty） |
| 5 | GB28181 | SIP：`javax.sip:jain-sip-ri`；框架：`io.github.lunasaw:sip-common/sip-gb28181`；媒体：`org.bytedeco:javacv-platform` | `1.3.0-91` / `1.8.7` / `1.5.14` | 各异（见 §5） | SIP 栈停更，框架活跃 | 是 | 信令 + 外置媒体面 |
| 6 | ONVIF | `org.homio:onvif`（Central）或 `fpompermaier/onvif`（JitPack）或 CXF 自生成 | `1.1.1` / 无 Central 版本 / `4.2.3` | MIT / Apache-2.0 / Apache-2.0 | 均偏弱（见 §6） | 是 | 客户端 |
| 7 | SNMP v1/v2c/v3 | `org.snmp4j:snmp4j`（+ `snmp4j-agent`） | `3.13.1`（agent `3.10.1`） | Apache-2.0 | 高（2026-08-02 发版） | 是（字节码 9） | Manager + Agent |

---

## 1. BACnet/IP（Java）

| 项 | 结论 |
|---|---|
| 推荐库 | **BACnet4J**（上游 `com.infiniteautomation:bacnet4j`，主线由 RadixIoT 维护） |
| 坐标 | `com.infiniteautomation:bacnet4j`（5.0+）；`com.serotonin:bacnet4j`（5.0 以前，已过时） |
| 版本 | **稳定最新：`6.1.1`**；官方 metadata 的 `<release>` 字段为 `7.0.0-alpha.5`（预发布）；已发布序列：5.0.0/5.0.1/5.0.2/6.0.0/6.0.1/6.0.2/6.1.0/**6.1.1** + 6.2.0-alpha、7.0.0-alpha.1~5 |
| 仓库 | **不在 Maven Central**。必须加厂商仓库 `https://maven.mangoautomation.net/repository/ias-release/`（README 原文给出）；metadata `lastUpdated=20260902` |
| 许可证 | **GPL-3.0（GitHub license 字段）+ 商业授权可购买**（README：「Commercial licenses are available…sales@radixiot.com」「Commercial licensers can pay an upgrade fee」）。闭源商业产品**必须**走商业授权 |
| 维护活跃度 | RadixIoT/BACnet4J：220 stars、122 forks、`pushed_at 2026-09-02`、open issues 0；最近一次带 release note 的正式发布为 v6.0.1（2024-11-21），其后有 6.0.2 / 6.1.0 / 6.1.1 / 7.0.0-alpha.5 等 tag |
| 能力 | 纯 Java，**BACnet/IP v4、IPv6、MS/TP 都支持**；含 LocalDevice 与完整对象模型（可做服务端/虚拟设备）、BBMD、Foreign Device、COV、内建告警、调度对象。5.0.0 起自称 "Fully BTL Certifiable" |
| 协议版本 | README 头部写 "protocol Version 1 Revision 19"（偏旧表述）；7.0.0 release note 提到对齐 **135-2024 / protocol revision 30** 与 **BACnet/SC（Secure Connect）**。即：6.x 为成熟稳定线，7.x 才是新规范线 |
| JDK 17/21 | **官方未声明最低 JDK（未验证）**。README 代码示例使用 `var`（≥JDK10）。建议 6.1.1 在 17/21 上先做一次冒烟实测 |
| 阻塞/非阻塞 | 3.0 起「Blocking request calls have been replaced with non-blocking promises/callbacks」（README 原文），核心为 Promise/Future + 监听器；6.0.2 起 I-Am 等事件回调改由线程池派发而非 transport 线程 |
| 线程安全 | **未声明（未验证）**。实践上共享单个 LocalDevice 时应自行串行化写操作与对象访问 |
| 备选 1 | `org.apache.plc4x:plc4j-driver-bacnet:0.13.1`（**Apache-2.0**，metadata lastUpdated 2025-08-29）。PLC4X 定位是采集侧驱动（客户端），**未见服务端/设备对象模型能力（未逐条验证）** |
| 备选 2 | JitPack 构建：`com.github.RadixIoT:BACnet4J:6.0.2`（JitPack API 显示 `6.0.2`/`v6.0.2`/`6.0.1` 构建 ok，其余 tag 多为 Error）。**许可仍是 GPL-3.0**，只是换了个分发渠道 |
| 备选 3（**不要用**） | `net.solarnetwork.external:net.solarnetwork.external.bacnet4j:6.0.0.SN01`（Maven Central，POM **自称 Apache-2.0**，但上游是 GPL-3.0 → **许可声明冲突，法务风险高**）；`org.code-house.bacnet4j:api:1.3.0-beta3`（只是 OSGi wrapper，其 POM 仍 `optional` 依赖 `com.infiniteautomation:bacnet4j`，**不是替代品**） |

**选型结论（BACnet/IP）**：Java 侧事实标准仍是 BACnet4J，客户端+服务端能力齐全、仍在维护，但两件事必须先谈清楚——**① 它不在 Maven Central，必须挂 RadixIoT 私服（或自建 Nexus 代理 + 冻结制品）；② GPL-3.0，闭源商用要买商业授权**。协议版本上建议锁 **6.1.1**（稳定、BTL 可认证语义），**7.0.0 系列暂缓**：其 release note 明示大量破坏性变更（对象必须 `localDevice.addObject(...)`、`Unsigned32` 取代 `UnsignedInteger` 会导致既有取值代码 `ClassCastException`、分段传输按 135-2020ch-1 重写、`Max_Segments_Accepted` 默认值与可写性变更、解码更严格等）。若只是做数据采集且必须 Apache-2.0，可评估 PLC4X 的 BACnet 驱动；若需要"设备/服务端"角色，PLC4X 大概率不满足。

---

## 2. KNX / KNXnet/IP（Java）

| 项 | 结论 |
|---|---|
| 推荐库 | **Calimero**（calimero-project） |
| 坐标与版本 | `com.github.calimero:calimero-core:2.6`（核心：KNXnet/IP 客户端、TP/FT1.2/RF/USB 接入、cEMI、DPT 编解码）<br>`com.github.calimero:calimero-server:2.6`（**KNXnet/IP 服务端**：KNX IP、KNX(RF) USB、FT1.2、TP-UART）<br>`com.github.calimero:calimero-device:2.6`（设备/组对象模型，用于实现虚拟 KNX 设备）<br>辅助：`calimero-tools:2.6`、`calimero-gui:2.6`、`calimero-testnetwork:2.6`、`calimero-rxtx:2.6`（串口，需 native）<br>版本证据：Central `maven-metadata.xml` release=**2.6**、`lastUpdated=20250626` |
| 许可证 | **GNU GPL v2 + Classpath Exception**（POM `<licenses>` 原文）。属弱 copyleft：链接使用通常可接受，但**修改库本身需回馈源码**；是否有官方商业授权渠道**未验证**。企业闭源分发前请法务确认 |
| 维护活跃度 | 高。`calimero-core` 仓库 161 stars、`pushed_at 2026-09-13`（当日仍有提交）；2.6 发布于 2025-06-26 |
| JDK 17/21 | **JDK 17 为基线**：Gradle module metadata 明确 `org.gradle.jvm.version = 17`，依赖 `org.slf4j:slf4j-api:2.0.17`。JDK 21 未官方声明（未验证），一般兼容 |
| 阻塞/非阻塞 | 事件/监听器驱动 + 显式连接管理（`KNXNetworkLink`、`ProcessCommunicator`），同步与异步请求均有 API（**逐条语义未验证**） |
| 线程安全 | 未声明（**未验证**）。连接/链路对象建议单线程持有或显式加锁 |
| 备选 1 | `li.pitschmann:knx-core:0.5.1`（Central metadata `lastUpdated=20220616` → **已停滞 4 年**；许可证未验证） |
| 备选 2 | `org.openmuc.framework:openmuc-driver-knx:0.20.1`（2024-11-05，**GPL-3.0**，且 POM 内部依赖 `com.github.calimero:calimero-core:2.3` 与 `org.openmuc:jrxtx:1.0.1`）→ 许可更严、版本更旧，仅作参照 |

**选型结论（KNX）**：Java 生态基本是 Calimero 一家独大，且**客户端与 KNXnet/IP 服务端能力都齐**（core 客户端 + server 服务端 + device 设备模型），JDK 17 基线明确。主要风险不是技术而是**许可（GPLv2+CPE）**与**接入方式**：KNXnet/IP 有 *tunneling*（单播，适合 NAT/云侧）与 *routing*（多播 224.0.23.12，跨网段常被禁）两条路，跨公网/MQTT 场景必须选 tunneling 或加 KNX 网关；串口接入（FT1.2/TP-UART）走 `calimero-rxtx`，**带 jrxtx native 依赖，平台相关**。若要求纯 Apache/MIT 许可，Java 侧没有合格替代品（`knx-core` 已停滞），只能自研或改用网关转 BACnet/Modbus。

---

## 3. LonWorks（LON / LONtalk）

| 项 | 结论 |
|---|---|
| Java 库 | **不存在可用的 Java 实现**。Maven Central 检索 `lonworks` → **numFound = 0**；LON/LonTalk/ISO/IEC 14908 无任何 Java 制品 |
| 开源协议栈现状 | 唯一活跃的开源 LON 栈是 **C 语言**：`izot/lon-stack-dx`（EnOcean LON Stack DX，**MIT**，C，10 stars，`pushed_at 2026-06-27`，实现 ISO/IEC 14908 系列）；配套 `izot/lon-driver`（Linux 下 U10/U20/U60/U70 USB LON 接口驱动） |
| 生态退场证据 | ① Gesytec（2025-11-04 客户公告）：因「客户普遍计划中期淘汰 LONWorks」且「Echelon/Renesas 供应的收发器等关键器件停产」，**将退出 LON 业务**；「所有含原始 Neuron 芯片的 LON 产品停产」「不再提供 OEM 开发与新产品设计」；例外供货窗口最长至 2029 年。② Renesas 社区存在 *Neuron Chip EOL* 讨论帖 |
| 历史/商用路径 | ① **i.LON 100 / SmartServer 2.0** 的 SOAP/Web Services API —— 已被后续产品线取代，**现行可采购性与生命周期未验证**；② **OpenLNS / OpenLDV COM API**（仅 Windows，32 位驱动）→ Java 只能经 JACOB/JNA 调 COM；③ **Tridium Niagara Framework**（Java 平台，商业授权，含 LON 驱动）—— 属商业闭源方案，**具体 LON 驱动版本/可用性未验证** |
| 推荐做法 | **网关转换**：现场 LON 设备经 LON↔BACnet/IP 或 LON↔Modbus TCP 网关汇聚，Java 侧只对接 BACnet（§1）或 Modbus；把 LON 复杂度与硬件生命周期风险留在网关厂商侧。**不建议**新项目自研 LON 栈，也不建议在 Windows 上重建 COM 桥 |
| Maven 坐标 / 版本 / 许可 / JDK | **无（不适用）** |

**选型结论（LonWorks）**：结论明确——**放弃 Java 直连 LON**。生态（芯片、收发器、工具商）正在退出，开源实现只有 C，Java 侧零制品；任何"用 Java 直读 LON"的方案都等于自研协议栈 + 依赖停产硬件。唯一工程化的路径是**在网关处把 LON 转成 BACnet/IP 或 Modbus TCP**，Java 侧复用本文其他协议的成熟库。

---

## 4. GB/T 26875（城市消防远程监控系统 · 用户信息传输装置/报警传输网络通信协议）

| 项 | 结论 |
|---|---|
| 开源 Java 实现 | **没有可用于生产的库**。Maven Central 检索 `26875` → **numFound = 0**；GitHub 全站检索 `26875` 仅 32 个仓库，绝大多数无关 |
| 可参考（不成熟，勿直接依赖） | ① Gitee `yanboot-iot-community/analysis-of-gbt26875-protocol`（《GBT26875协议解析》，**Java + MIT**，© 2025 研博数据；**仅 3 次提交、0 star**，含 `com.yanboot.iot.utils.gb26875.properties.MessageFactory` 等报文工厂）→ **只能当报文结构参考**；② GitHub `shootingfans/codec_gb26875_3_2011`（**Go 语言，GPL-3.0**，18 stars，最后提交 2023-03-14）→ 可用于字段交叉验证，语言不符且 GPL；③ GitHub `yangyiyuan/GB-T-26875.3-2011`（PHP/资料，2018）→ 参考价值有限 |
| **标准版本（最大风险点）** | 见下表 —— **2026 年该标准族正在整体换版** |
| 技术建议 | 报文层用 **Netty**（`io.netty:netty-bom:4.2.18.Final`，或 4.1 线的 `4.1.138.Final`）自研编解码：定长头 + 长度域 + 校验（CRC/累加和）+ 心跳/注册/超时重传状态机；把「2011 版字段模型」与「业务语义」彻底解耦，避免 2027 年换版时重写业务 |
| 许可 | 自研无第三方 copyleft 风险；若参考上述 MIT 仓库需保留版权声明（并注意"不照抄"原则） |

**GB/T 26875 标准族现状（全国标准信息公共服务平台核验）**

| 标准号 | 名称 | 状态 | 关键日期 |
|---|---|---|---|
| **GB/T 26875.3-2011** | 城市消防远程监控系统 第3部分：报警传输网络通信协议 | **现行** | 2011-07-29 发布 / 2011-11-01 实施；**2025-07-01 复审结论为「修订」** |
| **20252122-T-906** | 城市消防远程监控系统 第3部分：**用户信息传输装置与应用支撑平台通信协议**（修订计划，名称/范围已变） | **正在审查** | 计划号 20252122-T-906 |
| **GB 26875.9-2026** | 城市消防远程监控系统 第9部分：用户信息传输装置（**强制性**） | 即将实施（**全部代替 GB 26875.1-2011**） | **2026-02-27 发布 / 2027-03-01 实施** |
| GB/T 26875.1-2026 | 城市消防远程监控系统 第1部分：通用技术要求 | 已发布 | — |
| GB/T 26875.10-2026 | 城市消防远程监控系统 第10部分：消防设施信息采集装置及接口要求 | 已发布 | — |

**选型结论（GB/T 26875）**：**必须自研，且必须按"换版双轨"设计**。现状是：现行协议标准仍是 2011 版，但它已进入修订审查，同时 2026 年新发布了强制性 GB 26875.9-2026（2027-03-01 实施，取代 GB 26875.1-2011）以及配套的 GB/T 26875.1-2026、GB/T 26875.10-2026。因此工程上应当：编解码层与业务层分离、协议版本可插拔（策略/版本号路由）、用标准附录样例做黄金报文测试；不要把 2011 版字段写死在实体与数据库里。开源侧只能借结构、不能借代码。

---

## 5. GB28181（视频监控国标：SIP + RTP/PS）

### 5.1 SIP 协议栈

| 项 | 结论 |
|---|---|
| 推荐 | `javax.sip:jain-sip-ri:1.3.0-91`（事实标准，几乎所有国标平台都用它） |
| 版本证据 | Central `maven-metadata.xml`：release=`1.3.0-91`，**`lastUpdated=20210825`**，制品发布于 2018-03-23 → **事实停更** |
| 许可证 | 未见明确 license 字段（POM 无 `<licenses>`）→ **许可证未验证（需按 JAIN-SIP/NIST 原始条款确认）** |
| POM 关键事实（实测） | ① 依赖 `javax.sip:jain-sip-api:1.2.0` 为 **`provided` 作用域** → **业务方必须自己显式引 API 包，否则运行期 NoClassDefFoundError**；② 依赖 `log4j:log4j:1.2.14`（**Log4j 1.x**，provided）→ 与 Log4j2/SLF4J 共存需桥接；③ `source/target = 1.7` → JDK 17/21 可运行，但无 module-info、字节码陈旧 |
| 备选 | `javax.sip:jain-sip-api:1.2.1.4`（API）；`org.jitsi:jain-sip-ri-ossonly:1.2.279-jitsi-oss1`（2016，Jitsi 分支）；Android 场景 `javax.sip:android-jain-sip-ri:1.3.0-91` |
| 结论 | Java 侧**没有活跃的 JAIN-SIP 主线替代品**（Restcomm/Mobicents 系列最后发布集中在 2017–2018）。要么用 1.3.0-91，要么用封装栈/自研栈 |

### 5.2 现成 GB28181 协议框架（可直接作为 Maven 依赖）

| 项 | 结论 |
|---|---|
| 推荐 | `io.github.lunasaw`（Sip-Proxy） |
| 坐标与版本 | `io.github.lunasaw:sip-common:1.8.7`、`io.github.lunasaw:sip-gb28181:1.8.7`、`io.github.lunasaw:sip-proxy:1.8.7`（parent pom）。Central metadata release=**1.8.7**，`lastUpdated=20260625`。**注意模块改名**：1.2.x 时代的 `gb28181-server` / `gb28181-client` / `gb28181-common` 已演进为 `sip-common` / `sip-gb28181` / `sip-gateway` / `sip-test`（1.8.7 起） |
| 许可 | **POM 声明 Apache-2.0，README 徽章写 MIT → 两个来源不一致，需向上游确认后再商用**（显著风险） |
| 能力 | 内置 **GB28181-2016 + GB/T 28181-2022** 全量 cmdType（README 有逐章节覆盖矩阵：控制 13/13、配置 11/11、查询 13/13、通知 8/8、应答 12/15）；同一 JVM 可同时做平台服务端与设备客户端，支持级联；BYE/SUBSCRIBE 走 dialog-aware（无 dialog 抛 `DialogNotFoundException` 而非静默吞 481）；JaCoCo 行覆盖 ≥80% |
| 依赖特征（坑） | `sip-common` 会带入 **jain-sip-ri + dom4j + log4j-over-slf4j + Spring Boot cache + micrometer + SkyWalking apm-toolkit + Guava + Caffeine** —— 「协议层」并不轻，且**必须与业务同 JVM**（`ServerTransaction`/`Dialog` 不可序列化）；多节点部署强依赖 Redis 做设备会话与 INVITE 路由 |
| JDK | POM properties 声明 **Java 17**（模块 plugin 里又写 11，实际编译基线以 11 计，运行于 17/21 均可） |

其它 Java 国标实现（生态印证，非库依赖）：GitHub 上 `gb28181 language:java` 共 56 个仓库，主流为整平台而非可复用库（例如你点名的那个开源平台、以及若干设备端/客户端模拟实现）。

### 5.3 你点名的开源平台（wvp-pro）实测依赖

`648540858/wvp-GB28181-pro`：版本 **2.7.4**、**Java 21**、Spring Boot **3.4.4**、MIT、7.3k stars、`pushed_at 2026-08-29`。其 pom 中 SIP 依赖为 `javax.sip:jain-sip-ri:1.3.0-91`，并显式引入 `org.slf4j:log4j-over-slf4j:2.0.17`（正是为压掉 JAIN-SIP 的 Log4j 1.x）；**pom 中不存在任何媒体/解码库（无 JavaCV、无 FFmpeg 绑定）** → 印证下一条。

（该品牌名仅因你在需求中点名而出现，可自行删除。）

### 5.4 RTP / PS 解复用 / 媒体库

| 层 | 选型 | 版本 / 许可 | 说明与坑 |
|---|---|---|---|
| 架构结论 | **Java 只做 SIP 信令 + 调度，RTP 收流与 PS 解复用交给独立流媒体服务（C/C++）** | — | 主流国标平台（含上述 2.7.4 版本）Java 侧**零媒体依赖**，媒体面走外置流媒体服务；这是最稳的路线，天然规避 native 与 HEVC 问题 |
| 若必须纯 Java 处理 PS | `org.jcodec:jcodec:0.2.5` | 2019-06-16 后**停更**；README 声明 **FreeBSD License**（POM 未核验） | README 明确支持 **MPEG PS demuxer / MPEG TS demuxer**，源码含 `org.jcodec.containers.mps.MPSDemuxer`、`MTSDemuxer`、`MPSUtils`、`PESPacket`（已核验存在）→ 可剥出 H.264 裸流。**但**：能力清单里**没有 H.265/HEVC**，而 2022 版国标与新型 IPC 大量使用 H.265；README 自述纯 Java 解码比 native 慢约一个数量级 |
| Native 媒体 | `org.bytedeco:javacv-platform:1.5.14` / `org.bytedeco:ffmpeg-platform:8.1.2-1.5.14` | Central metadata 实测 **1.5.14（2026-08-10，内含 FFmpeg 8.1.2）**；Apache-2.0（**POM 未逐条核验**） | **带 native**：全平台 `javacv-platform-1.5.14-bin.zip` 约 **1.30 GB**；生产必须按目标平台裁剪（用 `javacv` + `ffmpeg` 而非 `-platform` 聚合包）。相对地，FFmpeg 8.x 原生支持 MPEG-PS 解复用与 H.264/H.265 解码，是纯 Java 路线的兜底 |
| RTP | **建议 Netty 自建 RTP 收包**（`io.netty:netty-bom:4.2.18.Final`，或 4.1 线 `4.1.138.Final`） | Central metadata 实测 4.2.18.Final，`lastUpdated=20260909` | Java 侧**无权威且仍在维护的通用 RTP 库**（`org.restcomm:media` 最后发版 2017）；国标场景只需要 RTP 头解析 + 抖动缓冲 + 丢包统计 + PS 组装，自建成本可控 |
| 音频/对讲 | 走流媒体服务或用 FFmpeg 侧处理 | — | G.711/AAC 的 RTP 打包与对讲（Broadcast/Talk）在 2022 版要求更严 |

**选型结论（GB28181）**：**信令层**用 `jain-sip-ri:1.3.0-91`（记得补 `jain-sip-api` + `log4j-over-slf4j`）或直接用 `io.github.lunasaw:sip-common/sip-gb28181:1.8.7` 少踩坑（但先解决其 Apache-2.0/MIT 声明不一致）；**媒体层**默认外置流媒体服务，Java 进程不要碰 PS/HEVC；确需纯 Java 时，JCodec 只能解 PS+H.264（无 HEVC、停更），生产级解码必须上 JavaCV/FFmpeg native 并做好平台裁剪。**协议差异坑**：2016 与 GB/T 28181-2022 在命令集、SDP、TCP 主动/被动、H.265 要求上均有差异，混接不同厂商设备时务必以 2022 版为基线并保留 2016 兼容开关；NAT 场景必须正确设置 `external-ip/external-port` 写入 `Via/Contact`；设备侧 INVITE 受 **Timer B = 32s** 约束，业务回包要控制在 30s 内或先回 180/200。

---

## 6. ONVIF（SOAP over HTTP + WS-Discovery）

| 项 | 结论 |
|---|---|
| 现实情况 | Java 侧**没有官方库**，也没有主流高质量的 Central 制品；三条可行路线见下 |
| 路线 A（能力最全，推荐做长寿命项目） | 用 **Apache CXF 从 ONVIF WSDL 生成客户端**：`org.apache.cxf:cxf-rt-frontend-jaxws:4.2.3`（Central metadata：release=4.2.3，`lastUpdated=20260805`；**Apache-2.0**；Spring Boot 3 需用 Jakarta 命名空间产物）。WS-Discovery 用 JDK `MulticastSocket`/Netty 自行组播到 `239.255.255.250:3702` |
| 路线 B（开箱即用但未上 Central） | `fpompermaier/onvif`（**Apache-2.0**，241 stars，102 forks，`pushed_at 2026-05-05`，20 open issues）—— 目前最活跃的 Java ONVIF 库。**未发布到 Maven Central**（`com/github/fpompermaier/onvif/maven-metadata.xml` 实测 404）；JitPack 有 master 构建（`com.github.fpompermaier:onvif:master-SNAPSHOT` 或 commit 号）→ 生产建议 fork + 自建私服/Git submodule 锁版本 |
| 路线 C（Maven Central 直取，均为小/停更项目） | ① `org.homio:onvif:1.1.1`：**MIT**，Java 21 编译，依赖 `jakarta.xml.soap-api:3.0.2` + `jakarta.xml.bind:4.0.2` + Netty(provided)；Central `lastUpdated=20250329`；GitHub `homiodev/onvif` 仅 1 star、最后 push 2025-03-29 → **社区极小，长期维护风险高**。② `com.github.03:onvif:1.0.9`：**Apache-2.0**，okhttp 4.9.3 + kxml2 2.3.0 + okhttp-digest，轻量（发现/PTZ/媒体），源自已归档的 `RootSoft/ONVIF-Java`（156 stars，archived，最后 push 2021-01-26），该 fork 最后 push 2022-05-23 → **停更** |
| 阻塞/非阻塞 | 取决于所选路线：CXF 支持同步/异步（`Dispatch`/`CallbackHandler`）；okhttp 系本身异步但库封装多为阻塞调用（**未逐条验证**） |
| 线程安全 | 未声明（**未验证**）。SOAP 客户端实例建议每设备或每线程隔离 |
| 关键坑 | ① **Profile 差异**（S/T/G/M）与厂商实现偏离，能力发现必须走 `GetCapabilities` 而非硬编码；② **WS-UsernameToken**：Nonce + Created + PasswordDigest（Base64(SHA1(nonce+created+password))），**设备与客户端时间偏差过大会直接认证失败**；③ WS-Discovery 多网卡主机必须显式指定组播网卡/IP；④ 部分设备 `GetSnapshotUri` 走 HTTP Digest（okhttp-digest 就是为此）；⑤ SOAP 1.2 + MTOM（媒体/事件服务）；⑥ **`javax.*` vs `jakarta.*`**：Spring Boot 3 / JDK 17+ 用 Jakarta，老库（jaxb/jaxws 2.x 系）会冲突；⑦ ONVIF 时间/时区字段（`UTCDateTime`）与本地时间混用 |

**选型结论（ONVIF）**：如果 ONVIF 只是"发现 + PTZ + 取流地址"这类轻功能，用 `org.homio:onvif:1.1.1`（MIT，Central 可取）能最快落地，但要接受"1 star 项目"的维护风险；如果 ONVIF 是关键能力（事件订阅、回放、媒体配置、多品牌兼容），**推荐 CXF + ONVIF WSDL 自生成**（Apache-2.0、可长期演进、完全可控），并把 `fpompermaier/onvif` 作为实现参考（Apache-2.0，注意它没有 Central 坐标）。

---

## 7. SNMP v1/v2c/v3（Java）

| 项 | 结论 |
|---|---|
| 推荐 | `org.snmp4j:snmp4j` |
| 版本证据 | Central `maven-metadata.xml`：**release = 3.13.1**，`lastUpdated=20260802`（**注意 Solr 检索接口仍显示 3.9.5，属索引滞后**）；javadoc.io 版本列表亦以 3.13.1 为最新 |
| 许可证 | **Apache-2.0**（POM `<licenses>` 原文，`http://www.apache.org/licenses/LICENSE-2.0.txt`） |
| JDK 17/21 | **兼容**：POM 中 maven-compiler-plugin 为 `source/target/release = 9` → JDK 9+；javadoc 版权串 "Copyright 2005-2026" 说明仍在持续发版 |
| Agent/告警接收 | `org.snmp4j:snmp4j-agent:3.10.1`（Apache-2.0，POM 依赖 `snmp4j:3.13.1`，同为 Java 9 基线）；（可选）`org.snmp4j:snmp4j-agent-db:3.8.2`（Apache-2.0，用 JetBrains Xodus 做嵌入式持久化） |
| 备选 1 | `org.sentrysoftware:snmp:2.0.00`（**LGPL-3.0**，Java 8 基线，`lastUpdated=20250214`，Sentry Software 维护）→ 许可更严 |
| 不推荐 | `westhawk:snmp:4_13`（2005）、`org.opendaylight.snmp:snmp:1.5.4`（2018，EPL）、`org.mobicents.*` 系列（2017 前）→ 均已停更十年量级 |
| v1/v2c/v3 支持 | SNMP4J 的公开定位是 v1/v2c/v3 全支持（含 USM/`MPv3`）。**本次 `snmp4j.org` 抓取失败，该点标记「未直接验证」**；坐标、版本、许可证、JDK 基线均已一手核验 |
| 阻塞/非阻塞 | 同时提供同步（`Snmp.get` 系）与异步（`send` + `ResponseListener`）API，传输层基于 `TransportMapping` + 线程池（**逐条 API 语义未验证**） |
| 线程安全 | 未声明（**未验证**）；实践上 `Snmp`/`TransportMapping` 实例应复用并避免并发改配置 |
| 关键坑 | ① **v3 必须先建 USM 用户与本地引擎 ID**（`MPv3.createLocalEngineID()`），认证/加密协议（MD5/SHA/SHA-2、DES/AES128…）必须与设备逐项对齐，否则报 `Unknown user name`/`Authentication failure`；② Trap/Inform 接收需绑 **162 端口**（Linux 上 <1024 需 root 或 `setcap`），并显式启动 `TransportMapping` 监听；③ **SNMP4J 2.x → 3.x 有较大 API 变更**，老代码迁移要改包与调用方式；④ 超时/重试/PDU 大小要与设备实际能力匹配（工业设备常只支持 v2c 与小 PDU）；⑤ 批量采集用 `GETBULK`（v2c/v3）而非逐个 `GET` |

**选型结论（SNMP）**：**SNMP4J 3.13.1 是唯一无需犹豫的选择** —— Apache-2.0、仍在活跃发版（2026-08）、JDK 9+ 基线可直接跑在 17/21、Manager/Agent/Trap 三件套齐全。唯一注意：v3 的 USM 配置比 v1/v2c 复杂得多，建议在配置层把"版本 + 认证协议 + 加密协议 + 引擎 ID"做成显式参数，并配超时/重试策略；若不能接受 Apache-2.0 之外无风险？其实 SNMP4J 已是 Apache-2.0，唯一的"备选"（LGPL-3.0）只会更麻烦。

---

## 8. 跨协议工程约束与风险清单

### 8.1 许可证总表（必须显著标注的）

| 库 | 许可 | 对闭源商用的影响 |
|---|---|---|
| BACnet4J（`com.infiniteautomation:bacnet4j`） | **GPL-3.0**（可购商业授权） | **必须**购买商业授权，或放弃闭源 |
| Calimero（`com.github.calimero:*`） | **GPL-2.0 + Classpath Exception** | 链接使用一般可接受；修改库体需回馈；无官方商业授权渠道（未验证） |
| OpenMUC KNX 驱动 | **GPL-3.0** | 高风险，仅作参照 |
| `shootingfans/codec_gb26875_3_2011` | **GPL-3.0**（且为 Go） | 只能读思路 |
| `org.sentrysoftware:snmp` | **LGPL-3.0** | 动态链接一般可接受，仍需法务确认 |
| SNMP4J / PLC4X / CXF / Netty / JavaCV | Apache-2.0 | 宽松，可用（JavaCV 的 POM 许可未逐条核验） |
| Sip-Proxy（`io.github.lunasaw:*`） | **POM=Apache-2.0，README=MIT（不一致）** | **先与上游确认**再用 |
| ONVIF：`fpompermaier/onvif`、`com.github.03:onvif` | Apache-2.0 | 宽松 |
| ONVIF：`org.homio:onvif` | MIT | 宽松 |
| `org.jcodec:jcodec` | README 声明 **FreeBSD License**（POM 未核验） | 宽松 |
| Gitee GB/T 26875 参考实现 | MIT | 需保留声明 |

### 8.2 二进制/分发风险

- **不在 Maven Central 的制品**：BACnet4J（必须加 RadixIoT 私服 → 建议自建 Nexus 代理并冻结版本）、`fpompermaier/onvif`（仅 JitPack/SNAPSHOT → 建议 fork 自建）。
- **带 native 的制品**：JavaCV/FFmpeg（按平台裁剪，勿用 `-platform` 聚合包，全平台包 ~1.3 GB）、`calimero-rxtx`（jrxtx 串口 native，平台相关）。
- **必须显式补依赖**：`jain-sip-api`（jain-sip-ri 把它标成 `provided`）。
- **日志桥接**：`log4j-over-slf4j`（JAIN-SIP 自带 Log4j 1.2.14）。

### 8.3 本次未能验证的事项（显式列出）

1. BACnet4J 的**最低 JDK 版本**与**线程安全承诺**（官方 README/POM 均未声明）。
2. `org.apache.plc4x:plc4j-driver-bacnet` 是否具备服务端/设备对象模型能力（未逐条验证其覆盖范围）。
3. Calimero 是否有**商业授权**渠道；KNX Secure（IP Secure/Data Secure）支持情况；JDK 21 官方兼容声明。
4. Tridium Niagara 的 LON 驱动可用性与版本（商业闭源，未见公开版本信息）。
5. i.LON 100 / SmartServer 2.0 与 SmartServer IoT 的现行销售与生命周期状态（仅见二手资料与厂商公告片段）。
6. SNMP4J v1/v2c/v3 支持与同步/异步 API 的**官方页面原文**（`snmp4j.org` 本次抓取失败；仅核验了 POM/版本/许可）。
7. 各 ONVIF 库的线程安全与异步语义细节。
8. `org.jcodec:jcodec` 的 Maven POM 许可证字段（仅核验 README 表述"FreeBSD License"）。
9. GB/T 26875 修订版（20252122-T-906）"正在审查"之后的具体发布/实施时间。
10. 所有库在 JDK 21 上的**实机运行验证**（本次为文献/元数据核验，未做编译与运行实测）。

### 8.4 落地顺序建议（若要做楼宇/消防/视频一体化平台）

1. **先做协议分层**：信令/采集（Java）与媒体面（外置流媒体）严格分离 —— GB28181 的经验同样适用于视频类需求。
2. **能网关化的就网关化**：LonWorks 必须网关转换；KNX 视网络条件选 tunneling；消防 26875 走自研 Netty 编解码并做版本可插拔。
3. **优先选 Apache/MIT 且活跃的库**：SNMP4J、CXF、Netty、JavaCV；把 GPL 系（BACnet4J、Calimero）作为"许可已谈定才引入"的项。
4. **所有远程调用**（SIP/RTP/SOAP/SNMP/BACnet）必须显式配置连接/读取超时、重试与降级，禁止无超时默认客户端。
5. **版本可复现**：不在 Central 的制品（BACnet4J、fpompermaier/onvif）一律经自建私服冻结，禁止直接依赖 `master-SNAPSHOT`。
