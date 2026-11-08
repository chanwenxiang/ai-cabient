# UAT 上线前测试覆盖总表（A 类真库/API · B 类浏览器/小程序）

> **用途**：上线前一句话看清「哪些做过、哪些没做、哪些做不了」。**口径以证据为准，不采信自报 `[x]`。**
> **生成**：2026-11-08（R14 收口后）。**活库**：`aicabinet_uat`；后台 API 靶端：trade-service `127.0.0.1:18080`。

## 0. 三个口径（先说明白，下面是「真没跑」还是「已经跑过」的前提）

| 代号 | 含义 | 判定方式 | 本轮状态 |
|---|---|---|---|
| **A 类** | 真库/真 API 造数 + 行级断言 + 负向控制 + 精确收口回 0 | 轮报告 `docs/uat/rounds/R*.md` + `.tmp/*.mjs` 日志 + 库内回读 | ✅ R1–R14 / A4 共 17 轮 |
| **B 类** | 浏览器后台 / 微信开发者工具 的 UI 断言（排版/交互/三端口径） | 必须先用 **Playwright MCP / DevTools automator** 真开真点；禁止 curl / 日志 / 代码推理冒充 UI | ⚠️ 历史批（2026-09-26/27）已做过；**本轮 A 类改动后未对每个模块回刷新 UI 截图** |
| **C 类（阻塞）** | 业务流把到达路径关死，或需要资金写之外才能造出前置的数据形态 | 如实登记「不可测 + 确切原因」，**不造数、不编跑通** | 见 §三 |

> 🔖 **证据分层**：A 类本轮证据个个落在 `rounds/`（含 `§N` 锚点）+ `.tmp/` 原始 stdout + 库表回读 count。B 类证据落在 `docs/uat-screenshots/2026-09-26/<模块>/`（1365 文件）与 `docs/uat/*_FULL_BROWSER_UAT.md`。

## 1. 本轮已闭环（A 类真库 + 真注册任务）—— 17 轮

| 模块 | 覆盖内容 | 状态 | 证据锚点 |
|---|---|---|---|
| **主链冒烟** 扫码开门→购物→结算 PAID | 全链对账平 | ✅ | `R1-main-chain.md`；`UAT-FINAL.md` §一 |
| **用户/账号/角色/组织** 全生命周期 + RBAC 矩阵双向 | 零新缺陷 | ✅ | `R2-users.md` |
| **商品/SKU/库存/仓储** SKU 生命周期+采购全链+三层账+盘点 ADJUSTED+调拨 | 零新缺陷（回归 MA-069/157/103/066） | ✅ | `R3-goods.md` |
| **营销/会员/积分/券/部分退** | MA-147/099/096 修复回归全绿；零新缺陷 | ✅ | `R4-marketing.md` |
| **退款/争议/钱包/免单 WAIVE** | 余额/券/分账全对平；零新缺陷 | ✅ | `R5-refunds-disputes-wallet.md` |
| **定时/日结/月结/fee-rerun/全景** | `fee-rerun×2` / 机器账单 ×2 / recon×2 / 巡检×2 / 调度 run 幂等；新缺陷 0 | ✅ | `R6-scheduled-settlements.md` |
| **并发/边界/收口 + F1 调账审批（坐实→已修→复验 15 PASS）** | 边界及死表候选 ×2；F1 转 CLOSE | ✅ | `R7-boundary.md` §5b/§5c |
| **空表批量填充（181 表触达 108→123）** | 15 张空表经业务链产生真实数据 + 归因 | ✅ | `R8-fill.md`；`table-coverage-final.md` |
| **定时任务/审批流/参数配置/告警规则** | 全量验证 | ✅ | `R9-scheduled-approval-config.md` |
| **参数 118 键 + 告警 34 类型 全量清点** | 逐键/逐类型核对，无死旋钮 | ✅ | `R9b-config-118-alerts-34.md` |
| **跨角色并发（MERCHANT_WALLET_ADJUST）** | 43 PASS / 0 FAIL；基线精确复原；安全不变式 ①–④ 坐实 | ✅ | `R10-cross-role-concurrency.md`；`UAT-FINAL.md` 四·补 R10 |
| **全库表覆盖终检 179 表返正** | 140 触达 + 39 空（修正上一版 `announcement` 自相矛盾）；C′ 商户结算单恒空属设计 | ✅ | `R11-table-coverage.md`；`table-coverage-final.md` |
| **CB-025 条码校验一致性（GS1）** | 条码双向一致 + 账期窗口边界取证（只读） | ✅ | `R11-barcode-gs1-consistency.md` |
| **admin 产物配对门禁（CI 稳定性）** | 干净检出重建字节比对闭环；preflight exit 0 | ✅ | `R12-artifact-guard.md` |
| **账期切换窗口（日结 settlement 侧边界）** | 窗口常量修正 + 半开左闭右开归属实测 | ✅ | `R13-settlement-period-boundary.md` |
| **分仓月结账期半开窗口** | 23 条跨两账期同源造数；SKU-SODA 负向控制命中；审计批次4 钳制坐实；真实 POST 写态 + 幂等 | ✅ | `R14-warehouse-monthly-close-window.md` §一–§十 |
| **`claimForPayout` 竞态（部分覆盖）** | mapper 条件 UPDATE 竞态已有真实 DB 证据 + CI 执行；**活库端到端阻塞**（见 §三 A2） | ✅↑ | `A4-claimForPayout-concurrency.md` |
| **R15 钱包→提现金额全链对账** | A 自动通过+MOCK 打款 / B 双节点人工审 / 负控制(最低/上限/不足/幂等/冲突) / 金额三恒等；58 PASS/0 FAIL，0 残留（外部全表双核）。单据→分账→钱包腿由 R1 覆盖（commercial-flow 识别恒转争议不成单，用户拍板聚焦提现链） | ✅ | `R15-wallet-withdraw-chain.md` |
| **R17 月结写态三接口** | approve(DRAFT→APPROVED 锁单) / updateLine(改行重算+editedFlag) / disposeLine(CLAIM→索赔台账)+负控；37 PASS/0 FAIL/0 残留 | ✅ | `R17-monthly-close-writes.md` |
| **R18 跨账期 drift 形状** | 真实 generate 出单(save=recalc)→改 saved 检出 drift→还原复一致→editedFlag 豁免；18 PASS/0 FAIL/0 残留 | ✅ | `R18-cross-period-drift.md` |

**CI 门禁（C1，真实 GitHub Actions）**：`20ffcb7b`（源码轮）→ run `42463931974`/number **1297** `completed/success` 5/5 job；`2be58f39`（R14 文档轮）→ run **1298** `completed/success`。证据见 `R14 §十` 与 `PROJECT_KNOWLEDGE §12`。
> ⚠️ 本仓本轮**只改 docs/脚本** ⇒ CI 绿主要证明「文档增补不破坏门禁」，**源码级产物比对尚未在含产品改动的提交上复验**。

## 2. 已做过但本轮 A 类改动后未刷新 UI（B 类待回补）

| 模块 | 历史批 | 本轮未刷新 | 待补口径 |
|---|---|---|---|
| 概览（Overview / 工作台 / 大屏） | 2026-09-26 已跑 | ✅ 本轮有源码改动（barcode/月结） | 深链落地 + 三端口径抽样 |
| 交易/履约（订单/对账/发票/报表） | 2026-09-26/27 | ✅ | 金额单位、导出、深链 |
| 设备域（详情/地图/KPI/固件/OTA/告警） | 2026-09-26/27 | ✅ | 阈值告警、温度块（屏上文案） |
| 营销/会员/卡券/积分 | 2026-09-26/27 | ✅ | 四维（数据/排版/交互/三端口径） |
| 仓储/补货/盘点（R14 月结窗新） | 2026-09-26/27 | ✅ **R14 改的是计算口径 ⇒ 需重拍「月结卡片」** | 月结期望值/缺口 的 UI 逐行核对 |
| 审批/通知/工单/报修 | 2026-09-27 | ✅ | 空态、toast、状态机 |
| 系统/角色/字典/组织 | 2026-09-27 | ✅ | 写取消、空态 |
| **小程序三端**（consumer / merchant MP） | `MP_THREE_END_SCENARIOS.md`；`MINIPROGRAM_BACKLOG.md` | ✅ **P0#1 充值 DONE；#2/#3/#6/#7/#8/#9/#10 均为 PARTIAL/PENDING** | 见 `MINIPROGRAM_BACKLOG.md` §1（12 行） |
| **四闸门人工复验**（登录/扫码开门/支付分/弱网） | `FULL_TEST_SESSION_PROMPT.md` | 🔴 未做 | ⚠️ **我不可替代，必须你在微信开发者工具里点**（R1 已打门等） |
| 真机支付分生产 API | —— | 已留痕（生产未接 = 501） | 需真实生产通道 |

## 3. 阻塞 / 未测（如实登记，不造数）

**A1. `merchant_settlement_bill`（商户月度结算单）**
- 覆盖面：`R11` 已证「恒空属设计如此」——`order_revenue_split` 实库 **3 行全 VOIDED**，被 `buildRows()` 的 `isReversed` 口径排除，与日志「重建 0 商户」一致。
- 🚫 **未实测**：该报表的**确切金额从未用一笔非 VOIDED 实单实跑**。
- 处理口径（用户 2026-11-08 拍板）：**不造数据，如实登记**。上线前如需实证，另立一轮补一笔无退款实单后重跑 `settlement-bill`（或让我补不动产品的触发入口方案）。

**A2. `claimForPayout` 活库端到端「两请求同抢一张真实 APPROVED 单」**
- 🚫 阻塞根因（源码级，`MerchantWithdrawService.java`）：`review()`→`completeReview()` 在同一次钱包锁块内立即 `executePayout`，唯一让单据**停在 `APPROVED`** 的分支是**备付金不足**（需把平台资金池造亏）——资金写操作。
- 📌 已落地证据：`WithdrawClaimForPayoutSqlIT`（真 Postgres + 并发断言）+ CI `.github/workflows/ci.yml:505-506` 纳入执行；服务层 `== 0` → 409。见 `A4` 文档。

**A3. 小程序四闸门人工复验 + 弱网/重签 URL UI**
- 🚫 阻塞原因：UI/真机操作，**我这边无法替代**。
- 📌 已做好准备：`FULL_TEST_SESSION_PROMPT.md`、`MINIPROGRAM_BACKLOG.md` §4 执行顺序、`cleanup-test-data.ps1`（默认 FullBusiness）+ `mp-seed-gate.ps1 -CleanupFirst` 造数闸门。
- ⚠️ 纪律：**S0+S1 绿才能进 S2**；禁止带脏会话/争议宣称主链 PASS，禁止未列场景就扩「全业务大套」。

**A4. 其他待拍板的既有待办**（见 `PROJECT_KNOWLEDGE §10` G 表）
- `settlement-bill` 被触发入口缺失、`G11` 毛额对账判据（已另起一轮）、G2 设备地图抖動、`G3` 部分 evidence 未回链总册行号。

## 4. 剩余 A 类轮次（按依赖排序，建议 4–5 轮）

| 序 | 轮 | 内容 | 预计 | 前置 |
|---|---|---|---|---|
| ~~1~~ | ✅ R15 | **已完成**：钱包→提现金额全链对账（聚焦提现链，用户拍板；commercial-flow 识别恒转争议不成单）58 PASS/0 FAIL/0 残留 | — | 见 §1 / `R15-wallet-withdraw-chain.md` |
| **2** | R16 | `restockDetail` 列求和一致性 + `outboundId`/`warehouse_outbound` 耦合命名清理实证 | ~0.5 轮 | 复用 R3/R14 造数 |
| ~~3~~ | ✅ R17 | **已完成**：月结写态 approve/updateLine/disposeLine 三接口 + 负控，37 PASS/0 FAIL/0 残留（3 张 DRAFT 单 0 行 ⇒ 造最小 gap 行覆盖） | — | 见 §1 / `R17-monthly-close-writes.md` |
| ~~4~~ | ✅ R18 | **已完成**：跨账期 drift 形状（真实 generate 出单→改 saved 检出→还原→editedFlag 豁免），18 PASS/0 FAIL/0 残留 | — | 见 §1 / `R18-cross-period-drift.md` |
| **5** | R19 | `settlement-bill` 无触发入口的**方案设计**（只出方案，不产品改动） | ~0.5 轮 | 无 |

## 5. 结论

- **A 类真库链路**：`主链 → 用户 → 商品 → 营销 → 退款 → 定时 → 边界 → 空表填充 → 审批/参数/告警 → 跨角色并发 → 表覆盖 → 条码 → 产物门禁 → 账期窗口 → 月结窗口 → 提现链 → 月结写态 → 跨账期drift` **20 轮全部真实落库、可复算**；新缺陷收敛到 **MA-163(P2) + F1**（F1 已修已复验转 CLOSE）。
- **当前最需要你的两双手**：小程序四闸门（§3 A3）＋ 含产品改动的提交上的**源码级 CI 绿**（§1 末的 ⚠️）。
- **阻塞项口径**（用户已拍板）：`settlement-bill` 真实金额**不造数、如实登记**。
- 启动下一个 A 类轮次前我会先回读本表 + `PROJECT_KNOWLEDGE §10/§12`，避免重复造数。
