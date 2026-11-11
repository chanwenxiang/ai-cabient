# 四端审计 R11 报告（2026-11-11）

> **基线**：HEAD `cc9e0852`（R10-A23 配对重烤之后）。**方法**：4 路并行只读审计（trade-service 后端 / admin-vue / merchant-mp / consumer-mp），全部先对齐 `AUDIT_FOUR_END_OVERNIGHT_FINAL_2026-11-11.md` + R3/R4 报告的已修/豁免清单（≈95 条）后仅报新增。全程未改任何源文件。
> **总账**：**新发现 25 条 = P1×1 + P2×7 + P3×17**；P0 为零。

---

## 一、发现总览

| 端 | P1 | P2 | P3 | 主打根因 |
|---|---|---|---|---|
| trade-service 后端 | 0 | 0 | 2 | 清列家族漏网 1 处 + 迁移门禁「只查新增」盲区 |
| admin-vue | 0 | 2 | 7 | seq 守卫「只挂 catch 半边」/ 修复点上游漏扫 |
| merchant-mp | 0 | 3 | 7 | 新页面从未接过代际守卫 + A-R3-1 未跨端同批改 |
| consumer-mp | **1** | 0 | 3 | **TDZ 第三处**（通宵报告预警命中） |
| **合计** | **1** | **7** | **19** | 「守卫只修一半」科占多数（≈12 条） |

**关键定性**：本轮 P1/P2 集中指向同一科——**守卫类修复存在系统性「修一半」**：① seq 守卫只挂 catch/finally、成功赋值不判（admin 4 处）；② 同文件修复点只盖了一个函数、上游/下游加载器漏扫（VisionMappingView、device-stocktake vs warehouse-stocktake）；③ 跨端契约只改一端（A-R3-1 admin 修了、mp ops-config 没修）——恰违反通宵报告新立的「跨端契约两端同批改」约定。

---

## 二、P1（1 条，建议立即修）

### C-N1 [consumer-mp] order-detail.vue `reloadSeq` TDZ——H5 订单详情页整体挂死
- **位置**：`clients/consumer-mp/src/pages/order-detail/order-detail.vue`：:516/:520 先注册 onLoad/onShow → bootstrap(:482) → IIFE(:504) 同步调 `reload()` → :602 `++reloadSeq` 撞 :594 的 `let reloadSeq = 0;`（声明在后）→ ReferenceError 无 catch → `loading=true` 永久骨架屏。
- **触发**：H5 端生命周期可在 setup 期同步触发（e2e-h5 TC-QUAL-001 对 messages.vue 已实证该机制）。mp-weixin 不受影响。
- **与已修区别**：R10-1/R10-2 是 onShow / immediate-watch 两把伞，本条属 **onLoad/onShow 注册位次**第三类；且该守卫是 9b957f19（11-10 09:14，晚于 messages TDZ 修复）新引入时造的位次问题；e2e-h5 未深访 order-detail 故未点名。
- **修法**：照 9e9a39b8 同款把 `let reloadSeq = 0;` 上移到 :516 之前；补「H5 深链直达 order-detail」e2e 用例。

---

## 三、P2（7 条）

### A-N1 [admin] VisionMappingView：loadDeployments/loadReviewStatus 无 seq/设备守卫（A-R3-2 的上游前提被打破）
- `clients/admin-vue/src/views/vision/VisionMappingView.vue:1154-1181` 两加载器直接赋值，无守卫（对照同文件 :1036-1068 loadVendorNames 已修）。
- **伤害升级**：loadVendorNames 的守卫读 `deployments.value`——当它是 A 柜陈旧数据时，用 **A 的 classesTextUrl 配 B 的 deviceId** 发解码请求，requestedDeviceId===deviceId 守卫判不出。A 的 reviewStatus 覆盖 B 还会错触发「已复核收起表单」。映射复核是结算闸口核对依据。
- **修法**：两函数照 :1043 加 requestedDeviceId 捕获 + ++seq 双判；loadVendorNames 对 `dep` 来源再判归属。

### A-N2 [admin] seq 守卫只挂 catch/finally、成功赋值不判 isCurrent——4 处列表旧响应可回写
- `FundBillView.vue:570-572`（loadLedger）/ `LineManagerView.vue:1184-1185`（loadManagers）/:1207-1209（loadWithdraws）/ `OrgSitesView.vue:1300-1311`（loadBills）。
- **触发**：翻页/改筛选后旧响应晚到回写列表+total+清勾选；`exportLedgerCsv` 可导出陈旧明细。
- 全仓该形态共 14 处，其余 10 处为幂等重取/一次性加载降 P3。**修法**：四处成功赋值前补 `if (!loadSeq.isCurrent(seq,'<channel>')) return;`（照 useCrudTable.load:162）。

### M-N1 [merchant-mp] device-stocktake：loadDevice 完全无代际守卫——换柜竞态跨柜写台账
- `pages/device-stocktake/device-stocktake.vue:138-188`；scanDevice(:123-133) 无 loading 门闩。A 柜在途时扫 B 柜 → A 的迟到续体把 isDoorCabinet/deviceName/goods/goodsInputs 写成 A 柜数据；A 非将邑柜还会误弹「不支持在线盘点」+ 清空 goods。**提交的是按 A 柜 SKU 组织的行打到 B 柜门态**——错账基础数据。
- **区别**：R5-F2 只修了 warehouse-stocktake 的 detailSeq；同批新增的门禁式盘点页从未接过 seq。
- **修法**：照 warehouse-stocktake detailSeq 形态 + scanDevice 在途拒绝。

### M-N2 [merchant-mp] useReplenishmentDetail：openTask 无代际——A 任务明细写进 B 任务详情
- `composables/useReplenishmentDetail.ts:134-221`：refreshSelectedTask 与 loadTaskDetailResources（4 个 await 后 5 组赋值）全部无守卫。弱网下 A 卡 → B 卡切换，B 详情显示 A 的补货行/台账上限/可补量/凭证，selected 还会被 A 改回（sheet 提交目标错位）。
- **区别**：历轮只扫页面层，该 composable 的 await 赋值链未被任何一轮扫过。
- **修法**：composable 加模块级 openTaskSeq，prepareTaskDetailSheet ++seq + 捕获 taskId，各赋值前比对。

### M-N3 [merchant-mp] ops-config：loadConfig/save 响应无商户+seq 守卫——跨商户配置写穿
- `pages/ops-config/ops-config.vue:77/:116` 赋值前不比对请求时商户；:140 picker 在 saving/loading 期间不禁用。B→C 快速两跳，B 的迟到 GET 写 cfg，save() 把 B 的配置写进 C。
- **区别**：A-R3-1 只落了 admin 端；mp 端当时只修「切换窗口同步清空」半边——恰违反「跨端契约两端同批改」新约定。
- **修法**：照 A-R3-1 修法（捕获 merchantId + 赋值前比对；或 saving/loading 禁用 picker）。

---

## 四、P3（17 条，按端）

### 后端（2）
| # | 条目 | 位置 | 要点/修法 |
|---|---|---|---|
| B-N1 | 分账刷新成功后 failure_reason 残留 | `WeChatProfitSharingService.java:495-521` applyRemoteState FINISHED/PROCESSING 分支 setFailureReason(null) → persistSplit=updateById 跳 null ⇒ FAILED 文案与 WECHAT_SUBMITTED 状态自相矛盾。同文件其余 6 处清列点全有 wrapper，**唯此第 7 处从未被任何轮点名** | persistSplit 对 null 补 lambdaUpdate().set(...,null)（照 :385-395 模板）+ 钉测 |
| B-N2 | V451/V452/V453 缺 MIGRATION_KIND（backfill）；V452 另缺 COMPETITOR_REF | `db/migration/V451__*.sql:12-15` / `V452__*.sql:2-4` / `V453__*.sql:3` | ⚠️ 不可直改已应用迁移（铁律 50 checksum）。正解：两个门禁（check-flyway-seed-separation / check-competitor-benchmark）加全量兜底模式 + 存量白名单豁免登记 |
| — | 观察项（不立案） | `BalanceRefundService:554/:581` 事务② REQUIRED JOIN 外层 ⇒「锁→事务→解锁」连接占用优化失效；行锁仍保串行无资损 | 登记 |

### admin（7）
| # | 条目 | 位置 | 修法 |
|---|---|---|---|
| A-N3 | 分账明细 CSV 金额带 ¥（A27 漏网页） | `MerchantSplitsView.vue:1161`（exportSplits） | 去 ¥ 直接 money() |
| A-N4 | 错误页「返回工作台」被 canAccessPath 拦死（死按钮） | `FinanceView.vue:11` goPath('/') 不在 menu.ts | 改 goHome()/goPath('/dashboard') |
| A-N5 | ExternalChannelImportTab onMounted 首个 await 无 catch——loadTemplates 失败断链后续三加载全不执行（全 tab 假空态） | `ExternalChannelImportTab.vue:166-175/:443-446` | try/catch 或并行化 |
| A-N6 | LineRoutesView loadChief 裸调用静默失败 | `LineRoutesView.vue:426-430/:653` | reportSoftFail |
| A-N7 | 公告「创建+发布」复合操作部分成功误报 → 重试重复建公告（create 无幂等键） | `AnnouncementsView.vue:514-531` | 失败文案区分「已创建 ID xxx 但发布失败」或拆两步 |
| A-N8 | auth.ts 401 判据仍为消息正则（软形态最后残留，方向 fail-closed 暴露窗口≈0） | `stores/auth.ts:138/:156` | 改 httpStatus(e)===401 |
| A-N9 | FundBillView 通道费 CSV 列带「(实结)/(估)」后缀不可求和——**待拍板**：可能是刻意口径区分 | `FundBillView.vue:490-491` | 建议「金额」「口径」拆两列 |

### merchant-mp（4）
| # | 条目 | 位置 | 修法 |
|---|---|---|---|
| M-N4 | alerts deviceNames 赋值在 seq 判定之前（:334 早于 :335） | `alerts.vue` | 赋值下移 |
| M-N5 | pricing devices 缓存赋值在 seq 判定之前 | `pricing.vue:291/:293` | 同上 |
| M-N6 | purchase suppliers 回填在 listSeq 判定之前 | `purchase.vue:365/:367` | 同上 |
| M-N7 | tally-records load() 自身无 seq（上轮只给 loadMore 加了） | `tally-records.vue:67-98` | load 自加代际 |
| M-N8 | messages load 无 seq——旧响应回滚用户刚点的「已读」（R10-1 删旧 loadSeq 留下的守卫真空） | `messages.vue:175-186/:202-207` | 补 listSeq 或乐观置位 |
| M-N9 | messages navigateWallet 权限兜底无 meFromServer 分流（B-新3 点击时点最后残留） | `messages.vue:239-244` | !meFromServer 先 refreshMe 重试再判 |
| M-N10 | device-stocktake gatherStatus 失败误报「该柜不支持在线盘点」（把查询不到报成柜型不支持） | `device-stocktake.vue:149-152` | 失败提示网络异常可重试，不落 bound=false 终态 |

### consumer-mp（3）
| # | 条目 | 位置 | 修法 |
|---|---|---|---|
| C-N2 | device-ad-banner load() 无 seq + 失败 reset() 清空已展示内容——**R3 已括注「同形态存量」但从未修**（非重复上报） | `device-ad-banner.vue:124-145/:142-144` | 照 marketing-ad-banner:62-85 补 loadSeq（声明上移）+ 失败不清空 |
| C-N3 | coupons load() 无 seq——tab 高亮与列表竞态错位（注释自证关心此事但只修了失败分支） | `coupons.vue:155/:157-170` | 捕获请求时 activeTab，赋值前比对 |
| C-N4 | dispute/detail reload() 无 seq——onShow/8s 轮询/下拉三路并发，窗口一个 tick 自愈，可补 seq 或登记豁免 | `dispute/detail.vue:403-419` | 补 seq 或豁免登记 |

---

## 五、确认无残留的家族（4 路排查面汇总）

- **后端**：窗口截断（38 处逐一核上游全量取数）、幂等键/回放闸（采购/提现/补货三闸在位）、REQUIRES_NEW 留痕、foreach 缺 script（全仓仅 2 文件均包裹）、join/split 对称（34 处）、深链/权限码（12 个门禁只读实跑全绿）、TOCTOU/终态闸；449 迁移无同号。
- **admin**：loading 复位 140 处全必达、保存响应双判唯一在位、已修 seq 点逐行核验、CSV 单点转义+公式注入中和在位、深链同源全登记、**产物配对 HEAD 干净（Safely 包装已落产物层）**、裸色棘轮恰等基线、双提示复合误导仅 A-N7。
- **merchant-mp**：TDZ 全扫无第三处（唯一 immediate watch 位次正确）、meFromServer 12 页全有前置分流、终态数组消费钉无变异、下拉刷新 24/24、热区棘轮 0/278 等基线、裸色恰等基线、深链全扫 0 失配、幂等键四处页面级容器在位。
- **consumer-mp**：TDZ/immediate 全扫唯一残留=C-N1、C-R3-1 结构化闭环在位、支付/充值链路（键归属/取消清 pending/mock fail-closed/补缴语义诚实）全在位、下拉刷新/热区/裸色门禁实跑全绿、深链白名单在位、订单终态全读 status。

**未验边界（与历轮口径一致）**：未跑 mvn verify / 真 Docker IT / 真机弱网时序复现；N1-N3 类竞态为静态推演（代码路径确定）。

---

## 六、修复建议排序

1. **立即**：C-N1（P1，H5 订单详情挂死）——一行上移 + e2e 用例。
2. **本批 P2**：M-N1 / M-N2 / M-N3（错账与跨商户写穿）→ A-N1 / A-N2 → 建议同轮把「seq 守卫成功路径赋值必须判 isCurrent」立成静态门禁（14 处该形态机器可扫）。
3. **P3 随批**：B-N1 + 门禁全量兜底（B-N2）+ CSV 漏网（A-N3/A-N9 待拍板）+ 其余。
4. **流程**：本轮 P1/P2 ≈12 条同科「守卫只修一半」——建议把 CODE_FIX_CHECKLIST 的「同文件同链全扫」升级为机器判据（扫描守卫函数的 await 赋值点是否全部在守卫判定之后）。

*报告落盘：2026-11-11 上午，基线 HEAD=cc9e0852，4 路子审独立完成、主审汇总。*

---

## 七、主审复核结论（2026-11-11 上午，逐条亲验）

**抽样范围**：P1 全部 1 条 + P2 全部 7 条 + P3 抽样 5 条（B-N1/A-N3/A-N7/M-N8/C-N2/M-N10 中 6 项），共 13 条亲读当前 HEAD 源码。

| 条 | 判定 | 关键证据 |
|---|---|---|
| C-N1 (P1) | ✅ **成立** | onLoad/onShow 在 :516/:520，`let reloadSeq = 0` 在 :594——声明后于注册，H5 同步触发即 TDZ，与 R10-1 同族（注册位次第三类） |
| A-N1 | ✅ 成立 | loadDeployments/loadReviewStatus 两函数体无任何 seq/deviceId 守卫（grep 零命中） |
| A-N2 ×4 | ✅ 成立 | FundBill loadLedger / LineManager loadManagers+loadWithdraws / OrgSites loadBills 均为「catch/finally 判 isCurrent、成功赋值裸奔」形态，实证如报告所引 |
| M-N1 | ✅ 成立 | loadDevice 全函数无 seq；isDoorCabinet/goods/goodsInputs 直接赋值 |
| M-N2 | ✅ 成立 | useReplenishmentDetail 无任何代际变量；Promise.all 后 5 组赋值裸奔；:138 selected 覆盖亦无守卫 |
| M-N3 | ✅ 成立（附精确化） | onMerchantChange 的 `cfg=null` 只挡**切换瞬间**——A 的迟到 GET 响应抵达后 cfg 仍被写成 A 的配置（loadConfig await 后不比对商户），save() 即写穿。null 守卫挡不住「迟到响应」这一支 |
| B-N1 (P3) | ✅ 成立 | applyRemoteState FINISHED/PROCESSING/CLOSED-无因三分支 setFailureReason(null) 后走 persistSplit=updateById 跳 null；对照 :385-395 markLedgerOnly 有 M01 wrapper，唯 applyRemoteState 缺 |
| A-N3 (P3) | ✅ 成立 | exportSplits 行 `¥${money(row.merchantCents)}`——CSV 带 ¥ 不可求和，A27 第 8 页漏网 |
| A-N7 (P3) | ✅ 成立 | create→publish 复合：publish 失败 catch 只报「发布失败」，公告已建无幂等键，重试必重复建 |
| M-N10 (P3) | ✅ 成立 | `catch { bound=false }` 把查询失败/无权限/非将邑三态并成一态，弱网误报「不支持在线盘点」 |
| C-N2 (P3) | ✅ 成立 | device-ad-banner load() 无 loadSeq（grep 零命中），与 marketing-ad-banner 修复形态不对称 |
| **M-N8** | ~~证伪~~ → ✅ **成立（复核订正 2026-11-11）** | 首验核错文件（consumer 端 messages.vue——该端确有守卫）；M-N8 报的是 **merchant 端** `clients/merchant-mp/src/pages/messages/messages.vue`。补核实文：load()（:174-186）**零守卫**——`list.value = await merchantApi.notifications(100)` 直接整表赋值，无 seq、无快照比对；markNotificationReadIfNeeded（:200-211）乐观置位 `m.read = true`。回滚场景成立：点「已读」乐观置位 → onShow/下拉触发 load() → 迟到响应整表覆盖 → 已读状态被打回。行号 :175-186/:202-207 与实文吻合 |
| — | **复核方法订正** | 首验只 grep 了 consumer 端同名文件。教训：**M 系列编号一律以 merchant-mp 为准**，同名页面两端的守卫状态互不相同，跨端核对必须两端都读 |

**判定汇总（订正后）**：亲验 13 条 = **13 全部成立**（M-N8 首验证伪系核错端，merchant 端补核后坐实）。未逐条亲验的其余 P3（12 条）按两路证据质量与抽验命中率暂记「推定成立」，随修复批逐条过码时再确认。

**两项待拍板的主审推荐**：
1. **P1+P2 批 + 静态门禁：建议立即修**。C-N1 是 H5 订单详情整页挂死（TDZ 家族 CI 已三次实证）；M-N1/M-N3 是跨柜/跨商户错写数据面。静态门禁建议收窄形态以控误报：仅扫「同函数内 `loadSeq.begin(seq)` + catch 判 isCurrent、但 try 内存在 ref 赋值且其前无 isCurrent 判定」的函数（如 loadLedger 形态），并按 R9 经验先抹注释、配三态注入。
2. **A-N9 FundBill「(实结)/(估)」后缀**：口径区分本身**是有意的**（channelFeeSource=ACTUAL 实结 / 估算，R2 时代设计），但作为 CSV 同格后缀破坏可求和性。推荐**拆两列**：「通道费(元)」纯数字 + 「通道费口径」= 实结/估算——口径信息保留、Excel 可求和，与其它 CSV 页修法同形。
