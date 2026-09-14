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
- ⬜ CI（`ci.yml` / `codeql.yml` / `release.yml`，对齐母仓）
- ⬜ NullAway（`-Pnullaway` + `@NullMarked`）覆盖全部模块
- ⬜ ArchUnit 补齐母仓有而本仓缺的门禁（注册发现、编码规则）
- ⬜ `tools/` 工具链（配置元数据导出与漂移门禁、`preflight.sh`）
- ⬜ 发布前置：GPG + `central-publishing-maven-plugin` 接入

## M2 · 协议扩容（⬜）

- ⬜ OPC UA 用户名令牌端到端验证（服务端需 `UserTokenPolicy` + 身份校验器）
- ⬜ Modbus RTU 真实硬件验证
- ⬜ 指标桥（Micrometer）——当前默认无操作实现，**指标会被丢弃**
- ⬜ GB/T 26875（消防）双版本
- ⬜ 剩余候选协议（见 `docs/PROTOCOLS.md` 的选型清单）

## 已知技术债（⬜）

- ⬜ OPC UA：信任目录放「CA 签发的叶子」不可用；`HOSTNAME`/`APPLICATION_URI` 对主动 MITM 无防护
- ⬜ 覆盖率数字存在并发分支导致的 ±0.5pp 不可复现噪声
- ⬜ `modbus` 与 `transport` 的分支覆盖余量偏薄（现约 +6% / +5%）
