# 四端代码审核报告（2026-11-09）

> **基线**：`dev` @ `f34de6b0`（「chore(admin): 重烤 static/admin 配对 theme.css 深底令牌」）+ 工作区未提交改动（8 个已跟踪文件、4 项未跟踪，含新增 `clients/alipay-mp-shell/`）。
> ⚠️ 审核期间另一会话连续推进了 3 个提交（`44d21c71` → `f34de6b0`），`consumer-mp/index.vue`、`HomeLanding.vue` 的 M 状态在此期间消失。凡引用行号均以本基线为准，若并发会话继续改动需重新核对。
>
> **范围**：`clients/admin-vue`（238 文件 / 4.7 万行）、`clients/merchant-mp`（145 / 2.3 万行）、`clients/consumer-mp`（97 / 2.5 万行）、`clients/alipay-mp-shell`（8 / 243 行）。
>
> **方法**：仓库门禁与类型检查实跑 + 4 路并行只读子代理（admin 资金库存 / admin 业务页 / merchant / consumer）+ 主审对全部 P0 与关键 P1 逐行亲验。alipay shell 由主审全文通读。
>
> **复核标记**：✅ = 主审读源码（必要时对照后端）确认；⚠️ = 子代理报告，主审未复核，需修复前再验。
>
> **纪律**：已知有意设计（无称重、商户端仅密码登录、补货自提自送无在途、开门柜库存真值在 `device_sku_inventory`、分仓负责人多值）已排除，未计入缺陷。

---

## 1. 总评

| 端 | P0 | P1 | P2 | 结论 |
|---|---|---|---|---|
| admin-vue | 0 | 7 | 4 | 资金写路径有静默失败面；权限码两处回归；composable 有死锁级逻辑错误 |
| merchant-mp | 1 | 3 | 3 | **备货重试会重复建任务**；盘点空值经验证降为 P1（有二次确认兜底） |
| consumer-mp | 1 | 3 | 3 | 支付链路末端表述错误、退款态不回读、开门幂等键半成品 |
| alipay-mp-shell | 1 | 1 | 2 | 未过格式门禁（卡 CI）；且完全在所有前端门禁之外 |

> **2026-11-10 00:40 复核修正**：原 P0-2（盘点空输入当 0）经验证降为 **P1-2'**——前端 `:218` 有 `uni.showModal` 二次确认，且弹窗显式列出每行差值（清空台账 20 的商品会显示「-20」），用户有可见信号；缺陷性质从"静默清零"变为"空值与 0 语义混淆 + 依赖用户从差值串里发现异常"。详见 §9 验证记录。

**门禁基线（实跑）**

| 检查 | 结果 |
|---|---|
| `pre-push-ci-preflight`（默认档） | ✗ **FAIL @ format:check** —— `clients/alipay-mp-shell/mini.project.json`、`pages/orders/orders.js` 未格式化。preflight 是 fail-fast，后续步骤未执行 |
| `pnpm lint` | ✓ 0 error / 30 warning（warning 集中在 merchant-mp 未使用的 `MerchantMe` 导入等重构残留） |
| admin `build:admin:typecheck` | ✓ exit 0 |
| merchant-mp `type-check` | ✓ exit 0 |
| consumer-mp `type-check` | ✓ exit 0 |
| `pnpm test:mp` | exit 2 —— consumer `test:uat` 需要本地 `127.0.0.1:3002`（dev:h5 + 网关），未起则红；且该脚本名含 "ci" 但 CI 并不调用（CI 走 `pnpm --filter … test` = vitest），见 §5.3 |

**一句话**：商户端的备货提交把"重试"当"新建"（幂等键建在函数局部、返回即销毁），会重复建补货任务；消费端开门幂等键带了 `createdAt` 却从不校验，配合后端只校验 user/device 的回放逻辑，会让用户重复扫同一台柜时不开门、反而接管上一次的终态会话。盘点的"空输入当 0"经实测有二次确认弹窗兜底（显示差值 -N），降为 P1。admin 侧没有资损级 P0，但资金操作 `catch {}` 且无全局提示 = 静默失败面较大。

---

## 2. P0（3 条，均 ✅ 亲验 + 实跑复现）

> 原 P0-2 已降级并入 §3（P1-2'）。**编号保持原样不重排**，避免已发出的结论对不上号。

### P0-1 新增的支付宝壳未过 prettier，直接卡死推送预检与 CI
- **位置**：`clients/alipay-mp-shell/mini.project.json`、`clients/alipay-mp-shell/pages/orders/orders.js`
- **证据**：实跑 `node scripts/pre-push-ci-preflight.mjs` →
  ```
  [warn] clients/alipay-mp-shell/mini.project.json
  [warn] clients/alipay-mp-shell/pages/orders/orders.js
  ✗ FAIL: format:check (exit 1)
  ```
- **影响**：preflight 第一关即退出，lint / mp type-check / audit-gates / migration 门禁全部未执行——本地"预检通过"的判断不可得；推上去 CI 同一条 `format:check` 也会红。
- **修法**：`pnpm exec prettier --write clients/alipay-mp-shell` 后重跑 preflight。
- **附带**：该目录 8 个文件里 `.axml/.acss` 不在 prettier glob（`clients/**/*.{ts,vue,js,mjs,css,html,json}`）内，格式一致性只能靠人工。

### P0-2（已降级 → §3 P1-2'）

原「盘点空输入当 0」经 2026-11-10 复核实测：前端存在 `uni.showModal` 二次确认且显式展示每行差值，用户有可见信号，不构成 P0。完整结论见 §3 **P1-2'**。

### P0-3 备货单：逐柜幂等键建在函数局部 Map 里，部分失败重提会重复建任务
- **位置**：`clients/merchant-mp/src/pages/prep-generate/prep-generate.vue:269`、`clients/merchant-mp/src/pages/prep-list/prep-list.vue:423`（两处同款）
- **证据**：
  ```js
  // 注释：幂等：每柜一次一键（V401 契约）——传输类错误保留该柜键，重试复用同键幂等返回原任务
  const requestNos = new Map<string, string>();   // ← submit() 的局部变量
  ...
  const task = await merchantApi.selfInitiateReplenishment(body);
  requestNos.delete(group.deviceId);              // ← 成功后删，函数返回 Map 也没了
  ```
- **影响**：注释承诺的"保留键供重试"不成立——`requestNos` 随 `submit()` 返回即销毁。部分成功（第 1 柜成功、第 2 柜超时）后页面停留在勾选态，用户再点提交：**已成功的柜被分配新的 `clientRequestNo`**。后端 `MerchantReplenishmentService:421-430`（回放 `return` 在 `:429`）以 `clientRequestNo` 查重、命中才回放，新键必然新建任务 ⇒ 重复备货任务、重复占用/扣减分仓库存。
- **prep-list 比 prep-generate 更隐蔽（2026-11-10 复核补充）**：部分失败分支会 `await loadPreview()`，而 `toView` 里 `checked: items.length > 0`（`prep-list.vue:356`）会把已成功的柜**自动重新勾选**；又因在途只在发运时记账（`WarehouseService:992`）、建任务不减少建议量，用户什么都不改直接再点一次提交，已成功的柜必然被重复提交——**不需要手动重勾**。
- **修法**：幂等键提升为页面级 `Map`（或 `reactive`/模块级），成功后**不删**；仅当"服务端明确返回业务失败（非传输错误）"时清该柜键。

### P0-4 消费者开门：幂等键无时效 + `adopt_fulfilled` 绕过终态校验 ⇒ 再扫同一柜不开门、接管上一次会话
- **位置**：`clients/consumer-mp/src/utils/consumer-open-attempt.ts:27`（键复用）、`clients/consumer-mp/src/pages/index/index.vue:1380`（接管分支）；后端 `SessionService.java:471-476`
- **证据**：
  ```ts
  // consumer-open-attempt.ts
  if (isOpenAttempt(saved) && saved.deviceId === normalized && saved.idempotencyKey) return saved;
  // ← createdAt 写了但全仓无人读（grep 仅定义与赋值）
  ```
  ```java
  // SessionService.validateIdempotentReplay —— 只校验归属，不看状态
  if (!session.getUserId().equals(userId) || !session.getDeviceId().equalsIgnoreCase(deviceId)) throw CONFLICT;
  return toDto(session);   // COMPLETED / CANCELLED 也原样回放
  ```
  ```ts
  // index.vue:1380
  if (sessionOpenDecision(sessionResult.status) === 'adopt_fulfilled') {
    adoptSession(...);            // ← 未用 isAdoptableSession
    hapticMedium(); return true;
  }
  ```
- **影响**：同一柜机的旧键只有 cancel/abort/finish/404 才清。用户开门途中杀进程、断网、或服务端已把会话结束（COMPLETED/CANCELLED）后，下次扫同一台柜仍复用旧键 → 后端回放终态会话 → 前端 `adoptSession` 直接 `setActiveSession + startPoll`，**柜门不开**，并可能把用户带进上一笔账单/「会话已结束」。对照：同文件 `adoptOrphanSession`（`:1351`）用了 `isAdoptableSession`（同柜且非终态），说明"终态不接管"是既有约定，此分支是漏网。
- **精确触发条件（2026-11-10 复核补充）**：本地存有 `ACTIVE_SESSION_KEY` 时，恢复流程只在「非终态」与 404 两条分支清旧键（`index.vue:2347`/`:2368`）。真正的洞是 `restoreActiveSession` 的 `if (!s) return`（`index.vue:2344`）——后端 `getActiveSession:338` **只查非终态**，会话已结束时返回 `null`，此时旧 `ACTIVE_SESSION_KEY`（和 `OPEN_ATTEMPT_KEY`）都不清，下一次扫码直接回放终态会话。
- **修法**：① `getOrCreateOpenAttempt` 增加时效（如 2 分钟，`Date.now() - createdAt > TTL` 即换新键）；② `adopt_fulfilled` 分支先过 `isAdoptableSession`，终态则 `clearOpenAttempt()` 并重试一次开门；③ `restoreActiveSession` 的 `null` 分支同步清 `ACTIVE_SESSION_KEY` + `OPEN_ATTEMPT_KEY`。

---

## 3. P1（正文 10 条；另 4 条由 §7 定论后并入，合计 14 条）

### P1-1 admin 设备策略加载失败时伪造「全关」，再拨任一开关会整份回写覆盖真实锁状态 ✅
- **位置**：`clients/admin-vue/src/composables/device/useDeviceRemoteOps.ts:112`（兜底）、`:197-210`（保存）
- **证据**：
  ```ts
  } catch {
    policy.value = { deviceId, salesLocked: fallbackSalesLocked,
      priceLocked: false, skuEditForbidden: false, saleForbidden: false };
  }
  ...
  policy.value = await api.request(AdminEndpoints.devicePolicy(id), 'PUT', policy.value);
  ```
- **影响**：`GET /ops/devices/{id}/policy` 失败（弱网/500/权限）后表单显示四个"关"，无任何提示；运营改动任一开关 → PUT **整份策略**，真实开启的「禁售/营业锁机」被静默改成 false，柜机恢复售卖。
- **修法**：加载失败置 `policyLoadFailed` 并禁用策略区（给重试）；保存只提交本次变更字段或带 version 做乐观锁。

### P1-2 admin 补货路线：`loadAssignees` 的 seq 与 loading 顺序颠倒 ⇒ 负责人选项永久空、loading 永久 true ✅
- **位置**：`clients/admin-vue/src/composables/replenishment/useReplenishmentRoutePlanning.ts:77-78`、`finally` 在 `:99-101`
- **证据**：
  ```ts
  const seq = deps.loadSeq.begin('loadAssignees');   // 先 begin（seq+1）
  if (assigneeLoading.value) return;                 // 再判 in-flight
  assigneeLoading.value = true;
  ...
  } finally {
    if (!deps.loadSeq.isCurrent(seq, 'loadAssignees')) return;  // 旧请求直接 return
    assigneeLoading.value = false;
  }
  ```
- **影响**：`openPlan()` 与深链 `?plan=1` 的 `maybeAutoPlanFromQuery()` 都会调它。第二次调用把 seq 顶掉并不再发请求；第一次的 `finally` 判非最新 ⇒ **不复位 loading**。此后每次调用都在 `:78` 直接 return ⇒ 负责人选项永远为空、规划路线主流程不可用（可编辑内已有负责人兜底，但选不了人）。
- **修法**：`if (loading) return` 放在 `begin()` 之前；`finally` 无条件 `loading=false`（只在赋值处用 seq 判新旧）。

### P1-2'（原 P0-2，验证后降级）柜机盘点：空输入被当成「实盘 0」，靠确认弹窗的差值串兜底 ✅ + 实跑复现
- **位置**：`clients/merchant-mp/src/pages/device-stocktake/device-stocktake.vue:196`（提交前确认在 `:218`，死校验在 `:207`）
- **证据**：
  ```js
  countedQuantity: Number(goodsInputs.value[g.skuId] ?? '')
  .filter((l) => { if (Number.isNaN(...) || <0) return false; return l.countedQuantity !== book; });
  const bad = changed.find((l) => Number.isNaN(l.countedQuantity) || l.countedQuantity < 0); // 与 filter 同条件 ⇒ 恒 undefined
  ```
  实跑复现（`.tmp/review/verify-stocktake.mjs`）：`Number('') = 0`、`Number('' ?? '') = 0`，台账 20 的行被清空后 `changed = [{"skuId":1,"countedQuantity":0}]`，`bad = undefined`。
- **后端语义**（已对到正确类）：`MerchantDevicePortalService.submitDoorStocktake:234`（注释「逐商品实盘覆盖式调账」）→ `InventoryLotService.stocktakeAdjust(..., int countedQuantity, ...):436` → `int delta = countedQuantity - current; :450`。**实盘数即最终数**，空输入落 0 就是把该 SKU 调成 0。
- **为什么不是 P0**：`:218` 有 `uni.showModal`，正文是 `系统将按实盘调账，差值：${diffText}`，`diffText` 把每行差值拼出来（台账 20 被清空会显示「-20」）。用户有可见信号，只是多行时容易漏看。
- **仍然是缺陷**：① 「没填」与「填 0」语义不同，前端把前者当后者；② `:207` 的 `bad` 是死代码，整数校验实际缺失——`2.5` 能进 `changed`，后端 `@Min(0) int` 反序列化会 400，用户看到的是一句泛化失败而不是"数量必须是整数"。
- **修法**：判空与整数校验移到 `filter` 之前（`raw.trim()==='' || !Number.isInteger(n) || n<0` 直接阻断并聚焦该行），删掉死代码；顺带把空值行的差值文案改成「未填写」而不是 `-N`。

### P1-3 admin 反馈「删除」把查看码也算进权限，后端只认回复码（MA-111 回归）✅
- **位置**：`clients/admin-vue/src/views/feedback/FeedbackView.vue:174-176`（`canDelete`）、`:262-268`（行动作 `perm`）；后端 `api/UserFeedbackController.java:43-45`
- **证据**：
  ```ts
  const canDelete = computed(() => auth.hasPerm('ops:feedback') || auth.hasPerm('ops:feedback:reply'));
  ...
  perm: ['ops:feedback', 'ops:feedback:reply']
  ```
  ```java
  // MA-111：删除是物理删用户原文，不能由查看码（ops:feedback）触发
  @RequiresPermissions("ops:feedback:reply")
  ```
- **影响**：只有查看权的运营能看到「删除」，点击必 403。这是已修问题的复发（后端注释即证据）。
- **修法**：前端两处统一为 `ops:feedback:reply`。

### P1-4 admin 选品诊断「确认下架」无按钮级权限，后端要求 `ops:sku-review:edit` ✅
- **位置**：`clients/admin-vue/src/views/growth/SkuReviewView.vue:242-247`（三个决策动作）、`:15-32`（批量按钮）；后端 `api/AdminGrowthController.java:112`
- **影响**：仅 `ops:sku-review:list` 的角色可点「确认下架/保留/建议下架」，提交 403；一旦该码误配到写侧或后端放宽，就是越权停售 SKU。
- **修法**：三个动作 + 批量按钮加 `perm: 'ops:sku-review:edit'`。

### P1-5 admin 资金操作 `catch {}` 且无全局提示 = 静默失败 ✅
- **位置**：`clients/admin-vue/src/views/finance/LineRoutesView.vue:574`（标记打款）；同类 `MerchantFeeLedgerView.vue:185`（标记结清无 try/catch）、`MachinePayeeBillsView.vue:286`（配置加载失败沿用上一台柜机的值）
- **证据**：
  ```ts
  } catch {
    // 余额不足等错误由后端返回…全局拦截器已提示   ← 该注释不成立
  }
  ```
- **核对**：`clients/admin-vue/src/api/client.ts` 无 `ElMessage`/toast（仅 `:143` catch、`:192` 解析错误体），`packages/shared-api` 也无全局提示层。注释所依赖的"全局拦截器"不存在。
- **影响**：打款/结清失败时弹窗不关、列表不刷、零提示，运营会重复提交或以为已成功；`MachinePayeeBillsView` 更危险——查询失败后表单**残留上一台柜机**的分账比例与收款人，照此保存会写到旧设备。
- **修法**：逐处 `catch (e) { ElMessage.error(errorMessage(e, '…')) }`；`MachinePayeeBillsView` 加载失败清空表单并禁用保存。

### P1-6 消费者结算后金额查询失败被当成 0，toast 明确告知「本次未消费」✅
- **位置**：`clients/consumer-mp/src/pages/index/index.vue:2229-2232`；文案 `packages/shared-uni/src/notify.ts:167-169`
- **证据**：
  ```ts
  let totalCents = 0;
  try { const order = await consumerApi.getSessionOrder(sid); totalCents = order?.totalAmountCents ?? 0; }
  catch { /* 零元单或查询失败仍跳转结果页 */ }
  showBillToast(totalCents);
  ```
  ```ts
  const title = totalCents <= 0 ? '本次未消费' : `已扣款 ¥${yuan}`;
  ```
- **影响**：结算瞬间 `getSessionOrder` 404/弱网失败时，用户刚被扣款却收到「本次未消费」，属对资金事实的错误陈述，易升级投诉。
- **修法**：区分"查询失败（未知）"与"确认 0 元"；未知时只提示"账单已生成，正在打开"，金额交由结果页展示。

### P1-7 消费者微信/支付宝充值取消后 pending 残留，之后每次进首页/我的阻塞约 12 秒 ✅
- **位置**：`clients/consumer-mp/src/utils/recharge.ts:285-288`（微信）、`:317`（支付宝）
- **证据**：
  ```ts
  savePendingRechargeOrder(prepay.orderId);
  await invokeWxRequestPayment(prepay.wxPay as WxPayLike);  // 用户取消 → reject
  await pollRechargePaid(prepay.orderId, 20, 1500);
  clearPendingRechargeOrder();                              // ← 取消时永不执行
  ```
- **影响**：pending 在下单后即写、只在走完轮询后清。用户点取消收银台后残留；下次进页 `onAuthenticatedShow` → `await resumePendingRechargeIfAny()` 要跑满 8×1.5s ≈ 12s 才返回 false，期间会话恢复被推迟，且每次进页重演（mine / recharge 页同源）。
- **修法**：`invokeWxRequestPayment` 失败/取消时清 pending；resume 改为不阻塞（fire-and-forget）。

### P1-8 消费者结果页退款成功后不回读订单：显示「已退款」但金额仍是原额 ✅
- **位置**：`clients/consumer-mp/src/pages/result/result.vue:762`
- **证据**：
  ```ts
  refundDone.value = true; disputeFiled.value = true; showDispute.value = false;
  statusLabel.value = '已退款';
  showSuccess(result.message || '退款成功');    // ← 没有 reload / applyOrderSnapshot
  ```
- **影响**：该页自动刷新只对非终态继续（`:502`），退款后不会自己拉新；`refundedCents/refundedAt` 未更新，模板「退款」行不出现、「实付」仍是原额，却已显示"退款已完成"。对照 `order-detail` 同场景是 `await reload()`，两页口径不一致。
- **修法**：成功后 `await refreshQuietly()` 或直接用接口返回快照 `applyOrderSnapshot`，再提示成功。

### P1-9 支付宝壳订单页承诺「订单将自动同步至此」，实际是纯占位、真实链路不经此小程序（文案与 README 已对照；审核后果需业务确认）
- **位置**：`clients/alipay-mp-shell/pages/orders/orders.axml:24-28`
- **证据**：
  ```
  <view class="empty-text">扫描柜门二维码开门取货后，订单将自动同步至此</view>
  ```
  同目录 `README.md`：`消费者实际购物链路不经由此小程序：柜机二维码 → H5 落地页 → schemeUrl(appId=20000067) 唤起`；`订单列表当前为占位态`。
- **影响**：① 对用户是空承诺（页面永远「暂无订单」）；② 该壳的唯一用途是芝麻先享**审核载体**，官方要求"订单查询"，审核方若核对功能可用性，占位页与文案不符是驳回/抽查风险。
- **修法**：二选一——按文案接入真实订单查询（哪怕只读 `sessionId → order` 的支付宝侧入口），或把文案改成"订单请在支付宝-信用服务/账单中查看"并说明本页用途。

---

## 4. P2（8 条，节选）

| # | 位置 | 结论 | 复核 |
|---|---|---|---|
| 1 | `admin-vue/src/components/warehouse/WarehouseMonthlyCloseTab.vue:254` | 「上柜」列 `row.shelvedQty ?? row.qty` 兜底方向错：字段缺失时把领货量当上柜量，与同行「领而未上」自相矛盾。后端 `WarehouseMonthlyCloseService:343` 恒传 `qty - remainderQty`，故当前不会触发，但兜底值本身是错的（应为 `—` 或 `qty - remainderQty`） | ✅ |
| 2 | `merchant-mp/src/views/.../video.vue` 未提交改动 | 播放器改按片源宽高比算高度：逻辑正确（`object-fit: contain` 不会变形）；但 `var(--text-muted)` 无 fallback，未过 `check-mp-css-token-fallback`？——该门禁只查令牌覆盖率不查 fallback，属规则空档 | ✅ |
| 3 | `admin-vue/src/components/CrudTable.vue:1162` | 内置 CSV 导出只导**当前页**（`displayItems`），未勾选时也在无提示下导出 20 条，而 `total` 可能是数千；资金类页面（退款/充值/争议/对账）都在用这个壳 | ⚠️ |
| 4 | `admin-vue/src/views/finance/FundBillView.vue:580` | 同一页头按钮：日账单走后端全量导出，账务明细只导当前页，两 tab 口径不同且无提示 | ⚠️ |
| 5 | `admin-vue/src/views/finance/BalanceRefundView.vue:59` / `InvoiceListView.vue:71` | 关键词在前端过滤当前页，`total` 仍取服务端全量 ⇒ 搜第 2 页以后的单是空表但分页器写"共 N 条"；因写法是 `filterByKeyword(res.items)` 而逃过 `check:list-filter-server-side` | ⚠️ |
| 6 | `admin-vue/src/views/warehouse/SupplierReconciliationView.vue:143` | 手写 CSV 拼装未中和公式注入（`=`/`+`/`-`/`@` 开头仍被 Excel 当公式），而这份文件**外发给供应商**；`check-csv-escape` 只扫 Java | ⚠️ |
| 7 | 三端 `money-ui-contracts.ts` | 可退款状态白名单三套并存：admin = `PAID/COMPLETED/DISPUTED/PARTIAL_REFUNDED`；consumer 详情 = `PAID/COMPLETED/PARTIAL_REFUNDED`；consumer 结果页 = `PAID/COMPLETED`（注释写"与 result.vue 历史行为一致"）。同一张 `PARTIAL_REFUNDED` 单在不同页能做不同操作，无单一源 | ✅ |
| 8 | `merchant-mp` lint warning | 6 个页面 `MerchantMe` 类型导入未使用、`playbackUrl` 赋值未使用、`merchant-api.ts` 两处未用参数——重构残留，建议随修复批清理 | ✅ |

---

## 5. 系统性缺口（比单条缺陷更值得处理）

### 5.1 `alipay-mp-shell` 完全在所有前端门禁之外
- 实跑统计：`scripts/check-*.mjs` 中引用 `clients/` 的 **50 个**，引用 `consumer-mp` 的 **26 个**，引用 `alipay-mp-shell` 的 **0 个**。
- 具体盲区：`check-mp-defect-classes`（SCOPES = consumer/merchant/shared-uni）、`check-mp-emoji-icon`（同）、`check-mp-touch-target-ratchet`、`check-mp-css-token-fallback`（MP_APPS 写死两个 App.vue）、`check-miniapp-privacy-declaration`、`check-mp-template-comments`。
- 现实后果已经出现：`orders.axml` 用 emoji `🧾` 当图标 —— 微信两端这条被门禁禁止，支付宝壳因不在扫描范围而放行（支付宝是否接受 emoji 图标另说，但"同一缺陷类在不同端有不同判据"本身就是门禁失效）。
- **建议**：把 `clients/alipay-mp-shell` 纳入缺陷类门禁的 SCOPES（`.axml/.acss` 作为新增扩展名）；顺手把 P0-1 的 prettier 修复一起做，否则这一端会持续漂移。

### 5.2 金额换算门禁只覆盖「分→元」，元→分方向无门禁
- `check-no-inline-money-math` 的注释明确「`* 100` 是百分比换算，不在本门禁范围」，`PATTERN = /\/\s*100\b/g`。
- 但 admin 有 5 处以上用 `Math.round(yuan * 100)` 做**元→分**（`PlatformFundView.vue:294/334`、`DeviceRentalView.vue:622`、`LineRoutesView.vue:567`、`MachinePayeeBillsView.vue:263`），同一文件里另一处却走 `yuanToCents` —— 同类换算两套写法，精度依赖 `el-input-number` 的 `precision` 兜底。
- **建议**：门禁补 `* 100` 分支或加"元→分必须用 `yuanToCents`"的独立规则；这条属门禁设计缺口，不是"某人写错"。

### 5.3 `test:ci` 名不副实 + 本机必红
- 两端 `test:ci = type-check && test:uat`，而 `test:uat` 需要本地 `127.0.0.1:3002`（dev:h5 + 网关）；本机实跑 exit 2。
- CI 里跑的是 `pnpm --filter … test`（vitest），**不跑 `test:ci`**。所以这个脚本既不在 CI 用，本地也要先起栈，实际处于"没人跑"状态。
- **建议**：改名 `test:e2e-local` 并在 README 标注前置条件；或把 UAT 纳入 CI（起 dev:h5 + 后端）——但后者成本高，前者更诚实。

### 5.4 新增端的安全边界（需业务确认，未定性为缺陷）
- `mini.project.json` 的 `urlCheck: false`、`app_id` 硬编码入库：前者只影响开发者工具域名校验，后者是工具链惯例，均可接受。
- 隐私政策文本已写（`docs/figures/alipay-mp-shell-privacy-policy.txt`），但**小程序内无任何入口**（全目录 grep "隐私/privacy" 无命中）。支付宝审核通常要求隐私政策可查，建议上线前补入口。
- 客服电话 `4000182992`、运营主体名在两页各硬编码一次：官方会拨测，改主体需改两处，建议抽常量。

### 5.5 部署面（工作区未提交改动，附带发现）
- `infra/docker-compose.apps.yml` / `full.yml` 的 MinIO 生命周期改为整桶 `mc ilm rule import`（幂等，图片资产永久保存）——方向正确，修的是真实事故（14 天全桶规则删掉 SKU 图）。
- ✅ **`mc ilm rule import` 的 stdin 用法已验证**（镜像 `a7fe349ef4bd`，即 compose 里钉的那个 digest）：`mc ilm rule import --help` 明确写 `USAGE: mc ilm rule import TARGET` + `Import entire lifecycle configuration from STDIN`，EXAMPLES 第 2 条即「不传文件、从 STDIN 读」。故 `printf … | mc ilm rule import local/$BUCKET` **写法正确**，不需要补 `-`。
- 仍建议注意：命令末尾无 `|| true`，MinIO 未就绪时 init 容器会失败并阻断依赖服务（这是刻意 fail-closed，可接受，但要清楚后果）。

---

## 6. 已核对、判定无问题（避免下轮重复审）

**主审亲验（✅）**

✅ **补货幂等后端契约**：`MerchantReplenishmentService:421-430` 按 `clientRequestNo` 查重、设备不符返回 409 ⇒ 前端只要复用同键即可幂等（P0-3 是前端没用上）。
✅ **盘点覆盖语义（后端）**：开场柜走 `MerchantDevicePortalService.submitDoorStocktake:234` → `InventoryLotService.stocktakeAdjust:436`（`int countedQuantity`，`delta = counted - current`）⇒ 实盘即最终数，P1-2' 是前端送错值，不是后端错。
✅ **账单提示文案**：`packages/shared-uni/src/notify.ts:167-169` 对 `<=0` 固定输出「本次未消费」（P1-6 的后果由此坐实）。
✅ **admin 请求层无全局错误提示**：`admin-vue/src/api/client.ts` 全文无 `ElMessage`/toast；`packages/shared-api` 亦无（P1-5 的注释"全局拦截器已提示"不成立）。
✅ **反馈删除 / 选品审核的后端权限**：`UserFeedbackController.java:43-45`（仅 `ops:feedback:reply`）、`AdminGrowthController.java:112`（`ops:sku-review:edit`）。
✅ **月结上柜字段后端恒有值**：`WarehouseMonthlyCloseService:343` 传 `acc.qty - acc.remainderQty` ⇒ P2-1 的 `?? row.qty` 当前不触发。
✅ **三端可退款白名单**：`admin-vue/src/utils/money-ui-contracts.ts` 与 `consumer-mp/src/utils/money-ui-contracts.ts` 两份常量逐字对比，确认三套口径（P2-7）。

**子代理报告、主审未亲验（⚠️ 采信前需自行过一眼）**

⚠️ **CSV 公式注入（admin 主路径）**：`src/utils/csv.ts:34-41` 对 `= + - @ TAB CR` 前缀加 `'`，带 BOM 与引号转义（例外见 P2-6）。
⚠️ **admin 列表分页竞态**：`useCrudTable.ts:99-186` 用 `createLoadSeq` 丢弃过期页，失败不画"暂无数据"。
⚠️ **菜单权限码类型**：`V451__fix_menu_perm_type.sql` 已把 `ops:rbac:assign`、`ops:approval:config` 改为 C；`menu.ts` 56 个菜单码均为 C/M。
⚠️ **小程序 401/刷新**：请求层刷新单飞 + 轮换旧 token，写入不自动重试，GET 传输错误最多重试 2 次。
⚠️ **consumer 定时器生命周期**：index 的识别/设备轮询/倒计时均在 `onHide/onUnload` 清理；`useSessionPoll.stopPoll` 会唤醒退避睡眠。
⚠️ **提现幂等（merchant）**：`pendingRequestNo + pendingWithdrawCents` 组合键在金额变更时重建、传输错误保留、业务失败清空。

---

## 7. 原「未复核 7 条」—— 已于 00:40 全部读码定论

| # | 条目 | 定论 | 证据 |
|---|---|---|---|
| 1 | `purchase.vue:284` / `bills.vue:174` 冷启动误判无权限 | **成立，P1（措辞修正）** | `App.vue:59 onLaunch` 只 `loadRuntimeDict()`，**不刷 me**；`useMerchantMe` 在 setup 里 `onShow(refresh)`（异步 fire-and-forget）先注册，页面自己的 `onShow(load)` 后注册并**同步执行** ⇒ 首次 load 时 `me` 必为 null。页面无 watch 重载 ⇒ 首屏误报，**下拉刷新或重进即自愈**（不是永久不可用，原措辞过重） |
| 2 | 将邑采集上传 401 不续期、英文串直出 | **成立，P2** | `merchant-api.ts:1234` 直接 `reject(new Error('UPLOAD_AUTH_EXPIRED'))`；`jiangyi-capture.vue:154` `error.value = e.message` ⇒ 商户看到 `UPLOAD_AUTH_EXPIRED` |
| 3 | `settlements.vue:347` 客单价口径 | **成立，P1** | `isZeroMoneyRow` = `grossCents===0 && merchantCents===0`（`:256-257`），`visibleDaily` 剔除这些行；`summary = summarizeDays(visibleDaily)` 而 `avgOrder = gross/orders` ⇒ 关掉"零元单"后 gross 不变、orders 变小、客单价变大 |
| 4 | 结果页 vs 订单详情部分退款能力不一致 | **成立，P2** | 常量层已对比（`RESULT_PAGE_REFUNDABLE_STATUSES` 无 PARTIAL_REFUNDED）；`result.vue:148` 以 `canRefundNow` 渲染入口 ⇒ 同一单在详情可续退、在结果页无入口 |
| 5 | `order-detail.vue:598` reload 无 seq | **成立，P2** | `order.value = await consumerApi.getOrder(...)` 直接赋值，无 `createLoadSeq`；而 `orders.vue` 有 seq 守卫 |
| 6 | 盘点过账/复盘/取消无二次确认 | **成立，P1** | `useWarehouseStocktakes.ts:111/126/140/154` 四处直接 `api.request`；`components/warehouse/*` + `views/warehouse/*` 全目录 grep `ElMessageBox` 只有 2 个无关文件命中 |
| 7 | Dashboard 首屏失败仍置 hydrated | **成立，P1** | `DashboardView.vue:755` silent 不提示；`finally` 里 `if (isCurrent) listHydrated = true` **无条件置真**；`stats` 初始 `{}` ⇒ KPI 渲染 0 |

---

## 8. 建议修复顺序

1. **立刻**（阻断/资金）：P0-1（prettier，5 分钟）→ P0-3（幂等键提升）→ P0-4（开门键时效 + 终态校验）→ P1-2'（盘点判空）。
2. **本批**：P1-1、P1-2（两条都是"加载失败被当成有效空值"，同类修法）；P1-5（资金静默失败）；P1-6、P1-8（支付末端表述）；§7 #1（商户端首屏误判无权限）、§7 #7（Dashboard 首屏 0）。
3. **同轮落门禁**（铁律 75）：P0-1/P0-3/P1-2' 修完后各补一条门禁——alipay 纳入 SCOPES；"输入框空值不得当 0"可做成 mp 缺陷类规则；"幂等键必须页面级存活"较难静态判，至少加注释锚点 + 后端 409 日志告警。
4. **随后**：§5.1 门禁纳管、§5.2 元→分门禁、§5.3 脚本改名、§5.5 `mc ilm` 实跑确认。
5. **入台账**：本轮未改 `docs/AUDIT_LEDGER_2026-10-13.md`。若采纳，建议把 P0-1/P0-3/P0-4 与 §5.1 登记为 AU 编号后再开工（编号唯一源）。

---

## 9. 验证记录（2026-11-10 00:35–00:50）

### 9.1 实跑命令与结果

| 命令 | 结果 | 结论 |
|---|---|---|
| `pnpm exec prettier --check clients/alipay-mp-shell` | `[warn]` × 3（`mini.project.json`、`pages/orders/orders.js`、`README.md`） | P0-1 复现。注：preflight 的 glob 不含 `.md`，故 preflight 只报 2 个文件 |
| `node scripts/pre-push-ci-preflight.mjs` | `✗ FAIL: format:check (exit 1)` | 首关即停，后续步骤未执行（fail-fast 已确认） |
| `pnpm -s lint` | 0 error / 30 warning | 基线绿；warning 内容见 P2-8 |
| `pnpm -s build:admin:typecheck` | exit 0 | admin 类型绿 |
| `pnpm --filter @aicabinet/merchant-mp run type-check` | exit 0 | merchant 类型绿 |
| `pnpm -s test:mp` | exit 2（`fetch failed` → 127.0.0.1:3002） | consumer `type-check` 段通过，卡在 `test:uat` 需本地栈；见 §5.3 |

### 9.2 复现脚本（均在 `.tmp/review/`，未入库）

**`verify-landing.mjs`** —— 直接 `import` 生产纯函数（`node --experimental-strip-types`）：
```
SESSION_ACTIVE_STATES = ["CREATED","OPENING","SHOPPING","RECOGNIZING","WAITING_UPLOAD","SETTLING"]
decision(fulfilled) = adopt_fulfilled
isAdoptableSession(同柜, COMPLETED) -> false
isAdoptableSession(同柜, CANCELLED) -> false
```
⇒ P0-4 的判定冲突是**运行时可证的**：`fulfilled` 分支拿到的就是 `adopt_fulfilled`，而"终态不可接管"的判据对该会话返回 false，该分支却从不调用它。

**`verify-stocktake.mjs`** —— 复刻 `device-stocktake.vue:196-207` 的表达式链：
```
Number('') = 0 ；Number('' ?? '') = 0
changed = [{"skuId":1,"countedQuantity":0}]      // 台账 20 的行被清空 → 以 0 提交
bad = undefined                                  // 与 filter 同条件 ⇒ 恒不触发
小数 2.5：changed = [{"skuId":2,"countedQuantity":2.5}]  // 前端放行（后端 int 会 400）
```
⇒ P1-2' 的"空⇒0"与"死校验"两点成立；但同文件 `:218` 的 `uni.showModal` 会显示差值 `-20`，故降级。

**`createdAt` 全仓读取点**：`grep` 仅命中 `consumer-open-attempt.ts:9`（类型定义）与 `:32`（赋值），**无任何读取** ⇒ P0-4 的"无时效"成立。

### 9.3 读码定论
原 7 条 ⚠️ 全部定论，见 §7 表格（其中 #1 措辞修正为"首屏误判、可下拉自愈"，#3/#6/#7 升为 P1）。

### 9.4 本轮**未能**验证的项（诚实标注）

- **运行时点击链**（盘点弹窗、开门失败路径、Dashboard 断网首屏）：需 dev 栈 + 小程序模拟器，本轮为静态 + 纯函数口径。P0-3/P0-4 的"用户可感知后果"是按代码路径推演，未在真机复现。
- **`mc ilm` 已补验**：`docker run --rm --entrypoint mc a7fe349ef4bd ilm rule import --help` 确认从 STDIN 读配置，compose 写法正确（§5.5）。

### 9.5 用户独立复核（2026-11-10 00:56）

用户逐条独立复核（读码 + 实跑纯函数），结论「全部站得住」，另订正 3 处引用细节（不改结论）：

| # | 原写法 | 订正为 | 核实方式 |
|---|---|---|---|
| 1 | 后端查重 `MerchantReplenishmentService:421-427` | `:421-430`（回放 `return` 在 `:429`） | `sed -n '415,435p'` 逐行数，`if (!clientRequestNo.isEmpty())` 起于 421、块止于 430 |
| 2 | P0-3 只写"页面停留勾选态" | prep-list 会**自动重勾**已成功柜（`checked: items.length > 0`，`:356`），无需用户手动重勾 | 已补进 P0-3「prep-list 更隐蔽」段 |
| 3 | 采集上传 401 `merchant-api.ts:1220-1222` | `:1234` | 读 `:1228-1240`，`reject(new Error('UPLOAD_AUTH_EXPIRED'))` 在 1234 |

另补 P0-4 精确触发条件：`restoreActiveSession` 的 `if (!s) return`（`index.vue:2344`）——后端 `getActiveSession:338` 只查非终态，返回 `null` 时旧 `ACTIVE_SESSION_KEY` 不清，下次扫码即回放终态会话。已写进 P0-4 与 §9.6 修复记录。

### 9.6 修复落地（2026-11-10 01:00 起）

见 §10「修复记录」。

---

## 10. 修复记录（2026-11-10 01:00–01:20）

> 范围：3 条 P0 + 同轮门禁（铁律 75）。P1/P2 未动，§8 的修复顺序仍适用。

### 10.1 改动清单

| 项 | 文件:行 | 改法 |
|---|---|---|
| P0-1 | `clients/alipay-mp-shell/`（3 文件） | `prettier --write` 纯格式：`mini.project.json`、`pages/orders/orders.js`（在 format:check glob 内）、`README.md`（**不在** glob 内，表格对齐被顺带规整，内容未变） |
| P0-3 | `utils/self-replenish-submit.ts:40-70` | 新增 `nextPrepRequestNo()`（成功/传输未知保留键，仅业务失败清空）+ `takePrepRequestNo()`（页面级 store 取/建，已有键直接复用） |
| P0-3 | `pages/prep-generate/prep-generate.vue:154, 283-302` | `prepRequestNos` 提升为页面级 `Map`；提交前 `takePrepRequestNo`，`finally` 按 outcome 决定是否删该柜键 |
| P0-3 | `pages/prep-list/prep-list.vue:277, 439-458` | 同上。该页部分失败后 `loadPreview()` 会自动重勾已成功柜（`:356`），是重复提交的实际入口 |
| P0-4 | `utils/consumer-open-attempt.ts:9-68` | 新增 `OPEN_ATTEMPT_TTL_MS=2min` 与 `isOpenAttemptFresh()`；`isOpenAttempt` 要求 `createdAt` 有限（旧数据无 `createdAt` ⇒ 换新键，防 `now - undefined = NaN` 让键永生）；`getOrCreateOpenAttempt(deviceId, now)` 判时效 |
| P0-4 | `pages/index/index.vue:1380-1409` | `adopt_fulfilled` 先过 `isAdoptableSession`；终态则 `clearOpenAttempt()` + 新键重开一次，仍不可接管才回落孤儿查询 |
| P0-4 | `pages/index/index.vue:2367-2373` | `restoreActiveSession` 的 `if (!s)` 分支补清 `ACTIVE_SESSION_KEY` + `OPEN_ATTEMPT_KEY`（后端只查非终态，null 即会话已结束） |

### 10.2 同轮门禁（判据取自本轮真实事故）

`scripts/check-mp-defect-classes.mjs` 新增两类：

- **R 幂等键局部容器**：`submit()/onSubmit()/handleSubmit()/doSubmit()` 函数体内出现 `new Map`，且同文件使用 `clientRequestNo` ⇒ 键随函数返回即销毁。
- **S 终态会话接管**：含 `'adopt_fulfilled'` 的函数体内调用 `adoptSession(` 却无 `isAdoptableSession(` ⇒ 终态会话被无条件接管。

自检从 56 例扩到 **62 例**；`scripts/devops/verify-mp-defect-class-gate.mjs` 注入键 20 → **22**（A–S 十九类）。

### 10.3 实跑结果

| 命令 | 结果 |
|---|---|
| `CODEBUDDY_SAFE_DELETE_ENABLED=0 node scripts/pre-push-ci-preflight.mjs` | **exit 0**；聚合链 98 个门禁 **0 失败** |
| `format:check`（preflight 内） | ✓（修前是 FAIL，P0-1 复现即此） |
| `lint` | 0 error / **30 warning**（与基线同数，未新增） |
| consumer / merchant `type-check` | ✓ / ✓ |
| merchant `vitest run` | 38 文件 / **249 用例**全绿（含新增 1 例） |
| consumer `vitest run` | 26 文件 / **174 用例**全绿（`consumer-open-attempt` 5 例，含新增 3 例） |
| `check:mp-defect-classes` | OK：270 文件 / 十九类零命中 / 自检 62 例 |
| `check:mp-gate-negative` | 全 PASS（基线绿、逐类报对文件、注释不误伤、真实树 md5 零写入、沙箱零残留） |

### 10.4 两点环境噪声（不是本轮代码问题）

1. **首次 preflight 的两个红是垫片误报**：`check:permission-matrix-negative`、`check:openapi-types-negative` 的自测断言全 `ok`，崩在收尾 `rmSync` 沙箱时被 WorkBuddy `node-safe-delete-shim` 判「批量删除」（count 50/57 > 阈值 50，scope=turn）。`CODEBUDDY_SAFE_DELETE_ENABLED=0` 单独复跑两者均 exit 0，整链复跑 exit 0。CI 无此垫片。
2. **`pnpm test:mp` 仍 exit 2**：两端 `test:ci = type-check && test:uat`，而 `test:uat` 要 `127.0.0.1:3002` 本地栈；**单测（vitest）不在 `test:ci` 里**——即 §5.3 那条，未在本轮处理，验证改用 `vitest run`。

### 10.5 有意未动

- **后端 `validateIdempotentReplay` 不看状态**：前端已能兜住（TTL + 终态不接管 + `null` 清键），但服务端回放终态会话本身仍可改进。属幂等行为变更，需产品/后端一起定，本轮不擅改。
- **支付宝壳仍在所有前端门禁之外**（§5.1）：§11 已让它过格式门禁并修文案，但未纳入缺陷类/热区/令牌兜底扫描。
- P1/P2 见 §11。

---

## 11. P1 / P2 修复记录（2026-11-10 01:20–01:50）

> 范围：§3 的 9 条 P1 + §4 的 8 条 P2，共 17 条。逐条处理，未采纳的写明理由。
> 三项口径先与用户确认：P1-9 改如实说明、P2-5 前端显式标注（不动后端）、P2-3/P2-4 导出前提示范围。

### 11.1 P1（9 条）

| 项 | 文件:行 | 改法 |
|---|---|---|
| P1-1 设备策略伪造全关 | `composables/device/useDeviceRemoteOps.ts:55-58, 120-141, 213-217` | 失败不再兜底四个 `false`，改 `policy = null` + `policyLoadFailed` + `ElMessage.error`；`savePolicy()` 在失败态直接拒绝；新增 `reloadPolicy()`。模板 `DeviceRemoteOpsCard.vue` 改显错误块 + 重试按钮，开关区整块不渲染 |
| P1-2 补货路线 loading 永真 | `composables/replenishment/useReplenishmentRoutePlanning.ts:76-105` | `if (assigneeLoading) return` 移到 `loadSeq.begin()` **之前**；`finally` 改为无条件 `assigneeLoading = false` |
| P1-2' 盘点空值当 0 | `pages/device-stocktake/device-stocktake.vue:191-249` | 逐行 `raw.trim() === ''` 直接跳过（未填写 ≠ 填 0），`Number.isInteger(n) && n >= 0` 校验前移并指名到行；删除恒 `undefined` 的死校验；确认弹窗把「未填写行数」说出来 |
| P1-3 反馈删除权限错配 | `views/feedback/FeedbackView.vue:176, 259-268` | `canDelete` 与行动作 `perm` 统一为 `ops:feedback:reply`（与后端 `@RequiresPermissions` 一致） |
| P1-4 选品诊断无按钮权限 | `views/growth/SkuReviewView.vue:16, 25, 245-252` | 批量按钮加 `v-hasPermi="['ops:sku-review:edit']"`；三个行决策动作加 `perm` |
| P1-5 资金操作静默失败 | `views/finance/LineRoutesView.vue:564-582`、`MerchantFeeLedgerView.vue:170-193`、`MachinePayeeBillsView.vue:284-313` | 两处补 `ElMessage.error(errorMessage(e, …))`；MakerPayee 改 `configLoading` + 失败清空表单 + `configLoaded = false`（不留上一台柜的残值）+ 按钮 loading |
| P1-6 结算金额失败当 0 | `pages/index/index.vue:2253-2262`、`packages/shared-uni/src/notify.ts:175-190` | `totalCents` 改 `number \| null`：`null` = 未知 ⇒ 「账单已生成，正在打开…」；只有确认 `0` 才说「本次未消费」 |
| P1-7 充值取消后 pending 残留 | `utils/recharge.ts:187-197, 295-310`、`pages/index/index.vue:1160-1162` | 收银台 `reject` 时立即 `clearPendingRechargeOrder()` 再抛出（只让「支付成功但到账慢」保留）；`resumePendingRechargeIfAny` 加模块级 in-flight 单飞；首页调用改 `void`（不阻塞首屏恢复） |
| P1-8 结果页退款后不回读 | `pages/result/result.vue:765-776` | 成功后 `await refreshQuietly()` 再提示；状态按后端快照取（`PARTIAL_REFUNDED` 显示「部分退款」），快照没跟上才用「已退款」兜底 |
| P1-9 支付宝壳空承诺 | `clients/alipay-mp-shell/pages/orders/orders.axml:9-23` | 按确认口径改如实说明：「本页用于服务说明与守约入口，扣款记录请在支付宝『账单』/『信用服务』查看」；空态标题从「暂无订单」改为「订单查询入口」 |

### 11.2 P2（8 条）

| 项 | 文件:行 | 改法 |
|---|---|---|
| P2-1 月结「上柜」兜底反向 | `components/warehouse/WarehouseMonthlyCloseTab.vue:62-76, 274` | 新增 `shelvedQtyText()`：字段缺失时按 `qty - remainderQty` 算，qty 也没有才显示 `—`，不再把领货量当上柜量 |
| P2-2 视频页令牌无 fallback | `pages/video/video.vue:254, 281` | `var(--text-muted, #64748b)`（两处） |
| P2-3 / P2-4 内置导出只导当前页 | `composables/useListCsv.ts:6-52`、`components/CrudTable.vue:1160-1166`、`views/finance/FundBillView.vue:500-503` | `useListCsv` 增加 `scope()`；未勾选且 `total > 本页条数` 时 `ElMessageBox.confirm` 说清范围，成功提示带「共 N 条」。CrudTable 自动喂 `table.total`/`hasSelection`；账务明细页显式传 `ledgerTotal` |
| P2-5 关键词只过滤当前页 | `views/finance/BalanceRefundView.vue:285-294`、`InvoiceListView.vue:316-325` | 关键词非空时置顶 `el-alert`：「本页命中 M 条，服务端共 N 条；要查更早的请逐页翻」 |
| P2-6 供应商对账单 CSV 公式注入 | `views/warehouse/SupplierReconciliationView.vue:110-147` | 手写 `replace(/"/g)` 拼装改走共享 `escapeCsvCell()`（与页面内置导出同一判据），空行结构保留 |
| P2-7 三端退款白名单各写一套 | 新增 `packages/shared-uni/src/order-refund-policy.ts`；`admin-vue/src/utils/money-ui-contracts.ts`、`consumer-mp/src/utils/money-ui-contracts.ts` | 白名单收敛到 shared-uni 单一源，三套口径（admin 含 DISPUTED / 消费者详情不含 / 结果页只做首次全额）在文件头写明**为什么不同**；两端改为引用 + `isRefundableOrderStatus(status, scope)` |
| P2-8 merchant lint 残留 | 6 个页面 + `utils/merchant-api.ts` | 删除未用的 `MerchantMe` 类型导入（6 处）、`playbackUrl` 计算属性、两处未用 `err` 形参；顺带清 `shared-uni/notify.ts` 同类一条 |

### 11.3 同轮门禁（铁律 75：修的是「这一类」）

| 门禁 | 判据 | 为什么不能只靠 review |
|---|---|---|
| `scripts/check-admin-write-perm-codes.mjs` | admin 权限判定（`perm` 数组 / `v-hasPermi` / `hasPerm()\|\|hasPerm()`）里若同时出现 `A` 与 `A:xxx`，且该组合**不在**后端显式 `Logical.OR` 注解里 ⇒ FAIL | 首跑即抓到第三处同类（`MerchantSplitsView.vue:912`）；核实后确认那处与后端 `OpsGapFeaturesController` 的 OR 双门一致，属合理设计 ⇒ 判据改成自动对齐后端注解，不靠人工白名单 |
| `scripts/check-admin-csv-escape.mjs` | 文件同时含 `text/csv` 与 `new Blob(`（= 自己在造 CSV）时必须引用 `toCsv/downloadCsv/escapeCsvCell`，且不得出现手写 `replace(/"/g)` | 既有 `check:csv-escape` 只扫 Java，前端是空档；`crud-table` 与 `WarehouseView` 的导入控件曾被误判，故判据限定「自己造 Blob」 |
| `scripts/devops/verify-admin-gates.mjs` | 两道门禁的三态验证：基线绿 / 注入红且报对文件 / 注释与合理设计不误伤 / 真实树指纹不变 | 门禁自身必须能红（铁律 4）。沙箱跑，不在真实树写文件 |

接线：`check:admin-write-perm-codes`、`check:admin-csv-escape`、`check:admin-gates-negative` 均已进 `check:audit-gates` 聚合链（101 个引用全部有定义）。

### 11.4 实跑结果

| 命令 | 结果 |
|---|---|
| `node scripts/devops/verify-admin-gates.mjs` | 11 项全 PASS（含零残留、真实树指纹未变） |
| `check:admin-write-perm-codes` | OK：236 文件 / 9 处权限判定 / 零命中 |
| `check:admin-csv-escape` | OK：236 文件 / 1 处自产 CSV 走共享转义 |
| `check:audit-gates-wiring` | OK：101 个引用全部有定义，113 个 `check-*.mjs` 全部已接线 |
| `lint` | 0 error / **19 warning**（基线 30，降 11） |
| admin `vitest run` | 17 文件 / **132 用例**全绿（含 `money-ui-contracts` 15 例，白名单收敛后断言仍成立） |
| consumer `vitest run` | 26 文件 / **174 用例**全绿 |
| merchant `vitest run` | 38 文件 / **249 用例**全绿 |
| 三端 `type-check` | admin ✓ / merchant ✓ / consumer ✓ |
| `check:mp-defect-classes` | OK：271 文件 / 十九类零命中 / 自检 62 例 |
| `check:mp-css-token-fallback` | OK：2 端覆盖 103 个令牌 |
| `node scripts/devops/verify-outbound-remainder-gate.mjs` | OK：基线绿 + 19 条判据逐条注入红并还原绿；真实树 12 文件 md5 前后一致、沙箱已清理 |
| `pre-push-ci-preflight` | **✓ 通过**（2026-11-10 终跑，见下） |

**P2-1 门禁判据升级（2026-11-10 收口）**：P2-1 修复把 `WarehouseMonthlyCloseTab.vue` 的
`{{ row.shelvedQty ?? row.qty }}` 改为 `{{ shelvedQtyText(row) }}` 后，
`check-outbound-remainder-ledger.mjs:270` 的旧判据（全文件找 `row.shelvedQty`）与
`verify-outbound-remainder-gate.mjs` 的旧注入锚点双双失配（负向验证首跑 FAIL，属预期——探针与源码脱钩必须重新对准，不许放行）。处置：

1. **判据升级**：「上柜」列模板必须调 `shelvedQtyText(row)`（直接绑 `row.qty` = 退回领货量，红）**且**文件仍引用 `row.shelvedQty`（取值函数被掏空也红），双条件任缺一即红；
2. **注入锚点对准新形态**：`{{ shelvedQtyText(row) }}` → `{{ row.qty }}`，仍触发同一句「月结下钻不再渲染 shelvedQty」；
3. **三态复跑**：基线绿 → 19 条注入逐条红 → 还原绿，零污染断言通过；
4. `pre-push-ci-preflight` 全链 exit 0（format / mp type-check / lint / audit-gates / migration 门禁 / admin 产物）。

### 11.5 有意未采纳 / 留待决策

- **P2-5 未改后端 keyword 参数**：按确认口径走前端标注。后端 `AdminBalanceRefundController` / `InvoiceAdminController` 无该参数，扩参数属前后端契约变更，不在本轮。
- **P2-3 未改服务端全量导出**：多数实体没有全量导出端点，按确认口径只做范围提示。
- **P1-9 未接入真实订单查询**：支付宝侧登录态与跨端订单查询是新能力，按确认口径先改如实说明。
- **`check:frontend-perm-codes` 与新增门禁的边界**：前者管「后端不存在的幽灵码」，后者管「存在的查看码被当写码」，互补不重叠。

### 11.6 遗留事项收口（2026-11-10 晚）

| 遗留 | 处置 | 实跑证据 |
|---|---|---|
| 后端 `validateIdempotentReplay` 只校验 user/device（P0-4 后端半边，铁律 82 ③） | `SessionService` 回放新增状态闸：`COMPLETED/CANCELLED/DISPUTED` ⇒ 409「该开门请求对应的会话已结束，请重新发起开门」；**FAILED 有意放行**（重试窗口内客户端凭 FAILED DTO 展示失败原因，是正确语义） | `SessionIdempotentReplayTest` **5/5 绿**（3 旧 + 终态回放 409 循环用例 + FAILED 对照组） |
| alipay-mp-shell 游离在全部前端门禁之外 | 新门禁 `check-alipay-shell-gate.mjs`（判据贴壳的真实责任边界）：①微信品牌字样/微信 API 禁入（审核红线，**有意不抹注释**）；②orders.axml 占位如实说明（「请在支付宝」+「信用服务」）必须仍在——未来接真实订单门禁即红，强制重评（铁律 57）；③壳内禁网络调用；④凭证不落仓 + `app_id` 白名单单点；⑤防空扫（<8 文件红）。已接 `check:audit-gates` 聚合链 | `verify-alipay-shell-gate.mjs` 三态：基线绿 + 5 条判据逐条注入红→还原绿，真实壳 13 文件 md5 零污染；wiring OK（103 引用全定义、114 门禁全接线） |

---

*本报告为静态源码审计 + 可执行逻辑复现口径；未做真机运行时探测与渗透。所有 ✅ 条目均已对照源码（必要时对照后端 Java）确认；§7 的 7 条已从"未复核"升级为"读码定论"，§9.4 列明仍未验证的边界；§10 记录 P0 修复、§11 记录 P1/P2 修复与新增门禁，均附实跑证据。*
