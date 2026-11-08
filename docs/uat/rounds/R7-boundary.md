# R7 · 并发 / 边界 / 收口 — 边界与真实触发（真实执行记录）

> 环境：UAT 网关 `http://127.0.0.1`（Apple Admin 后端 127.0.0.1:2149），UAT 库 `aicabinet_uat` / 容器 `ai-cabinet-postgres-1`。
> 执行日期：2026-11-07。全部结论来自源码 + 真实 API 落地执行 + 库内回读。
> 脚本：`.tmp/r7-f1-real-trigger.mjs`（触发+负向+阳性对照）、`.tmp/r7-f1-cleanup.mjs`（收口）、
> `.tmp/r7-f1-verify-fix.mjs`（**修复后复验**：重建镜像 + 重启容器后重跑，断言全部 15 项 PASS）、`.tmp/r7-f1-cleanup2.mjs`（复验收口）。
> 基线（本轮开始前）：`wallet_account MCH-DEFAULT balance=209900`、`approval_instance=14`、`admin_audit_log=306`。

## 1 · 本轮真实执行了什么

| R7 项 | 状态 | 证据 |
|---|---|---|
| 4. **F1 真实触发**（无实例调账单点通过） | ✅ 先坐实缺陷（修复后复验 PASS，见 §5b） | 见 §2 / §5b |
| 4b · F1 驳回路径（同家族） | ✅ 坐实 | 返回 200 REJECTED 且未见实例 |
| **4c · F1 幂等**（同 bizId 再点一次） | ✅ 无守卫 | 再次返回 200 APPROVED，无 4xx |
| **4d · F1 阳性对照**（≥阈值、实例存在、双签） | ✅ 暴露更深：报告 APPROVED 而实例仍 PENDING| 见 §3 |
| 5. **调账发起实例**（F3 弹窗真实点击） | ✅ F3 不可通过 API 触发（fail-loud 生效） | 见 §4 |
| 1. 并发双操作（同单据两操作员） | ⏳ 未覆盖 | 未执行（需双会话） |
| 2. 弱网（AUTOMATOR 断网） | ⏳ 未覆盖 | 需微信开发者工具人工操作 |
| 3. 跨日/账期边界 | ⏳ 未覆盖 | 本轮未执行 |
| 6. 全表覆盖终检（181 表三分类） | ⏳ 未覆盖 | 本轮未跑 |

## 2 · F1 〈调账审批：无实例也返回 200 APPROVED〉— 实测坐实（open）

**源码**：`services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantWithdrawService.java:319-332`
（`reviewAdjustment` 内 `approvalWorkflowService.completeApproved/completeRejected` 属静默 no-op，`status` 硬编码，无实例存在性校验。）

**实测构造**：`merchant.withdraw.review_threshold_cents = 50000`（¥500）。
把调账金额压到阈值之下（`49900` 分）→ `adjust()` 不发起 `MERCHANT_WALLET_ADJUST` 实例（库内 `approval_instance` 命中数 = **0**）。
对新建 `bizId=1794063944012629703975` 点「**通过**」：

```
adjust(49900 分)      => HTTP 200  数据 ok
bizId                 => 1794063944012629703975
instance(BIZ_ADJUST,bizId) => 0          ← 刻意不建实例
POST wallet-adjustments/{bizId}/review {approve:true}  => HTTP 200, code 0,
   data = {"status":"APPROVED","bizId":"1794063944012629703975"}
audit_log 命中        => MERCHANT_WALLET_ADJUST_REVIEW | 结果=APPROVED；备注=AUDIT-F1
```

**读法**：
- 期望（对齐同文件 `completeReview:354+` 的 `409 「审批流未发起…无法通过」`）＝ **409**；
- 实测 = **200 + APPROVED + 假审计行** ⇒ **F1 坐实，缺陷仍 open**。

**同家族两根**：
- 驳回：`review {approve:false}` 无实例 → **200 + REJECTED + 假审计行**（同样失真）。
- 幂等：对同 `bizId` 再点一次「通过」→ **仍 200 APPROVED**，未 409 ⇒ “一次审批最多一次”语义缺失。

**UI 影响**：`clients/admin-vue/src/views/finance/MerchantWithdrawView.vue:927-932`
`api.request(...)` 后无条件 `ElMessage.success('已通过')` + `loadAdjustTasks()`；调用方只看 HTTP 200，
无法区分「真通过」「静默空操作兜底」，也看不到真实实例状态。

## 3 · F1 阳性对照暴露更深一层 —— 报告状态与实例状态机脱节

对照：金额 **≥ 阈值**（`50000` 分 ⇒ 正常建实例）。
定义 `MERCHANT_WALLET_ADJUST`（def_id **9**）为**双节点**：节点1「运营审核」(USER 100000001)、节点2「财务复核」(USER 100000007)。

```
adjust(50000 分) => 200 ok                    → 自动 start 实例
bizId2            => 1794063944926303582856
instance(status=PENDING, current_node_seq=2)   ← 建后停在节点2
review {approve:true} => HTTP 200  data = {"status":"APPROVED","bizId":...}
instance.status after  => PENDING              ← 节点2 财务复核仍未批
```

**结论**：即使实例真实存在，`reviewAdjustment` 仍报 **APPROVED**，而实例实际停在节点2 仍 **PENDING**。
`status` 根本没读 `instanceStatus(bizId)`，是完全**硬编码**。这把 F1 从“无实例兜底”放大成
“**任何一次节点过关都被当成整单完结**”——上线后运营在后台看到「已通过」，而财务节点的待办依然挂账。

## 4 · F3 〈审批手动发起 Map.of 空字段 500〉— 实测不可触发（低风险，fail-loud 生效）

源码：`ApprovalController.java:104-117` 用 `Map.of(instanceId, bizType, bizId, status, title?:"")`。
实测：

```
POST /api/v2/ops/admin/approvals/instances {bizType:'MERCHANT_WALLET_ADJUST', bizId:<已存在实例>}  => 409
  「该业务单已有审批实例（PENDING），不可重复发起」        ← 同单重复发起被挡（fail-loud ✓）
POST /api/v2/ops/admin/approvals/instances {bizType:'', bizId:'', title:null}                     => 400
  「bizType 与 bizId 均必填」                              ← 空字段在 Map.of 前先被参数校验拦下 ✓
```

`Map.of(null,…)` 才会 NPE；而 `bizType/bizId` 必校验 + `title` 已做了 `== null ? "" :`，
因此当前 API 路径下 **F3 不可复现，维持低风险**。建议仍按 `Map.of(k, v==null?"":v)` 统一化以消除未来踩坑面。

## 5 · 收口核对（造数已回基线）

```
ledger deleted          = 2（两条 AUDIT-F1 造数，合计 99900 分）
balance restored        → 209900（回到起始基线）
instance 16 deleted     → 0；approval_instance = 14（基线）
admin_audit_log deleted = 6（本轮 6 条 AUDIT-F1 造行），admin_audit_log = 306（基线）
AUDIT-F1 ledger 残留     = 0
MERCHANT_WALLET_ADJUST instances = 5（基线，本轮所建已删）
```

> 负向对照：以上核对全部等于起始基线 ⇒ 无残留、无脏写。

## 5b · F1 修复 + 镜像重建后复验（2026-11-08）

**用户拍板方案**：**B — 补 409 守卫 + status 读实例真实状态（`MerchantWithdrawService.reviewAdjustment`）**。

**后端改动**（`services/trade-service/src/main/java/.../MerchantWithdrawService.java`）：
1. 审前先读 `approvalWorkflowService.instanceStatus(BIZ_WALLET_ADJUST, bizId)`；
2. 无实例 → **409**（与同文件 `completeReview:349-359` 既有模式一致，消息「审批流未发起（审批定义为空或已禁用），无法通过」）；
3. 实例存在但**非 PENDING**（APPROVED / REJECTED / SKIPPED 终结态）→ **409**（幂等守卫）；
4. 通过 / 驳回后**重新读一次真实状态**写入 `Map.of("bizId","status")` 与审计，**不再硬编码** APPROVED/REJECTED。

**部署**：`docker build -f infra/docker/trade-service.Dockerfile -t ai-cabinet/trade-service:local .`
（实测 Maven 重编译「Recompiling the module because of changed source code / Compiling 439 source files」）→
`docker compose -f docker-compose.full.yml -f docker-compose.uat.yml up -d --force-recreate trade-service`
（新镜像 `ai-cabinet/trade-service:local` @ `58a03f6813c2`；重启后 `/actuator/health` = UP）。

**复验实测（`.tmp/r7-f1-verify-fix.mjs`，15 项全 PASS）**：

| 断言 | 修复后实测 | 修复前 |
|---|---|---|
| 无实例（49900 < 阈值 50000）点「通过」 | **409** 「审批流未发起…无法通过」 | 200 APPROVED |
| 无实例点「通过」不写假审计 | 审计命中 **0** | 写「结果=APPROVED」 |
| 无实例点「驳回」 | **409** | 200 REJECTED + 假审计 |
| 无实例同 bizId 幂等再点 | 仍 **409** | 仍 200 APPROVED |
| ≥阈值（50000）实例存在、双签节点1运营通过 | HTTP 200，`data.status = PENDING` **== 库内实例真实状态** | 报 APPROVED（硬编码） |
| 双签实例不得假称 APPROVED | `data.status ≠ APPROVED`，库内仍 PENDING | 报 APPROVED 但实例 PENDING |
| 审计记录真实状态 | `结果=PENDING；备注=AUDIT-F1-VERIFY-ctl-approve` | `结果=APPROVED`（假） |
| 通过后同单再点（跨节点，本人已无待办） | fail-loud **403**「您不是当前审批节点的处理人」 | — |
| F3 重复发起 | **409**（已有实例） | — |
| F3 空字段 | **400**（不 500，`Map.of` NPE 不可经 API 触发） | — |

**收口**：`approval_instance` 14、`admin_audit_log` 306、`balance_cents` 209900、`MERCHANT_WALLET_ADJUST` 实例 5 —— 全部回到 §5 基线；`AUDIT-F1-VERIFY` 残留 = 0。

> 结论：**F1 已修复并经真实 API 复验，缺陷转 CLOSE（mitigated）**。
> F3 维持低风险（**非缺陷**）：请求体必填校验在 `Map.of` 之前拦截、`title` 已做 `== null ? "" :` 空安全，空值路径不可从 API 触达；可选加固 `Map.of(k, v==null?"":v)`。

## 6 · 本轮新增结论

| 编号 | 结论 | 状态 |
|---|---|---|
| F1 | `reviewAdjustment` 无实例兜底 + 报告状态硬编码（真通过部分） | **已修复（B 方案），复验 15 项 PASS**（§5b），**CLOSE** |
| F3 | 手动发起审批：空字段被 fail-loud 拦，`Map.of` NPE 不可经 API 触发 | 低风险（维持，可选加固） |
| 幂等 | 同 bizId 重复审批 | 已随 F1 修复（终结态 / 跨节点 → 4xx） |

## 7 · 被抢过一轮的阻塞项（本轮未覆盖）

1. **并发双操作**：同单据两个操作员并发通过/驳回，未测（需双登录会话）。
2. **弱网**：AUTOMATOR 断网后商户小程序应可恢复提示，未测（需微信开发者工具人工操作）。
3. **账期切换窗口**：窗口前后下单、日结跨日触发，未测。
4. **全表覆盖终检**：`docs/uat/baseline-tables.txt` 三分类未跑。

以上 4 项需下一轮或人工在开发者工具/浏览器配合完成，本轮不谎报为已完成。

## 8 · 提交前 pre-push 门禁回归——三项失败的定位与修法（2026-11-08）

推送前按规则 `pre-push-ci-green` 跑 `node scripts/pre-push-ci-preflight.mjs`（default 模式），某次运行暴露 3 处失败。逐一定位为**本次工作树改动引入**（非既有缺陷），修法如下，**全程未放宽/跳过任何门禁**：

| 门禁 | 现象 | 根因 | 修法 | 终态 |
|---|---|---|---|---|
| `check:consumer-endpoints` | 注释里的裸 `/api/v2/devices/{id}/status` 被判为未放行面 | 该门禁**不剥离注释**，注释里的字面量即算 offender | 注释改为引用 `ConsumerEndpoints.deviceStatus` 等符号，不再书写裸 URL | ✓ 16 pilot literals 全过，CB-030 未复活 |
| `check:mp-defect-classes`（F3 类自查） | `index.vue:1882` 门闩置位**晚于** `await ensureConsumerAuth()` | 门闩 `productsLoading.value = true` 在鉴权 await 之后，握手窗口内按钮不置灰、可二次点开 | 门闩置位**前移到**鉴权 await 之前，失败分支复位 | ✓ 270 files / 17 类 0 命中 / selftest 56 用例 |
| `check:mp-gate-negative` | 负向对照用例自证失败 | 其 `checkGuardAfterAuthAwait(single)` 读的是**真实** `index.vue`，同一份真实 F3 命中被它当成反例 | 随上一条 F3 修复自动转绿 | ✓ |

另有第 4 条 `check:mp-scoped-hash` 失败，定位为**本地构建产物**问题、**非本次源码改动**：

- 现象：`clients/consumer-mp/dist/dev/mp-weixin` 有 **38 对** wxml/wxss scoped-id 错位（如 `pages/result/result.wxml` `28d1ee04` vs `d38065ce`）。
- 根因：`dist/dev` **不在 git 中**，曾被 `dev:mp-weixin` 监听器与一次性构建**混写**——门禁源码注释记载的 2026-11-06「共享组件 CSS 落空」同源。`dist/build/mp-weixin` 与两个 merchant dist 目录本就正常。时间戳佐证：build `22:12`、dev `22:32`（监听器晚于构建，且两者 scoped-id 语义不同）。
- 修法（门禁注释自身给出的处置）：停旧监听器 → 删 `dist/dev` → 重启 `dev:mp-weixin` 让其全量重发（新监听器 PID 19472）。
- 终态：✓ 四个 dist 目录 wxml/wxss scoped 哈希全部成对。

**最终门禁**：`PREPUSH_EXIT=0`（format ✓ / consumer+merchant type-check ✓ / lint 0 error ✓ / 98 audit-gates 全过 ✓ / migration & openapi 门禁 ✓）。
> 门禁不覆盖 e2e-h5 / `mvn verify` / 真 Docker IT（仍依赖 CI 或本机整栈）。第三条失败全部由门禁**自身复跑通过**证明已修复，未绕过任何开关。

**Java 单测回归（与本提交同批次跑过）**：
`mvn -B -Pskip-admin-ui -Dtest=MerchantWithdraw*Test -Dsurefire.failIfNoSpecifiedTests=false -pl services/trade-service -am test`
→ `Tests run: 38, Failures: 0, Errors: 0, Skipped: 0`，BUILD SUCCESS（含 `MerchantWithdrawConcurrencyTest` 11、`MerchantWithdrawDoubleReviewTest` 5）。
> 必须从仓根跑且带 `-pl services/trade-service -am`：只跑 trade-service 模块不会编译 common-core，会以「找不到符号 `com.aicabinet.common.dto.*`」报编译错（非产品缺陷）。

## 9 · R7 §7 未覆盖项的进展与收口对账（2026-11-08，只读无需造数）

R7 §7 四项在单元/静态层面已有部分进展，**逐项对账如下**（不谎报）：

| §7 项 | 现状（2026-11-08） | 是否已覆盖 |
|---|---|---|
| 1 并发双操作（同单双操作员） | `MerchantWithdrawConcurrencyTest`(11) + `MerchantWithdrawDoubleReviewTest`(5) 已随本提交 Java 回归 PASS——**单测层已覆盖双操作/重复审** | ⚠ 单元层已证，**端到端真金库双会话并发仍未做**（留人工） |
| 4 全表覆盖终检（三分类补跑） | 本轮只能读 64 校验 → **作准数 = 实库 179 张**、`_bak` 备份表已不存在（见 §9.1）；三分类细分未重跑 | ❌ 未覆盖（留下一轮） |
| 2 弱网（AUTOMATOR 断网恢复提示） | 需微信开发者工具人工配合 | ❌ 未覆盖（用户在场） |
| 3 账期切换窗口（跨日下单/日结） | 需时间窗 + away-runner，且再增演示数据前需用户同意 | ❌ 未覆盖（需确认） |

### 9.1 全库表覆盖：旧口径与实库对账（read-only）

发现**文档计数过期且自相矛盾**，以实库为准纠正：

```
SELECT count(*) FROM information_schema.tables WHERE table_schema='public';  → 179
SELECT count(*) FROM pg_tables WHERE schemaname='public';                    → 179
SELECT count(*) FROM flyway_schema_history;                                   → 446
SELECT tablename FROM pg_tables WHERE schemaname='public' AND tablename LIKE '%_bak%';   → 空
```

- **实库 `aicabinet_uat`.public = 179 张**（双路 count 一致）。`UAT-FINAL.md §五` 写的「181 表 / 94 触达 / 87 空」是 V448 删 2 张备份表前的旧口径，已过期 ⇒ **已作准口径纠正并留痕**。
- **原「死表候选」两张已不存在**：`sku_catalog_vision_cols_bak_20261011`、`sku_vision_mapping_bak_20261011` 均不在库里（`pg_tables LIKE '%_bak%'` 返回空）⇒ 已被 V448 删除。旧口径「2 死表尚未清理」不成立，**勿据此排队清理动作**。
- `table-coverage-final.md` **表头 179 = 139 触达 + 40 空 与实库一致**，但其正文「已触达 94 表」/「空表 58 = A34+B22+C2」仍是旧稿残留、两处数字自相矛盾 ⇒ 三分类整轮终检时一并重写（本轮据实力此行/列未逐表重跑，不下结论）。

### 9.2 待用户决策（禁止自行猜测实现）

- **barcode 匹配一致性**：GS1 前端校验 shape 与后端 `BarcodeLookupClient` 不一致。**需用户拍板是否收紧前端**；本轮未改、未降级任何校验。
- **账期切换窗口造数**：再向 UAT 写入演示数据前需用户同意。
- **push**：local 领先远端 2 commit（`27e00b90`、`37fb91f1`），**待用户显式确认再推**。

---

## §10 · R9 真金库并发双操作（真钱路径，非 mock）

### 10.1 动机
R7 §7 项 1「并发双操作」此前仅做 Java 单测，**没有在活库上跑过真并发**：同一 PENDING 单据被两个请求同时点通过/驳回。真金库下 = 双提交审批 + 不可复现的重复记账窗口，属**会开出 bug 的那一类**。

### 10.2 测试面
- 对象：`MERCHANT_WALLET_ADJUST`（商户钱包调账）——**走真实钱包引擎 + `approval_instance` 工作流，不经 mock 开关**。
- 写法：`.tmp/r7-race-double-submit.mjs`（Node，`Promise.all` 并发 2 发）。
- 登录：`POST /api/v2/auth/admin-password-login`（13900000001，super admin）取操作员 token。

### 10.3 守卫 grounding（读源码，非拍脑袋）
| 方法 | 读的守卫 | 失败行为 |
|---|---|---|
| `reviewAdjustment` | 调账实例存在 + 顶点状态 PENDING（pre-check 绕过要求） | 409 |
| `completeApproved/completeRejected`（ApprovalWorkflowService） | `findByIdForUpdate`（行锁）再查状态 | 非 PENDING 即 return（幂等） |
| `payout` | `runWithMerchantWalletLock` **锁内重查** status ∈ {APPROVED,FAILED} | 否则 409 |
| `markPaying` | `claimForPayout(id, prev, now)` 条件 UPDATE | 0 行 → 409 |

### 10.4 结果（**9 PASS 0 FAIL**，实库 UAT `aicabinet_uat`）
```
[并发 2 次通过]
  A => 200 ok      |  B => 403 您不是当前审批节点的处理人
  之后实例: 20/PENDING/2
PASS 并发通过没有重复出实例/零分裂  count=1
PASS 节点恰好推进一次 (node_seq 1 -> 2 且实例未终态)
[负向对照 · 并发 2 次再过] 期望至少一个失败
PASS 负向对照：至少一个非 2xx  A=403 B=403
PASS 负向对照不产生第二次推进  19/PENDING/2
PASS 本轮调账流水恰好一条（无重复入账）
PASS 本轮实例增量 = 1
--- 回滚后基线核对 ---
approval_instance = 14 (baseline 14)
wallet_ledger      = 26 (baseline 26)
admin_audit_log    = 306 (baseline 306)
balance            = 209900 (baseline 209900)
```
- **关键判定**：并发「通过」被实例级处理器校验挡在**一次推进之外**；B 拿到 `403 您不是当前审批节点的处理人`，说明 A 已把 `current_node_seq` 指向 node2 ⇒ **无第二次出款/无重复记账**。负向对照（再并发 2 次）两个请求**都**被挡，无第二次推进、无新实例。
- **真钱守恒**：本轮 1 条 `wallet_ledger` 与基线净增 1 实例，**没有第二写**。
- **基线可复现回滚**：四个计数与余额**精确回到基线**，脚本自收口一次到位。

### 10.5 本轮修掉的测试脚手架 bug（诚实记录）
初跑出现 2 个 FAIL，根因**在脚手架谓词**，不在产品：
1. 断言用了 `...||'@'...` 但 `node_seq` 读的是 `instance_id||'/'||status||'/'||current_node_seq`（分隔符错）；
2. cleanup 用 `DELETE ... WHERE biz_id LIKE 'AUDIT-R7CONC%'`，但 `approval_instance.biz_id` 存的是**带 `'AUDIT-R7CONC-...'` 的 remark**，谓词不匹配 ⇒ 残留 instance 18。
修法：断言改用 `= '2,PENDING'`；回滚按真实列 `biz_id`/`remark` 精确定向。**未改任何产品代码**。

### 10.6 收口与留痕
- 造数收口：`approval_instance` 14、`wallet_ledger` 26、`admin_audit_log` 306、余额 `209900`，均回基线。
- 脚本：`.tmp/r7-race-double-submit.mjs`（前次残留的 instance 18/19 已按实例 id 精确删除，`aicabinet_uat` 已复原）。
- 三端代码行为门禁（`pre-push-ci-preflight.mjs`）、Java 单测、本轮活库并发在 R7/R9 各轮均绿；本节只补「活库并发」证据，**未放宽任何门禁**。

### 10.7 仍未做（待用户）
- **笔挺终态的两操作员并发**：需要拿 node1(运营 100000001) 与 node2(财务 100000007) 两套 token 翻案同一笔，验证两阶段竞态。本轮同用户账号并发已拦住（guard 生效），但「不同角色分节点并发提交」未跑——需第二操作员会话。
- **`claimForPayout` 并发抢行**：活库上没有 `APPROVED(非 mock)` 去抢同一 `payout()` 的第二个请求；本轮属调账面，不碰提款闸门。
- 推送：local 领先远端 **3 commit**（`27e00b90`、`37fb91f1`、`a0b56f9b`），**待用户确认**。
