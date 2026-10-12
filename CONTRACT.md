# 契约与兼容性承诺

本文件说明 `ypbin-iot-starter` 对外承诺什么、不承诺什么。**只写实际成立的事**。

## 一、版本语义

遵循语义化版本。当前开发版 `0.2.1-SNAPSHOT`；已发布 **`0.1.0`（2026-09-21）** 与
**`0.2.0`（2026-09-29）**。也就是说，本仓仍处于 `0.x` 阶段，**不提供跨版本兼容性保证**；
`1.0.0` 之前的 API 可能随时变更（现行版本以根 `pom.xml` 的 `<revision>` 与 `CHANGELOG.md` 为准）。

## 二、稳定面（SPI）

以下类型一旦进入 `1.0.0` 即视为稳定面，破坏性变更须走主版本：

- `cn.ypbin.iot.core.protocol.*`：`ProtocolAdapter`、`ProtocolConnection`、`DeviceSession`
- `cn.ypbin.iot.core.context.*`：`AdapterContext`、`AdapterSettings`、`DataEgress`、
  `CredentialResolver`、`MetricsRecorder`、`DeliveryDispatcher`、`TaskScheduler`
- `cn.ypbin.iot.core.model.*`：`ConnectionSpec`、`DeviceSpec`、`PointAddress`、`PointValue`、
  `DataBatch`、`ReadRequest`/`ReadResult`、`WriteRequest`/`WriteResult`、`SubscribeRequest`、
  `SubscriptionHandle`、`ProbeResult`、`SessionState`、`CloseReason`
- `cn.ypbin.iot.core.exception.*` 的**消息键**（`IotMessageKeys` 与各适配器的 `MSG_*` 常量）

## 三、不承诺的面

- **内部实现类**：`cn.ypbin.iot.runtime.*`、`cn.ypbin.iot.transport.*` 的具体实现类
  以及各协议模块的内部类，视为实现细节。
- **配置键的拼写**：配置键以 `spring-configuration-metadata.json` 为准；
  重命名会走废弃周期，但**不保证**长期别名。
- **日志文本**：日志只保证级别与「不静默」这一性质，不保证措辞。

## 四、行为承诺（**这些是有测试支撑的**，不是意愿声明）

1. **不静默降级**：未实现的组合一律 fail-fast，不静默退回明文/弱化安全。
   例：OPC UA 未实现的安全策略拒绝；配置了凭据但策略为 `None`（凭据会明文传输）拒绝；
   TLS 未实现拒绝；MQTT 5 专属配置拒绝。
2. **宿主回调不在协议线程上执行**：`DataListener.onData`、`DeviceEventListener.onEvent`
   经 `DeliveryDispatcher` 投递到框架执行器；过载**有界丢弃并计数**，不无限堆积、不阻塞协议线程。
3. **显式超时**：所有建链与请求都必须显式配置超时（`ConnectionSpec.connectTimeout`/`requestTimeout`
   有非空默认值，且各协议实现都消费它们）。
   *（OPC UA 另把 Milo 内部的 `connectTimeout`/`acknowledgeTimeout` 接线为同一值，以避免
   「两个超时赛跑」——该接线已实现，但**尚未有测试能在删除它时变红**，故不计入下面的
   「有测试支撑」范围。）*
4. **失败原因可区分**：`probe()` 与失败的 `open()` 带出**真实**消息键，
   不把配置错误折叠成「端点不可达」。
5. **重连不静默停摆**：连接意外断开按 `reconnect-initial-delay`/`max-delay`/`jitter`
   指数退避重连并重绑定其上设备；**可重复生效**（不是只生效一次）；
   显式关闭与设备 REMOVE 取消重连；全程有日志与计数。
6. **进程停机时不静默丢在途回调**：投递器关闭时有界等待在途宿主回调。
7. **每次 ADD 都换新的 `DeviceSession` 实例、且旧会话先被关闭**：`onDeviceChange(ADD)`（含规格变化，
   宿主只重发 ADD）与 `REMOVE → ADD` 两条路径都会先 `detach()`（`session.close()` + 从会话表移除）
   再由适配器 `bind()` 产生**新实例**。宿主**依赖**这条语义做订阅对账（`ypbin-access` 的
   `AccessSubscriptionPlanner` 以「实例是否相同」判断要不要重新订阅）⇒ 不得改为复用实例。
   测试：`IotLifecycleTest#everyAddMustCloseOldSessionAndRebindNewInstance`（LIFE-13）。
8. **TCP 键值帧一帧多点位（`payload-format=key-value`）**：`PointAddress.raw` 被解释为
   `KEY=VALUE` 帧的字段 selector；`subscribe()` 允许一次订阅多个 selector，同一帧为每个
   地址各自产出一个 `PointValue`（值类型为 `String`，不做物模型类型转换）。
   selector 语法非法（含 `=`、`,`、`;`、换行）在订阅期以 `AddressParseException` fail-fast；
   运行期 selector 未命中只让该点位产出 BAD（`iot.tcp.selector.not-found`），
   同帧其他点位照常交付，不静默丢弃；整帧非 UTF-8 / 无键值字段时全部点位 BAD。
   非 `key-value` 格式仍保持「单点位流」多地址 fail-fast。
   完整语义见 [`docs/TCP-VALUE-SELECTOR.md`](./docs/TCP-VALUE-SELECTOR.md)；
   测试：`TcpSessionBehaviourTest#keyValueFrameMustServeMultiplePoints`（TCP-12）、
   `#missingSelectorMustYieldBadPointOnly`（TCP-13）、`#invalidSelectorMustFailFastOnSubscribe`（TCP-14）、
   `#subscribeWithMultipleAddressesMustFailFast`（TCP-11，非 key-value 回归）。

## 五、明确不做的（有意为之，不是缺陷）

- **不做协议库的封装层抽象**：直接暴露各协议库的能力边界（如 Modbus 地址形态固定、不猜测）。
- **不做数据持久化/上云**：本仓只负责「连上、读写、订阅」，数据出口交给宿主的 `DataEgress`。
- **不提供 OPC DA / PROFINET / LonWorks**（依赖原生栈，见 `docs/PROTOCOLS.md`）。
