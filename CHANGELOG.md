# 更新日志

本项目遵循[语义化版本](https://semver.org/lang/zh-CN/)：`主版本.次版本.修订号`。
格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

当前开发版：`0.2.1-SNAPSHOT`。

已发布：**`0.1.0`（2026-09-21，Maven Central）**——首个正式版，由 `ypbin-access` 作为首个真实宿主接入验证。

## [未发布]

### 新增

- **契约显式化：同一设备每次 ADD 都产生新的 `DeviceSession` 实例、且旧会话先被关闭**
  （`IotLifecycleTest#everyAddMustCloseOldSessionAndRebindNewInstance`，LIFE-13）。
  宿主 `ypbin-access` 的订阅对账以「会话实例是否变化」决定要不要重新订阅（`subscribedSessions` 用 `==` 比较），
  此前这条语义只存在于 `bind()` 的实现细节、无测试覆盖；现提升为**有测试支撑的行为承诺**（见 `CONTRACT.md` §四 第 7 条），
  防止「改为复用会话实例」导致宿主静默退化成「改了点位却永不重新订阅」。

## [0.2.0] - 2026-09-29

### 修复

- **TCP 透传整帧当值、曲线画不出（UP-3，#13）**：新增 `ypbin.iot.protocol.tcp.payload-format`
  （`binary`（默认，向后兼容）/`text`（严格 UTF-8）/`number`），`TcpSession` 按格式解码入站帧——
  非法字节/非数值帧产出 **BAD 质量 + 明确消息键**（不再是 `[B@<hash>` 字面文本、也不再静默 GOOD）。
- **TCP 订阅 N 点位只投递第 0 个（UP-4，#15）**：明示「单点位透传流」契约——`subscribe()`
  对多地址 **fail-fast**（拒绝「声称订阅 N 个点位却静默只交付 1 个」）；一帧多点位的
  `valueSelector` 语义列为后续演进项。
- **建链超时死键（UP-2，#14）**：`ypbin.iot.protocol.<code>.connect-timeout` 从「有绑定、无消费」
  变为活键——宿主未显式给建链超时（恰为默认值）时用配置值覆盖（`ConnectionSpec.withConnectTimeout`）；
  绑定编排的 `probe/acquire/bind` 超时随之生效（connect-timeout=2s 时一趟绑定最坏 2s，不再恒为 10s 饿死租约）。
- **iotMetricsRecorder bean 竞争（UP-11，#16）**：`IotMicrometerAutoConfiguration` 声明
  `afterName=org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration`，
  修正 `@ConditionalOnBean(MeterRegistry)` 求值时机；classpath 有 micrometer 却仍回退 Noop 时日志升 **WARN**。
- **辅助缓冲无消费者噪声（UP-12，#17）**：`NettyChannelConnection` 的入站缓冲**惰性启用**——
  无 `drainFrames()` 消费者时不入队、不计数、不告警；缓冲溢出首帧 WARN、其后 DEBUG，
  计数语义注明「淘汰的是辅助缓冲副本，不代表数据面投递丢失」。

## [0.1.0] - 2026-09-21

### 新增

- **多协议适配 SPI**（core）：`ProtocolAdapter` / `ProtocolConnection` / `DeviceSession` 三层契约，
  以及 `AdapterContext`、`CredentialResolver`、`DataEgress`、`MetricsRecorder`、`DeliveryDispatcher`、
  `TaskScheduler` 等框架门面。契约详见 `docs/SPI.md`。
- **运行时**（runtime）：`ConnectionRegistry`（单飞建链 + 引用计数 + 空闲回收 + 建链限速）、
  `PollingSubscriptionManager`（`SUBSCRIBE_POLLING` 的框架侧实现）、
  `BoundedDeliveryDispatcher`（宿主回调有界投递）、`EgressRouter`（有界出口 + 溢出策略）。
- **传输底座**（transport）：基于 Netty 的 TCP 连接与三种帧定界（不定界 / 长度字段 / 分隔符）。
- **Spring Boot starter**（spring-boot-starter）：`ypbin.iot.*` 条件装配、`IotLifecycle` 设备生命周期编排、
  **连接意外断开后的指数退避重连与重绑定**、i18n 消息键（中/英）。
- **协议模块**：`protocol-tcp`（透传）、`protocol-modbus`（TCP/RTU，含 TLS fail-fast）、
  `protocol-mqtt`（MQTT 3.1.1）、`protocol-opcua`（含**安全策略与用户名密码认证**、
  浏览、原生订阅）。
- **集成测试体系**（`ypbin-iot-integration-tests`，不发布）：完整 Spring Boot 上下文 + 嵌入式 broker
  的端到端场景（自动装配 → 生命周期绑定 → 建链 → 订阅 → 中间件推送 → 出口），
  由 `-Pit`（failsafe）执行、CI 有独立作业。**该体系上线即抓到「MQTT 二进制负载被静默损坏」这一真实缺陷。**
- **OPC UA 信任模型与装配期诊断**：三条部署语义（自签证书=精确 pin / 自签 CA=信任该 CA 的一切 /
  CA 签发的叶子不可用）经源码级确认，并在装配期给出诊断（`SEC-15`/`SEC-16` 反向验证）。
- **工程门禁**：ArchUnit 架构约束（含规则有效性自检）、源码规范扫描、JaCoCo 指令 ≥0.80 与
  **分支 ≥0.64**、spotless、依赖版本收敛、模块发布边界门禁、配置元数据漂移门禁
  （含「模块集合不得静默缩小」）、**覆盖率快照由构建产物生成**（`tools/export-coverage.mjs`）、
  CI 覆盖率归档前的存在性断言。

### 已知限制

- **尚未发布**：`0.1.0-SNAPSHOT` 只在本地/CI 构建，Maven Central 上无此坐标。
- **OPC UA 用户名密码认证**：已在 `Basic256Sha256 + SignAndEncrypt` 上完成端到端验证
  （`SEC-E2E-02`），并有「错误口令必须被拒」的反向用例（`SEC-E2E-03`）守着他。
- **OPC UA**：非 None 安全策略下，信任目录放「CA 签发的叶子证书」**不可用**
  （Milo 只把自签证书当信任锚），需放自签叶子或整个 CA；两个可选校验
  （`HOSTNAME` / `APPLICATION_URI`）的比对值来自服务端自述，**对主动 MITM 无防护**。
- **MQTT**：基线为 MQTT 3.1.1（MQTT 5 专属配置不支持）；不声明 `READ` 能力。
  负载需按 `payload-format` 配置解码：`text`（默认，非法 UTF-8 产出 BAD 而非损坏字符串）、
  `number`、`binary`（原样交付 `byte[]`）。
- **Modbus RTU**：无真实硬件验证（仅协议层与串口配置校验）。
- **指标**：宿主提供 `MeterRegistry`（例如引入 `spring-boot-starter-actuator`）时
  自动装配 **Micrometer 指标桥**（读写计数按结果分维度、读写耗时计时器、订阅点位数、
  按消息键分类的错误数、瞬时值 gauge）；没有 `MeterRegistry` 时回退到无操作实现并打 INFO。
