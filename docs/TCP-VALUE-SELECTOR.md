# TCP 透传 · 帧内字段选择（valueSelector）

> 状态：**已实现**（`0.2.1-SNAPSHOT`，2026-10-12）。
> 相关代码：`TcpPayloadFormat.KEY_VALUE`、`TcpValueSelector`、`TcpSession`。
> 契约入口：[`CONTRACT.md` 行为承诺 §四 第 8 条](../CONTRACT.md)、[`docs/SPI.md` P2 注解](./SPI.md)。

## 1. 解决什么问题

裸 TCP 透传此前只有三种载荷口径：`binary`（整帧 `byte[]`）、`text`（整帧字符串）、
`number`（整帧数值）。当设备一帧返回多个点位（典型形态 `TEMP=23.5,SEQ=12`）时，
这些口径只能把**整帧**交给一个 `PointValue`，无法按点位拆分；因此 `subscribe()` 对多地址
fail-fast，业务宿主必须保留一层过渡解码。

`payload-format=key-value` 把「一帧」变成「一组字段」，并允许一次订阅携带多个 selector：

```yaml
ypbin:
  iot:
    protocol:
      tcp:
        payload-format: key-value
```

```java
// 订阅 4 个点位：raw 是帧内字段键（selector）
session.subscribe(SubscribeRequest.of(List.of(
        PointAddress.of("TEMP"),
        PointAddress.of("HUMI"),
        PointAddress.of("SEQ"),
        PointAddress.of("BAT"))), listener);

// 设备回帧：一帧 4 个字段
// TEMP=23.5,HUMI=55,SEQ=12,BAT=90
```

## 2. 语义表

| 项 | 规则 |
|---|---|
| 启用方式 | `ypbin.iot.protocol.tcp.payload-format=key-value`（默认仍是 `binary`，向后兼容） |
| selector 来源 | `PointAddress.raw`；在 `key-value` 格式下它就是字段键 |
| 帧编码 | 严格 UTF-8；非法字节整帧判 `iot.tcp.payload.not-utf8`，不产出 U+FFFD 替换字符 |
| 字段分隔 | `,`、`;`、`\r`、`\n` |
| 键值分隔 | 字段内**第一个** `=`；无 `=` 或 `=` 前为空的字段跳过 |
| 键归一化 | 键与 selector 都做首尾去空白 + `Locale.ROOT` 小写；大小写不敏感 |
| 值归一化 | 只做首尾去空白，交付 `String`；**本层不做任何类型转换**（int/decimal/bool 等物模型类型由宿主映射） |
| 重复键 | **先出现者生效**（字段顺序抖动不改变取值） |
| 空字段值 | 该点位 BAD，原因 `iot.tcp.selector.value-empty` |
| selector 未命中 | 该点位 BAD，原因 `iot.tcp.selector.not-found`；同帧其他点位照常交付 |
| 整帧无任何合法字段 | 所有订阅点位 BAD，原因 `iot.tcp.payload.not-key-value` |
| 多地址订阅 | `key-value` 格式下允许 0..N 个 selector；其他格式仍保持「单点位流」多地址 fail-fast |
| selector 语法 | 不得包含 `=`、`,`、`;`、`\r`、`\n`；非法在**订阅期**以 `AddressParseException` fail-fast |

## 3. 失败矩阵

| 时机 | 条件 | 行为 | 消息键 / 异常 |
|---|---|---|---|
| 订阅期 | 非 `key-value` 格式一次订阅 >1 个地址 | 失败，不注册订阅 | `IllegalArgumentException`（`iot.tcp.subscribe.multi-address-unsupported`） |
| 订阅期 | 任一 selector 含 `=`/`,`/`;`/换行 | 失败，不注册订阅 | `AddressParseException`（`iot.common.address.parse-failed`） |
| 运行期 | 帧非合法 UTF-8 | 该订阅全部地址 BAD | `iot.tcp.payload.not-utf8` |
| 运行期 | 空帧 / 帧内没有 `KEY=VALUE` 字段 | 该订阅全部地址 BAD | `iot.tcp.payload.not-key-value` |
| 运行期 | selector 在帧内找不到 | 仅该地址 BAD | `iot.tcp.selector.not-found` |
| 运行期 | selector 命中但字段值为空白 | 仅该地址 BAD | `iot.tcp.selector.value-empty` |
| 运行期 | selector 命中且值非空 | 该地址 GOOD，值为 `String` | 无 |

**为什么未命中不整帧失败、也不静默丢弃**：帧内字段的部分缺失通常是设备侧单点异常，
而不是整帧不可用；让该点位以 BAD 质量显式可见，宿主既能在数据面看到失败、也能继续消费
同帧其他点位。整帧解析失败（非 UTF-8 / 无键值字段）才让全部点位 BAD。两种情况都**不产出
GOOD 的脏值**，符合「未支持能力一律 fail-fast / 不静默降级」的仓库纪律。

## 4. `raw_address` 的角色

- `payload-format=key-value`：`PointAddress.raw` = 帧内字段 selector。
- 其他格式（`binary`/`text`/`number`）：`PointAddress.raw` 仍是订阅标识 / 流地址，
  不参与帧内寻址（与 0.1.0 语义一致）。

这与 `docs/SPI.md` 的 P2 一致：`raw` 始终由协议适配器解释，core 不做统一地址抽象。

## 5. 明确不做（边界）

- 不做 JSONPath / XPath / 嵌套字段 / 数组索引：第一版只支持扁平 `KEY=VALUE`。
- 不做引号、转义、字段值内含分隔符的语法：字段值不能包含 `,`、`;`、`\r`、`\n`；
  值内含 `=` 可以，按第一个 `=` 切分。
- 不做类型转换：`int`/`decimal`/`bool`/`date_time` 等由宿主物模型层完成。
- 不支持每个点位配置不同分隔符；分隔符对一次订阅/一个会话统一。
- 不支持按 MQTT topic / OPC UA NodeId 这类多级 selector；本能力只落在 TCP 的
  `key-value` 载荷格式上。

## 6. 迁移指引

1. 宿主将 TCP `DeviceSpec`/订阅配置的 `payload-format` 从 `text` 改为 `key-value`。
2. 点位的 `raw_address` 保持为帧内键（如 `TEMP`），无需单独新增 selector 字段。
3. 原先需要在宿主过渡层做「按 `raw_address` 从整帧 text 里拆键」的逻辑可以删除；
   但物模型类型转换（`String` → `Long`/`BigDecimal`/`Boolean`）仍属于宿主职责。
4. 迁移期注意：`key-value` 下多地址订阅不再 fail-fast；如果点位键配错，运行期会收到
   该点位的 BAD（`iot.tcp.selector.not-found`），而不是静默无数据。
5. 需要整帧文本语义的旧点位保持 `payload-format=text`，两者不能混用；需要混用时应拆设备。

## 7. 测试清单

| 层级 | 测试 |
|---|---|
| 单元语义 | `TcpValueSelectorTest` VALUE-01~10：多点取值、大小写/空白、重复键先到先得、未命中、空值、空帧/非键值帧、非 UTF-8、selector 语法 |
| 会话行为 / 契约 | `TcpSessionBehaviourTest` TCP-12（一帧多点位）、TCP-13（未命中只 BAD 该点）、TCP-14（非法 selector 订阅期 fail-fast）、TCP-11（非 key-value 多地址仍 fail-fast） |
| 回归 | TCP-08/09/10 保持 `text`/`number`/`binary` 单点位行为逐字不变 |
