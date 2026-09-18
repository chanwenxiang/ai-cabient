# `edge/` 第一批单元测试 —— device-simulator 纯函数抽取与 23 条断言

**日期**：2026-09-19 · **模块**：`edge/device-simulator` · **状态**：23/23 绿，注入漂移可红，还原字节一致

---

## 1. 背景：先纠正一个过期口径

旧记忆里写「`edge/` 零测试源码（22 `.kt` / 测试 0；但 `edge/device-simulator/target/` 存在 ⇒
是根 pom 的 Maven 模块、`mvn test` **有真实落点**）」—— **把两个子项目混成了一个**。本次取证结果：

| 子项目 | 语言 | 构建体系 | 在 Maven reactor 里？ | 测试资产 |
|---|---|---|---|---|
| `edge/android-app` | Kotlin（**23** 个 `.kt`） | Gradle / Android | **否**（`mvn test` 永远不碰它） | 0 |
| `edge/device-simulator` | **Java**（`DeviceSimulator.java` 845 行） | Maven（根 pom `<module>`） | **是** | 0 → **本批新增** |

⇒ 正确的判断是：`mvn test` 对 `edge/` 的**唯一落点是 device-simulator**，而它此前 `src/test` 为空。
android-app 要测需引入 Gradle 测试基建，属**单独立项**，不在本批范围。

## 2. 为什么此前「测不了」

`DeviceSimulator` 是一个 845 行的上帝类：构造即连 MQTT broker、内含 HTTP 服务与上传编排，
**单测里无法实例化**。但其内部有一批**不碰网络/时钟/文件系统**的纯逻辑，
只是被 `private static` 关在类里 ⇒ 不可测。

## 3. 做法：抽纯函数，**行为逐字不变**

新增 `edge/device-simulator/src/main/java/com/aicabinet/simulator/SimulatorSupport.java`，
把 4 类纯逻辑搬进去：

| 方法 | 原位置 | 抽取后的签名变化 |
|---|---|---|
| `env(Map, key, default)` | `DeviceSimulator.env(key, default)`（直接读 `System.getenv`） | 注入 `Map` 作为环境来源 ⇒ 可测 |
| `parseDoubleOrDefault(value, default)` | `parseDoubleEnv(key, default)` | 拆成「取值」与「解析」两步 |
| `extension(Path)` | 同名 private static | 纯搬 |
| `contentType(String)` | 同名 private static | 纯搬 |
| `rewriteUploadUrl(url, internalEndpoint)` | `resolveUploadUrl(url)`（内部读 env） | 把 `MINIO_ENDPOINT` 变成参数 |

`DeviceSimulator` 侧只留薄封装（**调用点一处未改**）：
`env` 委托注入 `System.getenv()`；`resolveUploadUrl` 委托并传 `env("MINIO_ENDPOINT", null)`。
`import java.util.Locale` 随之删除（抽取后不再使用）。

**约束**：本批是「让不可测变可测」，**不是**「顺手改行为」。所有既有边界行为一律保留并写成断言。

## 4. 测试内容（23 条）

`edge/device-simulator/src/test/java/com/aicabinet/simulator/SimulatorSupportTest.java`，按 `@Nested` 分 5 组：

| 组 | 条数 | 覆盖重点 |
|---|---|---|
| `env` | 5 | trim、空串/全空白回落、`source`/`key` 为 null 不抛 NPE、默认值允许为 null |
| `parseDoubleOrDefault` | 3 | 合法数字（含负数/小数）、非数字与空串回落、null 回落 |
| `extension` | 4 | 普通扩展名、多段点号取最后一段、无点回落 `.bin`、**点开头文件名返回整个名字**（固化） |
| `contentType` | 4 | 常见类型、`jpeg`≡`jpg`、大小写不敏感、未知回落 octet-stream |
| `rewriteUploadUrl` | 7 | 未注入端点原样返回、重写 host/port 且**签名 query 一字不动**、尾随斜杠、默认端口、保留 userInfo、URL 不可解析原样返回（固化）、端点不可解析原样返回（固化） |

依赖：`org.junit.jupiter:junit-jupiter`（`test` scope，**版本由根 pom 的 `spring-boot-dependencies` BOM 管**，
不写版本号，与 trade-service 同一套 JUnit 5）。**刻意不引 `spring-boot-starter-test`** —— 本模块不依赖 Spring。

## 5. 验证：三轮 + 字节级还原

`three-round-report.txt` + 三份完整日志：

| 轮次 | 退出码 | 结果 |
|---|---|---|
| 1-normal | **0** | `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS |
| 2-drift（注入漂移：默认端口 `80` → `9999`） | **1** | `Tests run: 23, Failures: 1` → **`SimulatorSupportTest.fillsDefaultPort`** |
| 3-restored | **0** | `Tests run: 23, Failures: 0, Errors: 0, Skipped: 0` → BUILD SUCCESS |

- 漂移注入用的是 `Replace` 字面量替换（`port = ... ? 443 : 80;` → `... : 9999;`），
  并校验「替换确实生效」（未生效会记为 `INJECT-FAILED`，不会伪装成通过）。
- **还原采用字节级写回**（先 `ReadAllBytes` 备份、后 `WriteAllBytes` 覆盖），
  报告里 `restore byte-identical: True (2E2EBAC314CE)` 是 SHA256 前 12 位比对结果。

⇒ 判据既能绿、也能红，且红得**指向具体测试**（不是「某个地方坏了」）。

## 6. ⚠️ 一个易误读的信号（本次差点被它骗到）

surefire 输出里有这么一行容易被读成「测试没跑」：

```
[INFO] Tests run: 0, Failures: 0, Errors: 0, Skipped: 0 ... -- in com.aicabinet.simulator.SimulatorSupportTest
```

它统计的是**顶层类自身的直接测试数**；本批所有用例都在 `@Nested` 内部类里，
所以顶层是 0、而**总计 23**。判「跑没跑」要看紧邻的 `Results:` 汇总行（`Tests run: 23`），
不能看这一行。（第一次看到 `Tests run: 0` + `BUILD FAILURE` 时曾误判为「测试根本没执行」。）

## 7. 🔴 这份证据**没有**证明什么

- **没有**覆盖 `DeviceSimulator` 本身：MQTT 连接、购物流程编排、HTTP 控制端点、上传重试等
  **全部仍未测试**。本批只覆盖了被抽出的 5 个纯函数 ⇒ 模块整体测试覆盖率仍然很低。
- **没有**测 `uploadViaPresign` 的实际上传路径 —— `rewriteUploadUrl` 是其中的纯逻辑片段，
  二者不等价。
- **没有**跑全量 `mvn test` 回归：只跑了 `-pl edge/device-simulator -am`，
  trade-service/device-service 的测试**未在本批重跑**。
- **没有**给 `edge/android-app` 增加任何测试（Gradle 项目，未纳入）。
- 漂移注入只做了**一处**（默认端口），其它 22 条断言的「可红性」是**按同一机制推定**的，
  未逐条注入验证。

## 8. 文件清单

| 文件 | 内容 |
|---|---|
| `three-round-report.txt` | 三轮结构与还原校验摘要（含 SHA256 前 12 位） |
| `round1-normal.txt` | 第 1 轮：正常跑，23/23 绿，BUILD SUCCESS |
| `round2-injected-drift.txt` | 第 2 轮：注入漂移后的完整 Maven 输出（含精确失败断言与调用栈） |
| `round3-restored.txt` | 第 3 轮：字节级还原后回到 23/23 绿 |
