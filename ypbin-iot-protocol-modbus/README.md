# ypbin-iot-protocol-modbus

> Modbus TCP / RTU 协议适配器 —— 基于 `com.digitalpetri.modbus:2.1.6`（EPL-2.0、基于 Netty、异步 API）。

引入即自动装配。

---

## 快速开始

```xml
<dependency>
    <groupId>cn.ypbin</groupId>
    <artifactId>ypbin-iot-protocol-modbus</artifactId>
</dependency>
```

```yaml
ypbin:
  iot:
    protocol:
      modbus:
        enabled: true
        connect-timeout: 10s
        request-timeout: 5s
        keep-alive-interval: 30s    # 保活：检测对端静默断开
        default-unit-id: 1          # 设备未配 localAddress 时使用
```

承载方式由**端点 URI 的 scheme** 决定，一个协议 code 覆盖 TCP 与 RTU：

| 端点 | 承载 |
|---|---|
| `tcp://10.0.0.1:502` | Modbus TCP |
| `serial:///dev/ttyS0?baud=9600` | Modbus RTU（串口）|

---

## 能力

| 能力 | 说明 |
|---|---|
| `READ` | 按寄存器区分块读，自动遵守协议上限 |
| `WRITE` | 单寄存器 / 单线圈写 |
| `SUBSCRIBE_POLLING` | 由框架轮询实现订阅（Modbus 无推送能力）|
| `MULTI_DEVICE_LINK` | **一条链路承载多个从站**（unitId）|

- **不支持 `SUBSCRIBE_NATIVE`**：Modbus 协议没有推送机制，声称支持就是撒谎。
- 不声明 `BROWSE`：Modbus 无目录概念。

---

## 地址写法（重要）

**两种写法，语义固定，不做猜测**：

| 写法 | 含义 |
|---|---|
| `holding:0` / `coil:5` / `input:3` / `discrete:2` | **推荐**：类型显式 + 偏移 **0-based** |
| `40001` / `30001` / `10001` / `00001` | 传统 5 位编号，按区段换算（`40001` → holding 偏移 0）|

其余写法（如 `holding`、`holding:-1`、`50000`）**一律报错**并说明可用写法。

> **为什么不自动猜测**：各厂商文档对同一寄存器的写法从 `40001` 到 `0` 到 `holding:1` 不等，
> `1-based` 与 `0-based` 的混用是 Modbus 现场最常见的错误来源。猜错的表现是
> 「读到了值但值不对（整体偏移一位）」，比直接报错难排查得多。

---

## 行为约定

| 场景 | 行为 |
|---|---|
| 批量读跨越 125 寄存器 / 2000 线圈上限 | **自动分块**，多次请求后按调用方给出的地址顺序合并 |
| 某个点位地址拼错 | **只让该点位失败**（质量 `CONFIG_ERROR`），其余点位正常返回 |
| 某个分块读失败/超时 | **只让该分块内的点位失败**（质量 `BAD`），不让整次读异常完成 |
| 写入只读区（输入寄存器/离散输入） | 该写项**逐项失败**，其余写项照常执行 |
| 写入值类型不支持（如 Map） | 该写项逐项失败，原因 `iot.modbus.value.invalid` |
| 从站地址 > 247 | 绑定时 **fail-fast** |
| 从站地址缺省 | 用 `default-unit-id` |

> 地址拼错与分块失败刻意**不**让整批失败：点位表里一个错别字如果让整台设备的采集全灭，
> 故障半径远大于它本身的严重程度。

---

## 协议特有坑

1. **地址基准是头号坑**：读到值但整体偏移一位，几乎总是 `1-based`/`0-based` 混用。用 `type:offset` 显式写法可完全避免。
2. **一条 TCP 链路承载多从站**：`device.localAddress` 填 unitId。网关设备的 TCP 连接数上限通常只有 1~4，
   **务必让同一网关下的设备共享 `connectionId`**（框架会复用链路，不会为每个从站重复建连）。
3. **单次读上限是协议硬约束**：寄存器 125、线圈 2000。超限设备直接返回异常码，框架已自动分块。
4. **线圈是「一位一个线圈」的位打包字节**，不是布尔数组；适配器已按低位在前解位。
5. **寄存器是 16 位无符号**：`0xFFFF` 读出来是 `65535` 而不是 `-1`。需要 32 位（双寄存器）语义时由宿主侧组合。
6. **RTU 需要串口独占**：同一条串口上的请求不能并发（RS-485 半双工）。串口参数（`baud`）走端点查询串，
   **不做默认值猜测**——猜错的表现是「偶尔能通」。
7. **保活是必需的**：Modbus 没有心跳，对端静默断开不会产生任何回调。适配器用
   `keep-alive-interval` 周期检查链路，断开即通知注册中心触发重连。**不要把它配成 0**。

---

## 许可

- 本模块依赖 `com.digitalpetri.modbus`（**EPL-2.0**，弱 copyleft，以库形式链接无传染义务；**不得修改其源码后再分发**）。
- 串口路径经 `com.fazecast:jSerialComm`（**LGPL-3.0**，动态链接闭源分发可接受，已在许可证白名单登记）。
- 平台线程池用于承载 native 调用（串口），见 `AdapterContext.scheduler().platformThreadExecutor()`。
