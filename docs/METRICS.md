# 协议层错误指标（iot.tcp / iot.modbus / iot.mqtt / iot.opcua）

> **用途**：登记 SDK（协议层）在失败路径上真正上报的错误指标，作为「文档承诺 ↔ 代码调用点」
> 的机器可校验契约。修的是 issue #27：`MetricsRecorder#recordError` 曾在生产代码零调用，
> 于是宿主（如 `ypbin-access`）的 `GET /actuator/metrics/ypbin.iot.errors` 无论桥是否接通都 404。

## 1. 指标形态与惰性注册

- **指标名**：`ypbin.iot.errors`，一个 Counter；**维度**：`type=<错误码>`。
- **惰性注册**：Counter 在**第一次** `recordError` 调用时才注册到 `MeterRegistry`。
  因此「指标不存在」既可能是桥没接通，也可能是**从未发生过该类失败**——二者在
  `/actuator/metrics/ypbin.iot.errors` 上都表现为 404。判断桥是否接通，请用一条**能人为触发**
  的失败路径（例如把 TCP 模拟器帧的键改成不匹配任意 selector，触发
  `iot.tcp.selector.not-found`）。
- **上报位置**：错误计数**由协议层失败路径上报**（`AdapterContext#metrics()` →
  `MetricsRecorder#recordError`），框架/桥只负责把 `MetricsRecorder` 接到 Micrometer，
  不会替协议层凭空造错误。
- **标签值必须是稳定错误码**：全部来自各协议适配器已有的 `MSG_*` 常量；未知原因不产生标签，
  避免标签基数失控。
- **失败只计一次**：同一失败分支不重复 `recordError`；写失败与编码失败按各自的错误码分开计。
- **不可吞异常、不影响主流程**：`MetricsRecorder` 是门面，计数失败不影响协议操作的返回/状态语义
  （本仓只做「加计数」，不改任何返回值）。

## 2. 错误码表

### 2.1 TCP（`iot.tcp.*`）

| 错误码（`type`） | 触发失败 | 上报位置 |
|---|---|---|
| `iot.tcp.write.failed` | `connection.write` 失败 | `TcpSession#recordStatus` |
| `iot.tcp.connection.inactive` | probe 探测失败（链路不可用） | `TcpAdapter#probe` |
| `iot.tcp.payload.not-utf8` | `payload-format=text/number` 下帧非合法 UTF-8 | `TcpSession#recordDecodeFailure` |
| `iot.tcp.payload.not-number` | `payload-format=number` 下帧无法解析为数值 | `TcpSession#recordDecodeFailure` |
| `iot.tcp.subscribe.multi-address-unsupported` | 非 key-value 格式一次订阅多个地址 | `TcpSession#subscribe` |
| `iot.tcp.payload.not-key-value` | key-value 格式下帧不是键值帧 | `TcpSession#recordDecodeFailure` |
| `iot.tcp.selector.not-found` | key-value 帧内找不到 selector 对应字段 | `TcpSession#recordDecodeFailure` |
| `iot.tcp.selector.value-empty` | key-value 帧内 selector 对应字段值为空白 | `TcpSession#recordDecodeFailure` |
| `iot.tcp.payload.unsupported` | 写值类型不支持（如数字） | `TcpSession#recordEncodeFailure` |
| `iot.tcp.payload.hex-invalid` | `hex:` 字面量格式非法 | `TcpSession#recordEncodeFailure` |

### 2.2 Modbus（`iot.modbus.*`）

| 错误码（`type`） | 触发失败 | 上报位置 |
|---|---|---|
| `iot.modbus.unit-id.invalid` | 从站地址（`localAddress`）非法 | `ModbusSession` 构造器 |
| `iot.modbus.address.invalid` | 点位地址非法（读/写） | `ModbusSession#read` / `ModbusSession#writeOne` |
| `iot.modbus.not-writable` | 向只读寄存器写入 | `ModbusSession#writeOne` |
| `iot.modbus.value.invalid` | 写入值类型不支持 | `ModbusSession#writeOne` |
| `iot.modbus.write.failed` | 写请求返回错误 | `ModbusSession#writeOne` |
| `iot.modbus.read.chunk-failed` | 分块读失败（非超时） | `ModbusSession#markChunkFailed` |
| `iot.modbus.read.timeout` | 分块读超时 | `ModbusSession#markChunkFailed` |
| `iot.modbus.response.too-short` | 响应长度不足 | `ModbusSession#executeChunk` |
| `iot.modbus.read.no-result` | 某点位读后无结果 | `ModbusSession#read` |
| `iot.modbus.session.closed` | 会话已关闭仍订阅 | `ModbusSession#subscribe` |
| `iot.modbus.connection.inactive` | 全部点位失败且链路不可用 | `ModbusSession#read` |
| `iot.modbus.transport.unsupported` | 承载方式不支持 / 链路类型不匹配 | `ModbusAdapter#open` / `ModbusAdapter#bind` |

### 2.3 MQTT（`iot.mqtt.*`）

| 错误码（`type`） | 触发失败 | 上报位置 |
|---|---|---|
| `iot.mqtt.topic.invalid` | 发布主题非法（含通配符/空/超长） | `MqttSession#publishOne` |
| `iot.mqtt.session.closed` | 会话已关闭仍订阅 | `MqttSession#subscribe` |
| `iot.mqtt.value.invalid` | 写入值类型不支持 | `MqttSession#publishOne` |
| `iot.mqtt.publish.failed` | 发布同步抛错或异步失败 | `MqttSession#publishOne` |
| `iot.mqtt.qos.invalid` | 设备 QoS 属性无法解析 | `MqttSession#qosOf` |
| `iot.mqtt.connection.inactive` | probe 探测失败（链路不可用） | `MqttAdapter#probe` |
| `iot.mqtt.payload.not-utf8` | `text/number` 格式下负载非合法 UTF-8 | `MqttSession#recordDecodeFailure` |
| `iot.mqtt.payload.not-number` | `number` 格式下负载无法解析为数值 | `MqttSession#recordDecodeFailure` |
| `iot.mqtt.transport.unsupported` | 承载方式不支持 / 链路类型不匹配 | `MqttAdapter#open` / `MqttAdapter#bind` |

### 2.4 OPC UA（`iot.opcua.*`）

| 错误码（`type`） | 触发失败 | 上报位置 |
|---|---|---|
| `iot.opcua.address.invalid` | NodeId 地址非法（读/写） | `OpcUaSession#read` / `OpcUaSession#write` |
| `iot.opcua.subscribe.failed` | 订阅全部监控项创建失败 | `OpcUaSession#subscribe` |
| `iot.opcua.subscribe.partial-failed` | 订阅部分监控项创建失败 | `OpcUaSession#subscribe` |
| `iot.opcua.status.bad` | 节点 StatusCode 非 Good（读/写） | `OpcUaSession#toPointValue` / `OpcUaSession#write` |
| `iot.opcua.credentials.need-security` | 明文策略下配置了用户名密码 | `OpcUaAdapter#open` |
| `iot.opcua.keystore.missing` | 缺少客户端证书（keystore） | `OpcUaAdapter#recordSecurityError` |
| `iot.opcua.trust.not-configured` | 未配置信任列表 | `OpcUaAdapter#recordSecurityError` |
| `iot.opcua.credential.missing` | 取不到凭据 | `OpcUaAdapter#recordSecurityError` |
| `iot.opcua.read.failed` | 读分块失败（非超时） | `OpcUaSession#read` |
| `iot.opcua.read.timeout` | 读分块超时 | `OpcUaSession#read` |
| `iot.opcua.read.no-result` | 节点无结果 | `OpcUaSession#toPointValue` |
| `iot.opcua.write.failed` | 写请求失败 | `OpcUaSession#write` |
| `iot.opcua.session.closed` | 会话已关闭仍订阅 | `OpcUaSession#subscribe` |
| `iot.opcua.connection.inactive` | 链路不可用（建链/读/写/bind/probe） | `OpcUaAdapter` / `OpcUaSession` |
| `iot.opcua.browse.failed` | 单节点浏览失败 | `OpcUaBrowser#browseLevel` |
| `iot.opcua.browse.unresolved` | 浏览结果无法解析为本地 NodeId | `OpcUaBrowser#browseLevel` |
| `iot.opcua.browse.truncated` | 浏览达到节点数上限被截断 | `OpcUaBrowser#browse` |

### 2.5 框架公共（`iot.common.*`）

> 本节由**框架层**（`iot-core` 的 `BytePayloadDecoding`、`iot-runtime` 的
> `BoundedBatchEgressQueue`）上报，不属于任何单个协议模块，因此**不参与** §3 的
> 「表 ↔ 协议模块源码」双向门禁（该门禁按 `iot.<protocol>.*` 前缀分组，只覆盖四个协议模块）。
> 这里的调用点由 `BytePayloadDecodingTest` / `BoundedBatchEgressQueueTest` 直接断言指标键。

| 错误码（`type`） | 触发失败 | 上报位置 |
|---|---|---|
| `iot.common.payload.decoder.not-found` | 宿主注册了解码器但没有一个认领该点位（字节原样交付） | `BytePayloadDecoding#apply` |
| `iot.common.payload.decode-failed.length-mismatch` | 字节长度与声明的解码规格不匹配 | `BytePayloadDecoding#invoke`（`DecodeFailure.LENGTH_MISMATCH`） |
| `iot.common.payload.decode-failed.not-finite` | 解出的浮点为 NaN/±Inf | `BytePayloadDecoding#invoke`（`DecodeFailure.NOT_FINITE`） |
| `iot.common.payload.decode-failed.invalid-value` | 字节串无法按声明规格解释 | `BytePayloadDecoding#invoke`（`DecodeFailure.INVALID_VALUE`） |
| `iot.common.payload.decode-failed.config-missing` | 该点位没有可用解码配置 | `BytePayloadDecoding#invoke`（`DecodeFailure.CONFIG_MISSING`） |
| `iot.common.payload.decode-failed.decoder-error` | 解码器抛异常或返回 null（实现缺陷） | `BytePayloadDecoding#invoke`（`DecodeFailure.DECODER_ERROR`） |
| `iot.common.egress.dropped` | 有界出口队列已满或已关闭，元素被丢弃 | `BoundedBatchEgressQueue#recordDrop` |

**失败原因码的来源是枚举**：`DecodeFailure` 的取值集合编译期有界，`messageKey()` 同时用作
BAD 质量的 `qualityReason` 与指标标签 ⇒ 日志看到的键与指标标签永远一致，不会产生基数爆炸。

## 3. 机器门禁

`ypbin-iot-architecture-tests` 的 `MetricsCallSiteGateTest` 双向断言本节错误码表与源码：

1. **表 → 代码**：表中每个错误码，其声明的 `MSG_*` 常量必须出现在协议层某个
   `recordError(...)` 调用语句中（不是只断言常量/注解存在）；
2. **代码 → 表**：协议层每个 `recordError(...)` 语句里出现的 `MSG_*` 常量都必须登记在本表。

新增错误码而不接调用点（或删掉调用点）会让门禁变红。
