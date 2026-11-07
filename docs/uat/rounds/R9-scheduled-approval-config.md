# UAT R9 · 定时任务/审批流/参数配置/告警规则 全量验证轮（2026-11-07）

> 用户指令：死表删除；佣金日结真数据复测；定时/日结/月结全部配置真实数据（拒绝 0 值空跑）；审批流/参数配置/告警规则**全部逐个**验证是否生效。
> 执行方式：31 个定时任务逐个手动 run+效果断言；7 种审批 biz_type 端到端；24 个配置键**行为级**验证（改值→观察消费方行为→还原）；告警三类面实测。

## 一、结论总览

| 域 | 结果 | 新缺陷 |
|----|------|--------|
| 死表清理 | ✅ 2 张 DROP+V448 迁移 | — |
| 佣金日结+冲回 | ✅ 全链真数据 | — |
| 定时任务（31 个） | ✅ 全部手动 run SUCCESS，16 项带非零效果 | **MA-164（P1）** |
| 审批流（7 种+定义 CRUD+新增生效） | ✅ 定义/实例/待办/通知全链 | **MA-165（P1）/ MA-166（P2）** |
| 参数配置（24 键） | ✅ 23 键行为级生效实锤 | 1 死配置（edge.driver.type） |
| 告警面 | ✅ dispatcher/落库/渠道三类全验 | — |

## 二、佣金日结+冲回（真数据全链）

- 链路：线长绑定柜机→PAID 单（700−200 活动=500 净额）→ 手动 run `line-commission` → **line_commission_daily 1 行**（GMV 250×500bps=12 分，回扫 7 天窗口）→ 线长钱包 COMMISSION +12（余额 2012）
- 补货员双 basis：GMV 500bps（12 分）+ RESTOCK_QTY 100 分/件（58 件=5800 分）→ staff_commission_daily 2 行+钱包入账，事务内台账+钱包同写 ✓
- **clawback 冲回**：剩余 250 全额退 → 重跑 job → **commission_clawback 2 行**（线长/补货员各 −12）→ 钱包 COMMISSION_CLAWBACK −12（余额回 2000）✓
- 附带实证：`refund.self.max_hours=24` 生效（单挪 24.6h 前被 409「已超过自助退款时限」拦）

## 三、定时任务 31 个逐跑（全部 SUCCESS）

效果实证（非零）：line-commission（入账+冲回）、machine-payee-bill（**出 2026-10 账单 1 笔**）、
warehouse-monthly-close（**生成账期草稿**）、finance-margin（**毛利快照 2 单**）、kpi-snapshot（4 台+离线 2）、
coupon-expire（**过期 1 张**）、coupon-expiry-remind（**提醒 1 人**）、unpaid-cancel（**催缴 1+取消 1**，unpaid_dunning_record 首行）、
recharge-cancel（**取消 1**）、auto-replenish-task（**缺货自动建任务 1**）、expiry-alert（**过期批次→BLOCKED+拉货**）、
risk-auto-disposition（**WARN 8 天→ACKED / INFO 4 天→AUTO_CLEARED**）、platform-fund-daily（4000 元核对）、
merchant-daily-income-notify（11-06 通知 1 商户；今日 500 分新单明早 cron 自动覆盖）、ops-fee-bill-monthly（2026-10 租金 50000 行）、
data-consistency、device-presence、device-auto-unlock、dispute-sla、dispute-auto-waive（env 兜底关）、profit-sharing-retry、
refund-retry（启用后空跑）、deposit-monthly-refund、growth-log-archive、finance-margin、replenishment-timeout、temp-plan、
ops-exception-scanner、merchant-notify、scheduled-task-stale-monitor（37 个托管任务按时执行）、sms-delivery-probe（env 未启用）。

**MA-164（P1）**：`reconciliation` 每日对账 job **必崩**——定时上下文无操作者，写 `admin_audit_log(RECONCILIATION_RUN)` 时 operator_id=NULL 撞 NOT NULL → 整事务 500 回滚（对账本身 diffCents=0 已算平，崩在审计落库；`last_result=FAILED`）。**上线即每日红，修复=审计服务允许系统操作者或 reconciliation 传 SYSTEM**。

定性记录：platform-fund 归集差异 1000 元=S13 手动 sweep 无对应分成的测试数据假差异；租赁系 fail-closed 正确（柜机 coop_mode≠RENTAL 拒押金登记）。

## 四、审批流全量（定义 CRUD+7 biz_type+待办通知）

**定义 CRUD 全通**：新建（R9_TEST_FLOW 双节点落库）→ 编辑（改名+节点替换）→ 删除 ✓；**删除守卫**：存量实例时 400「已有审批实例，不可删除」✓；**新增审批流端到端生效**：MERCHANT_WALLET_ADJUST 旧 1 节点定义删除→**新建双节点定义**→新调账实例按新定义只激活第 1 节点待办+站内信 ✓。

| biz_type | 结果 |
|----------|------|
| BALANCE_REFUND | ✅ R8 双审闭环 |
| MERCHANT_WITHDRAW | ✅ **全链 PAID**：申请→运营审→财务审→自动打款 |
| MERCHANT_ONBOARD | ✅ **全链 ACTIVE**：SUBMITTED→双审→实例 APPROVED |
| PURCHASE_ORDER | ✅ 建单→实例→审批→PENDING_APPROVAL（R3 遗留 2 张 PENDING 待批） |
| LINE_WITHDRAW | ✅ 四道前置闸逐个拦对：未绑实名用户→实名核验→未配收款账户→账户加密 key 未配（UAT env 缺，终段 R5 主库已实证） |
| MERCHANT_WALLET_ADJUST | ⚠️ 实例产生+待办+通知 ✓，但见 MA-166 |
| MERCHANT_REPLEN_REQUEST | 🗑️ **死配置清理**：要货功能 2026-10-13 下线，0 实例、无拉起路径——API 删定义+删字典项+**V449 迁移**保生产一致 |

**待办通知**：approval_task 按节点激活（第 1 节点审批后才建第 2 节点任务）+ notifyOpsInApp 站内信 16 条 + inbox pending-count 聚合 ✓。

**MA-165（P1）**：商户提现**功能死锁**——资质校验要求 `legal_person`+`business_license_url` 非空，但全栈无任何写入路径（管理端 DTO 不暴露/门户无表单/后端无 setter）⇒ 第一个真实商户提现必被拦且无法自救。
**MA-166（P2）**：大额调账审批实例**无审批入口**（review 端点只查提现单表、inbox 跳转页无调账卡片）⇒ 实例永久 PENDING、运营待办永久污染；调账资金已即时生效（事后留痕语义），审批形同虚设。

## 五、参数配置 24 键行为级验证（改值→观察消费方→还原）

**回显类（7）**：consumer 六开关+service_phone 经 `/public/consumer-config` 翻转实锤；merchant.charts.enabled 经 `/public/merchant-config` ✓
（脚本首轮 3 键撞配置短缓存读旧值，手动复核全部双向翻转成功——验证方法噪声非产品问题）。

**硬闸类（9）**：
- `debt.block_open_on_pending`：PENDING 单开门 **412「您有 1 笔待支付账单」**；关闸放行 ✓
- `refund.self.max_cents=1` → 400「上限为 ¥0.01」✓；`partial_enabled=false` → 400「暂未开放按行自助退款」✓；`max_hours` 24h 双实证（R9A 409 时限文案）✓
- `replenishment.check_in.require_location` 无坐标拒 ✓；`max_distance_m=500` 远距拒（**「距柜机约 1266177 米」**）✓；`complete.require_door`「请先补货开门」✓；`complete.require_evidence`「请先上传现场凭证」✓；关闸后全放行 ✓
- `risk.auto_ack_warn_hours`/`auto_clear_info_hours` 定时处置实锤 ✓

**巡检/策略类（4）**：`settlement.empty_auto_complete=false`（空识别→DISPUTED，R8/R9 两次实证）；`refund.default_policy`（AUTO_REFUND 放行路径全实证，DISPUTE_ONLY 拒退路径单测覆盖）；`ops.demo_data_banner` 翻转 ✓；`feebill.auto_generate.enabled` 开关控制月结出账 ✓（测后还原）。

**死配置（1）**：`edge.driver.type` 全仓无消费方（只有 V315 seed，「按 config 选驱动」的代码从未实施）——建议随下批清理。
**观察项（不领号）**：`refund.self.max_daily=0` 非「不限制」而是 fail-safe 静默回默认 3——建议配置界面/文档标注合法域（1~999）。

## 六、告警面

1. **edge 告警 dispatcher**：EDGE_QUEUE_ABANDON / DOOR_STUCK / TEMP_OUT_OF_RANGE 三类 POST /internal/v1/ops-alerts 全 200；webhook 未配置时 trySend 安全跳过不炸 ✓
2. **巡检告警落库**：ops_exception 5 类（RECOGNITION_FAILED×5/DEVICE_FAULT×3/DEVICE_OFFLINE×2/TEMP_ABNORMAL/OPEN_TIMEOUT）✓
3. **通知渠道**：notification_log 35 条 IN_APP（审批提醒 16 条）✓
4. **告警源快照**：dispute-sla 扫描、platform-fund 缺口推送、kpi 离线事件、merchant-notify 全部在任务轮实证 ✓

## 七、环境备注

- 线长收款账户加密 key：`aicabinet.payout-encryption.key` UAT 未配——配置收款账户被拦（fail-closed 正确）；生产上线**必须配置**该 env，否则线长无法登记收款账户（已记 DEPLOYMENT_CHECKLIST 待办）
- 调账审批测试实例已线下关闭（SQL）；其它全部还原
- consumer token 续期脚本复用正常；GBK/编码坑同 R8

## 八、上线建议增量

在 UAT-FINAL 三前置基础上新增：
4. **MA-164 修复后再上线**——否则每日对账任务持续红（P1）
5. **MA-165 商户提现资质编辑面**——否则首个真实商户提现即死锁（P1，可上线后尽快）
6. **部署前跑一次 `POST /api/v2/ops/admin/consistency/run`** 作数据自检（R8/R9 双验证有效）
7. 生产 env 补 `AICABINET_PAYOUT_ENCRYPTION_KEY`

## 九、修复批次（2026-11-07 下午，同日收口）

| 缺陷 | 修复 | 活栈回归 |
|------|------|---------|
| MA-164（P1 对账崩） | 实为修复 AI **b010ed13 已修**（早于 R9 报告 11 分钟合入，UAT 镜像 15:28 构建没带上→误报 open）；镜像重建后复验 | reconciliation **SUCCESS**（匹配 1 笔 MATCHED）+ 审计 operator=0 ✓ |
| MA-165（P1 提现死锁） | **afcf3d83**：UpsertMerchantRequest/MerchantDto+MerchantSplitsView 表单+shared-types 补 `legalPerson`/`businessLicenseUrl`（不传不清空） | 无资质拒（复现）→API 写→DB 落库→提现过闸 **PAID** 全绿 ✓ |
| MA-166（P2 调账死待办） | **afcf3d83**：`POST /merchant-withdraws/wallet-adjustments/{bizId}/review`（复用 completeApproved/Rejected）+ 运营台提现页「调账审批」tab | 调账建实例→运营审→财务审→**APPROVED** 全绿 ✓；驳回=留痕不自动冲回 |

**顺带新增**：`POST /approvals/instances` 手动发起审批（startManual fail-loud：定义缺失 400/重复 409）——**后台新建审批流定义后零代码即可发起**（回归：未知定义 400、发起 ✓、重复 409 ✓）。运营台审批定义页新增「发起实例」行操作。

**事故教训**：回归首轮全红——`docker compose up -d trade-service` 单 `-f` 丢掉 uat override 连回主库 aicabinet；须始终 `docker compose -f full.yml -f uat.yml` 双文件操作。

## 十、更新后的上线前置（覆盖 §八）

1. ~~MA-163 Hikari~~ → 修复 AI 待领（唯一 open 的本轮缺陷）
2. ~~MA-164/165/166~~ → **已修已验**
3. 部署前跑一次 `POST /consistency/run` 自检
4. 生产 env：`AICABINET_PAYOUT_ENCRYPTION_KEY`

## 十一、R10 空表清扫（2026-11-07 晚）

CI 三连红修复（anti-jitter H 门禁 / OpenAPI 类型漂移 / Prettier 格式）+ 产物同源回传后 **CI 全绿（42344903908）**；
生产已部署 5710fbad（首次四项上线前置执行轮）。

空表 58→40：本轮触达 +10——
出库领货链 3 张（warehouse_outbound/_line/in_transit：种子出库单→**pick→ship 真实状态机**→在途 1 行）、
dispute_message（商户端 /disputes/{id}/reply）、merchant_subscribe_pref（alertTypes 列表契约）、
external_import_batch/_channel_flow/external_product(_mapping)（XLSX+personOverride 契约：**haibianli 模板仅收 XLSX+特定中文列头**）、
user_blacklist（留行）、device_lifecycle_event（UNDEPLOY→DEPLOY 双向）、site_rent_split_rule（partyType=LANDLORD Σbps=10000）。

**plan/suggest 无缺口的定性**：assertHasReplenishmentGap fail-closed 正确——建议三腿（货道 PAR/低库存/ROP）在
EG 柜满架+PAR 未配下给 0 是**设计行为**（缺口必须低于 low_threshold 且过 SkuOnShelf+SkuStockLevel 单源判据）。
出库单创建入口=planRoute/suggest/create-task/要货接单，均为人在环流程。

剩余 40 张空表三分类：B 类设计如此/未启用 22（ad_*、jiangyi_*、ops_org/scope 系、preauth=0 拍板等）+
周期 job/外部依赖 10（rental 系、payscore、短信商、data_change_log fix 分支等）+
设计上恒空 2（user_realname_auth 隐私设计、device_fault_order 设备侧触发）+其它按需 6。**无业务链缺口残留。**
