# 问题台账（MA-xxx 唯一编号源）

> 规则见 [README.md](README.md)。领号前先回读本表（编号全局递增，禁止撞号）。
> 入账前提：**已在当前 HEAD 实跑复现**（用户拍板：旧文档结论只当线索，复现通过才入账）。

## 索引表

| ID | 级 | 状态 | 端/页面 | 一句话 | 发现人/日期 | 关联 |
|----|----|------|---------|--------|-------------|------|
| MA-001 | P2 | fixed | admin `/user-analysis`（后端口径） | 30 天口径 KPI 随所选窗口漂移：days=7 时活跃/新增「30 天」显示 0（真值 2），days=90 时显示 24（真值 2） | Qoder R1 / 2026-10-31 | |
| MA-002 | P3 | fixed | admin `/user-analysis` | 沉睡名单静默截断 20 条：KPI=22 但表格/CSV 导出/召回弹窗「目标人数」均按 20 显示且无截断提示 | Qoder R1 / 2026-10-31 | |
| MA-003 | P3 | fixed | admin `/user-analysis`（召回弹窗） | 「目标人数（近 {{days}} 天沉睡名单）」文案与实际不符：沉睡窗口固定 30-90 天，与所选 days 无关 | Qoder R1 / 2026-10-31 | |
| MA-004 | P2 | fixed | admin `/sales-reports` + `/big-screen`（品类维度） | 品类维度直接渲染字典 value 裸码（显示「1」而非品类中文名），两处同源（`CompetitiveGapService.aggregateByCategory`） | Qoder R1 / 2026-10-31 | 355bb308 |
| MA-005 | P3 | fixed | admin `/big-screen`（头部） | 窄视口（<1600px）下大屏头部标题与右侧信息重叠溢出 | Qoder R1 / 2026-10-31 | 5a62f77e + 产物 0eb90b49 |
| MA-006 | P2 | fixed | 三端共享 `displayBizNo`（admin `/orders` 首发） | 列表显示的单号是剥出来的假短号（`E2E-TAIL-0001`→「20001」），按显示值搜索 0 结果，且同行「复制单号」给的是真值 | Qoder R2 / 2026-10-31 | 见明细 |
| MA-015 | P2 | fixed | admin `/consistency`（巡检规则） | 巡检把「下单时无批次」的历史订单判成 MISSING_SALE（规则只看设备现在有无批次账本，不看订单行是否带批次）⇒ 本轮 5 条 FAIL 里 2 条是误报 | Qoder R6 / 2026-10-31 | 见明细 |
| MA-019 | P2 | fixed（档 A） | admin `/menus` 菜单管理 | 提示语称侧栏叶子与「菜单 C」一一对应，实测 19 页共用他页权限码（`ops:warehouse:list` 一行管 8 页），停用一行会连带隐藏全部共用页且树上毫无提示；另有 `/recognition-demo` 幽灵 C 行（无路由无页面、3 角色持有） | Qoder R8 / 2026-11-01 | rounds/R08-system.md |
| MA-020 | P1 | fixed（档 B，`b2da3814`） | admin `/login` | 「记住账号密码」默认勾选，登录口令以 base64（可逆）常驻 `localStorage.admin_password`，只有取消勾选才清；资金后台凭据在浏览器侧一步可还原 | Qoder R8 / 2026-11-01 | 档 B 已落地：口令不再落地、历史明文三处无条件清除；`auth-storage.test.ts` 3/0 + 活栈登录实测 `admin_password` 不存在；另见 R09 轮报告「值守期落地记录」 |
| MA-021 | P2 | fixed（V421） | admin `/menus` + 角色权限树 | 4 个「前后端零引用」页面权限码仍 ACTIVE（H51 只改了前端 perm），菜单管理显示成启用页、角色树勾了不生效＝幽灵菜单 | Qoder R8 / 2026-11-01 | 沿用 V386 RETIRED 口径；`ops:operlog`/`ops:pricing:view`/`ops:nav:*` 判据不同刻意保留 |
| MA-022 | P3 | fixed | admin `/devops` + `/observability` | dev（vite）端内嵌看板恒 404：在线探针由后端发起说「在线」，iframe 走同源 `/devops/grafana` 而 vite 只代理 `/api` ⇒ 「卡片在线、嵌入区空白」自相矛盾 | Qoder R8 / 2026-11-01 | 生产形态（网关挂 /admin）已实证 200，非产品缺陷 |
| MA-023 | P2 | 展示侧 fixed（`a96b57c8`）/ 记账侧随 MA-029 闭合 | consumer-mp `pages/index` 商品卡 + `cabinet_order` 优惠拆解 | 会员 98 折在两层都不现身：商品卡下发目录价 350、活动价按 343 再减 100 ⇒「原价 ¥3.50 − 1.00 ≠ 实付 ¥2.43」；且 `member_discount_cents` 全仓无写入者恒 0，而巡检对账式正在消费该列 | Qoder R9 / 2026-11-01 | 原价列 V424 已落；巡检折后式已拆雷（`813bb20e`）；入账与检测式于 2026-11-02 随 MA-029 落齐 |
| MA-024 | P2 | fixed（V424 毛额列 + 明细摊开） | consumer-mp `pages/order-detail` vs `pages/result` | 同一单结果页披露「活动优惠 减¥1.00」、订单详情页没有活动行；且 `cabinet_order` 无 `original_amount_cents` 列 ⇒ 读模型反推的「商品合计」是活动后金额，现在补行只会更对不上 | Qoder R9 / 2026-11-01 | 新增 `gross_amount_cents`；页面按 合计−会员−活动−券=实付 摊开，会员档账面为 0 时反推 |
| MA-029 | P1 | fixed（方案 A：①为权威） | 结算链 `SettlementOrderSupport` + 巡检 + 部分退预估 | 会员折扣有两套并存机制：①单价里已折（行金额含折扣）②把 `member_discount_cents` 当订单级减项再扣一次。历史因②无写入者恒 0 而潜伏；一旦入账即重复扣减＝少收钱 | Qoder R9 / 2026-11-01 | 2026-11-02 落地：删 ②共 **5 处**减项（改单重算/比例退/退侧预估/巡检检测式/脏券清理不再抹会员）+ ①入账 + V425 改 ②形种子；见 rounds/R09 与 AUTO-RUN §1。**端到端腿已挣**（R11 段 1 `MemberOrderChainE2ETest`：真 HTTP 链 + 真库，会员单 `gross 700 / member 14 / 券 200 → total 486`，consumer+merchant 读面同数、分账 LEDGER_ONLY 全额归商户、钱包台账↔账户互证、全库折后式违规 0） |
| MA-030 | P2 | fixed（R9 批 1 轮内修） | merchant-mp 首页 + 待办页（同一屏两个「待办数」） | 首页快捷入口角标 = `mergedTodos.length`（全量 60），底部 tab 角标与页内四卡 = 归类合计（58）——根因是 `RESTOCK_TASK_TIMEOUT`（补货任务超时）没归任何卡；同屏两数对不上，用户把四卡相加也复算不出角标 | Qoder R9 / 2026-11-02 | 修法沿用 2026-10-27 `OPEN_TIMEOUT` 那条：归库存/补货卡 + 新增单源 `todoBadgeCount()`，两处徽标改走它；不变式「徽标==列表行数（全类型清单）」3 例钉测（先红 2 例再绿 29/29）+ 活体复验 60/60 |
| MA-032 | P3 | fixed（轮末文案批，活体复验） | merchant-mp `pages/warehouse-stocktake` 盘点单列表 | 「待录入」盘点单显示 `明盘 · 账面 502 · 实盘 502 · 差异 0（0 行）`，读起来像「已核平无差异」；DB 实证该单 9 行全 `PENDING`、`counted_qty` 是**创建时按账面预填**的（明盘设计＝只改偏差行）⇒ 数字不算错，但状态语义没交代，运营/商户会误读 | Qoder R9 / 2026-11-02 | 建议：`status=DRAFT` 时渲染「实盘 未录入（默认同账面）」；模板 `warehouse-stocktake.vue:43-44` 已有 `countedQty ?? '—'` 兜底，改的是「预填态」而不是空值态。判据 = 待录入单不再出现「差异 0（0 行）」 |
| MA-033 | P3 | fixed（R9 批 2 轮内修） | merchant-mp `pages/self-replenish` | 空明细时页面同时显示两句打脸话：上面「已按缺货与销量建议预填数量，可直接调整」，下面「该柜机暂无在架商品（商品已下架或柜内无台账）」；默认选中柜机是 门店二号柜（`/suggestions?deviceId=CAB-OTHER` 实测返回 `[]`，因其 7 个货道 `assignedSkuId` 全为 null） | Qoder R9 / 2026-11-02 | 修法：提示行加 `v-if="draftLines.length"`；活体复验（重烤产物 + `cli auto :9420`）⇒ 空态只剩一句实话 + 按钮显示「发起补货（0 种）」。图证 `b2-m13*`（修前）/`b2-m21*`（修后） |
| MA-031 | P3 | fixed（轮末文案批：基线+dist+bootstrap+V426 四处同改，活体复验） | merchant-mp 待办行类型标签 | 4 个待办类型不在 `merchant_alert_type` 基线里 ⇒ 行首类型显示「未知」（实测命中 补货任务超时 / 开门超时 两行）；缺 `OPEN_TIMEOUT`、`RESTOCK_TASK_TIMEOUT`、`SLOT_DISCREPANCY`、`INVENTORY_MISMATCH`；另 `DOOR_OPEN_TOO_LONG` 与 `OPEN_TIMEOUT` 若都译成「开门超时」会撞 `DictLabelUniquenessIT` 的同名门禁 | Qoder R9 / 2026-11-02 | 修法：基线 `packages/shared-dict/src/index.ts:783-798` 补 4 值（`DOOR_OPEN_TOO_LONG` 改「长时间未关门」与 `exception_type` 对齐）+ 重烤 shared-dict dist + `SysDictBootstrap` 默认值 + 迁移补 DB 侧字典（admin 读 DB）；判据 = 待办页 0 行「未知」 ⇒ **已达成**：重烤产物后实跑 `pages/alerts`，「未知」出现 0 次，两行改读「开门超时 / 补货任务超时」；`DictLabelUniquenessIT`（真库 Flyway 从零含 V426）1/0/0 证明同 type 无重名。🔴 **2026-11-05 订正**：四个补进来的译名里 `SLOT_DISCREPANCY` 的**类型本身**已随货道退场（批 3b 第十一刀：producers 全删 + `V445` 置 INACTIVE），该键只作历史行译名保留（`shared-dict:566/859` 勿跟着删） |
| MA-034 | P3 | fixed（轮末文案批：两处标注同批改，活体复验） | merchant-mp `pages/business` 经营分析 KPI 卡 | 同屏两套分母不标注：「营收 ¥7.00 / 件均 ¥3.50」用**行原价营收**，而「客单 ¥2.50」用**实付合计 ÷ 成交单数** ⇒ 用户按屏上数算不平（7.00 ÷ 2 单 ≠ 2.50） | Qoder R9 / 2026-11-02 | 代码已证实两个分子：`MerchantAnalyticsService:113` `sumRevenueByDeviceIdsSince`（原价）与 `:124-126` `orderRevenue/orderCount`（注释「客单 = 剩余实付合计 / 成交单数（零元单不进分母）」）。数值本身都对，缺的是标注；最小修法＝标签改「客单(实付)」或复用本页既有 `help-q` 说明位。判据 = 卡内任意两数可互相推导。**M 批 5 追加实证（升级为「同屏算不平」级）**：`pages/cabinet-reports` 同窗同柜（将邑测试机 10-04–11-02、2 单、营收 ¥7.00）显示**客单 ¥3.50**，来源是另一套实现 `business-display.ts:77-80 rowAov = revenueCents / orderCount`（原价分子）⇒ 同一次走查里两页对同一概念给出 **2.50 vs 3.50** 两个数。修法必须一次覆盖两处（后端字段 + `rowAov`），否则改一边就对不上另一边 |
| MA-035 | P3 | fixed（轮末文案批：shared-uni 一处修两端 + 2 例钉测，活体复验） | merchant-mp + consumer-mp `pages/announcements/detail` | 深链没带公告编号时，页面显示「公告不存在」+ 组件默认副文案「请检查网络后重试」+ 一个必然失败的「重试」按钮（`load()` 重读的还是那个空 id）⇒ 三处都在骗用户 | Qoder R9 / 2026-11-02 | 根因单点：`packages/shared-uni/src/announcements.ts:98-102`（`if (!announceId) error='公告不存在'`），两端页面都 `import useAnnouncementDetail` 共用它 ⇒ 改一次修两端。同族先例已修过：`device-detail.vue:609-614` 注释「参数缺失属入口问题，不是网络故障…文案与按钮都在骗用户」。修法＝区分「缺参数」与「真不存在」两种文案，缺参数时不给重试；注意 shared-uni 改动要重烤 dist（`check:shared-dist-fresh`） |
| MA-036 | P3 | fixed（V427） | admin 角色权限树 + 商户端组织树（`ops_permission`） | 4 个 ACTIVE 权限码**全仓零引用**（既无 `@RequiresPermissions` 注解，也不在 menu.ts/merchant-nav.ts 或任何 .ts/.vue 字面量里），却被 1~2 个角色持有 ⇒ 角色树里可勾选、勾了不生效＝MA-021 同形残留：`merchant:coupon:view`(C, 1 角色)、`ops:operlog:export`(F, 1)、`ops:upload:export`(F, 2)、`ops:vision:export`(F, 2) | Qoder R10 / 2026-11-02 | V427 一并 RETIRED **5 行**：4 个幽灵 + `ops:operlog`（MA-021 当初保留它的唯一理由是子节点 `ops:operlog:export` 在用，本轮复核该子节点即幽灵 ⇒ 保留理由消失；对照 `ops:pricing:view` 有 2 个在用子节点，继续保留）。dev 事务内试跑 `UPDATE 5` 后回滚；三方对账脚本 `.tmp/r10-perm-closure.mjs`（A=后端 162 / B=表 241 / C=前端 234）；🔴 反例护栏：`ops:audit:recent` 以非注解形式在用，落门禁时 A 集必须双扫注解**与**字符串用法 |
| MA-025 | P2 | fixed（读面判据） | consumer-mp `pages/balance` 余额明细 | 微信支付分的 `CHARGE`（通道 WECHAT、余额前后 NULL）被列进「余额明细」并显示「余额 ¥0.00」，而同页可用余额 140 未动 ⇒ 读成「余额被扣到 0」 | Qoder R9 / 2026-11-01 | 根因＝可见性查询不按通道过滤 + `value()` 把 null 兜底成 0 |
| MA-026 | P2 | fixed（读面判据） | consumer-mp `pages/coupons` + `pages/marketing` | 过期券（`expire_at` 已过 5 天）在读面仍算「未使用/1 张可用」且可设优先使用，而 `CouponService` 用券抛 409、自动选券直接排除 ⇒ 「提示可用、结算不选」自相矛盾 | Qoder R9 / 2026-11-01 | 置过期只靠日批（最近成功 10-24 02:00），读面无 `expire_at` 兜底 |
| MA-027 | P2 | fixed（V423 + 三处默认值） | consumer-mp `pages/help` vs `pages/policy` | 客服电话两个号：帮助中心读配置 `consumer.service_phone`=400-888-0018，用户协议/隐私政策正文硬编码 4000182992（3 处）⇒ 法定联系方式披露与实际客服号不一致 | Qoder R9 / 2026-11-01 | 🔴 用户 2026-11-01 确认 **4000182992 是实号**（微信支付已认证）⇒ 改配置侧，法律文本不动 |
| MA-028 | P3 | fixed（V422 + 写入口校验） | consumer-mp `pages/points/redeem` | 3 张兑换券券名与 `min_spend_cents` 不符（满100减20 实际满50、满50减10 实际满20、满20减5 实际无门槛），页面把券名与「满 X 可用」并排显示 | Qoder R9 / 2026-11-01 | 建议以门槛为准改名 + 加「券名满N ⇔ 门槛」校验门禁 |
| MA-018 | P3 | fixed（V418 彻底清除） | admin 各页字典下拉 | 8 组字典同一 type 下两个值同名（如「已回复」「对账差异」「手工入库」），下拉出现重复项；根因是 `SysDictBootstrap` 的兼容别名 | 用户 / 2026-10-31 | 门禁 `DictLabelUniquenessIT`（白名单已清空）+ V418 + shared-dict baseline 同步 |
| MA-017 | P3 | fixed | consumer`/auth` 短信验证码（phone-verify 代码审查） | 校验是「查出未用 → save(used_at)」，并发两个请求可同时通过同一码（双花） | Qoder R7 / 2026-10-31 | 见明细 |
| MA-016 | P3 | wontfix（用户拍板） | admin `/consistency`（争议规则） | 「争议已结案但无关联订单」一律记不一致（3 条），未区分「无购买争议」是否本就允许无单 | Qoder R6 / 2026-10-31 | 见明细 |
| MA-014 | P3 | fixed | admin `/fund-bills`（账务明细） | 账期留空时后端只看**近 7 天**且页面不交代 ⇒ 库里 6 条 10 月流水全不显示，空态「暂无流水」误导为没数据 | Qoder R6 / 2026-10-31 | 见明细 |
| MA-013 | P3 | fixed | admin `/print`（打印预览） | 纸张固定 720px + 左右 40px 内边距 ⇒ 窄视口（702px）溢出、`margin:auto` 失效看着偏左 | 用户 / 2026-10-31 | 见明细 |
| MA-012 | P2 | fixed | admin `/ad-campaigns`（关键词） | 关键词为前端本页过滤（`data.items.filter`），`total` 仍取服务端 ⇒ 行数与「共 N 条」不符、跨页命中丢失（MA-007/011 同族，由新门禁扫出） | Qoder R3 门禁 / 2026-10-31 | `check:list-filter-server-side` |
| MA-011 | P2 | fixed | admin `/device-ops`（关键词） | 关键词是**前端本页过滤**（`filterByKeyword` + 页内条数顶替 total），与 MA-007 同类：命中第 2 页的事件搜不到、总数谎报 | Qoder R3 / 2026-10-31 | 同 MA-007 族 |
| MA-010 | P2 | fixed（档 A → 已加 from/to） | admin `/device-ops` 设备运维 | 列表后端写死「近 14 天」窗口（100 条事件只显 54），前端既无提示也无日期筛选 ⇒ 14 天前的事件在页面上永久不可见，而「共 54 条」看着像全量 | Qoder R3 / 2026-10-31 | 见明细 |
| MA-009 | P3 | fixed | admin `/devices/:id`（关联单据 tab） | 两张表各只取最近 8 条，段级既无总数也无「查看全部」入口（总数已取回却只用于空态判断） | Qoder R3 / 2026-10-31 | 见明细 |
| MA-008 | P2 | fixed | admin `/reports` vs `/dashboard`（同名 KPI） | 「离线设备」两处不同数（报表 5 / 工作台 3）且报表侧不标口径：报表=全量含未投放/已退役，工作台=仅 DEPLOYED（刻意设计） | Qoder R3 / 2026-10-31 | 线索 #2 转正 |
| MA-007 | P1 | fixed | admin `/sessions`（类型筛选） | 「类型」筛选是**客户端只对当前页生效**：已完成+消费 显 13 条，真值 17 条，且 `total` 被换成页内过滤后条数 ⇒ 结果不全、分页总数不可信、导出同源 | Qoder R2 / 2026-10-31 | 见明细；lessons #205 |
| MA-037 | P1 | fixed（V428，活栈实证） | admin `/warehouse-transfers` 新建调拨 | 跨仓调拨创建**必然 500**：`warehouse_transfer_line` 的检查约束 `chk_wh_transfer_loss = (received_qty + loss_qty) = quantity` 与创建期插入语义冲突——创建时服务写 `received_qty=0, loss_qty=0` 而 `quantity>0` ⇒ 约束立即违反；接口把 DB 异常直接冒成 500「系统繁忙」而非 4xx | Qoder R11 / 2026-11-02 | 证据：traceId `f46acef186cc`，`Failing row contains (5, 2, 100013, '', null, 1, 0, 0, null, null)`；库存前后一致（失败不改库存这点是对的）。修法二选一：①创建只插 `quantity`、把 loss 约束改成 `received_qty+loss_qty <= quantity` 且仅 `RECEIVED` 状态要求相等；②创建即写 `received_qty=quantity, loss_qty=0` 再在 receive 时回填真实值。需产品定 loss 口径 |
| MA-038 | P2 | fixed（创建期校验，活栈实证） | admin `/warehouse-transfers` 收货 | 调拨**收货必然 500**：`warehouse_inventory.expiry_date` 为 NOT NULL，而 `WarehouseTransferService.doReceive` 用 `new LotSpec(sku,batchNo,null,line.getExpiryDate())` 入库、调拨行常无有效期（创建接口也不校验）⇒ 收货时 500「系统繁忙」 | Qoder R11 / 2026-11-02 | 证据 traceId `f4f7927fb786`：`null value in column "expiry_date" of relation "warehouse_inventory"`；传输单 1793586826720675318377 停在 `SHIPPED`（源仓已扣 46→45）。修法：创建校验 `expiryDate` 必填（400）或收货时从源批次继承；同时把 `DataIntegrityViolationException` 映射为 409/400 |
| MA-039 | P1 | fixed（钉测 + 活栈实证，含无活动对照腿） | 消费者 `/orders/{id}/pay` + admin 待支付「代收」 | 补扣链没有「活动价订单禁用券」闸：活动价 PENDING 单在补缴/代收时在**活动价小计上再选一张最优券**，违反 CB-082「活动优先」拍板 ⇒ 少收一张券面额 | Qoder 三端审计 / 2026-11-02 | `UnpaidOrderService:441`；主链闸 `SettlementOrderFinalizeService:84` |
| MA-040 | P2 | fixed（钉测 + 活栈实证） | 结算转 PENDING 的落库金额 | 活动单转待支付时 `clearCouponSelection` 按 `originalAmountCents`（**活动前**合计）还原应付 ⇒ PENDING `total` 虚高一个活动优惠额，风控事件与催缴短信同错；补扣时才按行金额纠回 | Qoder 三端审计（复核时新挖） / 2026-11-02 | `SettlementOrderFinalizeService:281` |
| MA-041 | P1 | fixed（活栈 UI 实证 8/8） | admin `/warehouse/fulfillment` 新建调拨弹窗 | MA-038 只把**后端**收紧成批次/有效期必填，表单根本没有有效期控件且批次标注「可空」⇒ 运营台任何填写组合都 400，故障从「收货 500」前移成「创建 400」，写路径整条断 | Qoder 三端审计 轨 C / 2026-11-02 | `WarehouseTransferDialogs.vue` + `useWarehouseTransfers.ts`；回归脚本 `scripts/verify-warehouse-transfer-form.mjs` |
| MA-042 | P1 | fixed（脚本级） | `scripts/cleanup-test-data.ps1` | 仍 DELETE 两张 V431 已 DROP 的表（`revenue_share_detail`/`promotion_device`），单事务 `ON_ERROR_STOP=1` ⇒ 首条即 `relation does not exist`，四份 UAT 跑批文档依赖的清库链必失败 | Qoder 三端审计 轨 B / 2026-11-02 | AU-186/FS-15 同型第三次 |
| MA-043 | P2 | fixed（钉测 3 例 + 活栈实证） | 争议确认改单重算 | `applyItemsToOrder` 按**当前**活动重定价所有行，`recalculatePayableAfterLineChange` 又照折旧券 ⇒ 券单在新配活动期间被双折；且活动过期时旧行会丢活动价、本单还把自己算进 `user_limit` | Qoder 三端审计 轨 A1 / 2026-11-02 | 拍板：成交即定价（沿用原单活动决策） |
| MA-044 | P2 | fixed（钉测 + 真库 IT） | 活动限购 `user_limit`/`totalOrderLimit` | check-then-act 无锁：两柜并发结算可双吃活动价（`findByIdForUpdate` 早已存在但定价链没人调） | Qoder 三端审计 轨 A1 / 2026-11-02 | `PromotionPricingService.resolveUnitPriceForOrder` |
| MA-045 | P2 | fixed（钉测 + 活栈实证） | 按行部分退款重试 | 渠道退款成功、本地 prepare/finalize 失败后，同参重试命中 COMPLETED 幂等键被直接 409 ⇒ 本地 total/行/status 永远补不上（远端钱已退） | Qoder 三端审计 轨 A2 / 2026-11-02 | `[LOCAL_PREPARED]` 标记与 prepare 同事务 |
| MA-046 | P2 | fixed（单测；启用当天看日志） | `profit-sharing-retry` 调度器 | 调度器方法自带 `@Transactional`，把 `submitSplit` 的 B2 三段式 HTTP 又包回 DB 事务；且逐行无 catch ⇒ 一条坏行抛异常整批回滚、后面的失败分账永不重试（当前分账休眠） | Qoder 三端审计 轨 A2 / 2026-11-02 | 报告原例「receiver 缺失」经复核不成立（`recordSplit` 已落 LEDGER_ONLY） |
| MA-047 | P2 | fixed（钉测 + 活栈实证） | `/api/v2/payment/payscore/notify` | 支付分回调漏在 `WebConfig` 免登录白名单外（wechat/alipay 回调都在）⇒ 微信回调必 401，`DONE` 无法把 PENDING 自愈为 PAID，欠款闸误伤正常用户 | Qoder 三端审计 轨 A2 / 2026-11-02 | 活栈实测：修后 400（缺头），对照 `/api/v2/orders` 仍 401 |
| MA-048 | P2 | fixed（runbook 项，非代码） | 部署前置 / V431 | V431 的逐表 0 行守卫是**环境依赖**的：V222 曾向 `revenue_share_detail` 补最多 5 行演示数据，从未清库的存量库会在生产部署时 Flyway fail-loud 阻断启动 | Qoder 三端审计 轨 B / 2026-11-02 | 两库实测已越过 V434/V433 ⇒ 只对将来的新生产库生效 |
| MA-049 | P3 | fixed（钉测） | 部分退优惠拆解 | 只等比缩会员留档、活动留档不缩 ⇒ 部分退后「商品合计 − 各档 ≠ 实付」展示对不平 | Qoder 三端审计 / 2026-11-02 | 写侧与预估侧同步 |
| MA-050 | P3 | fixed（钉测） | 争议重算会员留档 | `memberDisc = min(member, subtotal−coupon)` 在 >50% 会员折扣档把留档夹小，写坏 `gross−member−activity−coupon=total` | Qoder 三端审计 / 2026-11-02 | 留档列不进应付 |
| MA-051 | P3 | fixed（钉测） | 读面「原价/优惠」 | `OrderViewAssembler.originalAmountCents` 兜底只加 coupon+member，漏 activity ⇒ 活动单「原价」少一个活动优惠额；admin 两处优惠汇总同样漏（列表 + 设备关联单据） | Qoder 三端审计 / 2026-11-02 | — |
| MA-052 | P3 | fixed（钉测） | 券全额抵扣的 0 元单 | `chargeOrder` 在 `total<=0` 直接 return，既不完结也不取消支付分开门单 ⇒ 远端留一张永不结算的 CREATED/DOING 服务单 | Qoder 三端审计 / 2026-11-02 | 取消失败落 `CANCEL_FAILED` 供对账 |
| MA-053 | P3 | fixed（钉测） | 锁包装器异常出口 | 几十个 `runWith*Lock` 用 `catch (Exception) → ResponseStatusException(500)`，把 `DataIntegrityViolation` 从全局 409 通道截胡成 500（现实可触发=跨商户并发同 requestNo 提现） | Qoder 三端审计 / 2026-11-02 | 改为按 cause 链还原 409，不逐个改 wrapper |
| MA-054 | P3 | fixed | 运营「刷新分账」 | `refreshSplitStatus` 带 `@Transactional`，在事务（含行锁）内做微信查单 GET | Qoder 三端审计 / 2026-11-02 | HTTP 出事务、落库短事务 |
| MA-055 | P3 | fixed | admin `/stock-health` | 注释写「警示只用色条不改底色」，但 `.kpi-tile.warn` 黄底规则还在（脚本改注释漏同步，本批唯一实锤） | Qoder 三端审计 轨 C / 2026-11-02 | — |
| MA-056 | P3 | fixed | admin `/dashboard` | quick-row/quick-tile/page-pager 等约 70 行死 CSS（模板早已换成 zone-* 家族） | Qoder 三端审计 轨 C / 2026-11-02 | — |
| MA-057 | P3 | fixed | admin `/devices/:id` + `/devices` | 设备 KPI 数值仍是 `display-md`(22px)/600，未跟规范 24px·700 | Qoder 三端审计 轨 C / 2026-11-02 | — |
| MA-058 | P3 | fixed（活栈 UI 实证） | admin `/warehouse/fulfillment` 新建调拨弹窗 | 一行一字段，违反 `form-grid` 两列拼排规范（采购/实体弹窗都是两列） | Qoder 三端审计 轨 C / 2026-11-02 | 随 MA-041 同批改；同一脚本 A2 判据验证 |
| MA-059 | P3 | fixed | merchant-mp `pages/self-replenish` | `.input` 三套互斥规则并存（80/88/88 + 8rpx/20rpx/token 圆角），前两套是死 CSS | Qoder 三端审计 轨 E / 2026-11-02 | 产物级复验：只剩一套 |
| MA-060 | P3 | fixed | merchant-mp `pages/device-detail` + `pages/mine` | 表单输入统一批（88rpx + `--radius-control` + `#f1f5f9`）漏了这两页，仍是 80rpx/8px/`--page-bg` | Qoder 三端审计 轨 E / 2026-11-02 | 产物级复验：0 处 80rpx |
| MA-061 | P3 | fixed | shared-uni `theme.css` | `ui-btn*`/`ui-thumb*`/`ui-row`/`ui-num`/`ui-caption`/`ui-divider`/`ui-empty`/`ui-group-title`/`ui-section-title`/`app-mono`/`app-icon--sm|md` 全仓零引用（各页自己复制度量 ⇒ 漂移源） | Qoder 三端审计 轨 E / 2026-11-02 | 删前逐一交叉核对动态类名形态 |
| MA-062 | P3 | fixed | shared-uni `empty-state` | 显式传 `icon="glyph"` 且 kind 缺省时，第二个分支不放图、掉到文本分支 ⇒ 把字符串 "glyph" 当图标渲染 | Qoder 三端审计 轨 E / 2026-11-02 | — |
| MA-063 | P3 | fixed | consumer-mp + merchant-mp | `absoluteImageUrl` 两端各一份逐字相同实现（当前无漂移，但属结构性重复） | Qoder 三端审计 轨 D/E / 2026-11-02 | 收进 `shared-uni/product-image` |
| MA-064 | P3 | fixed | `docs/TROUBLESHOOTING_GUIDE.md` | 排障 SQL 仍指 `idempotency_keys`（本就拼错且 V431 已删）、`payments`/`orders`/`revenue_share_tasks` 三张不存在的表，还教人手改订单/插流水 | Qoder 三端审计 轨 B / 2026-11-02 | 同批给历史 `wipe-business.sql` 加「勿重跑」头 |
| MA-065 | P3 | fixed（钉测 + 活栈实证） | 支付/支付分回调 | 白名单落地后实测：缺 `Wechatpay-Timestamp` 等请求头冒 **500**（全局只映射了缺参数、没映射缺请求头）⇒ 渠道探测刷 ERROR 日志 | Qoder 活栈复验 / 2026-11-02 | 改 400，`GlobalExceptionHandlerTest` 8 例 |
| MA-066 | P2 | fixed（钉测 4 例 + 活栈实证） | 争议确认/结算的欠款转待支付链 | 余额不足被吞掉转 PENDING 后，事务早被标 rollback-only ⇒ 提交抛 `UnexpectedRollbackException` 500，订单/工单/风控整链回滚（编程式 `TransactionTemplate` 无 noRollbackFor + `chargeOrder` 只放行 `BalanceInsufficientException`） | Qoder 活栈资金链复现 / 2026-11-02 | 账本收下该 412、出锁出事务后再抛并折成业务信号；同型第三例（P1-1/BUG-007） |
| MA-067 | P2 | fixed（钉测 1 例 + 活栈实证） | 结算「待支付不占券」落库 | `clearCouponSelection` 把 couponId 置 null，但订单此时已 insert ⇒ `save` 走 MP `updateById` **跳过 null**，库里仍留 coupon_id 而 `user_coupon` 仍 UNUSED（券没核销却挂在单上）；后续按 `order.getCouponId()` 重算的链路会对没收的券再折一次 | Qoder 活栈资金链复现 / 2026-11-02 | 新增显式 UPDATE `clearOrderCouponSelection` + `markPendingAndDropCoupon` 两支都走；e2e 腿 2 钉 PENDING 行 coupon_id=NULL |
| MA-148 | P3 | fixed（钉测 1 例 + 活栈实证） | 参数配置写侧留痕 | 审计总开关 `ops.config.audit.enabled=false`（dev 实测就是 false）时只有 `FUND_GUARDED_KEYS` 强制留痕；而「开关即资金动作」的**布尔键进不了那个集合**（`validateFundKeyValue` 只吃整数，写 `"true"` 当场 400）⇒ 谁把争议自动免单打开了，历史与审计双零 | Qoder 旋钮落地时自查 / 2026-11-03 | 新增 `AUDIT_FORCED_SWITCH_KEYS`（只管留痕、不管值校验），`recordChangeIfEnabled` 一并放行 |
| MA-149 | P2 | fixed（收窄两处注解；条码查询保留 OR） | `POST/PUT /ops/admin/skus`（建/改商品） | 注解 `OR{ops:sku:edit, ops:sku:import}`（AdminDashboardController.java:517/:527）与服务层单码 `ops:sku:edit`（OpsCatalogAdminService.java:115/:140）双轨：`ops:sku:import` 是 V106 已授角色的**真码**，只授「导入商品」的运营过注解门后建/改商品恒 403，只能靠 `ops:*` 通配绕过（MA-105 同族，但那条的 `ops:finance:view` 分支更少被授） | Qoder 批次A/B 复核时挖出 / 2026-11-03 | 新增 |
| MA-150 | P3 | fixed（批次 U：A 档归因落地，`user_id`+`position=BANNER`+按人去重；残余刷量倍率转 MA-154） | 广告打点回写无归属主体 | `POST /api/v2/devices/{deviceId}/ad-play`（`DeviceController#adPlay`）既不取登录主体、服务层 `recordPlayEvent` 也不校验调用者能否写这台柜机；`ad_play_event`（V191）没有 user 列，去重键是 `(device, campaign, asset, type)` ⇒ 换 deviceId 即绕开 60s 窗口，事后也查不出这批曝光来自谁 | 权限矩阵规则 2 首跑 / 2026-11-04 | **口径订正（用户 2026-11-04）**：我们是**收益侧**——接腾讯广告做流量主赚钱，自有活动位放自己的活动，不是花钱投放；曝光面 = 首页 / 开门前 / 关门后（所以「绑会话才计数」的方案作废）。现无资金路径（实测：`ad_play_event`/`AdPlayEvent` 只被 `DeviceController`/`DeviceInternalController`/`MarketingController`/`AdCampaignService` 与 ROI 报表读，未进分账/费用台账/结算/佣金任何一处）⇒ 危害目前限于内部决策报表失真；接流量主后结算以腾讯后台为准，自刷反而踩对方反作弊，属于「绝不能自动补量」的合规问题而不是配额问题。待拍板的最小动作＝归因（加可空 `user_id` + 去重键含 user + 报表显式列「人均/单用户最大次数」），**不设配额阈值**（当前无真实流量分布，阈值只能是猜的死旋钮） |
| MA-151 | P3 | fixed（批次 S：三列 + 三位置全打点 + 9 例钉测） | 开门前 / 关门后弹窗位完全无打点 | `MarketingController` 只有 `/ads/{campaignId}/events`（banner 组件在用），而 CB-082 的两个弹窗位（`POPUP_BEFORE_OPEN` 首页、`POPUP_AFTER_CLOSE` 结果页）从展示到点击一次都不上报 ⇒ 「这个活动位有多少人看过/点过」在弹窗位上是**空数据**；另外小程序主体此前被伪装成柜机号写入 `device_id`（`MP-U{userId}`），去重键也是设备维度 ⇒ 换 deviceId 就绕过 60s 窗口 | Qoder 批次 S / 2026-11-04 | 「多少人享受活动」不在本行——权威口径是 `cabinet_order.promotion_id + activity_discount_cents`（本批未动） |
| MA-152 | P2 | fixed（批次 T：V440 放开两列 + 反向 CHECK IT） | 批次 S 的弹窗位打点**在真库 100% 插入失败**，而前端把它降成一条 console 噪音 | `ad_play_event`（V191）的 `campaign_id BIGINT NOT NULL` 与 `device_id VARCHAR(64) NOT NULL` 从没被放宽，而批次 S 的弹窗事件按设计**只有** `activity_id`（campaign_id/device_id 皆 null）⇒ 每一次 `POST /api/v2/marketing/placement-events` 都在 DB 层炸 `NotNullViolation`；MP 侧 `.catch(e => console.warn(...))` 把它吞成一条日志 ⇒ 真机上看不到任何异常，弹窗位仍是零数据，只是原因从「没上报」变成「上报必失败」。9 例单测全绿（mapper 是 mock，MyBatis-Plus 生成的 INSERT 从未被执行） | 批次 T 的 `PlacementEffectSqlIT` 首跑（2026-11-04） | 与 MA-151 同族但判据不同：那条是「代码不报」，这条是「报了也进不了库」。修法=`V440` 放开两列 NOT NULL，并补 `ck_ad_play_subject`（campaign_id 与 activity_id 至少一个）兜住底线，配一条**反向** IT 钉「两主体全空的行必须被拒」 |
| MA-153 | P2 | fixed（编码还原由并发会话 `569ac0a6` 落仓；本批补**产物侧哨兵** + 批次 T 形状 regen） | regen 把 `openapi.ts` 里 460 个中文写成双重编码乱码，本地门禁全绿、CI 独红 | 批次 S 的 live regen 落盘的产物里所有 springdoc 中文描述变成「UTF-8 字节按 latin1 解码后再存 UTF-8」形态（`点位费用配置` ⇒ `ç¹ä½Mè´¹…`，`Buffer.from(x,'latin1').toString('utf8')` 可逐字节还原）；类型名与结构全是 ASCII ⇒ **形状门禁与结构校验都看不见**，只有 CI 起 HEAD 的 jar 抽真 spec 做 `git diff` 才红（`build` job:41761621025）。批次 S 的**形状**其实是对的：按现抓 spec 重打后「删除行去掉注释 = 0 行」 | Qoder 批次 T 归因 / 2026-11-04 | 取 spec 的那一步为何被码页污染没能复现（`.tmp` 里现存的 spec 全部干净），所以补的是**产物侧哨兵**而不是链路修法：`check-openapi-types.mjs` 现在对 `openapi.ts` + 8 个别名文件跑 `[\u00c2-\u00f3][\u0080-\u00bf]` 判定，命中即 exit 1；`check-openapi-types.test.mjs` 9 向钉测（含「正常中文注释不得误报」「缺失仍要红」「沙箱不碰真实树」）。peer 同期在 `gen-openapi-types.mjs` 加了落盘前防线，两处不重复：一个拦「写坏」，一个拦「已坏的推上去」 |
| MA-154 | P3 | fixed（IDOR 轴收口：9 条钉双向用例 + 2 条定性） | 归属校验普遍**没有「换别人的 id 必须失败」的用例**；ad-play 的 `{deviceId}` 与调用者仍无归属关系 | 逐条核 13 条带路径 id 的 self-service 写端点（权限矩阵基线 37 条里的子集）：12 条服务层确有归属闸（订单支付/退款/发票、充值取消、会话取消/购物车/创建、通知已读、活动领券、报修），但只有 `POST /payment/recharge/{orderId}/cancel` 有一条 `cancelRecharge_wrongUser_forbidden`（`PaymentServiceTest.java:86-92`）真正钉住「非本人 403」（全仓同类用例共 3 条，另两条 `CouponServiceTest.java:317 useCoupon_shouldThrow_whenWrongUser`、`FileAttachmentOperatorReadTest.java:103 otherUser_cannotReadForeignFile` 覆盖的是 body 里的券号与附件读面，不在本轮 13 条内）；其余 11 条要么只有「记录不存在/锁忙」用例（不等价），要么完全无测试（`sessions/{id}/cart`、`sessions/{id}/demo-close` 零命中）。⇒ 这些闸**现在是对的，但改一行就能悄悄失效**。另外 `POST /devices/{deviceId}/ad-play` 归因已补（MA-150），残余是：`deviceScope=ALL` 的计划对 deviceId 完全不校验（该行为被 `AdCampaignServiceTest#recordPlayEvent_allScopeAcceptsAnyDevice` 当契约钉住）、`assetId` 不校验是否属于该 campaign ⇒ 单人每 60s 窗口可产生的行数 ≈ RUNNING 计划数 × 存在素材数 × 3，且每行可挂不同柜机号；只失真报表，不动钱 | Qoder IDOR 轴第一轮（子代理取证 + 我逐行复核）/ 2026-11-04 | 补测口径：每条都要「第二个用户重放第一个用户的 id ⇒ 403/404」+「本人仍成功」两向，只断言前者会漏「整块闸坏掉」的假绿。**已钉（批次 U 续，7 条 / 11 例）**：`sessions/{id}/cancel`（他人 403 且零写入）、`sessions/{id}/demo-close`（他人 404 而非 403，403 等于告诉探测者会话号存在）、`sessions/{id}/cart`（他人 403 于任何 item 写入之前）、`member/notifications/{id}/read`（他人 403 且不先把 readAt 写上），各带本人正向对照。本笔再补三条**钱路**：`orders/{id}/invoice`（他人 403 且对开票表零交互，本人用 409 状态门证明不是归属门）、`orders/{id}/refund`（他人 404 而非 403，且不触发 `waiveAndRefund`）、`orders/{id}/pay`（他人 404 且不读明细行、不 save 订单）⇒ `SessionOwnershipGuardTest` 5 例 + `NotificationServiceTest` 2 例 + `InvoiceConcurrencyTest` 2 例 + `DisputeOrderRefundTest` 2 例 + `UnpaidOrderConcurrencyTest` 2 例。**本笔再补 2 条 / 5 例**：`marketing/campaigns/{id}/claim`——这条口没有可伪造的路径主体，风险形态是「额度算错人」，所以钉的是「调用者已达限领 ⇒ 409 且 `issueToUser` 一次都不调」+「换一个人额度干净 ⇒ 能领且**只发给自己**」（`ConsumerMarketingConcurrencyTest` 由 1 例增至 3 例）；`POST /sessions` 的 `idempotencyKey`——越权面藏在 body 的键里，`SessionIdempotentReplayTest` 3 例分别钉「他人键重放 ⇒ 409 且**不抢开门设备锁**、不落新会话」「本人换柜机重放 ⇒ 同样 409（否则一次重试就能把会话挪到别的柜开门）」「本人同柜重放 ⇒ 原样返回既有会话」。**两条定性**：`payment/recharge/prepay` 主体只从登录态取、请求里没有可重放的对象 id ⇒ **无 IDOR 面**；`devices/{id}/fault-report` 归属正确（工单记调用者），缺的是**频率限制**（每次成功都推一条 HIGH 运营异常），那不属于本行，另开 MA-158。ad-play 的 deviceId 归属要不要收成「调用者近期开过这台柜」属产品决定，先不动 |
| MA-158 | P3 | fixed（档①：按用户小时窗口限流已落，闸在抢锁之前） | `POST /api/v2/devices/{deviceId}/fault-report` 任何登录用户可对任意柜机反复报修 | 主体取自登录态、工单也记调用者（`DeviceFaultReportService:123`），所以**不是 IDOR**；缺的是限流：服务层只有防并发锁（`:111` 调用、`:155-159` 实现）。⚠️ **原判已订正（2026-11-04 复核）**：「每次成功都会推一条 `DEVICE_FAULT/HIGH` 异常 ⇒ 可刷运营告警」**不成立**——`OpsExceptionService.report` 的 dedupKey = `类型:第一个非空(sessionId,orderId,deviceId,userId)`（`OpsExceptionService:81`）= `DEVICE_FAULT:{deviceId}`，命中 `status ∈ {OPEN, PROCESSING}` 就直接返回既有异常（`:83`、`:44`）⇒ 同柜机未结案期间的重复报修**不产生新告警**。真实剩余面＝每次成功仍往 `device_fault_report` 多插一行，而该表全仓**无读面**（只有这条 POST 写入，`docs/EMPTY_TABLES_DISPOSITION_2026-11-02.md` §3.3）⇒ 刷的是行数。同族对照：`claim`/`pay` 都有 `ApiRateLimitService.assert*` 门（`ConsumerMarketingService:262`、`UnpaidOrderService:221`），这条口原本没有 | Qoder IDOR 轴第二批附带 / 2026-11-04 | 落地＝复用既有的 Redis 小时窗口：`ApiRateLimitService.assertFaultReportAllowed(userId)`（`ApiRateLimitService:76`）在 `DeviceFaultReportService:109` 调用，**放在抢锁之前** ⇒ 被拒的重复上报既不占 (user,device) 锁、也不落行。阈值 `maxFaultReportsPerHour` 默认 **10**（env `RATE_LIMIT_FAULT_REPORTS_PER_HOUR`，`RATE_LIMIT_ENABLED=false` 仍可整体关掉），与非正数即回落到默认的钳位先例一致。**刻意不做设备维度计数器**（否掉原候选的 `(userId, deviceId)` 形态）：同一台真坏的柜机应允许多名消费者各报一次，共用设备计数器会把后来者误拦——这条判据写进了 `RateLimitProperties` 的类注释防顺手对齐。**档②「同一未结案工单幂等合并」＝不需要（2026-11-04 复核后撤下）**：它的目标（重复报修别刷告警）已由 `OpsExceptionService` 的 OPEN/PROCESSING 去重覆盖，剩下的「行级幂等」只省一张**无读面**表的行数，等于给没人读的表造机件 ⇒ 不做；将来若真开「我的报修」读面、`reportId` 有了消费方，再按 `(user, device, issueType)` 窗口合并。钉测：`ApiRateLimitServiceTest` 7→9（键名钉 `aicabinet:rate:fault_report:{userId}`、超限 429 且不回写计数）、`DeviceFaultReportConcurrencyTest` 1→3（超限 ⇒ 429 且 never tryLock / never save / never 推告警；正向对照 ⇒ 未超限照常落单并释放锁，同时证明「闸被删掉」会因未消费的桩与 verify 立刻红）、`RateLimitPropertiesBindingTest` 四向补新键。**隔离栈活栈复验（2026-11-06，9/9 通过）**：干净 worktree(HEAD) 构建 jar + 临时 PG/Redis + 18100（不碰 18080/共享库/共享 Redis）实测——前 10 次 200、第 11 次 429、`device_fault_report` 只多 10 行、`ops_exception` 只多 1 条（告警去重同时也被实测坐实） |

| MA-068 | P2 | fixed（钉测 1 例） | merchant-mp `POST /merchant/devices/{id}/stocktake-evidence` | 盘点凭证上传缺设备归属校验（同 Controller 全部设备写端点都过 requireDevicePack，唯独上传漏）⇒ 商户 A 可对商户 B 柜机挂任意照片/灌图，盘点凭证链可跨租户污染 | ZCode R12批1 / 2026-11-02 | rounds/R12-write-surface.md |
| MA-069 | P2 | fixed（限幅单源+门禁） | `POST/PATCH /ops/admin/line-managers` 佣金参数 | 线长佣金 rateBps/fixedCents 裸 Map 无界直落（负数/50000bps=GMV5倍佣金都存得进），LineCommissionJob 按日真实入钱包 ⇒ 持续性资金乘数无范围校验；对照 WalletAdjustRequest ±1亿分先例 | ZCode R12批1 / 2026-11-02 | rounds/R12-write-surface.md |
| MA-070 | P2 | fixed（档 A：服务层按注解放宽） | admin `/org-sites` 组织与点位 | 权限码双轨：Controller 注解 ops:org:*、服务层强制 ops:device:*（AND），唯 DELETE 是 OR ⇒ 只授 ops:org:* 的角色「页面 403、增改 403、删除却放行」，删比改松方向反常 | ZCode R12批1 / 2026-11-02 | rounds/R12-write-surface.md |
| MA-071 | P3 | fixed（三处去重幂等接受） | OpsRbacService 三个分配端点 | 整删整插 body 裸 List 无去重，重复 ID 触发复合主键 DuplicateKey→500（应 200 去重或 400）；roles/{id}/permissions、users/{id}/roles、users/{id}/merchants 三处 | ZCode R12批1 / 2026-11-02 | |
| MA-072 | P3 | fixed（改密 + 2FA 开关三处留痕） | 运营改密/2FA 关闭 | PUT /rbac/me/password、two-factor/confirm、/disable 零审计留痕——同 Controller 其它 14 处 RBAC 写全留痕（CB-021），账号接管链关键事件审计页不可见；商户端改密反而有 PASSWORD_CHANGE | ZCode R12批1 / 2026-11-02 | |
| MA-073 | P3 | fixed（商户/线长提现申请留痕；系统重试仍记为系统） | 提现「申请」动作（商户+线长） | review/payout/cancel 三兄弟全有 MERCHANT_WITHDRAW_* 审计，唯独 APPLY 无；运营代提在低于阈值自动打款分支下操作者三处不可归因（线长侧 submitter 恒 null） | ZCode R12批1 / 2026-11-02 | |
| MA-074 | P3 | fixed（钉测 2 例，幂等命中不留痕） | `POST /ops/admin/staff-wallets/{id}/payout` | 补货员钱包打款标记（资金出账销账动作）无操作者审计；同族 LINE_MANAGER_ADJUST/MERCHANT_WITHDRAW_PAYOUT 均有 | ZCode R12批1 / 2026-11-02 | |
| MA-075 | P3 | fixed（快照留痕） | `PATCH /ops/admin/line-managers/{id}` | userId 换绑/改名/改手机不进 LINE_MANAGER_UPDATE 审计（只有佣金/状态变化才记）；userId 是 CB-066 实名守卫锚点，longVal 不校验用户存在可绑到不存在/他人 ID | ZCode R12批1 / 2026-11-02 | |
| MA-076 | P3 | fixed（税务资料 + 补货配置均留痕） | 商户补货配置 + 开票税务资料 | PUT /merchant/ops-config（CompetitiveGapService:301）与 PUT /merchant/tax-profile（InvoiceService:174）两处配置类写无审计；税务资料含银行账号。补货配置收口：`MERCHANT_OPS_CONFIG_SAVE`（阈值/模式/开关全量快照）+ 商户门户透传 userId + verify 钉测 | ZCode R12批1 / 2026-11-02 | |
| MA-077 | P3 | fixed（白名单 400） | OpsPayoutAccountController 收款账户 | ownerType 无白名单：未知值落到 else 分支 `ops:finance:view`（只读权限可写）且垃圾值原样入库；无资金影响（提现链只认 LINE_MANAGER/MERCHANT） | ZCode R12批1 / 2026-11-02 | |
| MA-078 | P3 | fixed（status 白名单 ACTIVE/INACTIVE） | `POST /ops/admin/merchants` | merchant.status 自由字符串直落：无白名单（"OPEN" 等静默生效使商户从 ACTIVE 过滤面消失）、超 16 字符触发 PG 22001→500 而非 400 | ZCode R12批1 / 2026-11-02 | |
| MA-079 | P3 | fixed（内置类型 409 禁删） | `DELETE /ops/admin/dicts/types/{dictType}` | 可硬删代码内置字典类型（ORG_NODE_TYPE 等白名单权威来源），删除后 resolveNodeType fail-open 放行任意值；fail-open 本身是 javadoc 钉的设计，缺的是内置类型删除门槛 | ZCode R12批1 / 2026-11-02 | |
| MA-080 | P3 | fixed（父级存在 + 祖先环检测） | `PUT /ops/admin/org/nodes` | 组织节点 upsert 只拦「上级是自己」，不查父级存在性也不查祖先环（parent_id 无 FK，对照 DepartmentService 有完整防护）⇒ 环中节点双双缺席 roots，整棵子树从 /org/tree 静默消失 | ZCode R12批1 / 2026-11-02 | |
| MA-081 | P3 | 复核不成立（撤账，见文末批次A/B收口节） | `POST /account/bind-openid`（mock-only） | 重复绑定他人已占 openId 撞 uk_user_wx_open_id 唯一索引→500 而非 409；生产 403 门禁在，仅 mock 环境报错语义问题 | ZCode R12批1 / 2026-11-02 | |

| MA-082 | P3 | fixed（券/活动/展示位/素材四服务留痕） | 营销域运营写面（券模板/发券/活动上下架/展示位/素材 5 服务 15 端点） | 审计留痕整体缺失（零处 appendLog），同域兄弟 AdCampaignService 四处全有 AD_CAMPAIGN_* 审计 ⇒ 券面值改动/活动上下架/素材删除在 /audit 页不可查 | ZCode R12批2 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-083 | P3 | fixed（DTO 约束 + Validator 钉测） | `POST /coupons/batch-issue` + `/issue` | DTO 零约束：userIds=null → NPE 500（应 400）；userIds 无上限（万级元素长事务持 coupon_def 行锁）；对照充值 DTO 有完整 @Min/@Size | ZCode R12批2 / 2026-11-03 | |
| MA-084 | P3 | fixed（status 白名单） | `PUT /ops/admin/ad/assets/{id}` | 素材 status 自由串直落（MA-078 同构）：垃圾值静默从全部 ACTIVE 过滤面消失、超 16 字符 22001→500 | ZCode R12批2 / 2026-11-03 | |
| MA-085 | P3 | fixed（闭集+钉测 7 例） | `POST/PUT /ops/promotions` | activityType 无白名单（垃圾类型经 launch 直接 ACTIVE 进消费端可见列表，非 SKU 类型跳过全部校验）；budgetCents 负数直落使预算守卫 fail-open（`budget>0 &&` 守卫），与 user_limit「0/负=不限」钉测口径不一致 | ZCode R12批2 / 2026-11-03 | |
| MA-086 | P3 | fixed（时间窗倒挂 400，对齐 Promotion） | `POST/PUT /ops/admin/ad/campaigns` | 投放计划时间窗倒挂不拦（startAt>endAt 静默接受→计划永不生效、轮播静默空转）；同域 Promotion 面有拦，兄弟端点不一致 | ZCode R12批2 / 2026-11-03 | |
| MA-087 | P3 | fixed（@Size 补齐，先例 FileDisputeRequest） | 营销域字符串 DTO | couponName/campaignName/mediaTitle/idempotencyKey 缺 @Size，超长撞列宽 22001→500（本仓已有 FileDisputeRequest @Size(256) 等成熟先例，属漏网） | ZCode R12批2 / 2026-11-03 | |
| MA-088 | P3 | fixed（拒入账+告警，双例钉测） | `POST /api/v2/payment/wechat/refund-notify` | refund_id 缺失时幂等键塌缩为全局字面量 `channel-refund-sync:null` 且不含 orderId ⇒ 理论上不同订单第二笔退款扣余额被静默吞掉而 refunded_cents 照常推进（幽灵余额窗口）；微信 v3 实际必带该字段，防御性加固 | ZCode R12批2 / 2026-11-03 | |
| MA-089 | P3 | fixed（去 DTO @Max） | `POST /api/v2/account/balance-refunds` | DTO @Max(500_000) 硬编码与服务端可配置上限 BALANCE_REFUND_MAX_CENTS 口径不一致——配置只能收紧不能放宽；对照充值 DTO 无 @Max 全由 DC-6 裁决，两 DTO 风格矛盾 | ZCode R12批2 / 2026-11-03 | |

| MA-090 | P2 | fixed（钉测 7 例） | `PUT /ops/admin/machine-payee-bills/config/{deviceId}` | 机器账单分成比例 shareBps 无界直落（>10000 可生成超 GMV 五倍的分成账单且每账期持续生效）；同仓三处先例都有闸（SiteRentSplit 400 / FundBill 钉测 / allocate 钳位），与 MA-069（线长佣金）完全同构；次要：enabled 无 0/1 约束（垃圾值使该柜从出账面消失）、payeeName null→500 | ZCode R12批3 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-091 | P3 | fixed（机器账单结清补归因；费用台账/重跑此前已接） | 商户费用台账结清 / 机器账单结清 / 按日重跑日扣 | 三处资金处置动作零操作者归因（连 operatorId 参数都没有；rerun-daily-fees 是批量真扣钱包 WalletEngine.debitIfAbsentAllowNegative）；MA-074 同类 | ZCode R12批3 / 2026-11-03 | |
| MA-092 | P3 | fixed（开票/驳回/机器账单/对账重跑全留痕） | 发票开具/驳回、机器账单出账、对账重跑 | 财务面 5 写端点无 /audit 审计（对照 SiteRentBill/DeviceDataFeeBill 全有 appendLog）；ReconciliationService.runDaily 的 operatorId 传而不用，重跑删旧重建无痕。收口：`RECONCILIATION_RUN`（date/channel/rebuilt/diff/status）+ verify 钉测 | ZCode R12批3 / 2026-11-03 | |
| MA-093 | P3 | fixed（充值正数闸 + 零落账钉测） | `POST /ops/admin/platform-fund/accounts/{id}/recharge` | 负数「充值」直落：以 TYPE_RECHARGE 语义落负数出账流水扣余额，绕过 adjust 的必填原因口径（V341 三闸类型语义失真）；Controller 用 Map 手工取参，字符串金额 ClassCastException→500 | ZCode R12批3 / 2026-11-03 | |
| MA-094 | P3 | fixed（非法账期 400 + 未来账期守卫） | `POST /ops/admin/machine-payee-bills/generate` | billMonth 裸 YearMonth.parse：非法账期 DateTimeParseException→500（同域 SiteRentBill/DeviceDataFeeBill 都走 FeeBillMonthResolver 400）；顺带无未来账期守卫 | ZCode R12批3 / 2026-11-03 | |
| MA-095 | P3 | fixed（区间/倍率/折扣/状态写侧闸） | `POST/PUT /ops/admin/growth/member-levels` | 会员等级规则零入参校验：区间倒挂 min>max 恒 false=静默死档、负 pointsRate 该档返积分恒 0、status 自由串静默失效；读侧 priceDiscountPct 有钳制（≤90）故无资金直损，写侧应对齐口径 | ZCode R12批3 / 2026-11-03 | |
| MA-096 | P3 | fixed（写侧闸 + 七分支钉测） | `PUT /ops/admin/growth/points-redeem` | 兑换商品 pointsCost 负数直落=持续性积分铸造参数（消费端 redeem 负 cost 使可用积分倒增）；0=免费兑；couponDefId 可 null/不存在（带病上架每次兑换必失败）；与 MA-069/MA-085 同类 | ZCode R12批3 / 2026-11-03 | |
| MA-097 | P3 | fixed（解除补记 BLACKLIST_REMOVE） | `DELETE /ops/admin/risk/blacklist/{userId}` | 黑名单解除零留痕（add 有 BLACKLIST_ADD 事件含 by，remove 完全无痕迹）——被收买账号可无声解除作弊者；一行改动级 | ZCode R12批3 / 2026-11-03 | |
| MA-098 | P3 | fixed（增长域 8 写端点全留痕） | 增长域运营写（MemberLevelAdmin/PointsRedeem/UserRecall/Notification/SkuDelistReview 6 服务 9 端点） | 审计整体缺失（grep 零 appendLog），同批 OpsRisk/DeviceOps/AdCampaign/MemberFinance 全有——MA-082 同构；等级规则叠加价格+积分双资金乘数。修法：`MEMBER_LEVEL_UPSERT/STATUS`、`POINTS_REDEEM_UPSERT/STATUS`、`SKU_DELIST_REVIEW_RUN/DECIDE`、`NOTIFY_SEND/UPDATE/DELETE` 九类 appendLog + 控制器透传 operatorId；钉测 4 处 verify | ZCode R12批3 / 2026-11-03 | |
| MA-099 | P3 | fixed（requestId 幂等键 + 同券 60s 短窗） | `POST /ops/admin/growth/user-recall` | 一键召回无顺序重放幂等：锁只防并发不防重放，双击=同批用户每人多发一张券+通知（maxIssueCount 只限总量）；对照订单积分有 existsByMemberAndSource 唯一索引幂等。收口：body 可带 requestId（Redis NX 10 分钟标记，重放 409 不再发券）；未带键退化为同券 60 秒短窗去重；body 解析错误改 400；4 条钉测 | ZCode R12批3 / 2026-11-03 | |

| MA-100 | P2 | fixed（限幅+留痕） | `PUT /ops/admin/line-routes/{routeId}/members` | 线路成员佣金参数（rateBps/perUnitCents/basis）无界零校验直落且零审计，LineCommissionJob 按 bps 计佣直接 creditIfAbsent 入补货员可提现钱包（bps=1000000=100倍GMV）；同族第三例（MA-069 线长佣金/MA-090 机器账单分成），G2-13 已给 LineManager 落了 before/after 审计先例，本端点漏网 | ZCode R12批4 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-101 | P2 | fixed（409 + V435 唯一索引） | `POST /ops/admin/external-import/batches/{id}/commit` | 外部导入同渠道同账期可多批次重复 commit（只挡同批次重提交，无 channel+period 闸，V347 无部分唯一索引），月结 flowBySku 无条件 sum ⇒ 盘点/外调口径翻倍；COMMITTED 批次不可删，错数据无补救只能三倍重导 | ZCode R12批4 / 2026-11-03 | |
| MA-102 | P3 | fixed（留痕 + 调用点断言） | `POST /ops/admin/warehouse/transfers/{id}/cancel` | 调拨取消零审计零操作者（同单 create/ship/receive 三兄弟全有 WH_TRANSFER_* 审计，表亦无 cancelled_by）；状态机仅 DRAFT，改动零风险。收口：cancel 成功路径 verify 钉住 WH_TRANSFER_CANCEL/操作者/单号 | ZCode R12批4 / 2026-11-03 | |
| MA-103 | P3 | fixed（行锁读 + 真并发 IT，含反向对照） | 月结差异处置 + 效期建补货任务 | `POST /close-sheets/{id}/lines/{lineId}/disposition` 与 `POST /expiry/alerts/{taskId}/create-replenishment` 先读后写无锁无版本：并发双击可双挂索赔台账（两条 inventory_write_off）或双建路线任务；同域标准是锁+FOR UPDATE，本面漏配 | ZCode R12批4 / 2026-11-03 | |
| MA-104 | P3 | fixed（非空 + 非负写侧闸） | `PUT /ops/admin/inventory` | 直写柜机库存汇总零校验（无 @Valid，DTO 零注解）：负数 quantity 污染短缺计算、空 deviceId/skuId 造主键残行；lot 账本设备已被 sync 挡住，非账本老设备暴露 | ZCode R12批4 / 2026-11-03 | |
| MA-105 | P3 | fixed（收窄注解） | `POST /ops/admin/purchase-orders/{id}/review` | 注解 OR(edit, finance:view) 是死分支——服务端只认 ops:procurement:edit ⇒ 持 finance:view 过注解门后必 403（MA-070 双轨同族，fail-closed 无安全洞但误导权限配置者） | ZCode R12批4 / 2026-11-03 | |
| MA-106 | P3 | fixed（未知 lineId fail-closed 400） | `POST /ops/admin/warehouse/transfers/{id}/receive` | 收货登记未知/缺 lineId 的行被静默按「全部到齐」入账（reported map miss → 按发运量全额入库）——在途损耗黑洞恰是 V314 要堵的场景；应校验 lineId 全部 ∈ 本单行 | ZCode R12批4 / 2026-11-03 | |
| MA-107 | P3 | fixed（assertNotDoorCabinet + 设备存在 400） | `POST /ops/admin/slot-templates/{id}/apply/{deviceId}` | 套用模板绕过「开门柜无货道」写守卫（DeviceSlotService 同类入口都有 assertNotDoorCabinet，注释明说 fail-closed，此处漏配）；目标设备不存在时 FK 500 而非 400 | ZCode R12批4 / 2026-11-03 | |
| MA-108 | P3 | fixed（幽灵线长 400） | `POST/PUT /ops/admin/line-routes` | create 缺 routeName/managerId → PG NOT NULL 违约 500（应 400）；update 的 managerId 不校验存在性，幽灵线长进 monthlySummary 财务聚合 | ZCode R12批4 / 2026-11-03 | |
| MA-109 | P3 | fixed（binStockChange 入库方向同闸） | 「入库不落无主仓」闸门两旁路 | P2-8 治理闸只在 inbound/receivePurchaseStock/returnPurchaseStock 三处；binStockChange 原语被货位入库与调拨收货复用且不设闸，显式传无主仓照样进（缓解：ops:warehouse:edit 门内+可调拨救回） | ZCode R12批4 / 2026-11-03 | |
| MA-110 | P3 | fixed（remark 链截断到 255） | 维修工单/异常结案列宽链断裂 | repair_ticket.remark(512)/event.remark(255) 双落列，256~512 字关单必失败；resolveWithRepair 三级列宽 detail(1000)→remark(512)→event(255) 拼接后超限（MA-087 同族新点位） | ZCode R12批4 / 2026-11-03 | |
| MA-111 | P3 | fixed（改窄+留痕+注解反射钉测） | `DELETE /ops/feedback/{id}` | 用户反馈查看权限即可物理硬删且零审计（reply 反而要求严格 :reply 码；权限字典缺 delete 码）；无痕删用户输入 | ZCode R12批4 / 2026-11-03 | |
| MA-112 | P3 | fixed（marker 单源 IDEMPOTENCY_MARKER） | `POST /ops/admin/exceptions/{id}/retry` | 幂等 marker 格式错位：Controller 查英文 `idempotencyKey=`、Service 写中文「幂等键：」⇒ alreadyProcessed 恒 false，幂等承诺失效（同文件 manualResolve 用英文自洽反证非设计；下层 409 兜底故 P3）。收口：三处写入 + 控制器读取共用 `OpsExceptionService.IDEMPOTENCY_MARKER`，manualResolve 审计断言钉住 | ZCode R12批4 / 2026-11-03 | |
| MA-113 | P3 | fixed（assignee null 陷阱 + ticketIds 400） | `POST /ops/admin/repair-tickets/batch-assign` | `{"assignee": null}` 经 String.valueOf 变字面量 "null" 入库；ticketIds 非数字 NumberFormatException→500（应 400） | ZCode R12批4 / 2026-11-03 | |
| MA-114 | P3 | fixed（personOverride 60 / title 128 / remark 512 闸） | @Size 家族补点（批4b） | 外部导入 personOverride 无 @Size(列60)；维修单 title(128)/remark(512) 无 @Size——并入 MA-087/110 批次修 | ZCode R12批4 / 2026-11-03 | |

| MA-115 | P2 | fixed（钉测 2 例） | `POST /ops/admin/ota/releases` + `/{id}/unpublish` | OTA 固件发布/下架零审计零归因：operatorId 传而不用（OtaService.java:69-70 收下即弃）、表无列、连 log.info 都没有——推错固件全车队瘫痪的最高危动作出事无法回答「谁推的哪个包」；主会话复核 grep OtaService 全文 0 处留痕 | ZCode R12批5 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-116 | P2 | fixed（归因+追加） | `POST /ops/admin/consistency/{recordId}/fix` | 数据一致性修复零归因 + 修复结果覆写原始不一致描述（error_message 被覆盖=「当初为什么不一致」证据被抹）；fix 链直改订单头金额/清券/触发真实退款；类内 logChange 脚手架全仓零调用（出生即死）；修错了无法追责 | ZCode R12批5 / 2026-11-03 | |
| MA-117 | P3 | fixed（五服务 + shelf 全留痕） | 将邑运营写面 ~30 端点 | 审计留痕整体缺失（Onboarding/ClassMapping/ModelSync/Gather/SkuJiangyiLink 五服务 0 appendLog；含租户级 setTenantDomain 厂商云写、识别映射对位锚改写、deleteInJiangyi 不可逆删除）；同域 JiangyiDeviceOpsService 六处范本在，借它回补。修法：`JIANGYI_CLASS_MAPPING_*`、`JIANGYI_SKU_LINK_*`、`JIANGYI_GATHER_*`、`JIANGYI_TRAINING_*`、`JIANGYI_MODEL_PREGENERATE/ACTIVATE/PUSH` + 商户采集入口透传 userId；内部接口/回调按系统 null；三处 verify 钉测 | ZCode R12批5 / 2026-11-03 | |
| MA-118 | P3 | fixed（非整数 400 + 反向锁钉测） | `POST /ops/admin/device-rental/deposits` | 原始 Map 数值参数静默吞错：monthlyRefundCents 非数字被 catch 后静默 null=「无月退」、contractYears 落默认 5——押金月退链（真扣真退商户余额）关键参数静默缩水无 400 反馈（与 MA-094 裸 parse 是同族两端，一个炸一个吞） | ZCode R12批5 / 2026-11-03 | |
| MA-119 | P3 | fixed（钉测 1 例） | 设备 MQTT/HTTP 凭据签发 | 不校验 device_info 存在性：12 位数字手滑一位即签出孤儿活跃凭据（明文 secret 回显一次）；同场景将邑入驻有现成闸（registerForDevice 防孤儿），凭据签发漏配 | ZCode R12批5 / 2026-11-03 | |
| MA-120 | P3 | fixed（NaN/Inf 丢弃 + 三读数物理区间钳制） | `POST /internal/v1/devices/{id}/heartbeat` | 心跳环境读数零界：NaN/Infinity→BigDecimal.valueOf 抛异常→心跳 500（且在 presence 更新之后）；1e8 量级直落污染环境看板；同源 signal 0-4 静默丢、OTA pct clamp 先例在，本条漏网 | ZCode R12批5 / 2026-11-03 | |
| MA-121 | P3 | fixed（register 走 registerForDevice 闸；status/source 白名单） | JiangyiInternal 遗留入口 | /onboarding/register 缺「柜机档案存在」闸（运营面 registerForDevice 有，internal 直连底层无）；/class-mappings upsert 收自由串 status/source（"active" 小写成死行）；grep 证实无代码调用方（运维脚本入口）+fleet key 保护故 P3 | ZCode R12批5 / 2026-11-03 | |
| MA-122 | P3 | fixed（create/update 字段校验 + scope/priority 白名单） | `POST/PUT /ops/announcements` | 公告 create/update 裸 Map 零校验：title>256/priority 9+字符→22001 500；targetScope 拼错（"CONSUER"）静默落库发布成三端不可见公告（MA-078/084/087 家族新点位） | ZCode R12批5 / 2026-11-03 | |
| MA-123 | P3 | fixed（三端点留痕 + 重复发布幂等） | 公告 update/publish/archive | 零操作者归因零审计（operator_id 只在 create 落库，V220 加 FK 证明留痕是既定口径）；publish 无状态机：ARCHIVED 可复活、重复发布刷新 publishAt 重排消费端 | ZCode R12批5 / 2026-11-03 | |
| MA-124 | P3 | fixed（不重放+退款回执） | `POST /ops/admin/consistency/{id}/fix` 重放 | 无 status 闸：FIXED 记录可重放，fixInventoryMismatch 用记录快照旧库存值回写覆盖当前（脏窗口）；fixPaymentAmount 重放被 M02 幂等键吞掉却回「已退多收 X 分」假成功（refundOrder 返回值未检查，javadoc 明说必须检测） | ZCode R12批5 / 2026-11-03 | |
| MA-125 | P3 | fixed（409 预检 + channel 白名单 + gray/ttl 钳制） | `POST /ops/admin/ota/releases` | 入参缺口：重复版本撞唯一索引 500 而非 409；channel 自由串拼错（"stabel"）=发布静默不可见；grayPercent>100 无钳制；presignTtlSeconds 无上界（可配一年有效预签名 URL）；降级推送设备侧有兜底判部分刻意 | ZCode R12批5 / 2026-11-03 | |

| MA-126 | P3 | fixed（@Positive 默认消息先行翻译） | 提现等全仓 `@Positive` 字段的 400 文案 | R17 活栈实测：amountCents=0/-500 都回「amountCents不能为空」——根因 `@Positive` 默认消息 "must be greater than 0"（严格大于）不匹配 ApiMessages 的 RANGE_MIN_PATTERN（只匹配 "greater than or equal to" 形式）落兜底误报；2026-10-23 生产实证修过 @Min/@Max 同款（代码注释在位），漏了 @Positive 形式，误导性完全相同 | ZCode R17批1 / 2026-11-03 | rounds/R17-runtime-flows.md |

| MA-127 | P2 | fixed（三端活动优惠行铺齐） | 活动优惠行三端铺开不齐 | activityDiscountCents 在 merchant-mp **全端 0 引用**、admin 订单明细/差额说明无活动行；admin 列表优惠列却含活动（05ec42c8）⇒ 同一活动单 consumer 详情能自证而 merchant 明细「已扣−会员≠实收」、admin 列表与自家明细对不上；MA-023/024 修复面只铺了 consumer | ZCode R16 / 2026-11-03 | rounds/R16-cross-end-static.md |
| MA-128 | P2 | fixed（已扣/实收改恒等式） | merchant-mp 订单详情「已扣/实收」 | 取 originalAmountCents：部分退后该列被重写为「剩余折后行合计」，前端再减一次 refunded ⇒ 半退单显示「实收 ¥0.00」（真实净收 ¥1.21）；且与钱包/分账基数 paidAmountCents 同单不同数（3.43 vs 2.43）；主会话已复核前端代码 | ZCode R16 / 2026-11-03 | |
| MA-129 | P2 | fixed（拍板②：剩余合计行，零后端字段） | 部分退款单 consumer 优惠拆解自证 | MA-049 把 member/activity 等比缩而 gross 不缩（V424 契约前提是优惠不缩）⇒ 半退单「合计−各档=1.22」缺口 1.75 而退款行只交代 1.21，剩 0.53（被缩掉的优惠）无行解释——MA-024 页面承诺「用户自己能算平」在所有部分退优惠单失效；需与巡检折后式一起拍板 | ZCode R16 / 2026-11-03 | |
| MA-130 | P2 | fixed（改走 restock_line_type 字典） | merchant-mp `pages/tally-records` 理货记录 | 整页裸英文：lineTypeLabel 只认 **PUT_ON**（页面发明的幽灵键，后端全仓 0 处写 PUT_ON、实际写 RESTOCK）⇒ 配货 Tab 每行 chip 显示 "RESTOCK"；taskStatus 只译 COMPLETED，PENDING/IN_PROGRESS/CANCELLED 裸码；主会话已复核 | ZCode R16 / 2026-11-03 | |
| MA-131 | P2 | fixed（VOIDED→VOID + 新字典类型 V436） | admin `DeviceRentalView` 租赁账单 | 本地 map/筛选键 **VOIDED** ≠ 后端常量 FEE_BILL_STATUS_VOID=**"VOID"** ⇒ 作废账单状态列裸码且「已作废」筛选永远查空；UNPAID 同族字典叫「待付」此处叫「未付」两词；主会话已复核 | ZCode R16 / 2026-11-03 | |
| MA-132 | P2 | fixed（V436 补两值） | wallet_entry_type 字典 | 缺后端真实写入的 COMMISSION_CLAWBACK/WITHDRAW_FEE（基线+DB 双缺）⇒ admin 资金页流水显示「未知」、merchant-mp 靠 remark 兜底显示中文——同流水两端不同词（V379 迁移注释还把 CLAWBACK 列入幂等白名单，字典却没回填） | ZCode R16 / 2026-11-03 | |
| MA-133 | P2 | fixed（entryType 改字典；WITHDRAW_PAYOUT 由 V436 补） | admin `LineRoutesView` 钱包流水弹窗 | entryType 裸码直出（STAFF_COMMISSION_DAILY/WITHDRAW_PAYOUT 等）；同数据 LineManagerView 走字典出中文——staff 系枚举从未入任何字典 | ZCode R16 / 2026-11-03 | |
| MA-134 | P2 | fixed（V436 补两值 + 自愈目录） | purchase_order_status | MA-031 同构复发：SysDictBootstrap 仅 4 值，缺在写的 PENDING_APPROVAL/REJECTED（ensureMissingDictItems 永不自愈）；admin「待审批」vs merchant-mp「待审核」两词，mp fallback 把未知值显示「处理中」 | ZCode R16 / 2026-11-03 | |
| MA-135 | P2 | fixed（V436 补两值 + 自愈目录新增该类型） | notification_biz_type | 缺后端真实下发的 DAILY_INCOME/FEE_REMINDER（每日批量落库的高频行）⇒ admin 站内信记录页整片「未知」、merchant-mp 本地 map 显示中文；写入点新增值没回填字典与 MA-132 同模式 | ZCode R16 / 2026-11-03 | |
| MA-136 | P2 | fixed（盘点状态改字典单源） | stocktake_status | 同值两套词：admin 走字典（草稿/盘点中/已调整）vs merchant-mp 本地 map（待录入/录入中/已过账）+ 未知值裸码兜底；违反 shared-dict「前端不得自行改文案」既定口径（expiry_urgency 曾为此立 lint 红线） | ZCode R16 / 2026-11-03 | |
| MA-137 | P3 | fixed（OrderReadModel 补 grossAmountCents） | shared-types 手写 OrderReadModel/OrderSummary | 缺 activityDiscountCents/grossAmountCents（生成物有）⇒ consumer 订单列表要补活动标签时 TS 无字段可用（MA-127 列表漏活动的直接诱因）；建议补字段或切 OpenApi 别名+加「手写接口 vs 生成物 diff」门禁 | ZCode R16 / 2026-11-03 | |
| MA-138 | P3 | fixed（门禁收编 composables/*.ts，3 处手算改 centsToYuanNumber） | check-no-inline-money-math 门禁盲区 | 只扫 views/components 的 .vue，composables/*.ts 残留 3 处手算（useWarehouseEntityDialogs.ts:102,226,266）不受棘轮约束；DIRS 加 composables 收编 | ZCode R16 / 2026-11-03 | |
| MA-139 | P3 | fixed（admin 费用列三态：—/金额/（免收）） | 提现 feeCents 空态两口径 | merchant「—（未结算/免收可区分）」vs admin `feeCents || 0` 显示「¥0.00」——null 被抹平后「未结算」与「免收」在 admin 不可区分；admin 费用列改三态对齐 | ZCode R16 / 2026-11-03 | |
| MA-140 | P3 | fixed（SPLIT_REV 入基线/Bootstrap/V437） | wallet_ref_type 缺 SPLIT_REV | 后端真实写入（RevenueSplitService:473）但基线/DB 均无 ⇒ admin 显示「关联/未知」vs mp 本地「分账冲正」同流水两词 | ZCode R16 / 2026-11-03 | |
| MA-141 | P3 | fixed（SPLIT_REVERSE 三源统一「分账冲正」，V437 带守卫 UPDATE） | wallet_entry_type.SPLIT_REVERSE 措辞漂移 | DB/迁移「分账退回」vs shared 基线「分账冲正」——runtime 加载状态不同则显示不同词（低频窗口）；择一统一（DB 侧改需幂等 UPDATE，参照 V415 范式） | ZCode R16 / 2026-11-03 | |
| MA-142 | P3 | fixed（shared-dict 注释钉住分受众话术差异） | 站内信 bizType 三端三词 | DISPUTE：consumer「售后」/merchant「争议」/字典「争议」；活动 vs 用户召回混用——分受众话术可理解但未登记为「有意差异」，对齐时无据可查；至少 shared-dict 注释钉住 | ZCode R16 / 2026-11-03 | |
| MA-143 | P3 | fixed（基线补齐 14 值，与 DB 33 值对齐） | exception_type 基线缺 15 值 | DB 33 值 vs 基线 19 值（G7 补集+将邑码只落 DB）；admin 靠 runtime 正常，runtime 未加载首屏 ExceptionListView 落「未知」；双 mp 不直接渲染故 P3 | ZCode R16 / 2026-11-03 | |
| MA-144 | P3 | fixed（4 类型补基线） | 反向缺：DB 有基线无的 4 类型 | sku_operation_scope/ops_fee_type/device_fault_code/risk_disposition_status——runtime 未加载时 SkuListView 首屏闪「未知」；违反 shared-dict 契约后半句「then seed here」 | ZCode R16 / 2026-11-03 | |
| MA-145 | P3 | fixed（10 类型入 seedCatalog + V437 回填 DB） | 基线独有 10 类型从未种子 | balance_refund_status/user_role/wallet_ref_type/wallet_ledger_type/consistency_diag_code/merchant_settlement_bill_status/close_sheet_*/expiry_urgency——字典管理不可见、runtime 常态性缺类型（结构性欠账，暂无可见故障） | ZCode R16 / 2026-11-03 | |
| MA-146 | P3 | fixed（批次 O 收口：CLOSED 死值删+幽灵选项 IT、enable_status 基线收 3 值、feedback 未知态显「—」） | 死字典值与局部内联漂移 | repair_ticket_status.CLOSED 状态机写不出（幽灵选项）；enable_status 基线冗余 DRAFT/STOPPED；AdAssetsView 内联三元与同页字典混用；MerchantOnboardingView PAYSCORE→支付分 vs 字典「微信支付分」；consumer feedback 未知状态兜底「处理中」掩盖 | ZCode R16 / 2026-11-03 | 活 dev 库仍留 CLOSED 行（未对共享栈跑迁移），新库/CI 已由 V438 删除 |

| MA-147 | P2 | fixed（显式清列+矩阵真库断言，门禁豁免已撤销） | 消费端全额退 `POST /orders/{id}/refund`（无 lines） | 全额退**不返还已核销券**：waive 路径（SettlementWaiveRefundService.waiveAndRefund）全文 0 处券处理，券返还（restoreUsedToUnused+活动预算释放）只接在比例退路径（CouponService.settleAfterPartialRefund:573-583）；R17 矩阵 E2E 实测全额退后 user_coupon 仍 USED（MemberOrderChain 同库可见）；券面额随退款被吞且活动预算不释放；类注释无「刻意不返还」声明 | ZCode R17批2 / 2026-11-03 | rounds/R17-runtime-flows.md |
| MA-155 | P3 | fixed（dfb794b9：desc 改「领货上柜」+ merchantNav 入 selfpickup 门禁白名单/BANNED/desc 断言） | merchant-mp `pages/mine` 导航「发起补货」描述 | 菜单描述仍写「缺啥补啥，直接生成**分仓出库**与补货任务」（`config/merchant-nav.ts:197`，经 `mine.vue` 的 `menu-desc` 直接渲染），与 2026-10-29 拍板的「补货＝自提自送；领货/上柜/领而未上」口径不一致——同批已把提交提示里的「分仓出库」删掉，导航描述漏改 | Delta R18 / 2026-11-04 | 铁律 80；`UI_STYLE_UNIFICATION_PLAN.md` Phase 4「补货措辞对齐」条。静态可证（常量直出 UI）；**已补实跑取证**：商户端模拟器 `pages/mine` 全文匹配命中该文案（298×14px，offset 50/482），截图 `.tmp/three-end-delta-20261104/mp-shots/merchant/_ma155-mine-menudesc.png` |
| MA-157 | P2 | **fixed（静态判据 + 构建产物双向实证 2026-11-06；runtime 复测待 DevTools）**：横向改用 `min-width: 88rpx`（不是负 inset——这些 chip 横向相邻且 `gap` 仅 8rpx，负 inset 会让相邻热区互相重叠，比小热区更糟），纵向补 `min-height: 32rpx` 与伪元素 56rpx 合计 88 ⇒ 两条边都可证；settlements 页原先零 padding（实测 43.1px 高）已补 `padding: 8rpx 4rpx` 对齐 orders 配方 | merchant-mp `pages/orders` / `pages/settlements` / `pages/cabinet-reports` 的 `.aux-link` | 时间筛选（近7天/近30天…）与「隐藏零元单」这类 `role="button"` 文本链接触控高度实测 **14px / 22px / 31px**（源码只给 `padding:8rpx …`，无 `::after` 扩展），远低于项目自定 88rpx（≈45.7px）；消费端同形控件实测 **45px** ⇒ 同一交互两端差 2–3 倍。与 `UI_STYLE_UNIFICATION_PLAN` Phase 4「aux-link 已改 88rpx 热区 + 小视觉、small=0」的记载**冲突**（那次清点未覆盖这三页） | Delta R18 / 2026-11-04 | 实测 `.tmp/three-end-delta-20261104/mp-elements-merchant.json`（heatSmall=4）。同批的 `.help-tip` 16px 判为假红：真命中层是 88rpx 的 `.help-tip-hit` 包裹层，`.help-tip` 只是内部图标。**2026-11-04 复核**（`build:mp-weixin:dev` 重编 dev 产物后 automator 实测，8 样本）：全部时间 48×51.1 PASS｜今天 26×51.1｜近7天 32×51.1｜近30天 39×51.1｜零元单(orders) 37×51.1｜零元单(settlements) 33×**43.1**｜商品/毛利 50×60.1 PASS ⇒ 纵向已解决，横向与 settlements 页纵横均未达标；证据 `.tmp/three-end-delta-20261104/ma-verify.json`。**2026-11-06 收口**：改的三处都落进 release 产物（`dist/build/mp-weixin/pages/{orders,settlements,cabinet-reports}/*.wxss` 内 `min-width: 88rpx` 逐页 grep 到）；门禁侧 merchant 两条 `.aux-link` 从「不可验」出档 ⇒ `TOUCH_UNVERIFIABLE_BASELINE` **219→217**（同笔下调，脚本对 216/218 两个方向都判红，非空转）。判据用门禁自身导出函数验过四种样式：改后 `null`、改前 `unverifiable`、`min-width:40rpx`/`min-height:20rpx` 均 `undersized`。**真机复验已完成**（2026-11-06，微信开发者工具 automator，`dist/dev` 重编后实测，windowWidth=390 ⇒ 88rpx=45.76px）：orders 5 个 chip **49×53.1**、settlements 零元单 **49×53.1**（原 33×43.1）、cabinet-reports 商品/毛利 **69×60.1** —— 全部 PASS；相邻 chip 间距 53 > 49 ⇒ **热区零相交**。脚本 `.tmp/ma157-runtime2.mjs`（只读；有效热区 = `el.size()` + 该页 WXSS 的 `::after` 负 inset） |
| MA-159 | P2 | fixed（2026-11-04 判据重写：单边可证即判 + inset 按边展开 + 动态类前缀，见 PK §12「修 MA-159」；**2026-11-06 独立复验**：jy-v16 合入的新采集页 `.mini`/`.submit` 因无显式宽高被计入「不可验」档、把基线顶到 221>219 而挡住推送 ⇒ 老问题「靠 padding/内容撑开恒放行」确已消失） | `scripts/check-mp-touch-target-ratchet.mjs` 的 `isViolation()` / `INSET_NEG_RE` | 热区门禁对「靠 padding/内容撑开」的可点元素**结构上不判**：`isViolation` 首行要求显式宽且显式高，否则 `return false` ⇒ `.aux-link`（解析得 `{hit:28}`，无 width/height）、`.app-btn--sm`（`{hit:8,minHeight:72,height:72}`，**有高无宽**）恒放行。故 dfb794b9 所称「热区门禁补扫 `shared-uni/components` 关闭 MA-156 盲区」实测**判定收益为 0**（用门禁自身导出函数解析真实文件，加不加 `::after` 都是 false）。另 `INSET_NEG_RE` 只捕获 `inset` 第一个值，`inset:-28rpx 0`（只扩上下）被当四向 28rpx ⇒ 即便日后补了显式宽高，横向会被**高估 56rpx** —— 这正是 MA-157 残留能通过门禁的原因 | Delta R18 复核 / 2026-11-04 | 复现：`node --input-type=module -e "const m=await import('./scripts/check-mp-touch-target-ratchet.mjs'); ..."` 解析 `app-button.vue` / `settlements.vue`，见 2026-11-04 日志。**2026-11-06 修 MA-157 时发现同类新盲区（未修，需拍板）**：脚本第 376 行对 `.css` 文件一律给空可点集，而每页只抽 `<style>` **内联**块；17 个页面用 `<style scoped src="./x.page.css">` 外置样式 ⇒ 这些页面的可点类**一条都不进判据**（cabinet-reports 的 `.aux-link` 实测就在门禁两档清单里都找不到）。收口它（展开 `src=`/`@import` 再解析）会让「不可验」档一次性从 217 跳到更多（量尺 `.tmp/measure-src-css-blindspot.mjs` 实测：**新增 87 条不可验 + 另有 4 条 undersized**），属基线重定 ⇒ **2026-11-06 用户拍板「按推荐」已收口**：`collectCssFor` 展开 `<style src>` 与 `@import`（按引用者目录解析、深 ≤5 去环、缺文件不崩），实测 **不可验 217→303、小热区仍 0**，基线同笔重定并在脚本内写明「这是判据变严后的重定，不是放宽」；自检新增 4 条（src 指向 40rpx⇒undersized／88rpx⇒合规／@import 链同抓／缺文件返回空），且 302 与 304 两个方向都 exit=1（只减不增 + 腐烂强制收紧）。**那 4 条真小热区已于同日修掉**（2026-11-06）：merchant-mp `prep-generate`/`prep-list` 的 `.step-btn` 与 `purchase`/`self-replenish` 的 `.qty-btn` 原为 48×48rpx（24px），改为「视觉 60rpx + `::after inset:-16rpx`」⇒ 两轴 92rpx；几何逐页核过才敢外扩——行间靠 `.line-row{padding:16rpx 0}` 撑出 32rpx 空档，与相邻行扩展**恰好相接不重叠**；横向靠 `.stepper gap:10rpx`／`.qty-box gap:8rpx` + `.qty-val` min-width 92/40rpx，两侧各进数字框 ≤12rpx，两按钮扩展区不相遇。🔴 **第一版取「56rpx + 16」= 恰好 88rpx，被真机否掉**：实测有效热区 45.6×45.6 < 45.76（88rpx@390px），亚像素舍入把边界设计吃穿；改 60rpx 后实测 **47.6×47.6（qty）/ 48.6×48.6（step）PASS，相邻零相交**（行距 57–64px > 热区高）。四页 release 产物的 `:after`（uni-app 已展开成 top/right/bottom/left）逐页 grep 到；收口后这些类**已进门禁判据**（可证 ⇒ 不再是「看不见」） |
| MA-156 | P3 | fixed（dfb794b9：app-button sm/compact 伪元素 +8rpx（72+16=88）+ 门禁补扫 shared-uni/components；负向门禁沙箱清单同步 31 判据复验能红） | 双小程序 `packages/shared-uni` `app-button` sm/compact 变体 | 可点按钮本体 72rpx（消费端模拟器实测 **37px**）低于项目自定 88rpx 热区阈值，且该变体**没有** `::after` 负 inset 扩展（`app-button.wxss` 里只有 `.app-btn:after{border:none}`）；而 `check-mp-touch-target-ratchet` 报「小热区 0/0（扫 95 文件）」⇒ 这一类「组件内尺寸变体」不在门禁判据覆盖面内。全仓 `<app-button compact>` 实测 **10 处**（recharge 1 / device-detail 4 / device-stocktake 5；另有若干 `compact` 用在别类组件上） | Delta R18 / 2026-11-04 | 实测页 `consumer-mp pages/recharge`「提交申请」；判据同 Phase 4「可点元素 <88rpx 清单」。修法二选一：给 sm/compact 补 `::after` 扩展，或把门禁判据扩到组件变体类 |
| MA-160 | P1 | fixed（`d7b37278`：两条渠道腿与余额腿共用单源 `remainingChargeCents`；差额≤0 不打渠道；痕迹行按实扣额） | 消费者补缴 `POST /orders/{id}/pay`（结算页选微信/支付宝）+ 自动决策渠道腿 | **「余额+渠道」组合腿重复扣**：PENDING 单若已被钱包预授权冲抵 N 分，`submitChannelCharge` 仍按 `totalAmountCents` 全额发起渠道扣款并记同额 CHARGE 流水 ⇒ 用户实付=冲抵+全额（钉测：冲抵 60 + 渠道 100 vs 应付 100），`freezePaidAmount` 随之把 160 冻成佣金基数；`markPaid` 的净额三分支只拦了「≥全额」那一档，其注释「补扣只收差额」在渠道腿从未实现（`applyBalanceCharge` 才减净额） | Qoder R17批3收口 / 2026-11-05 | 复现即红：`OrderPaymentChargeInvariantTest.partialCaptureThenWechatCollect_netEqualsTotalAndChannelChargesRemainder` 修前 `expected: <40> but was: <100>`；修后该类 6/0、trade-service 全量 2762/0。原判据链见 rounds/R17-runtime-flows.md 批 3 段 |
| MA-161 | P1 | **fixed（A1+A2 已落；剩活栈复跑属运维前置，不是代码缺口）** | 组合单退款 `OrderPaymentService.prepareOrderRefund` → 微信/支付宝原路退 | 退款只按 `order.payChannel` 把整笔压回**单一**渠道：组合单（钱包冲抵 60 + 微信 40）全额退时 refund=100 打在只收 40 的微信交易上 ⇒ 被网关拒（MA-160 修前微信确实收了 100 所以退成功，但钱包那 60 永远不退＝静默少退用户）。即修法把「静默少退」变成「显式失败」，闭合还需按渠道拆分：渠道退 `min(退款额, 该渠道实扣)`，其余退余额 | Qoder R17批3收口 / 2026-11-05 | `OrderPaymentService.java:774-786`（MA-161 拆腿判定与 `planRefundLegs` 消费点）、`:1354-1360`（`resolveOriginalChargeTotalCents`）〔行号 2026-11-06 现取：A2 与合流 `129dceaa` 之后整体后移，旧值 `745-755`／`1274-1287` 已失效，勿照抄〕；🔴 **口径订正**（2026-11-06 复核，旧写法与现码不符）：原记「取 CHARGE+ADJUST 合计，天然不含 `PREAUTH_CAPTURE`」错——`channelChargeBreakdown:1374-1376` 把 `CHARGE + ADJUST_CHARGE + PREAUTH_CAPTURE` **一并**计入并按 `op.getChannel()` 分组，而冲抵行由 `BalanceLedgerService.java:151` 写成 `channel=BALANCE` ⇒ 预授权冲抵不是被排除项，**它就是钱包腿的资金来源**，组合单退款能兜住那 60 靠的正是这一点；钉测已补齐并实跑（2026-11-06 09:06 全量 `mvn verify` 产物）：`OrderPaymentRefundLegsTest` **9/0** + `OrderPaymentIdempotencyTest.comboRefund_splitsChannelLegAtActualChargeAndWalletLegForTheRest` + **`OrderPaymentComboRefundLedgerSqlIT` 2/0（真库 23.77s，四层对账：渠道实扣/钱包冲抵/退款上限/痕迹行合计==退款额==refunded_cents，并钉「重试复用同一幂等键」）**；A2 期间另修掉两个自造缺陷（重试换键⇒网关双退、余额腿重复累加 refunded_cents），见 `8382d814`。 |
| MA-162 | P2 | **fixed（2026-11-06 五批全清，门禁 303→285）** | merchant-mp 热区门禁「不可验」档里、被真机证实 < 88rpx 的可点控件（此前因外置样式盲区**根本测不到**） | 门禁收口外置样式后（`15a58db8`，不可验 217→303），把新增的 118 条 merchant 类逐页真机量了一遍：**25 条有效热区 < 45.76px**。剔除状态类/无动作容器（`.is-off`/`.is-primary` 是同一元素的状态类、`.qty-box`/`.group-head` 只挂了 `@click.stop`）后约 12 条真候选 | 2026-11-06（DevTools automator，dist/dev，windowWidth=390 ⇒ 88rpx=45.76px） | 实测（本体 ⇒ 有效热区）：`disputes .reply-btn` 44×22（32 处，最广）｜`orders .filter-reset` 34×22｜`devices .nav-link`/`.app-link-chevron` 38×16、`.tool-item` 191×38｜`home .section-more` 62×16、`.app-link-chevron` 62×17、`.ov-item` 88×40、`.scan-btn` 52×45（差 0.76px）、`.home-notice` 366×38｜`mine .danger-cell` 366×43｜`prep-list .tab` 177×32、`.tool-link` 44×14｜`prep-generate .group-head` 343×38｜`pricing .history-btn` 68×25｜`replenishment .scan-primary`/`.clear-pill` 358×45（差 0.76px）｜`self-replenish .hint` 44×14。**修法不能一刀切**：纵向负 inset 受行距约束（`.line-row` 32rpx 空档 ⇒ 每侧 ≤16rpx），横向受相邻控件 gap 约束（MA-157 的教训）；每条须先核容器几何，再在「抬视觉尺寸 / 加 min-width·min-height / 不对称 inset」里选，并用 `.tmp/ratchet-303-runtime.mjs` 复测「≥88rpx 且相邻零相交」。优先级建议：`.reply-btn`（32 处）→ `.filter-reset`/`.history-btn`/`.tool-link`/`.hint`（文本链接族，可复用 aux-link 配方）→ 其余按页面批次。✅ **第一刀已落（2026-11-06）**：`disputes .reply-btn` 补 `min-width: 88rpx` + `min-height: 48rpx` + `::after inset:-22rpx 0` ⇒ 门禁不可验 303→**302**（写 301 判红），真机实测 32 处全 **67×54.9 PASS、零相交**（原 44×22）；几何依据：`.card-foot` 上下无相邻可点元素、左侧 `.card-id` 是 `flex:1` 的不可点文本，两侧扩展不抢点击。✅ **第二刀（2026-11-06）**：prep-list `.tool-link`（手动生成，44×14）与 self-replenish `.hint`（刷新建议，44×14）同样补 `min-width` + `min-height: 48rpx` + `inset:-22rpx 0` ⇒ 门禁 302→**300**；真机实测均 **49×46.9 PASS**。🔴 两处 `min-width` 都取 **96rpx 而非 88rpx**：88rpx 在真机被取整成 **45.0px**（< 45.76）——与步进器同一个亚像素坑，凡「恰好等于阈值」的设计都必须留余量。✅ **第三刀（2026-11-06）**：`orders .filter-reset` 34×22 ⇒ 真机 **49.6×47 PASS、与 aux-link 和导出按钮零相交**（左沿 343.1 vs aux 右沿 342.0，留 1.1px）；门禁 300→**299**。这条最难：它在筛选行**第二行**，上行 5 个 aux-link 的热区向下多探 14.6px、下方 7.8px 就是整宽导出按钮 ⇒ 只能向上/向右扩（`inset: -36rpx -28rpx -12rpx 0`）。🔴 第一版用 `min-width` 失败：`min-width` 作用在**内容盒**上，配合 `margin-left:auto` 把盒子整体左移 12px，正好撞进 aux-link 热区；正解是 `box-sizing: border-box` + 固定 `width: 68rpx`（≈原视觉宽）——宽度可证且盒子不位移。✅ **第四刀（2026-11-06）**：`pricing .history-btn` 68×25 ⇒ 真机 **67×47.9 PASS、与柜机选择器零相交**；门禁 299→**298**。这条最简单——**宽度本来就达标**，只差高度；上下是卡片内边距与 44px 卡间距、无可点元素 ⇒ 只补纵向（`min-height: 48rpx` + `inset:-22rpx 0`），横向一点不扩（左侧 8px 外就是可点的选择器）。✅ **第五批（2026-11-06，一次性 11 处）**：devices `.tool-item`/`.nav-link`、home `.section-more`/`.ov-item`/`.scan-btn`/`.home-notice`、mine `.menu-cell`+`.danger-cell`、prep-generate/prep-list `.group-head`、prep-list `.tab`、replenishment `.scan-primary`/`.clear-pill` ⇒ 门禁 298→**286**（12 类出档）。真机实测：`.nav-link` 49×24+纵向 22rpx ⇒ 46.9；`.section-more` 50/76×24 ⇒ 46.9；`.ov-item` 88×41 ⇒ 49.3；`.scan-btn` 52×49；`.home-notice` 366×55；`.tool-item` 191×57；`.menu-cell` 54 ⇒ 56.1、`.danger-cell` 45.0 ⇒ 47.1；`.scan-primary`/`.clear-pill` 抬到 96rpx ⇒ 49.9。🔴 过程中纠正一次自造回退：`.menu-cell` 一开始把 `inset` 从 `-2rpx` 加到 `-8rpx`，但菜单行**行距=行高、无间隙**，会让相邻行热区重叠 8px ⇒ 改为「inset 保持 -2rpx、min-height 84→88rpx」。✅ **第六刀（2026-11-06，用户「按推荐」）：`.star` 也已收**——给它一个**真实占位**的 96rpx 槽位（`display:inline-flex` + `justify-content:flex-start` ⇒ 字形靠左、视觉不位移）+ `min-height: 40rpx` + `::after inset:-28rpx 0` ⇒ 两轴 96rpx。真机 3 处全 **49.0×49.1 PASS**；门禁 286→**285**。取舍说明：星星在整卡可点的 `device-card` 内，其上下约 15px 环形带归开关（点那里不再进详情），这是嵌套小控件的固有取舍，按「开关优先」定。至此 MA-162 真候选 12 项全清（`.is-off`/`.is-primary`/`.qty-box`/`.app-link-chevron` 是同类元素的别名或 marker 类，非独立控件）|

| MA-163 | P2 | fixed | **已修（2026-11-07）：application.yml Hikari 双层兜底——`connection-init-sql: SET default_transaction_read_only=off`（新建连接复位）+ `max-lifetime=30min`/`idle-timeout=5min`（污染连接最长存活 30 分钟自然淘汰，间歇写失败窗口从无限期压到分钟级）。复验：readOnly 扫描与写事务交替 3 轮无 25006+写冒烟 ✓。根治仍以定位漏复位的 readOnly 长事务为准（117 文件 486 处 readOnly，暂无定向证据）**；历史记录：全局写路径（Hikari 连接池） | **间歇性「cannot execute INSERT in a read-only transaction」（25006）**：UAT 实测两轮窗口（21:05-21:37、22:07-22:19）内所有 app 写随机失败（用户开门失败/consent 日志写失败/购物失败），psql 新连接与重启 trade 后立即恢复；两次重启清池均止血。疑=某 readOnly=true 事务归还连接未复位（486 处 readOnly 事务，Hikari 未配 connection-init-sql 复位），或 Testcontainers 并行压测时的状态串扰；生产同代码⇒存在同型间歇 500 风险 | ZCode UAT R1 / 2026-11-07 | rounds/R1-main-chain.md |

## 待复核线索（未入账，实跑复现后转 MA 编号）

### 线索 #1：供应商对账单页面疑似不可达（R4 复核）→ ✅ 已实证并收口（D28-B15，2026-10-29）

- **来源**（当前代码，非旧文档）：
  - `clients/admin-vue/src/router/index.ts` 有路由 `warehouse-reconciliation`（`/warehouse/reconciliation`，标题「供应商对账单」，meta 无 `perm`）；
  - `clients/admin-vue/src/config/menu.ts` 的 `BASE_NAV` **没有** `warehouse/reconciliation` 条目；
  - `router/index.ts` 的 `beforeEach`：`!nav && !metaPerm` → 一律重定向 `forbidden`（fail-closed），而 `findNavByPath('/warehouse/reconciliation')` 只查 `NAV_ITEMS`，查不到。
- **实跑证实（2026-10-29，Playwright + 超管 13900000001）**：直链 `/admin/warehouse/reconciliation` → `forbidden?from=...`，页面 0 个元素；且全站 grep 无任何入口链到该路由——整页死路，**P1 成立**。
- **额外实测发现**：即使补登记 NAV_ITEMS，挂后端码 `ops:procurement:list` 仍 403——`rbac/me/nav` 的 ACTIVE 菜单表（95 行）没有任何行用该码，`isNavMenuActive` 恒 false；须挂已在 ACTIVE 表里的码（兄弟子页口径 `ops:warehouse:list`）。
- **修复**：见 [admin-vue-debt-tracker.md 十五期 D28-B15](../engineering/admin-vue-debt-tracker.md)（F5 批次）：NAV_ITEMS 补登记 + 菜单码 `ops:warehouse:list`，页内「生成对账单」由 `canList`（=后端注解码 `ops:procurement:list`）收口；Playwright 复测页面可达。R4 无需再开 MA 条目。

## 明细区块（每条一节，追加在文件末尾）

<!-- 模板：
### MA-xxx 标题
- 级别/状态：P1 / open
- 端与页面：admin `/orders`（订单管理 → 退款弹窗）
- 发现人/日期/HEAD：xxx / 2026-10-xx / `<sha>`
- 环境：本地 docker（trade:18080，admin 产物 <sha>）/ 开发者工具 mp-weixin
- 复现步骤：1) … 2) … 3) …
- 期望 / 实际：… / …
- 证据：evidence/MA-xxx-1.png（截图/请求响应/控制台）
- 源码定位：`path/to/file.ts:123`
- 联动影响：consumer-mp `order-detail` 显示 …
- 关联：AU-xxx / D-xx（旧编号，仅线索）
- 修复记录：（fixed 时填：改动、验证方式、门禁结果）
-->

### MA-001 用户分析「30 天」口径 KPI 随所选窗口漂移（active30/new30 与 active7 同源）
- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R1；修复锚 `43a4efe0` + 产物 `c3e0e44d`）
- 端与页面：admin `/user-analysis`（根因在后端 UserBehaviorAnalyticsService）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：本地 docker（trade:18080），运营超管账号
- 复现步骤：
  1) GET `/api/v2/ops/admin/growth/user-analysis?days=7`（库内两真实用户 last_order=2026-10-16/10-22，均在 30 天窗口内、7 天窗口外）→ `activeUsers7d=0, activeUsers30d=0, newUsers30d=0`，真值 active30=2、new30=2；
  2) `?days=90` 并回填 22 个 last_order=2026-08-20 的订单后探针 → `activeUsers30d=24`（真值 2，把 90 天窗口内活跃全计入「30 天」）；
  3) UI 近 7 天档 KPI 卡「活跃用户 0 / 30 天 0」。
- 期望 / 实际：`activeUsers30d`/`newUsers30d` 恒为近 30 天口径（标签写死「30 天」）/ 实际随 `days` 参数同窗：days<30 漏人（0），days>30 虚增（24）。
- 根因：`computeWindowMetrics` 只遍历 `windowOrders`（days 窗口），`active30++`/`active7++` 在同一循环对同一批用户计数 ⇒ active7≡active30≡「days 窗口活跃数」；`new30` 虽用 `since30` 比对首单，但迭代集仍是 days 窗口订单，days<30 时漏人。
- 证据：evidence/R01-useranalysis-active30-window-7d.png（近 7 天档 KPI=0/0）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/UserBehaviorAnalyticsService.java:106-140`
- 联动影响：运营按「30 天活跃」判断大盘规模时，选 7 天档会读成 0（误判无活跃），选 90 天档读成 24（虚增 12 倍）。
- 修复记录：2026-10-31 fixed（commit 见 R1 批次）。`computeWindowMetrics` 改三窗口独立计数（7d/30d 固定 + days 窗口），订单查询取 `min(sinceWin, since30)` 并集；钉测 `summary_days7_active30AndNew30_stayFixed30dWindow`、`summary_days90_active30_notInflated_andDormantList_notTruncatedAt20`（UserBehaviorAnalyticsServiceTest，4/4 绿）；活栈容器重建后探针 days=7/30/90：active30 恒 2、new30 恒 2、orders 随窗口（0/14/14）。口径依据 CB-081。

### MA-002 沉睡名单静默截断 20 条，导出与召回弹窗按截断值显示
- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R1；修复锚 `43a4efe0` + 产物 `c3e0e44d`）
- 端与页面：admin `/user-analysis`（沉睡用户表格 + 导出沉睡名单 + 召回弹窗）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：本地 docker（trade:18080）；回填 22 个 30-90 天沉睡用户（user_id 1990000001-22，已清理复原）
- 复现步骤：
  1) 回填 22 个 last_order=2026-08-20 的 PAID 订单+用户（`cabinet_order` 触发器 cutoff 2026-09-10 之前免 inventory 断言）；
  2) UI 沉睡 KPI=22，表格仅 20 行、无「仅显示前 20」提示；
  3) 点「导出沉睡名单」→ CSV 仅 20 行（前端 `s.dormantUsers` 已被截断）；
  4) 召回弹窗「目标人数 20 人」（真实 22）。
- 期望 / 实际：导出/展示口径与 KPI 一致（22 人），或明示截断 / 实际静默截 20。
- 根因：`UserBehaviorAnalyticsService.summary` 中 `if (dormant.size() > 20) dormant = dormant.subList(0, 20)`（dormantTotal 传全量，列表截 20，前端无从得知全量）。
- 证据：evidence/R01-useranalysis-dormant-truncated-30d.png、evidence/R01-useranalysis-recall-dialog-7d.png
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/UserBehaviorAnalyticsService.java:55-58`；前端导出 `clients/admin-vue/src/views/growth/UserAnalysisView.vue:257-269`
- 联动影响：召回不受影响（后端 `UserRecallService` 自行取全量、上限 1000），但运营按弹窗「20 人」核对发放数会对不上；导出名单不完整。
- 修复记录：2026-10-31 fixed。截断 20→`MAX_DORMANT_RETURNED=1000`（与 `UserRecallService.MAX_RECALL_USERS` 对齐）；回填 22 个沉睡用户复测：KPI=22、表格 22 行、导出同源。UI 回归截图 evidence/R01-useranalysis-fixed-dialog-22-full.png。测试数据已清理（0/0）。

### MA-003 召回弹窗「近 {{days}} 天沉睡名单」文案与实际固定 30-90 天窗口不符
- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R1；修复锚 `43a4efe0` + 产物 `c3e0e44d`）
- 端与页面：admin `/user-analysis`（一键召回弹窗）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / `0eb90b49`
- 环境：同 MA-002
- 复现步骤：选「近 7 天」→ 沉睡表格仍有 22 人（固定窗口）→ 点「一键召回」→ 弹窗显示「20 人（近 7 天沉睡名单，最多 1000 人）」。
- 期望 / 实际：文案「30-90 天沉睡名单」（沉睡窗口固定，`UserBehaviorAnalyticsService` 用固定 dormantCutoff/dormantFloor，与 days 无关；后端召回同源）/ 实际按所选 days 动态拼「近 7 天」，误导运营以为召回目标随窗口变化。
- 证据：evidence/R01-useranalysis-recall-dialog-7d.png
- 源码定位：`clients/admin-vue/src/views/growth/UserAnalysisView.vue:191`
- 联动影响：纯文案；行为本身一致（前后端都用同一固定窗口名单）。
- 修复记录：2026-10-31 fixed。`UserAnalysisView.vue` 弹窗改「{{ s?.dormantUsers30d ?? 0 }} 人（30-90 天沉睡名单，最多 1000 人）」——人数取全量 KPI 字段、窗口文案写死真实口径；复测弹窗显示「22 人（30-90 天沉睡名单）」。
### MA-004 品类维度显示裸字典码「1」（销售报表 + 大屏同源）

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R1；修复锚 `355bb308`）
- 端与页面：admin `/sales-reports`（销售报表 → 品类维度）与 `/big-screen`（品类销量榜）——同一后端方法，两页同时受影响
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / 发现于 R1 轮内（开轮基线 `4a96b0af`；精确观测 SHA 未记，故不假装精确定位）
- 复现步骤：1) `/sales-reports` 选品类维度出报表 → 类目列显示 `1` 而非中文品类名；2) `/big-screen` 品类榜同样显示裸码
- 期望 / 实际：与消费者目录一致的中文品类标签 / 直接透出 `sku_catalog.category` 里存的 `category_code` 字典值
- 根因：`CompetitiveGapService.aggregateByCategory` 归并键取 `sku_catalog.category`（存的是 `category_code` 字典值），此前**未过 `SysDictService` 翻译**就进展示字段。
- 证据：无独立截图（缺陷与结论记在 [rounds/R01-overview.md](rounds/R01-overview.md) 的 `/sales-reports`、`/big-screen` 两节）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/CompetitiveGapService.java`（`aggregateByCategory`，修复后构造函数多注入 `SysDictService`）
- 联动影响：任何复用该品类维聚合结果的读面（报表导出 CSV 同列）都会带裸码。
- 修复记录：2026-10-31 fixed（`355bb308`）。与消费者目录同口径经 `SysDictService` 翻中文；**未收录的历史自由文本原样透出、空值仍归「未分类」**（刻意不改成 fail-closed 报错，避免历史数据把整张报表打死）。钉测 `CompetitiveGapSalesReportTest.salesReport_categoryDim_translatesDictCodeToLabel`。回归索引见 [regression-index.md](regression-index.md)。

### MA-005 运营大屏头部在窄视口下标题与右侧信息重叠溢出

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R1；修复锚 `5a62f77e` + 产物 `0eb90b49`）
- 端与页面：admin `/big-screen`（全屏路由，头部区）
- 发现人/日期/HEAD：Qoder R1 / 2026-10-31 / 发现于 R1 轮内（开轮基线 `4a96b0af`）
- 复现步骤：1) 浏览器窗口收窄（非 1920 投屏档）；2) 头部标题与右侧时间/指标区互相压字、溢出容器
- 期望 / 实际：窄视口下不重叠（可换行/收缩）/ 绝对定位头部两坨文字打架
- 证据：`evidence/R01-big-screen.png`（修复前重叠）、`evidence/R01-big-screen-narrow-fixed-558.png`（修复后同视口档复验）
- 源码定位：`clients/admin-vue/src/views/dashboard/BigScreenView.vue`（+19 行窄视口样式）
- 联动影响：无（纯展示层）。
- 修复记录：2026-10-31 fixed。样式层收口，未动布局结构；产物随 `0eb90b49` 提交。🔴 **未解债**：无自动用例（1280/1920 双视口截图断言待 R10 补），且**严格 1920px 投屏档本轮未测**（见 R01 报告大屏节）。

### MA-006 `displayBizNo` 兜底把可读编号剥成假短号，显示的单号搜不到

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R2；发现锚 `8e12c11f` → 修复锚 `56100ddc` + 产物 `4715e6d9`）
- 端与页面：首发 admin `/orders`（订单号列 + 详情抽屉「订单号」）；根因在三端共享函数 `packages/shared-uni/src/format.ts` 的 `displayBizNo` ⇒ **merchant-mp / consumer-mp 所有用它展示单号的位置同受影响**
- 发现人/日期/HEAD：Qoder R2 / 2026-10-31 / `8e12c11f`
- 复现步骤：
  1) `/orders` 关闭「隐藏零元单」→ 第三行订单号列显示 **`20001`**；同页 API 真值是 `E2E-TAIL-0001`（`GET /api/v2/ops/admin/orders?page=0&size=10&excludeZero=1` 实测三行：`1792659779535535992400`、`1792659650288345016481`、`E2E-TAIL-0001`）；
  2) 关键词框输入 `20001` → 查询 → 请求 `q=20001&excludeZero=1` → **共 0 条 / 空态「暂无订单」**；
  3) 输入真值 `E2E-TAIL-0001` → 共 1 条（证明不是搜索功能坏，是显示值不可搜）；
  4) 同一行「更多操作 → 复制单号」复制的是 `row.orderId`（真值，`OrderListView.vue:1134` `copyOrderId(row.orderId)`）⇒ **同一行显示与复制不一致**。
- 期望 / 实际：列表显示的单号可直接用于搜索与对账 / 显示的是剥出来的假短号。
- 根因：`displayBizNo` 末段兜底 `const digitsOnly = raw.replace(/\D/g, ''); return digitsOnly.length >= 4 ? digitsOnly : raw;`（format.ts:308-309）。对「剥完已知前缀后仍含字母」的可读编号，它取纯数字：`E2E-TAIL-0001` → `20001`（丢掉首字母 `E` 与 `TAIL` 段）；按同一条规则，本轮造数前缀 `AUDIT-R1-0001` 会被显示成 `10001`（与 SKU 编号 `10001` 撞形）。
- 证据：`evidence/R02-orders-list.png`（第三行显示 `20001`）、`evidence/R02-orders-displayid-search-miss.png`（关键词 `20001` → 暂无订单）；上述两组 API 响应
- 源码定位：`packages/shared-uni/src/format.ts:268-310`（`displayBizNo`）；调用面含 `clients/admin-vue/src/views/orders/OrderListView.vue:139+`、`components/device/DeviceRelatedRecordsTab.vue`、`components/warehouse/WarehouseMovementsTab.vue` 等
- 联动影响：任何展示 `displayBizNo(...)` 的列表/详情/弹窗（三端）都可能给出不可回查的短号；客服/运营按屏幕上的号去搜必然 0 结果。
- 关联：**项目已对该类缺陷定过性**——同文件 `shortBizNo` 的 `@deprecated` 注释（2026-09-18 起）理由就是「列表取末尾 N 位、详情页给全号 ⇒ 同一单号两处对不上，客服按列表号搜不到」。既有钉测（`clients/merchant-mp/src/utils/biz-no.test.ts`）只锁了「PD 可读单号原样」「纯数字原样」，**没有覆盖本兜底分支** ⇒ 属漏网回归面。
- 修复记录：2026-10-31 fixed（`56100ddc`）。`displayBizNo` 末段兜底由「取全部数字」改为**原样返回**——走到该分支的都是「剥完已知前缀仍含字母」的混合编号，数字段不等价于编号本身；已知前缀剥完是纯数字的（`PO-12`→`12`）约定**不动**。
  - 回归：`clients/merchant-mp/src/utils/biz-no.test.ts` 新增 3 例（`E2E-TAIL-0001`/`AUDIT-R1-0001` 原样 + `PO-12`→`12` 保持），6/6 绿；
  - 活栈复验（产物 `4715e6d9`，硬刷 ignoreCache）：`/orders` 第 9 行订单号由「20001」变 **`E2E-TAIL-0001`**，关键词输入该显示值 → 共 1 条（修复前 0 条）。证据 `evidence/R02-orders-displayid-fixed-searchable.png`。
  - 影响面：`displayBizNo` 三端 34 个文件在用；生产真实单号是纯数字（早分支原样返回），本改动只改「混合字母编号」这一类。

### MA-007 开门记录「类型」筛选只在当前页生效，结果漏数据且分页总数谎报

- 级别/状态：P1 / **fixed**（2026-10-31，Qoder R2 轮末批次；发现锚 `fc5a6bde` → 修复锚 `124e03ff` + 产物 `9637a884`）
- 端与页面：admin `/sessions` 开门记录 → 筛选行「类型」下拉（消费/补货/运维/盘点）
- 发现人/日期/HEAD：Qoder R2 / 2026-10-31 / 发现锚 `fc5a6bde`
- 复现步骤：
  1) 打开 `/sessions`「全部」→ `共 72 条`（= SQL `select count(*) from shopping_session` = 21 CANCELLED + 24 COMPLETED + 22 DISPUTED + 5 FAILED ✅）；
  2) 点「已完成」tab → `共 24 条`（= SQL 真值 ✅，服务端参数 `state=COMPLETED`）；
  3) 类型选「消费」→ UI 变 **`共 13 条`**，而网络面板显示这次查询的请求仍是 `/api/v2/ops/admin/sessions?page=0&size=20&state=COMPLETED`（**没有类型参数**）；
  4) 真值对照：`GET /sessions?page=0&size=200&state=COMPLETED` → 24 条里 `sessionKind=CONSUMER` 有 **17** 条 ⇒ 少 4 条（落在第 2 页，被页内过滤吃掉）。
- 期望 / 实际：类型筛选应服务端生效、返回该筛选下全量并可翻页 / 只过滤当前页 20 行，且把 `total` 替换成过滤后的页内条数。
- 根因：`clients/admin-vue/src/views/sessions/SessionListView.vue:465-470` —— `fetchPage` 内 `rows = rows.filter(r => (r.sessionKind || 'CONSUMER') === kindFilter.value)`，随后 `const total = rows.length < before ? rows.length : (data.total ?? 0)`。该处注释写着「本页有剔除 → total 跟过滤后（禁「共 N + 空表」#205）」⇒ **是修 lessons #205 时用「页内计数」替代，代价换成了跨页漏数据**。
- 后端侧：`AdminDashboardController.java:271-284` 的 `/ops/admin/sessions` 参数集里**没有 kind/sessionKind** ⇒ 正解需要后端加参数 + mapper 过滤 + 前端把 `kindFilter` 进 query，并 regen openapi/shared-types（预检 `openapi-regen` 门禁会拦，见记忆坑：改 Controller 加参数必须 regen）。
- 联动影响：`useListCsv` 的 `pickSelected(crud.displayItems)` 同源 ⇒ **导出也只含当前页过滤结果**；`stuckOnly`/滞留置顶等其它页内过滤若与类型叠加，同样受本页内过滤语义影响。
- 证据：`.tmp` 探针（API 24 条中 CONSUMER=17）+ UI 三次观测（72 / 24 / 13）与请求 URL 记录；截图见 `evidence/R02-sessions-*.png`
- 关联：lessons #205（「共 N + 空表」旧缺陷，本次是其反向代价）；与 MA-006 同属「界面显示值 ≠ 可回查真值」类。
- 门禁化候选：PLAN 台账新增一条 —— **列表页客户端 `rows.filter(` 出现在 `fetchPage` 内即红**（除显式 allowlist），强制「筛选必须服务端化」。这条 R2 抓到两次（MA-006 是显示层，本条是取数层），值得钉。
- 修复记录：2026-10-31 fixed（`124e03ff` + 产物 `9637a884`）。
  - **同源做法**：新增 `support/SessionKind`（类型名 + 幂等键前缀的唯一字面量源），`DeviceValidationService` 的三个谓词改读同一批常量，mapper 条件也从这里取 ⇒ 没有把规则抄成第二份 SQL 字符串（铁律 16）。
  - 筛选进 `SessionFilterCriteria.sessionKind`，条件与 Java 判定顺序等价（OPS > RESTOCK > STOCKTAKE > CONSUMER）；🔴 取反一律写 `key IS NULL OR key NOT LIKE ...`——`NOT LIKE` 对 NULL 求值为 NULL，直接 `notLikeRight` 会把**没有幂等键的消费者会话整批判掉**（IT 里专门钉了这条）。
  - `/sessions` 与 `/sessions/export` 均加 `kind` 参数，未知值 **400**（不静默忽略筛选）；导出审计留痕带上 kind，事后能还原拖的是哪一类。`openapi.ts` 同批 regen（+2 行）。
  - 前端：`kind` 进 query，删掉 `fetchPage` 内的 `rows.filter(...)` 与「页内条数顶替 total」逻辑。
  - 验证腿（四腿齐）：① `SessionKindFilterSqlIT` 真库 2/2 绿（SQL 过滤结果逐个等于 `sessionKind()`、四类之和=全表、NULL 键留在 CONSUMER）；② 既有 `DeviceValidationServiceSessionKindTest` 3/3 绿；③ API 真值：已完成∩消费 **17**（RESTOCK 4 + STOCKTAKE 3 + OPS 0，四类之和=24=已完成全量）；④ 活栈 UI 重建产物后：**共 17 条 / 17 行**，请求 `?state=COMPLETED&kind=CONSUMER`，console 0 error。

### MA-015 一致性巡检对「无批次历史订单」误报 MISSING_SALE

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R6；发现锚 `d5c22e8c` → 修复锚 `（本批）` + 容器重建后巡检复跑）
- 端与页面：admin `/consistency` 数据一致性 → 巡检规则 `checkCrossLinkMissingSale`（CROSS_LINK/A）
- 发现人/日期/HEAD：Qoder R6 / 2026-10-31 / 发现锚 `d5c22e8c`
- 复现：页面 `未通过 5`，其中 2 条为 `SALE|1792659779535535992400` / `SALE|1792659650288345016481`，文案「已付有货且柜机有批次账本，但缺少 SALE+ORDER 库存流水」。
- 真值：两单 2026-10-22 支付，`cabinet_order_line.batch_no` **为空**（当时无批次，走无批次扣减，`inventory_deducted=true`）；该柜批次账本 `device_sku_lot` 最早 2026-10-26 建立 ⇒ **下单时刻不可能有 SALE 批次流水**；全库 `inventory_movement` 中 `SALE` = 0 条（只有 ADJ/SELL_OFF/RESTOCK）。
- 根因：规则只判断「设备现在存在批次账本」，未要求「订单行本身带批次」（`DataConsistencyService.checkCrossLinkMissingSale:1207-1224`）。
- 期望 / 实际：只对「从批次中扣减的销售」要求 SALE 流水 / 历史无批次订单被永久判红，检测器噪音会让人不信这张表。
- 证据：上述 SQL 真值 + 规则源码
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/DataConsistencyService.java:1207-1224`
- 关联：与 [[project-real-cabinet-settlement-test]] 的真柜结算时序一致（先有订单、后建批次）。
- 修复记录：2026-10-31 fixed。`checkCrossLinkMissingSale` 的「缺 SALE 流水」分支增加前提
  `EXISTS (SELECT 1 FROM cabinet_order_line bl WHERE bl.order_id = o.order_id AND COALESCE(bl.batch_no,'') <> '')`
  ——只有「从批次扣减」的销售才可能产生批次流水；并加注释说明历史无批次订单的时序。
  - 规则 SQL 前后对比（真库）：旧规则命中 **2** 条（正是那两笔 10-22 订单）→ 新规则 **0** 条，其余订单不受影响（库里只有这 2 笔 PAID）。
  - 活栈验证：容器重建后调用 `POST /consistency/run` → `failCount = 3`（仅剩三条争议 CROSS_LINK），SALE 误报消失；记录表状态 FAIL 5 → **3**。
  - 说明：**未**动 dispute 规则（MA-016 待拍板）。

### MA-018 字典同名标签 8 组（下拉重复项）

- 级别/状态：P3 / **open（门禁已加，清理方向待拍板）**
- 端与页面：admin 各页字典下拉（首见 `/feedback` 状态筛选「已回复」出现两次）
- 发现人/日期/HEAD：用户 2026-10-31 指出 → Qoder R7 全量排查 / 锚 `c433222e`
- 复现：`/feedback` 状态筛选出现「已回复」两次；全量 SQL 扫出 **8 组**同 type 同 label 的 ACTIVE 项。
- 根因：`SysDictBootstrap` **刻意**为历史数据保留了兼容别名（两套词表并存），例如
  `split_status` 同时种 `WECHAT_SUBMITTED`/`SUBMITTED`（都叫「已提交」）、
  `session_kind` 把 `SHOPPING`（其实是 `session_state` 的值）也种成「消费」。
  这不是散落手工数据；只在**下拉选项**层表现为重复。
- 门禁（已上线）：`DictLabelUniquenessIT`（Testcontainers + Flyway 全量迁移后断言 ACTIVE 字典无同名标签，
  棘轮白名单 8 组双向校验：新增重名红 / 清单多余红），已登记 `check-docker-tests-guarded`（job=integration）。
  首次实跑证据：`实际：{enable_status|停用=…, feedback_status|已回复=HANDLED,REPLIED, …}`。
- 清理方向（待拍板）：
  A. 清理式：每组定规范值，旧值停用/删除 + 迁历史数据（历史标签可能掉回裸码）；
  B. **选项层收敛（推荐）**：字典保留全部值用于历史渲染，只在「下拉选项」返回规范值（配 canonical 清单）——不动数据、不丢历史标签。
- 规范值证据：feedback→HANDLED（UserFeedbackService:118 写）、ops_alert→RECON_MISMATCH（ReconciliationService:216/283）、
  restock_line→PULL_OFF（ReplenishmentService:82/1705，读取端仍兼容 PULL/REMOVE）、split_status→WECHAT_*（RevenueSplitService:27）、
  warehouse_movement→MANUAL_INBOUND（WarehouseService:261）。
- 修复记录：（待选题后填）

### MA-017 短信验证码校验非原子，并发可双花

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R7；发现锚 `04d946c7` → 修复锚（本批））
- 端与接口：consumer `POST /api/v2/auth/login`（及后台重置密码同链路）→ `SmsCodeService.verifyCode`
- 复现（代码判据 + 钉测）：旧实现 `findTopBy...` 读出未用码 → `entry.setUsedAt(now); save(entry)`，两步之间无行锁/无条件更新 ⇒ 两个并发请求都能读到未用、都判定成功。
- 影响：同一手机号同一码可在并发窗口内被消费两次（例如两个登录请求同时换取会话）；不改余额/资金，故定 P3。
- 修复：新增 `SmsVerificationCodeMapper.markUsedIfUnused(id)`（`UPDATE ... SET used_at=now WHERE id=? AND used_at IS NULL`，返回是否抢到）；`verifyCode` 成功分支改为条件抢占，抢不到 ⇒ 判失败但**不计失败次数**（避免误烧新码）；mock 码分支的 `markLatestUsed` 同改。
- 钉测：新增 `SmsCodeServiceTest` 4 条（正确码走原子消费 / 并发已消费不计失败 / 过期码标记已用 / 第 5 次错码烧码），4/4 绿。
- 说明：dev 的 `SMS_MOCK_CODE` 会让配置的万能码短路校验（仅 mock 开启时生效，生产由 `ProductionStartupValidator` 禁止），故双花的活栈复现需关 mock-code；本轮以钉测为判据。

### MA-016 争议「已结案但无关联订单」是否一律算不一致（待拍板）

- 级别/状态：P3 / **wontfix**（用户 2026-10-31 拍板：演示假数据、代码没错，3 条争议数据人工处理，规则不改）
- 端与页面：admin `/consistency` → `checkCrossLinkDispute`
- 现象：3 条 `DSP|…`「争议已结案但缺少关联订单」，对应会话 `COMPLETED` 且 `order_id` 为空（顾客开门未购 / 识别争议未成单）。
- 规则：`RESOLVED/CLOSED` 且会话无订单 ⇒ 记 CROSS_LINK（`DataConsistencyService:1273-1284`）。
- 拍板结果（2026-10-31）：**规则保持原样**；这 3 条是演示数据，按「人工处理」对待（记录仍以 FAIL 呈现，属于检测器如实反映数据，不是缺陷）。
- 旁证：同批的另一条对齐差异（`payment_reconciliation` 2026-10-05 BALANCE，diff 500 / 未匹配 1，`detail.demo=true`）也由用户定为「人工平账留痕」——已执行：status→MATCHED、diff/unmatched 归零，原始值写入 `detail.manualClose`（`originalDiffCents=500`、`originalUnmatched=1`、`by=R6-audit`）；页面复验「批次总数 7 / 差异 0 / 匹配 7」。

### MA-014 「账务明细」默认只查近 7 天且不交代，空态误导

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R6；发现锚 `ecf45bb2` → 修复锚 `87e6beb1` + 产物 `56d58377`）
- 端与页面：admin `/fund-bills` → 账务明细 tab
- 发现人/日期/HEAD：Qoder R6 / 2026-10-31 / 发现锚 `ecf45bb2`
- 复现：账期选择器留空（默认）打开账务明细 → `共 0 条` + 「暂无流水」；API `GET /ops/admin/fund/ledger?page=0&size=20` → `total=0`，而加 `fromDate=2026-10-01&toDate=2026-10-31` → **total=6**（3 笔订单 × 实付/通道费/商户入账三类分录）。
- 根因：`FundBillService.listLedger` 默认窗口 `now-7d → now`（:332-333），页面 `range` 默认 null 且只提示「支持跨月，单次不超过 90 天」，**没写默认窗口**；账期留空时没有任何线索指向「7 天」。
- 期望 / 实际：留空应交代窗口（或在空态里给出「试试选账期」）/ 静默只看 7 天，看起来像没数据。
- 证据：上述两次 API 对照（0 vs 6）
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/FundBillService.java:332-333`；`clients/admin-vue/src/views/finance/FundBillView.vue:42,460-462`
- 关联：MA-010（14 天窗口）同族；不改变默认行为，只补交代。
- 修复记录：2026-10-31 fixed（`87e6beb1` + 产物 `56d58377`）。账期提示补「留空＝『账务明细』只看近 7 天」；空态分三态（有搜索词 / 未选账期 / 已选账期）分别给文案。**后端默认窗口一行未动**（7 天是既定行为，放宽需另行拍板）。
  - 活栈复验：提示文本与空态文本均按预期渲染；再加 10 月账期可看到 6 条分录（与 `wallet_ledger` 的 SPLIT 150/350、通道费合计 ¥0.03 对上）。

### MA-013 打印预览纸张在窄视口溢出、不居中

- 级别/状态：P3 / **fixed**（2026-10-31；发现锚 `5fd3e04a`（用户在 R4 实测中指出）→ 修复锚 `1f18d352` + 产物 `a3aa3097`）
- 端与页面：admin `/print`（采购收货单 / 拣货单预览）
- 复现：内置浏览器 702px 打开 `/print?type=picking&outboundId=15` → 纸张右侧被截、页面横向滚动；`margin: 24px auto` 因内容宽（720+80）> 视口而失效，视觉上贴左。
- 根因：`.print-sheet { width:720px; padding:32px 40px }` 未计入内边距、无上限收缩。
- 修复：`box-sizing:border-box; width:min(720px, calc(100% - 32px))` + `@media (max-width:760px)` 收紧内边距；`@media print` 原有 100%/margin:0 规则不动（打印纸面不受影响）。
- 验证（无头 Playwright 两个宽度）：702 → 宽 670、左右各 16、无横向滚动；1600 → 宽 720、左右各 440；均 `centered=true`。证据 `evidence/R04-print-centered-702.png` / `-1600.png`。
- 关联：R4 批 2「/print 打印单据」维度 5。

### MA-012 投放计划「关键词」为前端本页过滤，行数与总数不符

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R3 门禁批；发现锚 `423bdfe4` → 修复锚 `bb68e077` + 产物 `cb031e79`）
- 端与页面：admin `/ad-campaigns` 投放计划 → 关键词
- 发现人/日期/HEAD：Qoder R3（新门禁 `check:list-filter-must-be-server-side` 首扫）/ 2026-10-31 / 发现锚 `423bdfe4`
- 复现步骤：`AdCampaignsView.vue:312-325` fetchPage 里 `const items = kw ? (data.items||[]).filter(matchKeyword) : …`，`total` 仍 `Number(data.total)`；若某关键词只命中第 2 页的行，则列表为空但底部仍显示「共 N 条」；若命中当前页 3 行，则显示 3 行 + 「共 20 条」。
- 期望 / 实际：关键词服务端过滤、`total` 与命中集合一致 / 只过滤当前页且总数不联动。
- 根因：同 MA-007/MA-011（注释「关键词沿用前端过滤（只作用于当前页，与原 displayRows 行为一致），total 仍取服务端值」）。
- 证据：代码判据 + 门禁首扫输出
- 源码定位：`clients/admin-vue/src/views/growth/AdCampaignsView.vue:322-324`
- 关联：MA-007 / MA-011；门禁 `scripts/check-list-filter-server-side.mjs`（本轮落地）
- 修复记录：2026-10-31 fixed（`bb68e077` + 产物 `cb031e79`）。`/ops/admin/ad/campaigns` 加 `q`（匹配 活动名称/状态值）与 `status`（精确）参数，条件进 SQL；前端关键词进 query、中文状态标签先翻成值再作为 `status` 传（保住「按标签搜」），删除本地 `matchKeyword` 与页内过滤。
  - 验证：API `?q=RUNNING` → `total=1 items=1`（与 SQL `ad_campaign` RUNNING=1 一致）、`?q=点击` → 0；容器重建 + openapi regen（+4 行）；admin 产物重建；console 0 error。
  - 同批落地门禁 `check:list-filter-server-side`（自测 9 条断言 + 棘轮白名单 3 条 + 聚合链接线），**本条是该门禁首扫抓到的**——即「先有门禁再有修复」，后续同类写法在 CI 直接红。

### MA-011 设备运维「关键词」为前端本页过滤，跨页搜不到且总数谎报

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R3；发现锚 `54b807dd` → 修复锚 `f9bdf3f6` + 产物 `2bf02669`）
- 端与页面：admin `/device-ops` 设备运维 → 关键词
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `54b807dd`
- 复现步骤：列表 `size=50`、总 54 条 ⇒ 第 2 页只有 4 条；输入一个只命中第 2 页事件的 ID/详情关键词 → 后端仍只返回第 1 页 50 条，前端在这 50 条里 `.filter()` ⇒ **0 条**，且 total 被替换成 0。
- 期望 / 实际：关键词应服务端过滤、可命中全量窗口内事件 / 只在当前页里搜。
- 根因：`DeviceOpsMonitorView.vue:241` `filterByKeyword(rows)` + 取数处「有剔除时 total 跟可见行一致」（注释引 lessons #205/#208）；后端 `/device-ops/events` 当时没有关键词参数。
- 证据：代码判据 + MA-007 同族实测（同一台机、同样的页内过滤写法）
- 源码定位：`clients/admin-vue/src/views/devices/DeviceOpsMonitorView.vue:241`（修复后该方法删除）
- 关联：MA-007（`/sessions` 类型筛选同族）；R2 提出的门禁候选 `check:list-filter-must-be-server-side` 正是为这类设的。
- 修复记录：2026-10-31 fixed（`f9bdf3f6` + 产物 `2bf02669`）。`/device-ops/events` 加 `q`（匹配 事件ID/设备编号/标题/详情，mapper 里 `LIKE` + `CAST(event_id AS TEXT)`），前端关键词进 query 并**删除 `filterByKeyword` 与「页内条数顶替 total」**。
  - 活栈/API 验证：取放宽窗口下**第 2 页首条事件 ID（69）**做关键词 → `total=1`（修复前这种只在第 2 页的事件必然 0 条）；
  - 同批实测中文关键词 `设备离线`：API 14 天窗口内 27 条 = SQL `title LIKE '%设备离线%' AND created_at > now()-14d` 27 条（终端直接 curl 中文会因 GBK 编码得 0，必须用 UTF-8 编码发请求——这条也记进交接注记）。

### MA-010 设备运维列表后端写死 14 天窗口，前端不交代也不可改

- 级别/状态：P2 / **fixed（含 from/to 参数）**（2026-10-31，Qoder R3；发现锚 `e326601f` → 修复锚 `7dce0f04` 文案 + `f9bdf3f6` 参数 + 产物 `2bf02669`）
- 端与页面：admin `/device-ops` 设备运维（事件列表）
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `e326601f`
- 复现步骤：
  1) 打开 `/device-ops` → 分页器 `共 54 条`；
  2) SQL 真值：`select count(*) from device_ops_event` = **100**（类型分布 OFFLINE 45 / NO_SALES 28 / FAULT 21 / AISLE_AUDIT 6，且 `device_id` 全部能在 `device_info` 找到 ⇒ 不是孤儿行）；
  3) 筛选项只有 类型 / 级别 / 设备 / 关键词，**没有任何时间范围控件**；页面 hint 只说「与交易异常分流…事件 ID 默认升序」，不提 14 天。
- 期望 / 实际：要么在页面上写明窗口、要么给出可改的时间范围 / 静默截断，运营以为「共 54 条」就是全部运维事件。
- 根因：`CompetitiveGapService.listDeviceOpsEvents`（:376-380）把查询窗口硬编码成 `Instant.now().minusSeconds(86400L * 14)` ~ `now()`，controller `/device-ops/events` 也没有 from/to 参数（`OpsGapFeaturesController.java:183-195`）。
- 证据：`.tmp/r3-device-ops.json`（`paginationTotal=共 54 条` + 请求 `?page=0&size=50&sortDir=asc`）+ 上述 SQL；截图 `evidence/R03-device-ops-pass.png`
- 源码定位：`services/trade-service/src/main/java/com/aicabinet/trade/service/CompetitiveGapService.java:376-380`
- 联动影响：`/device-kpi` 的「离线事件」是**另一条口径**（今日上海时区新建的 `DEVICE_OFFLINE` 异常数，实测 2 = SQL 2 ✅ 正确），两者别混；商户端理货记录-盘点复用同一查询（CB-050 注释）⇒ 也受同一 14 天窗口影响。
- 关联：与 MA-008 同族（口径不交代）；与 MA-009（截断不交代总数）同族。
- 修复记录：2026-10-31 fixed 档 A（`7dce0f04` + 产物 `8a7c9418`）。页面 hint 追加「仅统计近 14 天事件（后端固定窗口，更早事件本页不返回，故『共 N 条』不是全量）」。活栈复验：文案已渲染、分页仍 `共 54 条`、console 0 error（`evidence/R03-device-ops-pass.png`）。
  - **用户拍板（2026-10-31）：加 from/to 参数**，已落地（`f9bdf3f6`）：`/device-ops/events` 新增可选 `from`/`to`，**两者都空时仍回落既有 14 天默认窗口** ⇒ 商户端理货-盘点（CB-050 同一查询）行为不变。前端加「时间」daterange，右端取当天 23:59:59.999。
  - 活栈验证：选 2026-10-01~10-31 → 请求带 `from=2026-09-30T16:00:00.000Z&to=2026-10-31T15:59:59.999Z`，列表 `共 80 条`；不选 → `共 44 条`；同刻 SQL `all=80 / within14d=44` **完全一致**。
  - 🔴 集成踩坑：Element Plus daterange 默认 v-model 回 **Date 对象**，而代码按 `number` 判 `Number.isFinite` ⇒ 一开始永远不带 from/to；加 `value-format="x"` 才通（已修 `0c5d76b6` 系列）。
  - 同轮澄清：`/device-kpi` 的「离线事件」是**另一条口径**（今日上海时区新建的 `DEVICE_OFFLINE` 异常数），实测页面 2 = SQL `created_at >= 2026-10-31 00:00+08` 的 2 ✅ 正确，与本条无关，别混。

### MA-009 设备详情「关联单据」截断 8 条且无总数、无段级查看全部入口

- 级别/状态：P3 / **fixed**（2026-10-31，Qoder R3；发现锚 `2b4046a3` → 修复锚 `3f877459` + 产物 `34068cd0`）
- 端与页面：admin `/devices/:id` → 关联单据 tab（`DeviceRelatedRecordsTab.vue`）
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `2b4046a3`
- 复现步骤：
  1) 打开将邑测试机详情 `EG943322621391`（SQL 真值：`shopping_session` 62 条、`cabinet_order` 8 条）→ 关联单据 tab；
  2) 「最近开门记录」「最近订单」两张表各渲染 8 行（共 16 行），**页面上没有任何地方说明开门记录其实有 62 条**；
  3) 想查全量只能自己想去 `/sessions` 再按设备筛——段级没有跳转入口，只有行级（点某条会话号才跳 `/sessions?deviceId=…&sessionId=…`）。
- 期望 / 实际：截断要同时给出总数与查看全部入口 / 只写「最近」，运营会以为该柜就这 8 次开门。
- 根因：`useDeviceRelatedRecords.ts:25-45` 两张表都按 `page=0&size=8` 取数，但**同时把 `total` 存进了 `sessionTotal` / `orderTotal`**；父页 `DeviceDetailView.vue:1215-1216` 只拿这两个总数判断「是否全空」，模板里从未渲染 ⇒ 数据在手却没用。
- 证据：`.tmp/r3-detail.json`（将邑柜 关联单据 rows=16）+ 上述 SQL 真值；截图 `evidence/R03-device-detail-related.png`（修复后）
- 源码定位：`clients/admin-vue/src/components/device/DeviceRelatedRecordsTab.vue:30,131`（两个 `section-title`）
- 联动影响：`/devices` 列表页的「关联记录」入口同组件 ⇒ 一并受益。
- 关联：与 R2 MA-007 同族（截断/口径不交代），但本条是**展示层缺交代**，非取数错误。
- 修复记录：2026-10-31 fixed（`3f877459` + 产物 `34068cd0`）。段级标题同行加「共 N 条 · 查看全部」，**仅当 `total > 已渲染行数` 才出现**（不截断就不显示），点击复用组件既有的 `open-sessions` / `open-orders` emit，不新增跳转实现。
  - 活栈复验（真 Chrome 1600）：将邑柜「最近开门记录」显 **共 62 条 · 查看全部**，点击落 `/admin/sessions?deviceId=EG943322621391` 且列表 `共 62 条`（深链两页都支持）；
  - 负向判据同测：同柜「最近订单」8 条未截断 ⇒ **不显示链接**；演示柜 2 条会话 ⇒ 两段都无链接；
  - 证据 `evidence/R03-device-detail-related.png`；console 0 error。

### MA-008 「离线设备」在设备报表与工作台是两个口径，报表侧不交代口径

- 级别/状态：P2 / **fixed**（2026-10-31，Qoder R3；发现锚 `35804dd5` → 修复锚 `7ad59bbc` + 产物 `c4ccfde9`）
- 端与页面：admin `/reports` 设备报表 KPI「离线设备」 vs `/dashboard` 运营工作台待办「离线设备」
- 发现人/日期/HEAD：Qoder R3 / 2026-10-31 / 发现锚 `35804dd5`（由 R1 遗留的**线索 #2** 转正）
- 复现步骤：
  1) 工作台读 `GET /api/v2/ops/admin/workbench-bundle` → `offlineDevices` = **3**；
  2) 设备报表 KPI 读 `GET .../reports/devices?online=OFFLINE&page=0&size=1` → `total` = **5**（`DeviceReportView.vue:554-561`）；
  3) SQL 真值：`select count(*) from device_info where online_status<>'ONLINE'` = **5**；再加 `and lifecycle_status='DEPLOYED'` = **3**；lifecycle 分布 = DEPLOYED 3 / INBOUND 1 / RETIRED 1（当前 5 台设备**全部离线**）。
- 期望 / 实际：两处数字都「对」，但同名 KPI 差 2 且报表侧只有 hint「点击筛选离线」，不交代是**含未投放/已退役的全量** / 运营按工作台数找报表数对不上，会当成数据缺陷。
- 根因（两侧都是刻意的，不许「顺手对齐」）：`OpsWorkbenchQueryService.countOfflineDevices`（:631-637）显式 `filter(isDeployedDevice)`，注释写明「仅投放柜离线（避免 INBOUND 孤儿柜抬高『离线设备』）」；设备报表的列表本来就是全设备台账，其 KPI 沿用列表口径。
- 证据：`.tmp` 探针（API 两侧 total 3 vs 5）+ 上述 SQL；截图 `evidence/R03-reports-offline-kpi.png`
- 源码定位：`clients/admin-vue/src/views/reports/DeviceReportView.vue:436-440`（hint 未标口径）、`services/.../OpsWorkbenchQueryService.java:631-643`
- 联动影响：商户端工作台 `MerchantWorkbenchQueryService` 也各有一套离线计数口径（R9 点验，不在本条范围）。
- 关联：R1 线索 #2（本条即其转正）；先例＝R1「缺货建议 tab 恒空」按档 A 纯文案落地（`24e2a900`）。
- 修复记录：2026-10-31 fixed（`7ad59bbc` + 产物 `c4ccfde9`），**档 A 纯文案**（沿用 R1「缺货建议 tab」先例）。`deviceReportOfflineHint` 由「点击筛选离线」改为「全量口径（含未投放/已退役柜；工作台只算投放柜）· 点击筛选离线」。
  - 两侧计数逻辑**一行未动**：工作台只算 DEPLOYED 是 `OpsWorkbenchQueryService.countOfflineDevices:631-643` 注释写明的刻意设计（避免 INBOUND 孤儿柜抬高），报表列表本就是全设备台账 ⇒ 不许顺手对齐。
  - 活栈复验（真 Chrome 1600）：报表 KPI 文本渲染出全量口径说明且值=5，同一会话工作台=3；证据 `evidence/R03-reports-offline-kpi.png`；console 0 error。
  - 顺带印证 R2 删夹具后的新基线在报表页同步生效：累计营收 ¥5.00、订单 13。

### 线索 #2：设备报表 vs 工作台「离线设备」同词两口径（R1，未复现未领号）→ ✅ 已复现并转正为 MA-008（2026-10-31）

- **来源**（R01 会话，2026-10-31）：设备报表 `/reports` 显「离线设备 5」（全部设备），工作台 `/admin` 显「离线设备 3」（疑 deployed/投放柜口径）。同词不同数，运营无法判断哪个是真。
- **未复现原因**：dev 库设备行会随环境变动，两处取数函数体未逐行比对，按入账前提（实跑复现才入账）暂不领号。
- **下一步**：读两处取数函数体 + `device_info` 行数/lifecycle 分布，判定是缺陷还是口径差；若为口径差，按「汇总行=全集、子集列表」惯例加口径标注而非对齐（参考记忆规则：negation/口径类先查权威模型）。

### MA-020 后台把可逆还原的登录口令常驻 localStorage，且「记住」默认开启（P1，档 B 已修）

- 级别/状态：P1 / **fixed**（2026-11-02 用户授权「按你的推荐做」后落档 B；发现锚 `911ebf4b` → 修复锚 `b2da3814`）
- 修复记录：`LoginView` 不再 `btoa` 写 `admin_password`、口令框恒空、勾选项改名「记住账号」；`api/auth-storage.ts` 登出一律删 `admin_password`/`admin_remember_password`；登录页 `onMounted` 也删一次（覆盖「从不登出」的用户）。
  证据：`clients/admin-vue/src/api/auth-storage.test.ts` 3 例（勾选着也删 / 手机号照旧保留 / 取消勾选照旧清手机号）全绿；admin 产物已由 CI 保真重建提交（`d913c59f`→`b2da3814` 之后的产物批）；活栈复验见 R09 轮报告「值守期落地记录」。
- 端与页面：admin `/login`（+ 登出清态 `api/auth-storage.ts`）
- 发现人/日期/HEAD：Qoder R8 / 2026-11-01 / 发现锚 `911ebf4b`
- 复现步骤：
  1) 未登录打开 `/login`：勾选项唯一、默认「√ 记住账号密码」，口令框初始长度 0（A-1「不预填演示口令」仍成立）；
  2) 用 `13900000001` 登录一次；
  3) 读 `localStorage`：`admin_password` 存在，`atob(admin_password) === 本次登录口令`（探针判据 `true`），`admin_remember_password="1"`，`admin_phone` 同在；
  4) 登出（保持勾选）再读：值仍在（[auth-storage.ts:87-89](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/api/auth-storage.ts#L87-L89) 只在取消勾选即 `'0'` 时清）。
- 期望 / 实际：期望凭据不由页面脚本持有；实际是「任何 admin 源上的 XSS / 任何有 storage 权限的扩展 / 任何能开 devtools 的人」一步还原明文口令并可重放登录，而这个后台管提现、分账、改价。
- 根因/沿革：[LoginView.vue:291-309](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/src/views/LoginView.vue#L291-L309) 注释自认「base64 编码（便利性混淆，非安全边界）」。2026-10-22 的拍板主题是「两勾选合并为一个」，并未评估凭据在浏览器侧可逆留存这一层 ⇒ 本条不是推翻那次拍板，而是补上它没覆盖的风险面。
- 证据：`.tmp/r8-login.mjs` 探针输出（键名 + 判据，未打印口令）；截图 `evidence/R08_login-fresh.png`
- 三档与推荐：A 保持现状（须把注释/hint 改成「明文可逆存本机」，别再写「非安全边界」）；**B 推荐**——应用不再写 `admin_password`、进页即清历史值，勾选改名「记住账号」，口令交给浏览器密码管理器（体验等价、凭据进 OS 级保管、脚本读不到；改动面 LoginView 两处 + auth-storage 一处，无迁移无后端）；C 折中 sessionStorage（关标签即失，XSS 窗口仍在，代码比 B 多收益比 B 小）。
- 关联：rounds/R08-system.md 待拍板 #3；同页正向结论（勾选项、忘记密码弹窗、验证码）均无缺陷。

### MA-021 4 个前后端零引用的页面权限码以 ACTIVE 留在权限表＝幽灵菜单（P2，已修）

- 级别/状态：P2 / **fixed**（2026-11-01，Qoder R8 批 2；发现锚 `911ebf4b` → 修复锚 `55bac20f`）
- 端与页面：admin `/menus` 菜单管理、`/roles` 角色权限树（数据源同一张 `ops_permission`）
- 复现步骤：
  1) `select perm_code,status,perm_type from ops_permission where perm_code in ('ops:ad:campaign:list','ops:bigscreen:view','ops:sku-vision:list','ops:vision:list')` ⇒ 四行 `C / ACTIVE`；
  2) 全仓（排除 node_modules/dist/target/db-migration/docs）检索这四个码 ⇒ **0** 命中；`menu.ts` 里只剩解释性注释（:35、:188、:441），侧栏 56 个 `NAV_ITEMS.perm` 全集不含它们；
  3) 菜单管理页（`rbacPermissionsIncludeInactive`）把它们显示成启用中的页面；角色编辑树（`RoleManageView.vue:402` 取 `rbacPermissions` 即 ACTIVE 全集）可把它们勾给角色——当前分别有 2/4/3/2 个角色持有。
- 期望 / 实际：期望「勾了=能打开」；实际勾了既不放开页面也不放开接口。
- 根因：H51 那轮修「导航码 ≠ 后端校验码」时只改了前端一侧，被换下的行没处理（V154/V170/V144/V14-V109 种子原样 ACTIVE）。
- 修复记录：`V421__retire_phantom_page_perms.sql` 置 **RETIRED 不删行**（V386 `ops:session:upload` 先例：授权行与审计历史保留，`OpsPermissionMapper` 两条读查询带 `p.status='ACTIVE'` ⇒ 即刻失效）。
  - 实跑证据：dev 库事务内 dry-run（`BEGIN` → 迁移体 → `ROLLBACK`）得 `UPDATE 4`，四行 ACTIVE→RETIRED，同前缀 `ops:vision:export` 未误伤；迁移幂等（`AND status <> 'RETIRED'`），空库顺序无新依赖（纯 DML，不动历史迁移）。
  - **判据不同、刻意保留**（勿顺手对齐，已写进迁移头）：`ops:operlog`(290)/`ops:pricing:view`(718) 各带一个在用 F 子节点（`ops:operlog:export`、`ops:pricing:edit` 前后端均有实引用）；`ops:nav:*` 7 个 M 目录行按设计不进代码（侧栏分组由「有无可见子页」推导，`config/sidebar.ts:176-184`）；`ops:recognition-demo:view` 由两条门禁按 V138 显式豁免，去留属 R8 待拍板 #1。
  - 权限矩阵正向同轮全绿：56 个导航码逐个都有 ACTIVE C/M 行（0 缺失、0 INACTIVE）。
- 后续建议（本轮未做，避免脏树期加 CI 红面）：把「反向」判据做成静态门禁——新迁移里 INSERT 的 C/M 码必须出现在导航 perm / 后端字面量 / 被引用的 F 子节点的父行 / 后续 RETIRED 语句之一。当前 `check-menu-permissions`、`check-frontend-perm-codes` 只查正向。

### MA-022 dev 端内嵌看板恒 404，与同页「在线」判定自相矛盾（P3，已修）

- 级别/状态：P3 / **fixed**（2026-11-01，Qoder R8 批 2；发现锚 `911ebf4b` → 修复锚 `55bac20f`）
- 端与页面：admin `/devops` DevOps 中心、`/observability` 日志中心
- 复现步骤：vite dev（`:3100`）登录后开这两页 ⇒ console 分别 1 条、6 条 404，全落在 `/devops/grafana/d/ai-cabinet-*`；同页四张卡却显示「Grafana 看板 · 在线」。
- 根因：在线探针是**后端**发起（`DevOpsHubService` 打 `aicabinet.devops.grafana-url`，容器网络内可达），而 iframe 用**浏览器同源路径** `/devops/grafana/…`；vite `server.proxy` 原先只有 `/api` ⇒ dev 下该前缀由 vite 直接 404。
- 生产形态不是缺陷（已实证）：带 htpasswd 凭据 `curl http://127.0.0.1/devops/grafana/d/ai-cabinet-overview/…` → **200**（网关 Basic + Grafana 自身登录，`GF_AUTH_ANONYMOUS_ENABLED=false`、网关剥 `Authorization`，见 `infra/gateway/nginx-full.conf:167-192`）。
- 修复记录：[vite.config.ts:60-68](file:///D:/ai-generated%20code/ai-cabinet/clients/admin-vue/vite.config.ts#L60-L68) 增 `/devops` 代理，默认 `http://127.0.0.1:13000`（compose 已发布该端口，root_url 含子路径故无需 rewrite），可用 `VITE_DEV_DEVOPS_TARGET` 覆盖；不动生产链、不写凭据。
  - 实跑证据：另起临时 dev（`:3199`，不动在跑的 `:3100`）后 `GET /devops/grafana/api/health` → **200**（改前同路径 404）。
  - 该文件不参与打包（只影响 dev server），故本轮不需重建 admin 产物。
- 关联观察（未领号）：`/observability` 六个 iframe 一次全挂（`v-for` + `v-show`，刻意换切页即时性），代价是打开即并发 6 个看板查询——留 R10 横切面评估「首次访问才挂载」。

### MA-023 会员折扣在「商品卡」与「订单账」两层都不现身（P2，展示侧已修 / 记账侧待拍板）

- 级别/状态：P2 / **展示侧 fixed、记账侧待拍板**（2026-11-01，Qoder R9 批 1；发现锚 `55bac20f` → 修复锚 `a96b57c8`）
- 端与页面：consumer-mp `pages/index/index` 商品卡（原价/活动价）+ 三端订单优惠拆解（`cabinet_order.member_discount_cents`）
- 真值链（同一笔真单 `1793498414728440537903` 逐源钉）：
  `sku_catalog.price_cents` **350** →（会员 98 折，`MemberService.applyMemberPriceDiscount:377-395`）→ **343** →（活动 4 `SKU_REDUCE reduce_cents=100`）→ **243**；
  订单行 `unit_price_cents=243 / activity_discount_cents=100`，订单头 `total=243 / paid=243 / member_discount=0 / coupon_discount=0`；
  排除其它定价来源：`device_sku_price` 无该 SKU 行、`near_expiry_price_cents` 为空。
- 子缺陷 ①（展示侧，已修）：商品卡的 `priceCents` 下发的是**目录价**，而 `activityPriceCents` 按**会员后价**再减 ⇒ 卡面「原价 ¥3.50 / ¥2.43 罐 / 活动价」三者减不平（差 0.07 无处解释）；非活动商品同样「显示 3.50、结算 3.43」。
  - 修复：`DeviceCatalogService:97-110` 的 `priceCents` 改为本用户无活动时的应付单价（会员价；`NearbyDeviceService` 走 `listProducts(deviceId)` → `userId=null` → 原价透传，不受影响）。
  - 单测：`DeviceCatalogServiceTest.listProducts_priceCentsIsWhatThisUserPaysWithoutActivity` 钉 `priceCents=343`、`activityPriceCents=243`、差额=100；整类 **8/0/0**（surefire `tests="8" failures="0" errors="0"` 实取）。
  - 🔴 运行时复验未做（dev 容器仍是旧 jar）——不谎报已上线；重建后重跑 `.tmp/r9-consumer-walk.cjs` 看卡面三数即可。
- 子缺陷 ②（记账侧，待拍板）：`cabinet_order.member_discount_cents` **全仓无写入者**（`grep setMemberDiscountCents` 在 `main/java` 0 命中），恒 0；而 `DataConsistencyService:1557-1580` 的对账式 `lineSum − coupon − member = payableFromLines` 正在读它 ⇒ 会员折扣在账面上完全隐身，商户分账与按比例退款读的是账面金额，拿不到这一层。
  - 为什么不当轮修：这是**资金字段语义 + 巡检规则联动**（一旦补值，PAID_GROSS_MISMATCH / ORDER_AMOUNT 两条规则可能从「平」变「不平」），按本程序既定边界与记忆规则（资金须数字实证）应单独一批做：结算写入该列 → 巡检口径复核 → 三端明细加「会员优惠」行 → 非零钉测 + `money-recheck.sql` 四层互证 + 留痕。
- 证据：卡面三数取自 automator 文本快照（`pages/index/index` 渲染节点：`活动价 | 原价 ¥3.50 | ¥2.43/罐`）+ 上述 SQL/API 逐源取值，见 rounds/R09-miniprograms.md 批 1 表。
  🔴 取证缺口：`evidence/R09/pages_index_index.png` 存下来的是**落地页**（同路由被第二次访问覆盖，购物态未留图）——不拿它对价格下结论，重建后的复验轮补图。
- 关联：CB-082 v1 的活动价展示链（本轮把「原价可减出实付」补为硬判据）；R8 MA-020 同属「三端明细可解释性」族。



### MA-039 补扣链缺「活动单禁券」闸（少收一张券面额）
- 级别/状态：P1 / **fixed（钉测 + 活栈实证，含无活动对照腿）**（2026-11-02，Qoder 三端审计；修复锚 `05ec42c8` + 产物 `f3f078ce`）
- 端与页面：消费者 `POST /api/v2/orders/{id}/pay` 与 admin 待支付「代收」（同一 `markPaid` 链）
- 发现人/日期/HEAD：Qoder 三端审计（轨 A1） / 2026-11-02 / `285bb48e`
- 期望 / 实际：CB-082 拍板「活动价与券二选一，活动优先」⇒ 活动价单补缴仍自动选最优券再减一张
- 源码定位：`UnpaidOrderService.applyBestCouponForCollect`（主链闸在 `SettlementOrderFinalizeService:84`）
- 触发链（静态推演）：结算余额不足 → PENDING（活动单本就不带券）→ 充值 → `collectByUser` → `markPaid`
- 修复记录：补 `activityDiscountCents > 0 ⇒ 不选券、清券字段、应付=活动价小计`；钉测 `UnpaidOrderConcurrencyTest.collectByUser_activityOrder_doesNotStackCoupon`
- 活栈实证（2026-11-02，`scripts/e2e-activity-unpaid-collect.ps1` 腿 1）：真柜模拟购物 → 余额不足转 PENDING
  → 充值后 `POST /api/v2/orders/{id}/pay` ⇒ `coupon_id` 仍 NULL、券抵扣仍 0、渠道实扣 486、余额 5000→4514、
  那张 ¥2 无门槛券仍 UNUSED；**对照腿**（停掉活动、同人同档券）补缴时券被照常选中并核销
  （486 = 700 − 14 − 0 − 200）⇒ 证明「不复用」是活动闸的效果，不是券不够格
- ⚠️ 订正：**报告原稿的第二半「补扣时覆写 gross 毛额列」是误报**——`originalAmountCents` 是 `@TableField(exist=false)` 瞬态字段，全仓无 `original_amount_cents` 列，`gross_amount_cents` 唯一写入点在 `SettlementOrderSupport`；真实缺陷另立 **MA-040**（lessons #379）

### MA-040 活动单转 PENDING 把活动优惠一起还原（待支付金额虚高）
- 级别/状态：P2 / **fixed（钉测 + 活栈实证）**（同上批）
- 现象：`clearCouponSelection` 用 `originalAmountCents` 还原「折前应付」，而活动单的该值是**活动前**合计 ⇒ PENDING 落库 total = 活动价 + 活动优惠（例：应 1026 落 1176），风控事件与催缴短信同源
- 修复记录：改为按 `total + couponDiscount` 还原；钉测 `SettlementOrderFinalizeAmountTest` 两例（券单/活动单）
- 活栈实证（同脚本腿 1 直接读库）：PENDING 落库 `total_amount_cents=486`，恒等式
  `gross 700 − member 14 − activity 200 − coupon 0 = 486` 成立；修复前这里会落 700（虚高 200，
  催缴短信与风控事件同源虚高）

### MA-041 admin 新建调拨表单缺有效期、批次标注可空（写路径整条断）
- 级别/状态：P1 / **fixed（代码 + 产物 + 活栈 UI 实证）**
- 端与页面：admin `/warehouse/fulfillment` 仓储履约 → 仓间调拨 tab → 新建调拨
  （⚠️ 路由不是 `/warehouse/transfers`：`clients/admin-vue/src/router/index.ts:13` 的 `ROUTE_TAB_SUB` 把 `transfers` 映射到 `fulfillment`，`/admin/warehouse` 直接落地 `/admin/warehouse/master`）
- 现象：MA-038 只把后端收紧成「批次/有效期必填否则 400」，弹窗无有效期控件、批次 placeholder「可空」、payload 从不发 `expiryDate` ⇒ 任何填写组合都 400；台账 MA-038 的「活栈实证」是 API 直调，UI 路径没复验
- 修复记录：`WarehouseTransferDialogs.vue` 补有效期日期控件 + 批次必填 + 两列 `form-grid`（顺带收 MA-058）；`useWarehouseTransfers.ts` 校验 + payload 透传 `expiryDate`
- 活栈 UI 实证（2026-11-02，Playwright 驱动本机真 Chrome + localhost/admin，8 判据全过）：
  A1 弹窗有「有效期」必填项 / A2 `form-grid` 两列（`396.005px 396.005px`）/ A3 批次占位符已不含「可空」/
  B1 批次留空被前端拦下（toast 出现且 `posts=0`，不发 POST）/ C1 创建请求发出 /
  C2 payload 带 `batchNo=B-VERIFY-20261102` + `expiryDate=2027-01-31` / C3 后端 `http=200 code=0` /
  C4 UI 提示「调拨单已创建」并刷新列表（transferId=7）
- 回归脚本：`scripts/verify-warehouse-transfer-form.mjs`（跑完自动 `POST /transfers/{id}/cancel` 收尾，库里只留序列烧号）
  截图 `docs/module-audit/evidence/MA-041-transfer-form-created.png`

### MA-042 清库脚本仍 DELETE 两张已 DROP 表
- 级别/状态：P1 / **fixed**（脚本级；下一次清库链跑批即验证）
- 证据：`cleanup-test-data.ps1` 单事务 + `ON_ERROR_STOP=1`，`revenue_share_detail`（1 处）与 `promotion_device`（2 处）已被 V431 DROP ⇒ 首条即 relation does not exist
- 影响面：`BROWSER_FULL_UAT_PLAN`/`BROWSER_MIN_UAT`/`MASTER_TEST_PLAN`/`docs/uat/FULL_TEST_SESSION_PROMPT` 等跑批文档都依赖该脚本
- 修复记录：删三处引用并注明 V431；复查 V431 全部 11 张表 ⇒ 脚本已无第四处；历史 `docs/uat-screenshots/2026-09-12/wipe-business.sql` 加「勿重跑」头
- 门禁候选（未做）：删表后自动扫 scripts/docs 引用——AU-186/FS-15 同型第三次

### MA-043 争议改单按当前活动重定价 + 旧券照折（双折）
- 级别/状态：P2 / **fixed（钉测 3 例）**
- 拍板（用户 2026-11-02 授权按推荐）：**成交即定价**——改单沿用原单每行的活动决策（`activity_id` + 每件减额），原单没活动的行不捡新活动，只有新增 SKU 按当前规则定价；此时订单级仍走「活动优先禁券」
- 竞品依据：Shopify 官方草稿单 price lock（未锁定才跟目录最新价）+「编辑订单后部分折扣不会重新计算」
- 修复记录：`SettlementOrderSupport.applyItemsToOrder(order, items, preserved)` + `preservedActivityFromLines`，`SettlementConfirmDisputeService` prepare/finalize 两处都传；`recalculatePayableAfterLineChange` 补活动禁券闸
- 活栈实证（2026-11-02 腿 1b）：结案后的活动单（单价 243 / activity_id=4 / 活动留档 200）先把活动 STOP，
  再重开争议工单 CONFIRM **同一份 items** ⇒ 走「已有订单」的改单分支：total 486 不变、行三列逐字不变、
  `payment_operation` CHARGE COMPLETED 仍 1 条（补差 0 ⇒ 未二次扣款）；按当前活动重算会跳到 686
- 活栈实证·腿 5（2026-11-03 补齐，同一单同一次 CONFIRM 里 items=100013×2 + 100014×1）：
  原行 `2|486|1` 逐字不变（活动 4 已停仍守 243/件），新增行 `unit_price=293 / activity_id=6`（343 会员价再减 50）
  ⇒ 两半口径同时成立；`total=779=行合计`、`coupon_id=NULL`（新增行命中活动 ⇒ 订单级仍禁券）、
  补差走 `ADJUST_CHARGE|293` 一条且余额 4514→4221 恰等于新行金额
- ⚠️ 判据曾假红两次：① `seed-demo-data.ps1` 的 `fallbackSkuId` 会随在售面变化改挑 SKU（腿 5 给 100014 建账面后
  整轮错位）⇒ 现在 $Sku 以**夹具活动的目标 SKU** 为准；② 改单补差是 `ADJUST_CHARGE` 不是 `CHARGE`

### MA-044 活动限购 check-then-act 可并发穿透
- 级别/状态：P2 / **fixed（单测 + 真库 IT）**
- 现象：`withinUsageLimits` 只读计数无锁；结算锁粒度是 `session:settle:{sessionId}`，同账号两柜并发可双吃活动价；代码里也无「单用户只允许一个进行中会话」约束
- 修复记录：新增 `resolveUnitPriceForOrder`（结算/改单链按 activityId 升序锁活动行，锁随外层事务提交释放；展示链不加锁），计数排除本单 `countOrdersForActivityExcluding`；钉测 `PromotionPricingServiceTest` + `PromotionPricingMapperIT` 真库两断言

### MA-045 部分退款「渠道成功、本地收尾失败」后 409 死路
- 级别/状态：P2 / **fixed（钉测 1 例 + 活栈实证）**
- 活栈实证（2026-11-02 腿 4）：2 件的活动单先退 1 件（`PARTIAL_REFUNDED`、已退 243），再**原样重复同一笔**
  退款请求 ⇒ 第二次返回成功而非 409「请勿重复提交」；`REFUND COMPLETED` 仍 1 条、`refunded_cents` 仍 243
  （未被二次摊薄）。注：1 件的单会直接退成 `REFUNDED`，被上层「订单已退款」前置闸拦下，走不到幂等恢复分支
  ⇒ 复现夹具必须买 2 件退 1 件
- 活栈实证·落点②③（2026-11-03 补齐，**状态级注入**：拿一次真实成功退款后的库状态往回倒，不往生产码塞故障钩子）：
  ①全完成后同参重放 ⇒ 200 幂等、金额与已退累计不变；
  ③行/库存留在退款后、状态回 PAID、已退归 0、标记保留 ⇒ 重放只补 finalize，收敛回基准态、`REFUND COMPLETED` 仍 1 条；
  ②行/库存/应付全部回退到退款前并抹掉 `[LOCAL_PREPARED]` ⇒ 重放重跑 prepare+finalize，
    行只扣一次（`1|243|1`）、库存只回补一次（`9|9`）、渠道零二次调用。
    每一落点还顺带钉住「可售批次 == 汇总账面」的自洽判据
- 现象：C04 注释承诺「同参安全重试」，实际 `refundOrder` 对 COMPLETED 幂等键返回 false ⇒ 直接 409，本地 total/行/status 永远补不上（钱已在远端退掉）
- 修复记录：按行集指纹反查已完成流水（`findCompletedRefundByFingerprint`）→ 恢复本地 prepare/finalize；prepare 与 `payment_operation.reason` 的 `[LOCAL_PREPARED]` 标记**同事务**写入，重试见标记即只补 finalize，避免重复扣行；无法判定时保留 409 交人工

### MA-046 分账重试调度器把微信 HTTP 包进外层事务 + 坏行中断整批
- 级别/状态：P2 / **fixed（单测 1 例；分账未启用 ⇒ 启用当天看一轮日志）**
- 订正：报告原例「receiver 缺失的行排队头」不成立——`recordSplit` 对 receiver 为空的商户直接落 `LEDGER_ONLY`，不会进 `WECHAT_FAILED` 队列；真机制是「无逐行 catch ⇒ 一条抛异常整批回滚」+「>20 条永久失败行占满 top20 窗口」
- 修复记录：调度器与三个批方法去 `@Transactional`；HTTP 全部移到事务外（`applyReturnOutcome`/`persistSplit` 短事务走 self 代理）；逐行 try/catch + 跳过行留日志；`WeChatProfitSharingRetryIsolationTest` 钉「一条坏行不得让其余行未执行」

### MA-047 支付分回调漏免登录白名单
- 级别/状态：P2 / **fixed（钉测 + 活栈实证，本地与服务器双验）**
- 活栈证据：修后 `POST /api/v2/payment/payscore/notify` = **400**（缺 `Wechatpay-*` 头，说明已进控制器），对照 `/api/v2/orders` = 401（其余端点未放宽）；本地 `:18080` 与服务器 `:8180` 各测一次
- 修复记录：`WebConfig.AUTH_EXCLUDE_PATTERNS` 提为常量并登记 payscore 路径；`AuthWhitelistTest` 用控制器真实映射反查白名单（防新增回调端点漏登记）
- 前置依赖：整链仍未接通（`PAYSCORE_SERVICE_ID`/`PAYSCORE_PERMISSION_NOTIFY_URL` 未配 ⇒ 真实签约恒 501）

### MA-048 V431 删表守卫是环境依赖的（部署前置检查）
- 级别/状态：P2 / **fixed（runbook 项，不改代码）**
- 机制：V222 曾向 `revenue_share_detail` 补最多 5 行演示数据；V431 逐表 `count(*)>0` 即 RAISE（fail-loud 设计如此）⇒ 从未清库的存量库部署时启动阻断
- 实测现状：本机 dev `max(version::int)=434`、服务器 V433（11 张表 DROP 前均 0 行）⇒ **本轮无任何清库/改数待办**；检查只对将来的新生产库生效
- 修复记录：`DEPLOYMENT_CHECKLIST §3.1.4` 补逐表 count SQL + 点名该表 + 写明「不得为了过迁移注释守卫」

### MA-065 缺 Wechatpay 请求头冒 500（MA-047 落地后活栈新挖）
- 级别/状态：P3 / **fixed（钉测 1 例 + 活栈复验）**
- 现象：全局只映射了 `MissingServletRequestParameterException`，没映射 `MissingRequestHeaderException` ⇒ 渠道探测/漏带头就落通用 500「系统繁忙」并刷 ERROR
- 修复记录：`GlobalExceptionHandler.handleMissingHeader` → 400 + 头部名；`d09e4865`

### MA-066 欠款转待支付把事务标成 rollback-only（争议确认必 500、整链回滚）
- 级别/状态：P2 / **fixed（钉测 4 例 + 活栈实证）**
- 来源：MA-039/MA-040 活栈资金链复现时挖出（非原报告条目）
- 现象：余额不足用户走「争议确认 CONFIRM 生成订单」这条链，`finalizeOrder` 内 `chargeOrder` 扣款失败被吞掉转 PENDING，
  但提交时抛 `UnexpectedRollbackException: marked as rollback-only` ⇒ HTTP 500，订单/工单/风控事件/通知**整链回滚**，
  运营台反复点确认都失败（活栈两次实测：traceId 3655c048ccbe、4269c229eea7）
- 根因（两处叠加，缺一不成立）：
  1. `BalanceLedgerService.change` 用**编程式** `TransactionTemplate`（无 `noRollbackFor` 概念），回调里抛出的
     412 `ResponseStatusException` 会把**参与**的外层事务标成 rollback-only；
  2. `chargeOrder` 只声明 `noRollbackFor = BalanceInsufficientException`，而账本抛的是 `ResponseStatusException` ⇒
     跨代理边界时按默认规则同样标 rollback-only（F1-B 的「信号链不毒化事务」只覆盖了 `captureForCharge` 那一支）
- 修复记录：账本在回调内收下「余额不足」412、事务与锁退出后再抛（`BalanceLedgerServiceTest` 2 例：
  `insufficientBalance_participatingTxIsCommittedNotRolledBack` 断言 `commit` 被调、`rollback` 从未被调；
  `otherLedgerFailure_stillRollsBackParticipatingTx` 反向钉住其它异常仍回滚）；
  `OrderPaymentService.changeBalanceForCharge` 把该 412 折成 `BalanceInsufficientException`
  （`OrderPaymentSelectedChannelTest` 2 例；`GlobalExceptionHandler` 仍映射 412 ⇒ HTTP 口径不变）
- 同型第三例：审计 P1-1（`runWithOrderPaymentLock` 原样穿透两种类型）、BUG-007（412 前置校验挪到动账之前）

### MA-067 「待支付不占券」只改了内存对象（coupon_id 悬空留在库里）
- 级别/状态：P2 / **fixed（钉测 1 例 + 活栈实证）**
- 现象：`SettlementOrderFinalizeService.clearCouponSelection` 把 `couponId` 置 null、抵扣清零、应付还原，
  随后 `orderRepository.save(order)`。但**扣款失败转 PENDING**这一支里订单已在 `finalizeOrder` 早期 insert 过 ⇒
  `BaseTradeMapper.save` 走 MP `updateById`，**null 字段被跳过** ⇒ 库里 `coupon_id` 仍是那张券，
  而 `user_coupon` 仍 `UNUSED`、`coupon_discount_cents` 已归 0（2026-11-02 活栈实测：PENDING 行 total=343、券抵扣=0、coupon_id=4）
- 影响：①订单显示「用了券」但券其实没收（`admin` 优惠拆解列/消费者单详情同源）；
  ②`recalculatePayableAfterLineChange`、`applyProportionalRefundPayable` 都读 `order.getCouponId()` 重算券抵扣
  ⇒ 对一张从未核销的券再折一次 = 少收/多退；③补扣 `applyBestCouponForCollect` 见 coupon_id 非空就直接按旧券折，
  跳过「门槛 + 设备范围」重选（实测腿 2 恰好折了同一张 ¥2 券，金额正确纯属同额巧合）
- 修复记录：新增 `CabinetOrderMapper.clearOrderCouponSelection`（显式 UPDATE 置空 coupon_id/券抵扣并写回应付），
  两支「已 insert 后转 PENDING」的收尾统一走 `markPendingAndDropCoupon`；`clearCouponSelection` 的 javadoc
  明写「只改内存对象，已 insert 的订单必须再走 markPendingAndDropCoupon」；
  钉测 `SettlementOrderFinalizeAmountTest#chargeFailPending_issuesExplicitCouponClearUpdate`
  （stub 券选中→扣款 412 ⇒ 断言显式 UPDATE 被调用且金额是还原后的 343）
- 活栈判据：`scripts/e2e-activity-unpaid-collect.ps1` 腿 2 直接断言 PENDING 行 `coupon_id IS NULL`
- 遗留数据：修复前生成的 PENDING 单可能留悬空 coupon_id；上线后只读判据
  `SELECT count(*) FROM cabinet_order o JOIN user_coupon c ON c.coupon_id=o.coupon_id WHERE o.status='PENDING' AND c.status='UNUSED'`

### MA-148 布尔型「资金动作开关」在审计总开关关闭时零留痕
- 级别/状态：P3 / **fixed（钉测 1 例 + 活栈实证）**
- 来源：给争议自动免单做后台旋钮时自查发现（编号原取 MA-068，与并行会话撞号后改 148）（`docs/P3_4_DISPUTE_AUTO_WAIVE_DESIGN.md §8`）
- 现象：`SystemConfigService.recordChangeIfEnabled` 只在 `ops.config.audit.enabled` 为真、或键在
  `FUND_GUARDED_KEYS` 时才写 `system_config_history` + `admin_audit_log`。dev 库里该开关是 **false**（实测），
  而 `dispute.auto_waive.enabled` 这类布尔键**加不进** `FUND_GUARDED_KEYS`：那个集合同时挂
  `validateFundKeyValue`（`Long.parseLong`），写 `"true"` 会当场 400
  ⇒ 「谁把自动免单打开了」这条最该追责的信息，历史与审计两边都查不到
- 与 lessons #319 的关系：那轮删掉的是**没有活消费者的** `ALWAYS_AUDITED_KEYS`；本键是真实需要强制留痕的
  第一个布尔资金开关 ⇒ 只留「一个集合 + 一个条件」，不重建值域白名单那套
- 修复记录：新增 `AUDIT_FORCED_SWITCH_KEYS = {dispute.auto_waive.enabled}`（**只管留痕，不做值校验**），
  `recordChangeIfEnabled` 判断加上该集合；钉测 `SystemConfigFundKeyGuardTest#upsert_auditForcedSwitchKey_auditExplicitlyDisabled_stillLeavesHistory`；
  活栈 `scripts/verify-auto-waive-knob.mjs` K5：审计开关=false 的当前库里，开关键写两次 ⇒ 历史与审计各 +2，
  同时对照普通调参键 `dispute.auto_waive.hours` 仍 0 条（按设计，非本集合的键继续受总开关约束）
- ⚠️ 未做（刻意）：没有把审计总开关本身改成默认开——库里那行 `false` 是既有环境事实，
  要不要在 dev/线上打开它是另一件事（要动就得连带说明「所有参数写入都会进审计表」的量级影响）

### 本批口径说明（2026-11-02 三端审计）
- 报告标题 **P1×3 / P2×6 / P3×18**；明细可数的 P3 只有 16 条，已按 MA-049…MA-064 + MA-065 全部入账，**差的 1–2 条 P3 明细报告里没展开**（需原作者补出后另行领号，不并入现有号）。
- 两条报告结论被复核订正：MA-039 的「gross 覆写」段（误报，见 lessons #379）、MA-046 的「receiver 缺失坏行」例（机制换掉，见 lessons #380）。

### MA-068 盘点凭证上传缺设备归属校验（跨商户写）
- 级别/状态：P2 / open
- 端与页面：`POST /api/v2/merchant/devices/{deviceId}/stocktake-evidence`
- 源码定位：`MerchantPortalService.java:278-283` → `FileAttachmentService.uploadDeviceStocktakeEvidence:375-414`（只校验非空/张数/大小/图片类型，按 refType=DEVICE_STOCKTAKE 直落）
- 复现推理：商户 A（有 merchant:slots:edit）对商户 B 的 deviceId POST multipart → 200，照片挂进 B 的盘点凭证链（photoStocktake 强制凭证政策可被跨租户污染）；同 Controller 其余设备写端点全过 requireDevicePack，本端点连读路径 streamStocktakeEvidence:286-293 都有 requireDeviceAccess，唯独上传漏。
- 修法方向：uploadStocktakeEvidence 补 `requireDevicePack(userId, deviceId, FIELD)` 一行收口。

### MA-069 线长佣金参数无界直落（持续性资金乘数）
- 级别/状态：P2 / open
- 端与页面：`POST /api/v2/ops/admin/line-managers`、`PATCH /{managerId}`
- 源码定位：`LineManagerController.java:52-63` + `LineManagerService.java:129-130,187-191`；资金落点 `LineCommissionJob.doPostCommissionTx:186-204`
- 复现推理：rateBps=50000（GMV 5 倍佣金）/负 fixedCents 均可入库，佣金按日真实入钱包；对照先例=商户钱包调账 ±1 亿分限幅（WalletAdjustRequest javadoc）。负数被 Job `commission<=0` 兜住、修改有审计，判校验缺失非设计。
- 修法方向：create/update 对 rateBps 限 [0,10000]、fixedCents 限 [0,上限]，超限 400。

### MA-070 「组织与点位」权限码双轨（AND/OR 混用，删比改松）
- 级别/状态：P2 / open
- 端与页面：admin `/org-sites`（OpsOrgController.java:43-107）
- 源码定位：`OrgService.java:26,80,115,154,191`（AND 强制 ops:device:*）vs `:209 deleteNode requireAnyPermission("ops:org:edit", device:edit)`（OR）；SiteContractService:84,119 同形
- 复现推理：只授 `ops:org:*` 的角色（V170 语义）→ 页面 403、增改 403、DELETE 成功；无提权面（收紧方向），属一致性/可用性缺陷。
- 修法方向：服务层统一 requireAnyPermission（与 delete、与 OpsRbacController:182 双轨先例一致）。

### MA-071 ~ MA-081（P3，明细从简，锚 rounds/R12-write-surface.md 批 1 战果）
- MA-071 RBAC 三个整删整插分配端点重复 ID → DuplicateKey 500（OpsRbacService.java:361-375,622-630,670-688；修法 LinkedHashSet 去重）
- MA-072 运营改密/2FA confirm/disable 三处零审计（OpsRbacService:558-570、OpsTwoFactorService:108-128,157-182）
- MA-073 提现 APPLY 动作无审计 + 运营代提低于阈值自动打款分支操作者不可归因（MerchantWithdrawService:230-257,424-494；LineManagerController:106-114 submitter 恒 null）
- MA-074 补货员钱包 payout 打款标记无操作者审计（OpsStaffWalletController:47-66）
- MA-075 线长 PATCH 换绑 userId/改名/改手机不进审计，longVal 不校验用户存在（LineManagerService:145-166,168-199）
- MA-076 商户补货配置与开票税务资料两处配置写无审计（CompetitiveGapService:301-328、InvoiceService:174-204）
- MA-077 收款账户 ownerType 无白名单、未知值落只读权限分支可写（OpsPayoutAccountController:52-103）
- MA-078 merchant.status 自由串直落（MerchantService:151-152 + V15 列定义；白名单归一 + @Size(16)）
- MA-079 字典类型硬删无内置类型门槛，联动 resolveNodeType fail-open（SysDictService:251-265,76-80）
- MA-080 组织节点 upsert 缺父级存在性/祖先环校验，环致子树从 tree() 静默消失（OrgService:114-149；对照 DepartmentService:100-114,263-275）
- MA-081 bind-openid 重复绑定 500 而非 409（AccountController:78-87、AccountService:222-229、V301 唯一索引；mock-only）

### MA-082 营销域运营写面审计留痕整体缺失（15 端点合并登记）
- 级别/状态：P3 / open
- 位置：CouponService.java:77-214（模板/发券）、PromotionService.java:42-149（活动/上下架）、PromotionPlacementService.java:70-84（展示位）、MediaAssetService.java:91-182（素材）
- 证据：五服务零 appendLog（grep 证实）；同域 AdCampaignService.java:156-200 四处全有 AD_CAMPAIGN_*；争议域 DisputeService 三处全有。判「漏网」依据：同包兄弟服务都是后加功能统一落审计，非「营销域约定不审计」。
- 修法方向：照 AdCampaignService 模式补 COUPON_DEF_UPSERT/COUPON_ISSUE/PROMOTION_LAUNCH/STOP/PLACEMENT_REPLACE/MEDIA_ASSET_DELETE 动作码。

### MA-083 ~ MA-089（P3，明细从简，锚 rounds/R12-write-surface.md 批 2）
- MA-083 BatchIssueCouponRequest/IssueCouponRequest 零校验注解（common-core dto；修法 @NotNull + @Size(max=1000)）
- MA-084 MediaAssetService.java:148-150 status trim+toUpperCase 直落（白名单 ACTIVE/INACTIVE + @Size(16)）
- MA-085 PromotionService.java:44-56 activityType/budgetCents 原样落库；:162-166 `budget>0 &&` 负数 fail-open（白名单归一 + @Min(0) + 钉「0=不限」口径）
- MA-086 AdCampaignService.java:136-137,149-150 无 endAt.isAfter(startAt) 校验（对照 PromotionService:112-115）
- MA-087 CreateCouponRequest/UpdateCouponRequest/UpsertAdCampaignRequest/UpsertMediaAssetRequest/CreateSessionRequest.idempotencyKey 补 @Size 对齐列宽
- MA-088 PaymentService.java:387,451 refund_id 空白时应告警 return 或键改 orderId+refundId（:451 现态键塌缩）
- MA-089 ApplyBalanceRefundRequest.java:8 @Max(500_000) 与 BalanceRefundService.java:133 可配置上限二选一对齐

### MA-090 机器账单分成比例无界（持续性资金乘数，P2）
- 级别/状态：P2 / open
- 端与页面：`PUT /api/v2/ops/admin/machine-payee-bills/config/{deviceId}`（ops:device:edit）
- 源码定位：`MachinePayeeBillService.saveConfig:151-159`（整 body 直落零校验）→ `upsertShareBill:79,94`（bps 仅拦 ≤0，`gmv*bps/10000` 直出账）；V356 `share_bps INT` 无 CHECK
- 复现推理：PUT config `{shareBps:50000,...}` → 200；`POST /generate?billMonth=2026-10` → SHARE 账单=GMV×5，且每个后续账期持续生效。三处兄弟先例（SiteRentSplitService.java:72-74 / FundBillChannelFeeRateTest / SiteRentBill.allocate）都有闸，判漏网非设计。
- 修法方向：saveConfig 校验 shareBps 0..10000、electricAmountCents≥0、enabled∈{0,1}、payeeName 非空；可加 DB CHECK 兜底。

### MA-091 ~ MA-099（P3，明细从简，锚 rounds/R12-write-surface.md 批 3）
- MA-091 OpsMerchantFeeLedgerController.java:57-65 + MerchantFeeLedgerService.java:39-57 + MachinePayeeBill settle：补 operatorId 透传 + settled_by 列或 appendLog（SiteRentBill V309 现成范式）
- MA-092 InvoiceService.java:130-161（issue/reject 无 issuedBy 无 appendLog）+ MachinePayeeBillService.java:52-64 + ReconciliationService.java:117-127（operatorId 传而不用）
- MA-093 PlatformFundService.java:127-131,154-162 recharge 前置 amountCents<=0→400；Controller Map 取参 DTO 化（3dd6152f 有先例）
- MA-094 OpsMachinePayeeBillController.java:44-46 改用 FeeBillMonthResolver.requireValid + 未来账期守卫
- MA-095 MemberLevelAdminService.java:66-99：min/max≥0 且 min<max、pointsRate≥0、priceDiscountPct 0-90 对齐读侧、status 白名单、levelCode/Name @Size
- MA-096 PointsRedeemService.java:166-181：pointsCost≥0、stockTotal≥0、couponDefId 非空且存在且 ACTIVE
- MA-097 RiskControlService.java:167-173 removeBlacklist 补 recordEvent("BLACKLIST_REMOVE", by=operatorId)；顺手 addBlacklist 校验 userId 存在/expiresAt 未来
- MA-098 对齐 MA-082 修法：MEMBER_LEVEL_UPSERT/POINTS_REDEEM_UPSERT/USER_RECALL/NOTIFY_* 补 appendLog
- MA-099 UserRecallService.java:42-93：召回幂等键（请求体传入+短窗去重）或 per-user 限频；body 解析包 400

### MA-100 线路成员佣金参数无界零审计（资金分配参数，与 MA-069/090 同族第三例）
- 级别/状态：P2 / open
- 端与页面：`PUT /api/v2/ops/admin/line-routes/{routeId}/members`（ops:replenishment:edit）
- 源码定位：`OpsLineRouteController.java:74-81`（@RequestBody 无 @Valid）→ `LineRouteAdminService.upsertMember:105-122`（三佣金字段直落）；消费链 `LineCommissionJob.java:218-323`（`gmv*bps/10000` → `staffWalletService.creditIfAbsent(...,"COMMISSION",...)` 入可提现钱包，:318）
- 主会话复核（2026-11-03）：已亲自读源码确认零校验 + creditIfAbsent 直入钱包属实。
- 复现推理：PUT 写 commissionRateBps=1000000 → 次日回扫按该 bps 全额入钱包。G2-13 先例：LineManagerService 对同类参数已落 before/after appendLog——本端点漏网。
- 修法方向：bps 0~10000、perUnitCents≥0 设上界、basis/role 白名单（MANAGER/STAFF · GMV/RESTOCK_QTY）、userId 存在校验、变更落 before/after 审计；建议与 MA-069/MA-090 一次同模式收口（三处「佣金/分成参数无界」）。

### MA-101 外部导入同账期多批次重复 commit（数据灌入口静默污染）
- 级别/状态：P2 / open
- 端与页面：`POST /api/v2/ops/admin/external-import/batches/{batchId}/commit`
- 源码定位：`ExternalChannelImportService.java:294-299`（只挡同批次重提交）；V347 无 (channel_code, period_label) 唯一约束；消费侧 `WarehouseMonthlyCloseService.flowBySku:426-444` 无条件 Integer::sum
- 主会话复核（2026-11-03）：已亲自读源码确认 commit 闸只判 batch.status。
- 复现推理：preview→commit(batch1)→再 preview 同渠道同账期→commit(batch2)→流水翻倍、月结盘点/外调列翻倍；COMMITTED 不可删 ⇒ 只能三倍重导。
- 修法方向：commit 前查同 channel+period 已 COMMITTED 批次则 409（或显式 supersede 作废旧批留痕）+ partial unique index 兜底。

### MA-102 ~ MA-114（P3，明细从简，锚 rounds/R12-write-surface.md 批 4）
- MA-102 WarehouseTransferService.doCancel:222-237 补 operatorId + WH_TRANSFER_CANCEL appendLog
- MA-103 WarehouseMonthlyCloseSheetService:157-185,341-372 + ReplenishmentService:1601-1634 套锁+FOR UPDATE（或 @Version）
- MA-104 OpsReplenishmentController.java:59-63 + DeviceInventoryDto 补 @NotBlank/@Min(0)
- MA-105 OpsProcurementController.java:132-142 注解 OR 与服务端单权限二选一对齐
- MA-106 WarehouseTransferService.java:174-206 校验 reported lineId ∈ 本单行
- MA-107 SlotTemplateService.apply:130-201 补 assertNotDoorCabinet + requireDevice
- MA-108 OpsLineRouteController.java:40-56 缺必填 400 化 + managerId 存在性
- MA-109 WarehouseService.binStockChange:379-395 补 requireManagedOutboundWarehouse 或注释豁免依据
- MA-110 RepairTicketService.java:271-275 + OpsExceptionService.java:255-264 列宽截断（255/512/1000 三级）
- MA-111 UserFeedbackController.java:43-50 新增 ops:feedback:delete 码 + 软删或 appendLog
- MA-112 OpsExceptionController.java:111-115 marker 常量化统一英文格式（recordAction:413/resolveByAction:432 同改）
- MA-113 RepairTicketController.java:88-101 assignee 显式 null 判断 + ticketIds try/catch 400
- MA-114 外部导入 personOverride @Size(60) + 维修单 title/remark @Size（并入 MA-087/110 批次修）

### MA-115 OTA 固件发布/下架零归因（全仓最高危运维动作）
- 级别/状态：P2 / open
- 端与页面：`POST /api/v2/ops/admin/ota/releases`、`POST .../{releaseId}/unpublish`
- 源码定位：`OpsOtaController.java:33-45` → `OtaService.publishRelease:69-103`（operatorId 收下后全文未用）、`unpublishRelease:106-120`；`OtaRelease` 无 operator 字段（V9 无列）
- 主会话复核（2026-11-03）：grep OtaService 全文 appendLog/logChange 0 命中，属实。
- 修法方向：publish/unpublish 双端 appendLog（OTA_PUBLISH/OTA_UNPUBLISH）或补 operator_id 列；与 MA-116 一批修。

### MA-116 数据一致性修复零归因 + 证据覆写
- 级别/状态：P2 / open
- 端与页面：`POST /api/v2/ops/admin/consistency/{recordId}/fix`（ops:consistency:fix）
- 源码定位：`DataConsistencyService.java:1440-1467`（fixInconsistencyDetailed 无 operatorId 参数）、`:1453-1458`（error_message 覆写）、`:1604-1618`（tryAlignHeaderToPayable 直改订单头）、`:1743-1761`（fixPaymentAmount 触发真实退款）；`:176 logChange` 全仓零调用
- 主会话复核（2026-11-03）：grep 仅剩 logChange 定义 1 处（零调用点），属实。
- 修法方向：data_consistency_record 补 fixed_by + fix 链传 operatorId + appendLog；error_message 改追加不改写（保留原始不一致描述）。

### MA-117 ~ MA-125（P3，明细从简，锚 rounds/R12-write-surface.md 批 5）
- MA-117 JiangyiOnboardingService.java:62-369、JiangyiClassMappingService.java:34-45、JiangyiModelSyncService、JiangyiGatherService.java:142-328、SkuJiangyiLinkService.java:256-430 五服务零 appendLog；优先 tenant-domain/class-mapping/deleteInJiangyi/shelf 四类（范本 JiangyiDeviceOpsService.java:68-206）
- MA-118 OpsDeviceRentalController.java:49-57,119-132 longOrNull/intOrNull catch 后静默 null（改 DTO+@Valid 或至少 warn/400）
- MA-119 DeviceMqttCredentialService.java:54-80 + DeviceHttpCredentialService.java:52-79 签发前补 deviceMapper.selectById（404）
- MA-120 DeviceInternalController.java:73-87 → DeviceEnvService.java:39-52,90-99 各指标物理界（湿度 0-100 等），越界照 updateSignal 口径静默丢
- MA-121 JiangyiInternalController.java:407-422 register 补档案存在闸；:343-362 status/source 白名单归一
- MA-122 AnnouncementController.java:41-60 Map 改 DTO + targetScope 白名单(ALL/CONSUMER/MERCHANT) + priority 白名单 + @Size
- MA-123 AnnouncementService.java:144-199 三端点补 operatorId + appendLog；publish/archive 状态机或钉注释
- MA-124 DataConsistencyService.java:1445-1450 doFix 入口拒绝非 FAIL 记录；:1743-1761 fixPaymentAmount 检查 refundOrder 返回值
- MA-125 OtaService.java:71-92 发布前同渠道版本比较拒降级/同版 409、channel 白名单、grayPercent 1..100、presignTtl 上限

### MA-126 @Positive 校验失败误报「不能为空」（R17 活栈实测发现）
- 级别/状态：P3 / open
- 端与页面：`POST /api/v2/ops/admin/merchant-wallets/{id}/withdraw`（实测）——全仓所有用 @Positive 的 DTO 字段同面
- 源码定位：`ApiMessages.java:245-268`（formatValidationFieldError：非 CJK 消息走 RANGE_MIN_PATTERN → @Positive 的 "must be greater than 0" 无 "or equal to" 不匹配 → 落末尾兜底 label+"不能为空"）；对照 :259-261 的 2026-10-23 生产实证注释（修的就是 @Min/@Max 同款误导）
- 复现：活栈实测 amountCents=0 与 -500 均回「amountCents不能为空」（应「必须为正数」类文案）；`WithdrawApplyRequest` 用 @NotNull @Positive。
- 修法方向：RANGE_MIN_PATTERN 扩形式（"must be greater than 0" → 「必须为正数」），或改用 @Min(1)/@Positive(message=中文)；补 ApiMessagesTest 钉测。

### MA-127 ~ MA-136（P2，证据与修法锚 rounds/R16-cross-end-static.md）

- MA-127 活动行铺开：merchant order-detail.vue:38-45/orders.vue:109-121、admin OrderListView.vue:481-491/977-987、dispute-amount-note.ts:39-52 按 MA-024 同款摊开+类型补字段（merchant `OrderDetail` 与 admin `OrderAmountInput` 补 activityDiscountCents）。
- MA-128 merchant order-detail.vue:198-218 chargedCents 改以支付流水为源（total+refunded 或读模型下发 ledger charged），与钱包/分账基数 paidAmountCents 对齐；后端 original 重写语义需注释拍板（SettlementOrderSupport.applyProportionalRefundPayable:246）。
- MA-129 与巡检折后式（DataConsistencyService:1626）一起拍板：①部分退不动 member/activity 留档（回退 MA-049 等比缩）靠 gross−各档−已退=实付；②读面新增「剩余毛额」推导。二选一后页面基准同步改。
  ✅ 已选 ② 并按**零后端字段**落地（`d8ff932c`）：「剩余毛额」不落读模型，由前端四列恒等式算出，见上方批次F 块的订正。本条**状态以索引行 fixed 为准**，此处只留拍板前的选项原文。
- MA-130 tally-records.vue:169-176,234 改 displayLabel('restock_line_type'/'replenishment_task_status')（与 useReplenishmentDisplay.isPullOffType 反判口径统一）。
- MA-131 DeviceRentalView.vue:55,433-446 键改 VOID + UNPAID 词与费用账单字典族统一。
- MA-132/133/135：字典三处（迁移+基线+SysDictBootstrap）补 COMMISSION_CLAWBACK/WITHDRAW_FEE/STAFF_*/WITHDRAW_PAYOUT/DAILY_INCOME/FEE_REMINDER（DictLabelUniquenessIT 同 type 禁重名）；LineRoutesView.vue:232 改 displayLabel。
- MA-134 SysDictBootstrap:312-313 补 2 值 + mp purchase-display.ts 对齐措辞或改 displayLabel。
- MA-136 warehouse-stocktake.ts:61-71 改 displayLabel（若「录入/过账」是商户侧刻意话术则改字典两源）。

### MA-137 ~ MA-146（P3，明细从简，锚 rounds/R16-cross-end-static.md）

- MA-137 packages/shared-types/src/index.ts:402-438 补两字段或切别名；门禁「手写接口 vs 生成物 diff」。
- MA-138 scripts/check-no-inline-money-math.mjs:16-18 DIRS 加 composables（.ts）；存量 3 处迁 centsToYuanNumber。
- MA-139 MerchantWithdrawView.vue:255 改三态（== null → 未结算）。
- MA-140 基线+DB 补 SPLIT_REV=分账冲正；mp 本地 map 收敛到字典。
- MA-141 择词统一两源（SPLIT_REVERSE）。
- MA-142 shared-dict 注释钉「DISPUTE 分受众话术」口径。
- MA-143/144/145 字典两源对齐批量回补（一批迁移+基线 PR 全收）。
- MA-146 repair CLOSED 死值删除或状态机补齐；内联漂移顺手改。

### MA-147 全额退不返还已核销券（矩阵 E2E 实测，P2）
- 级别/状态：P2 / open
- 端与页面：消费端/运营端全额退（DisputeService.executeFullRefund → SettlementService.waiveAndRefund）
- 源码定位：`SettlementWaiveRefundService.java`（全文 0 处 coupon 引用，已 grep 实证）；券返还唯一写侧 `CouponService.settleAfterPartialRefund:566-585`（newSubtotal<=0 分支）+ 修复用 `releaseStaleUsedCouponsForOrder:606-618`——两条都不在 waive 链上
- 实测证据：R17 矩阵 E2E（SettlementComboMatrixE2ETest，Testcontainers 真 PG 全链）——券单（AMOUNT_OFF 200）全额退 200/200 对平、分账 VOIDED、钱包冲平，但 user_coupon.status=USED 未返还；测试内已按「已知行为」钉住断言（修复后翻转为 UNUSED 即回归位）
- 修复方向：waiveAndRefund（或 finalizeFullRefundTicket 前）补 `couponService.settleAfterPartialRefund(order, oldSubtotal, 0)` 等价调用（复用返还+预算释放幂等）；补钉测「全额退券返还 UNUSED」



## 批次A/B 收口留痕（2026-11-03，逐条回源码复核后修）

> 前置纪律：80 条 open 全部派复核（8 路并行）+ 主会话对**每一条将修的证据**回读源码。
> 复核三态与订正见本节末尾，未经回读的台账描述一律不直接开修。

### 批次A｜资金参数无界 + 越权写 + 重复 commit（5 条 P2）

- **MA-069 线长佣金 / MA-090 机器账单 shareBps / MA-100 线路成员三列**——同族同修：新增单源
  `support/CommissionParamBounds`（bps 0..10000、分/件 0..5000，越界 400 指名道姓），
  三处写侧全部改走它；`FIXED_CENTS_MAX` 刻意**不做成参数配置旋钮**（理由写进类注释：
  分成系数长期生效，宁可改代码留评审，不给线上手滑的口子）。
  防复发门禁 `check:commission-param-bounds`（棘轮双向 + 锚点「写侧文件 <5 即红」，
  SiteRentSplit/SiteRentBill 两处自带闸的先例进白名单并写原因）。
- **MA-100 还含零留痕**：增/改/删成员三条动作全部落 `LINE_ROUTE_MEMBER_*` 审计，改前/改后同串对比。
- **MA-068 盘点凭证跨租户写**：`uploadStocktakeEvidence` 补 `merchantScopeService.requireDeviceAccess`
  （与同文件回看面 `streamStocktakeEvidence` 同规）。钉测用「归属闸抛 403」证明它在写文件**之前**跑。
- **MA-101 同渠道同账期重复 commit**：commit 前置查同 (channelCode, periodLabel) 的 COMMITTED 兄弟批次，
  命中即 409。**刻意未加 DB 唯一索引**：现网可能已有重复 COMMITTED 行，
  带数据依赖的迁移必须先看 count 再决定（判据待跑，见「待办」）。
- 附带收口：**MA-075**（换绑 userId/改名静默落库 → 审计条件从「三字段」收成整张快照 + userId 存在性 400）、
  **MA-077**（未知 ownerType 兜到只读码 `ops:finance:view` 即可写收款账户 → 白名单，
  写侧只认 LINE_MANAGER/MERCHANT，PLATFORM 保留只读；`ops:finance:edit` 这个码在本仓不存在，勿臆造）、
  **MA-108**（幽灵 managerId 400 + routeName/remark 按列宽 400）。

### 批次B｜零归因两条（P2）

- **MA-115 OTA 发布/下架**：`operatorId` 原来收下即弃（服务内 3 处命中全是签名），现落
  `OTA_PUBLISH`/`OTA_UNPUBLISH` 审计，detail 带 version/channel/grayPercent/checksum 前 12 位。
  **无需迁移**——走现成 `admin_audit_log`；台账原文「表无列」半句不准：`ota_release` 有
  `created_at`/`published_at`，缺的只是 operator 列。
- **MA-116 一致性修复**：Controller 原来连 operator 都不取。现 `fixInconsistencyDetailed(operatorId, recordId)`
  + `CONSISTENCY_FIX` 审计；`error_message` 从**整值覆盖**改为**追加**（列是 TEXT，
  巡检当时的成因得以保留）。**MA-124** 同批：FIXED 记录不再重放；两处 `refundOrder` 的返回值开始被检查
  （false = 幂等跳过/渠道未确认，原来照样回报「已退多收 X 分」= 假成功）。

### 复核三态与台账订正（本批涉及）

| 状态 | 条目 | 订正内容 |
|---|---|---|
| 复核**不成立** | MA-081 | 撞 `uk_user_wx_open_id` 抛的是 DuplicateKeyException（DataIntegrityViolation 子类），已被全局映射成 409，不是 500 → 撤账 |
| 口径过期 | MA-084 / 087 / 096 / 104 / 107 / 108 / 113 / 122 / 125 | 「→500」在 2026-11-02 `GlobalExceptionHandler` 补 `DataIntegrityViolation→409`、`IllegalArgumentException→400` 之后不再成立，实际是 409/400（话术泛化是真问题，500 不是） |
| 部分成立 | MA-071 | 重复 ID 撞复合主键的出口已被 409 通道还原；残留的是「整请求失败」而非「500」 |
| 部分成立 | MA-133 | `STAFF_COMMISSION_DAILY`/`STAFF_WITHDRAW` 是 **refType 不是 entryType**；真缺项是 `WITHDRAW_PAYOUT` + 整个 `wallet_ref_type` 类型从未入库 |
| 部分成立 | MA-134 | 「ensureMissingDictItems 永不自愈」措辞不准，实为「自愈目录本身缺这 2 值」；admin 靠 baseline 兜底不显裸码 |
| 部分成立 | MA-143 | 基线↔bootstrap 实差 **14** 值（非 15） |
| 措辞订正 | MA-115 | 「表无列」→ 缺的是 operator 列，时间列有 |
| 新增领号 | **MA-149** | 复核时挖出的台账外同族：`AdminDashboardController.java:517/:527` 注解 `OR{ops:sku:edit, ops:sku:import}`，但 `OpsCatalogAdminService.java:115/:140` 只认 `ops:sku:edit` —— `ops:sku:import` 是 V106 已授角色的真码，死分支可达（比 MA-105 更可能被踩到：只授「导入商品」的运营建/改商品恒 403） |

### R17-FINAL 终局报告数字订正（台账现算）

- 台账 **148** 行（MA-001~148 无断号），不是 146：漏算 MA-148。
- 第一波 MA-001~067 = **67** 条（不是 66）；其中 fixed 65 条，另 2 条非 fixed：
  MA-016 `wontfix（用户拍板）`、MA-023 展示侧已修/记账侧随 MA-029 闭合 ⇒「已全部修复」应作「已无 open」。
- open 80 条级别是 **P2×19 / P3×61**（不是 18/62）。差的正是 **MA-135**
  （notification_biz_type 缺 DAILY_INCOME/FEE_REMINDER，P2）——它被「家族6 字典回填 MA-130~134」漏在族外，
  **按族批量修就会真漏一条 P2**。

### 验证（本批）

- 钉测：`CommissionParamBoundsTest` 4 例、`MachinePayeeBillConfigGuardTest` 7 例、
  `LineRouteMemberCommissionGuardTest` 8 例、`OtaReleaseAuditTest` 2 例、
  `MerchantPortalConcurrencyTest` +1（MA-068）、`DataConsistencyServiceTest` 46→47（含不重放）全绿。
- 门禁：`check:commission-param-bounds` 绿（5 个写侧文件：3 走单源、2 白名单），
  并接入 `check:audit-gates` 聚合链（`check-audit-gates-wiring` 自报 87 引用全有定义、101 脚本全接线）。


### 批次C｜全额退返还券（MA-147，P2）

- **修法**：`SettlementWaiveRefundService.finalizeWaiveRefund` 在 `setStatus(REFUNDED)` 之后、
  `save` 之前调 `CouponService.settleAfterFullRefund(order)`。**单源不变**——新方法只是
  `settleAfterPartialRefund(order, 0, 0)` 的具名包装（全额退本就是它的 `newSubtotal <= 0` 分支），
  不在 waive 侧抄第二遍返还逻辑；预算回补仍**以 activity_id 为条件**（无活动的券不回补）。
- **顺序是硬约束**：结清会清 `coupon_id` 并把抵扣归零，若放在 save 之后就只改内存不落库
  （MyBatis-Plus 只在 save 时取值，lessons #384 同源）。钉测用 `InOrder` 把这个顺序钉死。
- **对端会话预留的回归位已翻转**：`SettlementComboMatrixE2ETest` 原来把 `USED` 钉成
  「已知行为」并注明「修复后翻转此断言」；本轮翻成 `UNUSED` + 新增「订单头 coupon_id 必须为 NULL」，
  同时保留它对分账冲正/钱包净额归零的断言。该矩阵是 Docker 守卫件（`check:docker-tests-guarded` → build job），
  由 CI 跑，本地不重跑。
- 顺带证实：`releaseStaleUsedCouponsForOrder`（一致性修复在用）只治「错绑/未生效」的脏券，
  不覆盖正常全额退 ⇒ 不能拿它当 MA-147 的修法。

#### MA-147 二版：一版修复被自己加的真库断言抓住（同批撤销门禁豁免）

- 一版只做了内存清列（`setCouponId(null)` + `save`）——**Docker 组合矩阵跑到这条新断言就红了**：
  `MA-147：券返还后订单头不得再挂 coupon_id，实际=6`。根因还是 MyBatis-Plus `updateById` 跳 null
  （lessons #384），`user_coupon` 已回 UNUSED 而 `cabinet_order.coupon_id` 原封不动。
- 二版把显式 SQL 放进**清列动作的归属点本身**：`CabinetOrderMapper.clearOrderCouponColumns`
  （只清券列、**不动应付**），由 `CouponService.settleAfterPartialRefund` 的 `newSubtotal<=0` 分支调用 ⇒
  waive 与「退到零行」两条路径都覆盖，不在调用方各抄一遍。矩阵件复跑：1 testcase / 0 failure，
  打印实证 `couponBack=UNUSED` 且断言 `coupon_id IS NULL`。
- 🔴 **这条缺陷曾被门禁的豁免条目掩护**：`check-mybatis-null-clear` 的 CouponService 条目写着
  「DB 清列由 `SettlementOrderFinalizeService.clearCouponSelection` 调用链负责」——那句只对
  扣款失败转 PENDING 那条链成立。已把该文件的**整条豁免撤销**（改完靠 `clear[A-Z]` 缓解判定自然通过），
  并在门禁头部写进豁免纪律：理由里声称「别处会清」必须覆盖**全部**调用链，否则就是给缺陷发通行证。


### 批次C′｜复核判为「P3 名义但实碰钱/鉴权」的那批（先于展示类收口）

- **MA-119 设备凭据签发**（鉴权面）：`requireDeviceId` 只验「12 位数字」，不验设备存在 ⇒
  手滑一位就签出一条 ACTIVE 孤儿凭据并把明文 secret 回显。HTTP 与 MQTT 两个签发服务同修
  （`requireDeviceExists` → 404），钉测 1 例（`issue_unknownDevice_rejectedWithNotFound`）。
- **MA-085 活动预算/类型**：`budgetCents < 0` → 400（负数会让 reserveBudget 的 `budget > 0` 整体跳过，
  used_cents 无上限推进 = 资金闸失效）；`activityType` 必填 + 入库前归一大写（判定侧
  `equalsIgnoreCase` 看着没事，`PromotionActivityMapper:30` 的 `IN ('SKU_REDUCE',...)` 是精确匹配，
  存小写该活动静默退出定价面）。钉测 4 例（含「0 = 不限额」仍放行）。
  **残留**：完整 activityType 白名单没做——词表实测至少含非 SKU 的 `FULL_REDUCE`（既有测试在用），
  盲列白名单会打断真实类型，需先拍板词表。
- **MA-093 平台资金池**：`recharge` 补「必须 > 0」，负数不再以充值语义扣余额、也不再绕过
  `adjust` 的「必须填原因」闸。**残留**：`OpsPlatformFundController` 的 `(Number) body.get("amountCents")`
  仍是裸取参（传字符串 → ClassCastException），DTO 化留待与 MA-104/108 同族一起收。
- **MA-088 微信退款同步幂等键**：回调缺 `refund_id` 时原来照入账，键退化成常量
  `"channel-refund-sync:null"` ⇒ 不同订单的第二笔命中同键时余额不扣、单据照涨（幽灵余额）。
  现在**拒绝入账 + 发 `WECHAT_REFUND_SYNC_MISSING_REFUND_ID` 告警**转人工。
  🔴 **键格式刻意不动**：微信会重投通知，老库已存在旧键流水，改字面量会让部署后的重投被当新键重复扣一次
  （与「锁 key 字面量不可改」同源）。
- **MA-118 押金登记裸 Map**：`longOrNull/intOrNull` 原来吞掉 `NumberFormatException` 返回 null，
  「填了但填错」与「没填」不可区分 ⇒ 月退额静默丢失、`contractYears` 静默落默认 5 年，
  而商户余额已经真扣。现按字段名 400（`monthlyRefundCents/refundStartMonth/expireRewardCents/`
  `installFeeDeductCents/contractYears` 五个入参全覆盖）。**残留**：Controller 未 DTO 化（同 MA-093 残留并入同族批）。
- **MA-089 退款上限双口径**：`ApplyBalanceRefundRequest` 去掉了 `@Max(500_000)`——Bean 校验先拦会让
  DC-6 的 `BALANCE_REFUND_MAX_CENTS` 只能收紧不能放宽，与充值侧（纯配置）矛盾。
  已核实 `@Max` 不进 OpenAPI 生成物（springdoc 不导出 bean-validation 的 maximum），**无需 regen shared-types**。

### 批次F｜四项拍板落地（用户 2026-11-03 授权「按你的推荐做」；其中一项我推翻了自己的推荐）

- **MA-070 组织/点位权限双轨（档 A：服务层按注解放宽）**：`OrgService` 树读(:80) 与三处写(:115/:154/:191)
  从单码 `ops:device:*` 改为 `requireAnyPermission(org:*, device:*)`，与本类 `deleteNode` 既有口径对齐；
  `SiteContractService.doUpsert` 同修（原「删比改松」的方向反常由此抹平）。
  效果：只持 `ops:org:edit`/`ops:org:list` 的角色从此**能**管组织节点——这是**扩权**，故须（且已）明示拍板。
- **MA-105 采购审核（收窄注解）**：`OR{ops:procurement:edit, ops:finance:view}` 收成单码
  `ops:procurement:edit`。行为等价（服务端本来就只认这个码，持 finance:view 者原先也是 403），
  只是不再向权限配置者 advertise 一个永远做不到的动作。**不给只读码挂写动作**是这里的取舍。
- 🔴 **MA-149 我推翻了自己的推荐**。推荐时的前提是「导入岗被死分支挡在自己的活外」；
  取证后该前提**不成立**：`ops:sku:import` 在**全仓没有任何导入端点用它把关**（V106 只建了按钮码），
  三处 OR 里唯一下方判据真生效的是 `GET /skus/barcode-lookup`（纯读代理、服务层不再二次判码）。
  所以：建/改商品两处**收窄注解**（行为等价），**条码查询保留 OR**（收窄它会真打断该岗）。
  没有按原推荐去放宽服务层——那等于凭错的证据给一个按钮码扩写权限。
- **MA-085 类型闭集补齐**：权威源不是猜的，是字典 `promotion_type` 九值（V180 种子 + OBS-006 补
  NEW_USER/POINTS；dev 库实测在用 DISCOUNT/NEW_USER/POINTS/SKU_REDUCE，既有用例另用 FULL_REDUCE）。
  `PromotionActivityValidator.SUPPORTED_TYPES` 落该闭集并在 `validateForSave` 首判；
  钉测 3 例（四个真实字典值不被自己拦死、`AMOUNT_OFF`（券类型）被拒、空白类型被拒）。
- **MA-101 补最后一道防线 V435**：部分唯一索引 `(channel_code, period_label) WHERE status='COMMITTED'`。
  照 V431 先例**先 count 重复组 → >0 就 RAISE EXCEPTION**（fail-loud），而不是让 PG 抛无上下文违例；
  索引兜的是**并发双提交**（服务层 409 兜顺序重放）。
  前置取证：本机 dev 库 `external_import_batch` **0 行**；其它库上线判据写进
  `docs/DEPLOYMENT_CHECKLIST.md §3.1.5`（重复组非 0 要先人工并账，**勿注释守卫、勿删历史批次**）。
- **MA-129 拍板选 ②**（当时写成「读模型新增『剩余毛额』列 + consumer 拆解块加一行『剩余合计』，
  **不动资金算式**」——选 ① 要回退两处等比缩并重新回归「预估==写侧」，风险直接把展示问题变成资损面）。
  🔴 本块已被 `d8ff932c`（同日 14:56）的落地**订正**：最终**没有新增后端字段**，剩余毛额由前端四列恒等式
  （实付+券+会员+活动）在 `clients/consumer-mp/src/pages/order-detail/order-detail.vue` 的
  `remainingGrossCents` 里算出 ⇒ openapi 形状不变，原记「状态仍是 open、须与 MA-127/128 同批做
  **shared-types regen**」的前置**不成立**（索引行 MA-129 = fixed 才是终态）。


### 批次G｜「级别标低了的」资金/权限面（3 条）

- **MA-083 批量发券（代码已修，**regen 欠账未清 → 任务 #25**）**：`BatchIssueCouponRequest` 补 `@NotNull/@NotEmpty/@Size(max=1000)`。
  原来裸 List 直接进 `userIds.stream()` ⇒ null 即 NPE→500；万级 id 单事务逐张持
  `coupon_def` 行锁且**逐张占用活动预算**（发券即 reserve）。
  🔴 关键取证：`packages/shared-types/src/generated/openapi.ts` 里
  `maximum/minimum/maxItems/maxLength` **0 命中** ⇒ springdoc 不导出 bean-validation ⇒
  ~~加 DTO 校验注解不需要 regen shared-types~~ 🔴 **此句已被 CI 证伪（教训 #394）**：`@NotNull/@NotEmpty` 会改 TS 类型（required=去掉 `?`）⇒ **必须 regen**；只有 `@Min/@Max/@Size` 这类数值/长度界不进类型 ⇒ 无形状差。
- **MA-096 积分兑换**：`adminUpsert` 原样落库 ⇒ `pointsCost` 为负时 `available < cost` 恒假，
  随后 `available - cost` 给会员**倒增积分**（反复刷 = 持续铸分）。写侧闸：pointsCost/stockTotal/
  sortOrder 非负、couponDefId 必填且券模板必须存在**且 ACTIVE**（挂停用券 = 用户一点就失败）、
  status 收成 ACTIVE/INACTIVE 并归一。
- **MA-111 反馈硬删**：原来「持查看码 `ops:feedback` 就能物理删用户原文」且全类零留痕。
  现删侧与 `reply()` 同闸只认 `ops:feedback:reply`，删前落 `USER_FEEDBACK_DELETE`，
  detail 只记 type/status/是否已回复/正文长度（不落 content 与 contactInfo——审计表面向全员可读）。
  ⚠️ 落码时我一次批量替换把 `GET /feedbacks` 也收窄成了回复码（只持查看码者连列表打不开），
  已当场回退那一处。教训：按字符串批量改注解必须逐处核对 HTTP 方法。

- **钉测已补齐（批次 N）**：MA-083/096/111 + 前批 088/093/118 六条由「只有代码级证据」升级为断言型用例，
  明细见文末「批次 N」。过程中两处自纠值得下轮沿用：① 别把「想要的行为」当「现状」写断言
  （我先写了「0 分兑换被拒」，实读实现只拦负数 ⇒ 删例）；② 字符串长度别心算
  （`contentLength` 我写 15、实际 17，靠断言失败才发现）。
- **MA-091 / MA-103 本轮未动**：091 需 Controller→Service 的 operator 透传与 V355 `settled_by` 取舍；
  103 是双挂索赔台账/双建任务的锁改造（照 `WarehouseTransferService:256-259` 范式）。排下批首站。

### 批次I｜审计族前两条 + 一条门禁设计缺陷的受控收口（MA-097 / MA-074 / 豁免通道）

- **MA-097 黑名单解除归因**：`addBlacklist` 记 `BLACKLIST_ADD`（带 by），`removeBlacklist` 连
  operatorId 都不接、零事件 ⇒ 被收买的账号可以**无声放行作弊用户**，事后查不到谁解的。
  现在行锁内删成功后记 `BLACKLIST_REMOVE`（detail 带 by）；两个调用方本就持有 operatorId，
  直接透传，**不留兼容重载**。
- **MA-074 补货员钱包出账归因**：`payout` 是真金销账，此前全类零留痕。审计放在 controller
  （与 `OpsPayoutAccountController` 同范式，不动 `payoutIfAbsent` 签名——那条线还有调度器调用方）。
  🔴 **幂等命中（applied=false）刻意不留痕**：那笔出账首次标记时已记过，重放再记等于审计里
  凭空多出一次出款。钉测 2 例分别钉「标记成功必留痕（含 requestNo）」与「幂等命中不留痕」。
- 🔴 **撞出一条门禁设计缺陷并受控修掉**：`check-openapi-regen-required` 把「变更行里含
  `@PathVariable/@RequestBody`」当成形状改动，而**往参数表里插一个服务端形参必然替换掉那一行**
  （git 连被替换的旧行也算变更行）；spec 里根本没有 `HttpServletRequest` 这类参数 ⇒ 净差恒为空，
  而代码 `s.covered = delta.length > 0 && ...` 把空净差判为「未覆盖」⇒ **这类改动结构性不可通过**。
  审计族有半数都要加 operator 参数，等于整族被堵死。
  此前 `OpsLineRouteController` 也加过 request 参数，只是恰好和 MA-083 的 DTO regen 挤在同一次
  推送里才过 CI —— 不是它被正确放行了，这点必须说清。
- **修法=受控豁免，不是放宽判据**：新增 `scripts/openapi-regen-exemptions.json`，三条硬约束
  ①**只对 controller 生效，DTO 永不豁免**（`@NotNull` 改 required 不产生新 property key，
  正是 MA-083 那次真该 regen 的情形）；②登记的 endpoint 字面量必须仍在该文件源码里（条目漂移即失效）；
  ③基线与当前的**映射签名**（HTTP 方法注解+路径+返回类型）逐条相等 ⇒ 挪路由/改响应类型一律拒绝豁免。
  7 条用例钉住（`check-openapi-regen-required.test.mjs`）：未登记仍红、登记后绿、改路径红、
  改返回类型红、条目漂移红、DTO 试图豁免红，且原有红/绿/回归/fail-closed 用例零退化。

### 批次J｜P2 清零：字典回填 7 条 + 跨端金额展示 3 条

**字典族（MA-130~136，7 条）**
- **V436 迁移**补齐「后端在写、字典没有」的 7 个值（`COMMISSION_CLAWBACK`/`WITHDRAW_FEE`/
  `WITHDRAW_PAYOUT`/`DAILY_INCOME`/`FEE_REMINDER`/`PENDING_APPROVAL`/`REJECTED`），
  并新建 `device_rental_bill_status`；`SysDictBootstrap` 与 `packages/shared-dict` 同批同步
  （新库/存量库/运行期自愈三条路径同一套词），dist 已重建。
- 🔴 **V436 我自己写错两处，被 fresh-DB Flyway 链抓出**（静态门禁看不见 SQL 语义）：
  ① `VALUES` 只有 4 列却 `SELECT v.remark`（语法错）；② 更关键——全新库里
  `purchase_order_status` 等 **dict_type 行根本不存在**（只由运行期 `SysDictBootstrap` 自愈补出），
  而 `sys_dict_data` 有 FK 指向 `dict_type` ⇒「迁移先跑、类型还没建」必然 FK 违约。
  现在四个类型一律 insert-if-absent。`DictLabelUniquenessIT`（V1→V436）1/1 绿。
- **MA-131 是功能性缺陷**：admin 把作废写作 `VOIDED`，后端常量是 `VOID` ⇒ 状态裸码 +
  「已作废」筛选值传到 mapper 的 `eq(status)` 后**恒查空**。改 `displayLabel` + 新字典类型。
- **前端三处删自造 map 改单源**：商户理货记录（`PUT_ON` 是页面发明的键，后端写 `RESTOCK`）/
  商户盘点状态（待录入/录入中/已过账 vs admin 草稿/盘点中/已调整）/ 运营线路页 `entryType` 裸码。
  盘点状态未知值原样露裸码，现兜底「未知」。

**跨端金额展示（MA-127/128/129，3 条）**
- **MA-128**：商户明细「已扣」原来优先取 `originalAmountCents`，而部分退后该列被后端重写成
  「剩余折后行合计」（`SettlementOrderSupport.applyProportionalRefundPayable`），前端再减一次已退
  ⇒ 半退单显示**实收 ¥0.00**（真实净收 ¥1.21）。改为恒等式：已扣 = 当前应付 + 已退；实收 = 当前应付。
- **MA-127**：`activityDiscountCents` 后端一直下发，但手写 `OrderReadModel` 缺这一列 ⇒
  merchant-mp 全端 0 引用、admin 明细只列券/会员，而列表优惠列含活动 ⇒ 同一单列表与明细对不平。
  补类型 + 三处展示（admin 明细、mp 明细、差额说明）。
- **MA-129**（拍板②）：部分退后 member/activity 等比缩而 gross 不缩 ⇒「合计 − 各档」比实付多一截。
  有退款时显示「剩余商品合计 = 实付 + 券 + 会员 + 活动」——**不新增后端字段**，
  因此不需要 openapi regen；改后端加「剩余毛额」列会为一行展示付出一次 regen 成本。

### 批次K｜审计族 2 条（MA-072 / MA-073）

- **MA-072 运营账号动作**：他人重置有 `OPS_OPERATOR_RESET_PASSWORD`，而**自助改密零留痕**、
  2FA 开/关也不记 ⇒ 账号接管链（改密 → 开关 2FA → 操作）在审计页上断了一截。
  三处各补一条事件，detail 不落任何密码/secret/恢复码材料。
- **MA-073 提现申请**：这是提现全族唯一无留痕的动作（审核 REVIEW / 打款 PAYOUT / 取消 CANCEL 都有）。
  商户链与线长链的**两个分支都补** APPLY 事件：待审核分支（进审批流前）与「低于阈值自动通过」分支
  （该分支不落 review 记录，只有这条审计能回答「谁提的」）。
  线长侧 `apply()` 原把 `submitterUserId` 传 null ⇒ 现从 controller 的 `operator(request)` 透传；
  **controller 签名未变**（已有 request 形参），因此无需 openapi regen。
- **残留（显式）**：`executePayout(requestId)` 仍不带操作者——自动打款分支的归因靠
  「申请事件 + 审批事件」两步串起来；调度/重试路径本就无人工操作者（应记 system）。
  若要逐笔打到「谁触发了这次打款」，需要给 executePayout 加 operator 形参并贯穿 3 个调用点，
  属独立小批（不是本条的阻塞项）。

### 批次L｜财务留痕两条（MA-092 部分 / MA-076 部分），各自把残留写清

- **MA-092 开票链**：`InvoiceService` 补 `AdminAuditService`——`issue`/`reject` 是财务凭证动作，
  此前**全类零留痕**。detail 记 `orderId`（`InvoiceRequest` 没有 merchantId 字段，按实体实际字段来）。
  **残留**：同一族的「对账重跑（`ReconciliationService.runDaily` 先删旧 recon+行再重建）」仍未留痕，
  且 `OpsFinanceAdminService`（机器账单出账/结清）也未动。
- **MA-076 税务资料**：保存含**银行账号**（PII），现只记「谁改的 / 新建还是修改 / 账号后四位 / 公司名」，
  不落完整账号与税号（审计表面向全员可读）。
  **残留**：同条的另一半 `CompetitiveGapService`（商户补货配置）本轮**刻意未动**——构造器 20 个参数、
  4 处调用点（其中一处是 20 个 null 字面量），为一条留痕把改动面扩到 5 处，收益与风险不成比例。
  下批单做该条，届时把 `CompetitiveGapConcurrencyTest` / `CompetitiveGapSalesReportTest` /
  `CompetitiveGapServiceDeviceScopeValidationTest` / `MerchantPortalConcurrencyTest` 四处一并补 mock。

> 🔴 **本轮操作失误（不是代码问题）**：我在后台全量套件仍在跑时又启动了定向 `mvn`，两个进程同时写
> `target/classes`，导致那次全量出现 30 failures + 323 errors，错误全是
> `class redefinition failed` / `NoClassDefFoundError` / `Could not modify all classes`（Mockito/ByteBuddy 类加载态被并发改写）。
> 已按纪律重跑 `mvn -pl services/trade-service -am clean test` 单独验证，结论以那次为准。
> 教训与 #390 同源：**同一模块内任何时刻只能有一个 mvn**。

### 批次 M｜MA-103 真并发 IT（含反向对照）

- 新增 `CloseClaimAndExpiryTaskRowLockSqlIT`：Testcontainers PG + Redis，两条腿各起 2 线程、同一闸门外放行打**同一行**。
- 判据（有锁态）：`inventory_write_off`（按本次 claimNo）恰 1 行、`replenishment_task` 中 `notes LIKE 'from-expiry:<taskId>%'` 恰 1 条；两个结果**必为 1 成功 + 1 个 409**（409 由服务层「已处置/已处理」判据给出，不是时间错开的运气）；行状态 CLAIM + `claim_write_off_id` 回写、告警 RESOLVED + `resolved_at`。实测 2/2 绿。
- **反向对照（证明这条 IT 能抓缺陷，不是同义反复）**：临时把两处读改回无锁 `selectById`（其余逻辑不动）重跑 ⇒ **两条腿同时红**，原文 `同一盘亏行挂出多笔索赔台账 … expected: <1> but was: <2>`、`同一条临期告警双建补货任务 … expected: <1> but was: <2>`——正是台账写的双挂本体。改完立即 `git checkout --` 复原（已核：`NEGATIVE-CONTROL` 计数 0，工作树该两文件无残留）。
- 播种教训：`pull_off_task.device_id` 有 FK，而 V2 的演示设备在后续清库迁移里已被删 ⇒ 首跑 `DataIntegrityViolation`。IT 自播 device/sku/warehouse（`ON CONFLICT DO NOTHING`），不依赖演示数据。

### 批次 N｜六条「代码级修复」补断言型钉测（2026-11-03 晚）

| 缺陷 | 新用例 | 钉住什么 |
|---|---|---|
| 083 | `CouponRequestDtoValidationTest`（5 例，Validator 直校 DTO） | `couponDefId` 缺失、`userIds` null **与空数组分别**被拒、1001 拒（文案含 1000）而 **1000 必须放行**（否则上限=永远发不出去） |
| 096 | `PointsRedeemServiceTest` 新增 4 例 | title/pointsCost/stockTotal/sortOrder/couponDefId 五条 400 分支各带**指名声**、`券模板不存在：2`、非 ACTIVE 被拒；status ` inactive `→INACTIVE、null→ACTIVE；每条都验 `save` 从未被调用（**校验必须先于落库**） |
| 088 | `PaymentServiceTest` 新增 2 例 | 缺 `refund_id` 与**空白串**都走同一道闸：不抛（仍 204 终止重投）+ 告警 `WECHAT_REFUND_SYNC_MISSING_REFUND_ID` + `balanceLedgerService.change` 与 `findById` **一次都不调** |
| 093 | `PlatformFundServiceTest` 新增 1 例 | 负数/零「充值」400 且**码不许漂**、`adjustBalance`/`insert` 零调用（含控制器缺字段→0L 这条链路） |
| 111 | `UserFeedbackConcurrencyTest` 新增 2 例 | 服务层只认 `ops:feedback:reply`；审计 detail **逐字等值**且不含手机号；再用反射钉控制器注解字面量（403 由切面抛，注解漂回查看码即红） |
| 118 | `OpsDeviceRentalControllerDepositBodyTest`（4 例，新建） | 非整数 `monthlyRefundCents`/`contractYears` 400 且服务不被调；**反向锁**：空白串与键缺席仍按 null 放行（防「顺手收紧」打断老前端）；字符串数字照收且**按声明顺序**到达服务层 |

- 顺带修掉一处「测试写坏源码」的操作失误：批量 `Edit` 替换时把 `PointsRedeemServiceTest` 审计用例的收尾断言吃掉了，补回后原断言仍在。
- 另记一次工具层自伤：用 `node -e` 对整个 ISSUES.md 做正则时 `[^】]*` 字符类**跨行**吞掉 1127 行；`git checkout --` 复原后改为逐行精确替换（改前后都跑了一次行数/行数账/open 计数核对）。批量文档改写禁用可跨行的字符类。

### 批次 O｜MA-146 三条残项收口，其中一条**原判定被源码推翻**（2026-11-03 深夜）

| 残项 | 处置 | 判据（全部现取，非引用台账措辞） |
|---|---|---|
| repair_ticket_status.CLOSED | 种子去值 + `V438` 删库行 | `RepairTicketService.STATUSES={OPEN,IN_PROGRESS,DONE,CANCELLED}`、转接表无边、`git log -S "CLOSED" -- RepairTicketService.java` 零命中 ⇒ 任何版本都写不出该值；dev 库 `repair_ticket` **0 行** ⇒ 删字典行不会造出「值在库里、标签没了」的历史行 |
| enable_status 基线 DRAFT/STOPPED | **不删**（订正为刻意分叉）+ 钉测 1 例 | 我按台账「冗余」动手删过一次，随即被消费方推翻：`PromotionsView.statusLabel` 用 `displayLabel('enable_status', …)` 渲染活动状态，而 `PromotionService:61` 建单即写 DRAFT、`:158` 写 STOPPED，库/种子只有 3 值 ⇒ 这两键是唯一译名来源。删掉的后果是活动列表「草稿/已停止」洗成「未知」 |
| consumer feedback 未知态兜底「处理中」 | 改显「—」，且未知态不再套 pending 黄条 | `feedback.vue:153` 原写法会把空状态/未知码洗成看似正常的进度；`displayLabel` 的兜底串就是给调用方决定「未知显示什么」的地方，传进度词等于自我掩盖。人工判据 = 「我的反馈」列表行右侧状态文字 |

- 新钉测两条都是**先跑红再改绿**的：
  - `DictLabelUniquenessIT#repairTicketDictValues_mustMatchStateMachineWritableStatuses`（真库全链 V1→V438）：注回种子的 `STATUS_CLOSED` ⇒ `Tests run: 2, Failures: 1` 且红的正是这条；撤注后 `tests=2 failures=0`。多值=幽灵选项、少值=新状态没标签，两个方向一起拦。
  - `dict-options-aliases.test.ts` 新例：从基线删掉 DRAFT/STOPPED ⇒ `1 failed | 6 passed`；补回 ⇒ 7 例全绿。
- 活的共享 dev 库里 **CLOSED 那一行仍在**（本轮按铁律未对 18080 栈跑迁移）⇒ 后台筛选此刻仍看得见「已关闭」，新库/CI 由 V438 收敛；重部署后自动消失，判「还是灰的/还有」先比容器镜像与 V438 提交先后。
- 台账核对：149 行、open=0 未变；MA-146 由 `fixed（部分…）` 转全量 `fixed`，并把「活库未迁移」写进残留列。

### 批次 P｜权限矩阵专项落地成门禁（2026-11-03 深夜，补 regression-index 里那条「待补」）

**判据方向先说清，只拦一侧**：切面是 OR（注解任一码即放行），服务层是 AND（路径上每道 `require*` 都得满足）。
⇒ 真缺陷形态 = **注解里存在某个码 X，而路径上某道校验不接受 X**：只持 X 的人「前端看得到按钮、点下去必 403」，正是 MA-070/105/149/111 那一族。
反方向（注解比服务窄）是刻意双门的常见形态，本门禁只记 info 不判红——把两侧都判红会逼出一堆无意义的对齐改动。

`scripts/check-permission-matrix.mjs` 实树数字：629 处注解 / 598 绑到方法 / **196 个端点两侧都有校验**，其中 **12 条**命中幽灵码形态，全部登记进 `scripts/permission-matrix-exemptions.json`。
豁免通道带三条硬约束（不是「登记了事」）：上限 12 **只许减不许加**；条目缺 `family/reason/ask` 即红；登记的端点一旦不再冲突就报「清单腐烂」并拒绝。
负向用例 13 条：`node scripts/check-permission-matrix.test.mjs`。其中两条专门钉本轮自己踩的坑：

1. **CRLF 偏移漂移**：按 `lines[k].length + 1` 累加行首偏移，在 CRLF 文件里每行少算 1 字符 ⇒ 方法体切片整体错位 ⇒ 服务层校验被读丢，「两侧都有校验」从 196 掉到 119。**这类退化不会报错，只会安静地少查 77 个端点**，所以必须留 CRLF 与 LF 同判的用例。
2. **跨行签名绑定**：`public X create(\n @RequestBody ..\n) {` 必须仍能绑对，否则同一个坑换个形式再来一遍。

#### 12 条按族归类，每族只问一个问题

| 族 | 端点 | 现状（可证部分） | 两档与爆炸半径 | 我的推荐 |
|---|---|---|---|---|
| A RBAC 柜机范围子码「能改看不到」 | `OpsGapFeaturesController#userDevices`、`OpsRbacController#userRoles`、`OpsRbacController#userMerchants` | 写侧 `CompetitiveGapService:129` 是 `requireAnyPermission(operatorId, "ops:rbac:assign:device", PERM_OPS_RBAC_ASSIGN)` 两码都收；三个**读**端点服务层只收 `ops:rbac:assign` ⇒ 子码角色一进用户详情就 403 | ① 读端点并受子码（与写侧同形）：只放开「看范围/角色/商户」，不碰任何写面；② 注解删子码：承认子码角色不该看这三面，代价是它连自己刚改的范围都查不到 | **① 读侧并受**——与写侧同形、零写面变化 |
| B 字典导入角色单条补录被拒 | `SysDictController#upsertType`、`SysDictController#createItem` | 注解 `{ops:dict:edit, ops:dict:import}`，服务层只认 `ops:dict:edit` ⇒ import-only 用户单条补录 403 | ① 服务层并受 import：等于给 import 角色两个单条写面（真扩权）；② 注解删 import：批量导入角色永远只能批量 | **②**，但前提是「import 角色只有批量入口」确实是有意的——我手里没有第二处证据，所以这条要你确认而不是我替你定 |
| C 写/导出路径按**读码**上闸 | `OpsGapFeaturesController#createPhoneVerify`、`#updatePhoneVerify`、`#deletePhoneVerify`、`#exportStockHealth` | 注解是写码（edit/delete/export），服务层只认 `ops:phone-verify:list` / `ops:user:list` / 三个 list ⇒ 服务层对写侧形同虚设，真正的门只剩注解 | ① 服务层换成对应写码：写侧闸门在服务层真正成立；今天「持写码但不持那把读码」的人会被放行，集合确实变大（属修方向性缺陷，但仍需知情）；② 保持现状：双门继续，写侧继续只靠切面 | **①**——这是「写侧缺闸」，不是权宜对齐；因涉及放宽一批人，必须你点头 |
| D 单码双门三例（本意各差一句话） | `MerchantPortalController#saveMyOpsConfig`（注解 view，服务要 request）、`AdminJiangyiController#shelveLearnedProduct`（注解 device:edit + 服务连道要 pricing:edit 与 replenishment:edit ⇒ 三码齐才能上架）、`OpsOrgController#siteContracts`（注解 org:list，服务要 device:list） | 三例的共同点：注解只有那一个码，**删掉就空**（=把双门拆成单门=扩权），所以只能改服务层或确认本意 | 每例一句话即可定：配置保存该认 view 还是 request？上架到底要几码？组织页的站点合同按 org 域还是 device 域？ | 待你给口径；A、C 两族我可以先落地 |

- 本批**未改任何 Java/前端行为**：只加门禁脚本 + 负向用例 + 豁免清单 + package.json 接线（聚合链 90 ⇒ 92，全绿）。
- 台账 149 行、open=0 不变；regression-index 里 MA-070/105/149 那行的「待补」已换成可执行命令。
- 遗留（下一轮候选，本批不动）：402 个端点是 `no-service-check`（有注解、服务层无校验）。多数是「只靠切面保护」的正常读接口，但**其中 POST/PUT/DELETE 那一档**如果也零服务层校验，就是另一类缺陷（切面被绕过=直接调用即长驱直入）。收窄到写方法再测一次才有意义。

### 批次 Q｜权限矩阵 12 条按拍板落地（2026-11-04，用户：「四族待拍板 按照你的推荐做」）

先把**测量误差**认掉：批次 P 的 12 条里，`AdminJiangyiController#shelveLearnedProduct` 是我自己的假阳性。
`JiangyiShelfService.shelve` 的两道 `requirePermission` 分别包在 `if (priceCents != null)` / `if (quantity != null)` 里，
属合法的「按字段分权」，而首版把方法体内所有校验当成恒需 AND。⇒ 门禁加了分支判定（`condRanges`：按 `{` 前配对括号的关键词判 if/for/while/switch/else；
`->` 与 try/finally 视为恒执行），并补两条**能失败**的负向用例（同一道校验放进 `if` ⇒ 绿；拿出来 ⇒ 红）。负向用例 13 ⇒ 15。

| 族 | 落点（按符号，行号会漂） | 改法 | 谁被放宽 / 谁不受影响 |
|---|---|---|---|
| A | `CompetitiveGapService#getUserDeviceScope`、`OpsRbacService#getUserRoles`、`OpsRbacService#getUserMerchants` | 三个读端点从 `requirePermission(PERM_OPS_RBAC_ASSIGN)` 改为 `requireAnyPermission(PERM_OPS_RBAC_ASSIGN_DEVICE, PERM_OPS_RBAC_ASSIGN)`，与写侧 `assignUserDeviceScope` 同形；子码字面量收进常量，写侧那条裸串一并换掉 | **放宽**：只持 `ops:rbac:assign:device` 的角色现在能看到自己负责的范围/角色/商户（此前一进用户详情就 403）。零写面变化 |
| B | `SysDictController#upsertType`、`SysDictController#createItem` + `SysDictService#upsertType`、`#upsertItem` | **推荐被新证据推翻并当场订正**：我原本推荐「注解删 `ops:dict:import`」，理由是「import 角色走批量入口」。查同仓惯例后反转——`AnnouncementController:40`、`CouponController:58`、`AdminDashboardController:554`、`OpsExternalImportController:54/72/97` 四处都是 create/upsert 端点**同时**认 `:edit` 与 `:import`。CSV 导入正是逐行走这两个端点（前端 `importPerm` 已经把按钮给了 import 角色），所以正解是**服务层并受 import**，注解保持 `{edit, import}` 不动 | **放宽**：只持 `ops:dict:import` 的角色现在真能导入字典类型/词条（此前前端按钮可见、每一行都在服务层 403）。**不放宽改与删**：PUT `/dict/{type}/items/{id}` 与 DELETE `/dict/types/{t}` 的注解仍是单码 `ops:dict:edit`，服务层虽然 `(edit OR import)`，有效门 = 注解 ∧ 服务层 = edit ⇒ 导入角色改不了也删不了既有项 |
| C | `CompetitiveGapService#recordPhoneVerify`、`#updatePhoneVerify`（→ 要 `ops:phone-verify:edit`）、`#deletePhoneVerify`（→ 要 `ops:phone-verify:delete`）、`DeviceAssetService#stockHealth`（OR 集里补 `ops:stock-health:export`） | 写/导出路径的服务层校验从「读码」换成对应写码（导出那条是补码，因为它同时服务 `/reports/stock-health` 读端点，换掉会把读面打断） | **放宽**：持写码但不持 `phone-verify:list`/`user:list` 的人现在能在服务层过闸（此前只靠注解单门，服务层形同虚设）；持 `stock-health:export` 的人能导出 |
| D | `SiteContractService#listPage` | `requirePermission("ops:device:list")` → `requireAnyPermission("ops:org:list", "ops:device:list")` | **放宽**：组织角色（`ops:org:list`）现在能读站点合同列表。`SiteContractService#list` 无调用方，未动 |
| 保留 | `MerchantPortalService#saveMyOpsConfig` | 不动。双门（注解 `merchant:replenishment:view` 粗门 + 服务层 `request` 细门）是拍板结果；注解只剩一个码，删到空=拆门=扩权 | 无变化。javadoc 里同时订正了原来「权限 merchant:replenishment:list 由控制器校验」这句与两侧实现都不符的旧注释 |
| 保留 | `JiangyiShelfService#shelve` | 不动（批次 P 的判定作废：分支权限合法）。`AdminJiangyiController#shelveLearnedProduct` 从豁免清单里移除，靠分支判定自然通过 | 无变化 |

- 豁免清单从 12 条降到 **1 条**，`MAX_EXEMPTIONS` 默认值同步从 12 收到 1（下次谁想加豁免，必须先改这一行并在台账写理由）。
- 权限矩阵本身不再是「待补」：`node scripts/check-permission-matrix.mjs` + 15 条负向用例进聚合链（92 道）。
- 验证腿：定向小测（受影响服务/控制器的全部用例）→ 聚合链 93 道 → 15 条门禁负向用例 → 全量 `clean verify` + `pre-push-ci-preflight`。
  本轮全量在**独立 worktree** 跑：共享 `services/common/common-core/target/*.jar` 被同机另一个 mvn 构建（jacoco fork）占用，
  `clean` 删不掉 ⇒ 在主树连跑两次都是 `Failed to delete`（构建失败，不是代码失败）。跑完的数与命令记在 `regression-index.md` 本批行里。

### 批次 R｜权限矩阵规则 2：写端点必须能归入一种授权类别（2026-11-04，用户「按照你的推荐做」）

**要拦的失效面不是批次 Q 那一族**，而是它看不见的那一侧：规则 1 只遍历「已经有注解的端点」，
所以**新增一个没有任何门的写端点，规则 1 永远不会响**。这轮把这条补上。

判据（可推导优先，推不出来才要求显式声明）：

| 类别 | 怎么判出来 | 今天条数 |
|---|---|---|
| `ops-perm` | 方法/类上有 `@RequiresPermissions`，或方法体/本类私有门里有 `require*Permission*`，或它调用的服务方法带校验 | 324 |
| `machine-token` | 完整路径以 `/internal/` 开头（`WebConfig` 把 `/internal/**` 交给 fleet-key 拦截器，三条设备路径走一机一密） | 31 |
| `pre-auth` | 命中 `WebConfig.AUTH_EXCLUDE_PATTERNS`（默认拒绝 `/api/v2/**`，只有这张表放行；表是**读源码现取**的，不是我抄的清单） | 18 |
| `dev-only` | 类上有 `@ConditionalOnProperty(mock-enabled)` / `@Profile`——生产这个 bean 根本不存在（`ProductionStartupValidator` 拒 mock-enabled） | 3 |
| `unclassified` → 基线 | 上面都推不出来 ⇒ 必须在 `scripts/permission-matrix-unclassified-writes.json` 里有 `{ep, category, reason}` | **37** |

- 全量：**413 个写动词处理器**（POST/PUT/PATCH/DELETE，GET 不计）。锚点 `MIN_WRITE_HANDLERS=200`：识别数掉一半即红。
- 37 条基线里 36 条是 `self-service`（消费者/商户/运营账号只能改自己的数据，主体一律取自 `AuthInterceptor.ATTR_USER_ID`），1 条 `unverified-write` ⇒ 新登记 **MA-150**（`DeviceController#adPlay` 打点不带主体、可换 deviceId 绕去重；口径见该行——我们是流量主收益侧，不是花钱投放）。
- 基线只许减：`UNCLASSIFIED_CAP=37` 写死在脚本里；清单条目一旦能自动归类就报「腐烂」；缺 `category/reason` 或 category 不在允许集合 ⇒ fail-closed。
- 🔴 **明确不覆盖的轴**：`self-service` 只声明「授权类别」，**不证明归属校验**。IDOR（服务层到底有没有按 userId 过滤 orderId/sessionId）是另一条轴，本门禁不判——批次 Q 的 C 族已经见过「服务层拿读码当写门」这类形态，说明这条轴值得单独立项，但不在本批范围。

#### 我这轮先造了假阳性，两条都记下来

1. **跨行注解**：`@RequiresPermissions(value = {...},\n        logical = ...OR)` 的尾行既不以 `@` 开头也不是注释，
   按「只吞 @ 行」的回溯会从那里断掉 ⇒ `OpsExternalImportController` 的 5 个端点被我误报成「没门」。
2. **私有门与换名**：`OpsPayoutAccountController` 的 create/disable/setDefault 走本类私有方法 `requireOwnerPermission(...)`，
   我的正则只认 `require(Any)?Permission` ⇒ 又是假阳性；`AdminDashboardController#collectUnpaidOrder` 同族。
   修法：正则放宽成 `require[A-Za-z]*Permission`，并加**同文件一跳展开**（深度上限 3，命中即算有门）。

⇒ 49 条初测里 12 条是我自己的解析噪声；剩下 37 条才进基线。规则 2 的负向用例共 6 条：
裸写端点必红、补注解必绿、`/internal/**` 必绿、`@ConditionalOnProperty` 必绿、基线腐烂必红、基线超上限必红。
（另修了一个自己写的洞：上限原来默认 `unclearedRaw.length` ⇒ 「往里加条目」永远不红。）

- 验证腿：21 条负向用例全过；聚合链 93 道（含 `check:permission-matrix` 与 `-negative`）；台账 150 行、open=1（就是 MA-150）。

### 批次 S｜活动展示位打点补齐（2026-11-04，用户：「首页、开门前/关门后都可能有活动」+「按你的节奏做」）

先纠我自己两处口径错误（都记进教训）：

1. 我第一版把这件事定性成「曝光可被刷，加 `user_id` 归因」。用户举例问的其实是**「多少人享受了 8 折」**——那个数的权威根本不在打点表，
   而在 `cabinet_order.promotion_id + activity_discount_cents`（今天就查得出，本批未动）。
2. 我随后断言「开门前/关门后没有活动位」——**又错了**：那两个位置是存在的，只是走的是 CB-082 的**弹窗**
   （字典 `promotion_placement_position` = `BANNER / POPUP_BEFORE_OPEN / POPUP_AFTER_CLOSE`，DTO `MarketingPopupDto`），
   我当时只 grep 了轮播组件名。⇒ 真实缺陷是「弹窗位有渲染、零打点」（MA-151），不是「位置不存在」。

**落地的东西**

| 面 | 改动 |
|---|---|
| schema | `V439` 给 `ad_play_event` 加三列 `activity_id` / `position` / `user_id`（各带索引）+ 两条守卫式回填：`device_id ~ '^MP-U[0-9]+$'` 的行把号填进 `user_id`、这些行 `position` 补 `BANNER`；`device_id` 原样保留不改历史报表 |
| 后端 | `AdCampaignService.recordPlacementEvent(userId, position, activityId, campaignId, assetId, type)`：**小程序事件 `device_id` 留 null**（不再伪装成柜机号）；去重键改为 `subject × position × activity × campaign × asset × type`，其中小程序 subject=`U{userId}`（**按人去重**）、设备侧仍是 deviceId；设备/柜机屏路径（position/activity 皆空）**逐字保持旧键格式**，不让 60s 窗口内在途键失效（有专测钉） |
| 校验 | `ConsumerMarketingService.recordPlacementEvent`：位置必须在字典集合内、`activityId` 与 `campaignId` 至少一个、活动必须存在且 ACTIVE ⇒ 否则 400；位置判据复用 `PromotionPlacementService.isKnownPosition`（不再抄第二份字面量集合） |
| API | 新端点 `POST /api/v2/marketing/placement-events`（`// authz: self-service` 声明，未登录 401）；`openapi.ts` 与端点改动**同批 regen**（门禁要求） |
| 小程序 | banner 组件改走新口并带 `position='BANNER'`；`index.vue` 开门前弹窗、`result.vue` 关门后弹窗各补 `IMPRESSION`（展示时）与 `CLICK`（点 CTA 时）；上报失败从 `.catch(() => {})` 改为 `console.warn` 留痕（仍不打扰用户） |

**新增 9 例钉测**（两类合计 27 例全绿）：按人落库且 `device_id` 为 null、同用户重复被吞/换用户各算一次、同用户不同位置各算一次、设备侧键格式与去重兼容、位置未知 400、两 id 全缺 400、活动缺失/停用 400、位置大小写归一并委派、banner-only 不查活动表。

**顺带挣到的两条证据**：为出 spec 而起的一次性 postgres 上，全新空库 V1→**V439** 严格升序跑通、三列确实存在（`max(version::int)=439`）；
🔴 这里刻意**没有**用共享 dev 库/18080 容器：`DemoDataService` 与 `SysDictBootstrap` 无开关，boot 上去会把演示数据「ensure」回来，污染 peer 刚清过的台账（我自己记着的坑）。

**这批明确没做**：不设任何配额/阈值（0 行真实数据下阈值只能是猜的死旋钮）；ROI 报表的「按位置分桶 + 人均/单用户最大次数」两列留下一批（那要动 admin 源码并重建产物）。
MA-150 保持 open，**但定性被我下一轮推翻**（原话「确认仍无调用方 ⇒ 死写面」是错的，见批次 T 小节的订正）：
`/api/v2/devices/{deviceId}/ad-play` 是在用的——`clients/consumer-mp/src/components/device-ad-banner.vue:174/:191/:204`
经 `consumerApi.reportAdPlay`（`utils/consumer-api.ts:659`）上报，组件挂载点在 `pages/index/index.vue:53`
（`v-if="deviceId && adBannerVisible && !marketingSlotVisible"`，与 CB-082 营销轮播互斥兜底）。我漏判的原因：只 grep 了
包装名 `deviceAdPlay` 在 `.vue` 里的命中，没 grep 方法名 `reportAdPlay` ⇒ 结论作废，**不能按死代码删**。
缺陷本体不变：任何登录用户都能给**任意已存在的设备**写曝光/完播/点击（`device_id` 有 FK 指向 `device_info`，
所以只能伪造成真柜号、不能凭空造号），而去重键 `(device, campaign, asset, type)` 不含人 ⇒ 换 assetId/eventType 就多计。
三档待拍板：A 取登录主体写 `user_id` 并要求该 deviceId 与调用者有活动/近期会话（最小，归因+伪造一起关掉，前端不动）；
B 让该组件改走批次 S 的 `/placement-events`（写面收敛成一个口，但要给 COMPLETE 定语义）；
C 只有「开门页设备投放位」整个功能决定下线，才连带删端点 + `reportAdPlay` 包装 + `ConsumerEndpoints.deviceAdPlay`。

### 批次 T｜活动位效果按展示位分桶 + 两条真库底线（2026-11-04，用户：「我们没有柜机屏幕，做 ROI『按 position 分桶 + 人均/单用户最大次数』」+「首页和开门前应该是同一个，按照你的节奏做」）

这一批先被真库推翻了两件事，都进台账（MA-152 / MA-153），再谈新功能：

1. **批次 S 的弹窗位打点其实一条都没进过库**。`ad_play_event` 的 `campaign_id` / `device_id` 自 V191 起都是
   `NOT NULL`，而弹窗位按设计只有 `activity_id` ⇒ 每次 `POST /placement-events` 都在 DB 层炸
   `NotNullViolation`；MP 侧 `.catch(e => console.warn(...))` 把它降成一行日志，真机上看不出任何异常。
   9 例单测全绿是因为 mapper 是 mock——**MyBatis-Plus 生成的 INSERT 从未被任何数据库执行过**
   （同型第三次撞上：`WithdrawClaimForPayoutSqlIT`、教训 #329/#330/#396）。
2. **批次 S 的 openapi.ts 是坏的**：460 个中文全被写成双重编码乱码，形状门禁看不见，CI 的
   `起 jar → 抽真 spec → git diff` 才红。批次 S 的**形状**没问题（按现抓 spec 重打后「非注释删除行 = 0」），
   坏的只有编码。⇒ 乱码本体由并发会话 `569ac0a6` 用 latin1 反解还原、并给**生成侧**加防线；本批补的是**产物侧**哨兵（`check-openapi-types.mjs` 扫生成物，`check-openapi-types.test.mjs` 9 向钉住）+ 批次 T 新形状的 regen。

**落地的东西**

| 面 | 改动 |
|---|---|
| schema | `V440`：`campaign_id` / `device_id` 放开 NOT NULL，并补 `ck_ad_play_subject CHECK (campaign_id IS NOT NULL OR activity_id IS NOT NULL)`——放开非空是为了让两类主体各自成立，底线改由 CHECK 守，不留「谁都能塞一行无主事件」的口子 |
| 后端 | `AdPlayEventMapper.aggregatePlacementsSince`（按 位置×主体×主体id 汇总曝光/已归因人数/点击/未归因行）+ `peakExposurePerUserSince`（两层 group-by，先 (位,主体,人) 再取人级最大，只统计 `user_id IS NOT NULL`）；`MarketingRoiService#placements(days)` 把两趟结果并成 `PlacementEffectRowDto`，窗口钳在 7–90 天 |
| API | 新端点 `GET /api/v2/ops/admin/growth/marketing-roi/placements?days=`（注解 `ops:marketing-roi:view`，服务层同名 `requirePermission` ⇒ 两侧都有门）；`openapi.ts` 与端点**同批**由现抓 spec 重打 |
| 后台 | `MarketingRoiView.vue` 主表下加「活动位效果（按展示位）」表：位置/主体/曝光次数/曝光人数/人均曝光/单用户峰值/点击次数/未归因行，取数失败只在本表位置提示不连带主表（`useCrudTable` 的 `errorMessage`）；位置译名走字典 `promotion_placement_position`，主体名缺失回落 `#id`。**首版直接写裸表格标签被 `check-admin-table-gate` 拦下**（该闸 CI-only ⇒ 本地预检全绿、CI 红），改成第二套 `useCrudTable`+`CrudTable`，并把 `gate:admin-tables` 接进聚合链（94→95） |
| 真库钉测 | `PlacementEffectSqlIT` 2 例（一次性 Testcontainers 空库 V1→440）：① 三新列经 mapper 写进可读回、`position` 在 PG 里同时是函数名所以列引用必须实测、未归因行不进 `COUNT(DISTINCT user_id)`、峰值取人级最大（2 而非总次数 4）、ACT/CAM 两套主体名各取各表；② **反向**用例——两主体全空的行必须被 `ck_ad_play_subject` 拒掉，并把约束名断言进去（防止红在别的约束上） |
| 浏览器真验 | throwaway 三件套（自建 jar :18099 + 一次性 PG :25433 + 一次性 Redis :26379 + vite :3210，共享 18080/15433/6379/3100 全程未动）播种后断言：`开门前弹窗｜新品8折·弹窗活动｜4｜2｜2.0｜2｜1｜1` 与 `轮播横幅｜新品8折·广告计划｜1｜1｜1.0｜1｜0｜0`，两张 `.crud-table`、零 console/pageerror（截图 `.tmp/ui-placements-1104.png`）。踩到隔离栈老坑：vite 代理把 `Origin` 改写成 `http://localhost` ⇒ 后端 CORS 恒 403，要 `VITE_DEV_ORIGIN=http://localhost:5173`（允许列表里有） |

**为什么必须自播两个主体**：`campaign_id` 有 FK 指向 `ad_campaign`（V217 加的，建表迁移里看不到），
`activity_id` 没有 FK——弹窗行只靠服务层「活动必须存在且 ACTIVE」拦，DB 层不兜底。首跑先撞
`asset_id` 的 FK（V217，指向 `media_asset`），本用例不测素材维度，留空即可。

**这批明确没做**：不动 `/api/v2/devices/{deviceId}/ad-play`（MA-150 仍 open；用户已授权删除的前提「零调用方」经复核不成立，见上一段订正）；
不给弹窗位设任何配额/阈值；不改 banner 位的主体口径（banner 仍挂 `AdCampaign`）；
**活栈那一轮已跑（2026-11-04 12:3x，共享 dev 栈）**：为应用 V438–V440 用 origin tip 重烤了 trade 镜像
（`infra/docker-compose.full.yml` + `win-ports.yml`，Flyway 把 dev 库从 V434 一路升到 **V440**，
`ad_play_event` 现有 `activity_id/position/user_id` 三列 + `ck_ad_play_subject`，`/v3/api-docs` 里两条新路径都在）。
消费者口令登录后打 6 条用例：开门前曝光/点击、关门后曝光各 200，未知位置 400、无主体 400、未登录 401；
库里回读 `POPUP_BEFORE_OPEN / IMPRESSION / user=10001 / device=NULL / activity=6` 等 **3 行**（`device_id` 全 NULL，
即不再伪装柜机号），随后按 `event_id > 基线最大号` 圈定删干净、行数复原到基线 0。探针：`.tmp/live-placement-round-1104.mjs`。
**小程序 UI 那一腿也已跑绿（2026-11-04 12:5x，微信开发者工具 + automator 0.12.1，auto 端口 9422 避开 peer 的 9420/9421）**。
前提确认：dev 库 `promotion_placement` 里原本**一条 `POPUP_*` 投放行都没有**（只有 activity 6 的 BANNER），
所以弹窗接口恒回空数组、组件不渲染 —— 这是**数据没配**，不是代码缺口。经用户授权临时插一条一次性
activity（`SKU_DISCOUNT`/ACTIVE/ALL）+ 一条 `POPUP_BEFORE_OPEN` 投放行，登录后进首页实测 6 项全过：
① `.promo-popup-mask` 与关闭按钮渲染出来（截图 `.tmp/mp-popup-1104.png`）；② 展示即报 `IMPRESSION` 落库，
行形态 `POPUP_BEFORE_OPEN / user=10001 / device=NULL`；③ 点图片报 `CLICK` 落库；④ 后台读面
`GET …/marketing-roi/placements` 回 `{position:POPUP_BEFORE_OPEN, subjectKind:ACTIVITY, exposureCount:1, exposureUsers:1, clickCount:1, exposurePerUser:1, maxExposurePerUser:1, unattributedCount:0}`，
主体名取到临时活动名；⑤ 三项计数在 finally 里删净复原（activity 5 / placement 1 / 事件 0，与播种前逐项相等）。
🔴 **我在这轮写下的「图片没出图」是误判**：截图里那块蓝色就是 asset 7（`promo-banner-blue-750x300`）的内容，
`GET /api/v2/media/ad-assets/{id}` 匿名与登录都是 200 + `image/png`。⇒ 该轮证的既包括结构/链路也包括素材渲染。
探针 `.tmp/mp-popup-ui-round-1104.mjs`。

**dev 已配一条常驻弹窗投放（用户授权，2026-11-04 13:0x）**：走运营真实顺序
建活动 → 配展示位 → 上架（**顺序不能反**：`PromotionPlacementService` 对 ACTIVE 活动直接 409「请先停止再编辑展示位」，
而 `launch` 又要求先有 BANNER）。结果 activity **11**「开门前弹窗演示（dev 常驻）」ACTIVE + placement 11(BANNER/asset 4) +
placement 12(**POPUP_BEFORE_OPEN**/asset 5，750×1000)；消费者读面 `/marketing/popups?position=POPUP_BEFORE_OPEN` 拿到该条，
小程序模拟器里弹窗带图渲染（`.tmp/mp-popup-live-1104.png`，src=`…/ad-assets/5`）、`IMPRESSION` 与 `CLICK` 落库、
`device_id` 仍为 NULL。库里现有 2 行是**有意的演示数据**，别再当残留删掉；脚本 `.tmp/dev-config-popup-placement-1104.mjs`、
`.tmp/mp-popup-live-check-1104.mjs`。
automator 0.12.1 的 `page.data(array)` / `page.text()` / `page.path()` 都不可用 ⇒ 判据一律走 DOM 选择器 + 库里行。

### 批次 U｜MA-150 A 档落地 + IDOR 轴第一轮（2026-11-04，用户：「MA-150 那就选 A」「IDOR 轴按照你的推荐做」）

| 面 | 改动 |
|---|---|
| 归因 | `DeviceController#adPlay` 从 `AuthInterceptor.ATTR_USER_ID` 取登录主体（空则 401；该路径不在 `AUTH_EXCLUDE_PATTERNS` 里，未登录本来到不了）；`recordPlayEvent` 新增 5 参重载，写 `user_id` + `position=BANNER` |
| 去重 | 归因分支的键改为**按人**：`U{userId} × BANNER × - × campaign × asset × type` ⇒ 旧结论「换 deviceId 即绕开 60s 窗口」不再成立。设备凭据口（`/internal/v1/…`）走 4 参重载，逐字保留旧键格式（键格式一变会让窗口内在途键失效，等于放开一次重复上报） |
| 一列两义 | `MarketingController#adEvent` 不再写 `device_id="MP-U"+userId`，改传 `deviceId=null` ⇒ 与批次 S/V439 的口径一致（小程序流量不伪装成柜机号）；`device_id` 为空时跳过设备范围校验（没有柜可校） |
| 死口 | `AdPlayEventDeduplicator` 的 4 参 `tryAcquire/release` 与私有 `dedupKey(String,…)` 删除：MA-150 后生产无调用方，留着就是「谁都能再走一遍不带人的去重」的诱惑。`AdPlayEventDeduplicatorTest` 22 处改用 `deviceKey()` 夹具（position/activityId 皆空 ⇒ 仍走旧格式），钉「旧格式没因改口而漂移」 |
| 测试 | `AdCampaignServiceTest` 新增 5 例：写 user_id+position 且保留 deviceId、同人换柜机只记 1 行、不同人各记、device_id 为空仍落库且不伪装、4 参重载保持旧语义。合计该类 25 例、去重类 6 例全绿 |
| 活栈复验 | dev 镜像重烤到批次 U 后实测：同一消费者用**两个不同柜机号**各报一次 ad-play ⇒ 库里只新增 **1 行** `BANNER / user=10001 / device=500909625160`（改前会是 2 行且无 user_id/position）；未登录报 ad-play → 401。探针 `.tmp/ma150-live-check-1104.mjs` |
| dev 演示数据 | 新增常驻投放「柜机位打点验证（dev 常驻）」（campaign 3，RUNNING/ALL/CABINET_SCREEN + item asset 4）。原因：campaign 1 的 `ad_campaign_device` 是空的，SPECIFIC 范围会把事件**静默丢弃**，不新建就验不到归因。**与 activity 11 一样是有意保留的演示数据，别当残留删** |
| 门禁 | `permission-matrix-unclassified-writes.json` 里 ad-play 条目 `unverified-write → self-service`，reason 重写为「归因已落 + 残余是刷量倍率」；条目总数 37 不变（上限 37），矩阵计数 630/599/196 不变 |

**IDOR 轴第一轮**（13 条带路径 id 的 self-service 写端点）：12 条服务层确有归属闸，但只有 1 条有「非本人必须失败」的用例 ⇒ 登记 MA-154。
两点要留着别忘：① 「记录不存在/锁忙」的用例**不等价于**归属用例，前者换 id 也会失败但原因不同；② `ad-play` 的 `{deviceId}` 与调用者无归属关系是**有意**的（柜机不是用户所有），要不要收成「近期开过这台柜」是产品决定，本批不动。
另记一条取证卫生：子代理第一轮读到的部分行号是我并发修改前的版本（它自己在报告开头标注了这点）⇒ 它的结论我逐条回读当前工作树复核后才入台账。

### 批次 V｜MA-158 报修频控按档①落地（2026-11-04，用户：「好的」= 授权我推荐的档①）

台账本轮唯一由我这条线留下的 open（MA-158）收口 ⇒ 该行转 fixed。**注意**：说「open 归零」前必须现算——同一时段并发会话在 R18 线上另开了 MA-157/MA-159（状态写作 `**open**` 加粗，用 `^open` 粗匹配会漏判），那两条不由本批判定。

| 面 | 落点 |
|----|------|
| 后端 | `ApiRateLimitService.assertFaultReportAllowed(userId)`（新动作常量 `fault_report`）复用既有小时窗口与 `enabled` 总开关；`DeviceFaultReportService:109` 在**抢 (user,device) 锁之前**调用 ⇒ 被拒的重复上报不占锁、不落行（告警层另有 `DEVICE_FAULT:{deviceId}` + OPEN/PROCESSING 去重，见 MA-158 行的订正） |
| 配置 | `RateLimitProperties` 新增 `maxFaultReportsPerHour`（默认 **10**，非正数回落默认，与其他五档同形）；`application.yml` 落 `max-fault-reports-per-hour: ${RATE_LIMIT_FAULT_REPORTS_PER_HOUR:10}` ⇒ 线上可调、可整开关掉 |
| 维度取舍 | **没有**做设备维度计数器（否掉我自己在台账里写的 `(userId, deviceId)` 候选）：同一台真坏的柜机会有多名消费者各报一次，按设备共用计数器会把后来者误拦。这条理由写进 `RateLimitProperties` 类注释，防后来人「顺手对齐开门的双计数」 |
| 契约 | 无形状改动：429 走既有 `ResponseStatusException`，全仓控制器**没有任何一处**用 springdoc 标注过 429 ⇒ 不需 regen（预检 `openapi-regen` 亦判无形状差） |
| 测试 | `ApiRateLimitServiceTest` 7→9（键名钉 `aicabinet:rate:fault_report:{userId}`、超限 429 且不回写计数）；`DeviceFaultReportConcurrencyTest` 1→3（超限 ⇒ 429 且 never tryLock / never save / never 推告警；正向对照 ⇒ 未超限照常落单并释放锁，同时让「闸被删掉」因未消费桩 + verify 立刻红）；`RateLimitPropertiesBindingTest` 四向补新键。定向跑 9+3+4+3 全绿 |
| 连带改动 | record 多一个组件会连坐构造点：本次改 9 处 `new RateLimitProperties(...)`（3 个测试类 + 绑定测试的钳位用例） |
| 证据边界 | 档②「同一未结案工单幂等合并」**已复核撤下**（不需要：重复告警本就被 `OpsExceptionService` 的 OPEN/PROCESSING 去重挡住，行级合并只省一张无读面表的行数）；`/fault-report` 的**归属**面本来就正确，本批没动。**活栈复验走了隔离栈**（2026-11-06）：干净 worktree（HEAD）构建 jar + 临时 PG/Redis + 18100 端口，**9/9 通过**——报修前 10 次 200、第 11 次 429、只落 10 行工单、告警只加 1 条（顺带实测坐实了告警层的柜机去重），同轮复验批次 T 弹窗位落库与批次 U 按人去重/未登录 401；共享 dev 容器**仍未升级**（重建会把并发会话在制品整棵烤进去），升级属「部署」不属验证 |

### MA-160 组合腿重复扣（R17 批 3 收口，P1，锚 `d7b37278`）

- **怎么判死的（不是推测）**：批 2 补扣链腿已在 E2E 造出「冲抵 300 → PENDING」这个真实前置态；而补缴换渠道
  是**已上线入口**——consumer-mp 结算页有支付方式选择器并真传值（`clients/consumer-mp/src/pages/order-detail/order-detail.vue:820-824`
  → `POST /api/v2/orders/{id}/pay` body.channel，`OrderController.java:77-85`）。前端给了入口＝产品已承诺该码可用，
  后端不认它就是缺陷。不传 channel 的自动决策腿同样命中（`OrderPaymentService.java:171` 先试免密）。
- **口径分叉的事实**（行号为修复锚 `d7b37278` 之后，复核请优先按符号 grep）：余额腿 `applyBalanceCharge:466`
  按「应付 − `netCompletedCents`」扣；渠道腿 `chargeWithSelectedChannel:231`（显式）与 `submitChannelCharge:305`（流水）
  都按「应付全额」。而 `PaymentOperationMapper.java:117-120` 自己写着
  漏算冲抵行的后果就是「按全额重复扣」，`UnpaidOrderService.java:381-383` 注释亦称「补扣只收差额」
  ⇒ 判为实现与既有拍板不一致，不是「补缴即重新全额收」的商业决策。
- **修**：新增单源原语 `OrderPaymentService.remainingChargeCents(order)`，三条腿共用；差额 ≤0 不再向渠道发起扣款；
  `recordPayScoreOrder` 痕迹行改记**实扣差额**（否则 payscore_order 比微信账单虚高）。
- **刻意保留不对齐的一腿**：`tryCompleteWeChatPayScore` 仍按全额完结支付分订单——免密会话开门时
  `ConsumerPreauthService.freezeForOpen` 的 `passwordFreeReady` 早退、根本不冻钱包 ⇒ 净入账恒 0，全额即差额。
  理由已写进该方法 javadoc，后来者勿「顺手对齐」。
- **测试**：`OrderPaymentChargeInvariantTest` 4→6 例（组合腿 + 差额=0 边界）。组合腿用例修前必红
  （`expected: <40> but was: <100>`），修后 6/0；trade-service 全量 2762/0。
- **原记「未闭合：MA-161…拆分退款需另拍板」已过期**（2026-11-06 对账订正）：MA-161 A1+A2 均已落地——按渠道实扣额截断、
  其余退钱包，且拆腿单的幂等键按毛额派生防重试换键双退。判据见 MA-161 行（`8382d814`，已随 `129dceaa` 进主线并推
  `origin/dev`）。本批 MA-160 自身结论不受影响。

| MA-166 | P2 | fixed | **已修（afcf3d83）：POST /merchant-withdraws/wallet-adjustments/{bizId}/review（复用 completeApproved/Rejected）+ 运营台提现页「调账审批」tab（inbox 过滤 PENDING+通过/驳回）；活栈回归：调账建实例→运营审→财务审→APPROVED 全绿；驳回=留痕不自动冲回（提示人工评估）** | 大额调账审批（MERCHANT_WALLET_ADJUST） | **审批实例无产品内审批入口=永久死待办**：调账 ≥¥500 触发 start() 建实例+approval_task+站内信+inbox 红点，但全栈无审批动作路径（`merchant-withdraws/{id}/review` 只查提现单表→400；OpsApprovalInbox 把该 bizType 跳转 /merchant-withdraw 页，该页无调账实例卡片/按钮；无业务回调）⇒ 实例永久 PENDING、待办清不掉；调账资金本身已即时生效（事后留痕语义），审批纯属形式但永久挂起 | ZCode UAT R9 / 2026-11-07 | R9-E 新增审批流端到端测试（E5a 全入口 400+前端 grep） |
| MA-165 | P1 | fixed | **已修（afcf3d83）：UpsertMerchantRequest/MerchantDto 补 legalPerson+businessLicenseUrl（不传不清空）+ MerchantSplitsView 表单两字段 + shared-types；活栈回归：无资质拒→API 写入→DB 落库→提现过闸 PAID 全绿** | 商户提现链 | **资质前置字段全栈无写入路径=提现功能死锁**：`WithdrawEligibilityService` 要求 `legal_person`+`business_license_url` 非空才允许发起提现，但两字段无任何合法写入途径（管理端 `UpsertMerchantRequest` 不暴露、商户门户无表单、后端无 setter 调用）⇒ 第一个真实商户提现必被「资质不完整」拦截且无法自救；须补管理端/门户资质编辑面或放宽前置 | ZCode UAT R9 / 2026-11-07 | R9-D 审批遍历（D4b 被拦+全仓 grep 无写入方） |
| MA-164 | P1 | fixed | **已修（b010ed13 修复 AI 早于 R9 报告 11 分钟合入主线，UAT 镜像陈旧致误报 open）+ 2026-11-07 UAT 镜像重建后活栈复验：reconciliation SUCCESS（匹配 1 笔 MATCHED）+ 审计 operator=0**；教训=up -d 单 -f 丢 uat override 连回主库、镜像 15:28 vs 修复 15:39 差 11 分钟 | reconciliation 每日对账 job | **定时上下文写审计崩 500**：`runDailyReconciliation` 无操作者，写 `admin_audit_log`(RECONCILIATION_RUN) 时 operator_id=NULL 撞 NOT NULL → 整个对账事务回滚，**每日 01:30 对账必失败**（UAT 实测：diffCents=0 对账算平仍崩，`scheduled_task.last_result=FAILED`；上线即每日红） | ZCode UAT R9 / 2026-11-07 | .tmp 调度 sweep（scheduled_task.last_message 全栈报错） |


### MA-163 Hikari 连接池只读状态污染（间歇性写失败，P2）
- 级别/状态：P2 / open
- 现象：UAT 空库实测，app 写操作随机报 `cannot execute INSERT in a read-only transaction`（SQLSTATE 25006）；同一时刻 psql 新连接写成功；`default_transaction_read_only=off`、库/角色级无覆盖、postgres 容器零重启。
- 时间窗：21:05-21:37（用户三次开门失败、consent 写失败）、22:07-22:19（购物失败）；两次 `force-recreate trade-service` 后立即恢复，写探针 3/3 绿。
- 定位方向：①全仓 486 处 `@Transactional(readOnly = true)`——Spring 的 DataSourceUtils 会调 `Connection.setReadOnly(true)`，归还时 Hikari 理应复位（resetBits 含 READ_ONLY），但若某条路径绕过（如事务模板外手动借还、或 MyBatis-Spring 与 Hikari 版本组合的复位缺口）即污染；②窗口与 Testcontainers 并行全量测试时间重合，需排除压测串扰。
- 修法方向：Hikari 显式 `connection-init-sql: SET default_transaction_read_only=off`（每连接借出即复位，兜底任何污染源）+ 复现用例（并发 readOnly 读 + 写穿插，断言写永不 25006）。
- 实测证据：`.tmp` 心跳/探针脚本与 trade 日志 21:05-21:37、22:07-22:19 段。
