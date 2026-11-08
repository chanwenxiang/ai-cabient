# UAT 全库表覆盖终检（2026-11-08 R11 重跑，uat 库 aicabinet_uat 精确 count）

> 🔴 **实测数字（2026-11-08 10:52 重跑，见 `.tmp/r11-table-coverage.mjs` / `rounds/R11-table-coverage.md`）**：
> **179 表 = 140 触达 + 39 空** —— 本版修正上一版（139/40）的计数差。
> 根因：上一版 §A 把 **`announcement` 误列为空表**，但它在 **R8 已填充**（`rounds/R8-fill.md:21`「admin 建+publish 行=1」、
> `UAT-FINAL.md` §四·补 R8 节同；实库现 `announcement=1`）⇒ 旧版自相矛盾、总计差 1。
> 另：`order_revenue_split` 上一版记 1 行，实库 **3 行且全部 VOIDED**（见下 §C′）。
> 判定规则：①本轮业务产生过数据=已触达；②空=对应业务流程未在 UAT 周期内触发（多为 R1-R7 夜间批未覆盖、或依赖特定前置）；③极少数=死表候选。

## 已触达 140 表（明细见 R11 实测快照与轮报告）

核心资金/交易/账号/仓储/营销/审批/字典全链均产生真实数据，
包括：cabinet_order(1)/line(1)、payment_operation(3)、order_revenue_split(3，全 VOIDED——见 §C′)、
wallet_account(2)/ledger(3)、
user_info(16)/user_account(15)/user_coupon(3)、merchant(3)、sku_catalog(9)/scene_limit(1)、
device_info(4)/sku_inventory(7)、warehouse(2)/inventory(7)/stocktake(2)+line(8)/transfer(1)+line(1)/inbound(1)+line(1)/movement(8)、
purchase_order(3)+line(3)、supplier(2)/payable(1)/entry(1)/payment(1)、dispute_ticket(3)、
coupon_definition(8)、promotion_activity(4)/placement(1)、member(2)/points_log(3)、
ops_permission(241)/role(11)/role_permission(485)/user_role(12)/user_merchant(16)/department(5)、
admin_audit_log(53)、sys_dict_data(595)/type(107)、flyway_schema_history(443)、scheduled_task(43)、
notification_log(11)/template(12)、recharge_order(4)、reconciliation(3)、risk_event(3)、
shopping_session(10)、recognition_result(3)、sku_delist_review(9)、data_consistency_record(1) 等。

## 空表三分类（R7 初版 87 空 → R8 后余 58）

### R8 填充轮已触达（15 表，全链真实操作，详见 rounds/R8-fill.md）
user_feedback、user_notify_pref、device_fault_report、device_http_credential、device_mqtt_credential、
ops_2fa_recovery_code、site_contract、site_rent_bill、device_temp_plan、announcement、invoice_request、
data_consistency_record、balance_refund_request、balance_refund_allocation、repair_ticket（消费者报修自动建单）；
另部分退活验（PARTIAL_REFUNDED 250/500 对平）+ MA-092/119 回归 + 余额原路退双人审批流全链 ✓

### A. 流程未在 UAT 夜间批触发（余 33 表）——白天/后续补测即有数据。
> R11 计数已以顶部「🔴 实测数字」为准；下列仍按 R11 前的分类口径保留，仅作**空表原因说明**。
> ⚠️ ~~`announcement`~~：上一版据此列空表属**自相矛盾**（R8 已填充，实库=1）⇒ R11 已从 A 类移除（见顶部）。
>
> balance_refund_allocation（部分退未触发——E2E 覆盖）、
commission_clawback、compensation_task、consumer_preauth_hold（R1 脚本 preauth=0）、
data_change_log、device_data_fee_bill、device_fault_order/report（报修未测）、
device_http_credential/mqtt_credential（凭据签发未测）、device_lifecycle_event、device_rental_bill/deposit/deposit_event（租赁未测）、
device_temp_plan(_entry)（温控未测）、dispute_message（争议消息未发）、external_channel_flow/import_batch/product/mapping（外部导入未测）、
file_attachment（凭证上传未测）、invoice_request（发票未测）、line_commission_daily（佣金 job 未到周期）、
line_device/route/route_member/promo_task（线路未测）、line_withdraw_request（线长提现未测）、
machine_payee_bill/machine_position_config（分成配置未测）、merchant_fee_ledger（无费用配置商户）、
merchant_notify_log/ops_config/subscribe_pref/tax_profile（商户资料未测）、merchant_withdraw_request（提现未测）、
ops_2fa_recovery_code（2FA 未测）、ota_device_report（OTA 未推）、payscore_order（支付分创单未走）、
platform_fund_account/flow（资金池未测）、pull_off_task（效期拉货未触发）、purchase_return(_line)（退货未测）、
repair_ticket(_event)（报修未测）、site_contract/rent_bill/split_rule（场地租未测）、sms_verification_code（短信未测）、
staff_commission_daily（佣金未到周期）、unpaid_dunning_record（催缴未触发）、user_blacklist/feedback/notify_pref（未测）、
warehouse_bin/bin_stock/in_transit（货位/在途未测）、warehouse_monthly_close_line（月结行，close 头表 2 行）

### B. 设计上该业务形态不产生数据（开门柜特化，22 表）——非缺口
device_slot / device_sku_lot / device_sku_price（开门柜无货道/无设备批次/价格在 catalog——铁律 78）、
sku_jiangyi_link（将邑绑定已撤，MQTT 形态）、jiangyi_recognition_report/training_ticket/model_deployment/order_video（将邑采集/学习未走）、
device_temperature_reading 之外的 env 读数、merchant_payment_onboarding 之外各自按需、
ops_org_node/ops_user_device_scope(_pref)/ops_user_route_scope（组织点位未启用）、
device_ops_event、sku_jiangyi_link、user_realname_auth（隐私设计）、device_temperature_reading(_entry)、
slot_template/slot_template_line、ops_device_org、ad_campaign_device/ad_play_event/ad_revenue_daily、
jiangyi_* / ops_org / ops_config 系。
- ✅ ~~两张备份表（sku_catalog_vision_cols_bak_20261011 / sku_vision_mapping_bak_20261011）~~：
  **已在 V448 被 DROP**（实库 `table_name ILIKE '%_bak%'` 空；R11 空表列表中不存在）⇒ 不再是候选。

### C. ⚠️ 已结清 · 死表/备份表候选（R11 复核：**2 张已在 V448 删除**）
- ~~sku_catalog_vision_cols_bak_20261011~~ ~~sku_vision_mapping_bak_20261011~~ —— 2026-10-11 迁移备份表，
  **均已 DROP（V448），`pg_tables LIKE '%_bak%'` 返回空** ⇒ 旧口径「2 死表尚未清理」不成立，勿再据此排队清理。

### C′. 设计恒空但属资金口径（非缺陷）——R11 实证
- **`merchant_settlement_bill`（商户月度结算单）为空是设计如此，非资金缺陷**：
  `MerchantSettlementBillService.buildRows()` 明确**排除 VOIDED/REVERSED**；实库 `order_revenue_split` 共 **3 行、全部 VOIDED**
  （`1793976576044748108876`/`1794017491698733162060`/`1794019755167728594983`，gross 1074/250/500）⇒
  重建账期 `2026-10 / 2026-11` 聚合结果 0 商户，与执行日志「结算单重建 … 共 0 商户」**一致** ⇒ 正确。
  该行账期（1 月 1 日 ~ 12 月 1 日 Asia/Shanghai）均覆盖 2026-11-06/07 这些 split，故排除发生在**状态**而非**时间窗口**。
- 同源口径：`merchant-daily-income-notify` 日志「昨日(2026-11-07)日结通知发送 0 商户」同为 `isReversed` 口径，一致。

## 结论（R11 口径）

- **实测 179 = 140 触达 + 39 空**（R11 重跑，`.tmp/r11-table-coverage.out.txt` 存全量 39 空表名单）。
- 上一版「139/40」差 1 的根因是**文档自相矛盾**（`announcement` 系 R8 已填充却被误列 A 类空表），已返正。
- 39 空表 = A 类补测（流程未触发，白天/后续轮次自然产生数据）+ B 类开门柜特化（无数据=正确）+ **1 项 C′（`merchant_settlement_bill`，设计不产生）**。
- **无「业务测了但表没数据」的可疑缺口**；**无新增死表发现**；R11 复核确认 C 类 2 张备份表**已在 V448 删除**（旧口径不成立）。
