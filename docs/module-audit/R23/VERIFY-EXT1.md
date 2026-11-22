# VERIFY-EXT1 — 外部审计《AUDIT_FOUR_END_R23_2026-11-15》对抗性验证

- **验证人**：R23 外部报告对抗性验证（证伪者视角，全部结论本机读码 + dev 库只读 SQL 独立得出）
- **快照 HEAD**：`fdf5f3e4`（`git log --oneline -3` 确认，与报告锚点一致；工作树仅未跟踪报告文件）
- **验证日期**：2026-11-15
- **对象**：`docs/AUDIT_FOUR_END_R23_2026-11-15.md`（R23I-01..22，P1×7 + P2×9 + P3×6，另 §5 撤回 3 条）
- **方法**：每条独立读码复现触发链；权限类逐层核（Controller 注解 → service 闸 → MerchantScopeService 数据面 → 全局拦截器）；并发类核事务边界与锁序；R23I-09 用 `docker exec ai-cabinet-postgres-1 psql`（只读 SELECT）复跑报告附录 SQL。

---

## 1. 总表

| 编号 | 报告 P | 判定 | 本验 P | 一句证据（本机实读） |
|---|---|---|---|---|
| R23I-01 提现失败解冻后取消再解冻 | P1 | **成立** | P1 | `MerchantWithdrawService.java:728-732` FAILED 即释放 + `:465-481` 仅查 FAILED 再释放；`WalletEngine.java:322-337` 无 ref 幂等；V379:85-93 唯一索引显式排除 `ref_type='WITHDRAW'` |
| R23I-02 视频播放地址无对象级授权 | P1 | **成立**（与 AU-062/DC-11 同族，见 §4） | P1 | `AdminJiangyiController.java:128-133` 仅 `@RequiresPermissions("ops:device:video")`；`JiangyiOrderVideoService.java:195-201` `selectById` 后直签，方法签名无 operatorId；V339:15-20 授 role 1+2 |
| R23I-03 单柜改价/批量重置无设备范围 | P1 | **成立** | P1 | `OpsDevicePricingService.java:103-104`（改价）/`:196-198`（reset）/`:75-77`（list）均只 `requirePermission`+`requireDevice`（:212-217 仅查存在）；V350:29-33 授 role 2 |
| R23I-04 设备 HTTP 凭据签发/吊销无范围 | P1 | **成立** | P1 | `DeviceHttpCredentialService.java:55-58` 仅 12 位格式+设备存在；Controller/Service/MQTT 三文件 `requireDeviceAccess` 命中数=0；响应体回明文 secret；V118:21 `ops:device:edit` 授 operator |
| R23I-05 日毛利锁只读权限可写全局日键 | P1 | **成立**（降档） | **P2** | `OpsGapFeaturesController.java:134-141` 读码护写端点；`FundBillService.java:565-573` 空集返回 0 + `:429-442` 无条件 upsert；`FinanceMarginDailyLock.java:16-17` 仅 bizDate 键。**消费面仅展示/CSV（全仓无其它读者），无资金路径、可重固化恢复 ⇒ 降 P2** |
| R23I-06 月结期初盘点取错一期 | P1 | **成立**（降档+拍板子题） | **P2** | `WarehouseMonthlyCloseService.java:153-154` 用 `prevLabel` 与 `:432-436` 导入口径注释直接矛盾；期初(prevLabel=N−1)/期末(nextLabel=N+1) 间距 2 期，**任一自洽口径下必错一侧**。影响=月结差异/索赔口径错位，无直接资金移动；真实导入口径需取样拍板 ⇒ P2 |
| R23I-07 手工退款同步去重与累加分属两事务 | P1 | **成立**（同族 R20-P2-6 残余并发面，新信息） | P1 | `PaymentService.java:478-481` 幂等首查与 `:501` `finalizeChannelPartialRefund`（`:866-881` 无条件累加）分属两事务；`BalanceLedgerService.java:84-95` 幂等命中原样返回不抛错 ⇒ 并发重投时账本去重、单据仍双累加 |
| R23I-08 退多收不回算分账 | P2 | **成立**（邻面 MA-124） | P2 | `DataConsistencyService.java:1617-1632`/`:1704-1725` 直调 `refundOrder`；`OrderPaymentService.java:660-698` 全链无 `adjustSplitAfterPartialRefund`（唯一调用点 `SettlementPartialRefundService.java:287`）也不置 PARTIAL_REFUNDED（状态写点仅 `SettlementPartialRefundService.java:283`） |
| R23I-09 毛额vs净额无巡检+1 笔实漂移 | P2 | **成立（✅ dev 库双证）** | P2 | `DataConsistencyService.java:934-988` 两检查均不含 gross↔net；dev 库复跑附录 A.2：order `1793669053437918137747` PAID/paid=586/refunded=293/split gross=586/merchant=586，payment_operation 三行 CHARGE 586→ADJUST_CHARGE 293→REFUND 293 全 COMPLETED，wallet_ledger SPLIT_CREDIT 586 —— **与报告逐位吻合** |
| R23I-10 月账单退款按订单创建日分日 | P2 | **成立** | P2 | `CabinetOrderLineMapper.xml:245-262` `WHERE created_at`+`GROUP BY to_char(created_at)`，退款额取订单级快照 `refunded_cents`（SQL 注释自认） |
| R23I-11 月账单费用源过期 | P2 | **成立** | P2 | `SystemConfigService.java:985-987` `feebill.auto_generate.enabled` 默认 false（OpsFeeBillJob.java:51-53 据此 SKIPPED）；日扣写 `merchant_fee_ledger`（MerchantDailyIncomeNotifyScheduler.java:485-486）；`MerchantFinanceService` 全文 0 引用 merchant_fee_ledger，只读 `device_data_fee_bill`（:279-289） |
| R23I-12 场地租金不看合同起止日 | P2 | **成立** | P2 | `SiteRentBillService.java:99-114` 只过滤 ACTIVE/EXPIRING；`doGenerateForContract`（:175-200）`isEffectiveInMonth` 只筛分账规则；`SiteContractService.java:122` status 仅建/改时计算，**无定时刷新任务** ⇒ 过期合同 status 仍 ACTIVE 继续出账，startDate 完全无人读 |
| R23I-13 chargeOrder 事务内渠道 HTTP | P2 | **成立** | P2 | `SettlementService.java:115-123` @Transactional+`findByIdForUpdate(session)`→`SettlementOrderFinalizeService.java:94` `deductForOrder`→`OrderPaymentService.java:126/138` @Transactional(REQUIRED 合流)→`:287` `submit.get()` 渠道 HTTP；订单 Redis 锁租约 60s（:668）无看门狗 |
| R23I-14 支付分通知读判写无锁 | P2 | **成立** | P2 | `PayScoreOrderNotifyService.java:82-86` selectList 无 FOR UPDATE，`:113-114` 无条件 `updateById(po)`；`UnpaidDunningService.java:81-99` 按用户累加无订单幂等；R20-P2-5 早退判据（:102-112）读的是判前快照，并发双投双双通过 |
| R23I-15 分账锁提交前解锁+DKE 读回必失败 | P2 | **成立** | P2 | `RevenueSplitService.java:80-91` @Transactional 内 `:722-723` finally 解锁早于提交；`:155-158` catch `DuplicateKeyException` 后**同事务** `findByOrderIdForUpdate` —— PG 约束违约即 abort，后续语句必 25P02；V207:31-32 唯一键在案 |
| R23I-16 温控/关门/重启无设备范围 | P2 | **成立** | P2 | `DeviceTempPlanService.java:134-139` 仅 `ops:device:edit`+存在性；`JiangyiDeviceOpsService.java:90-107` closeDoor/reboot 仅 `requireJiangyi`+会话忙闸；三文件 requireDeviceAccess=0；V118:21 授 operator |
| R23I-17 回调先占位后入账 | P3 | **成立（观察）** | P3 | `PaymentService.java:555` assertNotifyIdOnce 占位先写；自愈在案（`:648` autoCancelExpiredPending）——报告 §5.1 降级判据属实 |
| R23I-18 租机押金无 requireDeviceAccess | P3 | **成立（防守项）** | P3 | `DeviceRentalService.java:150-157` 仅 `ops:rental:manage`，全文件 0 处范围闸；V342:85-89 授且仅授 role 1 ⇒ 当前不可利用，报告自降 P3 正确 |
| R23I-19 月租逐日整除截断 | P3 | **成立** | P3 | `MerchantDailyIncomeNotifyScheduler.java:469` `rentCents / daysInMonth` 整数除 |
| R23I-20 结算单 vs 月账单归月口径分叉 | P3 | **成立** | P3 | `MerchantSettlementBillService.java:95-100` 按 `split.createdAt` 取窗 vs `MerchantFinanceService.java:266-277` 按订单 `created_at` |
| R23I-21 网关转发判定过宽 | P3 | **成立** | P3 | `DeviceReportController.java:321` `forwarded = response != null`（accepted=false 也注销 CLOSE watchdog；报告自评「非 P1，有 DISPUTED 兜底」恰当） |
| R23I-22 分账锁泛捕 IllegalStateException→500 | P3 | **成立** | P3 | `RevenueSplitService.java:454` 抛 ISE（「仅 LEDGER_ONLY 可确认」）；`:716-721` 非 RSE 一律包 500；`:460` 注释自认应 409 却未改 |

**判分汇总**：22 条全部成立（0 不成立 / 0 已修复 / 0 属拍板题误判）。其中 R23I-05、R23I-06 建议降 P1→P2；P3 6 条均属实（R23I-18 按报告自己的降级成立为防守项）。

---

## 2. 成立条目完整触发链（P1 逐条 ≤15 行）

### R23I-01 → MA-280（P1）
1. 商户提现 APPROVED → `payout()`（`MerchantWithdrawService.java:430-444`，权限 PERM_OPS_MERCHANT_WITHDRAW_REVIEW + 商户范围闸）→ `executePayout`：`:639` markPaying 短事务 → `:642` 渠道打款（事务外）→ `:643` finalizePayout。
2. 打款失败：`:728-732` status=FAILED + `releaseFrozen(amount, WITHDRAW, requestId)` —— 冻结已释放。
3. 运营台 FAILED 行仍显示「取消解冻」按钮（`MerchantWithdrawView.vue:903/927`，门禁 `money-ui-contracts.ts:73-77` status==FAILED）→ `cancelFailed` → `cancelFailedLocked`（`:465-481`）**只校验 status==FAILED**，再次 `releaseFrozen`（同 ref）。
4. 幂等缺失双证：`WalletEngine.java:322-337` 只校验 `frozen >= amount` 不查 ref 流水；V379:85-93 partial unique index 显式 `ref_type <> 'WITHDRAW'`。
5. 无在途单：第二次解冻 412「冻结金额不足」→ 事务回滚 status 恒 FAILED，取消功能永久不可用。
6. **资损形态**：另有在途单 B（APPROVED 已冻结 100）⇒ A 的第二次解冻吃掉 B 的冻结 ⇒ B 打款成功后 `consumeFrozenSplit`（WalletEngine.java:351-378）412 → finalizePayout 回滚 → **渠道已出账、本地余额未扣、单卡 PAYING**（markPaying :639 已独立提交）。镜像链 `LineWithdrawService.java:248-259/:489-492/:555` 同构。`failSingleStalePaying`（:776-802，MOCK 超时）产出同类已解冻 FAILED 单。

### R23I-02 → MA-281（P1）
1. `GET /api/v2/ops/jiangyi/order-videos/{id}/play-url`（`AdminJiangyiController.java:128-133`）：唯一闸 = `@RequiresPermissions("ops:device:video")`。
2. V339:15-20 将该权限授予 role 1（超管）与 role 2（运营人员）；role 2 非全局 ⇒ 数据面真源是 `MerchantScopeService.requireDeviceAccess`（`MerchantScopeService.java:255-267`），但 `JiangyiOrderVideoService.playUrls(long id)`（:195-201）`selectById` 后直签，**全链无 operatorId、无任何范围判**。
3. 全局拦截器核过：`WebConfig` 注册的 AuthInterceptor/InternalApiAuthInterceptor/ApiVersionInterceptor/DeviceHttpCredentialInterceptor 均不做数据范围，无兜底。
4. 返回逐片预签名 URL（私有桶，拿到即可离线播放/留存）⇒ 任一运营遍历 id 看任意商户柜机交易录像。范围模型（设备范围 vs 商户范围）子题应并入仍开着的 **DC-11** 拍板，但「零范围闸」本身今天即成立。

### R23I-03 → MA-282（P1）
1. `PUT /devices/{deviceId}/pricing/{skuId}` 与 `POST /devices/{deviceId}/pricing/reset`（`AdminDevicePricingController.java:44-63`）：`@RequiresPermissions("ops:pricing:edit")`（V350:29-33 授 role 1+2）。
2. `OpsDevicePricingService.updatePricing/resetPricing/listPricing`（:101-104/:196-198/:75-77）：服务层同权限码 + `requireDevice`（:212-217 仅 findById 判存在）——**无 `requireDeviceAccess`**。
3. 对照正确范式 `OpsDeviceAdminService.updateDevice`（:509-514）：同族写端点在查设备后即调 `merchantScopeService.requireDeviceAccess(operatorId, deviceId)` —— 本服务漏同款闸。
4. 后果：受限运营可对范围外商户柜机改价（可打到 min price）/ 一键清空全部覆盖价（恢复默认价），直接改交易金额与商户分成基数；读面同漏。

### R23I-04 → MA-283（P1）
1. `POST /devices/{deviceId}/http-credentials`（`DeviceHttpCredentialController.java:45-53`）：`@RequiresPermissions("ops:device:edit")`；V118:21 授 operator 角色。
2. `DeviceHttpCredentialService.issue`（:54-79）：仅 `requireDeviceId`（12 位格式）+ `requireDeviceExists` —— 无范围判；MQTT 同构（`DeviceMqttCredentialService.java:57-59`）；revoke/status 同族无闸（三文件 grep requireDeviceAccess=0）。
3. 响应体回**明文 secret**（Controller :53-61 注释自认「仅本次响应可见」）；持 secret 即通过 `DeviceHttpCredentialInterceptor`（auth/DeviceHttpCredentialInterceptor.java:15-16）以该设备身份调 OTA check/progress、screen-content、ad-play 等设备面端点 ⇒ 跨商户冒充设备。
4. 附加伤害：`issue` 对已有凭据是**轮换**（:64-77 旧 secret 即刻失效）⇒ 对他商户设备签发即打断其设备鉴权（DoS）。

### R23I-07 → MA-286（P1）
1. `syncChannelSideRefund`（`PaymentService.java:460-504`）外层禁 @Transactional（javadoc 自述）；`:478-481` 幂等首查 `findByIdempotencyKey("channel-refund-sync:"+refundId)` 是**普通读**。
2. 同一 refund_id 并发双投：T2 在 T1 提交前通过首查 → `balanceLedgerService.change`（:493-495）阻塞于用户行锁 → `doChange`（BalanceLedgerService.java:84-95）锁内复查幂等键命中 → **原样返回不抛错** ⇒ T2 继续执行 `:501` `self.finalizeChannelPartialRefund(orderId, refundCents)`。
3. `finalizeChannelPartialRefund`（:866-881）：`findByIdForUpdate(orderId)` 后 `setRefundedCents(already + refundCents)` 无条件累加，且允许 status=REFUNDED 再进 ⇒ 本地 refunded_cents 双记（甚至超面额，:481-488 的超退检查用的是 :460 的过期快照）。
4. 后果：本地已退 > 渠道实退 ⇒ 后续可退额被虚高值占掉（合法退款被拒=少退用户）+ 对账基数失真；渠道侧幂等 ⇒ 无双退。前置修复史：R20-P2-6（AUDIT_FOUR_END_R20:396）只封了**顺序重投**，本条是同点位残余**并发窗口**——新信息成立。

---

## 3. 不成立条目反证

**无。** 22 条全部经本机读码/实库复核成立。需要如实记录的三点限定：

1. **R23I-09 的运行库证据已复跑吻合**（非转述）：附录 A.2 两条 SQL 在 dev 库重跑，`PAID+refunded>0` 恰 1 笔（order `1793669053437918137747`），漂移明细与报告逐位一致（586/293/586 + SPLIT_CREDIT 586 + 三行操作流水）。另 2 行命中为 `paid_amount_cents=0` 的 LEDGER_ONLY gross=total 回退形态，与报告「非漂移」分类一致。
2. **R23I-05 的 P1 定级本验不采信**（缺陷成立、级降 P2）：`finance_margin_daily_lock` 全仓唯一读者是列表/CSV 展示（grep 全后端仅 `OpsGapFeaturesController` + `FinanceMarginDailyLockMapper`；admin-vue 仅 endpoints.ts 一处），无资金任务消费该表，管理员重固化即可覆盖恢复——是「权限模型违规 + 报表完整性」问题，不是资金完整性问题。
3. **R23I-06 的「哪一侧 label 才对」**依赖真实导入口径（报告 §7 已自列拍板判据）——但「代码与自述注释矛盾、两侧间距 2 期任一口径必错一侧」是静态事实，缺陷本身无需等待拍板即成立。

---

## 4. 交叉核对（防重复 / 防拍板题误判 / 权限闸真实形态）

### 4.1 与 ISSUES.md（MA-176..261）与 R23-FINAL
- R23-FINAL/MA-176..261 的 11 条（库存批次锁、V24 唯一键、押金预授权、config 守卫、门禁两刀、OrgSites 清列、轮询守卫、台账漂移、XXL 兜底）与本报告 22 条**零重叠**——两轮独立审计互补，无撞车。
- **同族/同源标注**：
  - R23I-02 ↔ **AU-062（P2，open，待 DC-11 拍板）**：AU-062 是视频**列表**对 `ops:device:list` 回显对象键；R23I-02 是 **play-url 端点对任一持 `ops:device:video` 者签发可用预签名 URL**——同属「视频信息授权面」，R23I-02 严格更重（对象级 IDOR + 可离线留存的成品 URL），**新增信息成立**。范围模型子题应并入 DC-11 一并拍板，避免两个口径。
  - R23I-07 ↔ **R20-P2-6 / MA-088 家族**：同代码点 `syncChannelSideRefund`；P2-6 已封顺序重投，本条并发窗口为新增残余。
  - R23I-08 ↔ **MA-124**（refundOrder 返回值契约）：邻面同点位，MA-124 修的是返回值语义，本条是分账回算缺失，不重复。
  - R23I-02/03/04/16/18 ↔ **MA-068 家族**（盘点凭证跨租户写，已修 `requireDeviceAccess`）：同族「功能权限通过、数据范围未过」，点位全新——报告「4 处同族缺口」的系统性判断与 MA-068 修复史互证。
- R23I-09 的漂移单（CHARGE 586→ADJUST 293→REFUND 293，status 恒 PAID）正是 R23I-08 所述终态的活体样本：`refundOrder` 不置 PARTIAL_REFUNDED ⇒ `checkRefundAmountConsistency`（只扫 REFUNDED/PARTIAL_REFUNDED）永不覆盖 ⇒ 三处巡检全盲。两条互为因果，报告把它们并列是对的。

### 4.2 与 PENDING_DECISIONS（①-⑫）
- 22 条中**无一条**把拍板题当缺陷：⑨-⑫（MA-177/178/260/欠款文案）与外部发现零交集；①②③⑤⑥⑦⑧ 与之无关。
- 两处含拍板子题但主体是缺陷：R23I-06（真实导入口径待取样）、R23I-02（范围模型并入 DC-11）——已在总表标注。

### 4.3 权限闸真实形态（越权类 02/03/04/05/16 逐层核过）
| 层 | 结论 |
|---|---|
| Controller 注解 | 5 条均有功能权限注解（`ops:device:video` / `ops:pricing:edit` / `ops:device:edit` / `ops:finance:view`），**非裸奔** |
| Service 层 | 5 条 service 闸均为功能码复检 + 设备**存在性**检查，无一调 `requireDeviceAccess`（grep 实证 0 命中） |
| 数据面 | `MerchantScopeService.requireDeviceAccess/allowedDeviceIds` 是既定真源（updateDevice :513、withdraw :433、盘点上传等大量正确用法对照）——5 条全部绕开 |
| 全局拦截器 | `WebConfig` 四个拦截器（Auth/Internal/ApiVersion/DeviceHttpCredential）均不落数据范围判——**无兜底闸**，外部报告「同族漏闸」判断成立 |

---

## 5. 对报告整体质量的定性

- **证据纪律：优秀。** 22/22 条的 file:line 经实读**全部命中**（含精确到语句的引用，如 `WarehouseMonthlyCloseService.java:153-154`、`RevenueSplitService.java:711-724`、`V379:87`）；权限种子（V118:21/55-60、V339:15-20、V342:85-89、V350:29-33）逐条对上；唯一需运行库的 R23I-09 以只读 SQL 自证且可复跑。**未发现任何编造迹象。**
- **自我纠偏诚实。** §5 三条撤回/降级（24h→35min 自愈、租机押金仅 admin 可达、幂等键撞键撤回）本验抽核属实：autoCancelExpiredPending 在案（PaymentService.java:648）、V342:85-89 仅 role 1、`CommissionBaseCalc.java:46-48` 净额单调不增的撤回论证正确。
- **定级口径**：P1×7 里 5 条站得住；R23I-05/06 偏严一级（消费面/资金路径核过建议 P2）。P3 6 条全部属实且自评克制（R23I-18「当前不可利用」、R23I-21「非 P1 有兜底」均与代码一致）。
- **可见盲区**：未与 AU-062/DC-11 交叉（独立审计可原谅，但汇审必须合并拍板面）；未提 R23I-09 漂移单与 R23I-08 修复路径的同源性实锤（其操作链 ADJUST+REFUND 形态与一致性修复退多收吻合，可作 MA-287 的取证锚）。
- **与我方 R23-FINAL 的关系**：两轮零重叠、互相补位——外部轮抓「权限/数据范围族 + 账期口径族 + 失败路径幂等族」三类系统性缺口，我方轮抓「门禁/台账/定时任务」面；组合后 R23 覆盖面完整。

---

## 6. 建议新 MA 号（保留段 MA-280..299，仅本报告标注，不写 ISSUES.md）

| 建议 MA | 对应 | 级 | 一句话 |
|---|---|---|---|
| MA-280 | R23I-01 | P1 | 提现 FAILED（finalizePayout/failStale 双源已解冻）→ cancelFailed 二次解冻；含在途单时资损 |
| MA-281 | R23I-02 | P1 | 视频 play-url 无对象级授权（role2 可遍历）；范围模型并入 DC-11 |
| MA-282 | R23I-03 | P1 | 改价/reset/list 三端点无设备范围（V350 授 role2） |
| MA-283 | R23I-04 | P1 | 设备 HTTP/MQTT 凭据签发/吊销无设备范围 + 明文 secret 回显 + 轮换可跨商户打断设备鉴权 |
| MA-284 | R23I-05 | P2 | 日毛利固化读码可写全局日键（空集写 0/子集覆盖） |
| MA-285 | R23I-06 | P2 | 月结期初盘点 label 与自述口径矛盾（prevLabel vs periodLabel，间距 2 期） |
| MA-286 | R23I-07 | P1 | 手工退款同步幂等去重与单据累加分属两事务，并发重投双记 refunded_cents |
| MA-287 | R23I-08 | P2 | 一致性修复退多收不回算分账、不置 PARTIAL_REFUNDED（dev 库 ...747 为活体样本） |
| MA-288 | R23I-09 | P2 | 分账 gross≠net 无巡检；新增 `PAID+refunded>0` 与 `split.gross≠netCents` 两判据 |
| MA-289 | R23I-10 | P2 | 月账单退款按订单 created_at 分日，跨月退款进不了当月 |
| MA-290 | R23I-11 | P2 | 月账单费用源过期（不读 merchant_fee_ledger，租金/流量费双漏） |
| MA-291 | R23I-12 | P2 | 场地租金不看合同起止日（status 无定时刷新 + startDate 无人读） |
| MA-292 | R23I-13 | P2 | chargeOrder 事务内渠道 HTTP + 会话/库存行锁同持（退款链已事务外 HTTP，扣款链未对齐） |
| MA-293 | R23I-14 | P2 | 支付分通知读判写无锁，DONE 可被迟到 EXPIRED 覆盖并误记欠款 |
| MA-294 | R23I-15 | P2 | 分账锁事务内提交前解锁 + DKE 后同事务读回必 25P02（锁外包事务对齐 BalanceLedgerService） |
| MA-295 | R23I-16 | P2 | 温控 applyNow / 将邑关门·重启无设备范围（跨商户运维中断） |
| MA-296 | R23I-19 | P3 | 月租逐日整除截断（每台每月 0~29 分），按累计应扣−已扣补差 |
| MA-297 | R23I-20 | P3 | 结算单（split.createdAt）vs 月账单（order.createdAt）归月口径分叉 |
| （不领号） | R23I-17/18/21/22 | 观察 | 按 R23-FINAL §六先例留观察；R23I-18 可并入 MA-283 修法范围一并补闸 |

---

## 7. 最终判分

**成立 22 条（报告 P1×7 中本验维持 P1×5、降档 2）/ 不成立 0 条 / 重叠同族 4 处（R23I-02↔AU-062/DC-11、R23I-07↔R20-P2-6、R23I-08↔MA-124、越权 5 条↔MA-068 家族）/ 属拍板题 0 条 / 已修复 0 条。**

报告无编造迹象、行号准确率 100%（抽核 30+ 处全部命中）、自我撤回纪律良好；唯 P1 定级偏严 2 条、DC-11 交叉缺位 1 处。可整体采信，按 §6 建议号汇审登记。
