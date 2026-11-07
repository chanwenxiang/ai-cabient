# UAT R9 增补 · 参数配置 118 键 + 告警 34 类型 全量清点（2026-11-07）

> 背景：R9 主报告初版只验证了 system_config 表前 24 行（查询 head 截断所致漏测）。用户指正后全量清点：
> **表 seed 118 键**（98 个 = SystemConfigService 常量 + 20 个迁移直 seed）+ **告警类型 34 个**（ops_alert_type 16 + merchant_alert_type 18）。
> 方法：2 个探索代理对 98 键逐一映射消费方（类#方法:行号级）；本会话对可造数键做行为级验证（改值→观察消费方→还原）。

## 一、参数配置 118 键清点结论

| 分级 | 数量 | 说明 |
|------|------|------|
| **行为级实证** | ~40 | 见 §二（改值→消费方行为变化，实锤生效） |
| **读路径确认+同族实证** | ~77 | 消费方映射到 行号 级（双代理交叉），同族机制已实证；逐键造数边际收益低（fee/limit 家族 R5 主库提现计费全链实证过、email/webhook 渠道未配置=安全跳过已验） |
| **DEAD 死键** | 1 | `edge.driver.type`——不在 SystemConfigService 常量内，V315 迁移直 seed，全仓零消费方（「按 config 选驱动」代码从未实施）。**待随下批迁移清理** |

**零 DEAD（98 常量键）**；唯一前缀族键 `merchant.withdraw.fee_bps.{merchantId}` 为按商户费率覆盖（字符串拼接读取，运营台提现费率 Tab 写入）。

## 二、行为级实证清单（改值→观察→还原，全部 ✓）

**回显类（12）**：consumer.service_phone、merchant.service_phone、ops.support_email（两端 config 下发）、
ops.brand.title（/public/ops-branding）、merchant.charts.enabled、consumer.ad_banner/order_search/wx_ad/coupon_entry/
pay_channel_select/product_detail 六开关、consumer.wx_ad.enabled、ops.demo_data_banner（data-scope 翻转）

**硬闸类（14）**：
- `debt.block_open_on_pending`：PENDING 单开门 **412**；关闸放行
- `refund.self.max_cents=1`→400「上限为 ¥0.01」；`partial_enabled=false`→400「暂未开放按行自助退款」；`max_hours` 24h 409
- `recharge.max_cents=100`→400「单次充值不超过 ¥1.00」
- `recharge.bonus.percent=10`→**充 1000 分到账 1100**（余额 22600→23700 精确对平）
- `balance.refund.max_cents=1`→400「单次申请不超过 ¥0.01」；阈内提交/驳回正常
- `merchant.withdraw.min_cents=999999`→400「最低提现 9999.99 元」
- `replenishment.check_in.require_location` 无坐标拒 / `max_distance_m=500`「距柜机约 1266177 米」/ `complete.require_door`「请先补货开门」/ `complete.require_evidence`「请先上传现场凭证」——四闸关闸全放行
- `order.unpaid.auto_cancel_hours=48`：50h 单催缴+取消实证

**开关/策略类（8）**：`replenishment.auto_task.enabled=false`→任务摘要「开关关闭，本次跳过」；
`ops.config.audit.enabled=false`→非资金键变更不留痕（history 74→74）；`platform.fund.recon.tolerance_yuan` 双向
（调大「核对平」/调小「超容差」——顺带坐实 1000 元差异=测试数据假差异）；`feebill.auto_generate.enabled` 月结出账总闸；
`settlement.empty_auto_complete=false` 空识别→DISPUTED（两次实证）；`dispute.auto_waive.enabled`（env 兜底关，任务摘要留痕）；
`refund.default_policy` AUTO_REFUND 放行路径全实证；`notify.wechat/sms/async` 三总闸读路径确认（false=渠道跳过）

**定时消费类（6）**：`risk.auto_ack_warn_hours`/`auto_clear_info_hours`（WARN→ACKED、INFO→AUTO_CLEARED 实证）；
`ops.scan.door_open_minutes`（scanner 消费）；`device.offline.auto_sales_lock_minutes`（R1 离线锁售实证）；
`device.rental.monthly_bill.enabled`（日扣模式停用月出账摘要）；`dispute.sla.hours/reminder_hours`（sla 任务扫描）

**R5 主库实证家族（12）**：merchant/line.withdraw 的 fee_cents/fee_bps/fee_cap_cents/max_cents/daily_limit_cents +
review_threshold_cents×2（本轮 500 元审批闸双向实证）

## 三、告警 34 类型清点结论

| 面 | 结果 |
|----|------|
| ops_alert_type（16） | **16/16 产生器在码**（SALES_LOCKED/PROFIT_SHARING×3/DISPUTE/XXL_JOB_WIRING_BROKEN/SCHEDULED_TASK_STALE/VISION_ANOMALY/DEVICE_OFFLINE/UPLOAD_STUCK/SESSION_STALE/LOW_STOCK/REPLENISHMENT/RECON_MISMATCH/SPLIT_EXCEPTION/IN_TRANSIT_OVERDUE） |
| merchant_alert_type（18） | **17/17 ACTIVE 项有产生器**；`RECOGNITION_NEEDS_REVIEW` 全仓零引用=**死字典项已删（V450）**；`SLOT_DISCREPANCY` INACTIVE（V445 正确软退役，非残留） |
| 实际落库实证（14 类） | DEVICE_OFFLINE/DEVICE_FAULT/OPEN_TIMEOUT/RECOGNITION_FAILED/TEMP_ABNORMAL/EXPIRY/LOW_STOCK/REPLENISHMENT/SESSION_STALE/SCHEDULED_TASK_STALE/RECON_MISMATCH/DISPUTE/SALES_LOCKED/INVENTORY_MISMATCH（ops_exception 12 行+merchant 侧） |
| 通道面 | edge 告警 dispatcher 3 类事件 200；feishu/dingtalk/wecom/webhook/email 六渠道未配置时安全跳过不炸；notification_log 35 条 IN_APP |

## 四、新增待办

1. `edge.driver.type` 死键清理（随下批迁移）
2. V450 已推（RECOGNITION_NEEDS_REVIEW 死字典项）
3. `refund.self.max_daily=0` 写侧 fail-safe 静默回默认 3——建议配置页标注合法域
4. `ops.alert.email.smtp_port` 非法值静默吞（仅日志）——低优观察项

## 五、方法备注

- 消费方映射基准：SystemConfigService.java 98 常量（两个探索代理逐键定位到 类#方法:行）
- 行为验证模式：PUT system-configs → 触发消费方（API/定时任务 run）→ 断言行为/文案 → 还原 seed 值
- 坑：PUT 后立即 GET 撞 SystemConfigService 短缓存需 sleep 1.5s；psql 复合 -c 单事务回滚
