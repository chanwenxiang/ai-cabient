# 证据：行尾假改动根除 + 告警投递重试 + 运营台实机（2026-09-18）

本目录是这三件事的**原始命令输出级证据**，不是结论摘要。
审计纪律要求判据可复核，故保留可复现的输入与输出。

---

## ① 14 个「纯行尾假改动」——根因是磁盘 CRLF、不是 git 抽风

### 现象与判据

`git status` 报 10 个 ` M`，但三种 diff 视角**全为空**：

| 命令 | 输出 |
|---|---|
| `git diff --stat` | 空 |
| `git diff --ignore-cr-at-eol --stat` | 空 |
| `git diff --no-textconv --text --stat` | 空 |

逐文件做**字节级三元对比**后定位真因：磁盘 100% CRLF（0 个孤立 LF）、索引 blob **纯 LF**、
归一化（`\r\n`→`\n`）后 **md5 与索引 blob 完全相同** ⇒ 内容没变，变的只有行尾。

根因是两套互相冲突的配置：`core.autocrlf=true`（检出方向 LF→CRLF）
与 `.gitattributes` 的 `eol=lf`（要求磁盘留 LF）。冲突的结果就是「磁盘 CRLF、索引 LF」。

**为什么必须修，而不只是 `git add` 糊过去**：该文件集是 vite/rollup 的**构建输入**
（产物文件名带内容哈希），磁盘 CRLF 会让本地产物与 CI 产物哈希不同 ⇒ 产物门禁假红
（`.gitattributes` 里记着 2026-09-16 一次实测：124 个输入是 CRLF，导致 78 个 chunk 哈希不一致）。
而且 `git add` 只刷新 stat，**磁盘仍是 CRLF**，任何一次工具改写都会让 ` M` 再次出现。

### 处置

把磁盘**改写回 LF**（`.gitattributes` 规定的状态）。改写前逐文件预检：
归一化字节必须**等于**索引 blob（不等即中止），且文件内**不得**有孤立 `\r`（会被误改）。
改写后逐文件回验 `disk == index blob` 全部 MATCH。

覆盖面：`git status` 只抓到 10 个，另有 **3 个同族文件因索引 stat 缓存恰好命中而被隐藏**
（git 跳过内容比对 ⇒ 既不报 ` M`、也不发 CRLF 警告）。按「修一类缺陷要扫全部副本」一并处理，
另**新发现第 14 个**（见下）。

| 文件 | 磁盘 CRLF（改前） | 改后 |
|---|---:|---:|
| `clients/admin-vue/public/favicon.svg` | 7 | 0 |
| `clients/admin-vue/src/components/ChartPanel.vue` | 34 | 0 |
| `clients/admin-vue/src/config/feature-flags.ts` | 4 | 0 |
| `clients/consumer-mp/README.md` | 41 | 0 |
| `clients/consumer-mp/scripts/gen-tab-icons.py` | 74 | 0 |
| `clients/consumer-mp/src/config/api.ts` | 1 | 0 |
| `clients/consumer-mp/tests/imp-dispute-copy-uat.mjs` ← 被 stat 缓存隐藏 | 247 | 0 |
| `clients/merchant-mp/scripts/gen-tab-icons.py` | 79 | 0 |
| `packages/shared-api/tsconfig.json` | 13 | 0 |
| `packages/shared-dict/dist/dict-options.spec.d.ts` ← 被隐藏 | 1 | 0 |
| `packages/shared-dict/dist/dict-options.spec.js` ← 被隐藏 | 24 | 0 |
| `packages/shared-dict/package.json` | 21 | 0 |
| `packages/shared-dict/tsconfig.json` | 13 | 0 |
| `services/trade-service/.../static/admin/favicon.svg` ← 新门禁抓到的第 14 个 | 7 | 0 |

### 第 14 个：产物 favicon.svg（`git status` 完全没提它）

新增的行尾门禁第一次运行就红在它身上 —— `i/lf w/crlf attr/text=auto eol=lf`。
字节对比证明它是**源文件的陈旧副本**：

```
artifact.replace(b"\r\n", b"\n") == source   →  True        # 只差行尾
source   399B crlf=0 lf=7
artifact 406B crlf=7 lf=0
```

`public/` 下的文件由 vite **逐字节**拷贝进产物目录，源文件已归 LF，故产物也必须是 LF
（否则重建一次就会产生 ` M`）。改后 `git hash-object --path` 与索引 blob 同为 `c43830f8…`。

### 结果

- `git status --porcelain` 只剩**真正的内容改动**（不再有行尾噪音）。
- `.gitattributes` 声明的 `eol=lf` 文本文件（收敛后 **667 个**），索引与磁盘**全部为 LF**。

---

## ② 告警投递重试：只重试瞬时故障，且重试粒度是「单渠道」

### 修复前

`OpsAlertDispatcher` 每条分发路径各自 `try/catch → log.warn`，**一次抖动丢一条告警**；
而告警正是从支付回调、定时任务这类关键线程发出的。

### 改法（判据落在「有效值」上）

- **重试粒度 = 单渠道**，不是整个 `send()` —— 后者会让**已经收到告警的渠道重复收一遍**。
- **只重试瞬时故障**：连接失败 / 读写超时（`ResourceAccessException`）、5xx（`HttpServerErrorException`）、429。
- **业务码拒绝不重试**（飞书 `19024` 关键词不匹配等）：那是**配置问题，重试不会自愈**，
  重试只会把一次配置错误放大成 N 次无用请求。
- 有界：`MAX_ATTEMPTS = 3`，指数退避 200ms → 400ms；线程被中断则恢复中断位并放弃剩余尝试。
- 投递语义为 **at-least-once**：首次已被处理但响应丢失时会产生重复告警 —— 告警场景下
  「重复」优于「丢失」，已在 Javadoc 中显式写明。
- 补了**显式超时**（connect 3s / read 5s）。Boot 自动配置的 `RestClient.Builder` 默认**无读超时**，
  目标机器人半死（建连成功但不回包）会**永久占住**发送线程，重试根本等不到触发时机。
- 把三条路径（`send` / `trySend` / `probeChannels`）收敛到**唯一投递原语** `deliver()`，
  日志只在 `tryPost()` 一处输出（避免「两处一起写错」）。

### A/B 对照（改回「无重试」→ 必红）

把 `MAX_ATTEMPTS` 临时改成 1（＝模拟修复前）重跑，**30 个用例红 6 个，且恰好是 6 个重试用例**：

| 用例 | 红的表现 |
|---|---|
| `maxAttemptsGuard_shouldStayAtExpectedDepth` | `expected: <3> but was: <1>` |
| `trySend_shouldRetryTransientTransportErrorThenSucceed` | `expected: <true> but was: <false>` |
| `trySend_shouldStopAfterMaxAttemptsWhenTransportKeepsFailing` | `TooFewActualInvocations` |
| `trySend_shouldRetryServerError` | `TooFewActualInvocations` |
| `send_shouldRetryTransportErrorWithoutThrowing` | `TooFewActualInvocations` |
| `probeChannels_shouldRetryTransientErrorThenReportDelivered` | `expected: <true> but was: <false>` |

**关键**：两条**反向守卫**用例在 A 组里**保持绿**：
`trySend_shouldNotRetryPlatformBusinessRejection`、`trySend_shouldNotRetryDeterministicClientError`
⇒ 判据能区分「重试开了」与「重试关了」，不是整片乱红。

还原为 3 后：`Tests run: 30, Failures: 0, Errors: 0` → `BUILD SUCCESS`。

> 顺带修掉一个**自带恒真**的断言：原先写 `times(OpsAlertDispatcher.MAX_ATTEMPTS)`，
> 把常量调成 1 时用例照样全绿。改为独立期望值 `EXPECTED_ATTEMPTS = 3`，
> 并加「守卫用例」——常量一改必然红，逼一次显式决策。

---

## ③ 运营台实机（真实浏览器，此前判据全在 HTTP 层）

`clients/admin-vue/tests/admin-alert-channel-uat.mjs` —— 真实 Chrome 登录运营台 → 渲染 `/alert-rules`
→ 点「测试发送」，取回**每个渠道的真实投递结果**（含平台业务码），并截图存证。

```
✓ AC-01 登录运营台 — token 已取得
✓ AC-02 告警规则页渲染
✓ AC-03 「测试发送」按钮存在（= 有编辑权限） — count=1
✓ AC-04 飞书 Webhook 配置键在列表中 — 找到 ops.alert.feishu_webhook
✓ AC-05 「测试发送」返回真实投递结果 — 测试发送：全部成功 :: FEISHU：已投递
✓ AC-06 控制台无阻断性错误 — clean
6/6 PASS
```

页面截图可见：`告警渠道` 分组下 `ops.alert.feishu_webhook`（当前值 `https://open.feishu.c…`）
与 `ops.alert.feishu_sign_secret`（暂无），弹窗标题「测试发送：全部成功」、正文「FEISHU：已投递」。

> ⚠️ 踩坑记录：弹窗是 `fixed` 定位，`fullPage: true` 截图会把它渲染到视口之外而**拍不到** ——
> 第一版证据是一张没有弹窗的图，等于没有证据。改用视口截图后重采。
>
> ⚠️ 本脚本对所有输出做 URL 脱敏（`/hook/***`、`access_token=***`、`&key=***`）；
> token 只存在于 `infra/.env`，不进任何日志或证据文件。

---

## ④ 新增门禁 `check:line-endings` + 漂移验证

这一类缺陷已出现两次，故补一条门禁（`scripts/check-line-endings.mjs`，接在聚合链**第 2 位**）：

- 判据用 `git ls-files --eol --cached --others --exclude-standard` 取「索引行尾 / 磁盘行尾 / 生效属性」，
  **判有效值**：属性含 `eol=lf` 的文本文件，索引与磁盘都必须是 LF。
- 带 `--others` 是刻意的：默认只列已跟踪文件，而「**新建**文件一落盘就是 CRLF」在 `git status` 里
  显示为 `??`（不是 ` M`），谁都不会觉得有问题，提交后才变成永久噪音。
- **不越界管辖**：`services/**` 的 `.java` 在 `core.autocrlf=true` 下磁盘本来就是 CRLF 且 `attr/` 为空，
  门禁必须忽略（否则在几百个正常文件上恒红）；`clients/**` 下 118 个 png/jpg 虽带 `eol=lf` 属性，
  但 `text=auto` 把它们识别为 `w/-text`，同样不判 —— 这正是 `.gitattributes` 用 `text=auto`
  而不是 `text` 的原因。
- **防恒真**：受控文件总数、声明 `eol=lf` 的文件数低于下限即红（锚点漂了＝失去判别力）。
- 带 `--fix` 修复路径（只替换 `\r\n`，latin1 逐字节往返，不动其它字节）。
- 接进 `check:audit-gates` 后 `check:audit-gates-wiring` 仍绿（22 个引用全部有定义）。

当前状态：`扫描 3232 个文件（含未跟踪），其中 667 个声明 eol=lf 的文本文件索引与磁盘均为 LF`。

漂移验证 `scripts/devops/verify-line-endings-drift.py` 的 11 例**全部符合预期**：

| # | 注入 | 期望 | 结果 |
|---|---|---|---|
| 1 | 一个受管文件磁盘改 CRLF | RED「磁盘是 CRLF」 | ✓ |
| 2 | 两个受管文件磁盘改 CRLF | RED，两个路径都列出 | ✓ |
| 3 | 索引 blob 改 CRLF（`git --cacheinfo`） | RED「索引是 CRLF」 | ✓ |
| 4 | 磁盘 CRLF 后跑 `--fix` | exit 0 且磁盘回 LF | ✓ |
| 5 | `.gitattributes` 去掉 `eol=lf` | RED「失去判别力」 | ✓ |
| 6 | `MIN_SCANNED` 抬到 999999 | RED「输出格式可能已变」 | ✓ |
| 7 | `MIN_LF_MANAGED` 抬到 999999 | RED「失去判别力」 | ✓ |
| 8 | 受管文件加一行注释（保持 LF） | **GREEN**（不绑「文件没被动过」） | ✓ |
| 9 | **非受管** `.java` 改 CRLF | **GREEN**（不许越界管辖） | ✓ |
| 10 | 新建**未跟踪**文件即 CRLF | RED（`??` 不是 ` M`，最容易漏） | ✓ |
| 11 | 全部还原 | GREEN（防「永久红」被当有效） | ✓ |

> ⚠️ 串行执行：本脚本按设计会真注入 CRLF / 改索引 / 改 `.gitattributes`，**不可与聚合门禁链并发跑**。
> 2026-09-18 实测踩到：并发时聚合链报「失败 1 个」，隔离重跑即 **0 个** —— 那是自己造出来的假红。

---

## 文件

| 文件 | 证明了什么 |
|---|---|
| `ab-A-no-retry-RED.log` | A 组：模拟修复前（`MAX_ATTEMPTS=1`）→ 30 用例红 6 个，恰好是重试用例；反向守卫保持绿 |
| `ab-B-retry-GREEN.log` | B 组：还原为重试启用 → `Tests run: 30, Failures: 0` / `BUILD SUCCESS` |
| `line-endings-drift-verification.txt` | 行尾门禁 10 例漂移验证全部符合预期 |
| `console-alert-rules-list.png` | 运营台 `/alert-rules` 实机渲染（含告警渠道分组） |
| `console-test-send-dialog.png` | 「测试发送」弹窗：「测试发送：全部成功」/「FEISHU：已投递」 |
| `console-alert-channel-summary.json` | 上述浏览器检查的逐条判据（AC-01…AC-06） |

### 编码规范化说明

两份 `ab-*.log` 由 PowerShell `Out-File -Encoding utf8` 产出，带 UTF-8 BOM。
归档时**逐字节去掉 BOM（`ef bb bf`）**，正文一字未改，以便 `Read` 直接可读。
本次两份日志**均为纯 UTF-8**（无历史上出现过的「正文 UTF-16LE + 尾部 ANSI」混杂编码）。
