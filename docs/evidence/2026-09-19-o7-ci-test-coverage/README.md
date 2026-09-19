# O7 测试资产还债 · CI 真正跑起 device-service / device-simulator

日期：2026-09-19（竞品路线图 §6 · O7 的第一批落地）
改动文件：`.github/workflows/ci.yml`（唯一改动，38 insertions / 2 deletions）

## 一、根因（改前实测）

| 事实 | 证据 |
|---|---|
| `device-service` 在 CI 里**只编译、不测试** | 改前 `ci.yml:187`：`run: mvn compile -pl services/device-service -am` |
| 该模块有 **6 个测试类、38 条用例**却从不执行 | `InternalApiAuthInterceptorTest` 6 / `TradeServiceClientRetryTest` 4 / `DoorEventDeduplicatorTest` 5 / `MqttEventListenerDoorTest` 14 / `DeviceCommandServiceTest` 2 / `DeviceCommandTrackerTest` 7 |
| `edge/device-simulator`（根 pom 第 4 个 module）**完全不在 CI 里** | `pom.xml:14-17` 四个 module：`common-core` / `trade-service` / `device-service` / `edge/device-simulator`；改前 ci.yml 只在根 pom 的 `-am` 里被动带过 |
| 根 pom 只有 surefire、**无 failsafe** | `EmqxSharedSubscriptionIT`（`@Testcontainers`）连本机 `mvn test` 都不跑 —— **本批未处理，见第四节** |

## 二、改动

1. `Build (device-service)` → **`Test (device-service + device-simulator)`**
   `mvn test -pl services/device-service,edge/device-simulator -am`
   （`-am` 只带出 `common-core`；`trade-service` 不是二者的上游，不会重跑）
2. 新增 **`Verify device tests ran (no silent skip)`** 守卫。

### 守卫的关键设计：判 `<testcase>` 元素个数，不判 `.txt`

这是一处**必须避开、否则判据恒真**的陷阱：

| 读取源 | `SimulatorSupportTest`（用例全在 5 个 `@Nested` 里） |
|---|---|
| `*.txt` 报告 | **`Tests run: 0`** |
| `TEST-*.xml` 根元素 | **`tests="0"`** |
| `<testcase>` 元素个数 | **23** ← 唯一真值 |

⇒ 沿用既有 RBAC 那条「判断 `Failures: 0, Errors: 0, Skipped: 0`」的写法，
在「**一条用例都没跑**」时同样命中、照样判绿。本守卫改判 **`<testcase>` 元素个数 ≥ 基线**（缺失 / 低于基线 / 出现
`<failure|<error|<skipped` 三者任一即红）。

3. 顺手补掉**既有 RBAC 守卫**的同一漏洞：在原有绿判定之后追加
   `grep -qE "Tests run: [1-9][0-9]*"` —— 0 条用例的报告同样写着
   `Failures: 0, Errors: 0, Skipped: 0`，单靠原有那一行会假绿。
   （该 4 个类实测分别有 2 / 6 / 8 / 13 个 `@Test`，且都不用 `@Nested`，故 `.txt` 的 `Tests run` 对它们可信，加这一行不会假红。）

## 三、验证

- `guard-scenarios.txt` —— 把 `ci.yml` 里那段 `run` **原样抽出**（不手抄）在隔离沙箱跑 5 个场景：

  | 场景 | 期望 | 实测 |
  |---|---|---|
  | S1 真实报告 | 绿 | `exit=0`，7 个类计数 6/4/5/14/2/7/23 全对 |
  | S2 某测试类报告缺失 | 红 | `exit=1`，`missing surefire report: …MqttEventListenerDoorTest.xml` |
  | S3 `SimulatorSupportTest` 变成 0 用例（`@Nested` 陷阱本体） | 红 | `exit=1`，`only 0 testcases … (baseline >= 23)` |
  | S4 注入 `<failure>` | 红 | `exit=1`，`contains failure/error/skipped` |
  | S5 用例数低于基线（14 → 10） | 红 | `exit=1`，`only 10 testcases … (baseline >= 14)` |
  | 对照：同一份「0 用例」文本报告 | —— | 旧式断言**命中⇒判绿（假绿）**；新式断言**未命中⇒判红** |
- `ci-diff.txt` —— 本次改动全量 diff（60 行）。
- `gates.txt` —— `node scripts/run-audit-gates.mjs` ⇒ **23/23，失败 0，EXITCODE=0**。
- YAML 合法性：用 PyYAML 解析通过；`build` job 步骤顺序确认 device 测试排在 trade-service 之后。
- 行尾：`.github/**` 未被 `.gitattributes` 声明 `eol=lf`，且本机 `core.autocrlf=true`
  ⇒ `git ls-files --eol` 显示 `i/lf w/crlf` 是**正常检出态**，提交 blob 为 LF（`git diff` 与 `git diff --ignore-cr-at-eol` 同结果）。

## 四、遗留（本批未做）

1. **根 pom 无 failsafe** ⇒ `*IT.java`（如 `EmqxSharedSubscriptionIT`）在任何入口都不执行。
   需要新增 failsafe 插件 + 独立 IT job（Testcontainers 依赖 Docker；trade-service 侧已用 `-DskipITs` 刻意跳过）。
2. `edge/android-app` 仍零测试（无 `gradlew` / `local.properties` / `ANDROID_HOME`，CI 无 edge job）⇒ 另立项。
3. 本守卫的基线数字（6/4/5/14/2/7/23）是**当前实测值**；日后**新增**用例不会触发红（判据是 `-ge`），
   **删除**用例会红并需同步更新基线 —— 这是刻意设计（用例减少必须显式过堂）。

## 五、复现

```bash
# 本机真实报告（已存在 target/surefire-reports 时）
mvn -B clean test -pl services/device-service,edge/device-simulator -am

# 抽出守卫脚本并跑（沙箱路径务必用 C:/… 形式；用 /c/… 会被 Windows 程序解析成 C:\c\…）
python -c "import yaml;d=yaml.safe_load(open('.github/workflows/ci.yml',encoding='utf-8'));print(next(s['run'] for s in d['jobs']['build']['steps'] if s.get('name','').startswith('Verify device tests')))" > /tmp/guard.sh
```

> ⚠️ 本目录 `guard-scenarios.txt` 的第一版曾因**沙箱路径写成 POSIX 形式`/c/…`** 而整份作废（Windows 程序把它解析成 `C:\c\…`，bash 找不到目录，5 个场景全部「目录不存在」）。
> 同一坑此前已记录在跨项目记忆里：**bash 调 Windows 程序时路径必须写 `C:/…`**。若日后重跑本验证，先核对沙箱目录确实被创建。
