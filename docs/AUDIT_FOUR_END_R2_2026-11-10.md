# 四端代码审核报告 R2（2026-11-10，五面全量）

> **基线**：`dev` @ `5215c129`（「fix(jiangyi): 上架预览收 CREATED 挂接…」）。工作树仅 `scripts/probe-zhima-payafteruse.py` 一处未提交改动（不在审计范围）。
>
> **范围（五面全量）**：`clients/admin-vue`（路 A）、`clients/merchant-mp`（路 B）、`clients/consumer-mp` + `clients/alipay-mp-shell` + `packages/shared-uni`（路 C）、`services/trade-service` 资金域 + V430+ 迁移（路 D）、后端其余域（auth/RBAC/审批/定时任务/jiangyi 集成/device-service/jiangyi-gateway/infra，路 E）。
>
> **方法**：5 路并行只读子代理（4 并发批 + 1 后台批）+ **主审逐条亲验**——按用户指令「所有问题都验证」，路 A–D 的 **44 条 + 路 E 的 4 条 = 48 条发现全部由主审读当前源码逐条复核**（非抽样）；**1 条撤账（D4）、1 条部分证伪（A17）、5 处引用/范围修正（A3/A4/A5/A8/B13）**，撤账与修正均写明源码依据。
>
> **复核标记**：本轮默认全部 ✅（主审亲验）。与子代理结论有出入的，在 §7「撤账与修正」逐条写明；**1 条撤账（D4）、1 条部分证伪（A17）、5 处引用/范围修正（A3/A4/A5/A8/B13）**。
>
> **纪律**：已知有意设计（无称重、商户端仅密码登录、补货自提自送、分仓负责人多值、库存真值 `device_sku_inventory`、订单优惠四列口径、`platform_rate_bps=0`⇒LEDGER_ONLY、FAILED 回放放行、壳订单页如实说明、H5 不作验收面等）已排除；上轮 `AUDIT_FOUR_END_2026-11-09.md` 全部 30+ 条已修项不重报。

---

## 1. 总评

| 面 | P1 | P2 | P3 | 结论 |
|---|---|---|---|---|
| admin-vue（A） | 8 | 6 | 4 | **系统性 P1：事件期未捕获异常被错误边界整页替换**；上轮「资金静默失败」修复只覆盖一半；盘点/调拨写动作缺确认或保存守卫 |
| merchant-mp（B） | 1 | 7 | 6 | 备货幂等键无失效时机（任务取消后假成功）；上轮 §7#1 修复引入 2 条回归（B3/B9） |
| consumer-mp + 壳（C） | 0 | 1 | 4 | 上轮 8 项修复全部在位无回归；新发现集中在「上传 401 自愈判据死代码」（C1） |
| 后端资金域（D） | 1 | 1 | 4 | 无 P0；`updateById 跳 null` 家族仍有 3 处漏网（D1/D2/D3），其中解绑残留属权限面（D2 升 P1） |
| 后端其余域（E） | 2 | 2 | 0 | `updateById 跳 null` 家族再抓 2 处（E1 清主图删文件/E2 券定义清列）；定时任务注册表漏 1 项（E3）；将邑挂接重绑回归 22f9ff5a 已修不变式（E4） |

> **本轮无 P0；总账 48 条 = P1×12 / P2×18 / P3×17 + 撤账×1（D4）。** 上轮 3 条 P0 修复（幂等键提升、开门键 TTL+终态闸、错误边界外格式）经回归抽查全部在位，且未发现修复本身引入的 P0 级回归；§12 残留 2 条（指纹拦截、409 换键）同样在位。

**一句话**：admin 侧的最大问题不再是「静默失败」而是「失败太响」——`PageErrorBoundary` 把事件处理里的任何异常（包括确认框点「取消」）放大成整页错误页，A2–A5/A16 的每一条都以它为放大器；商户侧的备货幂等键修好了「重试重复建任务」，却留下了「取消后回放假成功」的新尾巴；后端 `updateById 跳 null` 家族两路共抓 **5 处漏网**（D1 补扣清券/D2 解绑商户/D3 failReason/E1 清主图删文件/E2 券定义清列）——M01 显式 UPDATE 范式已覆盖 16 处，仍漏的都在「新近长出来的写路径」上。

---

## 2. 上轮修复回归抽查（全部 ✅ 在位，除注明外）

### admin-vue 12 项
| # | 项 | 结论 |
|---|---|---|
| 1 | P1-1 设备策略 | **在位**（`policy=null`+`policyLoadFailed`+拒绝保存+`reloadPolicy`+错误块）。新发现上游逃逸路径与失败后不回滚 ⇒ A8/A9 |
| 2 | P1-2 补货路线 | **在位**（在途判断在 `begin()` 前、finally 无条件复位） |
| 3 | P1-3 反馈删除 | **在位**（`ops:feedback:reply` 两处统一） |
| 4 | P1-4 选品诊断 | **在位**（批量+行动作 `ops:sku-review:edit`） |
| 5 | P1-5 资金写操作 | **部分修复**：`submitPayout`/FeeLedger 结清/MachinePayee 加载已修；**同文件其余写操作漏网** ⇒ A3/A4 |
| 6 | P2-1 上柜兜底 | **在位**（`shelvedQtyText`）；remainderQty 缺失子情形仍按 0 ⇒ A18（不触发） |
| 7 | P2-3/4 导出范围 | **在位**（`scope()`+total 接线） |
| 8 | P2-5 关键词提示 | **在位**；提示条时机细节 ⇒ A18 |
| 9 | P2-6 供应商 CSV | **在位**；修复引入负数金额变文本 ⇒ A15 |
| 10 | §7#6 盘点确认 | **在位**；同族调拨漏修 ⇒ A5；新问题 A6/A12 |
| 11 | §7#7 工作台 | **在位**（`workbenchFailed`）；部分失败仍假 0 ⇒ A11 |
| 12 | 将邑商品页 e5fae132 | 契约正确；竞态/加载态 ⇒ A14 |

### merchant-mp 8 项
P0-3 幂等键**在位**（页面级 Map/成功保留/业务失败清/指纹拦截，铁律 82 第①层合格；第②③层缺口即 B1）；§7#1 **在位**但引入 B3、同形态扩散出 B9；§7#2 **在位**（兄弟函数未对齐 ⇒ B11）；§7#3 **在位**（summary 与列表解耦）；P2-2 **在位**；P2-8 **在位**；将邑新代码中文映射无问题（采集页 B6、后端同步 B7）；56c2e329 **在位**（边角 B13）。

### consumer-mp + 壳 8 项
P0-4 **在位且三层闭环**（TTL 键+`isAdoptableSession` 闸+`restoreActiveSession` 清键+后端状态闸+409 锚三方一致）；P1-6 **在位**（`number|null` 分流）；P1-7 **在位**（微信分支；支付宝分支漏堵 ⇒ C3）；P1-8 **在位**；§7#5 **在位**；P2-7 **在位**（单源+理由头，全仓无散写残留）；P1-9+壳门禁 **在位**（双锚在位；门禁 `:57` 死行 ⇒ C7）；深色弹窗批 **无新问题**（令牌双定义/对比度/层级/reduced-motion 全核）。

---

## 3. P1（路 A–D 10 条；路 E 的 E1/E4 见 §11，合计 12 条）

### A1 [P1·系统性] 页面事件里未捕获的异常被错误边界接住，整页替换成「本页出现异常」
- **位置**：`clients/admin-vue/src/components/PageErrorBoundary.vue:29-33`；挂载 `src/layouts/AdminLayout.vue:241-249`（包裹 router-view）；全局兜底 `src/main.ts:38-42` 只打印不拦截。
- **证据**（主审亲验）：
  ```ts
  onErrorCaptured((err) => {
    failed.value = true;          // 不区分错误来源，整页替换
    adminDevWarn('[admin] 页面渲染异常，已用兜底 UI 替代：', err);
    return false;
  });
  ```
  Vue 3.5.40（本仓实测版本）：事件处理函数返回的 Promise 挂 `.catch → handleError`，沿父链逐个调 `errorCaptured`；页面组件是边界的子孙 ⇒ 模板 `@click`、子组件 `emit('action')` 抛出的错误都被它接住，`return false` 后不再上抛。
- **触发与影响**：任何写在事件处理里、没有 try 的 `await api.request` / `await ElMessageBox.confirm`——接口失败（403/409/500）时运营看到的不是错误提示，而是整页被替换成「页面渲染出错」，**正在填的表单、筛选、标签页状态全部丢失**；确认框点「取消」（Element Plus 以 `'cancel'` reject）同样触发。A2–A5、A16 的每一条都以它为放大器。
- **刻意设计排查**：组件注释只声明针对「**渲染**抛错→白屏」；docs/规则/ISSUES 无事件期约定；`main.ts` 全局 errorHandler 到不了这层（边界已 `return false`）。
- **修法**：`onErrorCaptured` 按 `info`/错误来源区分——仅渲染/生命周期期错误切兜底页；事件期错误改 `ElMessage.error` 后返回 false。同轮落门禁（页面事件处理禁裸 await）。
- **置信度**：高（组件+布局+emit 链+运行时机制四点全验）。

### A2 [P1] 外部渠道导入：三个确认框点「取消」即整页崩
- **位置**：`src/components/warehouse/ExternalChannelImportTab.vue:199`（commitPreview）、`:228`（discardPreview）、`:356`（removeBind）。
- **证据**（主审亲验）：`await ElMessageBox.confirm(...)` 均在 `try` **外**；同文件先例（`useDeviceLifecycleActions.ts`、`SkuReviewView.vue`）都把 confirm 包在 try 内。
- **影响**：「提交批次/放弃批次/解绑」任一确认框点取消 → 100% 复现整页错误页。
- **修法**：confirm 挪进 try；catch 里 `isUserDismiss(e)` 直接 return。

### A3 [P1] 机器账单（场地方）：结清/出账/保存配置无错误处理；「手动出账/保存配置」按钮无权限门
- **位置**：`src/views/finance/MachinePayeeBillsView.vue:226-230`（settle）、`:247-257`（submitGenerate）、`:314-322`（saveConfig）、`:42`（手动出账按钮）、`:128`（保存配置按钮）；后端 `OpsMachinePayeeBillController` `:45/:62/:77` 均 `ops:device:edit`。
- **证据**（主审亲验）：三处 `await api.request` 无 try/catch；两个头部按钮无 `v-hasPermi`。
- **修正（对照子代理）**：行级「标记结清」动作**有** `perm: 'ops:device:edit'` 门（`rowActions`）——子代理「写按钮没有权限门」对该处不成立；缺门的是「手动出账/保存配置」两个头部按钮。
- **影响**：只有 `ops:device:list` 的账号能点「手动出账/保存配置」→ 必 403 → 经 A1 整页崩；有编辑权账号遇到失败也只崩页无业务提示；结清无二次确认。
- **修法**：三处补 try/catch+`ElMessage.error`；结清加确认；两按钮加 `v-hasPermi="['ops:device:edit']"`。

### A4 [P1] 线路与提成：建线路/归档/保存成员/移除成员无错误处理；归档/移除无确认；「新建线路」与「保存成员」无权限门
- **位置**：`src/views/finance/LineRoutesView.vue:442-456`（saveRoute）、`:458-462`（archiveRoute）、`:493-505`（saveMember）、`:510-520`（onMemberAction DELETE）、`:32`（新建线路按钮）、`:186` 附近（保存成员按钮）；后端 `OpsLineRouteController` `:44/:54/:62/:77/:88` 均 `ops:replenishment:edit`。
- **证据**（主审亲验）：四个写函数裸 `await`；归档/移除一点即生效。
- **修正（对照子代理）**：「移除成员」行动作**有** `perm: 'ops:replenishment:edit'`（memberActions）；缺门的是「新建线路」按钮与成员弹窗内「保存成员」按钮。
- **影响**：只读账号点「新建线路」或在成员弹窗点「保存成员」→ 403 → 整页崩；归档（停用线路）/移除（删提成成员）无确认直发。
- **修法**：四处补 try/catch；归档/移除加确认；两入口加 `hasPerm('ops:replenishment:edit')`。

### A5 [P1] 仓储调拨「发运/收货/取消」直发请求：无确认、无错误处理（§7#6 同族漏网，范围比子代理报的更大）
- **位置**：`src/composables/warehouse/useWarehouseTransfers.ts:113-118`（ship）、`:121-133`（**receiveTransfer，V314 真正收货入库**）、`:136-141`（cancel）；按钮 `WarehouseTransfersTab.vue:120/:130`。
- **证据**（主审亲验）：三个动作都裸 `await`（同 composable 的 `saveTransfer` 有完整 try/catch）。
- **影响**：发运扣调出仓库存、收货入库加调入仓库存——两个动库存的动作都是一点即发、无确认；失败时经 A1 整个仓储页崩。
- **修法**：参照 `confirmStocktakeWrite` 给三个动作加确认（收货已有明细表单路径可豁免表单内提交，仅拦「一键收货」入口）；补 try/catch。

### A6 [P1] 盘点「完成/完成并过账」不提交弹窗里未保存的实盘修改：按旧值过账或英文 400
- **位置**：`src/components/warehouse/WarehouseStocktakeDialogs.vue:86-92`（行内 `v-model="row.countedQty"` 仅本地）、`:121-136`（保存实盘/完成盘点/完成并过账三按钮并列）；`src/composables/warehouse/useWarehouseStocktakes.ts:125-169`（complete/completeAndAdjust 只 POST 不带行数据）；后端 `WarehouseStocktakeService.doComplete`（`lineRepository.findByStocktakeIdOrderByLineIdAsc` 为真源，`countedQty==null` 抛英文 400）。
- **证据**（主审亲验，前后端两侧）。
- **影响**：运营改了实盘数没点「保存实盘」直接点「完成并过账」→ 首次录入行 400「all lines must be counted…」（且违反中文文案约定）；改过已保存行则按**上次保存值**过账，随后详情刷新把输入框刷回旧值，修改静默丢失。
- **修法**：完成/过账前检测有未保存修改即先跑 `saveStocktakeLines`（或禁用按钮+提示）；后端 400 文案中文化。

### A7 [P1] 商户运营配置：慢响应晚到覆盖新商户表单，保存写错商户；附带 loading 永久卡死
- **位置**：`src/views/merchants/MerchantSplitsView.vue:1314-1335`（loadOpsConfig）、`:1337-1352`（saveOpsConfig）、`:291`（@change）。
- **证据**（主审亲验）：catch/finally 都判 `isCurrent`，**唯独成功赋值处没判**；`begin()` 在商户为空判断之前，空值分支提前 return 后在途请求的 finally 判过期不复位 `opsConfigLoading`。
- **影响**：选商户 A（慢）→ 改选 B（快）→ A 响应晚到覆盖表单（下拉显示 B）→ 点「保存」把 A 的拍照盘点/并发上限写进 B。清空商户选择会让加载遮罩永久卡住（直到再选商户）。
- **修法**：赋值前 `if (!isCurrent) return`；`begin()` 挪到空值判断后；保存前校验 `opsConfig.merchantId === opsConfigMerchantId`。

### A8 [P1·置信中] 设备详情：资产加载失败后空表单可保存，PATCH 空串清掉「所属仓库」；策略区因 `loadPolicy` 被跳过而空白且无失败标记
- **位置**：`src/views/devices/DeviceDetailView.vue:1363-1369`（`Promise.all([loadAsset(),…])` 失败 ⇒ `loadPolicy` 不执行）；`src/composables/device/useDeviceAsset.ts:96-104`（loadAsset 无失败态）、`:126-131`（`homeWarehouseId: asset.homeWarehouseId || ''` 恒回传）；后端 `OpsDeviceAdminService.applyHomeWarehouse(device, homeWarehouseId, /* allowClear */ true)`：`'' → trimToNull → null → setHomeWarehouseId(null)` + wrapper 显式写 null（主审亲验）。
- **证据**：主审亲验前后端；**修正（对照子代理）**：`onMounted` 的 catch 有全局「加载失败，请重试」toast——并非完全无信号；但 toast 不阻止保存，核心风险仍在。
- **影响**：`GET /ops/devices/{id}` 单点失败（detail 成功）时，资产 tab 渲染空表单；运营选好必填场景保存 ⇒ `homeWarehouseId:''` 把真实所属仓清掉（影响补货归仓）；策略锁区因 P1-1 的失败标记只在「loadPolicy 自身失败」置位，此处是「没被调用」⇒ 整块空白无错误块无重试。
- **修法**：`assetLoadFailed` 状态+失败禁用保存；`loadPolicy` 移出 `Promise.all`（或放 finally）。

### B1 [P1] 备货单幂等键无失效时机：任务被取消后再提交被回放成「已生成」假成功
- **位置**：`clients/merchant-mp/src/pages/prep-list/prep-list.vue:278/460-472`（页面级 Map 无 TTL、onShow 不清、成功不看 task.status）；后端 `MerchantReplenishmentService:421-430`（回放只校验 deviceId，**不看任务状态**）；`setClientRequestNo` 全仓仅建任务一处（`:490`），取消不清键（主审 grep 亲验）。
- **证据**（主审亲验）：
  ```java
  if (replay.isPresent()) { … return replenishmentService.getTaskDto(original.getTaskId()); } // 不看状态
  ```
- **触发**：备货成功 → 进补货任务页取消该任务 → 返回备货单页（onShow 自动重勾已成功柜）→ 再提交：数量没变 ⇒ 复用旧键回放已取消任务，页面报「已生成（任务 xxx）」实际什么都没建；数量变了 ⇒ 被指纹拦截「已按上次提交数量生成任务」，但那个任务已取消，商户被困死循环（只有退出页面重进才解）。
- **刻意设计排查**：「成功保留键」是 P0-3 拍板形态（不报）；本条报的是铁律 82 第②层（无 TTL/失效时机）与第③层（回放不校验状态）缺口，与上轮已知残留「传输未知+改量」是两回事。
- **修法**：回放命中且任务非 PENDING/IN_PROGRESS ⇒ 前端清该柜键+指纹、换新键真开一次；或后端回放对终态任务返回 409 指引重开（与 `SessionService.validateIdempotentReplay` 状态闸同构）。
- **置信度**：高。

### D2 [P1]（升级）设备解绑后 `merchant_id` 清不掉：原商户仍可见可操作该柜
- **位置**：`services/trade-service/.../DeviceAssetService.java:239/:244`（`setMerchantId(null)`）→ `:120`（`deviceInfoMapper.save` = updateById 跳 null）；`clearMerchantId`/wrapper 清列全仓 grep 零命中（主审亲验）。
- **波及面**（主审补验）：`MerchantScopeService.allowedDeviceIds` 按 `device.merchantId` 圈定；该集合被商户门户设备列表、运营异常、经营分析（`CompetitiveGapService:384/:495`）、自主补货（`canAccessDevice`）等全链消费 ⇒ 解绑后（未复绑窗口期）原商户的设备列表/异常/报表仍含该柜，且能继续对其发起操作。
- **刻意设计排查**：同函数对 `lifecycleRemark` 用 `blankToNull` 说明处理过清列语义；同族显式 UPDATE 先例五处（M01 范式），此处漏抄，非有意。
- **修法**：UNBIND 分支落库后补 `update…set(merchantId,null)`（或 `clearMerchantId` mapper 方法）。
- **定级说明**：子代理报 P2；按本项目定义「权限错误（漏拦）=P1」升级——残留的是对已解绑资产的访问权。影响限于「自己曾经的柜」，无跨租户泄露。

---

## 4. P2（16 条）

| # | 位置 | 结论（主审亲验） | 修法 |
|---|---|---|---|
| A9 | `useDeviceRemoteOps.ts:218-230` | 策略保存失败只 toast 不回滚不 reload：开关停在已拨位置，下次拨任意开关整份 PUT 把失败值一并写入；且无在途锁可并发 PUT | catch 里 `reloadPolicy()`+saving 锁 |
| A10 | `MerchantWithdrawView.vue:381-440/958-995` | 费率面板显示商户 A 的数据，改输入未重查时「保存/清除覆盖」作用于输入框里的 B；无 watch 复位 `feeLoaded`。「清除覆盖」确认框带 B 的号（部分缓解） | 记录 `feeLoadedMerchantId` 读写同源 |
| A11 | `DashboardView.vue:716-727/:768-771` | 聚合接口 500 走降级后：stats 失败而 workbench 成功 ⇒ `stats={}` 渲染假 0；异常数失败 ⇒ 待处理 0；均无提示（§7#7 只修了「全部失败」） | 分项失败显「—/加载失败」 |
| A12 | `useWarehouseStocktakes.ts:136-140` 等 4 处 | 写成功后 `reloadStocktakeDetail()` 在同一 try ⇒ 刷新失败时先「已完成」后「完成失败」两条相反提示；重试撞 409 | 刷新移出写 try，单独 catch |
| A13 | `VisionMappingView.vue:878-891` | `fetchPage` 内对 `binding`/`allMappings` 的赋值在 useCrudTable 丢过期页**之前** ⇒ 切柜时旧柜的 BOUND 态与全量映射盖掉新柜 | 附带数据赋值比对请求时 deviceId |
| A14 | `JiangyiProductsView.vue:107-122/:61-64` | 查状态无 seq（连选两商品旧驳回原因串显）；搜索在途时清空关键词早退不复位 `skuSearching`（下拉永久转圈）；手改条码不清 `pickedSkuId`（:219 v-model 外无任何复位） | 三处小修 |
| B2 | `MerchantWithdrawService.java:481-497`、`LineWithdrawService.java:308-322` | `validateAmount` 排在 `findByRequestNo` 回放**之前**：首笔已入库冻结、客户端超时重试（按设计保留 requestNo）⇒ 先撞「可用余额不足/超限额」，前端当业务失败清键——幂等重试契约在最常见场景失效（不会双扣，钱其实已提） | 回放查询移到 validateAmount 前（归属+金额校验保留） |
| B3 | `merchant-mp purchase.vue:156/302-335` | §7#1 修复引入：`loadCreate`/`loadOrders` 共用一个 `loadSeq`，申请页在途时切「单据」页签 ⇒ seq 被顶、`createLoading` 永不复位；切回「申请」不重载 ⇒ 卡加载或空白（下拉刷新可恢复） | 两加载各用 seq；或 finally 无条件复位+赋值处判新旧 |
| B4 | `device-stocktake.vue:46/:171/:257` | `evidenceUrl` 全仓仅上传成功一处赋值，换柜/重盘不清 ⇒ 「盘点必须拍照」策略被上一轮旧照片静默满足（后端只判非空） | `loadDevice` 开头+提交成功后清空 |
| B5 | `warehouse-stocktake.vue:408-431` | `loadList` 递增 `listSeq` 但不捕获、赋值不判 ⇒ 快速切状态页签旧响应覆盖新页签（loadMore 有守卫，首页没有） | `const seq=++listSeq`+赋值/finally 判 |
| B6 | `jiangyi-capture.vue:111-118/:141-153` | 换条码/提交成功后 `photos` 不重置 ⇒ A 商品实拍图可提交到 B 条码的采集任务（识别学错/审核驳回）；附带：`@blur="loadState"` 让失焦自动触发状态查询（子代理称该查询有厂商侧写副作用——⚠ 待路 E 复核） | 条码变化与提交成功后 `photos` 重置；去掉 blur 自动查 |
| B7 | `ReplenishmentService.completeTask`（@Transactional+runWithTaskLock）→ `finishCompletedTaskSideEffects` → `jiangyiRestockService.syncAfterRestock`（HTTP） | §7.11 同步跑在完成事务+任务行锁内：厂商接口慢 ⇒ 小程序 20s 超时但任务已完成（或事务挂锁）；重试 `finalizeAlreadyCompletedTask` 再同步再超时。「失败不阻断」的 catch 挡异常挡不住时延 | `TransactionSynchronization.afterCommit` 或事件异步化 |
| B8 | `bills.vue:194-199/:30` | 切月份加载失败只置 `loadError`、`bill` 不清 ⇒ 新月份标题下显示旧月销售额/利润；加载中同样短暂错配 | 失败/切筛选时 `bill=null` 或按月份校验渲染 |
| B9 | `settlements.vue:458-470`、`devices.vue:285-299`、`bills.vue:178-188` | §7#1 修复形态扩散：冷启动弱网 `refreshMe()` 失败 → `seedMerchantMeDisplayCache`（**去权缓存**，`permissions:[]`，主审读 `useMerchantMe.ts:29-56` 亲验）→ 判成无权限 ⇒ 「无结算权限」退回/「无柜机权限」切首页/「没有查看账单的权限」——网络错误被报成权限收回 | refresh 失败且 me 为空 ⇒ 显示网络错误+重试，不进无权限分支 |
| C1 | `consumer-mp/src/utils/consumer-api.ts:186-198/:234`；后端 `AuthInterceptor:41/:53`、`ApiMessages:15-16` | 申诉凭证上传的 401 自愈判据是 `e.message.includes('(401)')`，而后端 401 走 `ResponseStatusException → GlobalExceptionHandler → ApiResponse.error(401, 中文)` ⇒ 消息恒为「请先登录/登录已失效」**不含 `(401)`** ⇒ 静默刷新分支是死代码；只有非 JSON 响应（网关层）的「上传失败 (401)」兜底能命中。token 过期（>30min 会话常态）上传凭证必失败，最后 JSON 层刷新成功后**申诉可能无图提交**（凭证选填）。商户端同族（§7#2）已修成哨兵码+状态判，消费端未对齐 | 上传错误带结构化 status（复用 `createMpApiError`），按 `status===401` 判 |
| D1 | `UnpaidOrderService.java:452-463/:397-402` | 补扣链清券两分支只 `setCouponId(null)` 内存态，随后 `orderRepository.save`=updateById 跳 null ⇒ 库里 `coupon_id` 清不掉；`clearOrderCouponSelection/Columns` 调用方仅 `SettlementOrderFinalizeService:288` 与 `CouponService:615`（主审 grep 亲验），补扣链不在其中。触发面=支付分免密 PENDING 单（带券进 PENDING）或 MA-067 前存量 ⇒ PAID 单悬挂脏券列（巡检告警噪音；部分退 `CouponService:621-624` 会把无辜券 `discount_cents` 改写 0） | A/B 两分支 save 后补 `clearOrderCouponSelection(orderId, subtotal, now)` |

---

## 5. P3（17 条）

| # | 位置 | 一句话（均主审亲验） |
|---|---|---|
| A15 | `utils/csv.ts:34-41` + `SupplierReconciliationView.vue` | P2-6 修复引入：负数金额 `-500.00` 被 `/^[=+\-@\t\r]/` 前缀 `'` 变文本，Excel 不能直接求和；金额列可按 `/^-?\d+\.\d{2}$/` 豁免 |
| A16 | `ReplenishmentView.vue:1094-1105` | `loadFulfillment` 只 `begin()` 不捕获 seq、赋值不判 ⇒ 旧筛选覆盖；且裸 await 无 try ⇒ 失败走 A1 崩页（修 A1 时顺带收口） |
| A17（部分成立） | `WarehouseStocktakeDialogs.vue:86-92` | **成立部分**：`el-input-number` 无 `:precision="0"` ⇒ 2.5 可录入，Jackson `ACCEPT_FLOAT_AS_INT`（默认开）静默截 2、按截断值算差异。**证伪部分见 §7**：不会清掉商户端差异原因 |
| A18 | `LineRoutesView.vue:155-186`、`WarehouseMonthlyCloseTab.vue:68-75`、`BalanceRefundView.vue:289`/`InvoiceListView.vue:320` | ①同一弹窗表格「每件佣金(元)」显示元、表单「每件佣金(分)」输入分（违反表单按元约定）；②月结兜底 remainderQty 缺失按 0（后端恒传，不触发）；③关键词提示条在输入即出现且「本页命中」取的是上次取数过滤结果（filterByKeyword 在取数处应用，:59），清空按钮后提示消失但列表仍是上次过滤结果 |
| B10 | `order-detail.vue:8/:255-259` | 缺 orderId 的 error-state 未传 `:show-retry="false"` ⇒ 「缺少订单号」仍给必然失败的重试按钮（announcements/device-detail 已修成缺参不给重试，本页漏） |
| B11 | `merchant-api.ts:351-358` | `uploadMerchantAuthedFile` 重试分支无内层 catch ⇒ 刷新成功但重试仍 401（如账号被禁）漏出 `UPLOAD_AUTH_EXPIRED` 哨兵码；同文件 `uploadJiangyiCapturePhoto:1226-1231` 已对齐处理 |
| B12 | `settlements.vue:310/:379` vs `orders.vue:298` | 「近7天」实为 8 个自然日（`now-7*86400000` 到今天、后端含当天）；订单页 `dateStr(6)` 是 7 天 ⇒ 两页口径差一天 |
| B13 | `MerchantDevicePortalService.java:441-442`、`OpsDeviceAdminService.java:676-677`（**行号订正**） | 「补货中」= LIMIT 500（createdAt 升序）**之后**内存 `filter(isRestockingNow)` ⇒ 未签到的陈旧 IN_PROGRESS 累积 >500 条时挤掉真实任务（提交说明自认「永不收口」）；修法=`check_in_at IS NOT NULL` 下推 SQL |
| B14 | `purchase.vue:427-456` + `merchant-api.ts:1103-1108` | 采购申请提交无 requestNo 幂等键 ⇒ 超时重试可生成两张 CREATED 采购单（收货人工逐单，风险有界）；照提现模式补键 |
| C3 | `recharge.ts:341-343/:61-75/:82-123` | 支付宝充值「收银台未打开」（非法 action/非 H5 throw）路径 `savePendingRechargeOrder` 已先行 ⇒ pending 残留（P1-7 只堵了微信分支）。生产 mp 不可达（按钮 #ifdef H5/入口在 devTools 区），dev 构建/H5 可达 |
| C5 | `marketing-ad-banner.vue:59-65` | deviceId 变化仅 `if (!slides.value.length)` 才重拉 ⇒ Tab 态拉到的 ALL 投放内容在扫码后不刷新，SPECIFIC 定向永不出现；同文件 `device-ad-banner.vue:214-220` 是无条件 `void load()`（对照亲验） |
| C6 | `recharge.ts:223-226`、`index.vue:725`、`announcement-read.ts:24-33` | 三个只增不清键：`recharge_resume_soft_<orderId>`（全仓无 remove）、`promoPopupSeen:<activityId>`（活动结束不清）、`announcement_read_map`（无界 Map）；`evidenceLocalCache` 已有 `BoundedFifoCache(50)` 先例可抄 |
| C7 | `scripts/check-alipay-shell-gate.mjs:57` | `:56` 扩展名过滤已排除 README.md ⇒ `:57` README 豁免行永不可达（死代码，误导后来者） |
| D3 | `SessionService.java:456`、`OpsExceptionService.java:513-514` | `setFailReason(null)`+transition→save ⇒ updateById 跳 null，旧失败原因永久残留（重试/人工结案后的 COMPLETED 会话 DTO 仍带旧文案）；9d0f31b7 同族范式漏抄两处 |
| D5 | `DeviceSkuLotMapper.java:35-42/:68-76` | 批 3b 货道退场残留：`findByDeviceIdAndSkuIdAndSlotIdOrderByExpiryDateAsc`/`findByDeviceIdAndSkuIdAndBatchNoAndSlotId` 全仓（含 test）零调用（主审 grep 亲验）；注释「补货入库按货道精确匹配」与新声明矛盾 |
| D6 | `common-core UpsertMerchantRequest.java:20-24`；V321 列 `legal_person VARCHAR(64)`/`business_license_url VARCHAR(255)` | MA-165 两字段无 `@Size` ⇒ 超长直接 DB 500 而非 400 |
| D7 | `SessionService.java:118-127` | 幂等回放路径绕过 `assertSessionCreateAllowed/assertOpenDoorAllowed`（限流只在创建分支）；持有效 token 可对活跃会话键无限刷回放（每次 1-2 次点查，无资金动作） |

---

## 6. 系统性缺口（比单条缺陷更值得处理）

1. **错误边界越权（A1）是本轮 admin 一半 P1 的公共放大器**。修 A1 一处，A2–A5/A16 的「崩页」后果全部降级为普通错误提示；再逐处补 try/confirm/权限门。同类机制未在其他端发现（双 mp 无全局错误边界替换）。
2. **「上轮修复引入回归」3 条**：B3（§7#1 顺手补的共用 loadSeq）、B9（§7#1 的 catch→去权缓存→报权限形态在 settlements/devices/bills 复制）、A15（P2-6 修复的公式注入前缀误伤负数）。修复合入前的「同文件其余写操作/同族页面」扫描是当前流程的稳定盲区——本轮 A3/A4（同文件漏网）、A5（同 composable 漏网）、B11（兄弟函数漏网）同属此类。**建议把「修复时必须同文件全扫一遍同族」写进 CODE_FIX_CHECKLIST。**
3. **`updateById 跳 null` 家族收尾**：资金域 M01 范式已覆盖 11 处，本轮两路共发现 **5 处漏网**（D1 补扣清券=资金面、D2 解绑商户=权限面、D3 failReason=展示面、E1 清主图=删文件面、E2 券定义清列=口径面）——漏的都在「新近长出来的写路径」上；门禁化判据已成熟（见 §9 第 5 条）。
4. **seq/竞态家族在 mp 端的覆盖率**：admin 端 useCrudTable 有统一 seq，本轮 mp 端新增 3 条同类（B3/B5/A14 及 B8 的月份错配变体）。mp 端没有统一的列表加载守卫抽象，值得抽一个 `useMpListSeq`。

---

## 7. 撤账与修正记录（主审亲验 vs 子代理报告）

| # | 子代理结论 | 主审复核 | 处置 |
|---|---|---|---|
| D4 | MA-166 调账审批多节点下响应/审计写 APPROVED 失真 | **不成立**：现码 `MerchantWithdrawService.java:341-346` 在 `completeApproved` 后**读实例真实状态**回填（`instanceStatus(...).orElse("")`），注释明记「MA-166/F1 修：③读实例真实状态（双签：本节点过→实例移交下一节点仍 PENDING）」；子代理引用的 `status="APPROVED"` 无条件赋值在当前 HEAD 不存在 | **撤账** |
| A17 | 盘点保存会清掉商户端录入的差异原因 | **证伪**：`matchesDirection(diffQty, null)` 返回 true（`StocktakeDiffReason:72-73`「未分类永远合法」）⇒ admin 不带 reason 保存不 400；且 MP 默认跳 null ⇒ `setDiffReason(null)` 落不了库，商户原因**保留** | 仅保留小数截断部分 |
| A3 | 「写按钮没有权限门」 | 行级「标记结清」**有** `perm: 'ops:device:edit'`（rowActions）；缺门的是「手动出账/保存配置」两个头部按钮 | 修正范围 |
| A4 | 「移除成员无权限」 | 「移除」行动作有 `perm: 'ops:replenishment:edit'`；缺门的是「新建线路」按钮与「保存成员」弹窗按钮 | 修正范围 |
| A5 | 调拨「发运/取消」无确认无处理 | 属实且**范围更大**：`receiveTransfer`（V314 收货入库）同样裸 await 无确认 | 扩大范围 |
| A8 | 「资产标签页没有失败态」 | `onMounted` catch 有全局「加载失败」toast（非完全无信号）；但 toast 不阻止保存，核心风险（清所属仓）不变 | 修正表述 |
| B13 | 引用 `MerchantDevicePortalService:484-486` | 实际调用点 `:441-442`（运营台 `:676-677` 同构）；结论不变 | 行号订正 |

---

## 8. 已核对、判定无问题（避免下轮重复审）

**admin-vue**：`useReplenishmentRoutePlanning.loadAssignees` 时序；finally+isCurrent 复位的 7 处 composable（LineManager/MerchantSplits scope/status/OperatorManage/FundBill ledger/Replenishment tab/DeviceDetail loadDetail）；`submitPayout`/FeeLedger 结清/DisputeList/AlertRule/useDeviceLifecycleActions/SkuReview 批量的错误处理；反馈与选品前后端权限码；30 个前端 `*:export` 码为按钮码非漂移；将邑 SKU 搜索接口契约；CSV 导出范围接线；定时器仅 ObservabilityView 一处且已清理；元→分 `*100` 共 9 处属已登记门禁缺口（§5.2 旧账）+会员域例外；深链 AnalyticsView/FinanceView/VisionMapping 均有 query 处理。

**merchant-mp**：单柜自主补货幂等（成功即清=有意）；提现组合键与额度口径（可提=余额−冻结−待结算）；金额格式化无散写；`?url=` 深链无回归；device-detail/announcements 缺参已关重试；订单列表 seq 完整；定价页单飞+blur 比对；补货履约五动作 submitting 闩；盘点 P1-2' 判空前移在位；e93bccc3 字典去重不影响商户端；56c2e329 判据同源。

**consumer-mp + 壳 + shared**：`use-session-poll` 调度壳时序；index.vue 生命周期清理与 showSeq；finishSession/closeDoorDemo/abort 各分支清键；`handleSessionOpenResult` 换键重开；充值 mock fail-closed 与轮询语义；按钮级充值幂等（未到收银台不扣款+清理入口，两害相权）；领券/兑换/补缴后端三层幂等+前端闩；退款/争议回放状态层兜住；`request.ts` 刷新单飞/重试上限/匿名 401 不杀会话；409 信封链路与「会话已结束」锚逐字对齐；`notify.ts`/`format.ts` 语义与无浮点误差；announcements 缺参分离在位；device-ad-banner 双钩子+去重；`use-network-status` 引用计数；qrcode/深链解析白名单同源；`dispute-evidence` 单飞+有界缓存；result 页竞态在 mp 权威口径下无窗口；orders.vue 双段守卫；storage take 式键全景（C6 三键除外）；alipay 壳 13 文件红线全过（微信字样/网络调用/凭证/app_id 单点）。

**后端资金域**：钱包三层幂等+`uk_wallet_ledger_ref` DB 兜底（V388 亲读）；提现 `claimForPayout` 条件 UPDATE+PAYING 重入拒+stale 转人工；充值回调锁+早退+金额校验；微信回调验签/nonce/mchid；退款回调缺 refund_id 拒绝（MA-088）；AU-137 切片续退权威跳过；MA-161 A2 键金额段与腿级查重；锁外置事务约定+REQUIRES_NEW 痕迹+self 代理注入；优惠四列写侧/巡检/重算/比例退四处一致且无第六处消费者；updateById 家族 M01 已覆盖 11 处（漏网=D1/D2/D3）；BigDecimal 无 double 构造+bps 钳位；ScheduledTaskRegistry 41 项与三焦点任务 tryBegin（settlement-bill/sms-probe/jiangyi-poll）+AU-173 两任务在位；MerchantWithdrawController 权限注解+service 二次校验；`/internal/v1/**` fail-closed 503+常量时间比较+CIDR；`setState` 旁路仅 4 处新建实体；revenue_split 全翻转过订单锁+ForUpdate+终态早退；V431–V453 迁移全读（无同号/热表索引/回填幂等/删表守卫/死键零活引用/V451 实测证据）；**120705b1 状态闸三问**：闸在锁外读路径无副作用、FAILED 放行=有意且已释放预授权、并发同 key 靠 DB UNIQUE fail-loud 不会双开门。

---

## 9. 建议修复顺序

1. **立刻**（崩页面）：A1（错误边界按来源分流）→ A2/A3/A4/A5（逐处补 try/confirm/权限门，A1 修后崩页风险已消，但业务提示仍缺）→ A7/A10（写错商户/费率两处）。
2. **数据面**：A6（盘点未保存过账）、A8（空表单清仓）、B1（回放状态闸：前端清键+后端 409 同构）、B4（盘点凭证复用）、B6（采集照片串号）、D1/D2/D3（三处显式 UPDATE 清列，D1 补钉测）。
3. **幂等契约**：B2（回放前置）、B14（采购键）。
4. **体验/口径批**：A9–A14、B3/B5/B8/B9、B12、C1（上传 401 状态化，对齐商户端哨兵码形态）、C3/C5/C6、E2/E3。
5. **同轮门禁候选**（铁律 75）：①A1 修法配套「事件处理禁裸 await」静态判；②「上轮修复引入回归」→ CODE_FIX_CHECKLIST 加「同文件同族全扫」硬条目；③updateById 清列家族门禁（两路共 5 处漏网后判据已成熟：`trimToNull/set(null)` 后直接 `save(` 且无 wrapper 的组合，D1–D3/E1/E2 五处全是同形态）；④C7 死行顺手删；⑤seed 门禁补「有 scheduled_task 种子行的 key 必须有 registry 注册项」规则（E3 方向，防再漏）。
6. **E1/E4 尽快**：E1 是「图片物理删除不可恢复」的破坏性动作（比悬空引用更重），且前端有现成「清除」入口；E4 是 22f9ff5a 已实证 P1 伤害形态（1018/664 误扣）的不变式回归。

---

## 10. 验证记录

- **主审亲验范围**：路 A–D 全部 44 条——每条至少读到所引代码本体；机制性断言额外验证了：Vue 3.5.40 错误传播链（A1）、Element Plus confirm reject 语义（A2）、MyBatis-Plus 默认 update 策略=NOT_NULL 且无全局覆盖配置（D1/D2/D3/A17）、`matchesDirection(null)=true` 源码（A17）、`MerchantScopeService.allowedDeviceIds` 消费面（D2）、`stripMerchantGrants` 去权链（B9）、`escapeCsvCell` 前缀正则（A15）、`check-alipay-shell-gate` 扫描顺序（C7）。
- **实跑基线**：`pnpm -s lint` = **0 error / 18 warning**（上轮修复批基线 19，持平略降，无新增 error）；本轮为只读审计，未改动任何源码；类型检查与单测未在审计轮内重跑（上轮 §11.4/§12.3 为当前最近基线）。
- **未验边界（诚实标注）**：B6 的「blur 触发状态查询有厂商侧写副作用」子声明待路 E 复核后端；B14 后端是否有兜底去重未验；A7/A13/A14 的竞态后果为静态推演（需弱网复现）；A8 需「detail 成功而 asset 接口失败」的局部故障注入。真机点击链（盘点弹窗、备货取消重提、弱网冷启动）未复现——与上轮 §9.4 同口径。
- **路 E（后端其余域）**：已返回并按同一纪律**逐条全验**（§11）。路 E 另独立复核了 D4 撤账（全部 5 个 `completeApproved` 业务调用方均有 isInstanceApproved/真实状态门，无漏网同族——与主审撤账结论互证）。

---

## 11. 路 E 审计结果（后端其余域 + device-service + jiangyi-gateway，4 条全部主审亲验）

### E1 [P1] SKU 编辑「清除主图」：附件行+MinIO 对象被物理删除，DB 却永久留旧 URL（updateById 跳 null + 先删后写）
- **位置**：`services/trade-service/.../OpsCatalogAdminService.java`（updateSku：`applySkuRequest` → `skuCatalogRepository.save` → release 分支）；`applySkuRequest` 内 `sku.setImageUrl(trimToNull(request.imageUrl()))`；`FileAttachmentService.releaseSkuImageIfUnused`（删附件行 + 无引用时 `minioVideoService.removeObject` 删对象）。
- **证据**（主审亲验）：
  ```java
  sku.setImageUrl(trimToNull(request.imageUrl()));   // 前端「清除」发 '' ⇒ null
  skuCatalogRepository.save(sku);                     // updateById 跳 null ⇒ DB 留旧 URL
  String newImageUrl = trimToNull(request.imageUrl());
  if (oldImageUrl != null && !oldImageUrl.equals(newImageUrl)) {
      fileAttachmentService.releaseSkuImageIfUnused(oldImageUrl);   // 判定「已移除」⇒ 删行删文件
  }
  ```
  前端入口：`clients/admin-vue/src/views/skus/SkuListView.vue:537-538`——`<el-button v-if="form.imageUrl" link type="danger" @click="form.imageUrl = ''">清除</el-button>`（主审源码实证）。
- **触发与影响**：编辑商品点「清除」保存 ⇒ ①DB `image_url` 保留旧值（跳 null）；②release 判「图片已移除」删除附件行、并在无同 storagePath 引用时**物理删除 MinIO/本地对象** ⇒ 商品图 404 且不可恢复（文件已删）。同函数 `category/category_id/barcode/brand/description` 清空同样不落库（陈旧值残留，P2 面）。
- **刻意设计排查**：release 分支显式处理 `newImageUrl==null` 证明「清空」是预期语义；同类位点（OpsDeviceAdminService imei/homeWarehouseId 等）均有 M01 wrapper，唯 updateSku 整段漏抄。与 11-09 infra 的「全桶 ILM 删图悬空引用」同一伤害形态，但这是应用层自造。
- **修法**：可清空字段 trimToNull 后为 null 且旧值非空 ⇒ 走 `LambdaUpdateWrapper.set(col, null)` 显式清列；release 移到 DB 清列成功之后。
- **置信度**：高。

### E2 [P2] 券定义编辑：券型切换/活动解绑的 null 清列不落库——预算记账与审计口径失真
- **位置**：`services/trade-service/.../CouponService.java`（updateDefinition → `applyDefinitionAmounts`/`applyActivityId` → `definitionRepository.save`）。
- **证据**（主审亲验）：`PERCENT_OFF` 之外的券型 `setDiscountPercent(null)`；`applyActivityId(def, null)` 解绑活动 `setActivityId(null)`；save=updateById 跳 null ⇒ 两列残留旧值。
- **影响**：①残留 `discount_percent` 继续出现在定义 DTO（结算 `resolveDiscount` 按券型 switch，不参与金额计算——**非资金损失**）；②残留 `activity_id` ⇒ 领取预算预留/释放仍挂在旧活动上记账，而审计行写的是解绑后的 null——账实两套口径。
- **修法**：两字段 M01 wrapper 显式清列。
- **置信度**：高。

### E3 [P2] `session-failed-hold-clear` 未登记 ScheduledTaskRegistry——手动执行 404、超期看护盲区（R19-A1 同族新条目）
- **位置**：`SessionExpireService.java:139-152`（`@Scheduled` + `tryBegin(SESSION_FAILED_HOLDCLEAR, 600)`）；`ScheduledTaskRegistry` 全文 grep 无该 key（主审亲验）；同文件 `:137`（refund-retry）、`:154-157`（settlement-bill，注释明记 R19 方案 A1 与 404 后果）为先例。
- **影响**：①运营台 `POST /scheduled-tasks/session-failed-hold-clear/run` 404；②不进 `ScheduledTaskStaleMonitor` 扫描 ⇒ 该任务（FAILED 会话预授权悬挂清扫，V306）停跑无人告警，用户冻结资金悬挂加长。DB 有种子行 ⇒ 启停开关/执行记录仍可见，现有 seed 门禁因「有种子行」放行——恰好漏掉「有种子行但注册表缺项」方向。
- **修法**：照 refund-retry 先例补 `register(...)`；或 seed 门禁补双向规则。
- **置信度**：高。

### E4 [P1] 将邑挂接重绑 RETIRED 行跳过同条码兄弟退役——22f9ff5a 已修不变式经「解绑→重绑」回归
- **位置**：`services/trade-service/.../SkuJiangyiLinkService.java`（bind 方法：冲突守卫放行 RETIRED；`link.getId()==null` 的 **insert 分支**才有同条码兄弟退役循环，else 分支 `updateById` 复活旧行时跳过）；`JiangyiSkuJiangyiLinkMapper.byProduct`（无 status 过滤，RETIRED 行会被查到走 else）。
- **证据**（主审亲验）：unbind（`:478`）`setSyncStatus("RETIRED")` 留痕；bind 守卫 `if (!"RETIRED".equals(existing.getSyncStatus())) throw 409`；兄弟退役代码与注释（「换产品时把同条码旧行置 RETIRED…多条 BOUND 并存会让其他商品按本条映射结算」，22f9ff5a 真机实证 1018/664 误扣）**只在 insert 分支**。
- **触发**：SKU X 挂 P1（BOUND）→ 解挂 P1（RETIRED 留痕）→ 挂同条码 P2（BOUND；insert 分支的 listBySku 滤 RETIRED，看不到 P1）→ 重绑 P1（else 分支，无退役）⇒ 同 SKU 同条码两条 BOUND 并存——正是 22f9ff5a 修掉的状态。P2 若挂在不同 SKU 上，则形成跨 SKU 双 BOUND 的结算歧义（伤害量级与 22f9ff5a 同族）。
- **刻意设计排查**：else 分支无排除理由，属 insert/else 分支不对称的漏抄。
- **修法**：兄弟退役循环提到 `link.getId()==null` 判断之前，对两分支统一生效。
- **置信度**：高。

### 路 E 附带结论（主审复核认可，并入总账）

- **D4 审批家族终表**：`completeApproved/completeRejected` 全部 5 个业务调用方（withdraw 调账/withdraw 审核/line withdraw/余额退款/入驻/采购单 6 处面）逐一核过——除 reviewAdjustment（已修形态）外，其余全部有 `isInstanceApproved`/真实状态门（中间态「初审通过」留 PENDING_REVIEW/SUBMITTED 可重入），**无漏网同族**。撤账 D4 双重确认。
- **updateById 跳 null 家族非资金域全扫**：`SkuCatalog`（E1）、`CouponDefinition`（E2）为漏网；`JiangyiClassMapping.writePregenerated` 的 null 不进 SET 是注释明言的刻意设计；DeviceInfo/WarehouseService/ApprovalInstance/OpsDeviceAdmin 等其余位点 M01 wrapper 全在位；脚本化扫描（set-null 距 save ±6 行无 M01 标记）全 trade-service 仅另命中一处 insert 语义（非清列）。
- **定时任务全量**：registry 43 项 / XXL KEYS 38 = handler 38 = seed 38（四方一致）；44 个注册 action 逐一核过 tryBegin（44/44，0 缺失）；`@Scheduled` 44 个 = 43 注册 + cache-purge（刻意排除有明文）+ **session-failed-hold-clear（E3 漏网）**。
- **已核对无问题**（路 E）：审批流本体（passRule 会签/非当前处理人 403/startManual fail-loud）；11-02 以来新增 23 端点权限注解全覆盖、商户 token 打 ops 端点被权限码拒；jiangyi admin 面 28 写端点审计留痕抽查全中；captcha 单次有效+LoginThrottle+2FA M01 清列；device-service（手动 ACK/door 去重/三处 deviceId 伪造拦截/QoS1+expireAt/fleet key 后白名单）；jiangyi-gateway（token fail-closed/CommandTracker owner 校验/识别上报四分支留档/视频 uk 幂等/STS 目录级最小权限）；infra 自 11-02（MinIO ILM 幂等替换拍板落地、dispute-auto-waive 三层一致关、无端口/密钥漂移）；将邑补货链（confirmDelist 红线/requestId 内容哈希幂等/syncAfterRestock 仅一处调用——结论归 B7）。

---

*本报告为静态源码审计 + 主审逐条亲验口径（路 A–E 共 48 条全部亲验，撤账/修正见 §7）；未做真机运行时探测与渗透。上轮报告 `AUDIT_FOUR_END_2026-11-09.md` 的全部修复经 §2 回归抽查确认在位。本报告未提交 git——是否入库由用户决定（工作树另有 `scripts/probe-zhima-payafteruse.py` 在制改动，提交时须按铁律 61 显式路径分开）。*

---

## 12. 修复落地记录（2026-11-10，按 §9 顺序第一批）

> 判据：只改本条命中的写路径，改完跑 `pnpm -s lint`（0 error / 18 warning，基线持平）、`clients/admin-vue` `type-check`（clean）、`mvn -o -pl services/trade-service -am -DskipTests compile`（clean）、受影响单测 `-Dtest=DeviceAssetLifecycleTest,CouponServiceTest,MerchantSelfReplenishTest` = **Tests run: 49, Failures: 0, Errors: 0**。

### 已修（32 条）

| 条 | 改动落点 | 做法 |
|---|---|---|
| A1 | `PageErrorBoundary.vue` | `onErrorCaptured` 按 `info` 分流：仅 setup/渲染期整页替换；事件/watcher/生命周期改 `ElMessage.error` |
| A2 | `ExternalChannelImportTab.vue` | 新增 `confirmOrSkip()`（confirm 包 try + `isUserDismiss` 静默），提交/放弃/解绑三处改用它 |
| A3 | `MachinePayeeBillsView.vue` | 结清加二次确认+try；出账/保存配置补 try；两个头部按钮加 `v-hasPermi` |
| A4 | `LineRoutesView.vue` | 建改线路/归档/保存成员/移除成员补 try；归档与移除加确认；「新建线路」「保存成员」加 `v-hasPermi` |
| A5 | `useWarehouseTransfers.ts` | 发运/收货/取消加确认+try+`saving` 锁（带明细的收货表单提交豁免确认） |
| A6 | `useWarehouseStocktakes.ts` | 完成/完成并过账前先 `persistStocktakeLines()`（弹窗内未保存实盘先落库）+ `assertAllLinesCounted()` 中文前置 |
| A7 | `MerchantSplitsView.vue` | `begin()` 移到空值判断后；成功赋值处补 `isCurrent`+商户比对；记录 `opsConfigLoadedMerchantId` 并在保存前校验 |
| A8 | `useDeviceAsset.ts` / `DeviceDetailView.vue` / `DeviceAssetDeploymentCard.vue` | 新增 `assetLoaded/assetLoadFailed`；失败禁保存；`Promise.all`→`allSettled`；`loadPolicy` 独立加载 |
| A9 | `useDeviceRemoteOps.ts` | `policySaving` 在途锁；保存失败 `reloadPolicy()` 回滚基线 |
| A10 | `MerchantWithdrawView.vue` | 引入 `feeLoadedMerchantId`+watch（改商户号即收起面板）；保存/清除覆盖前比对，不一致即拒 |
| A11 | `DashboardView.vue` | `statsFailed`/`exceptionCountFailed` 分项标记，KPI 失败显「—」+原因文案 |
| A12 | `useWarehouseStocktakes.ts` | 4 个写动作的 `reloadStocktakeDetail` 移出写 try，改 `reloadStocktakeDetailSafely()` |
| A14 | `JiangyiProductsView.vue` | `stateSeq` 守卫；清空关键词复位 `skuSearching`；watch 条码改动清 `pickedSkuId` |
| A15 | `utils/csv.ts` | 纯十进制（含负数金额）豁免公式前缀；新增用例钉住 |
| A16 | `ReplenishmentView.vue` | `loadFulfillment` 取 seq 并判新旧 + try/catch |
| A17（部分） | `WarehouseStocktakeDialogs.vue` | `el-input-number` 加 `:precision="0" :step="1"` |
| B1 | `MerchantReplenishmentService.java` | 回放命中 `CANCELLED` ⇒ 409 指引重提（**COMPLETED 仍回放**，保持 V401 幂等契约）；新增 `selfInitiate_rejectsReplayOfCancelledTask` |
| B3 | `purchase.vue` | 申请/单据拆成 `createSeq` / `listSeq`，`ensurePurchaseAccess()` 不再带 seq |
| B10 | `order-detail.vue` | 缺 `orderId` 时 `:show-retry="false"` |
| B11 | `merchant-api.ts` | `uploadMerchantAuthedFile` 重试分支补内层 catch，哨兵码不漏出（对齐将邑上传） |
| B12 | `settlements.vue` | 「近 7 天」改 `now-6d`（原为 8 个自然日，与订单页差一天） |
| C1 | `consumer-api.ts` | 上传 401 自愈判据改「结构化 `status===401` 或旧的 `(401)` 串」 |
| C7 | `check-alipay-shell-gate.mjs` | 删除不可达的 README 豁免行 |
| D1 | `UnpaidOrderService.java` | 补扣链两分支 save 后补 `clearOrderCouponSelection`（仅 `couponId==null` 时） |
| D2 | `DeviceAssetService.java` / `DeviceInfoMapper.java` | 新增 `clearMerchantId`，UNBIND 且 `merchantId==null` 时显式清列 |
| D3 | `SessionService.java` / `ShoppingSessionMapper.java` / `OpsExceptionService.java` | 新增 `clearFailReason`，重试复位与人工结案两处调用 |
| D5 | `DeviceSkuLotMapper.java` | 删除两个零调用方法（含误导注释） |
| D6 | `UpsertMerchantRequest.java` | `legalPerson @Size(64)` / `businessLicenseUrl @Size(255)` |
| E1 | `OpsCatalogAdminService.java` | 新增 `clearExplicitlyBlankColumns()`：**只对请求里出现过的字段**走 `LambdaUpdateWrapper.set(col,null)`，且在 `releaseSkuImageIfUnused` **之前** |
| E2 | `CouponService.java` / `CouponDefinitionMapper.java` | `writeNullableColumns` 显式写 `discount_percent`/`activity_id` |
| E3 | `ScheduledTaskRegistry.java` | 补 `register("session-failed-hold-clear", …)` |
| E4 | `SkuJiangyiLinkService.java` | 同条码兄弟退役循环提到 `link.getId()==null` 判断之前，insert/update 两分支统一生效 |

### 与报告修法的有意偏离（1 处）

- **E1**：报告写「trimToNull 后为 null 且旧值非空 ⇒ 直接清列」。实际代码里 `updateSku` 的注释明记「CSV 导入等局部更新」会传 `sceneLimits=null`，说明该入口存在**局部更新**语义；若无条件清 `image_url/description/category/barcode/brand`，会把「字段缺省」误当「用户清空」⇒ 局部更新会删图。故收窄为「请求里该字段非 null（含空串）才写」，前端「清除」发 `''` 仍能清空。

### 未修（14 条 → 已由 §12.5 第二批清空）

`A13`（算力映射页 seq）、`A18`（元/分展示、月结兜底、关键词提示时机）、`B2`（`validateAmount` 与回放顺序）、`B4`（盘点凭证 `evidenceUrl` 复用）、`B5`（仓储盘点列表 `listSeq`）、`B6`（将邑采集 `photos` 串号 + `@blur` 自动查）、`B7`（补货完成事务内同步 HTTP）、`B8`（账单切月残留旧值）、`B9`（弱网经去权缓存误报无权限）、`B14`（采购申请幂等键）、`C3`（支付宝充值 pending 残留）、`C5`（广告位 deviceId 变化不重拉）、`C6`（三个只增不清的键）、`D7`（幂等回放绕过限流）。

> **2026-11-10 第二批已全部落地，见 §12.5。**

> ~~B1 的残余已知：前端 `prepSuccessFingerprints` 在「取消后改量重提」时仍会拦下（提示去补货任务页调量，而任务已取消）。彻底收口需要 `MerchantPrepGroupDto` 暴露 `lastTaskStatus`（改 DTO+`gen:api-types`），两批都没动这条 API。~~
> **已收口，见 §12.6**（实际要暴露字段的是预览 DTO `MerchantPrepPreviewGroupDto`，不是首页那支 `MerchantPrepGroupDto`）。

## 12.5 第二批落地记录（2026-11-10，14 条）

> 判据同上：只改命中路径；`pnpm -s lint` 0 error / 18 warning（持平）、三端 `type-check` clean、`mvn compile` clean、
> `-Dtest=ProcurementServiceTest,MerchantWithdrawChannelLimitTest,LineWithdrawPayeeTest,MerchantSelfReplenishTest,MerchantReplenishmentCompleteGatesTest,ProcurementReceiveWarehouseTest` = **67 tests 全绿**、
> consumer-mp vitest **175 全绿** / merchant-mp vitest **250 全绿** / admin vitest **133 全绿**。

| 条 | 改动落点 | 做法 |
|---|---|---|
| A13 | `VisionMappingView.vue` | `fetchPage` 捕获请求时 `deviceId`，await 后比对才写 `binding/allMappings`（这两处赋值发生在 useCrudTable 丢过期页之前） |
| A18① | `LineRoutesView.vue` | 表单「每件佣金」改**按元**录入（`:precision="2"`），提交 `yuanToCents()` 换算；非按件基数时传 0 |
| A18② | `WarehouseMonthlyCloseTab.vue` | `remainderQty` 缺失不再按 0 补（那等于又回到「领货量=上柜量」），显「—」 |
| A18③ | `BalanceRefundView.vue` / `InvoiceListView.vue` | 引入 `appliedKeyword`（取数瞬间的口径）：提示条只按已生效关键词显示；只打字未查询时改提示「尚未生效」 |
| B2 | `MerchantWithdrawService.java` / `LineWithdrawService.java` | `findByRequestNo` 回放提到 `validateAmount` **之前**（归属+金额校验保留），并让回放不再依赖收款账户解析 |
| B4 | `device-stocktake.vue` | `loadDevice()` 开头清 `evidenceUrl`（换柜/重盘不再复用上一张照片；提交成功后走的也是 loadDevice） |
| B5 | `warehouse-stocktake.vue` | `loadList` 捕获 `const seq=++listSeq`，赋值/错误/finally 三处判新旧 |
| B6 | `jiangyi-capture.vue` | 新增 `photosForBarCode` 标记：条码变即 `resetPhotos()`；提交成功也重置。**删掉 `@blur="loadState"`** — 该接口注释自证「首次查询会建采集任务，只限显式点击」 |
| B7 | `ReplenishmentService.java` | `syncAfterRestock` 改「`afterCommit` + 单线程 daemon 执行器」（对齐 `DeviceCommandTracker` 的线程范式）；既出事务也出请求线程，线程池 `@PreDestroy` 关闭 |
| B8 | `bills.vue` | 切月/切柜即 `bill=null`（标题取 month、内容取 bill，不清就在新月份标题下显示上月数据） |
| B9 | `settlements.vue` / `devices.vue` / `bills.vue` | `refreshMe` 失败时若**没有内存态** ⇒ 记 `permUnknown`，报「网络异常，暂时无法确认权限，请重试」，不再走「无权限」分支；已有内存态时权限判据仍可信 |
| B14 | `ProcurementService.java` / `PurchaseOrderMapper.java` / `merchant-api.ts` / `purchase.vue` | 复用 DTO 已有的 `refNo` 作客户端幂等键（`MP-<ts>-<rand>`）：后端按「仓库+refNo」回放已有单；前端**成功后**才换新键。新增 `createSatellitePurchaseOrder_replaysSameClientRequestNo` 并做负向验证（关掉回放⇒用例报错） |
| C3 | `recharge.ts` | 支付宝 `openAlipayPrepay` 包 try：收银台没打开就清 pending 再抛（对微信分支 P1-7 同构） |
| C5 | `marketing-ad-banner.vue` | `deviceId` 变化无条件 `load()`（原 `if (!slides.length)` 让 Tab 态拉到的 ALL 投放挡住 SPECIFIC 定向） |
| C6 | `recharge.ts` / `index.vue` / `result.vue` / `utils/popup-seen.ts` / `shared-uni/announcement-read.ts` | ①`forgetPendingRecharge()` 连 `recharge_resume_soft_*` 一起清；②弹窗已见改单个有界 map（上限 50）+ 旧前缀键迁移后删除；③公告已读表上限 200，按最久未读淘汰 |
| D7 | `SessionService.java` / `ApiRateLimitService.java` | 新增 `ACTION_SESSION_REPLAY` 独立计数器（不占 `session_create` 配额），回放命中即过闸 |

### 第二批的两处「按实际代码修正报告」

1. **B14 不新增字段**：报告建议「照提现模式补 requestNo」，但 `CreatePurchaseOrderRequest` 已有 `refNo` 且落库 ⇒ 用它当客户端幂等键即可，**不必改 DTO、不必 `gen:api-types`**（铁律 52 的重建容器代价）。回放只在商户自提路径生效——运营端「新建采购单」的 refNo 是人工参考号，语义不同不能一起吃去重。
2. **B7 只 afterCommit 不够**：报告给了「afterCommit 或事件异步化」二选一。只 afterCommit 时 HTTP 仍在请求线程上，20s 超时症状不减；故 afterCommit **再投递到专用单线程**（串行避免多柜同步互相覆盖）。代价：进程退出瞬间的同步可能丢失——库存真值在库里，下次补货会重新同步，可接受。

### 第二批的第三处：报告漏了一个同族（C6）

C6 只列了开门前弹窗的 `promoPopupSeen:<activityId>`（`index.vue`），但 `result.vue` 的**关门后**弹窗是同一套写法（`promoPopupAfterCloseSeen:<id>`，同样只写不删）。已一并收口：
抽出 `utils/popup-seen.ts`（单个有界 map，上限 50，按日期淘汰最旧；**首次读即迁移**旧前缀键并回写），两页共用；新增 `popup-seen.test.ts` 3 例并做负向验证（放大上限 ⇒ 红）。

## 12.6 第三批落地记录（2026-11-10，B1 前端残留 1 条）

> 判据：`mvn -Dtest=MerchantPrepPreviewTest,MerchantSelfReplenishTest` = **25 tests 全绿**（新增 1）、
> merchant-mp vitest **251 全绿**（新增 1）、`vue-tsc --noEmit` clean、改动文件 eslint 0 error、
> `check:openapi-regen-required` / `check:openapi-types` / `check:replenishment-checkin-contract` 绿。

| 条 | 改动落点 | 做法 |
|---|---|---|
| B1（前端半） | `MerchantPrepPreviewGroupDto.java` / `ReplenishmentTaskMapper.java` / `MerchantReplenishmentService.java` / `self-replenish-submit.ts` / `prep-generate.vue` | 预览带上「本柜最近一张任务状态」（`lastTaskStatus`，设备范围 + 每柜最新都下推 SQL、一次查询、不设上限）；前端判据从「指纹变了就拦」改成「指纹变了**且任务还活着**才拦」，终态时作废旧键按新意图重提 |

### 为什么不能只靠用例（门禁的必要性）

纯函数用例只钉 `shouldBlockPrepResubmit` 本身。**删掉 DTO 字段、或服务不查、或页面不传参**，
纯函数用例照样全绿，而线上会退化成「永远拿到 null ⇒ 永远拦」。故在
`check:replenishment-checkin-contract` 加**规则 9**（五条判据，见脚本注释）。

### 两个当场踩到的坑

1. **`lastTaskStatus` 为 null 必须按「还在进行中」拦，不能放行**。常见路径是「提交成功 → 部分柜失败 →
   留在本页改量重提」，而预览是**提交前**加载的，那时本柜还没有任务 ⇒ `lastTaskStatus` 天然是 null。
   按 null 放行等于把 P0-3 的修复（防重复建任务 + 重复扣分仓库存）整个废掉。只有预览里**明确读到终态**才放行。
2. **终态放行时必须同时作废旧键**。COMPLETED 会被后端回放成「已生成」旧任务（假成功）、
   CANCELLED 直接 409（假失败）。两个都是假结果，故 `renew_after_ended_task` 分支清
   `prepRequestNos` + 指纹，让 `takePrepRequestNo` 生成新键。

### 残余时序窗口（诚实标注）

`lastTaskStatus` 是**取预览那一刻**的快照。若用户在「预览加载后、点提交前」才把任务取消，
本页仍会按旧快照拦一次。出路已写进拦截文案（「若该任务已被取消，请下拉刷新本页后重试」），
刷新后快照更新为 CANCELLED 即走重提分支。未做「提交前实时复查单柜状态」——那需要新增单柜
状态接口，收益不抵成本。
