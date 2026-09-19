# 路线图

> 状态标记：✅ 已完成并验证 / 🚧 进行中 / ⬜ 未开始。
> **只列有明确验收标准的事**；「已完成」一律有测试或构建证据。

## M0 · 骨架（✅）

- ✅ 模块划分与依赖图、`ProtocolAdapter` SPI 与 `AdapterContext`
- ✅ 条件装配策略、BOM 与依赖基线（JDK 21 + Spring Boot 4.1 + Netty 4.1）

## M1 · 内核与首批协议（✅ 主体完成）

- ✅ `ConnectionRegistry`（单飞 + 引用计数 + 空闲回收 + 建链限速）
- ✅ `PollingSubscriptionManager`、`BoundedDeliveryDispatcher`、`EgressRouter`
- ✅ Netty 传输底座与三种帧定界
- ✅ **连接意外断开后的指数退避重连与重绑定**（含「可重复生效」「显式解绑取消」）
- ✅ **宿主回调卸载出协议/IO 线程**（有界丢弃 + 计数）
- ✅ 协议：TCP 透传、Modbus（TCP/RTU）、MQTT 3.1.1、OPC UA（含安全策略与认证）
- ✅ OPC UA **签名加密会话端到端可用**（`Basic256Sha256` + `SignAndEncrypt`）

## M1.x · 工程化对齐（🚧）

- ✅ 非发布模块隔离（`dev-only` profile）+ `ModulePublishingTest` 门禁
- ✅ `LICENSE` / `CHANGELOG` / `CONTRACT` / `CONTRIBUTING` / `RELEASING` / `ROADMAP`
- ✅ CI（`ci.yml` / `codeql.yml` / `release.yml`，对齐母仓）+ 独立的**集成测试作业**（`-Pit`）
  - ✅ 覆盖率的**步骤顺序不变量**：会 `clean` 的步骤（NullAway 内部就是 `clean compile`）必须排在
    「产出覆盖率报告」的构建步骤之前，否则 `target/site/jacoco/` 会被删光，
    归档步骤会「success 但零产物」（2026-09-15 实测事故：日志出现
    `No files were found with the provided path: **/target/site/jacoco/`）。
    现在既有顺序保证，也有**归档前断言**（`jacoco.csv < 8` 即失败）。
  - ✅ `-Pit` = 只跑集成测试：`it` profile 里**显式跳过 surefire**。
    早期用的 `-Dsurefire.skip=true` 在本项目**不生效**（CI 日志里 11 个模块的 surefire 全跑），
    现已改为声明式配置，并由 CI 断言守着「surefire 不得出现用例记录」。
- ✅ NullAway（`-Pnullaway` + `@NullMarked`）覆盖全部 8 个模块（0 违规，含执行自检）
  - ✅ 父版本钉 **`3.1.0`（已发布正式版）**：拿到 `nullaway` / `dep-convergence` 两个 profile，
    同时干净环境可直接构建（2.2.3 没有这些 profile）
  - ✅ 全 8 模块完成（core 40 / starter 10 / runtime 8 / tcp 8 / opcua 8 / transport 4 /
    modbus 2 / mqtt 2 处，全部按语义修，无一处 `@SuppressWarnings` 压制）
  - ✅ 依赖版本收敛（`-Pdep-convergence`，随父版本一并获得）
- ✅ ArchUnit 补齐母仓有而本仓缺的门禁（本清单即当时的差距全集，4 项已全部闭环；
  母仓后续新增门禁时需重新评估）
  - ✅ 模块发布边界（`ModulePublishingTest`，已反向验证）
  - ✅ 配置元数据（`ConfigMetadataTest`，已反向验证）
  - ✅ 注册发现：**已有**（`IotAutoConfigurationTest.CFG-08` 已做 SpringFactoriesLoader 发现，不重复建设）
  - ✅ 编码规则（printStackTrace/System.out/字段注入/Collections）：**已有**（`ARCH-05`）
- ✅ `tools/`：`check-nullaway.sh`（含执行自检）、`export-config-metadata.mjs`（聚合 + 漂移门禁 +
  **模块集合不得静默缩小**）、`export-coverage.mjs`（覆盖率快照，由构建产物生成）、`preflight.sh`（发布前总检，8 步含集成测试）
- ✅ 发布前置：GPG + `central-publishing-maven-plugin`（在根 pom 的 release profile；`-Prelease` 反应堆已实测不含非发布模块）
- ⬜ 发布 secrets（Central 凭据 + GPG 私钥）与首个正式版本号
  - 📌 **发布时机（2026-09-19 决定）**：**先让 `ypbin-iot-cloud` 的 P4b「access 接协议栈」在本地
    用 SNAPSHOT 跑通，再发 0.1.0**。理由：access 是 iot-starter 的第一个真实宿主，
    首个正式版应当带着「有宿主真的在用它」这个事实发布；用 `mvn install` 出来的本地 SNAPSHOT
    足以支撑 P4b 的开发与验证（`ypbin-iot-cloud` 侧的 `ypbin-iot-bom` import 仍留占位）。
    若 P4b 发现契约缺口，改在发布前比改在发布后便宜得多。

## M2 · 协议扩容（⬜）

- ✅ OPC UA 用户名令牌端到端验证（`SEC-E2E-02` 通过令牌认证读到数据；`SEC-E2E-03` 证明错误口令被拒）
- ⬜ Modbus RTU 真实硬件验证
- ✅ Micrometer 指标桥（`IotMicrometerAutoConfiguration` + `MicrometerMetricsRecorder`）：
  有 `MeterRegistry` 时自动生效（7 个仪表：读写计数×2 维度、读写计时器、订阅点位数、错误数、gauge），
  无则回退无操作；实现严格遵守「非阻塞」契约（无 IO、无日志、无锁等待），
  且不擅自开启分位数/直方图
- ⬜ GB/T 26875（消防）双版本
- ⬜ 剩余候选协议（见 `docs/PROTOCOLS.md` 的选型清单）

## 审计发现并已修的两个门禁缺陷（2026-09-14）

1. **协议模块没有配置处理器**：4 个协议模块都用了 `@ConfigurationProperties` 但没有
   `spring-boot-configuration-processor`，宿主的 IDE 对 `ypbin.iot.protocol.*` 的所有配置项
   **没有任何补全**（不报错、不影响运行，只是静默失去提示）。元数据从 1 模块/19 项 → **5 模块/51 项**。
2. **ArchUnit 规则静默跳过 3 个协议模块**：arch-tests 只依赖 `protocol-tcp`，
   而规则用 `importPackages("cn.ypbin.iot")` —— modbus/mqtt/opcua 的字节码**不在 classpath 上**，
   于是「协议实现包不得使用 Spring 类型」这类规则**看起来通过、实则从未检查过它们**。
   已补齐依赖，并加 `CFGMETA-05` 自检把「规则覆盖了哪些模块」变成可断言的事实。

> 这两个都不是「代码写错」，而是**门禁覆盖范围与它声称的不一致** —— 同一族问题的第 N 次出现。

## 已知技术债（⬜）

- 🚧 **集成测试体系**：`ypbin-iot-integration-tests` 已落地（完整 Spring Boot 上下文 + 嵌入式 broker），
  走 `-Pit` + failsafe；已覆盖 MQTT 全链路与指标装配，待补多协议并存、重连后数据面恢复等场景
- ✅ **MQTT 二进制负载静默损坏已修**：新增 `payload-format: text|number|binary`；
  `text` 模式下非法 UTF-8 产出 BAD（不再静默替换成 U+FFFD），`binary` 原样交付 `byte[]`
- ✅ OPC UA 信任模型：机制已源码级确认并做成**装配期诊断**（`SEC-15`/`SEC-16` 反向验证）。
  三条部署语义已写进 `docs/PROTOCOLS.md`：自签证书=精确 pin、自签 CA=信任该 CA 的一切、
  CA 签发的叶子=不可用。**注意「CA + 指定叶子」并不能精确 pin**（反直觉但已实测）。
- ⬜ `HOSTNAME` 已做成可选开关、`APPLICATION_URI` 保留，但两者的比对值取自服务端自述，
  **对主动 MITM 无防护** —— 真正的门闩只有信任列表本身。
- 🚧 覆盖率并发噪声：**已实测量化并部分收敛**（4 轮全量：runtime 2.32pp、starter 0.36pp、
  其余 6 模块 0.00pp；`EGRESS-11` 把 EgressRouter 的 CAS 重试分支变成基本必然覆盖）。
  **未能完全收敛**：竞争态分支的命中取决于队列满/空状态，原理上无法用测试确定性钉死。
  门禁余量均大于波动，但 **runtime 的余量/波动比仅约 1.9 倍，是需盯着的一项**。
  > 补充（2026-09-15 独立复核）：同一提交上又观测到 `protocol-mqtt` 波动 0.63pp，
  > 因此「其余 6 模块 0.00pp」只是**当时那 4 轮**的结论，不能当作「这些模块不会波动」。
  > 结论：**覆盖率数值不设漂移门禁**（有波动必然假红），只有模块集合是确定性的、可门禁的
  > —— 见 `tools/export-coverage.mjs --check`。
- ⬜ 分支覆盖率余量偏薄：按最近一次实测（[`tools/generated/iot-coverage.json`](./tools/generated/iot-coverage.json)，
  该文件由构建产物生成，**不要手工抄数字**），余量最薄的是 **`core`（约 +3.0pp）与
  `spring-boot-starter`（约 +3.2~3.5pp）**，其后是 `modbus`；此前点名的 `transport` 其实偏厚。
  门槛是 ≥ 0.64（防倒退下限，非目标），这三者是最先会被波动顶到红线的地方。
