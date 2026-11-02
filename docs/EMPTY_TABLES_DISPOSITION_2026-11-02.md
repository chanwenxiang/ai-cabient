# 空表逐张处置清单（2026-11-02）

> **范围**：本机 dev 库 `aicabinet`（Postgres，`127.0.0.1:15433`）中 **0 行**的 75 张业务表。
> **数据源**：`pg_class` + 每表 `count(*)` 精确统计（**不是 `pg_stat_user_tables` 估算**）；代码证据取当前工作区 `services/trade-service/src/main/java` 主代码、Mapper、Controller、`scheduled_task` 注册表。
> **结论**：必须补种子 **0 张**；保留 **64 张**；建议废弃 **11 张**。
> **执行状态（2026-11-02，已执行）**：V429（补注释）、V430（标废弃）、V431（DROP 11 张）、V432（DROP `rate_limit_config`）均已由 Flyway 应用；库内 **179 表 / 64 张空表 / 0 张无表注释 / 0 个无字段注释**。
> **备份**：`.tmp/backup_deprecated_tables_2026-11-02.sql`（11 张表）与 `.tmp/backup_rate_limit_config_2026-11-02.sql`（5 行种子配置）。
> **服务器测试环境（2026-11-02）**：`120.25.246.179` 已部署 HEAD `18ae1760`，Flyway **V433**（含 peer 的 V433 敏感配置脱敏）；179 表 / 0 表注释缺口 / 0 字段注释缺口；trade healthy，admin/API 200。
> **服务器部署注意**：V431/V432 已在 dev 与服务器测试环境执行；再部署仍须全库备份 + 逐表 `count(*)`（迁移内行数守卫，形态不符会拒绝删除）。
> **仓库/回滚注意**：V429–V433 已应用（服务器 V433；本地 dev 也已 433）；不要用 `pre-v432`/`pre-v431` 旧镜像直接连当前库（Flyway 会因缺少已应用迁移而 validate 失败）。备份不含 `flyway_schema_history`；完整回退需从完整库备份恢复。

---

## 0. 一页结论

| 处置 | 张数 | 含义 |
|---|---:|---|
| **补种子（必须）** | **0** | 没有“缺种子就起不来”的表 |
| **保留** | **64** | 分「需后台配置 / 任务批处理 / 事件触发 / 待接线 / 刻意不落库」 |
| **废弃（已执行）** | **11** | 2026-11-02 已由 V431 DROP；备份见文件头 |
| 合计 | **75** | 与删除前库内 0 行表数一致 |

> 执行后：**191 → 179 表；空表 75 → 64；表注释缺口 22 → 0；字段注释缺口 463 → 0**。
> 另：旧 B1 的 `rate_limit_config`（5 行种子配置，零代码引用）已由 V432 删除，不在 75 张空表内。

> **先纠正一个易误判点**：`external_import_template` **不是空表**（7 行，`R__seed_external_import_templates.sql` 已生效）。空的是 `external_import_batch` / `external_product` / `external_product_mapping` / `external_channel_flow`，它们要等真正导入一次才有数据。

---

## 1. 口径与证据

### 1.1 空表口径

- 表：`public` schema 下 `relkind='r'` 的基表，共 **191** 张。
- 空：对每张表执行 `count(*) = 0`；包含软删除行，所以「空」是真正一行都没有。
- 排除：`flyway_schema_history` 本身不参与业务判断（当前 437 行）。

### 1.2 代码证据

| 证据 | 取法 |
|---|---|
| 实体 | `@TableName("表名")` |
| 写入 | Mapper 字段上的 `insert/insertBatch/update/updateById/delete/save/saveBatch/upsert` |
| 读取 | Mapper 字段上的 `select*/find*/count/exists/query*` |
| 入口 | Controller 的 `@RequestMapping` 基路径，以及 Controller 对 Service 的引用 |
| 调度 | `scheduled_task` 注册表 + `@Scheduled` / `@XxlJob` 类 |
| 种子 | `db/seed/R__*.sql` 的 `INSERT INTO` |

### 1.3 口径局限（如实标注）

1. 只按主代码整词匹配；测试、迁移、旧文档、构建产物不计入「活引用」。
2. 「有写入方法」不等于「线上一定被调用」；`ad_revenue_daily` 就是反例（有 `AdRevenueSyncService`，但无任何生产调用方/调度）。
3. 本清单是**当前 HEAD + 本机 dev 库**的快照；若 peer 分支有未合并改动，结论需重跑。
4. 「废弃」只代表**代码层面无活引用/已被替代**，不代表生产库一定 0 行；删表前必须再查生产。

---

## 2. 已执行废弃（11 张，V431）

> 2026-11-02 已由 `V431__drop_deprecated_tables.sql` 执行 DROP；11 张表删除前均 0 行，备份见文件头。以下依据保留作为决策留痕。

> 处理口径：**先标记 deprecated，再删代码引用，最后单独出 DROP 迁移**。本批不执行删除。

| 表 | 依据 / 删除前置 |
|---|---|
| `data_discrepancy` | 0 代码引用；一致性巡检已改用 `data_consistency_record`（146 行）。旧清库脚本仍列此表，删前先改脚本。 |
| `data_quality_check` | 0 代码引用。旧清库脚本仍列此表，删前先改脚本。 |
| `idempotency_key` | `IdempotencyService/Mapper` 无生产调用方（仅单测）；充值幂等已走 `recharge_order` / `payment_operation.idempotency_key` + Redis 锁。删表前先删 3 个类与测试。 |
| `payscore_contract` | 0 代码引用；合同号实际存 `user_info.payscore_contract_id`（`PayScoreService` 读写）。 |
| `promotion_device` | 表注释已标废弃；活动范围改 `promotion_activity.device_scope`。仅 `DeviceIdRenameService` 的改名清理清单还引用，删前移出。**注：2026-09-20 旧定策曾判为合法占位，本文件建议改判废弃，需重新拍板。** |
| `rate_limit_record` | 0 代码引用；限流配置表 `rate_limit_config` 仍是活的（5 行）。与 2026-09-20 旧定策 B1「应删」一致。 |
| `revenue_share_detail` | 仅 `ConsumerE2ETest` 字符串引用，无写入方；分账明细已用 `order_revenue_split`（5 行）。旧清库脚本仍列此表。 |
| `revenue_share_rule` | 0 代码引用；分账已走 `order_revenue_split`。旧清库脚本仍列此表。 |
| `sys_oper_log` | 无写入方；真实审计是 `admin_audit_log`（318 行）。仅后台数据管理保护清单引用。与 2026-09-20 旧定策 B1「应删」一致。 |
| `user_login_log` | 0 代码引用。若合规确实要登录留痕，应改判「保留 + 补写入」；否则废弃。旧清库脚本仍列此表。 |
| `wechat_binding` | 无写入方；消费者 openid 存 `user_info.wx_openid`（`AuthService`）。仅后台数据管理保护清单引用。旧清库脚本仍列此表。 |

### 2.1 与《孤儿表定策》（2026-09-20）的关系

- 旧文件 [ORPHAN_TABLE_DISPOSITION.md](ORPHAN_TABLE_DISPOSITION.md) 的范围是「Flyway 建表中代码零引用的表」，不是「0 行表」；本次范围是后者。
- 旧文件的 B1 三张已全部删除：`sys_oper_log`、`rate_limit_record` 随 V431；`rate_limit_config` 随 V432（5 行种子配置，零代码引用，不在 75 张空表内）。
- 旧文件把 `promotion_device` 判为「合法占位、保留」；本文件基于「活动范围已由 `promotion_activity.device_scope` 取代」改判废弃，并已于 2026-11-02 执行 DROP。
- 旧文件的 B2「其余零引用表保留为占位」是当时的默认处置；本次逐表复核后，把其中确有替代机制的表升级为废弃并执行；旧定策的占位状态作废。

---

## 3. 保留（64 张）

### 3.1 需后台先配置/录入（22）

> 空着是「还没录」，不是缺陷；演示环境可补非资金种子。

| 表 | 入口 / 说明 |
|---|---|
| `ops_org_node` | 组织树（`OpsOrgController`），点位/区域配置的根。 |
| `ops_device_org` | 设备归组织，依赖先建组织树。 |
| `ops_user_route_scope` | 运营账号补货路线范围（`OpsRbacController`）。 |
| `line_manager` | 后台建线长档案（先建人再建线路）。 |
| `line_route` | 后台建线路（`OpsLineRouteController`）。 |
| `line_route_member` | 线路成员与提成规则。 |
| `line_device` | 绑定线长管辖设备。 |
| `line_promo_task` | 后台建推广/巡检任务。 |
| `slot_template` | 货道铺货模板（`SlotTemplateController`）。 |
| `slot_template_line` | 模板行随模板配置。 |
| `warehouse_bin` | 仓库货位配置（`OpsWarehouseController`）。 |
| `site_contract` | 点位合同（租金/分成规则的来源）。 |
| `site_rent_split_rule` | 合同分账规则。 |
| `machine_position_config` | 逐柜配点位费用/分成（`OpsMachinePayeeBillController`）。 |
| `merchant_ops_config` | 商户运营参数。 |
| `merchant_tax_profile` | 商户开票抬头，开票前录入。 |
| `device_temp_plan` | 后台建温控计划（`OpsDeviceEnvController`）。 |
| `device_temp_plan_entry` | 温控计划时段条目。 |
| `device_http_credential` | 后台逐设备签发；**不应补种子**。 |
| `device_mqtt_credential` | 后台逐设备签发；**不应补种子**。 |
| `external_product_mapping` | 导入后在后台把外部商品挂到我方 SKU。 |
| `user_blacklist` | 运营拉黑名单。 |

### 3.2 任务批处理（9）

| 表 | 触发 |
|---|---|
| `line_commission_daily` | `line-commission` 任务，线长佣金日批。 |
| `staff_commission_daily` | 同上，补货员佣金日批。 |
| `commission_clawback` | `LineCommissionJob` 按退款冲回。 |
| `machine_payee_bill` | `machine-payee-bill` 机器账单出账。 |
| `merchant_fee_ledger` | 商户日结扣款成功后写行。 |
| `device_data_fee_bill` | `ops-fee-bill-monthly` 周期费用月结出账。 |
| `site_rent_bill` | 同上，场地月租出账。 |
| `compensation_task` | `compensation-process` 补偿/分账重试。 |
| `data_change_log` | `data-consistency` 巡检写变更日志。 |

### 3.3 事件/业务触发（31）

| 表 | 触发 |
|---|---|
| `ad_campaign_device` | `AdCampaignService` 保存投放计划时写设备范围。 |
| `ad_play_event` | 柜机播放上报时写入。 |
| `balance_refund_allocation` | 余额退款审核后按充值单分摊。 |
| `device_env_reading` | 设备温湿度上报。 |
| `device_fault_report` | 故障上报。 |
| `device_lifecycle_event` | 设备状态变更自动写。 |
| `dispute_message` | 争议工单沟通。 |
| `external_channel_flow` | 导入批次确认后聚合。 |
| `external_import_batch` | 渠道对账导入批次。 |
| `external_product` | 外部商品首次出现建档。 |
| `inventory_write_off` | 报损/核销。 |
| `invoice_request` | 用户申请开票。 |
| `jiangyi_model_deployment` | 将邑模型下发审计。 |
| `line_withdraw_request` | 线长发起提现。 |
| `merchant_notify_log` | 商户消息推送。 |
| `ops_2fa_recovery_code` | 运营开通 2FA 时生成。 |
| `ota_device_report` | 设备 OTA 上报。 |
| `purchase_return` | 采购退货。 |
| `purchase_return_line` | 随采购退货单。 |
| `repair_ticket` | 运营/设备异常创建维修工单。 |
| `repair_ticket_event` | 随维修工单事件。 |
| `supplier_payable` | 收货生成应付。 |
| `supplier_payable_entry` | 应付 RECEIVE/RETURN/OPENING 流水。 |
| `supplier_payment` | 供应商付款。 |
| `system_config_history` | 系统配置每次写操作留痕。 |
| `unpaid_dunning_record` | 欠款关单时累计催缴阶梯。 |
| `user_feedback` | 用户提交反馈。 |
| `user_notify_pref` | 用户修改通知偏好。 |
| `warehouse_bin_stock` | 货位库存随入/出/移更新。 |
| `warehouse_inbound` | 采购收货写入库单。 |
| `warehouse_inbound_line` | 随入库单。 |

### 3.4 待接线（1）

| 表 | 说明 |
|---|---|
| `ad_revenue_daily` | `AdRevenueSyncService` 只有单测引用，无生产调用，`scheduled_task` 里也没有广告收益任务。**接调度前等于死表**；要么补接线，要么改判废弃。2026-11-02 复核后保持待接线，不删；待产品决定是否接调度/入口。 |

### 3.5 刻意不落库（1）

| 表 | 说明 |
|---|---|
| `user_realname_auth` | `WithdrawEligibilityService` 明确不存证件号，永远 0 行是隐私设计，**不要补种子**。 |

---

## 4. 补种子

### 4.1 必须补：0 张

没有“缺种子就起不来”的表。所有配置类表都有后台入口；设备凭据表必须逐设备签发，不能 seed。

### 4.2 可选演示种子（非资金类，按组补）

> 目的只是让演示页面/流程不空；补之前先确认使用哪些既有 `user_info`、`device_info`、`merchant` ID，保证跨表一致。建议写成 `db/seed/R__seed_demo_empty_tables.sql`（幂等 `ON CONFLICT DO NOTHING`），不要散落一次性 SQL。

1. 组织：`ops_org_node` → `ops_device_org`
2. 线长：`line_manager` → `line_route` → `line_route_member` → `line_device`
3. 铺货模板：`slot_template` → `slot_template_line`
4. 场地：`site_contract` → `site_rent_split_rule`
5. 场地方计费：`machine_position_config`
6. 温控：`device_temp_plan` → `device_temp_plan_entry`
7. 仓储：`warehouse_bin`
8. 商户配置：`merchant_ops_config`、`merchant_tax_profile`
9. 运营配置：`ops_user_route_scope`
10. 非资金单据/名单：`user_blacklist`、`user_feedback`、`repair_ticket`、`line_promo_task`

### 4.3 禁止直接 seed 的资金/库存表

`site_rent_bill`、`machine_payee_bill`、`supplier_payable`、`supplier_payable_entry`、`supplier_payment`、`line_commission_daily`、`staff_commission_daily`、`commission_clawback`、`line_withdraw_request`、`merchant_fee_ledger`、`device_data_fee_bill`、`invoice_request`、`balance_refund_allocation`、`inventory_write_off`、`purchase_return`、`warehouse_inbound`。

> 这些必须走真实流程，或使用明确标注的演示单；直接 INSERT 会污染对账/库存口径。

---

## 5. 建议动作

1. **废弃（dev 已完成）**：代码引用已清；V431 已应用。生产部署前按第 5 条复核；迁移头已带 `MIGRATION_REVIEWED: yes` / `LOCK_RISK` / 备份回滚说明。
2. **演示种子**：按 §4.2 分组补非资金种子；禁止 seed §4.3。
3. **`ad_revenue_daily`**：2026-11-02 复核后保持待接线，不删；待产品拍板是否接调度/入口。
4. **旧清库脚本（历史证据）**：`docs/uat-screenshots/2026-09-12/wipe-business.sql` 仍列出已 DROP 的表，已与当前 schema 不一致；勿直接重跑，若要复用先删掉这些表名。
5. **生产复核（仍未做）**：V431 的 0 行守卫会在生产任一表 >0 行时 fail-loud；上线前仍须逐表 `count(*)` 并保留备份。

---

## 6. 复现

### 6.1 空表清单（精确行数）

```sql
DO $$
DECLARE r record;
BEGIN
  CREATE TEMP TABLE rc(tbl text, n bigint);
  FOR r IN
    SELECT c.relname
    FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
    WHERE n.nspname = 'public' AND c.relkind = 'r'
  LOOP
    EXECUTE format('INSERT INTO rc SELECT %L, count(*) FROM %I', r.relname, r.relname);
  END LOOP;
END $$;
SELECT tbl FROM rc WHERE n = 0 ORDER BY tbl;
```

### 6.2 代码引用

- 主代码：`services/trade-service/src/main/java`（排除 `target/`、`/test/`）。
- 表名/实体名整词匹配；Mapper 字段级读写方法见 §1.2。
- 调度：`scheduled_task` 表 + `@Scheduled` / `@XxlJob` 类。

---

## 7. 变更记录

| 日期 | 变更 |
|---|---|
| 2026-11-02 | 初版：75 张 0 行表逐张处置；必须补种子 0、保留 64、建议废弃 11。未改库、未删表、未 seed。 |
| 2026-11-02 | 第一批：`idempotency_key` 代码下线（删 5 个文件）；V430 写好 11 张表废弃注释（未应用）；支付/幂等 4 个测试类 45/45 绿。 |
| 2026-11-02 | 第二批准备（当时未执行）：11 张表已 `pg_dump` 到 `.tmp/backup_deprecated_tables_2026-11-02.sql`；V431 DROP 脚本放 `.tmp/V431__drop_deprecated_tables.sql.pending`，未进 migration 目录；同日随后执行，见下一行。 |
| 2026-11-02 | 第二批执行：镜像重建后 Flyway 应用 V429/V430/V431；11 张表 DROP；库内 180 表 / 64 空 / 0 注释缺口；trade-service 启动 20.6s、health 200、无 ERROR。 |
| 2026-11-02 | 追加：V432 删除零引用的 `rate_limit_config`（5 行种子配置，已备份）；库内 179 表 / 64 空 / 注释缺口 0。 |
| 2026-11-02 | 服务器测试环境 `120.25.246.179` 部署 HEAD `18ae1760`；Flyway 429→433、179 表、注释缺口 0、trade healthy、admin/API 200；部署前全库备份 `/data/backups/aicabinet-pre-v432-20261102-171420.dump`。 |
