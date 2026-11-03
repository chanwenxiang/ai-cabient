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
| MA-031 | P3 | fixed（轮末文案批：基线+dist+bootstrap+V426 四处同改，活体复验） | merchant-mp 待办行类型标签 | 4 个待办类型不在 `merchant_alert_type` 基线里 ⇒ 行首类型显示「未知」（实测命中 补货任务超时 / 开门超时 两行）；缺 `OPEN_TIMEOUT`、`RESTOCK_TASK_TIMEOUT`、`SLOT_DISCREPANCY`、`INVENTORY_MISMATCH`；另 `DOOR_OPEN_TOO_LONG` 与 `OPEN_TIMEOUT` 若都译成「开门超时」会撞 `DictLabelUniquenessIT` 的同名门禁 | Qoder R9 / 2026-11-02 | 修法：基线 `packages/shared-dict/src/index.ts:783-798` 补 4 值（`DOOR_OPEN_TOO_LONG` 改「长时间未关门」与 `exception_type` 对齐）+ 重烤 shared-dict dist + `SysDictBootstrap` 默认值 + 迁移补 DB 侧字典（admin 读 DB）；判据 = 待办页 0 行「未知」 ⇒ **已达成**：重烤产物后实跑 `pages/alerts`，「未知」出现 0 次，两行改读「开门超时 / 补货任务超时」；`DictLabelUniquenessIT`（真库 Flyway 从零含 V426）1/0/0 证明同 type 无重名 |
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

| MA-068 | P2 | fixed（钉测 1 例） | merchant-mp `POST /merchant/devices/{id}/stocktake-evidence` | 盘点凭证上传缺设备归属校验（同 Controller 全部设备写端点都过 requireDevicePack，唯独上传漏）⇒ 商户 A 可对商户 B 柜机挂任意照片/灌图，盘点凭证链可跨租户污染 | ZCode R12批1 / 2026-11-02 | rounds/R12-write-surface.md |
| MA-069 | P2 | fixed（限幅单源+门禁） | `POST/PATCH /ops/admin/line-managers` 佣金参数 | 线长佣金 rateBps/fixedCents 裸 Map 无界直落（负数/50000bps=GMV5倍佣金都存得进），LineCommissionJob 按日真实入钱包 ⇒ 持续性资金乘数无范围校验；对照 WalletAdjustRequest ±1亿分先例 | ZCode R12批1 / 2026-11-02 | rounds/R12-write-surface.md |
| MA-070 | P2 | fixed（档 A：服务层按注解放宽） | admin `/org-sites` 组织与点位 | 权限码双轨：Controller 注解 ops:org:*、服务层强制 ops:device:*（AND），唯 DELETE 是 OR ⇒ 只授 ops:org:* 的角色「页面 403、增改 403、删除却放行」，删比改松方向反常 | ZCode R12批1 / 2026-11-02 | rounds/R12-write-surface.md |
| MA-071 | P3 | open | OpsRbacService 三个分配端点 | 整删整插 body 裸 List 无去重，重复 ID 触发复合主键 DuplicateKey→500（应 200 去重或 400）；roles/{id}/permissions、users/{id}/roles、users/{id}/merchants 三处 | ZCode R12批1 / 2026-11-02 | |
| MA-072 | P3 | fixed（改密 + 2FA 开关三处留痕） | 运营改密/2FA 关闭 | PUT /rbac/me/password、two-factor/confirm、/disable 零审计留痕——同 Controller 其它 14 处 RBAC 写全留痕（CB-021），账号接管链关键事件审计页不可见；商户端改密反而有 PASSWORD_CHANGE | ZCode R12批1 / 2026-11-02 | |
| MA-073 | P3 | fixed（商户/线长提现申请留痕；系统重试仍记为系统） | 提现「申请」动作（商户+线长） | review/payout/cancel 三兄弟全有 MERCHANT_WITHDRAW_* 审计，唯独 APPLY 无；运营代提在低于阈值自动打款分支下操作者三处不可归因（线长侧 submitter 恒 null） | ZCode R12批1 / 2026-11-02 | |
| MA-074 | P3 | fixed（钉测 2 例，幂等命中不留痕） | `POST /ops/admin/staff-wallets/{id}/payout` | 补货员钱包打款标记（资金出账销账动作）无操作者审计；同族 LINE_MANAGER_ADJUST/MERCHANT_WITHDRAW_PAYOUT 均有 | ZCode R12批1 / 2026-11-02 | |
| MA-075 | P3 | fixed（快照留痕） | `PATCH /ops/admin/line-managers/{id}` | userId 换绑/改名/改手机不进 LINE_MANAGER_UPDATE 审计（只有佣金/状态变化才记）；userId 是 CB-066 实名守卫锚点，longVal 不校验用户存在可绑到不存在/他人 ID | ZCode R12批1 / 2026-11-02 | |
| MA-076 | P3 | fixed（部分：税务资料已留痕；补货配置待单做） | 商户补货配置 + 开票税务资料 | PUT /merchant/ops-config（CompetitiveGapService:301）与 PUT /merchant/tax-profile（InvoiceService:174）两处配置类写无审计；税务资料含银行账号 | ZCode R12批1 / 2026-11-02 | |
| MA-077 | P3 | fixed（白名单 400） | OpsPayoutAccountController 收款账户 | ownerType 无白名单：未知值落到 else 分支 `ops:finance:view`（只读权限可写）且垃圾值原样入库；无资金影响（提现链只认 LINE_MANAGER/MERCHANT） | ZCode R12批1 / 2026-11-02 | |
| MA-078 | P3 | open | `POST /ops/admin/merchants` | merchant.status 自由字符串直落：无白名单（"OPEN" 等静默生效使商户从 ACTIVE 过滤面消失）、超 16 字符触发 PG 22001→500 而非 400 | ZCode R12批1 / 2026-11-02 | |
| MA-079 | P3 | open | `DELETE /ops/admin/dicts/types/{dictType}` | 可硬删代码内置字典类型（ORG_NODE_TYPE 等白名单权威来源），删除后 resolveNodeType fail-open 放行任意值；fail-open 本身是 javadoc 钉的设计，缺的是内置类型删除门槛 | ZCode R12批1 / 2026-11-02 | |
| MA-080 | P3 | open | `PUT /ops/admin/org/nodes` | 组织节点 upsert 只拦「上级是自己」，不查父级存在性也不查祖先环（parent_id 无 FK，对照 DepartmentService 有完整防护）⇒ 环中节点双双缺席 roots，整棵子树从 /org/tree 静默消失 | ZCode R12批1 / 2026-11-02 | |
| MA-081 | P3 | 复核不成立（撤账，见文末批次A/B收口节） | `POST /account/bind-openid`（mock-only） | 重复绑定他人已占 openId 撞 uk_user_wx_open_id 唯一索引→500 而非 409；生产 403 门禁在，仅 mock 环境报错语义问题 | ZCode R12批1 / 2026-11-02 | |

| MA-082 | P3 | fixed（券/活动/展示位/素材四服务留痕） | 营销域运营写面（券模板/发券/活动上下架/展示位/素材 5 服务 15 端点） | 审计留痕整体缺失（零处 appendLog），同域兄弟 AdCampaignService 四处全有 AD_CAMPAIGN_* 审计 ⇒ 券面值改动/活动上下架/素材删除在 /audit 页不可查 | ZCode R12批2 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-083 | P3 | fixed（部分：DTO 约束，钉测待补） | `POST /coupons/batch-issue` + `/issue` | DTO 零约束：userIds=null → NPE 500（应 400）；userIds 无上限（万级元素长事务持 coupon_def 行锁）；对照充值 DTO 有完整 @Min/@Size | ZCode R12批2 / 2026-11-03 | |
| MA-084 | P3 | open | `PUT /ops/admin/ad/assets/{id}` | 素材 status 自由串直落（MA-078 同构）：垃圾值静默从全部 ACTIVE 过滤面消失、超 16 字符 22001→500 | ZCode R12批2 / 2026-11-03 | |
| MA-085 | P3 | fixed（闭集+钉测 7 例） | `POST/PUT /ops/promotions` | activityType 无白名单（垃圾类型经 launch 直接 ACTIVE 进消费端可见列表，非 SKU 类型跳过全部校验）；budgetCents 负数直落使预算守卫 fail-open（`budget>0 &&` 守卫），与 user_limit「0/负=不限」钉测口径不一致 | ZCode R12批2 / 2026-11-03 | |
| MA-086 | P3 | open | `POST/PUT /ops/admin/ad/campaigns` | 投放计划时间窗倒挂不拦（startAt>endAt 静默接受→计划永不生效、轮播静默空转）；同域 Promotion 面有拦，兄弟端点不一致 | ZCode R12批2 / 2026-11-03 | |
| MA-087 | P3 | open | 营销域字符串 DTO | couponName/campaignName/mediaTitle/idempotencyKey 缺 @Size，超长撞列宽 22001→500（本仓已有 FileDisputeRequest @Size(256) 等成熟先例，属漏网） | ZCode R12批2 / 2026-11-03 | |
| MA-088 | P3 | fixed（部分：拒绝入账+告警，钉测待补） | `POST /api/v2/payment/wechat/refund-notify` | refund_id 缺失时幂等键塌缩为全局字面量 `channel-refund-sync:null` 且不含 orderId ⇒ 理论上不同订单第二笔退款扣余额被静默吞掉而 refunded_cents 照常推进（幽灵余额窗口）；微信 v3 实际必带该字段，防御性加固 | ZCode R12批2 / 2026-11-03 | |
| MA-089 | P3 | fixed（去 DTO @Max） | `POST /api/v2/account/balance-refunds` | DTO @Max(500_000) 硬编码与服务端可配置上限 BALANCE_REFUND_MAX_CENTS 口径不一致——配置只能收紧不能放宽；对照充值 DTO 无 @Max 全由 DC-6 裁决，两 DTO 风格矛盾 | ZCode R12批2 / 2026-11-03 | |

| MA-090 | P2 | fixed（钉测 7 例） | `PUT /ops/admin/machine-payee-bills/config/{deviceId}` | 机器账单分成比例 shareBps 无界直落（>10000 可生成超 GMV 五倍的分成账单且每账期持续生效）；同仓三处先例都有闸（SiteRentSplit 400 / FundBill 钉测 / allocate 钳位），与 MA-069（线长佣金）完全同构；次要：enabled 无 0/1 约束（垃圾值使该柜从出账面消失）、payeeName null→500 | ZCode R12批3 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-091 | P3 | open | 商户费用台账结清 / 机器账单结清 / 按日重跑日扣 | 三处资金处置动作零操作者归因（连 operatorId 参数都没有；rerun-daily-fees 是批量真扣钱包 WalletEngine.debitIfAbsentAllowNegative）；MA-074 同类 | ZCode R12批3 / 2026-11-03 | |
| MA-092 | P3 | fixed（部分：开票/驳回已留痕；对账重跑仍未动） | 发票开具/驳回、机器账单出账、对账重跑 | 财务面 5 写端点无 /audit 审计（对照 SiteRentBill/DeviceDataFeeBill 全有 appendLog）；ReconciliationService.runDaily 的 operatorId 传而不用，重跑删旧重建无痕 | ZCode R12批3 / 2026-11-03 | |
| MA-093 | P3 | fixed（部分：充值正数闸） | `POST /ops/admin/platform-fund/accounts/{id}/recharge` | 负数「充值」直落：以 TYPE_RECHARGE 语义落负数出账流水扣余额，绕过 adjust 的必填原因口径（V341 三闸类型语义失真）；Controller 用 Map 手工取参，字符串金额 ClassCastException→500 | ZCode R12批3 / 2026-11-03 | |
| MA-094 | P3 | open | `POST /ops/admin/machine-payee-bills/generate` | billMonth 裸 YearMonth.parse：非法账期 DateTimeParseException→500（同域 SiteRentBill/DeviceDataFeeBill 都走 FeeBillMonthResolver 400）；顺带无未来账期守卫 | ZCode R12批3 / 2026-11-03 | |
| MA-095 | P3 | open | `POST/PUT /ops/admin/growth/member-levels` | 会员等级规则零入参校验：区间倒挂 min>max 恒 false=静默死档、负 pointsRate 该档返积分恒 0、status 自由串静默失效；读侧 priceDiscountPct 有钳制（≤90）故无资金直损，写侧应对齐口径 | ZCode R12批3 / 2026-11-03 | |
| MA-096 | P3 | fixed（部分：写侧闸，钉测待补） | `PUT /ops/admin/growth/points-redeem` | 兑换商品 pointsCost 负数直落=持续性积分铸造参数（消费端 redeem 负 cost 使可用积分倒增）；0=免费兑；couponDefId 可 null/不存在（带病上架每次兑换必失败）；与 MA-069/MA-085 同类 | ZCode R12批3 / 2026-11-03 | |
| MA-097 | P3 | fixed（解除补记 BLACKLIST_REMOVE） | `DELETE /ops/admin/risk/blacklist/{userId}` | 黑名单解除零留痕（add 有 BLACKLIST_ADD 事件含 by，remove 完全无痕迹）——被收买账号可无声解除作弊者；一行改动级 | ZCode R12批3 / 2026-11-03 | |
| MA-098 | P3 | fixed（增长域 8 写端点全留痕） | 增长域运营写（MemberLevelAdmin/PointsRedeem/UserRecall/Notification/SkuDelistReview 6 服务 9 端点） | 审计整体缺失（grep 零 appendLog），同批 OpsRisk/DeviceOps/AdCampaign/MemberFinance 全有——MA-082 同构；等级规则叠加价格+积分双资金乘数。修法：`MEMBER_LEVEL_UPSERT/STATUS`、`POINTS_REDEEM_UPSERT/STATUS`、`SKU_DELIST_REVIEW_RUN/DECIDE`、`NOTIFY_SEND/UPDATE/DELETE` 九类 appendLog + 控制器透传 operatorId；钉测 4 处 verify | ZCode R12批3 / 2026-11-03 | |
| MA-099 | P3 | open | `POST /ops/admin/growth/user-recall` | 一键召回无顺序重放幂等：锁只防并发不防重放，双击=同批用户每人多发一张券+通知（maxIssueCount 只限总量）；对照订单积分有 existsByMemberAndSource 唯一索引幂等 | ZCode R12批3 / 2026-11-03 | |

| MA-100 | P2 | fixed（限幅+留痕） | `PUT /ops/admin/line-routes/{routeId}/members` | 线路成员佣金参数（rateBps/perUnitCents/basis）无界零校验直落且零审计，LineCommissionJob 按 bps 计佣直接 creditIfAbsent 入补货员可提现钱包（bps=1000000=100倍GMV）；同族第三例（MA-069 线长佣金/MA-090 机器账单分成），G2-13 已给 LineManager 落了 before/after 审计先例，本端点漏网 | ZCode R12批4 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-101 | P2 | fixed（409 + V435 唯一索引） | `POST /ops/admin/external-import/batches/{id}/commit` | 外部导入同渠道同账期可多批次重复 commit（只挡同批次重提交，无 channel+period 闸，V347 无部分唯一索引），月结 flowBySku 无条件 sum ⇒ 盘点/外调口径翻倍；COMMITTED 批次不可删，错数据无补救只能三倍重导 | ZCode R12批4 / 2026-11-03 | |
| MA-102 | P3 | fixed（留痕已接；调用点断言待补） | `POST /ops/admin/warehouse/transfers/{id}/cancel` | 调拨取消零审计零操作者（同单 create/ship/receive 三兄弟全有 WH_TRANSFER_* 审计，表亦无 cancelled_by）；状态机仅 DRAFT，改动零风险 | ZCode R12批4 / 2026-11-03 | |
| MA-103 | P3 | fixed（行锁读；真并发 IT 待补） | 月结差异处置 + 效期建补货任务 | `POST /close-sheets/{id}/lines/{lineId}/disposition` 与 `POST /expiry/alerts/{taskId}/create-replenishment` 先读后写无锁无版本：并发双击可双挂索赔台账（两条 inventory_write_off）或双建路线任务；同域标准是锁+FOR UPDATE，本面漏配 | ZCode R12批4 / 2026-11-03 | |
| MA-104 | P3 | open | `PUT /ops/admin/inventory` | 直写柜机库存汇总零校验（无 @Valid，DTO 零注解）：负数 quantity 污染短缺计算、空 deviceId/skuId 造主键残行；lot 账本设备已被 sync 挡住，非账本老设备暴露 | ZCode R12批4 / 2026-11-03 | |
| MA-105 | P3 | fixed（收窄注解） | `POST /ops/admin/purchase-orders/{id}/review` | 注解 OR(edit, finance:view) 是死分支——服务端只认 ops:procurement:edit ⇒ 持 finance:view 过注解门后必 403（MA-070 双轨同族，fail-closed 无安全洞但误导权限配置者） | ZCode R12批4 / 2026-11-03 | |
| MA-106 | P3 | open | `POST /ops/admin/warehouse/transfers/{id}/receive` | 收货登记未知/缺 lineId 的行被静默按「全部到齐」入账（reported map miss → 按发运量全额入库）——在途损耗黑洞恰是 V314 要堵的场景；应校验 lineId 全部 ∈ 本单行 | ZCode R12批4 / 2026-11-03 | |
| MA-107 | P3 | open | `POST /ops/admin/slot-templates/{id}/apply/{deviceId}` | 套用模板绕过「开门柜无货道」写守卫（DeviceSlotService 同类入口都有 assertNotDoorCabinet，注释明说 fail-closed，此处漏配）；目标设备不存在时 FK 500 而非 400 | ZCode R12批4 / 2026-11-03 | |
| MA-108 | P3 | fixed（幽灵线长 400） | `POST/PUT /ops/admin/line-routes` | create 缺 routeName/managerId → PG NOT NULL 违约 500（应 400）；update 的 managerId 不校验存在性，幽灵线长进 monthlySummary 财务聚合 | ZCode R12批4 / 2026-11-03 | |
| MA-109 | P3 | open | 「入库不落无主仓」闸门两旁路 | P2-8 治理闸只在 inbound/receivePurchaseStock/returnPurchaseStock 三处；binStockChange 原语被货位入库与调拨收货复用且不设闸，显式传无主仓照样进（缓解：ops:warehouse:edit 门内+可调拨救回） | ZCode R12批4 / 2026-11-03 | |
| MA-110 | P3 | open | 维修工单/异常结案列宽链断裂 | repair_ticket.remark(512)/event.remark(255) 双落列，256~512 字关单必失败；resolveWithRepair 三级列宽 detail(1000)→remark(512)→event(255) 拼接后超限（MA-087 同族新点位） | ZCode R12批4 / 2026-11-03 | |
| MA-111 | P3 | fixed（部分：改窄+留痕，钉测待补） | `DELETE /ops/feedback/{id}` | 用户反馈查看权限即可物理硬删且零审计（reply 反而要求严格 :reply 码；权限字典缺 delete 码）；无痕删用户输入 | ZCode R12批4 / 2026-11-03 | |
| MA-112 | P3 | open | `POST /ops/admin/exceptions/{id}/retry` | 幂等 marker 格式错位：Controller 查英文 `idempotencyKey=`、Service 写中文「幂等键：」⇒ alreadyProcessed 恒 false，幂等承诺失效（同文件 manualResolve 用英文自洽反证非设计；下层 409 兜底故 P3） | ZCode R12批4 / 2026-11-03 | |
| MA-113 | P3 | open | `POST /ops/admin/repair-tickets/batch-assign` | `{"assignee": null}` 经 String.valueOf 变字面量 "null" 入库；ticketIds 非数字 NumberFormatException→500（应 400） | ZCode R12批4 / 2026-11-03 | |
| MA-114 | P3 | open | @Size 家族补点（批4b） | 外部导入 personOverride 无 @Size(列60)；维修单 title(128)/remark(512) 无 @Size——并入 MA-087/110 批次修 | ZCode R12批4 / 2026-11-03 | |

| MA-115 | P2 | fixed（钉测 2 例） | `POST /ops/admin/ota/releases` + `/{id}/unpublish` | OTA 固件发布/下架零审计零归因：operatorId 传而不用（OtaService.java:69-70 收下即弃）、表无列、连 log.info 都没有——推错固件全车队瘫痪的最高危动作出事无法回答「谁推的哪个包」；主会话复核 grep OtaService 全文 0 处留痕 | ZCode R12批5 / 2026-11-03 | rounds/R12-write-surface.md |
| MA-116 | P2 | fixed（归因+追加） | `POST /ops/admin/consistency/{recordId}/fix` | 数据一致性修复零归因 + 修复结果覆写原始不一致描述（error_message 被覆盖=「当初为什么不一致」证据被抹）；fix 链直改订单头金额/清券/触发真实退款；类内 logChange 脚手架全仓零调用（出生即死）；修错了无法追责 | ZCode R12批5 / 2026-11-03 | |
| MA-117 | P3 | fixed（五服务 + shelf 全留痕） | 将邑运营写面 ~30 端点 | 审计留痕整体缺失（Onboarding/ClassMapping/ModelSync/Gather/SkuJiangyiLink 五服务 0 appendLog；含租户级 setTenantDomain 厂商云写、识别映射对位锚改写、deleteInJiangyi 不可逆删除）；同域 JiangyiDeviceOpsService 六处范本在，借它回补。修法：`JIANGYI_CLASS_MAPPING_*`、`JIANGYI_SKU_LINK_*`、`JIANGYI_GATHER_*`、`JIANGYI_TRAINING_*`、`JIANGYI_MODEL_PREGENERATE/ACTIVATE/PUSH` + 商户采集入口透传 userId；内部接口/回调按系统 null；三处 verify 钉测 | ZCode R12批5 / 2026-11-03 | |
| MA-118 | P3 | fixed（部分：非整数 400） | `POST /ops/admin/device-rental/deposits` | 原始 Map 数值参数静默吞错：monthlyRefundCents 非数字被 catch 后静默 null=「无月退」、contractYears 落默认 5——押金月退链（真扣真退商户余额）关键参数静默缩水无 400 反馈（与 MA-094 裸 parse 是同族两端，一个炸一个吞） | ZCode R12批5 / 2026-11-03 | |
| MA-119 | P3 | fixed（钉测 1 例） | 设备 MQTT/HTTP 凭据签发 | 不校验 device_info 存在性：12 位数字手滑一位即签出孤儿活跃凭据（明文 secret 回显一次）；同场景将邑入驻有现成闸（registerForDevice 防孤儿），凭据签发漏配 | ZCode R12批5 / 2026-11-03 | |
| MA-120 | P3 | open | `POST /internal/v1/devices/{id}/heartbeat` | 心跳环境读数零界：NaN/Infinity→BigDecimal.valueOf 抛异常→心跳 500（且在 presence 更新之后）；1e8 量级直落污染环境看板；同源 signal 0-4 静默丢、OTA pct clamp 先例在，本条漏网 | ZCode R12批5 / 2026-11-03 | |
| MA-121 | P3 | open | JiangyiInternal 遗留入口 | /onboarding/register 缺「柜机档案存在」闸（运营面 registerForDevice 有，internal 直连底层无）；/class-mappings upsert 收自由串 status/source（"active" 小写成死行）；grep 证实无代码调用方（运维脚本入口）+fleet key 保护故 P3 | ZCode R12批5 / 2026-11-03 | |
| MA-122 | P3 | open | `POST/PUT /ops/announcements` | 公告 create/update 裸 Map 零校验：title>256/priority 9+字符→22001 500；targetScope 拼错（"CONSUER"）静默落库发布成三端不可见公告（MA-078/084/087 家族新点位） | ZCode R12批5 / 2026-11-03 | |
| MA-123 | P3 | fixed（三端点留痕 + 重复发布幂等） | 公告 update/publish/archive | 零操作者归因零审计（operator_id 只在 create 落库，V220 加 FK 证明留痕是既定口径）；publish 无状态机：ARCHIVED 可复活、重复发布刷新 publishAt 重排消费端 | ZCode R12批5 / 2026-11-03 | |
| MA-124 | P3 | fixed（不重放+退款回执） | `POST /ops/admin/consistency/{id}/fix` 重放 | 无 status 闸：FIXED 记录可重放，fixInventoryMismatch 用记录快照旧库存值回写覆盖当前（脏窗口）；fixPaymentAmount 重放被 M02 幂等键吞掉却回「已退多收 X 分」假成功（refundOrder 返回值未检查，javadoc 明说必须检测） | ZCode R12批5 / 2026-11-03 | |
| MA-125 | P3 | open | `POST /ops/admin/ota/releases` | 入参缺口：重复版本撞唯一索引 500 而非 409；channel 自由串拼错（"stabel"）=发布静默不可见；grayPercent>100 无钳制；presignTtlSeconds 无上界（可配一年有效预签名 URL）；降级推送设备侧有兜底判部分刻意 | ZCode R12批5 / 2026-11-03 | |

| MA-126 | P3 | open | 提现等全仓 `@Positive` 字段的 400 文案 | R17 活栈实测：amountCents=0/-500 都回「amountCents不能为空」——根因 `@Positive` 默认消息 "must be greater than 0"（严格大于）不匹配 ApiMessages 的 RANGE_MIN_PATTERN（只匹配 "greater than or equal to" 形式）落兜底误报；2026-10-23 生产实证修过 @Min/@Max 同款（代码注释在位），漏了 @Positive 形式，误导性完全相同 | ZCode R17批1 / 2026-11-03 | rounds/R17-runtime-flows.md |

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
| MA-137 | P3 | open | shared-types 手写 OrderReadModel/OrderSummary | 缺 activityDiscountCents/grossAmountCents（生成物有）⇒ consumer 订单列表要补活动标签时 TS 无字段可用（MA-127 列表漏活动的直接诱因）；建议补字段或切 OpenApi 别名+加「手写接口 vs 生成物 diff」门禁 | ZCode R16 / 2026-11-03 | |
| MA-138 | P3 | open | check-no-inline-money-math 门禁盲区 | 只扫 views/components 的 .vue，composables/*.ts 残留 3 处手算（useWarehouseEntityDialogs.ts:102,226,266）不受棘轮约束；DIRS 加 composables 收编 | ZCode R16 / 2026-11-03 | |
| MA-139 | P3 | open | 提现 feeCents 空态两口径 | merchant「—（未结算/免收可区分）」vs admin `feeCents || 0` 显示「¥0.00」——null 被抹平后「未结算」与「免收」在 admin 不可区分；admin 费用列改三态对齐 | ZCode R16 / 2026-11-03 | |
| MA-140 | P3 | open | wallet_ref_type 缺 SPLIT_REV | 后端真实写入（RevenueSplitService:473）但基线/DB 均无 ⇒ admin 显示「关联/未知」vs mp 本地「分账冲正」同流水两词 | ZCode R16 / 2026-11-03 | |
| MA-141 | P3 | open | wallet_entry_type.SPLIT_REVERSE 措辞漂移 | DB/迁移「分账退回」vs shared 基线「分账冲正」——runtime 加载状态不同则显示不同词（低频窗口）；择一统一（DB 侧改需幂等 UPDATE，参照 V415 范式） | ZCode R16 / 2026-11-03 | |
| MA-142 | P3 | open | 站内信 bizType 三端三词 | DISPUTE：consumer「售后」/merchant「争议」/字典「争议」；活动 vs 用户召回混用——分受众话术可理解但未登记为「有意差异」，对齐时无据可查；至少 shared-dict 注释钉住 | ZCode R16 / 2026-11-03 | |
| MA-143 | P3 | open | exception_type 基线缺 15 值 | DB 33 值 vs 基线 19 值（G7 补集+将邑码只落 DB）；admin 靠 runtime 正常，runtime 未加载首屏 ExceptionListView 落「未知」；双 mp 不直接渲染故 P3 | ZCode R16 / 2026-11-03 | |
| MA-144 | P3 | open | 反向缺：DB 有基线无的 4 类型 | sku_operation_scope/ops_fee_type/device_fault_code/risk_disposition_status——runtime 未加载时 SkuListView 首屏闪「未知」；违反 shared-dict 契约后半句「then seed here」 | ZCode R16 / 2026-11-03 | |
| MA-145 | P3 | open | 基线独有 10 类型从未种子 | balance_refund_status/user_role/wallet_ref_type/wallet_ledger_type/consistency_diag_code/merchant_settlement_bill_status/close_sheet_*/expiry_urgency——字典管理不可见、runtime 常态性缺类型（结构性欠账，暂无可见故障） | ZCode R16 / 2026-11-03 | |
| MA-146 | P3 | open | 死字典值与局部内联漂移 | repair_ticket_status.CLOSED 状态机写不出（幽灵选项）；enable_status 基线冗余 DRAFT/STOPPED；AdAssetsView 内联三元与同页字典混用；MerchantOnboardingView PAYSCORE→支付分 vs 字典「微信支付分」；consumer feedback 未知状态兜底「处理中」掩盖 | ZCode R16 / 2026-11-03 | |

| MA-147 | P2 | fixed（显式清列+矩阵真库断言，门禁豁免已撤销） | 消费端全额退 `POST /orders/{id}/refund`（无 lines） | 全额退**不返还已核销券**：waive 路径（SettlementWaiveRefundService.waiveAndRefund）全文 0 处券处理，券返还（restoreUsedToUnused+活动预算释放）只接在比例退路径（CouponService.settleAfterPartialRefund:573-583）；R17 矩阵 E2E 实测全额退后 user_coupon 仍 USED（MemberOrderChain 同库可见）；券面额随退款被吞且活动预算不释放；类注释无「刻意不返还」声明 | ZCode R17批2 / 2026-11-03 | rounds/R17-runtime-flows.md |

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
- **MA-129 拍板选 ②**（读模型新增「剩余毛额」列 + consumer 拆解块加一行「剩余合计」，
  **不动资金算式**）——选 ① 要回退两处等比缩并重新回归「预估==写侧」，风险直接把展示问题变成资损面。
  状态仍是 open：本条含读模型字段新增，须与 MA-127/128 同批做 **shared-types regen**（openapi 形状变了），
  不在没有 regen 的情况下先动后端。


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

- **仍欠钉测（显式登记）**：MA-083/096/111 + 前批 088/093/118 共 6 条只有代码级证据。
  下批补「能失败」的用例：083 越界 400、096 负积分被拒且不改余额、111 查看码 403 + 删后审计可读。
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
