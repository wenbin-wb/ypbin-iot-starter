# ypbin-iot-protocol-tcp

> 通用 TCP 透传协议适配器 —— **M0 骨架的验收载体**。
>
> 引入即自动装配，零配置可用。

---

## 它解决什么问题

任何「能以字节流收发的设备」，只要没有专用协议适配器，都可以通过本模块接入：
上行数据经订阅推送、下行指令按字节发送。它是**兜底能力**，不是某个具体设备的通信方案。

> **它是 M0 的验收载体**：用于验证异步契约、连接生命周期、微批出口、TCK 与架构门禁是否成立。
> 后续 20 个协议模块都会复用这同一套骨架。

---

## 快速开始

```xml
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-tcp</artifactId>
</dependency>
```

```yaml
ypbin:
  iot:
    protocol:
      tcp:
        enabled: true
        framing-mode: LENGTH_FIELD   # NONE / LENGTH_FIELD / DELIMITER
        max-frame-length: 65536      # 单帧上限（安全边界，必须设）
        length-field-length: 2
        idle-interval: 30s           # 空闲检测，0 表示不启用
```

---

## 能力声明（刻意的窄口径）

| 能力 | 是否支持 | 语义 |
|---|---|---|
| `WRITE` | ✅ | 把值编码为字节后发送 |
| `SUBSCRIBE_STREAM` | ✅ | 收到字节帧后组装点位值推送 |
| `READ` | ❌ | **有意不支持** |

**为什么不支持 `read`**：裸 TCP 没有请求-响应语义，无法定义「读一次返回什么」。
把 `read` 实现成「取缓冲区里的帧」会引入不可预测的时序语义。
调用 `read` 会抛 `UnsupportedCapabilityException`（**fail-fast，不返回空结果**）。

需要请求-响应语义的协议（Modbus、SNMP、S7）应实现各自的适配器。

---

## 载荷编码规则

写入值的支持类型：

| 输入 | 编码方式 |
|---|---|
| `byte[]` | 原样发送 |
| `String` | UTF-8 编码 |
| `String` 以 `hex:` 开头 | 十六进制解析（允许空格，如 `hex:01 0A FF`） |

**其余类型一律失败**（返回 `PointWriteStatus.fail` 并给出消息键 `iot.tcp.payload.unsupported`）。

> **刻意不做隐式转换**：数字类型**不会**被「猜测字节序」后发送。
> 工业透传场景下隐式字节序转换会静默产生错误数据，比显式失败危险得多。

十六进制格式非法时返回 `iot.tcp.payload.hex-invalid`。

---

## 数据流向

```
设备 ──字节帧──► Netty pipeline ──► NettyChannelConnection ──► TcpSession
                                                                    │
                              ┌───────────────────────────┴───────────────────────────┐
                              ▼                                                       ▼
                    传入了 listener → 回调它                              未传 listener → 经 egress
                    （不再走 egress，避免重复投递）                        （微批出口 → DataSink）
```

订阅得到的点位值：`address` = 订阅时给出的第一个地址；`value` = `byte[]`；`quality` = `GOOD`。

---

## 协议特有坑

1. **粘包/半包必须靠 `framing-mode` 解决**，不要指望上层自己切分。
   不定界（`NONE`）只适合极短报文，否则会收到「半条报文」。
2. **`max-frame-length` 是安全边界，不是可选优化**：长度字段来自对端，
   不设上限意味着一个错误或恶意的长度值就能把进程打 OOM。
3. 链路是 **1:1** 的（一条链路一个设备）。设备的 `localAddress` 在透传场景下不参与寻址。
4. 对端静默断线依赖 `idle-interval` 与 `SO_KEEPALIVE` 兜底；
   不配 `idle-interval` 时，半开连接可能长时间不被发现。
5. 会话在 `bind` 阶段才创建（**会话延迟绑定**），因此 `open` 之后的链路对象
   直接调 `session()` 会抛 `IllegalStateException` —— 这是刻意的，避免返回 null。

---

## 许可

本模块无第三方协议库依赖（仅 Netty，Apache-2.0），无 copyleft 约束。
