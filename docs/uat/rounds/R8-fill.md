# UAT R8 · 空表批量填充轮（2026-11-07）

> 目标：对 UAT-SUPPLEMENT-PLAN 里剩余「流程未触发」空表，用真实 API 链填充并断言。
> 全程真实链：consumer token 经 automator→真实 wx-login（code2session）续期；模拟柜 MQTT 实拍开门；
> admin/finance 双角色真审批流；每步库断言。
> 结果：**181 表触达数 108 → 123（+15）**；新缺陷 0；发现数据一致性核查器工作正常并抓到 UAT 造数遗留 6 条（非产品缺陷，见 §3）。

## 1. 本轮新触达 15 张表（空→有数，全链真实操作）

| # | 表 | 链路 | 断言 |
|---|----|------|------|
| 1 | user_feedback | consumer `POST /api/v2/feedback`（键名=feedbackType） | 行=1 |
| 2 | user_notify_pref | consumer `PUT /member/notifications/prefs` | 行=1 |
| 3 | device_fault_report | consumer `POST /devices/{id}/fault-report` | 行=1，**且自动生成 repair_ticket 行=1**（CB-077 双表分线在真链生效） |
| 4 | device_http_credential | admin 对 12 位门柜 `500909625160` 签发 | 行=1（MA-119 回归：14 位将邑号被正确 400「deviceId 须为 12 位数字」——门柜/将邑凭据体系隔离正确） |
| 5 | device_mqtt_credential | 同上 | 行=1 |
| 6 | ops_2fa_recovery_code | admin `GET /rbac/me/two-factor/enroll` | 行=8 |
| 7 | site_contract | admin `PUT /site-contracts/{deviceId}`（键名=siteName+monthlyFeeCents） | 行=1 |
| 8 | site_rent_bill | `POST /site-contracts/{contractId}/rent-bills/generate` | 行=1 |
| 9 | device_temp_plan | admin 温控计划 PUT | 行=1 |
| 10 | announcement | admin 建+publish | 行=1 |
| 11 | invoice_request | consumer `POST /orders/{id}/invoice` → admin reject | 行=1，状态 OPEN→REJECTED（**MA-092 驳回审计回归 ✓**） |
| 12 | data_consistency_record | admin `POST /consistency/run` | 行=7（6 FAIL+1 FIXED） |
| 13 | balance_refund_request | consumer `POST /account/balance-refunds`（冻结 2500 分） | 行=2，FREEZE op COMPLETED |
| 14 | balance_refund_allocation | **双人审批流**：超级管理员(运营审核)→财务(13900000002, 财务复核) | 行=1：recharge_order 1793971155…\|2500\|WECHAT；余额 24750→22250；FREEZE→BALANCE_REFUND→RECHARGE_REFUND 三 op 全 COMPLETED（**原路退回充值单分摊 ✓**） |
| 15 | cabinet_order PAID 新单 | 充值(5000)→session→模拟柜开门 SHOPPING→超时关门 DISPUTED→运营 CONFIRM（SKU-DEMO-001×2）→**PAID** | 四列恒等式 700−0−200−0=500 ✓（活动价 CB-082 在 sim 链上生效） |

**部分退活验（R17 矩阵 E2E 的线上复证）**：consumer `POST /orders/{id}/refund` `{reason, lines:[{skuId,quantity:1}]}` →
订单 PARTIAL_REFUNDED，refunded=250=500÷2 ✓，REFUND op(BALANCE) COMPLETED，余额 24500→24750。

## 2. 关键修复回归（本轮新增实证）

- **MA-092**（发票驳回审计）✓
- **MA-119**（凭据签发设备存在性/位数校验）✓
- **MA-100 家族部分退**（consumer lines 部分退路径线上复证）✓
- 余额原路退**双人审批门**（运营→财务，缺一步则单据停在 PENDING_REVIEW——G9-4 设计按预期工作）✓

## 3. 一致性核查器实测（data_consistency_record 7 行）

`POST /api/v2/ops/admin/consistency/run` 对故意 +777 的订单金额立即抓到并记录；还原后该条自动 FIXED。
剩余 6 条 FAIL 全部为 **UAT 造数期直改 SQL 的遗留**（device_sku_inventory 聚合数与 device_sku_lot 批次合计不同步、
拉货批次 UAT-EXP-01 流水差 1、一条 R5 期无单争议交叉引用），非产品代码缺陷；已逐条核对成因为测试造数路径。
这同时证明核查器在真库上工作正常（生产部署前跑一次 run 可作为上线自检步骤，建议写进 DEPLOYMENT_CHECKLIST）。

## 4. 仍空表定性（较上版更新）

| 表 | 定性 |
|----|------|
| sms_verification_code | 短信通道 dev 未配真实发送商，sms-code 端点未落行——B 类（环境未触发） |
| data_change_log | 唯一写入点在 DataConsistencyService:179（fix 分支的条件未满足时为空）；fix#3 调用后仍 0，具体分支条件待源码核对——暂 B 类，不排除死代码候选 |
| inventory_write_off | 报损需匹配库存批次（当前报错 stock insufficient），参数形态待对 batchNo——可补 |
| device_fault_order | 消费者报修自动建的是 repair_ticket（正确分线）；fault_order 由设备侧自动故障上报触发——B 类 |

## 5. 环境备忘（后续轮复用）

- consumer token 续期脚本：`.tmp/uat-r8-refresh-token.js`（automator `callWxMethod('login')`→真 wx-login→写回三键 storage）
- 财务角色=13900000002（user_id 100000007）；超管=13900000001（100000001）
- 订单金额列：gross_amount_cents/member_discount_cents/activity_discount_cents/coupon_discount_cents/paid_amount_cents
- 模拟柜购物窗 120s，edge-results 必须在**关门之后**提交（R11 经验复证）
- GBK 坑：python 脚本必须 `sys.stdout.reconfigure(encoding='utf-8')`；subprocess 全部加 `encoding='utf-8', errors='replace'`
