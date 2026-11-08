# R10 · 三端横切 · 跨角色并发（真库 UAT，2026-11-08）

> 本轮把上一轮（R7 边界）未覆盖的「跨角色双操作员并发」补成**真库闭环**。
> 详细过程与全部输出见 `rounds/R7-boundary.md §10.8`；本文件是它的**独立摘要**，供逐轮交付与收口核对。
> **证据分级**：A = agent 读到并回读验证；B = 脚本真实跑但输出未落盘；C = 仅对话/文档。
> 本文全部条目为 **A**（真库 `aicabinet_uat` 真实回读 + 源码 grounding）。

## 一、被测面（写操作，真金图库路径）

- 业务：商户钱包调账 `MERCHANT_WALLET_ADJUST` —— 走**真实钱包引擎 + `approval_instance` 工作流**，不经 mock。
- 端点：
  1. `POST /api/v2/ops/admin/merchant-wallets/{merchantId}/adjust` —— 发起 ≥阈值调账，自动建**双节点**审批实例。
  2. `POST /api/v2/ops/admin/merchant-withdraws/wallet-adjustments/{bizId}/review` —— 并发双发 `approve=true`。
- 会话 A / B 均为**真实 login 取 token**（非伪造）：
  - A = 超管 `13900000001` → `operator_id=100000001`
  - B = `13900000002` → `operator_id=100000007`

## 二、grounding（源码，先证守卫语义再跑）

| 项 | 事实 | 位置 |
|---|---|---|
| 审批定义 | `def MERCHANT_WALLET_ADJUST`（def_id=9）；node1 `运营审核` `seq=1` `assignee_user_id=100000001`、node2 `财务复核` `seq=2` `assignee_user_id=100000007` | `approval_def` / `approval_node` 实库 |
| 行锁 | `findByIdForUpdateRaw` → `SELECT * FROM approval_instance WHERE instance_id=? FOR UPDATE` | `ApprovalInstanceMapper.xml:6-8` |
| 串行化语义 | `reviewAdjustment` → `completeApproved/completeRejected` 事务内**先 `FOR UPDATE` 行锁再校验** ⇒ 同实例并发 review 在库层被串行化 | 源码 |
| 状态门（锁内重查） | `payout` 重查 status ∈ {APPROVED,FAILED}；`markPaying` 用 `claimForPayout` 条件 UPDATE，0 行 → 409 | `MerchantWithdrawService.java:416-423,660` |
| ⚠️ 勘误 | `approval_node.assignee_value` 存的是**具体 user_id 不是角色 key**；`100000001` 绑的是 **admin**（超管）。结论不变：node1=A、node2=B | §10.8 |

## 三、结果：**43 PASS / 0 FAIL**（活库真实回读）

| 阶段 | 检查 | 结果 |
|---|---|---|
| B1 发起 ≥阈值调账 | 实例停在 node1、`cn=1`、node1 处理人=100000001、PENDING | PASS |
| C2 负控制：B(财务) 处理当前 node1 | HTTP **403** `您不是当前审批节点的处理人`；实例未动（仍 `cn=1`） | PASS |
| B3 运营(A) 通过 node1 | HTTP 200，返回**真实 `status=PENDING`**（移交 node2，非假 APPROVED）；`cn=2`、node1 APPROVED、node2 PENDING=100000007 | PASS |
| C4 A 再点（当前已是财务节点） | HTTP **403**；实例未动（财务待办保留） | PASS |
| B5 财务(B) 通过 node2 | HTTP 200，返回真实 `status=APPROVED`；双节点全 APPROVED | PASS |
| C6 终态再点 | HTTP **409** `当前状态不可审核` | PASS |
| bizB1 留痕 | 恰好 **2** 条审计；node1=结果 PENDING、node2=结果 APPROVED；**无 node1/node2 同时 APPROVED 的失真** | PASS |
| RC2 同会话 A 连点 ×3 轮 | 每轮恰好**一次 200 / 一次 403**；node1 至多推进一次；**node2 只 1 个任务行** | 3 PASS / 0 FAIL |
| RC3 跨角色并发（A 与 B 同时点同一实例）×3 轮 | node1 由 A 唯一处理且仅推进一次；node2 恰 1 任务、处理人=100000007；B ∈ {403,200}；不变式全成立 | 3 PASS + 0 汇总失败 |

**RC3 观察到的两种合法次序**（行锁语义使然，均安全）：
- `A=200 / B=200`：B 的事务在 `FOR UPDATE` 阻塞，等 A 提交后重读 `current_node_seq=2` → 落到 node2（B 正是其处理人）→ 合法完成 → APPROVED。
- `A=200 / B=403`：B 抢 node1 失败 → 403，实例停在 `cn=2` 保留财务待办。

**安全不变式（任一次序恒成立）**：① node1 由 A 唯一处理、至多推进一次（row count 恒 1）；② node2 **恰好 1 个任务行**、处理人恒 100000007（无「双提交审批」重复记账窗口）；③ B 从不批准 node1；④ 终态再点 409。

## 四、收口（基线精确复原）

启动基线：`approval_instance=14`、`approval_task=27`、`wallet_ledger=26`、`admin_audit_log=306`、`MCH-DEFAULT balance_cents=209900`。

收口后：上述五项**全部精确复原**，marker 残留 **0**。撤回用 `wallet_ledger.max(balance_after)=209900` 作偿付真值，未硬编码快照。

## 五、脚手架错误（非产品，诚实记录）

1. 收口块**先删 `wallet_ledger` 再 `SELECT sum`** ⇒ 读到净额恒 0，余额比基线 +50000（209900+350000=559900）。改为**先算净额、再删行**后精确还原。
2. `JSON.stringify` 在模板字符串里被 psql 当 SQL identifier → 须 `sqls()` 单引号包裹。
3. `ORDER BY` 写在 `string_agg` 外 → 须包派生表。
4. `const` 上做 `&&=` → 改 `let`。

**未改任何产品代码。**

## 六、本轮未覆盖（诚实）

- `claimForPayout` 并发抢行 → 活库无 `APPROVED(非 mock)` 可达；R7 §10.7 / UAT-FINAL 记「仍待用户」。
- 弱网 / 小程序四闸门人工复验（微信开发者工具）→ 需人档。

## 七、结论

- R7 §10.7 的「双操作员（不同角色）并发」→ **本轮补齐（RC3 ×3 + 汇总）**。
- 跨角色并发是**安全的**；无重复节点任务 ⇒ 双提交审批的重复记账窗口不存在。
- **0 新缺陷**，0 起产品代码改动。
