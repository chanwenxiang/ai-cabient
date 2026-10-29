# P1 基础台账（2026-10-28 · UAT 全面测试铺量收口）

判定库：`aicabinet_fulltest_1023b`（UAT 隔离栈 compose project `aicabinet-uat`），
trade-service 镜像 tag `g95b-312bc970`（提交 `312bc970`）。
计数快照：`.tmp/full-test-20261023/artifacts/g9-7-counts-20261028.txt`（`drivers/zz-g97-counts.sql`，只读）。

> 口径：本台账只记录**可复算的库内计数 + 真实端点判定结果**。SEED 行只用于证明列表页能渲染
> 26+ 行，**不当业务真值**；`payment_operation` 由 G9-3/G9-4 的合成渠道流水与真实端点共同形成。

## §1 主数据

| 表 | 行数 | 说明 |
|---|---|---|
| `device_info` | 27 | 含 G9-6 SEED 15 台（`ops_remark='G9SEED'`）+ G9-5b 4 台 + 既有 |
| `user_info` | 148 | 消费者号段全部 < 1e8；运营号段 ≥ 1e8 |
| `sku_catalog` | 9 | 真柜在售 4 款 + 水位/苹果等负例 SKU |
| `merchant` | 16 | |
| `warehouse` | 4 | 全部 ACTIVE（月结 4 仓） |
| `device_slot` | 80 | |

## §2 交易与资金

| 项 | 数量 | 备注 |
|---|---|---|
| `cabinet_order` | 85 | PAID 62 / REFUNDED 13 / PARTIAL_REFUNDED 4 / CANCELLED 4 / PENDING 1 / DISPUTED 1 |
| `shopping_session` | 108 | |
| `order_revenue_split` | 64 | LEDGER_ONLY 48 / ACCRUED 6 / WECHAT_SUBMITTED 4 / SETTLED 2 / VOIDED 4 |
| `payment_operation` | 206 | 合成渠道流水 + 真实端点流水；对账重算口径未造 SEED |
| `merchant_withdraw_request` | 7 | G9-4：PAID 5 / REJECTED 1 / PENDING 1 |
| `balance_refund_request` | 27 | G9-4 真实端点 6 + G9-6 SEED 21 |
| `invoice_request` | 3 | G9-4：ISSUED 2 / REJECTED 1 |

## §3 纯台账列表页（每页 ≥26 行，真实端点实读）

| 页面 | 总数 | 来源 |
|---|---|---|
| 报修单 | 27 | SEED |
| 用户反馈 | 27 | SEED |
| OTA 升级上报 | 27 | SEED |
| 商户费用台账 | 27 | SEED |
| 设备租金账单 / 流量费账单 / 场地租金账单 | 27 / 27 / 27 | SEED |
| 线长提现申请 | 27 | SEED |
| 采购退货 / 整仓盘点 / 仓库调拨 / 报损索赔 | 27 / 28 / 27 / 27 | SEED |
| 余额退款申请 | 27 | SEED 21 + 真实 6 |
| 争议工单 | 44 | G9-5c 真实端点 + G9-6 真实消费者申诉 2 |
| 平台资金账户 / 流水 | 3 / 27 | SEED |

**两张「假列表」不铺**（G9-0-GAP §3-C 口径订正）：
`user_realname_auth` 恒 0 行是 `WithdrawEligibilityService` 明示的隐私设计（实名委托第三方）；
`user_login_log` 全仓无任何 Java/前端读写方（死表）。两者都没有 ops 列表页/API。

## §4 月结（真实端点，4 仓 × 2026-10 / 2026-11）

8 张单：APPROVED 1 / DRAFT 7。已验证账期窗口（上月 26 日 00:00 ~ 本月 26 日 00:00，左闭右开）、
期初结转（`PERIOD_CLOSE`，2026-11 期初=2026-10 草稿实盘）、明细恒等式（不含损耗列）、
DRAFT 重生成不叠加、APPROVED 重生成 409、分仓月结一张表。

## §5 G9-5 业务样本（真实端点 + 负例）

| 子批 | 样本量 | 判定 |
|---|---|---|
| G9-5a 采购 | 采购单 12（本轮新增 7，含 1 条被拦未落库） | **56 checks / 0 failed** |
| G9-5b 补货 | 补货任务 42 / 路线 40（4 台柜） | **194 checks / 0 failed** |
| G9-5c 争议 | 44 单（RESOLVED 38 / OPEN 3 / CLOSED 3；`AUTO_WAIVE` 5） | **152 checks / 0 failed** |

## §6 ENV-BLOCK（不得伪造）

- 真柜识别上报 ≥10 / `jiangyi_recognition_report`：UAT 无厂商凭据与在线真柜，**未产生真机证据**；
  G9-2 的 8 类分支由 BOUND 库内夹具使能窗口，已标注不计入真柜样本。

## §7 已知口径与观察（非缺陷）

1. `cabinet_order.paid_amount_cents` 首次扣款时冻结，**争议改单一律不改写**（V384/CB-057）；
   分账基数与佣金同源（AU-182）⇒ ADJUST 加价部分不进分账，谓「已知形态·佣金基数偏小」。
   `RevenueSplitService` 行内注释「增额补扣会随之上升」与 V384 矛盾，建议只订正注释（P3，待拍板）。
2. `shopping_session.dispute_flag` 只有迁移定义（V68 建列 + V237 注释），全仓无 Java/SQL/前端读写方 ⇒ 死列（P3）。
   库里现有 25 行 `true` 全部来自测试驱动的会话夹具（`ensureSession` 直接写），不是产品行为。
3. 自动免单单用户 7 天窗口上限 3 次实测生效（第 4 次跳过且单据留 OPEN，无告警/失败）。
