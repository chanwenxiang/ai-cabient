# P3-4 争议「无证据超时自动免单」设计稿

> **日期**：2026-10-01 ｜ **状态**：**已实施（2026-10-01 用户授权按建议判断；开关默认 OFF，dev 观察）**
> **来源**：`docs/SYSTEM_COMPARISON_EASYGO_VS_AICABINET_2026-10-01.md` §6.3 / §14.1 P3-4——借鉴旧弹簧柜 `RyTask.refundForWaitingFall`（超 30s 未出货自动原路退款，`mis-quartz\RyTask.java:413-441`）的思想。
> **纪律**：本文全部现状经源码核实（`文件:行号`）；资金路径必须有评审拍板后才实施。

---

## 1. 现状链路（已核实）

识别/结算超时的既定行为（`SessionExpireService.expireStaleRecognizingSessions`，每 60s，XXL `session-recognizing-expire`）：

1. RECOGNIZING / WAITING_UPLOAD / SETTLING 超过 `recognizingMinutes` → `upgradeConsumerRecognizingTimeout`（`SessionExpireService.java:256-283`）；
2. **升级前先释放预授权冻结**（C09，`:264-268`，失败只告警不阻断）；
3. 会话迁移 DISPUTED + `disputeService.createTimeoutTicket`（`:270-273`；failReason=「识别超时，已转人工审核，本次暂未扣款」）；
4. 超时争议单（`createTimeoutTicket :184-197`：识别结果 items=`[]`、modelVersion=`timeout`）**一直 OPEN 等运营人工结案**；`DisputeSlaScheduler` 只做 SLA 告警不结案（`DisputeSlaService` 仅 count/rate 查询）。

**关键资金事实**：超时单**从未扣款**（先不扣款转人工是本仓铁律，见三端审查 F2 修复），预授权也已释放。因此对超时单免单结案 = `waiveAndRefund` 返 0（「已免单，无需扣款」，`DisputeService.resolveWaive :763-782`）——**零资金移动的纯状态收口**，资金风险面远小于字面直觉。

## 2. 问题

争议人工结案队列随单量线性增长：每次识别超时/视觉不可用都产一张 OPEN 单，运营必须逐张点「免单」。量大时（视觉边缘弱网柜）这是纯重复劳动——旧系统用「超时自动退款」把这类单清零，新系统对应缺口就是**超时争议单的自动免单结案**。

## 3. 方案：XXL 托管任务 `dispute-auto-waive`

**范围门控（全部满足才自动免单）**：
1. `status = OPEN`；
2. 超时来源：`reason` 含「识别超时」（或等价：items=`[]` 且创建链路为 timeout）——**消费者申诉单（fileByConsumer）与低置信人工升级单一律不进**；
3. **未被认领**（`assignee` 为空）——运营已接手说明在处理，不抢；
4. 创建时间早于阈值（系统配置 `dispute.auto-waive.hours`，默认 **72h**，给运营留足处理窗）。

**动作**：复用现有 `resolveTicket`（system 操作者身份）走 `WAIVE` + `restoreInventory=false` 显式传参——审计、状态对齐（WAIVE→REFUNDED 兜底）、会话收口全部复用既有链路，不另写旁路（`DisputeService.java:594,605-617`）。

**护栏**：
- 总开关 `dispute.auto-waive.enabled` **默认 OFF**（fail-closed，先灰度再开）；
- 单轮批量上限（如 50 张/轮），防首开洪水；
- 同一用户滚动窗口自动免单次数上限（如 7 天 ≤3 次，防「制造识别失败白拿货」薅羊毛；超限单留人工）——上限触发时发风控事件（挂 `RiskControlService` 现有体系）；
- 每轮结果汇总走 `OpsAlertDispatcher`（免单张数/总金额=0/超限跳过数），失败也必须可观测。

**接线成本（诚实列出）**：新增 XXL 托管任务 = 7 处同步 + 两道门禁（`XxlJobManagedTasks.java:38-52`、`check-xxl-job-wiring`、`check-scheduled-task-seed`），并遵守「`@Scheduled` 保留、让位 `tryBegin`」约定。

## 4. 备选与取舍

| 备选 | 取舍 |
|------|------|
| A. 识别超时当场免单（不建单） | 更省事，但丢失「超时单可回溯复核」能力，且识别链路抖动时会放大误免；否 |
| B. 只做 SLA 到期提醒升级（现状已有告警） | 不解决人工点单的重复劳动；否 |
| C. 本方案（超时+未认领+72h 自动 WAIVE） | 兜底性质：72h 内运营正常处理则自动层永远不触发；**推荐** |

## 5. 实施清单（评审通过后）

1. 配置：`dispute.auto-waive.enabled/hours/max-per-round/per-user-window`（SystemConfigService，参考 V266/V267 补货闸门的配置先例）；
2. `DisputeAutoWaiveService`（门控查询 + 逐张复用 `resolveTicket` + 汇总告警）+ 单测（门控矩阵：非超时不进/已认领不进/未到 72h 不进/超限跳过/免单走既有链）；
3. XXL 七处接线 + 两门禁绿；
4. 回归：milk 全链（超时单场景）+ 一致性巡检绿。

## 6. 决策记录（2026-10-01 用户授权按建议与竞品惯例判断）

1. **阈值 = 72h**：跨完整周末；行业争议处理 SLA 惯例 48–72h，自动层做最后兜底不抢人工；
2. **防薅 = 滚动 7 天单用户 ≤3 次**（历史按 `operator_note='AUTO_WAIVE'` 统计）：电商自动退款防滥用常规口径，超限单留人工并发告警摘要；
3. **灰度 = properties `aicabinet.dispute-auto-waive.enabled` 默认 false**（fail-closed）：dev 可开观察，production 评审后再开；XXL 任务即使被触发也在未启用时空转。

## 7. 实施记录（2026-10-01）

- `DisputeAutoWaiveProperties`（enabled/hours=72/maxPerRound=50/perUserMax=3/perUserWindowDays=7）；
- `DisputeService.autoWaiveTicket`：跳过人工权限/设备范围校验，落账与状态对齐与人工 WAIVE 同链，`operatorNote='AUTO_WAIVE'` 标记；
- `DisputeTicketMapper.findOpenTimeoutUnclaimedCreatedBefore`（OPEN+reason 前缀「识别超时」+assignee 为空+创建早于 cutoff）；
- `DisputeAutoWaiveScheduler`（每 15min，单轮 ≤50，告警摘要）；
- 七处接线：KEYS / ScheduleZones cron+静默阈值 / XXL handler `disputeAutoWaiveJob` / Registry 注册 / V297 登记 / seed_aicabinet_jobs id=133；
- 测试：门控矩阵 4 用例（未启用不扫/正常免单+告警/防薅跳过/幂等返 null 不告警）+ 看护阈值表全量绿；两道接线门禁绿（31 托管任务全对齐）。

1. 默认阈值 72h 是否合适（或 48h）？
2. 单用户防薅窗口（7 天 ≤3 次）数值口径；
3. 首发是否直接在 dev 打开开关观察一周再上 production。
