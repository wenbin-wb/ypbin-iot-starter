# 发布指南

> **当前状态：`0.1.0-SNAPSHOT` 从未发布。** 本文件是**待执行**的发布流程。
> 发布插件（central-publishing / GPG）**已配置**在根 pom 的 `release` profile，
> 但 **secrets 与首个正式版本号还没配**——见下方「一、发布前置」。

## 一、发布前置

| 项 | 状态 | 说明 |
|---|---|---|
| `LICENSE` | ✅ | Apache 2.0 |
| 非发布模块隔离 | ✅ | `ypbin-iot-architecture-tests` 由 `dev-only` profile 承载；**已实测**：`-Prelease` 的反应堆为 12 个模块、不含它。该不变量由 `ModulePublishingTest` 与 `release.yml` 双重守着 |
| GPG 签名 + `central-publishing-maven-plugin` | ✅ 已配置 | 在根 pom 的 `release` profile（母仓的发布配置在其**根 pom**，不在已发布的父 pom，故本仓需独立声明）|
| Central 凭据与 GPG 私钥 | ⬜ **需配置** | GitHub 仓库 secrets：`MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` / `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE`；并创建 `release` environment |
| 首个正式版本 | ⬜ | `<revision>` 目前是 `0.1.0-SNAPSHOT`；发布前先改为 `0.1.0` |

> 发布工作流默认是 `dry-run`（只做前置校验与打包，不签名不上传）——
> 在凭据配置完成前，**不会**出现「看起来发布了其实什么都没上传」的情况。

## 一之二、CI 现状

| 项 | 现状 |
|---|---|
| 父 pom 获取 | ✅ 钉的是已发布正式版 `3.1.0`（Central 可解析），CI 可直接构建 |
| 远端仓库 | ✅ 已配置（`github.com/wenbin-wb/ypbin-iot-starter`） |
| 工作流 | `.github/workflows/` 的 ci / codeql / release 已接入（release 默认 dry-run）|
| 首次 CI 运行 | ✅ 已通过（8 个步骤全部真实执行：构建 124s / NullAway 26s / 依赖收敛 / 元数据 / SBOM 26s）|

## 二、每次发布

1. 确认 `mvn clean test`（**不带任何 `-P`**）全绿 —— 这是唯一会跑架构门禁的调用方式
   （`dev-only` 是 `activeByDefault` profile，显式激活任一 profile 都会让它失效）。
2. 更新 `CHANGELOG.md`：把 `[未发布]` 改为目标版本号并补日期。
3. 根 pom 的 `<revision>` 改为目标版本（`0.1.0`），提交并打 tag（`v0.1.0`）。
4. 触发 `Release` 工作流（先 `dry-run=true` 校验，再 `dry-run=false` 正式发布），
   或在本地执行 `mvn -Prelease -DskipTests deploy`（需本机配好 GPG 与 `~/.m2/settings.xml` 的 `central` 服务端凭据）。
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
