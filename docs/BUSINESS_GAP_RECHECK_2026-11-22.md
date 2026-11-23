# 业务差距清单逐条复核（2026-11-22）

- **复核对象**：`docs/archive/BUSINESS_GAP_ANALYSIS_2026-10-06.md` §11「缺口清单真实进度（2026-10-07 逐条核对）」
- **快照**：`ac45ce55`（origin/dev，CI run 44990971228 全绿）
- **动因**：该文 §13.1 自述「§11 清单还剩 31 项，**可能还有类似过期项**」「本节只核了仓储域 6/9，剩余 25 项**未核** —— 不能假设它们都是真缺」。距 §11 已过一个半月，期间落了 V311–V321 等迁移与 DC-27 架构变更。
- **方法**：每项独立 grep + 读源码定写入/消费点，证据到 `文件:行`。**不采信任何文档自报状态**（含本文）。

---

## 1. 一句话结论

§11 的「31 项未做」里，**14 项已不成立**（已有实现，或对象已退役），**3 项受外部前置阻塞**，**3 项实为部分完成**，真正还缺且现在能做的只有 **10 项**。
其中最大的翻转是**仓储补货域**：§11 判「0✅/9❌，是当前最被忽略的整块空白」，复核后 **6 项已完成、2 项部分、1 项待拍板，零项完全未动**。

> 🔴 **2026-11-23 复验订正（见 §11）**：本文 §7 原判「仓储④ 只差一个查询端点 + 台账视图」**是错的** ——
> 端点（`WriteOffClaimLedgerService`，`f70e3e8d`）、页面（`WriteOffClaimLedgerView.vue`，`aa5004b0`）、
> 路由（`warehouse/claims`）、菜单（`menu.ts`/`sidebar.ts`）**四件齐全且页面真调端点**。
> 故 ④ 由「E 待拍板」改列「A 已不成立」，总账 13/10/3/3/1 → **14/10/3/3/0**。

---

## 2. 总账（原 30 项逐条归位）

| 档 | 含义 | 项数 |
|---|---|---|
| A 已过期/不适用 | 文档说缺，实际已有；或对象已退役 | 14 |
| B 真缺·现在能做 | 纯代码，无外部前置 | 10 |
| C 真缺·受外部阻塞 | 依赖支付认证 / 真实微信 / 硬件协议 | 3 |
| D 部分完成 | 已有骨架但缺关键环节 | 3 |
| E 待拍板 | 需业务决策后才能动 | 0 |

> ⚠️ 原表「31 项」本身是错的：设备运维域小计写「8❌」，逐项数只有 7 项 ❌（#4/#5/#7/#8/#10/#11/#12），✅ 实为 2 项（#1 真机指令、#3 云端视觉——后者是「路线放弃」而非完成）。真实基数 **30 项**。
>
> 🔴 **2026-11-23 复验**：A 由 13 改 14、E 由 1 改 0（仓储④ 实证已完整落地，见 §3 末行与 §11.1）。

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
| 仓储 | ④ | 盘亏不联动供应商应付（原判「只差一个查询端点 + 台账视图」） | ✅ **2026-11-23 复验：方案 C 已完整落地** | 端点 `WriteOffClaimLedgerService`（`listClaims`/`sumByParty`，`f70e3e8d` 2026-10-07）+ 页面 `clients/admin-vue/src/views/warehouse/WriteOffClaimLedgerView.vue`（`aa5004b0` 2026-10-30）**真调** `AdminEndpoints.inventoryWriteOffClaims(:78)` / `…Summary(:94)` + 路由 `router/index.ts:244 warehouse/claims` + 菜单 `config/menu.ts:230` + 侧栏图标 `config/sidebar.ts:83`。⚠️ 本文原判「只差端点和视图」错在**只 grep 了后端**：前端三件（页面/路由/菜单）当时已存在 |

---

## 4. B 档：真缺·现在能做（10 项）

| 域 | # | 缺口 | 实证 | 量级判断 |
|---|---|---|---|---|
| 资金 | 5 | 银行预留手机号 | `payout_account` 建表（`V307:21-51`）无该列；后续 `V309` 只补 `bank_code` / `bank_province_city` | 小（加列 + DTO），**但无银行代付签约前无消费方** |
| 资金 | 6 | 开票金额无「未开票冻结」 | `InvoiceService` 仅工单状态机，无 freeze/hold 概念 | 中（涉及余额可用额口径） |
| 资金 | 8 | 无保证金/押金模型 | `depositCents` 仍只作 `ConsumerPreauthService` 开门预授权额度 | 中，**需先定业务是否收保证金** |
| 资金 | 10 | 无到账时效/截单配置 | ✅ **已落 MA-391（2026-11-22）**：原判「`WithdrawPolicyResolver` 无 timeliness/cutoff」属实，但**进一步的取证推翻了「加两个开关」的做法** —— ① 到账时效其实已有半套：`settle_unlock_hour`（CB-035，`MerchantWithdrawService:915`）管「T+1 后几点可申请提现」；② 「截单」在本系统**无对象可拦**：打款是运营审核后**逐单**触发（`MerchantWithdrawController:107` → `payout`），没有批量出款窗口。故不加开关（加了就是没人消费的死开关，正是 `check-feature-flags` R2 要抓的），改为把真口径写进打款模式面板真值提示：`MerchantWithdrawPayoutService.modeInfo()` / `LineWithdrawPayoutService.modeInfo()` 的 `note` + 结构化 `settleUnlockHour` | 已收口（口径展示，非新开关） |
| 资金 | 14 | 无分期/按量计费账单 | 全仓无 `installment` / `usageBased` / `meteredBilling` | 大（新账务模型） |
| 设备 | 5 | 无远程配置下发 | 无 `RemoteConfig` / `device_config` 任何实现 | 中 |
| 设备 | 7 | 耗材零管理 | 全仓无 `Consumable` 相关 | 中，**需先有耗材清单业务定义** |
| 设备 | 11 | `OFFLINE_AFTER_MINUTES=2` 硬编码 | ✅ **已修 MA-390（2026-11-22）**：原判属实（`DevicePresenceService:28` 常量）。现改为 SystemConfig 键 `device.offline.threshold_minutes`（默认 2，seed + 注册表已登记），一次扫描只解析一次阈值（扫描途中改配置不会让同一批设备用两个 cutoff）；读侧 0 / 负数 / >30 分钟一律回落 2 —— 阈值被填成 0 或极大值会让「离线」永不成立，属 fail-open | 已收口 |
| 营销 | 2 | 分享/邀请裂变归因 | `inviteCode` / `invitedBy` 仅存在于 `Member.java:33,35`，**零读写点** | 中，**需先定奖励规则** |
| 营销 | 3 | 抽奖/互动游戏 | 全仓无 lottery 相关 | 大（新产品功能） |

---

## 5. C 档：真缺·受外部前置阻塞（3 项）

| 域 | # | 缺口 | 阻塞点 | 证据 |
|---|---|---|---|---|
| 资金 | 11 | 打款退票/查单终态回查 | 支付通道未签约 | `PayoutChannel.java:64` 接口只有 `transfer()`，无 query 方法；`BankPayoutChannel` 无 status 查询 |
| 资金 | 13 | 微信分账 30% 上限未校验 | ⚠️ **一半已落 MA-389（2026-11-22）**：提交闸到位——`ProfitSharingCapPolicy`（单源判据）+ `WeChatProfitSharingService.prepareSubmit` 在**唯一出口** fail-closed（超限不发请求、状态不动、原因落 `failure_reason`，运营台「失败原因」列直接可见）；上限走 SystemConfig `fund.profit_sharing.receiver_max_percent`（默认 30，资金键守卫 + 天花板 100 + 强制留痕），0/越界回落 30 而不是「不限制」。**仍受外部阻塞的是比例真值**：CB-032 的 30% 只有 ⚪ 旁证，拿到微信书面确认前提额不可当真。⚠️ 另：原判「`MerchantService:149` 写入侧无范围校验」**是错的**——`UpsertMerchantRequest.platformRateBps` 带 `@Min(0) @Max(10000)` 且 `MerchantAdminController:47` 有 `@Valid` | 闸已收口；真收分账仍需 CB-032 六问 |
| 设备 | 8 | 货道级故障无独立遥测 | 需硬件协议 | 全仓无 `slotFault` / 货道级错误上报 |

---

## 6. D 档：部分完成（3 项）

| 域 | # | 原判 | 实际 | 差距 |
|---|---|---|---|---|
| 仓储 | ① | 货损责任归属全缺 | ⚠️ **大半已有** | `InventoryWriteOff` 现有 `warehouseId` / `reasonCategory` / `responsibleParty` / `claimNo` / `claimAmountCents`（`:21-58`）。缺的是 `merchantId` / `supplierId` **结构化外键**（`responsibleParty` 是自由文本） |
| 仓储 | ⑨ | 仓库月结不接财务结算 | ⚠️ 月结已存在 | `WarehouseMonthlyCloseLine` 实体在；「接财务结算」的口径需产品确认 |
| 设备 | 10 | 灰度无分批推进语义 | ⚠️ 有百分比灰度 | `OtaService.java:101` `grayPercent` 钳 0–100（MA-125）；缺的是**按设备分组/波次**推进 |

---

## 7. E 档：待拍板（0 项）

> 原 1 项「仓储 ④ 盘亏不联动供应商应付」经 **2026-11-23 复验判定为「已完整落地」**（方案 C 的端点 + 页面 + 路由 + 菜单四件齐全），
> 已移入 §3 A 档末行，证据见 §11.1。
>
> ⚠️ 该结论的教训：原文只 grep 了后端（`InventoryWriteOff` 字段 + 有无查询端点），**没查前端是否接上** ——
> 而「后端有端点 ≠ 运营能用」。反过来也成立：本次差点因为「`endpoints.ts` 里有声明」就再判一次「未接页面」，
> 是 `grep 调用点` 才确认页面真的在用。**两侧都要取到证据才算数**（同 §10 的方法学结论）。

---

## 8. 对原文档的纠偏

1. **§11 总账 31 项是数字错误**，逐项数实为 30 项（设备域小计写 8❌，实际 7❌）。
2. **§11 加的「#3 云端视觉生产不可用 ✅ 不再需要」不应计入 ✅**——这是「路线放弃」，不是「功能完成」，计入完成数会让「已做 5 项」虚高。
3. **§13 的 ①③⑤ 判据已过期**，与 §11 同期写就但结论已被 V311/V312/V313 覆盖。
4. **§13/§14 自相矛盾**：§13 说「货损无理赔单号」，§14 说「V311 已存 `claim_no`」——后者才对（见本文 §6 仓储①）。

---

## 9. 建议下一步（按价值/风险排序，不自动执行）

> ⚠️ **本节第 1 条的在先写法有算术错误，已订正**：原文写「`platformRateBps` 加上限 ≤7000 bps，
> 即平台抽成 ≤70%，保证接收方 ≤30%」——方向搞反了。接收方占比 = `1 − 平台抽成`，
> 要让接收方 ≤30% 必须让平台抽成 **≥70%**，写「上限 ≤7000」恰好把合法档位全挡掉。
> 且「拦配置」会否掉现实档位（平台抽 5%），属产品决策而非缺陷修复。故实际做法改为
> **提交出口 fail-closed + 运行期可调上限**（MA-389），不动账本状态机。

1. ~~资金 13：写入侧给 `platformRateBps` 加上限~~ → **已按订正后的做法落地（MA-389）**。
2. ~~设备 11（离线阈值配置化）~~ → **已落地（MA-390）**。
3. ~~资金 10（到账时效/截单）~~ → **已收口（MA-391，口径展示；截单在本系统不适用）**。
4. ~~仓储 ④ 方案 C——`InventoryWriteOff` 台账查询端点 + admin 视图~~ → **2026-11-23 复验：早已落地**（见 §11.1），本条不作为待办。
5. 其余（资金 6/8/14、设备 5/7、营销 2/3）**都需要产品/业务决策**才能动，不建议在决策前写代码。
6. **分账模型本身**（平台抽成 >0 时商户份额 ~95% > 30% 上限）是 CB-032 六问的一部分：
   现闸只保证「不发必被拒的请求」，要让 rate>0 真能出款，仍需拍板走「持牌分账」或改「钱包入账 + 转账出款」。

---

## 10. 复核方法与限制

- 每项至少两条独立判据：① 字段/接口是否存在（grep）；② **写入侧与消费侧是否真接**（避免「字段存在=功能完成」的假绿）。示例：券的 `device_scope` 曾是死字段、`inviteCode` 至今仍是死字段——两者都存在，但前者已由 V319 接活、后者没有。
- **未复核**：原 ⚠️ 档中未逐条验的 3 项（设备 2 自检、设备 6 健康评分、设备 9 自助诊断）——它们原本就不是「未做」，本文不动。
- **不做**：不改任何业务代码；本文件只做状态归位。DEPLOY/资金链路结论以源码为准。

---

## 11. 2026-11-23 复验（动手前重取证据）

本文件自己写着「不采信任何文档自报状态（**含本文**）」。所以开工清缺口前逐项重取了一次，**又抓到自己一处错误**（§11.1）。

### 11.1 仓储 ④：判「只差端点 + 视图」是错的 —— 四件早已齐全

| 件 | 证据 |
|---|---|
| 后端端点 | `WriteOffClaimLedgerService`（`f70e3e8d`，2026-10-07）：`listClaims` 只列「责任方非空 **且** 索赔额 > 0」；`sumByParty` 按责任方聚合；控制器 `OpsReplenishmentController:233/248` |
| 前端 API | `clients/admin-vue/src/api/endpoints.ts:951-963`（`inventoryWriteOffClaims` / `…Summary`） |
| 页面 | `clients/admin-vue/src/views/warehouse/WriteOffClaimLedgerView.vue`（`aa5004b0`，2026-10-30，282 行），**真调**上面两个端点（`:79` / `:95`） |
| 路由 + 菜单 | `router/index.ts:244`（`warehouse/claims`）、`config/menu.ts:230`、`config/sidebar.ts:83`（`Money` 图标） |

**为什么会判错**：当时只 grep 了后端（实体字段 + 有无查询端点），没查前端调用点。
教训与本文 §10 同源，但要补一条方向：**「端点存在」与「页面在调」是两个独立判据，缺任一条都不能下结论** ——
这次差点反向再错一次（只看到 `endpoints.ts` 有声明就判「未接页面」，而声明恰恰是页面要用的前提）。

### 11.2 B 档 10 项重取结论

| 项 | 2026-11-23 重取 | 结论 |
|---|---|---|
| 资金 5 银行预留手机号 | `payout_account` 建表无该列；全仓仅 `BankPayoutChannel.java:21` 注释提到「部分银行要求银行预留手机号做二次确认」 | 仍缺（无代付签约前无消费方） |
| 资金 6 未开票冻结 | `InvoiceService` 无 freeze/hold 概念 | 仍缺 |
| 资金 8 保证金/押金模型 | `depositCents` 只在 `DeviceInfo:111`（设备押金 / 开门预授权额度） | 仍缺 |
| 资金 10 到账时效 / 截单 | MA-391 已收口（口径展示） | ✅ |
| 资金 14 分期 / 按量计费 | 全仓无 `installment` / `meteredBilling` / `usageBased` | 仍缺（大） |
| 设备 5 远程配置下发 | ⚠️ **口径更正**：并非「全仓无任何实现」—— `MerchantDeviceSettingsDto` + `MerchantDevicePortalService.updateDeviceSettings(:146)` 可改设备名/告警联系人/目标温度，并对在线柜下发 `SET_TARGET_TEMP`。缺的是**版本化远程配置**（配置版本、批量、ACK 回执、回滚） | 部分（原判偏绝对） |
| 设备 7 耗材管理 | 全仓无 `consumable` | 仍缺（需耗材清单业务定义） |
| 设备 11 离线阈值硬编码 | MA-390 已收口 | ✅ |
| 营销 2 分享 / 邀请裂变 | grep `inviteCode` / `invitedBy` 全仓命中**仅** `Member.java:33,35` 两处字段 | 仍缺（零读写点） |
| 营销 3 抽奖 / 互动 | 全仓无 lottery 相关 | 仍缺（大） |

⇒ B 档仍缺 8 项，其中 **6 项要产品决策**（资金 6/8/14、设备 7、营销 2/3），设备 5 属「可做但缺业务定义」。

### 11.3 新发现并当日修复：将邑车队运维指令「假成功」（MA-403）

清缺口途中撞到一条比清单上任何缺口都更该先修的缺陷：**运营台 / 商户端的设备指令按钮，对当前唯一真实车队是假成功**。

- **事实链**：将邑柜不接 MQTT，只连 `jiangyi-gateway` 的 WebSocket；我方已实现的 10 个 msgType 里**没有**自检、没有温控（`docs/JIANGYI_INTERFACE_CHECKLIST.md:30` 记「B WS 下行 17 条」，逐条对照）。
- **代码路径**：`DeviceServiceClient` 只对**开门**做了 `isJiangyi` 分支（`:55` / `:72`）；`requestOpsCommand`（`:133`）与 `requestSetTargetTemp`（`:119`）直接发 device-service 的 MQTT ⇒ 发给没有订阅者的主题，接口当场回 200「已下发」，15s 后 `DeviceCommandTracker` 置 TIMEOUT。
- **可达路径**：商户端 `POST /merchant/devices/{id}/commands`（`MerchantPortalController:169`）→ `AdminDeviceOpsService.executeForMerchant` → `reboot` → MQTT。**而将邑侧 `reboot`（§4.3.2.1）2026-10-23 就已实现并真机验过**（下发 → `rebootSure` → WS 断 → 约 28s 重连）。
- **修复**：新增单源能力表 `JiangyiCommandCapability`（GATEWAY / PLATFORM_ONLY / UNSUPPORTED）；`reboot` 转 `JiangyiDeviceOpsService`（复用「进行中业务」409 闸与审计，不另写第二份闸）；自检 / 设温明确 501；营业锁对将邑不再伪造 LOCK；温控计划写入侧拒绝启用（存量计划仍可停用）。
- **类级收口**：门禁 `check:jiangyi-command-capability` —— 新增任何 `MQTT_CMD_*` 常量不声明将邑归属即红；15 断言夹具（真树绿 + 6 注入红）。

> ⚠️ 这条不在原 30 项差距里，是**缺陷**而非**缺口** —— 但它比清单上任何一项都更影响现场：
> 商户以为柜子重启了、实际没动；且 `DeviceTempPlanService` 每分钟往将邑柜发一条注定超时的温控指令。
