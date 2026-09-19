# P0-8 证据：依赖 Docker 的测试「必须真跑」—— build job 守卫升级 + 接线门禁

日期：2026-09-19（第十一轮）
范围：承接 P0-7 的 CI 守卫加固，**扫其它端副本**（`build` job 的两个守卫仍是硬编码白名单）。

## 一句话结论

仓库里带 `@Testcontainers(disabledWithoutDocker = true)` 的测试类共 **7** 个，其中 **2 个从来没被任何守卫覆盖过**
（`ReconciliationIntegrationTest`、`WeChatNotifyIntegrationTest`）—— 缺 Docker 时它们会被 JUnit **整个类禁用**，
surefire 照旧写出一份 **0 条用例**的报告，`mvn` 照样 SUCCESS，而没有任何门禁会发现。
`build` job 的守卫已从**硬编码白名单**改为**按源码自动发现**（`find … -name '*Test.java'` + 行首 `@Testcontainers`），
并新增静态门禁 `check:docker-tests-guarded`（进聚合链）钉住「那套自动发现还在不在」。

过程中还顺手定论了一条**假警报**：本地 13:35 有一批 `AdminE2ETest` / `ConsumerE2ETest` / `MerchantE2ETest` /
`ReconciliationIntegrationTest` 的**失败报告**（`NoClassDefFoundError: com/aicabinet/common/dto/VerifyUserRequest`）。
实跑复现 ⇒ **全绿**，结论是**并发构建**（另一个 `mvn` 的 `clean` 删掉了 `common-core/target/classes`，恰好卡在
trade-service 测试 fork 的窗口），**不是代码缺陷**。

## 证据清单

| 文件 | 内容 | 关键数字 |
| --- | --- | --- |
| `mvn-trade-verify.txt` | 实跑 `mvn verify -DskipITs -pl services/trade-service -am -Dtest=ReconciliationIntegrationTest,WeChatNotifyIntegrationTest` | 各 `Tests run: 1, Errors: 0`；`BUILD SUCCESS`；`EXITCODE=0` |
| `mvn-refresh-1.txt` | 刷新报告：`AdminE2ETest,ConsumerE2ETest` | `Tests run: 5` / `7`，全 0 失败；`BUILD SUCCESS` |
| `mvn-refresh-2.txt` | 刷新报告：`MerchantE2ETest,PermissionCodeDriftTest` | `Tests run: 11` / `2`，全 0 失败；`BUILD SUCCESS` |
| `ab-static-gate.txt` | 新门禁 `check-docker-tests-guarded.mjs` 的 A/B（junction 沙箱） | **9 例，0 例不符** |
| `ab-ci-guard.txt` | 升级后的 `build` job 守卫 A/B（真产物上注入漂移） | **9 例，0 例不符** |
| `aggregate-gates.txt` | `node scripts/run-audit-gates.mjs` 全量 | **27 个门禁，失败 0 个**（上轮 26 ⇒ 本轮 +1） |
| `scripts/ci-guard-build-job.sh` | 改后守卫正文（从 `ci.yml` 抽出，避免手抄失真） | — |
| `scripts/extract-guard.py` | 从 `ci.yml` 抽某 job 某步骤 `run:` 正文的小工具 | — |
| `scripts/ab-static-gate.py` | 门禁 A/B 驱动（可重跑） | — |
| `scripts/ab-ci-guard.py` | 守卫 A/B 驱动（可重跑） | — |

## 缺口取证（为什么必须改）

```
$ grep -rl "@Testcontainers" --include="*.java" services edge
services/device-service/.../EdgeCloudMqttIntegrationIT.java     ← integration job 守卫（自动扫 *IT.java）✅
services/device-service/.../EmqxSharedSubscriptionIT.java       ← 同上 ✅
services/trade-service/.../e2e/AdminE2ETest.java                ← build job 守卫①**白名单**里有 ✅
services/trade-service/.../e2e/ConsumerE2ETest.java             ← 同上 ✅
services/trade-service/.../e2e/MerchantE2ETest.java             ← 同上 ✅
services/trade-service/.../integration/ReconciliationIntegrationTest.java   ← 🔴 谁都没管
services/trade-service/.../integration/WeChatNotifyIntegrationTest.java     ← 🔴 谁都没管
```

改前的 `Verify RBAC E2E ran` 守卫是 `for cls in <4 个类名>` 的**手写名单**；`*Test.java` 侧的
`@Testcontainers` 类实际有 **5** 个 ⇒ 名单模式漏掉两个，且**新增一个类不会有任何东西提醒你**
（与「失效六形态」的①同源：没人调）。

## A/B 矩阵 ①：新门禁 `check-docker-tests-guarded.mjs`

沙箱 = `%TEMP%\p08-ab`（`scripts/`、`.github/` 为副本，`services/`、`edge/` 为 junction 指向真仓库）。

| # | 注入的漂移 | 期望 | 实测 |
| --- | --- | --- | --- |
| 基线 | 真仓库内容 | 绿 | `rc=0` ✅ |
| ① | 源码里有 `@Testcontainers` 但清单未登记（等价于「新类没接守卫」） | 红 | `rc=1` ✅ |
| ② | 清单登记了源码里已不存在的类（清单腐烂） | 红 | `rc=1` ✅ |
| ③ | `build` 守卫的命令里删掉 `-name '*Test.java'`（自动发现被破坏） | 红 | `rc=1` ✅ |
| ④ | `integration` 守卫的命令里删掉 `-name '*IT.java'` | 红 | `rc=1` ✅ |
| 对照③ | 同一输入，把门禁实现换成**不剥 shell 注释** | **假绿** | `rc=0`（假绿）✅ 证明剥注释必要 |
| 对照④ | 同上 | **假绿** | `rc=0`（假绿）✅ |
| 反假红 A | 注释里塞满锚点关键词、命令不动 | 绿 | `rc=0` ✅ |
| 反假红 B | 清单 `why` 文字里塞锚点关键词 | 绿 | `rc=0` ✅ |

> 🔴 锚点第一版用的是 **短关键词 `@Testcontainers`**，A/B 的「漂移③」**假绿**了 —— 因为守卫自己有两行
> `echo "…带 @Testcontainers 的 *Test.java 都没扫到…"`，删掉真正的扫描行之后**输出文案**照样命中。
> 这正是「判据要落在**真正起作用的那几句**上，而不是在整个文件里搜关键词」。最终锚点改为
> `find … -name '<模式>'` 这种**只可能出现在真命令里**的 shell 语法，`surefire-reports` / `failsafe-reports`
> 作为第二锚点；同时保留剥注释（防御性）。

## A/B 矩阵 ②：升级后的 `build` job 守卫

在**真产物**（`services/trade-service/target/surefire-reports`）上注入漂移，每例用完立刻还原。
判定：`rc=0` 绿 / `rc=1` 红。

| # | 注入的漂移 | 期望 | 实测 |
| --- | --- | --- | --- |
| 基线 | 6 份报告齐备（5/7/11/1/1 + 权限漂移 2） | 绿 | `rc=0` ✅ |
| ① | 某个 `@Testcontainers` 类的报告被删 | 红 | `rc=1` ✅ |
| ② | 报告在但 `<testcase>` 计数为 0（静默跳过） | 红 | `rc=1` ✅ |
| ③ | 报告里出现 `<failure>` | 红 | `rc=1` ✅ |
| ④ | `PermissionCodeDriftTest`（不带 `@Testcontainers`，单独钉住）报告被删 | 红 | `rc=1` ✅ |
| ⑤ | 同上，报告 0 条用例 | 红 | `rc=1` ✅ |
| ⑥ | 守卫自己的 `@Testcontainers` 筛选被改坏（护栏：零发现必须红） | 红 | `rc=1` ✅ |
| ⑦ | 整个 `surefire-reports` 目录消失 | 红 | `rc=1` ✅ |
| 反假红 | **非** `@Testcontainers` 的报告里注入 `<failure>` | 绿 | `rc=0` ✅（mvn 自己会红，守卫不越权） |

### 判据口径

* 真值只在 **`<testcase>` 元素个数**上（`*.txt` 的 `Tests run:` 与 XML 根元素的 `tests=` 在 `@Nested` 类上都会写 0）。
* 旧守卫用 `grep "Failures: 0, Errors: 0, Skipped: 0"` + `grep -qE "Tests run: [1-9]"` 读 **`.txt`** ⇒ 一并换成 XML。
* 扫描范围用 `<module>/target/` 是否存在判定 ⇒ **本 job 未构建的模块不误红**，且随 `-pl` 参数自动跟随。

## 复现步骤

```bash
# 0) 先让产物是新鲜的（守卫只看产物，不看代码）—— 否则基线可能是上一批实验的残留
mvn -B verify -DskipITs -pl services/trade-service -am \
  -Dtest=AdminE2ETest,ConsumerE2ETest,MerchantE2ETest,ReconciliationIntegrationTest,WeChatNotifyIntegrationTest,PermissionCodeDriftTest \
  -Dsurefire.failIfNoSpecifiedTests=false

# 1) 静态门禁（不需要产物）
node scripts/check-docker-tests-guarded.mjs

# 2) 守卫正文从 ci.yml 抽出后跑（不要在 ci.yml 里手抄）
python .tmp/p08-extract-guard.py build "Verify Docker-backed tests ran" .tmp/p08-guard.sh
bash .tmp/p08-guard.sh

# 3) 两个 A/B 矩阵
python -u .tmp/p08-ab.py          # 静态门禁（需要先建 %TEMP%\p08-ab 沙箱）
python -u .tmp/p08-guard-ab.py    # CI 守卫（需要新鲜产物）
```

### 🔴 复现时的两个坑（都真实踩过）

1. **必须用 Git Bash 且 `PATH` 前置 Git 的 `usr/bin`**。
   直接 `shutil.which('bash')` 会命中 PortableGit/WSL 的 bash，其 `PATH` 里 `find` 解析到
   `C:\Windows\System32\find.exe`（Windows 的**文本搜索**工具，会等 stdin）⇒ 守卫**静默「零发现」并挂起 ~64 秒**，
   整批 A/B 结论全假。A/B 驱动脚本里已固定 `D:\devTools\Git\bin\bash.exe` + `PATH` 前置 `D:\devTools\Git\usr\bin`。
2. **实验对象一律用临时副本**。
   早前一版 A/B 直接改 `.tmp/p08-guard.sh`，进程被 SIGTERM 时 `finally` 未执行，
   留下一个**锚点被改坏**的脚本 ⇒ 后续所有结论作废（同一事件还污染了一份 surefire 报告）。
   现在的驱动脚本用 `contextlib` 上下文管理器 + 「基线不绿即 `exit 2` 中止」的前置闸。

## 边界 / 未做

* `trade-service` 侧的 `*IT.java`：目前为空（该服务的集成测试走 `*Test.java` + Testcontainers，由 `build` job 的 surefire 跑）。
* `edge/android-app` 仍零测试（Gradle，另立项）；`edge/device-simulator` 的 `@Testcontainers` 类数为 0。
* 守卫只在 CI 里消费产物；本地跑需要先构建（见复现步骤 0）。
