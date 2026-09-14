# ypbin-iot-protocol-opcua

> OPC UA 协议适配器 —— 基于 `org.eclipse.milo:milo-sdk-client:1.1.7`（MPL-2.0 / EPL-2.0 双许可）。

引入即自动装配。

---

## 快速开始

```xml
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-opcua</artifactId>
</dependency>
```

```yaml
ypbin:
  iot:
    protocol:
      opcua:
        enabled: true
        connect-timeout: 10s
        request-timeout: 10s
        publishing-interval: 500ms
        max-nodes-per-read: 500     # 单次 Read 服务最多携带的 NodeId 数
        browse-max-depth: 1
        browse-max-nodes: 1000
```

端点：`opc.tcp://host:4840`（默认端口 4840）。

---

## 能力

| 能力 | 说明 |
|---|---|
| `READ` | **批量读**：一次 Read 请求携带多个 NodeId（协议原生能力，不逐点位调用）|
| `WRITE` | 批量写，结果逐节点返回 `StatusCode` |
| `SUBSCRIBE_NATIVE` | **服务器原生推送**，不占框架轮询调度资源 |
| `BROWSE` | **地址空间浏览**（`BrowseExtension` 的首个真实实现）|
| `MULTI_DEVICE_LINK` | 一条服务器连接承载多台设备（多组节点子树）|

不声明 `SUBSCRIBE_POLLING`：有原生推送就不该再声明轮询。

---

## 地址写法（NodeId）

只接受标准 NodeId 写法，**不做猜测**：

| 写法 | 含义 |
|---|---|
| `ns=2;s=Device.Temperature` | 命名空间 2 的字符串标识 |
| `ns=2;i=1234` | 命名空间 2 的数值标识 |
| `i=2258` / `s=MyVar` | 命名空间 0（标准地址空间）|

**裸标识符（如 `Temperature`）会被拒绝**：命名空间索引是 NodeId 的组成部分，缺失时无法推断用户指的是
ns=0（标准类型）还是 ns=2（厂商模型）。猜错的表现是「解析成功但读到 `BadNodeIdUnknown`」——
比直接报错难排查得多（与 Modbus 侧同一口径）。

---

## 行为约定

| 场景 | 行为 |
|---|---|
| 批量读/写 | 按 `max-nodes-per-read` 分批，**结果顺序与请求顺序一致**（不是服务端返回顺序）|
| 某节点地址非法 | 只让该节点失败（质量 `CONFIG_ERROR`），其余节点正常 |
| 某节点返回非 Good 状态码 | 该节点质量 `BAD`，`qualityReason` 带原始状态码 |
| 写只读节点 | 该写项逐项失败（服务端拒绝），其余写项照常 |
| 浏览超过 `max-nodes` | 截断并记 WARN，不继续拉取（防止在大型地址空间上失控）|
| 配置了未实现的非 None 安全策略 | **fail-fast 拒绝**，绝不静默走明文 |
| 传输层 TLS | **fail-fast 拒绝**（应改用 OPC UA 安全策略，而不是套 TLS）|

---

## 协议特有坑

1. **`opc.tcp` 是一个 scheme，不是 `opc`**：`URI.getScheme()` 对 `opc.tcp://` 返回 `"opc.tcp"`（scheme 允许点号）。
   按直觉写白名单会让**所有连接被拒**——这个坑只有端到端测试才会暴露。
2. **服务端传输实现在独立制品**：`milo-sdk-server` 不传递 `OpcTcpServerTransport`，需要额外的 `milo-transport`。
   按名字猜会找不到类。
3. **`Namespace` 接口没有生命周期**：Milo 1.x 的节点在构造器里建、再用 `AddressSpaceManager.register(...)` 注册，
   不存在 `onStartup()`。但 `onDataItemsCreated/Modified/Deleted/onMonitoringModeChanged` 四个方法是**抽象方法**，
   必须实现（用一张 map 跟踪数据项是标准做法）。
4. **`OpcUaMonitoredItem$DataValueListener` 是两参数方法**：`onDataReceived(OpcUaMonitoredItem, DataValue)`，
   不是单参数。按文档名猜必错。
5. **`ExpandedNodeId.toNodeId(...)` 必须传命名空间表**：无参重载不存在。
6. **不要覆盖基类已实现的方法**：`ManagedAddressSpace` 已实现 `read`/`write`/`browse`，覆盖成空实现会静默失效。
7. **安全默认是明文**：默认 `SecurityPolicy#None`（现场大量旧设备只支持明文）。
   **生产环境必须显式配置签名/加密策略**——但注意本版本**尚未实现**非 None 策略，配置后会 fail-fast 而不是静默明文。
8. **认证尚未实现**：当前是匿名连接。需要用户名/密码或证书的服务器连不上，且没有对应配置项。

---

## 已知未完成项

| 项 | 说明 |
|---|---|
| **原生订阅的推送路径未验证** | 订阅创建与取消已验证；但「服务端改值 → 推送」需要测试 harness 通过 Milo 的 `AttributeService` 驱动值变更，当前 harness 用 `UaVariableNode.setValue` 不足以触发上报。`OpcUaAdapterTckTest#nativeSubscriptionMustReceivePush` 以 `Assumptions.abort` **显式标记为未验证**（不是静默跳过）。 |
| 非 None 安全策略 | 未实现（含签名/加密与证书管理），配置后 fail-fast |
| 认证 | 匿名连接；用户名/密码、X.509 用户证书均未实现 |
| 方法调用（Method）与事件订阅 | 未实现（`EVENT` 能力未声明）|

---

## 许可

- 依赖 `org.eclipse.milo:milo-sdk-client`（**MPL-2.0 / EPL-2.0 双许可**，弱 copyleft，以库形式链接无传染义务）。
- 测试用 `milo-sdk-server` 与 `milo-transport`（同许可，仅 test scope）。
