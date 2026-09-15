# ypbin-iot-starter

[![CI](https://github.com/wenbin-wb/ypbin-iot-starter/actions/workflows/ci.yml/badge.svg)](https://github.com/wenbin-wb/ypbin-iot-starter/actions/workflows/ci.yml)
[![CodeQL](https://github.com/wenbin-wb/ypbin-iot-starter/actions/workflows/codeql.yml/badge.svg)](https://github.com/wenbin-wb/ypbin-iot-starter/actions/workflows/codeql.yml)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](./LICENSE)

> 多协议物联网接入框架 Spring Boot Starter —— **JDK 21 · Spring Boot 4.1 · Netty 4.1**
> 目标：单机 10 万连接，集群千万级。
---

> ## ⚠️ 开发阶段说明（请先读这一段）
>
> 本项目的状态是 **早期开发（alpha）**，请按「可以读、可以试、但**不要直接上生产**」来看待它。
>
> | 维度 | 现状 |
> |---|---|
> | 版本 | **未发布任何正式版**（开发版 `0.1.0-SNAPSHOT`），Maven Central 上无此坐标 |
> | API 稳定性 | **不保证**。`1.0.0` 之前的公开接口可能随时变更，见 [`CONTRACT.md`](./CONTRACT.md) |
> | 真实环境验证 | **尚未在真实项目中接入验证过** —— 现有证据全部来自本仓自带的模拟器/broker/服务端 |
> | 协议覆盖 | TCP 透传 / Modbus(TCP+RTU) / MQTT 3.1.1 / OPC UA 四个；RTU 无真实硬件验证 |
> | 工程质量 | CI 的门禁是**真的在跑**（构建 342+ 用例、架构约束、NullAway、覆盖率、依赖收敛、元数据漂移），详见下方「工程治理与质量门禁」 |
>
> **这意味着什么**：如果你要拿它做真实项目，请把它当作一个**需要自己补齐验证**的起点 ——
> 先用 `probe()` 打通目标设备、按 `CONTRACT.md` 核对行为承诺、并在自己的环境里补一轮端到端测试。
> 欢迎反馈问题，尤其是「现场设备连不上 / 行为与文档不符」这类真实性缺口。
>
> **已知限制**（详见 [`ROADMAP.md`](./ROADMAP.md) 与 [`docs/PROTOCOLS.md`](./docs/PROTOCOLS.md)）：
> 指标桥需要宿主提供 `MeterRegistry`；OPC UA 信任目录放「CA 签发的叶子证书」不可用；
> `HOSTNAME`/`APPLICATION_URI` 校验对主动 MITM 无防护；`-Pit` 集成测试目前是空转。



> **当前状态：M1 主体已完成（2026-09-14）**。**尚未发布任何版本**（`0.1.0-SNAPSHOT`）——
> 尚未在真实项目中验证过，因此刻意不发布。
> 12 个模块 `mvn clean test` 全绿，**341 个测试用例**（0 失败、7 跳过）。
> 指令覆盖率：core 82.9% / runtime 82.9% / transport 81.2% / starter 88.7% /
> protocol-tcp 87.7% / protocol-modbus 82.6% / protocol-mqtt 88.4% / protocol-opcua 83.3%。
>
> **已实现**：`iot-core`（契约层，零 Spring/零 Netty）· `iot-runtime`（单飞建链连接复用、微批出口、
> 有界回调投递、双执行器调度）· `iot-transport`（Netty 底座）· `iot-spring-boot-starter`
> （条件装配 + 生命周期编排 + **断线指数退避重连**）· `iot-protocol-tcp` ·
> `iot-protocol-modbus`（TCP/RTU）· `iot-protocol-mqtt`（3.1.1）·
> `iot-protocol-opcua`（含**安全策略/证书认证/用户名密码**，加密会话端到端可用）·
> `iot-test`（TCK 一致性测试套件）· `iot-architecture-tests`（ArchUnit 约束，**含规则有效性自检**）。
>
> **下一步**：见 [ROADMAP.md](./ROADMAP.md)（协议扩容、指标桥、发布前置）。

---


## 工程治理与质量门禁

| 门禁 | 内容 | 本地命令 |
|---|---|---|
| 架构约束（ArchUnit） | 分层依赖、编码铁律、规则有效性自检 | `mvn -pl ypbin-iot-architecture-tests -am test` |
| 源码规范 | 禁内联 FQCN、`@Bean` 覆盖语义、autoconfig 注册、禁 `ordinal()` | 同上（`SourceConventionTest`） |
| 模块发布边界 | 非发布模块必须由 `dev-only` profile 承载 | 同上（`ModulePublishingTest`） |
| 配置元数据 | 每个带 `@ConfigurationProperties` 的模块都必须产出元数据；前缀齐全；协议模块必须在 arch-tests classpath 上 | 同上（`ConfigMetadataTest` CFGMETA-01~05） |
| 配置元数据漂移 | 提交的聚合元数据必须与构建产物一致 | `node tools/export-config-metadata.mjs --check` |
| 覆盖率 | 指令 ≥ 0.80、**分支 ≥ 0.64**（防倒退下限，非目标） | `mvn -pl <模块> -am test` |
| 空值语义（NullAway） | 8 个模块参与；**含「门禁是否真的执行过」自检**，并打印实际生效的分析器版本（父 pom 是 SNAPSHOT，旧快照会跑出假绿）| `tools/check-nullaway.sh` |
| 依赖版本收敛 | enforcer `dependencyConvergence` | `mvn -Pdep-convergence validate` |
| 供应链 | CycloneDX SBOM | `mvn -Psbom verify -DskipTests` |
| 代码风格 | spotless（Apache 头、导入顺序、去尾空格） | `mvn spotless:apply` |
| 发布前总检 | 按正确顺序把上述门禁各跑一遍（`-P` 会关掉架构门禁，不能合并） | `tools/preflight.sh` |

> 门禁一律经过**反向验证**（注入违规必须红、撤掉必须绿）。本仓已因此发现并修掉两类问题：
> 一个放在 `pluginManagement` 里**永不生效**的覆盖率门禁，以及一套**被上游短路成死代码**的安全实现。

**未接入（见 `ROADMAP.md`）**：发布 secrets（Central 凭据 + GPG 私钥）与首个正式版本号。
发布插件本身已配置在根 pom 的 `release` profile，且 `-Prelease` 反应堆已实测不含非发布模块。

## 文档

- `docs/DESIGN.md` 总体设计 · `docs/SPI.md` 契约 · `docs/RUNTIME.md` 运行时 · `docs/PROTOCOLS.md` 协议与审查记录
- `CONTRACT.md` 兼容性承诺 · `CHANGELOG.md` 更新日志 · `ROADMAP.md` 路线图
- `CONTRIBUTING.md` 贡献指南 · `RELEASING.md` 发布指南

## 这是什么

一套**只做协议对接**的物联网接入框架：把 OPC UA、Modbus、S7、BACnet、MQTT、GB28181 等
17+ 种工业/楼宇/消防/视频协议，收敛成**一个 `ProtocolAdapter` 接口**。

```java
// 新增一种协议的全部成本：实现一个接口 + 加一个 Maven 模块
public final class MyProtocolAdapter implements ProtocolAdapter {

    @Override
    public ProtocolDescriptor descriptor() {
        return ProtocolDescriptor.builder()
                .code(ProtocolCode.of("my-protocol"))
                .name("我的协议")
                .capabilities(ProtocolCapability.READ, ProtocolCapability.WRITE)
                .build();
    }

    @Override
    public CompletionStage<ProtocolConnection> open(ConnectionSpec spec, AdapterContext context) {
        // 建链
    }
}
```

宿主引入模块即自动装配，把数据经 `DataSink` 接走：

```xml
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-modbus</artifactId>
</dependency>
```

```yaml
ypbin:
  iot:
    egress:
      batch-size: 1000          # 微批聚合：1000 点或 200ms 触发一次
      queue-capacity: 100000
      overflow-policy: DROP_OLDEST
    protocol:
      modbus:
        enabled: true
```

---

## 职责边界

| 本仓负责 | 本仓**不**负责 |
|---|---|
| 协议建链、会话生命周期、重连退避 | 设备台账、点位模板、产品物模型 |
| 点位读取、写入、订阅（原生/轮询/流式） | 规则引擎、告警、联动 |
| 连接复用、采集调度、背压、微批出口 | 数据落库、时序库写入 |
| 协议能力声明、诊断、指标 | 前端可视化、组态 |
| 提供 `DataSink` / `DeviceRegistry` SPI 供宿主接入 | 设备 OTA、远程配置下发 |

---

## 模块总览

### 契约与底座

| 模块 | 职责 | Spring | Netty |
|---|---|---|---|
| `ypbin-iot-core` | SPI 契约、值对象、异常体系 | ❌ 零依赖 | ❌ 零依赖 |
| `ypbin-iot-transport` | Netty 传输底座：TCP/UDP/串口/WebSocket 连接工厂与编解码基座 | ❌ | ✅ |
| `ypbin-iot-runtime` | 适配器注册、连接复用、会话管理、时间轮调度、微批出口 | ❌ | ❌ |
| `ypbin-iot-spring-boot-starter` | 条件装配、`ypbin.iot.*` 配置、Actuator、**Micrometer 指标桥**（宿主有 `MeterRegistry` 时自动生效，否则回退无操作并打 INFO）| ✅ | 传递引入 |

### 协议模块（按需引入）

> **范围已按 D5 收敛**：核心档 5 个（M1）+ 扩展档 5 个（M2/M3）+ 按需档 3 个。
> 下表是**能力规划清单**（保留蓝图），不代表都会实现——判据是「能否在本机验证」，见[无硬件验证矩阵](./docs/PROTOCOLS.md#75-无硬件验证矩阵个人项目的关键一节)。

| 分类 | 模块 |
|---|---|
| **工业** | `-modbus` · `-opcua` · `-opcda` · `-s7` · `-ethernetip` · `-profinet` · `-can` · `-hart` |
| **楼宇** | `-bacnet` · `-knx` · `-lonworks` |
| **消防** | `-gbt26875` |
| **监控** | `-gb28181` · `-onvif` · `-snmp` |
| **通用** | `-mqtt` · `-coap` · `-tcp` · `-udp` · `-http` · `-websocket` |

> 协议模块之间**零依赖**：引入 Modbus 不会把 OPC UA 的依赖树拖进来。
> 每个模块自带 `AutoConfiguration.imports`，引入即生效。
>
> **`-modbus` 的 ASCII 是自研帧层，不引第二套协议栈**（决策 D4）：
> M1 只做 TCP/RTU（`digitalpetri modbus`，实测其源码**零 ASCII 支持**），
> ASCII 延到 M3，复用其 PDU 模型与 `ModbusClient` 基类、只自研 ASCII 帧与串口通道（约 500 行）。
> 详见[选型文档 §2.6](./docs/PROTOCOLS.md#26-modbus)。
>
> **长尾协议（EtherCAT / IO-Link / DALI / M-Bus / JT/T 1078 / Zigbee / Z-Wave / gRPC / AMQP）默认不建模块**：
> 前七者无成熟 Java 库，推荐路径是**硬件网关转换**后再接入本仓；
> 后二者库成熟但与「设备接入」的语义边界需先定义。评估口径见[选型文档 §2.11](./docs/PROTOCOLS.md)。

### 治理与测试（不发布）

| 模块 | 职责 |
|---|---|
| `ypbin-iot-dependencies` | IoT 三方库版本集中管理 + 全模块 parent |
| `ypbin-iot-bom` | 对外统一 BOM |
| `ypbin-iot-architecture-tests` | ArchUnit 约束：契约层零 Spring、协议互不依赖、装配规则 |
| `ypbin-iot-test` | 协议一致性测试套件（TCK）+ 协议模拟器 |

---

## 设计文档

| 文档 | 内容 |
|---|---|
| [`AGENTS.md`](./AGENTS.md) | 本仓开发规范：分层、编码铁律、AI 审查清单、常见陷阱 |
| [总体设计](./docs/DESIGN.md) | 模块划分与依赖图、条件装配策略、千万级并发关键技术、技术风险与应对、ADR、**与母仓规范的显式对齐** |
| [SPI 契约](./docs/SPI.md) | `ProtocolAdapter`、`AdapterContext` 及全部配套类型的接口签名与 Javadoc、**代码级约定 C1~C10** |
| [协议选型与路线图](./docs/PROTOCOLS.md) | 各协议库选型（坐标/版本/许可证/坑）、许可证三档门禁、pinning 风险分级、MVP 路线图 |
| [运行时与运维设计](./docs/RUNTIME.md) | 设备影子、时钟同步、配置热更新、i18n、版本兼容矩阵、限流熔断、协议模拟器、优雅停机、测试策略 |
| [调研存档](./docs/research/) | 选型版本号的一手来源依据（可追溯） |

---

## 十二条核心设计决策

1. **契约层零依赖**：`iot-core` 只依赖 JDK 21 + SLF4J + JSpecify，可脱离 Spring 与 Netty 复用。
2. **协议即模块**：一种协议一个 Maven 模块，模块间零依赖；引入即装配，零侵入宿主。
3. **异步为纲，同步为目**：对外契约 `CompletionStage`（EventLoop 不可阻塞），阻塞式协议库用 `BlockingProtocolAdapter` 桥接。
4. **连接 / 设备两级模型**：一条链路承载 N 个逻辑设备（Modbus 网关多从站、S7-1500 并发上限 8~16），
   连接复用是框架能力而非适配器自觉。
5. **不做统一地址抽象**：工业地址语义无法无损归一，core 只透传 `raw`，解析权归协议模块。
6. **数据出口单一**：所有数据经 `DataEgress` 微批出站（10 万设备 × 100 点位 ÷ 5s = **200 万点/秒**，逐点回调必然压垮框架）。
7. **接入路径零 DB 访问**：从根本上规避虚拟线程 × 连接池的雪崩冲突，而不是靠调参掩盖。
8. **时间类型分界**：协议时序用 `Instant`（UTC 绝对时刻，跨时区无损），业务实体与 API 用 `LocalDateTime`（母仓规范）。
9. **native 走平台线程**：JEP 444 明确 native 调用的 pinning **不可移除**（JDK 24 也只修 `synchronized`），
   串口 / CAN / 媒体类适配器强制平台线程池。
10. **时间轮调度**：10 万周期任务用分层时间轮，不用 `ScheduledThreadPoolExecutor`。
11. **会话状态本地化**：跨节点同步 10 万会话状态是灾难；全局视图由宿主落库。
12. **失败要可见**：不支持的能力抛异常、协议 code 冲突终止启动、数据丢弃必须计数——禁静默降级。

---

## 三项已拍板结论

| # | 决策 | 落地口径 |
|---|---|---|
| **D1** | **许可证：记录而非阻断** | 扫描生成 `LICENSE-RISK.md`，**不因许可证失败构建**；BACnet4J（GPL-3.0）**解禁可用**（仅登记）；`io.github.lunasaw:sip-*` **恢复使用**（POM=Apache-2.0 / README=MIT 都是宽松许可）；Calimero 正常使用 |
| **D2** | **OPC DA / PROFINET / LonWorks 不规划** | 三者都需专用硬件或 Windows 桥接环境，**个人项目无法验证**；移出路线图，方案留档（§2.4/§2.8/§3.4） |
| **D3** | **GB/T 26875 换版双轨** | 编解码与业务解耦 + 协议版本可插拔；GB 26875.9-2026 为强制性标准，**2027-03-01 实施并全部代替 2011 版第 1 部分**；禁止把 2011 版字段写死进实体与数据库 |
| **D4** | **Modbus ASCII 用 j2mod，不投入自研** | ASCII 不进 M1；一旦要做直接引 `com.ghgande:j2mod:3.4.0`（Apache-2.0，ASCII 开箱即用）；自研方案（约 700 行）降为备选 |
| **D5** | **范围收敛到 5 + 5 + 3** | 从 21 个模块收敛为：核心档 5 个（M1）+ 扩展档 5 个（M2/M3）+ 按需档 3 个；判据是「能否在本机验证」，见 §7.5 无硬件验证矩阵 |
| **D6** | **规模目标收敛验收门槛** | 架构仍按 10 万连接设计；**日常门禁降为 1 万连接 / 20 万点每秒 / 8 小时长稳**，10 万连接作为云服务器上的一次性验证 |

---

## 构建

父 pom 钉的是母仓的**已发布正式版**（`cn.ypbin:ypbin-starter-dependencies:3.1.0`，
Central 可解析），因此**干净环境可直接构建**，无需先装母仓。

```bash
mvn -pl <模块> -am test        # 单模块测试（**必须带 -am**：不带会用 ~/.m2 里的旧制品，产出假失败）
mvn clean test                 # 全量单元测试 + 覆盖率门禁（不含任何 -P：这是唯一会跑架构门禁的方式）
tools/check-nullaway.sh        # 空值语义门禁（会打印实际生效的分析器版本）
mvn -Pdep-convergence validate # 依赖版本收敛
mvn -Psbom verify              # 生成 SBOM
tools/preflight.sh             # 发布前总检（按正确顺序把上述门禁各跑一遍）
```

> **CI 已实测通过**（首次运行）：8 个步骤全部真实执行 —— 全量构建与单测 124s、
> NullAway 26s、依赖收敛 4s、元数据漂移校验、SBOM 生成 26s（耗时即「确实跑了」的证据）。
>
> **`-Pit` 目前是空转**：本仓还没有任何 `*IT.java`，`mvn -Pit verify` 不会执行任何集成测试。
> 协议侧的端到端验证目前都在各协议模块的单测里（自带本地模拟器/broker/服务端）。
>
> **子模块测试 vs 全量**：`-pl X test`（不带 `-am`）会用 `~/.m2` 里的**旧制品**跑测试，
> 可能产出与全量构建不同的失败 —— 判定「是否回归」一律以 `mvn clean test` 或带 `-am` 的命令为准。

## 与 `ypbin-starter` 的关系

本仓是**独立仓库**，但复用母仓 `ypbin-starter` 的 parent 与构建门禁：

```xml
<parent>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-starter-dependencies</artifactId>
    <version>3.1.0</version>
</parent>
```

| 关注点 | 处理 |
|---|---|
| 版本治理 | 继承母仓 Boot 4.1 BOM 与插件配置，母仓升级只需改 parent 版本号 |
| 代码风格 | 复用母仓 spotless（license header 覆盖为 iot 署名） |
| 依赖复用 | `-spring-boot-starter` 模块可复用 `SpringUtils` / `R` / `BusinessException`；core/runtime 不复用 |
| 功能重叠 | 母仓 `messaging` 的 MQTT 面向**应用消息推送**，本仓 `protocol-mqtt` 面向**设备接入**，语义与生命周期不同，两者并存 |
| 集成 admin | admin 引入 BOM + 所需协议模块，实现 `DeviceRegistry` 与 `DataSink` 即可，无需改框架代码 |

---

## 许可证

Apache License 2.0
