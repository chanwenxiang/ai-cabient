# 业务差距清单逐条复核（2026-11-22）

- **复核对象**：`docs/archive/BUSINESS_GAP_ANALYSIS_2026-10-06.md` §11「缺口清单真实进度（2026-10-07 逐条核对）」
- **快照**：`ac45ce55`（origin/dev，CI run 44990971228 全绿）
- **动因**：该文 §13.1 自述「§11 清单还剩 31 项，**可能还有类似过期项**」「本节只核了仓储域 6/9，剩余 25 项**未核** —— 不能假设它们都是真缺」。距 §11 已过一个半月，期间落了 V311–V321 等迁移与 DC-27 架构变更。
- **方法**：每项独立 grep + 读源码定写入/消费点，证据到 `文件:行`。**不采信任何文档自报状态**（含本文）。

---

## 1. 一句话结论

§11 的「31 项未做」里，**13 项已不成立**（已有实现，或对象已退役），**3 项受外部前置阻塞**，**3 项实为部分完成**，真正还缺且现在能做的只有 **10 项**。
其中最大的翻转是**仓储补货域**：§11 判「0✅/9❌，是当前最被忽略的整块空白」，复核后 **6 项已完成、2 项部分、1 项待拍板，零项完全未动**。

---

## 2. 总账（原 30 项逐条归位）

| 档 | 含义 | 项数 |
|---|---|---|
| A 已过期/不适用 | 文档说缺，实际已有；或对象已退役 | 13 |
| B 真缺·现在能做 | 纯代码，无外部前置 | 10 |
| C 真缺·受外部阻塞 | 依赖支付认证 / 真实微信 / 硬件协议 | 3 |
| D 部分完成 | 已有骨架但缺关键环节 | 3 |
| E 待拍板 | 需业务决策后才能动 | 1 |

> ⚠️ 原表「31 项」本身是错的：设备运维域小计写「8❌」，逐项数只有 7 项 ❌（#4/#5/#7/#8/#10/#11/#12），✅ 实为 2 项（#1 真机指令、#3 云端视觉——后者是「路线放弃」而非完成）。真实基数 **30 项**。

---

## 3. A 档：已过期 / 不适用（13 项，附实证）

| 域 | # | 原判 | 复核结论 | 证据 |
|---|---|---|---|---|
| 资金 | 4 | 无提现实名/KYB 校验 | ✅ **已有** V321 | `WithdrawEligibilityService.java:12-51`：商户侧 `requireMerchantQualified` + 申请人实名；线长侧未绑实名用户直接 403。注释写明「本地只留布尔位」是**有意的隐私设计** |
| 仓储 | ② | 盘点差异无原因分类 | ✅ **已有** V312 | `WarehouseService.java:356` `StocktakeDiffReason.matchesDirection(delta, command.diffReason())`；`:376` 注释「V312 盘点差异原因分类」 |
| 仓储 | ③ | 跨仓调拨在途无损耗 | ✅ **已有** | `WarehouseTransferLine.java:27-38` 有 `receivedQty` / `lossQty` / `lossReason` / `lossNote` |
| 仓储 | ⑤ | 仓库侧无核销/报废 | ✅ **已有** V313 | `InventoryOpsService.java:66-91`：`warehouseId` 与 `deviceId` 二选一，仓库侧走独立仓储范围、不受设备闸限制 |
| 仓储 | ⑥ | 仓库侧无近效期预警 | ✅ **已有**（§13 已判不成立） | `OpsReplenishmentController:253` / `MerchantPortalController:272` 均有 `expiryAlerts` |
| 仓储 | ⑦ | 采购退货无原因分类/残次品 | ✅ **已有** V318 | `PurchaseReturn.java:36-39` `defectiveFlag`；`ProcurementService.java:402-407` 退货原因分类/责任方/残次标记 |
| 仓储 | ⑧ | 供应商无对账单 | ✅ **已有** | `SupplierReconciliationService.java`（同目录另有 `InventoryOpsService:200` 索赔额非负闸，防「凭负数索赔减少应付」） |
| 设备 | 4 | APK 硬编码凭据 + debug key + cleartext + OTA 不验签 | ⛔ **对象已退役** | `47188717`：2026-10-18 拍板「暂时只上将邑套件」⇒ 自研 `edge/android-app` 整模块删除、git 历史留档。仓内 `.kt` 文件数 = 0（`git ls-files "*.kt" \| wc -l`） |
| 设备 | 12 | Kafka 识别入口绕过 deviceId 校验 | ⛔ **入口已下线** | 全仓 `KafkaListener` 仅剩 `KafkaEnableConfig` + `NotificationDispatchListener`；视觉 Kafka 消费随将邑端侧改造移除 |
| 营销 | 4 | 券可用范围维度 | ✅ **已有** V319 | `CouponDefinition.java:48-59` `scopeType`（ALL/DEVICE/MERCHANT）+ `scopeDeviceIds` / `scopeMerchantId`；`CouponScopeValidator` 注释：「迁移前 `device_scope` 是**死字段**」 |
| 营销 | 5 | 触达渠道仅站内信 | ✅ **已有** | `NotificationProperties`：`wechatEnabled` / `smsEnabled` / `asyncEnabled`；`ExternalNotificationDispatcher.java:62,70` 真消费两个开关 |
| 营销 | 6 | 会员等级权益仅文案 | ✅ **已有** | `MemberLevelRule.java:39` `priceDiscountPct` + `:36` `pointsRate`；消费点 `ConsumerMemberFacade.java:90` |
| 营销 | 7 | 营销 ROI 看板单维度 | ✅ **已有** | `MarketingRoiService.java:66` `placements(days)` + `:150` `list(days)` 两套维度 |

---

## 4. B 档：真缺·现在能做（10 项）

| 域 | # | 缺口 | 实证 | 量级判断 |
|---|---|---|---|---|
| 资金 | 5 | 银行预留手机号 | `payout_account` 建表（`V307:21-51`）无该列；后续 `V309` 只补 `bank_code` / `bank_province_city` | 小（加列 + DTO），**但无银行代付签约前无消费方** |
| 资金 | 6 | 开票金额无「未开票冻结」 | `InvoiceService` 仅工单状态机，无 freeze/hold 概念 | 中（涉及余额可用额口径） |
| 资金 | 8 | 无保证金/押金模型 | `depositCents` 仍只作 `ConsumerPreauthService` 开门预授权额度 | 中，**需先定业务是否收保证金** |
| 资金 | 10 | 无到账时效/截单配置 | `WithdrawPolicyResolver` 全量方法只有金额上下限/免审阈值/费率/封顶，无 timeliness/cutoff | 小（加配置键 + 展示） |
| 资金 | 14 | 无分期/按量计费账单 | 全仓无 `installment` / `usageBased` / `meteredBilling` | 大（新账务模型） |
| 设备 | 5 | 无远程配置下发 | 无 `RemoteConfig` / `device_config` 任何实现 | 中 |
| 设备 | 7 | 耗材零管理 | 全仓无 `Consumable` 相关 | 中，**需先有耗材清单业务定义** |
| 设备 | 11 | `OFFLINE_AFTER_MINUTES=2` 硬编码 | `DevicePresenceService.java:28` `private static final long OFFLINE_AFTER_MINUTES = 2;` | 小（挪 SystemConfig，与 `WithdrawPolicyResolver` 同款） |
| 营销 | 2 | 分享/邀请裂变归因 | `inviteCode` / `invitedBy` 仅存在于 `Member.java:33,35`，**零读写点** | 中，**需先定奖励规则** |
| 营销 | 3 | 抽奖/互动游戏 | 全仓无 lottery 相关 | 大（新产品功能） |

---

## 5. C 档：真缺·受外部前置阻塞（3 项）

| 域 | # | 缺口 | 阻塞点 | 证据 |
|---|---|---|---|---|
| 资金 | 11 | 打款退票/查单终态回查 | 支付通道未签约 | `PayoutChannel.java:64` 接口只有 `transfer()`，无 query 方法；`BankPayoutChannel` 无 status 查询 |
| 资金 | 13 | 微信分账 30% 上限未校验 | 真实微信支付未接入 | `WeChatProfitSharingService.java:124-133`：`platformCents<=0` 时跳过分账（2026-10-17 拍板「平台分账先不收」），注释**自认**「全额分账是否被微信服务商允许**未实证**（CB-032 的 30% 上限仅 ⚪ 旁证）」。⚠️ 一旦运营把 `platformRateBps` 填成 500（5%），`merchantCents` = 毛额 95% > 30% ⇒ 微信侧会拒。且 `MerchantService.java:149` 写入侧**无范围校验** |
| 设备 | 8 | 货道级故障无独立遥测 | 需硬件协议 | 全仓无 `slotFault` / 货道级错误上报 |

---

## 6. D 档：部分完成（3 项）

| 域 | # | 原判 | 实际 | 差距 |
|---|---|---|---|---|
| 仓储 | ① | 货损责任归属全缺 | ⚠️ **大半已有** | `InventoryWriteOff` 现有 `warehouseId` / `reasonCategory` / `responsibleParty` / `claimNo` / `claimAmountCents`（`:21-58`）。缺的是 `merchantId` / `supplierId` **结构化外键**（`responsibleParty` 是自由文本） |
| 仓储 | ⑨ | 仓库月结不接财务结算 | ⚠️ 月结已存在 | `WarehouseMonthlyCloseLine` 实体在；「接财务结算」的口径需产品确认 |
| 设备 | 10 | 灰度无分批推进语义 | ⚠️ 有百分比灰度 | `OtaService.java:101` `grayPercent` 钳 0–100（MA-125）；缺的是**按设备分组/波次**推进 |

---

## 7. E 档：待拍板（1 项）

| 域 | # | 事项 | 现状 |
|---|---|---|---|
| 仓储 | ④ | 盘亏不联动供应商应付 | 原文档 §14 已给出「先 C 后 A」建议，且 V311 已把 `responsible_party` / `claim_no` / `claim_amount_cents` 存好 ⇒ **方案 C 只差一个查询端点 + 台账视图**。阻塞点未变：批次→采购单追溯链不存在，且「盘亏≠供应商赔」需业务确认 |

---

## 8. 对原文档的纠偏

1. **§11 总账 31 项是数字错误**，逐项数实为 30 项（设备域小计写 8❌，实际 7❌）。
2. **§11 加的「#3 云端视觉生产不可用 ✅ 不再需要」不应计入 ✅**——这是「路线放弃」，不是「功能完成」，计入完成数会让「已做 5 项」虚高。
3. **§13 的 ①③⑤ 判据已过期**，与 §11 同期写就但结论已被 V311/V312/V313 覆盖。
4. **§13/§14 自相矛盾**：§13 说「货损无理赔单号」，§14 说「V311 已存 `claim_no`」——后者才对（见本文 §6 仓储①）。

---

## 9. 建议下一步（按价值/风险排序，不自动执行）

1. **资金 13（分账 30% 上限）**——虽然受真实微信支付阻塞，但**校验本身可以先落**：在 `MerchantService` 写入侧给 `platformRateBps` 加上限（≤7000 bps，即平台抽成 ≤70%，保证接收方 ≤30%），并在 `prepareSubmit` 加 fail-closed 断言。这是**资损/上线阻塞类**，且改动小、不依赖支付接入。
2. **设备 11（离线阈值配置化）**——照 `WithdrawPolicyResolver` 的 SystemConfig 模式，一个常量挪配置，20 行内。
3. **资金 10（到账时效/截单）**——纯运营参数，与 11 同款。
4. **仓储 ④ 方案 C**——`InventoryWriteOff` 台账查询端点 + admin 视图，数据已备，零资损风险；需先确认是否认可「先 C 后 A」。
5. 其余（资金 6/8/14、设备 5/7、营销 2/3）**都需要产品/业务决策**才能动，不建议在决策前写代码。

---

## 10. 复核方法与限制

- 每项至少两条独立判据：① 字段/接口是否存在（grep）；② **写入侧与消费侧是否真接**（避免「字段存在=功能完成」的假绿）。示例：券的 `device_scope` 曾是死字段、`inviteCode` 至今仍是死字段——两者都存在，但前者已由 V319 接活、后者没有。
- **未复核**：原 ⚠️ 档中未逐条验的 3 项（设备 2 自检、设备 6 健康评分、设备 9 自助诊断）——它们原本就不是「未做」，本文不动。
- **不做**：不改任何业务代码；本文件只做状态归位。DEPLOY/资金链路结论以源码为准。
