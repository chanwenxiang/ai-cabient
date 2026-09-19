# O7-① `*IT.java` 没有任何入口 —— 修 + 证据（2026-09-19）

## 一句话

根 pom 此前**只有 surefire**，而 surefire 默认排除 `**/*IT.java`、CI 里也没人调 failsafe
⇒ `EmqxSharedSubscriptionIT`（Testcontainers + EMQX `$share` 只投递一次）**在任何入口都不执行**：
`mvn verify` 是 `BUILD SUCCESS`，日志里 0 次提及它，也没有 `target/failsafe-reports`。
**一个全绿的构建，从来没跑过那个用例。**

## 改了什么

| 文件 | 改动 |
|---|---|
| `pom.xml` | pluginManagement 加 `maven-failsafe-plugin:3.5.4`（`argLine=@{argLine}`，与 surefire 一致，让 Jacoco 生效）；`<plugins>` 里绑定 `integration-test` + `verify` |
| `.github/workflows/ci.yml` | 新增 `integration` job（`needs: build`）：`mvn -B verify -pl services/device-service -am` + `Verify integration tests ran (no silent skip)` |
| `scripts/devops/verify-ops-alert-channels-drift.py` | 本次不涉及；见另一份证据 |

`integration` job **不挂** postgres/redis：device-service 的单测是纯 Mockito，该 IT 只用 Testcontainers。
刻意**不**用 `-DskipTests` 去「只跑 IT」—— `skipTests` 会把 failsafe 一起跳过，那正是要防的静默跳过。

## A/B（同一条命令 `mvn -B clean verify -pl services/device-service -am`）

| | A 组（改前） | B 组（加 failsafe 后） |
|---|---|---|
| 日志里 `EmqxSharedSubscriptionIT` 出现次数 | **0** | **2** |
| 日志里 `failsafe` 出现次数 | **0** | 8 |
| IT 执行结果 | —— | `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 7.915 s` |
| `target/failsafe-reports` | 现场实测「不存在」 | 存在（`TEST-…EmqxSharedSubscriptionIT.xml`） |
| 结论 | `BUILD SUCCESS` `EXITCODE=0` | `BUILD SUCCESS` `EXITCODE=0` |

⇒ **A 组那行 `SUCCESS` 是「什么都没跑」的绿**；B 组才是「跑了并且过了」的绿。

## 守卫的判据为什么这么写

真值**只在 `<testcase>` 元素个数上**。同一份报告里：

| 读取源 | 值 |
|---|---|
| `*.txt` 的 `Tests run:` | `1`（这个 IT 没有 `@Nested`，尚可；但 `@Nested` 类会写成 `0`） |
| XML 根元素 `tests=` | `1`（同上，`@Nested` 类同样是 `0`） |
| `<testcase>` 元素个数 | `1` ← 判据用它 |

`disabledWithoutDocker = true` 这个注解让「无 Docker」变成**显式跳过**而不是静默丢失；
但 GH 托管 runner 有 Docker ⇒ 这里要求它**真跑**，一条都没跑就是红。

## 证据文件

| 文件 | 内容 |
|---|---|
| `ab-report.txt` | A/B 两组的日志读法（含「不能用现在看磁盘的读数当 A 组状态」的声明） |
| `ab-logs-tail.txt` | 两份原始 Maven 日志的尾部 |
| `guard-scenarios.txt` | 把 ci.yml 里那段 `run` **原样抽出**后跑的 5 个场景：真实=绿 / 缺报告=红 / 0 用例=红 / 注入 failure=红 / 复制成 2 条=绿，外加还原回绿 |
| `failsafe-reports-tree.txt` | failsafe 报告树、txt 报告、XML 根属性 vs `<testcase>` 计数 |

## 复现

```bash
mvn -B clean verify -pl services/device-service -am      # 需要 Docker（Testcontainers 拉 emqx/emqx:5.5）
node scripts/run-audit-gates.mjs                        # 聚合链
```

## 仍未做（另立项）

`edge/android-app` 的 23 个 `.kt` 仍**零测试**：本机无 `gradlew` / `local.properties` / `ANDROID_HOME`，
CI 也没有 edge job ⇒ 写 Kotlin 单测目前是不可运行的装饰。需要先补 Gradle 测试基建。
