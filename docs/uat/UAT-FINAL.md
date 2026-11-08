# UAT 数据测试总结报告（2026-11-07 定稿）

> 环境：uat 空库 `aicabinet_uat` 切栈（Flyway V1→V447 全链 443 迁移一次通过）；三端真实操作（admin 浏览器/API 链、consumer 微信开发者工具真实扫码+登录、模拟柜 MQTT/设备事件）；「数据说话」=每步库断言。
> 计划：docs/UAT_DATA_TEST_PLAN.md；轮报告：docs/uat/rounds/。

## 一、七轮执行结果

| 轮 | 域 | 结果 | 新发现 |
|----|----|------|--------|
| D0 | 基线 | ✅ 空库切栈+443 迁移+181 表底表 | — |
| R1 | 主链冒烟 | ✅ 扫码开门→购物→结算 PAID，全链对账平 | **MA-163（P2）Hikari 只读连接污染** |
| R2 | 用户账号小点 | ✅ 运营/商户/门户/线长全生命周期+RBAC 矩阵双向 | 零新缺陷（2 个「疑似」经查均为正确契约） |
| R3 | 商品/库存/仓储 | ✅ SKU 生命周期+采购全链+三层账+盘点 ADJUSTED+调拨 | 零新缺陷（MA-069/157/103 回归 ✓） |
| R4 | 营销/会员 | ✅ **MA-147/099/096 三修复回归实证全绿** | 零新缺陷 |
| R5 | 退款/争议/钱包 | ✅ 全额退+WAIVE 免单裁决（余额/券/分账全对平） | 零新缺陷 |
| R6 | 定时/日结/月结 | ✅ fee-rerun×2/机器账单×2/recon×2/巡检×2/调度 run 幂等全验 | 零新缺陷（feeDate 未来值 400 边界 ✓） |
| R7 | 并发/边界/收口 | ✅ 覆盖终检（181 表三分类）✅ 边界部分（2026-11-07 补，见「四·补 R7 边界」） | **F1 调账审批：坐实 → 已修并复验 PASS** | 死表候选 ×2 |

**新缺陷合计：MA-163 一条（P2）+ F1（2026-11-07 R7 边界轮实测坐实，**2026-11-08 已修并复验 15 项 PASS，转 CLOSE**）**；回归实证：MA-147/099/096/069/157/103/066 + CB-082 禁券（R1/R17 矩阵）。

## 二、修复验证（白天修复 AI 的工作验收）

白天修复的缺陷在 UAT 中全部回归实证：MA-147（全额退还券 ✓ UNUSED）、MA-099（召回幂等 409 ✓）、
MA-096（负积分成本 400 ✓）、MA-069（佣金限幅 400 ✓）、MA-157（调拨取消留痕 ✓）、MA-103（盘点行锁 ✓）。

## 三、上线结论与建议

**可上线**，附四个前置动作：
1. **MA-163（P2）**：Hikari 加 `connection-init-sql: SET default_transaction_read_only=off` 兜底（一行配置）——修复前生产存在间歇写失败风险（UAT 两轮窗口实证）。
2. **部署前置 SQL**：DEPLOYMENT_CHECKLIST §3.1.5（V435 重复组）+ V431 逐表 count——服务器库必跑。
3. **小程序发布产物**：必须从 dist/build 重建上传（merchant 曾发现产物落后 5.3 小时）。
4. **F1 调账审批（2026-11-07 R7 实测坐实，2026-11-08 修复并复验）**：已采用方案 B（补 409 守卫 + 返回/审计真实实例状态），重建 trade-service 镜像并复跑 `r7-f1-verify-fix.mjs` 全 15 项 PASS，详见 rounds/R7-boundary.md §5b。上线前需把该改动随本次提交真正推上（当前为本地工作树）。

## 四、遗留（不阻断上线）

| 项 | 说明 |
|----|------|
| 消费者 UI 小点 | 昵称/偏好/注销四守卫——token 过期，白天 devtools 登录后 10 分钟可验 |
| 争议 ADJUST/KEEP 裁决 | 同族 CONFIRM/WAIVE 已实测；单测 6 例覆盖 |
| 真机开门/支付分生产 API | 已留痕（支付分生产未接=501，设备摄像头售后中） |
| R6 佣金日结 | 周期扫描型，造数后等下一轮（幂等键已在位） |
| R7 边界未覆盖 4 项 | 并发双操作、弱网恢复提示、账期切换窗口、全表三分类补跑——本节「四·补 R7 边界轮」已列 |
| **F1 调账审批** | **已修复（2026-11-08）：补 409 守卫 + 返回真实实例状态，重建镜像后复验 15 项 PASS，转 CLOSE**（见「四·补 R7 边界轮 §5b」）——上线前确保该改动随本次提交推送并回归一次 |

## 四·补 R8 空表填充轮（2026-11-07，详见 rounds/R8-fill.md）

**181 表触达 108 → 123（+15）**，全部真实 API 链+库断言，新缺陷 0：
- 新触达：user_feedback/user_notify_pref/device_fault_report/http+mqtt_credential/ops_2fa_recovery_code/
  site_contract/site_rent_bill/device_temp_plan/announcement/invoice_request/data_consistency_record/
  balance_refund_request/balance_refund_allocation/repair_ticket（报修自动建单，CB-077 分线 ✓）
- 部分退线上复证（PARTIAL_REFUNDED 250/500 对平）+ MA-092/119 回归 ✓
- 余额原路退**双人审批流**全链 ✓（运营审核→财务复核→allocation 分摊到原微信充值单）
- consumer token 续期链打通（automator login→真 wx-login，全程无需人工扫码）
- 一致性核查器真库实测：抓故意 +777 立即告警，还原自动 FIXED；6 条存量 FAIL 全部定性为 UAT 造数直改 SQL 遗留（非产品缺陷）——建议部署前跑一次 `POST /consistency/run` 作上线自检

## 四·补 R7 边界轮（2026-11-07 晚，详见 rounds/R7-boundary.md）

**边界项中 F1 真实触发已实测坐实；2026-11-08 采用方案 B 修复并复验全绿（下表为溯源，原 open 缺陷记录）：**

- `MerchantWithdrawService.reviewAdjustment(319-332)`：对**无审批实例**的调账单点「通过」仍返回
  HTTP 200 + `status=APPROVED`，且写假审计行（期望 409）。驳回路径同失真，且**同 bizId 幂等无守卫**（再点仍 200）。
- **更深一层**：`MERCHANT_WALLET_ADJUST` 定义是**双节点**（运营审核 → 财务复核）。即使实例真实存在、
  运营节点通过，方法仍报 `APPROVED`，而库内实例仍 `PENDING`（停在财务复核节点）。`status` 完全**硬编码**，
  未读 `instanceStatus(bizId)`。
- UI 影响：`MerchantWithdrawView.vue:927-932` 只看 HTTP 200 即 `ElMessage.success('已通过')`，无法区分真通过与静默空操作。
- 收口：本轮造的两条账目/假审计行/临时实例已全部回滚至基线（`balance=209900`、`approval_instance=14`、`admin_audit_log=306`）。
- 同轮 F3（`ApprovalController.startInstance` `Map.of` NPE）实测**不可触发**：空字段被参数校验拦在 400，同单重复发起 409 —— fail-loud 生效，维持低风险。

> 说明：上表 R7 行的「覆盖终检」系早先已完成项，与边界/F1 两块不同；边界/F1 至本轮才算真实执行。
> **F1 修复方案 B（2026-11-08）已执行并复验全 15 项 PASS，详见 `rounds/R7-boundary.md §5b`。**
> R7 未覆盖：并发双操作、弱网、账期切换窗口、全表覆盖终检三分类补跑（需双会话/开发者工具人工/下一轮）。

## 五、全库表覆盖（181 表）

> ⚠️ 2026-11-08 用真实库对账为**作准数 179 张**（`information_schema.tables WHERE table_schema='public'` 与
> `pg_tables` 双路 count 一致），原「181 表 / 94 触达 / 87 空」为 V448 删 2 张备份表前的旧口径，已过期。
> 　合并判据：`SELECT count(*) FROM information_schema.tables WHERE table_schema='public'` = **179**；
> 　`SELECT count(*) FROM pg_tables WHERE schemaname='public'` = **179**；`flyway_schema_history` = **446 行**。
> 　上表三分类需要整轮重跑才能作准（本轮未跑）；已核实 **原「死表候选」两张 `*_bak_20261011` 备份表已不存在**
> （`pg_tables LIKE '%_bak%'` 返回空 ⇒ 已被 V448 删除），故旧的「2 死表尚未清理」口径不成立，勿再据此条排队清理动作。
> 　重新分类未完工前，本节数字以真实库 179 为准；细分（触达/空）待下一轮全表终检补跑后重写。

详见 docs/uat/table-coverage-final.md（其表头 179 = 139 触达 + 40 空与实库一致，但正文「已触达 94」/ 
「空表 58 = A34+B22+C2」仍为旧稿残留，两处数字自相矛盾，整轮终检时一并修正）。
