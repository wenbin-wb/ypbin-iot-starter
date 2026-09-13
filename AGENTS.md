# ypbin-iot-starter · 代理记忆与开发规范（AGENTS）

> 本文件是本仓的自动化记忆入口（AGENTS.md / CLAUDE.md 双读），**任何在此仓库的工作会话开工前必须读取**。
>
> **本仓是独立仓库，但不是独立规范体系**：母仓 `ypbin-starter` 的 `AGENTS.md` 与本文件冲突时，
> 以本文件为准（本仓更具体）；本文件未覆盖的部分，**一律按母仓规范执行**。

---

## 0. 全局偏好（务必遵守）

1. **所有回答一律使用中文**（代码、日志、命令行原文除外）。
2. git commit **绝不**添加 `Co-Authored-By` 尾注。
3. 复杂任务**连续推进、全部完成后再统一汇报**，不逐步停下等待催促；仅在高危动作、需求决策或不可绕过的阻塞时暂停询问。

---

## 1. 仓库定位

`ypbin-iot-starter` —— **多协议物联网接入框架 Spring Boot Starter**，**只做协议对接**。

> **定位：个人自研项目**，用于以后做物联网项目时直接上手，**无商业交付约束**。
> 由此推导的三条口径：① 许可证**记录而非阻断**（D1）；② 只做**能在本机验证**的协议（D5/D2）；
> ③ 规模目标保留 10 万连接架构，但**验收门槛收敛到 1 万连接**（D6）。详见 `docs/PROTOCOLS.md` §1.3。

- 技术基线：**JDK 21 · Spring Boot 4.1 · Netty 4.1 · Maven**
- 规模目标：单机 10 万连接，集群千万级
- 坐标：`cn.ypbin:ypbin-iot-*`，parent = `cn.ypbin:ypbin-starter-dependencies`（复用母仓 BOM 与构建门禁）
- 上游依赖方：`ypbin-admin`（通过 `ypbin-iot-bom` 引入所需协议模块）

### 1.1 职责边界（越界即不合格）

| 本仓负责 | 本仓**不**负责 |
|---|---|
| 协议建链、会话生命周期、重连退避 | 设备台账、点位模板、产品物模型 |
| 点位读写、订阅（原生/轮询/流式） | 规则引擎、告警、联动 |
| 连接复用、采集调度、背压、微批出口 | 数据落库、时序库写入 |
| 协议能力声明、诊断、指标 | 前端可视化、组态、OTA |

**判定标准**：如果一件事需要知道「业务语义」（这个点位代表什么、这个值正常吗），它就不属于本仓。

### 1.2 设计文档索引（改动前先读）

| 文档 | 内容 |
|---|---|
| [`README.md`](./README.md) | 定位、模块总览、核心设计决策 |
| [`docs/DESIGN.md`](./docs/DESIGN.md) | 模块划分与依赖图、条件装配策略、并发关键技术、风险与 ADR、**§1.4 与母仓规范的显式对齐** |
| [`docs/SPI.md`](./docs/SPI.md) | `ProtocolAdapter` / `AdapterContext` 及全部配套类型；**§0.1 代码级约定 C1~C10** |
| [`docs/PROTOCOLS.md`](./docs/PROTOCOLS.md) | 各协议选型（坐标/版本/许可证/坑）、许可证门禁、pinning 分级、MVP 路线图 |
| [`docs/RUNTIME.md`](./docs/RUNTIME.md) | 设备影子、时钟同步、配置热更新、i18n、版本兼容、限流熔断、模拟器、优雅停机、测试策略 |

---

## 2. 模块分层（依赖方向不可逆）

```
iot-core                 零 Spring · 零 Netty（仅 JDK 21 + slf4j-api + jspecify）
  ├── iot-runtime        零 Spring · 零 Netty
  └── iot-transport      Netty 4.1 底座
        └── iot-spring-boot-starter     唯一的 Spring 装配层
              └── iot-protocol-*        协议模块，互相之间零依赖（按需引入，范围见 D5）
```

| 规则 | 说明 |
|---|---|
| `iot-core` 不得依赖 Spring / Netty / runtime / transport / 任何协议模块 | 由 ArchUnit 强制 |
| `iot-runtime` / `iot-transport` 不得依赖 Spring | 同上 |
| 协议模块**之间零依赖** | 保证「引入一个协议不拖进另一个」 |
| 协议模块的 **`protocol/` 包零 Spring 注解** | 只有 `autoconfigure/` 子包可碰 Spring |
| `iot-core` / `iot-runtime` / `iot-transport` **不提供静态门面** | 静态门面只在 `-spring-boot-starter` |

---

## 3. 编码铁律（RED — 违反即不合格）

### 3.1 框架库装配约定（继承母仓，不可豁免）

| # | 规则 |
|---|---|
| **R1** | 每个能力模块必须有 `autoconfigure/XxxAutoConfiguration.java`（`@AutoConfiguration`）+ `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 登记。**禁止 `spring.factories`** |
| **R2** | 每个 `@Bean` 必须带 `@ConditionalOnMissingBean`（宿主可覆盖） |
| **R3** | `@ConditionalOnClass` **必须指向协议库的类**，不得指向本模块的类（后者恒为真，条件形同虚设） |
| **R4** | 协议 `@AutoConfiguration` 必须 `@AutoConfiguration(after = IotAutoConfiguration.class)` |
| **R5** | 配置项统一 `ypbin.iot.*` 前缀 + `@ConfigurationProperties(PREFIX)` + `PREFIX` 常量；**禁止**散落 `@Value` |
| **R6** | 协议 code 全局唯一；`descriptor()` 非空；`capabilities` 与实现一致——**注册时 fail-fast** |
| **R7** | 装配日志统一 `log.debug("[ypbin-iot] xxx configured.")` 前缀 |
| **R8** | 协议模块的协议库依赖必须 `compile` 传递（不得 `optional`），Spring 依赖用 `optional` |

### 3.2 代码规范（继承母仓，不可豁免）

| # | 规则 |
|---|---|
| **R9** | 类级 Javadoc 末尾必带 `@author wenbin` + `@since <日期>`；**禁 `@date`、禁版本号**；顶部 Apache-2.0 license 头 |
| **R10** | **禁内联全限定类名**，一律顶部 `import` |
| **R11** | **禁静默吞异常/静默降级**；`log.error` 必须传完整堆栈；禁 `printStackTrace()` |
| **R12** | 集合返回**禁 `null`**，无数据返回空集合；字面量统一 `List.of()`/`Map.of()`/`Set.of()`，**禁用** `Collections.emptyXxx()`/`singletonXxx()` |
| **R13** | 枚举必须显式 `code` + `desc`；**禁 `ordinal()`** 存库或传参 |
| **R14** | **杜绝魔法值**：业务判断与状态必须抽为 `public static final` 常量或枚举 |
| **R15** | 所有远程调用**必须显式配置** `connectTimeout` 与 `readTimeout`，并明确重试与降级策略 |
| **R16** | 事务（若涉及）必须 `@Transactional(rollbackFor = Exception.class)` |
| **R17** | 禁止在循环内做 DB 查询或 RPC；批量 `IN` 查询前必须判空短路 |
| **R18** | **禁参考项目品牌词**（blade/continew 等）出现在类名、包名、注释、文档、commit message |
| **R19** | 提交前必须过 `spotless:apply` 再 `test`/`compile`；**验证后才宣称完成** |

### 3.3 本仓特有约束（IoT 专属）

| # | 规则 | 依据 |
|---|---|---|
| **I1** | **时间类型分界**：`iot-core` 的协议时序字段用 `Instant`；宿主的实体/API 用 `LocalDateTime`（GMT+8）。不得混用 | DESIGN §1.4.2 A1 |
| **I2** | **消息用消息键**：异常与 `AdapterContext.log` 传 `iot.<protocol>.<category>.<detail>` 键；**禁止**中英文字面量 | SPI §0.1 C6 |
| **I3** | **native 调用禁止上虚拟线程**：串口（jSerialComm）、CAN（JavaCAN）、媒体（JavaCV）类适配器必须覆写 `requiresPlatformThread()` 返回 `true` | PROTOCOLS §5.5 |
| **I4** | **EventLoop 上禁止任何阻塞**：编解码以外的操作一律异步化 | DESIGN §5.3 V2 |
| **I5** | **接入路径禁止 DB/RPC**：`AdapterContext` 不暴露任何数据访问能力；`DataSink.write` 只允许入队 | DESIGN §5.4 |
| **I6** | **能力不支持必须抛 `UnsupportedCapabilityException`**，不得返回空结果或伪造成功 | SPI P3 |
| **I7** | **部分失败逐项标记**：批量读写中个别点位失败不得让整个 Stage 异常完成 | SPI §4.2 |
| **I8** | **`ProtocolAdapter` 必须无状态单例**：所有链路/设备状态放在 `ProtocolConnection` / `DeviceSession` | SPI §2 |
| **I9** | **JSON 只能用 Jackson 3**（`tools.jackson`），禁 Jackson 2 | 母仓已全面切换 |
| **I10** | **协议实现类零 Spring 注解**，只有 `autoconfigure/` 子包可碰 Spring | DESIGN §2.5 |
| **I11** | **协议 code ↔ 包名 ↔ 配置键 ↔ 模块名一一对应**（`cn.ypbin.iot.protocol.<code>`） | DESIGN §3.4 A17 |
| **I12** | **引入 GPL/LGPL/MPL 类库的模块，README 必须含许可边界声明** | PROTOCOLS §3.3 |
| **I13** | **Modbus ASCII 用 j2mod**（`com.ghgande:j2mod:3.4.0`，Apache-2.0），**不投入自研**；引入时必须排除其 `log4j-core` + `slf4j-reload4j` 传递依赖。ASCII 不进 M1 | PROTOCOLS §2.6 D4 |

---

## 4. AI 代码审查清单（每次提交/评审必须逐条核对）

> 本清单用于**自动或人工审查**。凡勾选为「否」的项，**必须整改后才能合入**。

### 4.1 装配类（`*AutoConfiguration.java`）

- [ ] 类上有 `@AutoConfiguration`，且**已在 `AutoConfiguration.imports` 中登记**（两者缺一即失效）
- [ ] `@AutoConfiguration` 有正确的 `after`（协议模块必须 `after = IotAutoConfiguration.class`）
- [ ] 每个 `@Bean` 方法都带 `@ConditionalOnMissingBean`
- [ ] `@ConditionalOnClass` 指向**协议库**的类（不是本模块的类）
- [ ] `@ConditionalOnProperty` 使用 `ypbin.iot.*` 前缀 + `matchIfMissing = true`
- [ ] 装配日志用 `[ypbin-iot]` 前缀且为 `debug` 级别
- [ ] 若 `@AutoConfiguration` 类被重命名/换包，`AutoConfiguration.replacements` 已同步维护

### 4.2 包边界

- [ ] `iot-core` 中**不存在** `org.springframework.*` / `io.netty.*` 的 import（含 `@link` 之外的代码引用）
- [ ] `iot-runtime` / `iot-transport` 中**不存在** `org.springframework.*` 的 import
- [ ] 协议模块的 `protocol/` 包中**不存在**任何 Spring 类型
- [ ] 协议模块之间**没有**互相 import
- [ ] 包名符合 `cn.ypbin.iot.protocol.<code>` 且 `<code>` == `descriptor().code()`

### 4.3 配置

- [ ] 所有配置项落在 `ypbin.iot.*` 命名空间
- [ ] 使用了 `@ConfigurationProperties` + `PREFIX` 常量，**没有**散落的 `@Value`
- [ ] 字段有**开箱即用的默认值** + 中文注释
- [ ] 协议扩展参数对**未知 key 显式 warn**（不得静默忽略拼错的配置项）

### 4.4 异常与降级

- [ ] 没有空 `catch {}`、没有失败静默 `return`、没有"失败返回默认值假装正常"
- [ ] `log.error` 传了完整堆栈（`log.error("...", ex)`），无 `printStackTrace()`
- [ ] 能力不支持抛出 `UnsupportedCapabilityException`（而不是返回空集合）
- [ ] 批量操作的**部分失败**用逐项状态表达，未让整个 Stage 异常完成
- [ ] 异常消息使用**消息键**（`iot.*`），无中英文字面量

### 4.5 并发与线程

- [ ] 没有在 EventLoop 上做阻塞调用
- [ ] native 调用（JNI）**没有**跑在虚拟线程上（`requiresPlatformThread()` 正确覆写）
- [ ] 自研代码**没有** `synchronized` 方法/块（用 `ReentrantLock`）
- [ ] `ProtocolAdapter` 实现类是**无状态单例**（无可变实例字段）
- [ ] 所有远程调用有显式超时

### 4.6 集合 / 枚举 / 时间 / 序列化

- [ ] 返回集合的方法**不返回 `null`**；使用 `List.of()`/`Map.of()`/`Set.of()`
- [ ] 枚举有 `code` + `desc`，**无 `ordinal()`** 使用
- [ ] 协议时序字段用 `Instant`，业务字段用 `LocalDateTime`（未混用）
- [ ] 无 `@Data` 用于业务 DTO（仅 `@ConfigurationProperties` 允许）
- [ ] 未引入 Jackson 2

### 4.7 许可与依赖

- [ ] 新引入的依赖**已核对 POM 的 license 字段**（不采信 README/博客）
- [ ] 传递依赖已审查并按需 `<exclusions>` 裁剪（如 `lunasaw:sip-*` 的 Spring Cache / SkyWalking / Guava）
- [ ] 引入 GPL/LGPL/MPL 类库时，模块 README 已含许可边界声明，且 `LICENSE-RISK.md` 已自动更新（**不阻断构建**，但必须记录）
- [ ] 新增协议前确认它**能免硬件验证**（`PROTOCOLS.md` §7.5 矩阵）——表里没有验证手段就不开始写

### 4.8 测试

- [ ] 适配器通过了 TCK 一致性测试（继承基类即自动跑）
- [ ] 有 `ApplicationContextRunner` 装配测试（4 场景：默认/关闭/覆盖/库缺失）
- [ ] 新增的 ArchUnit 规则有**有效性自检**（合成违规样本能触发报错）
- [ ] 涉及并发的改动补了并发测试（尤其 `ConnectionRegistry`）
- [ ] `spotless:apply` 已执行，`mvn test` 通过

---

## 5. 开发工作流

### 5.1 构建与验证

```bash
# 全量构建（不跑测试）
mvn -DskipTests install

# 单模块测试（含 spotless 校验，绑定在 process-test-classes）
mvn -pl ypbin-iot-core test

# 集成测试（协议模拟器，启动较慢）
mvn -Pit verify

# 生成 SBOM（供应链合规）
mvn -Psbom verify

# 格式化（提交前必须执行）
mvn spotless:apply
```

> **命令不绑定本机路径**：使用 `mvn` 而非绝对路径；不同环境（Windows/Linux/CI）都适用。

### 5.2 新增一个协议模块的标准步骤

1. 复制 `ypbin-iot-protocol-tcp` 作为模板（或跑 `tools/ypbin-iot-init.mjs`）；
2. 改 `artifactId` / 包名 / 协议 code（**三者必须对应**，见 I11）；
3. 实现 `ProtocolAdapter`（一设备一链路用 `BlockingProtocolAdapter`；JNI 的覆写 `requiresPlatformThread()`）；
4. 实现地址解析 + 协议扩展接口（若需要）；
5. 写 `autoconfigure/XxxAutoConfiguration` + `XxxProperties`，并在 `AutoConfiguration.imports` 登记；
6. 继承 TCK 抽象基类跑一致性测试；
7. 补 `ApplicationContextRunner` 装配测试；
8. 写模块 `README.md`（含**协议特有坑**；引入 GPL/LGPL 库时含**许可边界声明**）；
9. 在 `ypbin-iot-bom` 中登记坐标；
10. 在 `PROTOCOLS.md` 更新选型表与兼容矩阵。

### 5.3 与母仓的协作

| 场景 | 做法 |
|---|---|
| 需要母仓的新能力 | **先反馈**，由母仓补；**不要**在本仓自造 workaround 掩盖缺口 |
| 母仓发新版 | 改本仓 parent 版本号**一处**；跑全量回归；parent 版本**显式锁定，不自动跟随** |
| 需要复用母仓能力 | 仅 `-spring-boot-starter` 模块可复用（`SpringUtils` / `R` / `I18nUtil` / `EntityStatus`）；`core`/`runtime`/`transport` **不得**复用 |
| 发现文档与实际不符 | **以代码与实际制品为准**，同时更新文档（本仓已实证推翻两条二手说法，见 `PROTOCOLS.md` §5.4） |

---

## 6. 常见陷阱速查（本仓已踩过或已预判）

| 陷阱 | 现象 | 正确做法 |
|---|---|---|
| `AutoConfiguration.imports` 未登记 | 模块引入后**静默不装配**，无任何报错 | 新增装配类必须同步登记；`RegistrationDiscoveryTest` 做运行时可见性断言 |
| `EnvironmentPostProcessor` 注册键未随接口改包 | Boot 4.1 仍兼容旧键，**默认值静默失效** | 接口与 `spring.factories` 键**同步**改为 `org.springframework.boot.EnvironmentPostProcessor` |
| Milo 用旧坐标 `sdk-client` | 拿到冻结在 `0.6.16` 的版本 | 用 `milo-sdk-client:1.1.7` 等新坐标 |
| PLC4X 用 `plc4j-driver-ethernet-ip` | 停在 `0.6.0`（2020） | 用 `plc4j-driver-eip:1.0.0` |
| 以为 `digitalpetri modbus` 支持 ASCII | 实测零支持（89 个源文件零命中） | 按 **D4** 用 `j2mod:3.4.0`（记得排除 `log4j-core`）；ASCII 放到最后按需做 |
| 以为升级 JDK 24 就能解决 pinning | 只解决 `synchronized`，native 那条**永不可解** | native 路径一律平台线程池 |
| 把 `maxPoolSize` 当"载体线程数" | 参数调错方向（它补偿的是普通阻塞，不是 pinning） | 调度线程数用 `jdk.virtualThreadScheduler.parallelism` |
| `DataSink.write` 里写 `mapper.insert(...)` | 10 万连接下连接池被压垮，现象是"IoT 框架卡死" | 有界队列 + 固定平台线程消费者 + 批量落库 |
| 配置项拼错被静默忽略 | 现场"配置了但不生效" | 未知 key 必须 warn 并列出可用 key |
| ArchUnit 规则本身写错 | 规则恒为真，**永远不报错** | 每条规则配合成违规样本自检（SPECIAL：`callMethod` owner、`switch(enum)`→`ordinal`、Lombok `@Data` 字节码不可见） |

---

## 7. 常用参考

| 主题 | 位置 |
|---|---|
| 条件装配策略与铁律 A1~A18 | `docs/DESIGN.md` §3.3 / §3.4 |
| SPI 代码级约定 C1~C10 | `docs/SPI.md` §0.1 |
| 许可证管理（记录而非阻断） | `docs/PROTOCOLS.md` §5.2 |
| pinning 风险分级与落地规则 | `docs/PROTOCOLS.md` §5.5 |
| 内核与 JVM 参数基线 | `docs/DESIGN.md` §5.10 |
| 测试策略与 TCK 用例集 | `docs/RUNTIME.md` §9 |
| 优雅停机阶段与信号处理 | `docs/RUNTIME.md` §8 |
| 决策口径 D1~D6（个人项目） | `docs/PROTOCOLS.md` §1.3 |
| MVP 路线图与批次 | `docs/PROTOCOLS.md` §7.1 |
| **无硬件验证矩阵**（立项前置条件） | `docs/PROTOCOLS.md` §7.5 |
