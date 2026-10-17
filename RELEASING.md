# 发布指南

> **当前状态：开发版 `0.3.4-SNAPSHOT`；已发布 `0.1.0`（2026-09-21）、`0.2.0`（2026-09-29）与 `0.3.0`（2026-10-12）。**
> 本文件描述的是**每次发布**的流程。
> 发布插件（central-publishing / GPG）**已配置**在根 pom 的 `release` profile，
> 但 **GitHub 侧的 Central 凭据与 GPG secrets 至今未配全**——因此**工作流发布路径不可用，历史上三个版本都是本地发布**，见下方「一、发布前置」与「二、每次发布」第 4 步。

## 一、发布前置

| 项 | 状态 | 说明 |
|---|---|---|
| `LICENSE` | ✅ | Apache 2.0 |
| 非发布模块隔离 | ✅ | `ypbin-iot-architecture-tests` 与 `ypbin-iot-integration-tests` 由 `dev-only` profile 承载；**已实测**：`-Prelease` 的反应堆为 12 个项目、两者均不在其中。该不变量由 `ModulePublishingTest` 与 `release.yml` 双重守着 |
| GPG 签名 + `central-publishing-maven-plugin` | ✅ 已配置 | 在根 pom 的 `release` profile（母仓的发布配置在其**根 pom**，不在已发布的父 pom，故本仓需独立声明）|
| Central 凭据与 GPG 私钥 | ⚠️ **工作流不可用 / 本地可用** | GitHub 仓库 secrets（`MAVEN_CENTRAL_USERNAME` / `MAVEN_CENTRAL_PASSWORD` / `GPG_PRIVATE_KEY` / `GPG_PASSPHRASE`）与 `release` environment **均未配置**：2026-09-29 的非 `dry-run` 运行中 `release` 作业**实测失败**（`guard` 成功、`release` failure），故 `0.1.0`/`0.2.0`/`0.3.0` **全部由本地发布**。本机可用的凭据在 `~/.m2/settings.xml`：`central` server（用户名/口令）+ `gpg` profile（`gpg.executable`/`gpg.keyname`/`gpg.passphrase`）；已实测 `mvn -Prelease -DskipTests verify` 能正常签名（产出 27 个 `.asc`）。**要走工作流先把上述 secrets 配全** |
| 已发布版本 | ✅ `0.1.0` / `0.2.0` / `0.3.0` | 三个版本均已发布到 Maven Central，首个真实宿主为 `ypbin-access`。发版后 `<revision>` 已推回下一开发版 `0.3.4-SNAPSHOT`（**这一步不是可选项**，漏了会出现「同名不同内容」事故） |
| 仓库可见性 | 私有 | 徽章只对已登录用户可见；转公开后自动生效 |

> 发布工作流默认是 `dry-run`（只做前置校验与打包，不签名不上传）——
> 在凭据配置完成前，**不会**出现「看起来发布了其实什么都没上传」的情况。

## 一之二、CI 现状

| 项 | 现状 |
|---|---|
| 父 pom 获取 | ✅ 钉的是已发布正式版 `3.8.0`（Central 可解析），CI 可直接构建。**该值与 `pom.xml` 的 parent 版本必须一致**——本行曾长期写 `3.1.0` 而实际是 `3.6.0/3.8.0`（2026-10-12 更正） |
| 远端仓库 | ✅ 已配置（`github.com/wenbin-wb/ypbin-iot-starter`） |
| 工作流 | `.github/workflows/` 的 ci / codeql / release 已接入（release 默认 dry-run）|
| 首次 CI 运行 | ✅ 已通过；**具体步骤与耗时请看 Actions 运行页**（这里的「8 个步骤 / 124s」是首次运行的快照，作业结构后来已变为 build + integration-tests 两个作业，故不再在此登记数字）|

## 二、每次发布

1. 确认 `mvn clean test`（**不带任何 `-P`**）全绿 —— 这是唯一会跑架构门禁的调用方式
   （`dev-only` 是 `activeByDefault` profile，显式激活任一 profile 都会让它失效）。
2. 更新 `CHANGELOG.md`：把 `[未发布]` 改为目标版本号并补日期。
3. 根 pom 的 `<revision>` 改为目标版本（`0.1.0`），提交并打 tag（`v0.1.0`）。
4. 发布（两条路，**当前只有第二条可用**）：
   - ⚠️ **工作流**：`Release` 工作流（先 `dry-run=true` 校验，再 `dry-run=false` 正式发布）—— **需先配全 GitHub secrets**，否则 `release` 作业必失败（2026-09-29 已实测）。
   - ✅ **本地**：`mvn -Prelease deploy`（**不要**加 `-DskipTests`：`deploy` 会跑全量单测，`verify` 阶段会 GPG 签名）。需本机 `~/.m2/settings.xml` 配好 `central` server 与 `gpg` profile；跑完**手工补** tag 与 GitHub Release（工作流那条路才会自动 tag）：
     `git tag vX.Y.Z && git push origin vX.Y.Z`，并在 GitHub 上建对应 Release。
5. **立刻**把 `<revision>` 推回下一个开发版本（`0.2.0-SNAPSHOT`）并提交。

> 第 5 步不是可选项。母仓出过一次真实事故：**漏了这一步**导致 master 在已发布坐标下继续累积改动，
> 本机 `~/.m2` 的同一坐标与中央仓库**同名不同内容**，宿主解析到的是「从未发布」的代码。

## 三、模块边界（发布前必须核对）

**以 pom 为准**（本节曾漏掉 `integration-tests`，与 README 的口径也不一致；现已对齐）：

- **会发布**：`ypbin-iot-dependencies`（parent）、`-bom`（对外 BOM）、`-core`、`-runtime`、`-transport`、
  `-spring-boot-starter`、`-protocol-{tcp,modbus,mqtt,opcua}`、`-test`（TCK 基座，协议作者需要）。
- **不发布**（均声明 `maven.deploy.skip=true`，由 `dev-only` profile 承载）：
  `ypbin-iot-architecture-tests`、`ypbin-iot-integration-tests`。
- 该边界由 `ModulePublishingTest` 强制：把非发布模块写回顶层 `<modules>` 会让构建失败。
- ⚠️ `-test` 的 JUnit / AssertJ 是 **compile 作用域**（协议模块的 TCK 子类依赖它们），
  发布后会被**传递**给依赖方 —— 这是有意为之（它是测试基座），但也意味着
  **生产代码不应依赖 `ypbin-iot-test`**。

## 四、版本迭代规范

- `0.x` 阶段不提供兼容性承诺（见 `CONTRACT.md`）。
- 进入 `1.0.0` 后，SPI 稳定面的破坏性变更必须走主版本，并在 `CHANGELOG.md` 的
  「破坏性变更」小节写明**迁移方式**。
