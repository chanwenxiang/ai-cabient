# 四端代码审核报告 R3（2026-11-10，增量轮）

> **基线**：R2 基线 `5215c129` → HEAD `893bb2ca`（dev），47 提交 / 308 文件（+6362/−1368）。工作树仅 `scripts/probe-zhima-payafteruse.py` 一处 peer 在制改动（不在审计范围）。
>
> **范围**：R2 之后全部增量——①R2 三批 46 条修复在后续重构（尤其 `6f7c3ba8` 商户端 UI 重设计）中是否回归；②新增代码（将邑主图同步/模型确认旁路/挂接列、工作台下推、盘点回执批次口径、改量重提实时复查、新门禁脚本）。
>
> **方法**：5 路并行只读子代理（A admin / B merchant+shared-uni / C consumer+壳 / D trade 后端 / E 门禁脚本）+ **主审逐条亲验**：30 条发现全部由主审读当前 HEAD 源码复核（P1/P2 逐行读代码本体，P3 读关键断言），无撤账；2 条数据/运行时依赖项标注置信度。
>
> **纪律**：R2 已修且在位的不重报（§2 给出回归核对矩阵）；已知有意设计排除；纯样式重设计不报，只报「重设计删掉守卫/引入新缺陷」。

---

## 1. 总评

| 面 | P1 | P2 | P3 | 结论 |
|---|---|---|---|---|
| admin-vue（A） | 1 | 2 | 3 | A7 修复只守了加载侧，**保存响应侧同机制残留**（写错商户）；新代码复发 seq/静默清空家族 |
| merchant-mp（B） | 0 | 5 | 3 | UI 重设计未丢 R2 守卫，但**引入盲盘泄账面**；B6 修复留了「不点查询直接提交」串图旁路 |
| consumer-mp（C） | 1 | 2 | 2 | **R2-C1 宣称已修实际未闭环**：判据改了 status，错误源从不带 status，自愈仍是死代码 |
| trade 后端（D） | 0 | 2 | 4 | R2 路 D/E 修复全部在位无回归；开机回执旁路同步阻塞、采购回放吞改量意图 |
| 门禁脚本（E） | 0 | 4 | 4 | 新门禁全部接进聚合链且当前健康，但规则 9 三种退化里两种可绕、两个棘轮有静默假绿通道 |

> **本轮无 P0；总账 30 条 = P1×2 / P2×14 / P3×14。** 两条 P1 都是「上轮修复未闭环」而非新缺陷：A-R3-1（A7 残留）、C-R3-1（C1 假修复）。R2 全部 46 条修复经 §2 矩阵核对**除这两条残留外均在位**，UI 重设计未删任何守卫。

**一句话**：增量代码质量整体健康（工作台下推空集 fail-closed、批量挂接实为只读列表无 E4 绕过、模型旁路鉴权/幂等齐全），问题集中在**修复的「另一半」没修**（保存响应侧、错误源 status）与**新代码复发旧家族**（seq 守卫、静默清空、门禁判据不剥注释）。

---

## 2. R2 修复回归核对矩阵（主审亲验）

> **2026-11-10 补记（R4 交叉复核后，主审已亲验）**：本矩阵有两行判「在位」实为**带洞**，R4 抓出且主审确认：
> - **A1**：`onErrorCaptured` 按 info 分流的逻辑在源码层在位，但 Vue 3.5.40 **生产构建**的 `handleError` 传的是
>   `https://vuejs.org/error-reference/#runtime-${type}`（node_modules `runtime-core.cjs.prod.js:115` 实读），
>   `FATAL_INFO_RE` 只匹配 dev 字符串 ⇒ **生产兜底页整体失效**（R4-A19，P1）。本矩阵只验了源码行为、未验产物层 info 形态。
> - **B1**：前端判据在位，但「终态快照 renew 分支不走实时复查」+「指纹相等短路先于终态判断」⇒ 已成功柜改量重提仍可
>   重复建任务（R4 B-新1，P1）；配套 SQL（9025489b）`@Select` 内嵌 `<foreach>` **缺 `<script>` 包装** ⇒ prep-preview 必 500
>   （R4-D1，P1）。本矩阵只验了「判据存在 + 空集 fail-closed」，未验 SQL 绑定层。
> - **E1**：后端清列在源码层在位，但被前端 `|| undefined` + JSON.stringify 丢键架空 ⇒ 「清除主图」在唯一真实路径
>   100% 复现原缺陷（R4-E5，P1）。本矩阵只验了后端判据。
>
> **结论修正**：§2 的「全部在位」应读作「源码行为在位」；R2 修复共 9 条带洞/被架空，两轮并集见 R4 §1。
> 教训：回归核对必须覆盖**产物层（生产构建）/序列化层/绑定层**，不能止步于源码判据。


| R2 项 | 结论 |
|---|---|
| A1–A6/A8–A18 | **全部在位**（边界按 info 分流、confirmOrSkip、权限门、调拨确认+锁、先落盘再完成、assetLoadFailed、policySaving 锁、feeLoadedMerchantId、分项失败、reloadSafely×4、deviceId 比对、stateSeq、csv 负数豁免、precision=0、元录入+appliedKeyword）。残留：A7 保存响应侧 ⇒ A-R3-1；A12 saveStocktakeLines 一处漏抄 ⇒ A-R3-5 |
| B1 全链（含 §12.6） | **在位**：self-replenish-submit.ts 三层判据（null⇒拦/终态⇒renew 清键+指纹/复查失败 fail-closed）、两页传 lastTaskStatus、DTO+SQL 下推+openapi 一致、855a517f 锚点对齐、8fc6593b 实时复查在位 |
| B2/B3/B4/B5/B7/B8/B9/B10/B11/B12/B14 | **在位**（回放前置、createSeq/listSeq 分离、evidenceUrl 清理、listSeq 三判、afterCommit+单线程+@PreDestroy、切月 bill=null、permUnknown 三页、缺参无重试、上传内层 catch、now-6d、refNo 成功后换键）。B9 同族未修页 ⇒ B-R3-4；B6 旁路 ⇒ B-R3-2；B14 回放不核对明细 ⇒ D-R3-2 |
| C1/C3/C5/C6/C7 | C3/C5/C6/C7 **在位**；**C1 未闭环** ⇒ C-R3-1 |
| D1/D2/D3/D5/D6/D7 | **在位**（补扣清券两分支、clearMerchantId 事务内、clearFailReason 两处、死方法已删、@Size、ACTION_SESSION_REPLAY 独立计数） |
| E1/E2/E3/E4 | **在位**。E1 清列在 releaseSkuImageIfUnused 之前且只对请求出现字段；E4 退役循环在两分支前。**任务所疑「38e9d961 批量挂接绕过 E4」不成立**：`GET /jiangyi/links` 是只读列表（listBoundAll ne RETIRED），该提交仅 openapi 再生成，无批量写端点 |

---

## 3. P1（2 条，均主审逐行亲验）

### A-R3-1 [P1] MerchantSplitsView：A7 修复残留——saveOpsConfig 的 PUT 响应赋值无守卫，仍可写错商户
- **位置**：`clients/admin-vue/src/views/merchants/MerchantSplitsView.vue:1357`（`opsConfig.value = await api.request(...PUT)` 成功赋值无 isCurrent/商户比对）；对照 :1335（loadOpsConfig 已守）、:1351（保存前比对）、:287-291（商户下拉在 savingOpsConfig 期间**不禁用**）。
- **触发**（主审推演，代码路径确定）：选 A 点保存（PUT 在途，URL 已按 A 求值）→ 切商户 B（下拉可用，@change 触发 loadOpsConfig，B 的 GET 先回，opsConfigLoadedMerchantId=B）→ A 的 PUT 响应晚到把表单盖成 A 的配置 → 此时 :1351 比对（B===B）通过 → 再点保存把 A 的拍照盘点/并发上限写进 B。PUT 慢于 GET 时时序天然成立。
- **与 R2-A7 的关系**：R2 只守了 loadOpsConfig 的成功赋值处；saveOpsConfig 的响应赋值是同机制的另一半，漏修。
- **修法**：保存响应赋值前比对请求时商户+seq；或不赋值、成功后改走 loadOpsConfig；保存期间禁用商户下拉亦可兜底。
- **置信度**：高（代码路径）；弱网时序未运行时复现。

### C-R3-1 [P1] R2-C1 宣称已修实际未闭环：401 判据改了 status，但错误源从不带 status
- **位置**：`clients/consumer-mp/src/utils/consumer-api.ts:196-197`（新判据 `status===401 || message.includes('(401)')`）；**:240**（`reject(new Error(localizeApiMessage(body?.message, ...)))` —— 裸 Error，**没有 `.status`**）；对照同文件下载通道 :112-116 用了 `createMpApiError(...,401)`。
- **触发**（与 R2-C1 原故障完全相同）：token 过期上传申诉凭证 → 后端 401 返回中文「请先登录/登录已失效」→ localizeApiMessage 中文直返 → 无 status、无 "(401)" → unauthorized=false → 不刷新不重试直接抛错。仅 body.message 为空走 fallback「上传失败 (401)」时才命中旧串判据。
- **定性**：R2 §12 记录「C1 已修（判据改结构化 status）」——判据侧改了，**错误源侧没改**，修复未闭环。商户端同族（§7#2）是哨兵码+状态判两侧都改的，消费端只改了一半。
- **修法**：:240 改 `createMpApiError(msg, res.statusCode===401 || body?.code===401 ? 401 : res.statusCode)`，并补「401 中文消息触发刷新重试」钉测（现测试若只测判据函数则护不住此退化）。
- **置信度**：高（主审读两侧源码）。

---

## 4. P2（14 条，均主审亲验）

| # | 位置 | 结论 | 修法 |
|---|---|---|---|
| A-R3-2 | `VisionMappingView.vue:1034-1060`（c9c01b7b 新增 loadVendorNames） | await 后 `vendorNameByText.value=map` 无 seq/deviceId 守卫（A13 同族在新代码复发）：切柜 A→B 时 A 的解码响应晚到盖掉 B 的「厂商品名」列——该列是映射复核（结算闸口）的核对依据 | 捕获请求时 deviceId，赋值前比对（照抄 :905 A13 修法） |
| A-R3-3 | `SkuListView.vue:1107-1115` | 挂接列加载 `catch { jiangyiLinkBySku={} }` 无失败标记 ⇒ 网络/500 时整列假报「未挂接」（A11 同族）；watch 翻页全量拉取无 seq，解挂后重载可被在途旧响应短暂复活 | 失败显「—/加载失败」+seq |
| B-R3-1 | `warehouse-stocktake.vue:164-165/:210/:672-673`（重设计引入） | **盲盘泄账面**：头部「差异 N 行」、原因 picker 与备注框按 `hasDiff`（实盘−账面）反应式显隐，而 BLIND 进行中账面只遮数字（:410）；后端 toLineDto 恒传真实 bookQty。改输入框看 picker 出现与否即可二分猜出账面；与同文件 :639-648「盲盘不泄露方向」刻意守卫自相矛盾 | 盲盘进行中差异行数/picker/备注显隐不得读 hasDiff（按「已录入」口径或终态后才给） |
| B-R3-2 | `jiangyi-capture.vue:118/:137-178` | **B6 修复不完整**：photosForBarCode 只在 loadState（显式查询/扫码）比对；canSubmit 只查照片满槽。手改条码输入框（无 watch）**不点查询直接提交** ⇒ A 商品照片经 uploadJiangyiCapturePhoto(barCode.trim()) 提交到 B 条码——正是 B6 要防的识别学错路径 | submit/take 前校验 photosForBarCode===barCode.trim()，不一致 resetPhotos；或 watch barCode 即重置 |
| B-R3-3 | `device-stocktake.vue:64-72/:88-92/:509-515`（重设计引入） | 非法输入（小数/负数）diffOf 归 null ⇒ pendingCount=0 ⇒ 吸底条禁用且文案「与台账一致」；注释宣称「非法值交提交校验点名」（:272-296 校验在），但唯一提交入口被禁用 ⇒ 校验永不可达。无资金风险（拦住了提交），口径误导 | 非法非空行计入「待处理」或吸底条文案区分「有非法输入」 |
| B-R3-4 | `warehouse-stocktake.vue:460-466/:418`、`purchase.vue:167-175`、`wallet.vue:43-49` | **B9 同族未收口**（基线已有）：refreshMe 失败⇒去权缓存⇒「当前账号无盘点权限」/采购页永显「暂无可采购商品」无错误无重试/钱包无权限空态——网络错误被报成权限收回 | 照 settlements/devices/bills 的 permUnknown 形态补三页 |
| B-R3-5 | `warehouse-stocktake.vue:795-831` | **admin A6 同族 mp 侧未修**（基线已有，主审升级 P3→P2）：doComplete/doCompleteAndAdjust 只 POST 不带行数据；行内改 countedInput 未点「保存」直接过账 ⇒ 改过已保存行按**旧值调库存**（静默错账），首次录入行 400。确认弹窗只问「确认过账」不提示未保存修改 | 过账前先 persist 未保存行（照 admin useWarehouseStocktakes A6 修法） |
| C-R3-2 | `marketing-ad-banner.vue:70-78` | C5 改无条件重拉但 load 无 seq：deviceId null→X 两次并发，ALL 响应晚归覆盖 SPECIFIC 且不再重拉 ⇒ C5 修复在弱网下失效（device-ad-banner 同形态存量） | load 捕获 seq/deviceId，赋值前比对 |
| C-R3-3 | `recharge.ts:35-39/:202-232` | forgetPendingRecharge 无条件 clearPendingRechargeOrder，不校验键归属：首页 resume 轮询 A（12s 窗）期间用户发起 B（savePendingRechargeOrder(B) 覆盖单键）→ A 轮询终态调 forget(A) 把 B 的 pending 清掉；B 若「支付成功但确认超时」失去自动恢复兜底（资金不受损，后端权威） | `if (peekPendingRechargeOrder()===orderId)` 再清 |
| D-R3-1 | `jiangyi-gateway DeviceInfoController.java:181-194`、`TradeInternalClient.java:41-42` | receiveCurrentTxtUrl 旁路确认是**同步阻塞**（connect 10s/read 30s），与注释「fire-and-forget 不阻断开机」不符；trade 侧端点还内联 autoRebuildQuietly。trade 慢/宕机时每台 806 固件柜开机回执最长挂 ~40s 才拿 666（原端点立即返回）——B7 同族「catch 挡异常挡不住时延」 | 投递网关侧异步执行器或缩短该调用超时 |
| D-R3-2 | `ProcurementService.java:186-192`、`PurchaseOrderMapper.java:26-33` | **B14 回放不核对明细/意图**：按仓库+refNo 取最新一条直接返回，不比较 lines/金额。前端成功后才换键 ⇒「提交超时→改数量→同键重提」拿回旧数量单且界面显示成功——B1 修掉的「假成功」形态（单真实存在、收货人工逐单，风险有界）。对照提现回放有归属+金额校验 | 回放比对明细指纹，不一致 409 引导换键 |
| E-R3-1 | `check-replenishment-checkin-contract.mjs:583/:588` | 规则 9「服务不查/不回填」两条判据是**全文件 substring**，未钉在 prepPreview 方法体：查询移出 prepPreview 但同文件他处保留字面量、或回填留在未用局部量而给 DTO 传 null，均绿。现状健康（锚点实在方法内），机制可绕 | 判据钉进 prepPreview 方法体切片 |
| E-R3-2 | 同文件 :593/:614/:615/:621 | 规则 9 前端判据不剥注释（Java 侧却剥了）：删真实调用留注释即绿；`shouldBlockPrepResubmit(` 只查出现——**函数定义自身即满足**，包装函数停止调它门禁不红（P0-3 判据可被空心化）；终态数组只钉定义不钉消费 | 剥注释+钉调用点（排除定义行）+钉消费 |
| E-R3-3 | `check-mp-pull-refresh.mjs:43-52/:106` | pages.json 存在但结构漂移（pages/subPackages 键变化）⇒ declared=0 ⇒「OK：0 个」**静默假绿**，无 declared==0 防锚；另只收页面级 style，声明移入 globalStyle 整批漏管 | declared==0 即红（当前实测 30） |
| E-R3-4 | `check-mp-touch-target-ratchet.mjs:61/:69/:530-538` | `Number(env||默认)`：设 `TOUCH_UNVERIFIABLE_BASELINE=abc` ⇒ NaN ⇒ 大于/小于比较均 false ⇒ **静默绿**；设大数自由抬顶。豁免通道无硬约束（本轮恰改这两行未顺手修） | NaN 即红+env 覆盖只允许下调 |

---

## 5. P3（14 条）

| # | 位置 | 一句话（主审亲验，除注明外） |
|---|---|---|
| A-R3-4 | `DashboardView.vue:741-745/:792` | 无 /exceptions 权限账号 ex=null（设计如此）⇒ exceptionCountFailed=!ex 恒真，把权限缺失误报为「加载失败」（stats 403 同理） |
| A-R3-5 | `useWarehouseStocktakes.ts:137-145` | saveStocktakeLines 的刷新仍在写 try 内无独立 catch（A12 漏抄一处）：刷新失败先「已保存」再补一条错误提示 |
| A-R3-6 | `OpsApprovalInbox.vue:269` | 待办跳采购单带无人消费的 orderId 参（基线即如此，非本轮回归），落在列表页定位不到单据 |
| B-R3-6 | `warehouse-stocktake.vue:704` | saveLine 只判 `=== ''`；`' '` ⇒ Number(' ')=0 过 buildUpdateLineBody（只查整数≥0）⇒ 按 0 提交；同页 lineDiff 已把空白归 null，口径不一致（number 键盘可达性低，置信中） |
| B-R3-7 | `check-replenishment-checkin-contract.mjs:594-614` | 规则 9 前端锚点只钉 prep-generate.vue；prep-list.vue 同判据（已核在位）无锚，单独回退 prep-list 门禁仍绿 |
| C-R3-4 | `HomeCartBar.vue:90/:107/:258-284`、`index.vue:277/:289` | 删「关门结算」假按钮后死引用残留：closeDoor emit/closingDoor prop/死 CSS 无模板消费，父页仍传参（无害但误导） |
| C-R3-5 | `popup-seen.ts` ensureMigrated | 旧键 >50 个时迁移写回不套上限，直到下次 markSeen 才淘汰（有界性最终成立，仅延迟；测试未覆盖该分支） |
| D-R3-3 | `JiangyiModelSyncService.java:465-481/:434-457` | confirmModelByTxtUrl 匹配 SENT 后调 confirmModel 重查 findLatestSent：窗口内被 watchdog 标 FAILED 且该模型从未 CONFIRMED ⇒ 走「未知模型撤销复核+409」——旁路注释明言不想承担的后果。方向 fail-safe（多一次人工复核，不错钱），窗口极小（置信中） |
| D-R3-4 | `DataConsistencyService.java:1090-1103` | DEVICE_LEDGER 排除无正流水批次（含只剩出库流水的批次）：注释称 INVENTORY_MISMATCH 兜底，但该检查只比聚合 vs 批次合计，**批次内部流水漂移**两头一致时两边都查不出。误报消除是拍板收益，漏报面建议活文档显式登记 |
| D-R3-5 | `DeviceInfoMapper.java:107-123` vs `DeviceAssetService.normalizeLifecycle:487-493` | 下推谓词 `IS NULL OR ='DEPLOYED'` 比内存归一（空串/空白/非法值也归 DEPLOYED）窄；历史行若有 ''/非法值则待办/计数与统计分叉。**数据依赖，未查库**（置信中，建议只读 SQL 抽查一次） |
| D-R3-6 | `MerchantDevicePortalService.java:257-281` vs `InventoryLotService.java:447-449` | 盘点回执 lotLedger 标志一次快照 vs 调整逐行重判：无批次账柜第一行盘盈建首个 lot 后，回执 before/after 与实记流水 delta 可能不符（仅混合账本边缘柜；调整行为本身旧逻辑未变，置信低中） |
| E-R3-5 | `check-ui-barehex-ratchet.mjs:279-294/:229` | 实测低于基线只提示不红（与 touch 棘轮双向红不一致）；豁免 `includes('theme.css')` 子串匹配无结构指纹（预存） |
| E-R3-6 | `check-approval-action-path-nav.mjs:249/:256-290` | 规则 C/E 只覆盖字面量直调与旧入口推导；数据驱动卡片 path 字段里的**新**未登记路径漏管，原缺陷家族仍可复发（置信中高） |
| E-R3-7 | `check-workbench-scope-pushdown.mjs:64-72` | 黑名单钉仓储变量名，改名可绕；REQUIRED_CALLS 只钉名出现不钉结果消费（正向锚点已覆盖主回归形态，残余风险低） |
| E-R3-8 | `check-replenishment-checkin-contract.mjs` 尾部 | export maskJavaComments 但无 main 守卫，import 即全量执行（现无 importer，潜在） |

---

## 6. 系统性观察

1. **「修复只修一半」是本轮两条 P1 的共同根因**：A7 守了加载侧没守保存响应侧；C1 改了判据侧没改错误源侧。与 R2 §6.2「修复时必须同文件/同链全扫」建议一致——**该建议尚未写进 CODE_FIX_CHECKLIST**，本轮再次验证其必要。
2. **新代码复发旧家族**：loadVendorNames（A13 同族）、挂接列静默清空（A11 同族）、marketing-ad-banner 无 seq（C5 修复留尾巴）。seq/失败态守卫在 admin 有 useCrudTable/loadSeq 统一抽象，**散落的独立加载函数是稳定盲区**。
3. **UI 重设计本身干净**：6f7c3ba8 未删任何 R2 守卫（B4/B5/B9/B12 等逐条核过），但引入 B-R3-1（盲盘泄账面）与 B-R3-3（非法输入文案）两条新缺陷——重设计的反应式显隐逻辑读了不该读的真值。
4. **门禁「现状健康 ≠ 机制可靠」**：规则 9 三种退化（DTO 删字段/服务不查/页面不传参）只有第一种真钉住；两个棘轮的 env/结构漂移通道可静默假绿。当前全部实测绿（路 E 只读实跑 5 门禁），但负向绕过路径存在。

---

## 7. 已核对无问题（下轮勿复审）

- **R2 全部 46 条修复**：§2 矩阵逐条在位（除 A7/C1 两条残留已单列）。
- **工作台下推**（2f72ce24/0f5c6d0b/93e98aea）：全部新 mapper 空集⇒空/0 fail-closed、null⇒不限；LIMIT 在过滤后；超时会话列表与 count 谓词逐字一致；isRestockingNow=IN_PROGRESS+check_in_at 非空 SQL 同源；在途计数 COUNT(DISTINCT COALESCE) 消除 500 封顶。
- **updateMainImg**（e636038a）：ops:sku:edit+审计+CREATED 闸+中文 409；body 键与将邑文档一致；前端确认在 try 内+syncingImage 锁。
- **model-confirmed-by-txt**（a8ca7722）：/internal/** fail-closed 鉴权；deviceId 取自设备 JWT 不可伪造；重复回执 no-op 幂等；txtUrlMatches 带路径边界防误命中；不命中不撤复核（除 D-R3-3 竞态）。
- **挂接列/解挂**（1fdace14）：GET /jiangyi/links 只读；解挂走既有 RETIRED 留痕路径；E4 不变式无绕过；权限门与后端一致。
- **优惠减修复**（39f6044f）：两端列表 coupon+member+activity 三档与后端恒等式一致（OrderViewAssembler original=total+三档），无重复减/漏减，两端口径一致。
- **门态修复**（4d8de053）：前端 OPENING/SHOPPING 判与后端 resolveDoorSession 双闸一致；消费端删假按钮未断真结算链（物理关门+轮询；mock 链 live-cart-sheet→closeDoorDemo 完整）。
- **popup-seen/announcement-read/format.ts**：有界淘汰/迁移/未知态文案均正确（C-R3-5 仅迁移延迟超限）。
- **门禁接线**：3 新脚本+负向验证全进 check:audit-gates 聚合链（package.json:40）且 ci.yml:674 实调；基线冻结干净（barehex 1078/71/0/164==冻结值、touch 278==278，无夹带）；approval-nav 四重防腐锚点可红。
- **盘点回执正常路径**（a876861a）：有账柜 before/after 与 stocktakeAdjust 内部 current 同口径（边缘见 D-R3-6）。

---

## 8. 建议修复顺序

1. **立刻（P1）**：C-R3-1（错误源带 status+钉测，回炉 R2-C1）→ A-R3-1（保存响应守卫/保存期禁切商户）。
2. **数据/完整性面（P2）**：B-R3-1（盲盘泄账面）、B-R3-2（串图旁路）、B-R3-5（mp 过账先落盘）、D-R3-2（采购回放核对明细）、D-R3-1（开机回执异步化）。
3. **同族收口（P2）**：B-R3-4（B9 三页）、A-R3-2/A-R3-3/C-R3-2（seq/失败态）、B-R3-3（文案）、C-R3-3（键归属校验）。
4. **门禁加固（P2/P3）**：E-R3-1/2（规则 9 钉方法体+剥注释+钉调用点）、E-R3-3（declared==0 防锚）、E-R3-4（NaN 即红）、B-R3-7（prep-list 锚点）。
5. **流程建议**：把 R2 §6.2「修复时同文件/同链全扫」正式写进 CODE_FIX_CHECKLIST——本轮两条 P1 均为此前科。

---

## 9. R4 交叉复核后的终裁（2026-11-10，主审亲验）

| 项 | 终裁 |
|---|---|
| **D-R3-5**（lifecycle 下推谓词窄于内存归一） | **撤账**：只读 SQL 实测本地 dev 库 `device_info.lifecycle_status` 仅 `DEPLOYED(3)/INBOUND(2)`；V127 列 `NOT NULL DEFAULT 'DEPLOYED'` 且写侧全是常量 ⇒ 归一分支（空串/非法值）不可达。R4 判「等价」成立（UAT/生产库未抽查，留作部署前探针） |
| **D-R3-6**（盘点回执 lotLedger 快照 vs 逐行重判） | 保留为**观察项不立案**：两边均静态推演，影响仅限「无批次账柜 + 首行盘盈」边缘柜，调整行为本身未变 |
| **B-新1**（R4） | **成立，P1**：`shouldBlockPrepResubmit` 指纹相等短路先于终态判断（:127），`renew_after_ended_task` 不走实时复查（:156），prep-generate 部分失败后不 reload 预览，后端对带键请求无「同设备活动任务」闸 ⇒ 已有历史终态任务的柜改量重提会重复建任务+重复扣分仓库存（与 P0-3 同伤害） |
| **B-新2**（R4，同量+任务已终态⇒假成功） | **降级 P3/并入 B-新1 设计讨论**：回放按 clientRequestNo，同页同量重提命中的是本页刚建的任务（V401 有意契约）；只有「成功后任务被他人完结再同量重提」的窄窗成立。**不可简单把终态判断提到指纹相等之前**——那会让「任务完成后同量重提」每次都建新任务，与 V401 冲突，须先拍板语义 |
| **D2 并发子声明**（R4「并发同键可建两张」） | 成立性保留、**UI 不可达**：purchase.vue:443 有 `submitting` 锁，双击不会并发；DB partial unique 仍建议加（防脚本/弱网双发），但 P2 的主依据应落在「回放不核内容」 |

---

## 10. 验证记录与未验边界



- **主审亲验**：30 条全部读当前 HEAD 源码复核；P1/P2 逐行读代码本体（含前后端两侧、门禁脚本判据实现）。无撤账；B-R3-5 由子代理 P3 升级 P2（与 admin A6 同伤害形态：按旧值调库存）。
- **只读实跑**：路 E 子代理只读运行 5 个门禁脚本全绿（未写盘）；本轮未跑构建/单测/类型检查（只读审计，未改源码）。
- **未验边界（诚实标注）**：A-R3-1/C-R3-2/C-R3-3 的竞态后果为静态推演（需弱网复现）；D-R3-5 未查库验证历史行是否存在 ''/非法 lifecycle 值；D-R3-3/D-R3-6 窗口极小/边缘柜型，置信中低；B-R3-1 的二分猜账面为逻辑推演（代码路径确定，未真机操作）。真机点击链未复现——与 R2 §10 同口径。

---

*本报告为静态源码审计 + 主审逐条亲验口径；未做真机运行时探测与渗透。是否入库由用户决定（工作树另有 peer 在制 `scripts/probe-zhima-payafteruse.py`，提交时须显式路径分开）。*