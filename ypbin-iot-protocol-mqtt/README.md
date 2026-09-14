# ypbin-iot-protocol-mqtt

> MQTT 3.1.1 协议适配器 —— 基于 `com.hivemq:hivemq-mqtt-client:1.4.0`（Apache-2.0）。

引入即自动装配。

---

## 快速开始

```xml
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-mqtt</artifactId>
</dependency>
```

```yaml
ypbin:
  iot:
    protocol:
      mqtt:
        enabled: true
        connect-timeout: 10s
        request-timeout: 10s
        keep-alive-interval: 60s
        clean-session: true      # false 时 broker 保留订阅与离线消息
        qos-default: 1           # 0/1/2
        retained-default: false
```

端点：`tcp://broker:1883`（也接受 `mqtt://`、`ssl://`、`mqtts://`、`ws://`、`wss://`；TLS 尚未实现，配置后会 fail-fast 拒绝而不是静默明文）。

---

## 能力

| 能力 | 说明 |
|---|---|
| `WRITE` | 发布到主题 |
| `SUBSCRIBE_NATIVE` | **broker 原生推送**，不占框架轮询调度资源 |
| `MULTI_DEVICE_LINK` | 一条连接承载多台设备（按主题前缀切分）|

- **刻意不声明 `READ`**：MQTT 是发布/订阅模型，没有请求-响应语义。若声称支持读，就必须自造一套
  「请求主题 + 关联 id + 超时匹配」的伪协议——那是宿主业务层的编排职责，伪装成协议能力只会误导使用者。
  调用 `read()` 会按未声明能力 fail-fast。
- 不声明 `BROWSE`：MQTT 无目录概念。

---

## 地址与主题

| 用途 | 地址取值 |
|---|---|
| 订阅 | **主题过滤器**，支持 `+`（恰一层）与 `#`（剩余全部，只能是最后一层）|
| 发布 | **具体主题** |
| 投递值的地址 | **实际主题**（不是订阅过滤器——`#`/`+` 本身不是有效主题）|

设备 `localAddress` 是**主题前缀**（用于诊断），一条链路可绑定多台设备。

---

## 行为约定

| 场景 | 行为 |
|---|---|
| 订阅时过滤器非法（如 `a/b+`） | **fail-fast** 拒绝 |
| 取消不存在的订阅 | 幂等，不报错 |
| 关闭后的会话再次订阅 | 按未声明能力 fail-fast |
| 载荷类型不支持（如 Map） | 该写项**逐项失败**，其余写项照常 |
| 设备属性 `qos` 非法 | **告警并回落到默认值**，不让一条配置错误阻断整条链路 |
| 设备属性 `retained` | 覆盖协议默认的保留标志 |
| 连接超时 | **显式施加** `connect-timeout`，不依赖客户端默认行为 |

QoS 与 retained 配在**设备属性**上而不是点位属性：它们描述的是「这条链路对该 broker 的投递保证」，按点位配只会让运维困惑。

---

## 协议特有坑

1. **客户端标识必须进程内唯一**：broker 看到相同 clientId 的第二个连接会**踢掉第一个**，表现为
   「新连接一上来旧连接就断」的诡异循环。适配器已用「连接标识 + 序号」保证唯一。
2. **主题匹配不是字符串相等**：用 `equals` 会漏掉通配订阅，用 `startsWith` 会把 `a/bc` 错配到 `a/b`。
   适配器实现了完整的过滤器语义（含 `+` 只匹配一层、`#` 含零层）。
3. **必须先订阅再发布**：未设 `retained` 的消息不会补发给后来的订阅者。测试里踩过一次。
4. **`clean-session: false` 的含义**：broker 会保留订阅与离线消息，重连后可能收到**一批历史消息**。
   采集类场景通常要 `true`，除非确实要接住离线数据。
5. **MQTT 3.1.1 而非 5.0**：工业现场的 broker 与设备绝大多数只支持 3.1.1，
   而 5.0 专属能力（会话过期、共享订阅、原因码）在现场几乎用不上。选 3.1.1 是**兼容性优先**，
   需要 5.0 时再按需扩展。
6. **`#` 必须是过滤器的最后一层**：`a/#/c` 是非法过滤器，broker 会直接拒绝订阅。
7. **载荷不做自动 JSON 解析**：MQTT 载荷是**不透明字节**，协议层不做结构假设。
   多点位 JSON 载荷的拆分是宿主业务层的事（在 `DataSink` 或 `DataListener` 里按自身契约解析）。

---

## 许可

- 依赖 `com.hivemq:hivemq-mqtt-client`（**Apache-2.0**）。
- 测试用嵌入式 broker `io.moquette:moquette-broker`（**Apache-2.0**，仅 test scope）。

> 说明：选 Moquette 而非 Testcontainers 起真实 broker，是为了让测试在**没有 Docker 的机器上也能跑**。
> 代价是它只支持 MQTT 3.1.1 —— 这正是本模块把基线定在 3.1.1 的直接原因之一。
