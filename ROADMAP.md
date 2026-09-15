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
- ✅ CI（`ci.yml` / `codeql.yml` / `release.yml`，对齐母仓）
- ✅ NullAway（`-Pnullaway` + `@NullMarked`）覆盖全部 8 个模块（0 违规，含执行自检）
  - ✅ 父版本升到 `3.1.0-SNAPSHOT`（`nullaway` / `dep-convergence` profile 定义在父 pom，
    2.2.3 没有它们；母仓发布 3.1.0 正式版后应改钉正式版）
  - ✅ 全 8 模块完成（core 40 / starter 10 / runtime 8 / tcp 8 / opcua 8 / transport 4 /
    modbus 2 / mqtt 2 处，全部按语义修，无一处 `@SuppressWarnings` 压制）
  - ✅ 依赖版本收敛（`-Pdep-convergence`，随父版本一并获得）
- ⬜ ArchUnit 补齐母仓有而本仓缺的门禁
  - ✅ 模块发布边界（`ModulePublishingTest`，已反向验证）
  - ✅ 配置元数据（`ConfigMetadataTest`，已反向验证）
  - ✅ 注册发现：**已有**（`IotAutoConfigurationTest.CFG-08` 已做 SpringFactoriesLoader 发现，不重复建设）
  - ✅ 编码规则（printStackTrace/System.out/字段注入/Collections）：**已有**（`ARCH-05`）
- ✅ `tools/`：`check-nullaway.sh`（含执行自检）、`export-config-metadata.mjs`（聚合 + 漂移门禁，已反向验证）、`preflight.sh`（发布前总检）
- ✅ 发布前置：GPG + `central-publishing-maven-plugin`（在根 pom 的 release profile；`-Prelease` 反应堆已实测不含非发布模块）
- ⬜ 发布 secrets（Central 凭据 + GPG 私钥）与首个正式版本号

## M2 · 协议扩容（⬜）

- ✅ OPC UA 用户名令牌端到端验证（`SEC-E2E-02` 通过令牌认证读到数据；`SEC-E2E-03` 证明错误口令被拒）
- ⬜ Modbus RTU 真实硬件验证
- ⬜ 指标桥（Micrometer）——当前默认无操作实现，**指标会被丢弃**
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

- ⬜ **`-Pit` 是空转**：本仓没有任何 `*IT.java`，`mvn -Pit verify` 不执行任何集成测试。
  协议侧端到端验证暂时都在各协议模块单测里（自带模拟器/broker/服务端）；
  若要真正用起 IT 体系，需要把「跨模块 + 真实中间件」的场景拆出去。
- ⬜ **CI 从未执行**：父 pom 是未发布的 SNAPSHOT，本仓也没有配置远端仓库；
  在补齐这两项之前，`.github/workflows/` 的绿灯不构成验证。
- ⬜ OPC UA：信任目录放「CA 签发的叶子」不可用（Milo 只把自签证书当信任锚）；
  `HOSTNAME` 已做成可选开关、`APPLICATION_URI` 保留，但两者的比对值取自服务端自述，
  **对主动 MITM 无防护**，真正的门闩只有信任列表。
- ⬜ 覆盖率数字存在并发分支导致的 ±0.5pp 不可复现噪声。
- ⬜ `modbus` 与 `transport` 的分支覆盖余量偏薄（现约 +6% / +5%）。
