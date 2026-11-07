# UAT 补充测试计划（覆盖 87 空表中的 45 张「流程未触发」表）

> 目标：每张空表通过真实业务流程产生数据并断言，消除「业务测了但表没数据」的覆盖缺口。
> 环境：uat 库继续使用；新增前置按各批说明造数。剩余 42 张空表 = B 类开门柜特化（22，无数据=正确）+ C 类死表候选（2）+ 既有主体表的空态（18，如 coupon 已核销行之外的空闲行——不计）。
> 执行方式：API 链 + 库断言（与主轮一致）；预计 **6 批 × 0.5 天**。

## 批 S1 · 出库/领货链（3 表：warehouse_outbound/_line + 补货联动）
- 前置：补货任务（R3 已有 route/task）
- 流程：ops 建出库单 → pick → ship（领出）→ 商户端确认领货 → 上柜（RESTOCK applied）
- 断言：warehouse_outbound/_line 状态机、warehouse_inventory 台账变化、movement 流水
- 措辞门禁回归：界面动词=领货/上柜（selfpickup 门禁）

## 批 S2 · 采购退货 + 应付冲减（2 表：purchase_return/_line）
- 前置：PO=3 已收货 10 件（R3 遗产）
- 流程：purchase_return 建（退 2 件）→ 审批 → 冲减应付
- 断言：purchase_return/_line 行、supplier_payable_entry RETURN 行、应付余额 1200→960

## 批 S3 · 货位管理（2 表：warehouse_bin/_stock）
- 流程：建货位（PUT /warehouse/bins）→ bin 入库（inbound）→ bin 间 move
- 断言：warehouse_bin_stock 数量、BIN_INBOUND 流水；**MA-109 回归**（显式传无主仓应被闸）

## 批 S4 · 线路+佣金+线长提现（5 表：line_route/_member/device + line_commission_daily + line_withdraw_request + staff_commission_daily）
- 前置：R2-C 线长 id=1 在；补线路（create 键名 routeName/managerId 必填——MA-108 回归）
- 流程：建线路→加成员（佣金 500bps）→绑柜→**造昨日 GMV**（对 EG 柜补一笔 PAID 订单挂线路设备）→ 触发佣金（周期扫描或等）→ staff_commission_daily + line_commission_daily → 线长提现申请（≥¥10）
- 断言：佣金=GMV×bps、commission_clawback（**对订单做全额退→佣金冲回行出现**——顺带覆盖 clawback 表）
- 注意：扣佣金入钱包后，钱包余额对账

## 批 S5 · 租赁全链（4 表：device_rental_deposit/_event/_bill + monthly bill）
- 前置：无（押金登记即可）
- 流程：POST deposits（押金+月退）→ deduct → settle → bills/generate → mark-paid → void
- 断言：押金三态（HELD→SETTLED）、账单 PAID、钱包 debit 流水、appendLog

## 批 S6 · 场地租（3 表：site_contract/rent_bill/split_rule）
- 流程：PUT site-contracts/{deviceId}（月租+起止）→ rent-bills/generate → pay → split_rule 整替（Σbps=10000 校验）
- 断言：账单行、appendLog、分摊规则

## 批 S7 · 报修/工单/反馈（4 表：device_fault_report/device_fault_order/repair_ticket(_event)/user_feedback）
- 流程：consumer 报修（feedback 或 fault-report）→ ops 建 repair_ticket → batch-assign → transition 关单（备注 ≤255 字=MA-110 回归）；consumer feedback → reply → **MA-111 回归**（确认 delete 端点行为未变）
- 断言：双表分线（CB-077）、事件留痕、appendLog

## 批 S8 · 发票（1 表：invoice_request）
- 前置：R1 订单已全额退（INVOICEABLE 状态需 PAID）→ 用商户端新订单或改用「驳回」路径：先造 PAID 单（复用 R3 采购到货上架+开门）或对既有 recharge 单
- 简化：**对可开票订单走申请→驳回**（驳回也是状态机一环）；开具留给有真实开票需求的轮次
- 断言：invoice_request 状态机、MA-092 回归（issue/reject 现已有审计——修复后补验）

## 批 S9 · 凭证/文件（1 表：file_attachment）
- 流程：盘点凭证上传（multipart，走 S 批盘点单）→ streamStocktakeEvidence 回读
- 断言：**MA-068 回归**（商户 A 传商户 B 柜机应 403——修复后归属校验在位）

## 批 S10 · 外部导入（4 表：external_import_batch/external_channel_flow/external_product(_mapping)）
- 流程：模板已有（7 行种子）→ 上传 CSV preview → commit（**MA-101 回归**：同渠道同账期第二次 commit 应 409）→ 商品映射绑定
- 断言：external_channel_flow 行、V435 唯一索引兜底、月结 sum 口径

## 批 S11 · 商户资料四件（4 表：merchant_ops_config/merchant_tax_profile/merchant_subscribe_pref/merchant_notify_log）
- 流程：PUT ops-config / PUT tax-profile（**MA-076 回归**：审计留痕应在）→ subscribe_pref 设置 → notify 发送
- 断言：行落库 + appendLog

## 批 S12 · 设备凭据/OTA/温控（4 表：device_http_credential/device_mqtt_credential/device_temp_plan(_entry)/ota 相关）
- 流程：**MA-117 回归**（签发凭据对不存在设备应 404）；真设备签发→吊销；OTA 发布→unpublish（**MA-115 回归**：归因审计应在）；temp_plan 创建/应用
- 断言：凭据行、OTA_RELEASE 审计、温控计划下发

## 批 S13 · 资金池（2 表：platform_fund_account/_flow）
- 流程：建账户→recharge（**MA-093 回归**：负数应 400）→sweep→adjust→月度归集核对
- 断言：流水类型语义三闸（RECHARGE/SWEEP/ADJUST）

## 批 S14 · 用户域四件（4 表：user_feedback/user_notify_pref/user_blacklist/ops_2fa_recovery_code）
- 流程：consumer feedback 提交→ops 回复；notify_pref 设置；黑名单 add→**remove（MA-097 回归：留痕应在）**；2FA 绑定→recovery code→关闭（**MA-072 回归**：关闭留痕应在）
- 断言：各表行+审计

## 批 S15 · 短信/2FA（2 表：sms_verification_code/ops_2fa_recovery_code）
- 并入 S14 执行

## 批 S16 · 催缴/补偿/数据变更（3 表：unpaid_dunning_record/compensation_task/data_change_log）
- 造 PENDING 欠款（低余额结算）→ 催缴阶梯触发；AdminDataManage 改白名单表数据 → data_change_log
- 断言：催缴阶梯、变更日志 before/after

## 批 S17 · 部分退+余额退款（2 表：balance_refund_allocation + 部分退路径）
- 前置：新 PAID 单（复用 R3 采购到货上架后开门购买）
- 流程：部分退 1 件（预估==写侧断言）→ 余额退款申请→审批→原路退
- 断言：balance_refund_allocation 行、refunded_cents、**MA-129 剩余合计行**（consumer 读面）

## 批 S18 · 依赖特定前置的延后表（5 表：payscore_order/commission_clawback/jiangyi_*/pull_off_task）
- payscore_order：需支付分 mock 创单链（开门走 WECHAT 渠道+payScoreEnabled）
- commission_clawback：并入 S4（线长佣金后退款）
- jiangyi_recognition_report / jiangyi_model_deployment：将邑模拟器 WS 保活修复后
- pull_off_task / 效期：造临期批次（R3 收货时 expiryDate 设近未来即可触发）

## 执行顺序建议

S2 → S3 → S1 → S7 → S11 → S14/S15 → S13 → S9 → S8 → S5 → S6 → S4 → S10 → S12 → S16/S17 → S18
（先无依赖/已有前量的，再依赖链，最后特定前置）

## 完成判据

- 45 张表全部 count>0（或明确「该流程在当前产品形态下无数据」拍板记录）
- 每批：前后快照对账 + 断言 SQL + 缺陷领号（MA-164 起）
- 全部完成后更新 table-coverage-final.md 三分类，并出增补总结
