# P0-7 证据：edge↔device↔trade 契约/集成测试 + CI 守卫升级

日期：2026-09-19（第十轮）
范围：O7 遗留测试资产还债 —— **P0-7 edge 契约/集成测试**，含 CI 守卫加固。

## 一句话结论

模拟器报文从「手抄 JSON」改为**复用生产构造器**做契约测试（10/10）；新增经**真 EMQX 容器**的端到端 IT（1/1）；二者与既有 IT 共 **2 条**在 `mvn verify` 下真跑（`BUILD SUCCESS`）。
过程中揪出并根治一个**假绿根因**：failsafe 跑在 `package` 之后，fork classpath 里本模块是 `spring-boot:repackage` 产出的 **fat jar** ⇒ 任何**引用本模块类**的 `*IT` 都在 discover 阶段 `ClassNotFoundException`，而**不引用**本模块类的旧 IT 侥幸能跑。
CI 的 `integration` job 守卫同步升级：从「只盯 `EmqxSharedSubscriptionIT` 单个报告文件」改为**双向扫**（全部报告 + 源码里全部 `*IT.java`），并修掉 `check-audit-gates-wiring` 里**早已失效的硬编码行号标注**。

## 证据清单

| 文件 | 内容 | 关键数字 |
| --- | --- | --- |
| `mvn-verify-fixed.txt` | 最终 `mvn verify -pl services/device-service -am`（PowerShell 原生 + `Out-File`） | surefire 23（simulator）+ 48（device-service）；failsafe **Tests run: 2**；`BUILD SUCCESS`；`EXITCODE=0` |
| `classpath-probe.txt` | 临时 `ClasspathProbeIT` 打印 fork JVM 的 `java.class.path` 并 `Class.forName` 探测 | 第 3 条 classpath 是 `device-service-…-SNAPSHOT.jar`（fat jar）；`TradeServiceClient`/`MqttEventListener` **MISSING**，兄弟模块类 `FOUND` |
| `ab-ci-guard.txt` | 新/旧 CI 守卫逐场景 A/B（沙箱，不碰真仓库） | 见下表 |
| `ab-wiring-rule5.txt` | `check-audit-gates-wiring` 新增规则 5 的 A/B（沙箱复制三件套） | 基线 0；删/改锚点 → 1 |
| `aggregate-gates.txt` | `node scripts/run-audit-gates.mjs` 全量 | **26 个门禁，失败 0 个** |
| `scripts/new-ci-guard.sh` | 改后守卫正文（从 ci.yml 抽出，避免手抄失真） | — |
| `scripts/old-ci-guard.sh` | 改前守卫正文（从 `git show HEAD:.github/workflows/ci.yml` 抽出） | — |
| `scripts/ab-ci-guard.sh` | A/B 驱动脚本（可重跑） | — |
| `scripts/ab-wiring-rule5.sh` | 规则 5 A/B 驱动脚本（可重跑） | — |
| `scripts/extract-guard.py` | 从 ci.yml 抽 `run:` 正文的小工具 | — |

## A/B 矩阵 ①：CI `integration` 守卫

沙箱结构：`services/device-service/{target/failsafe-reports,src/test/java/...IT.java}`。
判定：`rc=0` 绿 / `rc=1` 红。

| # | 注入的漂移 | 新守卫 | 旧守卫 |
| --- | --- | --- | --- |
| 基线 | 真实结构（2 IT + 2 报告） | 0 ✅ | 0 ✅ |
| ① | 新增 `BrandNewIT.java` 但**没有报告** | **1 ✅红** | **0 ❌漏检** |
| ② | 报告在但 `<testcase>` 数为 0（被 disabled / 静默跳过） | **1 ✅红** | **0 ❌漏检** |
| ③ | 报告里出现 `<failure>` | 1 ✅红 | 1 ✅红 |
| ④ | 一份报告都没有 | 1 ✅红 | 1 ✅红 |
| ⑤ | `edge/` 模块（`-am` 闭包内）的 IT 没被跑 | 1 ✅红 | — |
| 反假红 A | 本 job **未构建**的模块（`services/trade-service`）里存在 `*IT.java` | 0 ✅不误红 | — |
| 反假红 B | 已构建模块里 IT **改名**且报告同步改名 | 0 ✅不误红 | — |

关键点：①②是**旧判据恒绿的两种写法**（换个文件名就能绕过；或报告在但一条没跑），新判据把它们变成红；
反假红 A/B 证明新判据依赖的「在不在本 job 范围内」判据（`<module>/target/` 是否存在）不会误伤。

## A/B 矩阵 ②：`check-audit-gates-wiring` 规则 5（豁免锚点）

豁免名单（`LOCAL_ONLY`）此前用 `ci.yml:297 / :306 / :309 / :316` 标注「CI 里哪一步替代了它」——
这是**硬编码行号**，任何在它之前的插入都会让它悄悄过期。实测：`ci.yml` 当前第 297 行是新增守卫里的一句 shell 注释，
与 `pnpm check:audit-gates` 毫无关系 ⇒ 标注已在骗读者（且无任何门禁会发现）。
改法：锚点改用**命令串**，并新增规则 5 在**工作流原文**里逐条校验。

| # | 注入的漂移 | 结果 |
| --- | --- | --- |
| 基线 | 沙箱三件套 = 真仓库 | 0 ✅ |
| ① | CI 删掉 `pnpm check:nav-perms` | 1 ✅红（规则 4 + **规则 5** 同时报） |
| ② | CI 删掉 `pnpm check:audit-gates` | 1 ✅红 |
| ③ | `pnpm build:packages` 改名 | **1 ✅红，且只有规则 5 报** ⇒ 规则 5 非冗余 |

## 复现步骤

```bash
# 0) 前置：Docker 可用（IT 用 Testcontainers 起 emqx/emqx:5.5）

# 1) 跑真 IT（PowerShell 原生调用 Maven；bash 工具里 mvn 不可用）
#    mvn -B verify -pl services/device-service -am   → 期望 failsafe Tests run: 2 / BUILD SUCCESS

# 2) 抽守卫正文并用沙箱做 A/B（不影响工作区）
python docs/evidence/2026-09-19-p07-edge-contract/scripts/extract-guard.py   # 需在仓库根执行
bash   docs/evidence/2026-09-19-p07-edge-contract/scripts/ab-ci-guard.sh

# 3) 规则 5 A/B
bash   docs/evidence/2026-09-19-p07-edge-contract/scripts/ab-wiring-rule5.sh

# 4) 聚合门禁
node scripts/run-audit-gates.mjs      # 期望：26 个门禁，失败 0 个
```

## 已知 / 未做

- `doorEvent_reportTimestampIsIgnored_serverClockIsUsed` 固化的是一条**真差异**：报文里的 `timestamp` 被云端忽略（`MqttEventListener` 取 `System.currentTimeMillis()`）。断网补投时时序归因会失真，已留作已知差异，未在本批改行为。
- `SimulatorSupport.ackPayload` 由 `Map.of` 改 `LinkedHashMap`（有序、`commandId=null` 不再 NPE），是**宽松化**非破坏性变更；`DeviceSimulator` 行为逐字不变（simulator 23 条单测全绿）。
- `edge/android-app` 仍零测试（Kotlin/Gradle，另立项），真机端硬编码 topic/字段由静态门禁 `check:edge-cloud-mqtt-contract` 守护，不在本批。
- 本批只覆盖 device-service 一侧；`trade-service` 侧未加 IT（CI job 的 `-pl` 范围未变）。
