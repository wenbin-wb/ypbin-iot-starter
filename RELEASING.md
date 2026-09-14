# 发布指南

> **当前状态：`0.1.0-SNAPSHOT` 从未发布。** 本文件是**待执行**的发布流程，
> 其中的发布配置（central-publishing / GPG）**尚未接入本仓**——见下方「一、发布前置（未完成）」。

## 一、发布前置（未完成，发布前必须补齐）

| 项 | 状态 | 说明 |
|---|---|---|
| `LICENSE` | ✅ | Apache 2.0 |
| GPG 签名配置 | ❌ | 母仓通过 profile 提供；本仓尚未接入 |
| `central-publishing-maven-plugin` | ❌ | 同上 |
| 非发布模块隔离 | ✅ | `ypbin-iot-architecture-tests` 由 `dev-only` profile 承载，`-Prelease` 会排除 |
| 发布后推进开发版本 | 见第四节 | |

## 二、每次发布

1. 确认 `mvn clean test`（**不带任何 `-P`**）全绿 —— 这是唯一会跑架构门禁的调用方式
   （`dev-only` 是 `activeByDefault` profile，显式激活任一 profile 都会让它失效）。
2. 更新 `CHANGELOG.md`：把 `[未发布]` 改为目标版本号并补日期。
3. 根 pom 的 `<revision>` 改为目标版本（`0.1.0`），提交并打 tag（`v0.1.0`）。
4. 执行发布构建（**接入发布插件后**）。
5. **立刻**把 `<revision>` 推回下一个开发版本（`0.2.0-SNAPSHOT`）并提交。

> 第 5 步不是可选项。母仓出过一次真实事故：**漏了这一步**导致 master 在已发布坐标下继续累积改动，
> 本机 `~/.m2` 的同一坐标与中央仓库**同名不同内容**，宿主解析到的是「从未发布」的代码。

## 三、模块边界（发布前必须核对）

- **会发布**：`ypbin-iot-core`、`-runtime`、`-transport`、`-spring-boot-starter`、
  `-protocol-{tcp,modbus,mqtt,opcua}`、`-test`（TCK 基座，协议作者需要）、`-bom`。
- **不发布**：`ypbin-iot-architecture-tests`（声明 `maven.deploy.skip=true`，由 `dev-only` profile 承载）。
- 该边界由 `ModulePublishingTest` 强制：把非发布模块写回顶层 `<modules>` 会让构建失败。

## 四、版本迭代规范

- `0.x` 阶段不提供兼容性承诺（见 `CONTRACT.md`）。
- 进入 `1.0.0` 后，SPI 稳定面的破坏性变更必须走主版本，并在 `CHANGELOG.md` 的
  「破坏性变更」小节写明**迁移方式**。
