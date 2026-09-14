# 贡献指南

## 一、开工前必读

1. `AGENTS.md`（本仓）——铁律与约定；
2. `docs/DESIGN.md`、`docs/SPI.md`、`docs/RUNTIME.md`、`docs/PROTOCOLS.md`——设计与契约；
3. 母仓 `ypbin-starter` 的 `AGENTS.md`——本仓与它共用一套规范。

## 二、硬性红线（违反即不予合并）

| 类别 | 要求 |
|---|---|
| 署名 | 每个类必须有 Apache 头 + 类级 Javadoc 的 `@author wenbin` + `@since <日期>`；禁 `@date`、禁版本号 |
| 内联全名 | Java 代码禁内联全限定类名，一律顶部 `import`（由 `SourceConventionTest` 拦截） |
| 静默降级 | 禁静默吞异常/静默兜底；未实现的组合必须 fail-fast |
| 协议线程 | **严禁在协议线程/IO 线程上执行宿主代码或阻塞**（I4） |
| 集合 | 返回集合的方法不得返回 `null`，一律 `List.of()`/`Map.of()`/`Set.of()`；禁用 `Collections.emptyXxx/singletonXxx` |
| 魔法值 | 业务判断禁硬编码裸值，抽常量或枚举 |
| 枚举 | 必须显式 `code` + `desc`；**禁 `ordinal()`** |
| 超时 | 所有网络/阻塞调用必须显式超时（含库内部超时） |
| 文案 | 代码与文档禁出现参考项目品牌词 |

## 三、本地验证（**本机是低配服务器，遵守以下约定**）

```bash
# 只跑单模块 + 其依赖，并限制堆内存
MAVEN_OPTS=-Xmx768m mvn -pl <模块> -am test

# 格式化（提交前必须）
MAVEN_OPTS=-Xmx768m mvn spotless:apply

# 提交时跳过全仓钩子
LEFTHOOK=0 git commit -m "..."
```

**不要在本机跑全量 `mvn clean install`、Testcontainers 集成测试或前端全量构建**——交给 CI。
一次只跑一个重型命令，绝不并发。

## 四、提交与合并

- 提交信息用中文，说明**做了什么 + 为什么**；不得添加 `Co-Authored-By`。
- **文档与实现必须一致**：文档里凡是「怎么做才能通」的具体命令/部署建议，必须有实测用例支撑，
  否则只能写成「未验证」。
- **声明某条路径「已实现」时，必须附带一条证明它可达的用例**（断言失败原因是该路径特有的，
  而不是被上游短路后的通用错误）。
- 新增门禁必须做**反向验证**（注入违规必须红，撤掉必须绿）。

## 五、新增协议模块

按 `docs/PROTOCOLS.md` 的清单逐项完成：SPI 三件套、能力声明、`probe` 失败原因、
显式超时、TCK 通过、协议特有坑归档、覆盖率达标、README 与设计文档同步。
