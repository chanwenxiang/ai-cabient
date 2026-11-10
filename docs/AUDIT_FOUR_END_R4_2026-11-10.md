# 四端代码审核报告 R4（2026-11-10 深夜，五面全量·R2 修复确认 + 第二轮审查）

> **基线**：审计执行于 `b02e9041`；报告落盘时 HEAD 已推进至 `893bb2ca`（仅 +1 个测试修复提交），**本报告全部发现所涉文件经 `git diff b02e9041..893bb2ca` 核实未被再改 ⇒ 全部在当前 HEAD 成立**。
>
> **与 R3 的关系**：`AUDIT_FOUR_END_R3_2026-11-10.md` 是并发会话在同一 HEAD（893bb2ca）上完成的**同轮独立审查**（R3：30 条 = P1×2/P2×14/P3×14）。R4 与 R3 **互补而非替代**：两轮发现集交集 9 处（§4 归并表），并集才是完整账。**R3 的回归矩阵判「在位」的 4 处实为带洞**（A1/B1/E1/B1-SQL），由 R4 抓出；R4 未覆盖的 A-R3-1（A7 保存响应侧）等由 R3 抓出。两份都应入账。
>
> **双重任务**：①按用户指令**确认 R2 修复批**（48 条逐条源码确认）；②确认后的基线上再跑一轮五面审查。方法：修复确认=主审逐条读现码；新审查=5 路并行只读子代理（4 并发+1 后台）+ 主审对全部发现逐条亲验（5 条关键 P1/P2 做机制级实证）。R4 无撤账。
>
> **范围**：同 R2/R3 五面。未审新代码同 R3 所列（UI 重设计 6f7c3ba8、将邑挂接/改主图/txt 旁路、待办下推、盘点回执批次口径、拆页路由、订单优惠列、门态修复、新门禁三件）。

---

## 1. R2 修复确认总结论（R4 侧）

**48/48 条改动全部在位**（主审逐条读现码）。三处「有意偏离」核实正确（E1 判据收窄、B14 复用 refNo、B7 afterCommit+daemon——但 E1 的偏离被前端架空，见 E5）。

**两轮并集后，R2 修复共暴露 9 条「修复自身带洞/被架空」**：

| # | R2 修复 | 洞 | 抓出轮次 | 级 |
|---|---|---|---|---|
| 1 | A1 错误边界按 info 分流 | **生产构建 info=URL，FATAL_INFO_RE 永不命中** ⇒ 渲染错误兜底页整体失效+英文 toast 反复堆叠（dev/单测测不出） | **R4** A19 | P1 |
| 2 | B1 改量重提按任务状态分流 | **指纹相等短路先于终态判断 + renew 分支不实时复查** ⇒ P0-3（重复建任务+重复扣分仓库存）复发 | **R4** B-新1（+B-新2 同族） | P1 |
| 3 | B1 配套 SQL（9025489b） | **`@Select` 内嵌 `<foreach>` 缺 `<script>` 包装 ⇒ prep-preview 必 500**，改量重提链路整体自失效（mock 单测盖住） | **R4** D1 | P1 |
| 4 | E1 清主图显式清列 | **被前端 `|| undefined` + JSON.stringify 丢键架空**：清空请求里没有 imageUrl 键 ⇒ 清列永不可达、release 照删文件——原缺陷在唯一真实路径 100% 复现 | **R4** E5 | P1 |
| 5 | C1 上传 401 结构化判据 | 判据改了、构造侧 4 条 reject 全是裸 Error ⇒ 自愈仍死代码 | R3=C-R3-1（R4 独立复得） | P1 |
| 6 | A7 运营配置加载守卫 | 只守了加载侧，**saveOpsConfig 的 PUT 响应赋值无守卫**仍可写错商户 | R3=A-R3-1 | P1 |
| 7 | B9 permUnknown 三页 | 判据 `!me.value` 可被**首页 hydrateFromCache 预先 seed 的去权缓存**击穿 ⇒ 主路径仍误报无权限；同形态 ≥8 页未收口 | **R4** B-新3（根因）+ R3=B-R3-4（同族页） | P2 |
| 8 | B14 采购 refNo 幂等 | 回放不核内容/状态 + 键驻留 + 并发无 DB 兜底 ⇒ 第二笔采购假成功/改量被吞 | R4=D2（补强并发面）+ R3=D-R3-2 | P2 |
| 9 | C5 广告位无条件重拉 | 无 seq ⇒ 弱网乱序 ALL 覆盖 SPECIFIC、失败清空 | R3=C-R3-2（R4 独立复得 C4） | P3 |

**§9 门禁候选落地**：规则 9 ✅（锚点缺 prep-list=R3-B-R3-7）、C7 ✅；**①裸 await 门禁、②CODE_FIX_CHECKLIST 同族条目、③updateById 门禁、⑤seed→registry 双向规则未实施**。

---

## 2. R4 独有发现（22 条 = P1×4 / P2×7 / P3×11，全部主审亲验、与 R3 无重复）

### 3.1 P1（4 条）

#### A19 [P1] PageErrorBoundary 渲染兜底在生产构建整体失效
- **位置**：`clients/admin-vue/src/components/PageErrorBoundary.vue:38-45`。
- **证据**（产物层双实锤）：`static/admin/assets/ui-vendor-*.js` 内存在生产版 `` `https://vuejs.org/error-reference/#runtime-${n}` `` 模板；同批 `AdminLayout-*.js` 内正是该正则 + onErrorCaptured 回调。Vue 3 生产构建 `handleError` 传 URL 而非可读 info（`ErrorTypeStrings` 仅 dev）⇒ 锚定正则永不命中 ⇒ 生产环境 setup/渲染期抛错不切兜底页：子树空白、组件仍挂载、每次重渲染再抛再弹英文 toast。**dev/单测/类型检查全绿测不出**。附带：正则中 `render`/`component render function` 两分支在任何构建下都是死分支（真实枚举只有 `render function`）。
- **修法**：`/^(setup function|render function)$/.test(info) || /#runtime-[01]$/.test(info)`（prod：setup=0、render=1），补生产形态用例。
- **置信度**：高。

#### B-新1 [P1] 备货改量重提：终态快照直通 renew 不复查 ⇒ P0-3 复发（重复建任务+重复扣分仓库存）
- **位置**：`self-replenish-submit.ts:126-128`（`prevFingerprint === fingerprint → 'proceed'` **先于**终态判断）+ `:155-157`（`first !== 'block_changed_with_active_task'` 即返回——`renew_after_ended_task` **不走实时复查**）；`prep-generate.vue` 部分失败后不 reload 预览（快照停在 onLoad）；后端 `selfInitiateTask` 无「同设备活动任务」闸（新键即真建任务+出库）。
- **触发**：部分失败后调低某台**已成功柜**的数量再提交——该柜快照 `lastTaskStatus` 多半是**历史**任务的 COMPLETED ⇒ renew ⇒ 不复查 ⇒ 换新键 ⇒ 旧 PENDING 之外再建一张、库存扣两次。prep-list 全成功路径（pendingGroups 不刷新）同理。
- **刻意设计排查**：§12.6 论证过 null 快照必须拦，漏了「旧任务终态快照」这一更常见形态；测试把「快照本就终态⇒不复查」钉成期望行为（设计盲区）。与已登记残余窗口（预览后取消=误拦）方向相反。
- **修法**：`renew_after_ended_task` 且 `prevFingerprint` 存在时同样实时复查（失败 fail-closed 改拦）；或部分失败/全成功后补 `loadPreview()` 刷新快照。
- **置信度**：高。定级说明：伤害量级=原 P0-3，触发需改量+该柜有历史终态任务，记 P1（可议 P0）。

#### D1 [P1] 备货预览 `@Select` 缺 `<script>` ⇒ prep-preview 必 500，改量重提链路整体自失效
- **位置**：`ReplenishmentTaskMapper.java:54-64`（`selectLatestStatusByDeviceIds`，9025489b 引入）→ `MerchantReplenishmentService.prepPreview` → `GET /merchant/replenishment/prep-preview`。
- **证据**：文本块以 `SELECT` 开头、内含 `IN <foreach ...>#{d}</foreach>` ⇒ RawSqlSource 不解析动态标签 ⇒ 执行期 `BindingException: Parameter 'd' not found`。**同批对照**：`WarehouseInTransitMapper.java:91` 同批注解 SQL 带 `<script>`（会写，此处漏抄）。**测试不背书**：`MerchantPrepPreviewTest` 对该 mapper 是 `@Mock`；规则 9 只做静态文本判据。
- **影响**：设备范围非空的商户调 prep-preview 即 500 ⇒ 备货单页/手动生成页/提交实时复查（fail-closed 恒拦）全部不可用——§12.6 链路自失效，商户备货主流程阻断。无资金损失（写腿不经此查询）。
- **修法**：补 `<script>`；给「mapper 新增注解 SQL」补绑定层验证（静态门禁或轻量 IT）。
- **置信度**：高。

#### E5 [P1] E1 修复被前端序列化架空——「清除主图」在其唯一真实路径 100% 复现
- **位置三段合链**（逐段亲读）：①`SkuListView.vue:1445` `imageUrl: form.imageUrl.trim() || undefined`（清除按钮 ：1184 置 `''` ⇒ undefined；该行自 f030f09f 起如此，E1 修复只改后端）；②`JSON.stringify` 丢弃 undefined 键 ⇒ 请求无 imageUrl 键；③后端 `clearExplicitlyBlankColumns` 判据 `request.imageUrl() != null` ⇒ 不清列（DB 留旧 URL），而 release 分支 `oldImageUrl != null && !oldImageUrl.equals(null)` 恒真 ⇒ 删附件行+物理删对象。修复注释自证假设「前端清开发 ''」为假。
- **连带**：`description/brand/category` UI 清空同一 `|| undefined` 形态 ⇒ E1 的「陈旧值残留」半边也原样保留。**E2 不受影响**（writeNullableColumns 无条件写内存值）、**D1/D2/D3 清列不受影响**（状态机触发，非客户端判据）。
- **修法**：前端四字段去 `|| undefined` 显式携空串（后端无需再改）；同批补「构造侧能产生判据输入」的正负用例。
- **置信度**：高。

### 3.2 P2（7 条）

| # | 位置 | 结论 | 修法 |
|---|---|---|---|
| B-新2 | `self-replenish-submit.ts:126-132` | 同量+最近任务 COMPLETED ⇒ 指纹相等短路 proceed → 后端回放旧 COMPLETED 任务（V401 有意）⇒「已生成」假成功实际没建。终态时「同量」≠「同一意图」（并入 B-新1 修法：终态判断提前） | 终态判断提到指纹相等之前 |
| B-新3 | `useMerchantMe.ts:11`（模块级单例）+ `useHomeWorkbench.ts:242`（hydrate 先 seed） | **B9 判据根因被击穿**：首页冷启动先把 `permissions:[]` 缓存 seed 进共享 meRef ⇒ 三页 `permUnknown=!me.value` 为 false ⇒ 走进权限判据 ⇒ 照旧误报。R3=B-R3-4 只列了同族三页（warehouse-stocktake/purchase/wallet），未触及 seed 击穿根因；两份合并=修法一处（meFromServer 权威标志）收口 11 页 | useMerchantMe 加模块级 `meFromServer` 标志，三页+8 同族页统一改判 |
| C2 | `index.vue:2274-2284`+`notify.ts:175-191`+`SessionSettleService:76-86` | 结算 toast 不读 `order.status`：余额不足/支付分失败单落 **PENDING 待补缴**，客户端拿到的 totalAmountCents 是应缴额 ⇒ toast 报「已扣款 ¥X」，1.2s 后 result 页「待支付」打脸——R1-P1-6 警告的反向形态（R1-P1-6 只修了「失败当 0」） | finishSession 读 status，PENDING/PROCESSING 改报「订单已生成，请完成支付」 |
| D2 | `purchase.vue:187`+`ProcurementService.java:185-191`+refNo 无唯一约束 | **B14 次生残余**（⊃R3-D-R3-2 的「回放不核明细」）：超时后键永久驻留 ⇒ 同页第二笔**不同内容**采购被静默回放成旧单；改量被吞；**并发同键先查后插无 DB 兜底可建两张**（对照 withdraw 同族两道保险皆缺） | 回放比对内容指纹+终态 409；refNo 加 `MP-` 前缀 partial unique；前端业务失败即清键 |
| A20 | `SkuListView.vue:585/:1108`+`SkuJiangyiLinkDialog.vue:324/:402` | 新功能：挂接弹窗内挂接/解挂成功后不 emit ⇒ 列表「将邑挂接」列与真实状态相反（弹窗解挂后行内仍挂「解挂」入口；行内路径自己会刷 map，两路径不对称） | 弹窗加 `@changed` emit，父页监听后 `loadJiangyiLinkMap()` |
| A21 | `StockHealthView.vue:308-322` | A13 同族（新代码复发）：fetchPage 附带 4 个 KPI 与 `planDeviceIds` 无请求时点守卫 ⇒ 快速切筛选后「一键补货规划（N 台）」带旧设备集（跳转可人工改，伤害有界） | 照 A13：捕获请求时筛选参数，await 后比对才写旁路 ref |
| A22 | `MachinePayeeBillsView.vue:76-84/:307-335` | A7 同族（R2 修复同文件漏网）：loadConfig 无 seq、Enter 可并发查询 ⇒ 晚到响应覆盖表单，保存把 A 柜分成/收款人写成 B 的意图值（表单 disabled deviceId 显示真实归属，降 P2） | loadConfig 捕 seq 判新旧；Enter 在途先拒 |

### 3.3 P3（11 条）

| # | 一句话 |
|---|---|
| A24 | A6 前置落库副作用：persistStocktakeLines 无条件回写弹窗内全部非空实盘（开弹窗快照）⇒ 双人/双端并发同一盘点单覆盖对方改动（只 PUT 与快照不同的行即可） |
| A25 | 调拨 saving 锁未接行按钮（在途可连点，第二笔被后端 409 拒，无害） |
| A26 | SystemConfigView 下发将邑云 catch 内 toast 后 rethrow ⇒ 错误边界二次弹同文案（双弹；A1 家族扫描命中点） |
| A27 | 五页 CSV 金额列带 ¥（BalanceRefund/FundBill/MarketingRoi/Coupons/UserAnalysis）⇒ 负数导出仍是文本不能求和——A15 豁免未达成原始诉求 |
| A29 | 识别入驻新文案含 APPLIED/CABINET_REVIEWING/verified 裸枚举（违反界面中文约定） |
| C3 | 4d8de053 假按钮只删横幅：**LiveCartSheet 同款仍在且文案「关门后按此结算」与 DC-27 后端语义相反；mock 空购物车零元结案出口被删空**（无硬件演示会话卡 SHOPPING 到超时清扫）。死代码部分=R3-C-R3-4 |
| E6 | session-failed-hold-clear 补注册后仍不在 StaleMonitor 扫描（KEYS 不含三个 Spring 常驻任务）——E3 影响②未随修复闭环（先例 refund-retry/settlement-bill 同缺口） |
| B-新6 | 仓库盘点换单瞬间旧明细/照片在新单头部下闪现（seq 防错配持久，仅展示窗口；loadDetail 开头三值清空即可） |
| B-新7 | devices.vue `.tool-item::after` 双规则同伪元素合并 ⇒ 主操作下划线画到条目上方+热区扩量失效（同文件 `.nav-link` 注释自证此坑；静态推断，真机未验） |
| D3 | 2f72ce24 全局范围（deviceIds=null）争议待办走无 JOIN 回退 ⇒ 行丢设备标签、跳转 query 缺 deviceId（域受限账号不受影响） |
| D4 | 提现回放不看状态：终态单窄窗内回放假成功——B2 修复前即有的既有形态，`PayoutGate.done` 无资金动作，记残余观察待拍板 |

---

## 4. 与 R3 的归并表（两轮并集 = 完整账）

| R4 发现 | R3 对应 | 关系 |
|---|---|---|
| C1（上传 401 死修复） | C-R3-1 | **同一条**，两轮独立复得 |
| C4（广告位无 seq） | C-R3-2 | 同一条 |
| A23（保存实盘刷新未 Safely） | A-R3-5 | 同一条 |
| A28（厂商品名解码无 seq） | A-R3-2 | 同一条 |
| B-新4（规则 9 未锚 prep-list） | B-R3-7 | 同一条 |
| B-新8（purchase 弱网空态） | B-R3-4 之一 | R3 同族清单已含 |
| D2（采购回放不核内容） | D-R3-2 | **R4 补强**：并发无 DB 兜底 + partial unique 修法 |
| C3（假按钮/零元出口） | C-R3-4 | R3 只报死代码；**R4 报核心**（按钮漏删+文案反语义+零元结案出口丢失） |
| B-新9（globalStyle 盲区） | E-R3-3 | 部分重叠（R3 另报 declared==0 防锚） |

**R3 独有、R4 未覆盖（同样有效，修复时以 R3 为准）**：A-R3-1（saveOpsConfig PUT 响应侧）、B-R3-1（盲盘泄账面）、B-R3-2（采集串图不点查询旁路）、B-R3-3（盲盘非法输入文案）、B-R3-5（mp 盘点过账先落盘，P2）、C-R3-3（forgetPendingRecharge 键归属）、D-R3-1（txt 旁路同步阻塞 40s）、E-R3-1/2/4/5/6/7/8（门禁判据绕过通道）等。

**两份回归矩阵的分歧（需一次取证终裁）**：
1. **D-R3-5**（lifecycle 下推谓词窄于内存归一）：R4 路 4 判「等价——V127 列 NOT NULL DEFAULT 'DEPLOYED' 且写侧全大写常量，不存在空白/非法值」；R3 判「窄于归一、未查库」。**终裁动作：一次只读 SQL 抽查 `lifecycle_status` 的 distinct 值即可定**。
2. **D-R3-6**（盘点回执 lotLedger 一次快照 vs 调整逐行重判）：R4 路 4 判「before/after 与调整内部 current 同源（sumSellableQuantity）」；R3 判「混合账本边缘柜可能不符」。均为静态推演，影响仅限无批次账+首行盘盈的边缘柜，修复优先级低，登记不立案。

---

## 5. R2 修复 48 条逐条回归确认（R4 侧明细，与 R3 §2 矩阵互补）

- **路 A**：A2-A18 中 17 条在位（A6 副作用=A24、A12 漏网=A23/R3-A-R3-5、A13 同族=A21/A28、A15 残留=A27）；**A1 在位但生产失效（A19——R3 矩阵判「在位」未及此层）**；A7 在位但保存响应侧洞由 R3 抓出（A-R3-1）。
- **路 B**：B3/B4/B5/B6/B7/B8/B10/B11/B12 在位；B14 主体在位（残余=D2）；B1 后端在位+前端判据洞=B-新1/B-新2、配套 SQL 硬缺陷=D1；B9 判据根因=B-新3。
- **路 C**：C3/C5/C6/C7 在位（C5 无 seq=C4/R3-C-R3-2）；**C1 修复无效（=R3-C-R3-1）**。
- **路 D**：B1 后端（409 锚逐字对齐、COMPLETED 回放=有意）/B7/D1/D2/D3/D7 在位（D1 清列条件核实正确、D7 独立计数器不误伤）；B2 修法在位（回放不看状态=D4 既有）；D5/D6/E2/E3/E4 快速核全过；**E1 被架空（E5——R3 矩阵判「在位」未及前端序列化层）**。
- **未审新代码**（UI 重设计六页/将邑四链/待办下推/盘点回执/拆页路由/优惠列/门态/新门禁三件）：R4 与 R3 结论一致——**无 P0/P1**；问题集中在挂接状态列不刷新（A20）、盲盘泄账面（R3-B-R3-1）、txt 旁路阻塞（R3-D-R3-1）等 P2/P3；两轮对工作台下推口径逐项比对一致（唯 lifecycle 谓词与盘点回执边缘两处分歧待取证，见 §4）。

---

## 6. 系统性缺口（两轮合并观察）

1. **「判据类修复」没有构造侧正负用例**：C1（判断侧改了、构造侧不产生输入）、A19（判据只在 dev info 字符串上验证）、B-新1（纯函数用例把盲区钉成期望行为）、E5（后端判据假设了前端行为没验证）。**修法约定：改判据的修复必须同时有一条「真实输入能命中判据」的正向用例**——C1/E5 若有此用例当场就红。
2. **「修复只改单侧」**：E5（后端判据 vs 前端序列化）、B-新1（后端状态闸 vs 前端快照生命周期）、D2（回放语义 vs 键管理）、A-R3-1（加载守卫 vs 保存响应侧）。R2 §6.2「同文件同族全扫」+「跨端契约两端同批改+门禁锚双侧」至今**未写进 CODE_FIX_CHECKLIST**——两轮四条 P1 有三条属此科。
3. **注解 SQL 无绑定层验证**：D1 被 mock 单测盖住。全仓 4 处对照样本足以定判据：`@Select` 含动态标签必须 `<script>` 开头（静态门禁）或轻量 IT。
4. **两轮独立审查的互补性本身是流程结论**：R3+R4 并集比任何单轮多出 4 条 P1——同 HEAD 双轮交叉（不同子代理切分/不同主审）是当前最有效的漏斗；门禁候选 ①②③⑤（裸 await/CHECKLIST 条目/updateById 家族/seed 双向）两轮后仍全部未实施，应随修复批一起清。

---

## 7. 建议修复顺序（合并 R3 §8）

1. **立刻（P1×6，全链路影响）**：D1（补 `<script>`，解锁整条备货链）→ E5（前端四字段去 `|| undefined`）→ B-新1+B-新2（renew 分支实时复查+终态判断提前）→ A19（FATAL_INFO_RE 双形态+prod 用例）→ C-R3-1（构造侧挂 status）→ A-R3-1（保存响应守卫）。
2. **数据/完整性面（P2）**：B-新3（meFromServer 一次收口 11 页）→ C2（toast 读 status）→ D2（回放指纹+partial unique）→ B-R3-1（盲盘泄账面）→ B-R3-2（串图旁路）→ B-R3-5（mp 过账先落盘）→ D-R3-1（txt 旁路异步化）→ A20/A21/A22。
3. **同族收口与 P3**：R4 §3.3 十一条 + R3 同族项；D4 与「RETIRE 柜不解绑是否有意」**待用户拍板**。
4. **门禁三件 + 流程**：①`@Select` 动态标签门禁；②规则 9 补锚 prep-list（B-R3-7）+ 钉方法体/剥注释（R3-E-R3-1/2）；③「判据类修复必须有构造侧正向用例」+「跨端契约两端同批改」写进 CODE_FIX_CHECKLIST；④R2 §9 候选 ①②③⑤ 一并清。

---

## 8. 验证记录

- **主审亲验**：R2 修复 48 条逐条读现码；R4 发现 22 条全部亲验——5 条关键项机制级实证：A19（部署产物双 grep：URL 模板与正则同产物共存）、D1（mapper 全文+同批 `<script>` 对照+mock 单测确认）、E5（前端 ：1445/清除 ：1184/后端 release 分支三段逐行）、B-新1（判据函数全文+prep-generate 无 reload）、C2（finishSession 只读 totalAmountCents）、B-新3（meRef 单例+hydrateFromCache seed 链）。
- **HEAD 有效性**：`git diff b02e9041..893bb2ca` 核实本报告全部涉文件未被再改。
- **实跑**：`node scripts/check-alipay-shell-gate.mjs` = OK（12 文件）。本轮只读未改源码；lint/type-check 未重跑（修复批自报与 R2 基线一致）。
- **未验边界（诚实标注）**：B-新7 为静态推断需真机渲染确认；A21/A22/A24/D2 的竞态后果为静态推演；D1 未起栈实跑 500（机制证据充分）；§4 两处分歧（D-R3-5/D-R3-6）需一次只读 SQL 抽查终裁；真机点击链未复现（同 R2/R3 口径）。
- **待用户拍板**：①D4 提现回放终态窄窗改不改；②RETIRE 柜不解绑是否有意；③§1 门禁候选 ①②③⑤ 是否随修复批开工；④两轮（R3+R4）是否合并一份修复批开工。

---

*本报告为静态源码审计 + 主审逐条亲验口径（R4 独有 22 条全部亲验、无撤账；与 R3 归并后两轮并集 = P1×6 / P2×16 / P3×30 量级的完整账，见 §4）。R2 修复 48 条经两轮交叉确认「改动都在位」，其中 9 条自身带洞/被架空（§1 表）。未提交 git。*
