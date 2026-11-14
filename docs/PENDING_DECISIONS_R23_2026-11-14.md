# 需拍板清单（R23 §四要求的正式清单）—— 2026-11-14

> **定位**：R23 计划 §四第 3 条「不把拍板题当缺陷……列『需拍板』清单」——R23 计划当时只写了要列清单，清单本身不存在（此前散落在各审计报告的「待拍板」句 + `.workbuddy` 日志里，换会话即丢）。本文档把**当前仍开着的拍板面**收拢成唯一权威清单；R23 各路审计发现新拍板项时**追加到本文档**，不得只在报告里留一句。
>
> **前置事实**：`docs/PENDING_DECISIONS_5_2026-11-13.md` 的 5 条（①争议改单券不返还 / ②对账日跑删除重建 / ③分账回退幂等 / ④Ops 重试落补货会话 / ⑤device_sku_price 行锁粒度勘误）经 2026-11-14 用户复核确认**全部收口**，本文档不重复收录。
>
> **纪律**：拍板项不是缺陷（修的人不自己选口径）；R23 审计期间保持只读，拍板项允许挂着进 L4 现状评估；每条拍板后在此标注「✅ 已拍板 YYYY-MM-DD：结论 → 落点提交」。

---

## ① R22 §8.4「两侧皆 0 ⇒ MATCHED」语义 —— 🔴 最高优先（漏登记已久）

- **现状**：`ReconciliationService.java:226-227` 终态判定只比较三个量：
  `diffCents == 0 && unmatched == 0 && ledgerOnly.isEmpty() ? "MATCHED" : "MISMATCH"`
  ——不区分「核对过且相等」与「两侧都没有数据」。
- **实库取证**（R22 报告 :395）：`WECHAT` 13 次对账里 **9 次两侧全 0** 却记 `MATCHED`。
- **拍板问题**：「两侧皆 0 ⇒ MATCHED」是产品认可（当日确实无交易就是平的），还是必须区分「未核对」（如记 `EMPTY`/`NOT_CHECKED` 并进告警面）？
- **关联**：R22-8 的静默面（空账单 fail-fast）已修（382c0769）；本条只剩**语义口径**。
- **报告出处**：`AUDIT_FOUR_END_R22_2026-11-12.md` :316 / §8.4（:393「不是待定，是拍板项」）。

## ② R19-2 赠送冲回 —— 改退款资格语义

- **现状**：R19 发现赠送（免费券/赠送金）在退款场景的冲回语义未实现；11-12 修复核查报告明列「未做待拍板」。
- **拍板问题**：赠送部分是否参与退款资格/冲回？改的是退款资格语义，修的人不能自己定。
- **报告出处**：`AUDIT_FIX_QUALITY_VERIFICATION_2026-11-12.md` §七「未做待拍板」。

## ③ R19-5 dev 80 端口暴露 —— 断真机联调的取舍

- **现状**：dev 栈 80 端口暴露给真机联调用（花生壳隧道链路依赖）；收紧会断将邑真机联调。11-12 修复核查报告明列「未做待拍板」。
- **拍板问题**：真机联调期结束后是否收回？还是维持到生产化迁移完成？
- **报告出处**：同上。

## ④ R22-7 厂商待定 —— 重复 start 的设备侧行为

- **现状**：§7.15 双摄重复 `start` 是拒绝还是幂等返回，需厂商答复或真机验证。
- **附**：R22 报告 :421 的现场动作「**同柜连点两次 start**」真机双点验证也未做（需要真机在手的人执行）。
- **真机双点实验协议（2026-11-14 补写；服务侧不改码）**：拿到真机的人按下列三态判读；**厂商书面答复先到则以厂商为准**。在任一结论落地前，不碰协议代码、不预加幂等键或 409。

  | 第二次 start 的返回 | 判读 | 结论 |
  |---|---|---|
  | 拒绝/报错 | 设备侧有去重 | 现状安全，只需文案确认 |
  | 幂等返回同一 taskId | 设备幂等 | 重试安全，服务侧无需改 |
  | 返回**新** taskId | 并发双采集风险 | 服务侧须加幂等键或 409——**届时再拍板改法**（本表是判读标准，不是已定方案） |

- **状态**：仍是开着的拍板题；三态只给判读标准，不预设结论，也不代表实验已做。
- **报告出处**：`AUDIT_FOUR_END_R22_2026-11-12.md` §7.15 / :421。

## ⑤ OpsOta 审计双记 —— ✅ 已拍板 2026-11-14：维持双记、不动码

- **现状**（2026-11-14 精确化）：publish/unpublish **各**双记——
  `OtaService.java:120`（OTA_PUBLISH）/:143（OTA_UNPUBLISH）与
  `OpsOtaController.java:43`（OTA_RELEASE_PUBLISH）/:58（OTA_RELEASE_UNPUBLISH）写的是同一动作的审计。
- **门禁事实（2026-11-14 A/B 实证订正，改写原「技术上可行」一句）**：只改一个变量（把控制器两处 `appendLog(` 改名，隔离副本 `.tmp/gate-ab/{base,mut}`，不碰工作区）——未删时类级绿（可达 72/基线 72、登记 26）、端点级绿（455/240/215 对平）；删后**端点级仍绿**（service 链可达），**类级必红 2 条**（零归因未登记 + 可达 71<基线 72）。所以要绿只能降基线或放宽 1-hop 判据＝**拆 S9 防线**，换掉两条审计收益≈0。「合并到 service 单点记」不是零成本的可行方案。
- **拍板结论**：✅ 已拍板 2026-11-14（用户**采纳**复核结论）：维持双记、不动码；交 R23 L4 用产品视角复评。落点 = 本条判据锚点说明（纯文档、零代码），落点提交 = `b72a8d0b`。
- **判据锚点说明**：`OpsOtaController.java:43/:58` 是 `265e55ce`（11-13 15:38，S9 收口）**刻意加的**，注释写明「MA-115：OTA 是全仓最高危运维动作」；`OTA_PUBLISH/UNPUBLISH` 来自更早的 `7f7f5a1d`（MA-115）。这两行不是冗余，是类级 1-hop 判据的锚点。
- **定案依据勘误**：不以「`OTA_RELEASE_*` 零消费面」定案——`shared-dict` 的 `auditActionLabel` 对两组动作码都无字典项（一律显示「其他操作」），且该快查只扫源码，**`admin_audit_log` 里已有的 `OTA_RELEASE_*` 历史行未查**；删了会让同一动作在历史里留两个 action 值。真正依据是上面的门禁判据。
- **两处无的放矢（复核留痕）**：`OpsOtaControllerTest` 只有 `verify(listOta)`、**没有 appendLog 断言**（「适配测试」无对象）；`write-attribution-registry.json` 26 条里**没有** OpsOtaController 条目（「销双记条目」无对象，双记条目在本清单 ⑤ 与 `.workbuddy/memory/2026-11-14.md`）。
- **⚠️ 记录位置教训**：本条此前只写在 `.workbuddy/memory/2026-11-14.md`，未进 docs 台账——本文档即修正（ OpsOta 双记自此以本文档为准）。

## ⑥ AccountController 6 写端点 0 归因 —— ✅ 已拍板 2026-11-14：CONSUMER_WRITE 语义豁免

- **现状**：S9 端点级基线 `AccountController {endpoints:6, attributed:0}`；`AccountService.updateNickname` 等为自助改昵称类低风险写（源码注释「无越权面」）。
- **拍板结论**：✅ 已拍板 2026-11-14（用户点头**采纳**归类建议）：这 6 个端点归类 `CONSUMER_WRITE` 语义豁免（消费者自主操作，业务单据即流水；先例 `PaymentController` / `SessionController`），**不补** `appendLog`。落点 = 本条登记（纯文档、零代码），落点提交 = `b72a8d0b`。端点级 `215` 棘轮**不变**——215 是分类/登记维度的存量天花板，不因归类清零；整体收敛不排期，维持冻结（只许减）。
- **类级注册表不新增条目（订正原方案「26→27」）**：`write-attribution-registry.json` 的 `entries` 只登记**类级不可达**的写控制器；AccountController 自身无 `appendLog`，但注入的 `InvoiceService` 含真实审计调用点 ⇒ **1-hop 可达**。门禁正向 `98 个写控制器 / 可达 72 / 登记 26 / TBD 0` 且绿，正是因为它未登记（`evaluate()` 第 85 行「可达的无需登记」）。强行加条目构成误登记，且 `evaluate()` 的 `e.auditCapable === false` 条件看不见这种条目 ⇒ 既护不住也不报错。故 26 保持 26。
- **R23 L4 交待**：本条只解决这 6 个端点的口径；215 里剩下那块（见文末备忘的 134 条）才是「运营侧写端点漏网」镜头该重扫的面。

## ⑦ movePoint(2) 单源例外 —— ✅ 已拍板 2026-11-14：维持登记例外、不动码

- **现状**（2026-11-14 性质订正）：`common-core/UserBehaviorRowDto.java:34`
  `totalSpent.movePointRight(2).setScale(0, HALF_UP).longValue()` 是**元→分资金方向派生 + 静默四舍五入**（正是 `YuanFen.toCents` 拒 `scale>2` 要防的形态）；因 common-core 不能反向依赖 trade-service 的 `YuanFen`，登记为**已知单源例外**。`UserBehaviorAnalyticsService.java:194` 的 `movePointLeft(2)` 是分→元**显示**方向——两者勿混为一类。
- **实证订正（2026-11-14，替换原「输入来自 DB 聚合的 BigDecimal，风险低」）**：
  - **消费面活着**：`clients/admin-vue/src/views/growth/UserAnalysisView.vue` 有 **10 行**含 `totalSpentCents`（8 处取值/计算 + 1 类型声明 + 1 函数签名），不是死代码——「删派生字段让例外自然消失」这条路堵死。
  - **输入是精确恒等回环**：`u.totalRevenue` 是 long 分（`:112` 的 `toLong(row[2])`），`:194` 用 `BigDecimal.valueOf(u.totalRevenue).movePointLeft(2)` 转元，DTO 构造器再 `movePointRight(2)` 回分 ⇒ 精确两位小数的恒等回环，`setScale(0, HALF_UP)` 永不触发＝死防御路径。这比原描述更硬地支持风险≈0。
  - **下沉成本**：`check-yuan-to-cents.mjs:56` 的 `YUANFEN_FILE` 锚点写死 `services/trade-service/.../util/YuanFen.java`，下沉 common-core 要动锚点 + 全部 importer；R23 前动它＝扩大 L1 被审面换零收益。
- **拍板结论**：✅ 已拍板 2026-11-14（用户**采纳**复核建议）：维持登记例外、**不动码**（落点提交 = `b72a8d0b`）；`movePointEntries` 2 处在册不变。R23 L4 保留复评权，若不同意再排修复批。
- **关联**：`check-yuan-to-cents` movePoint(2) 棘轮在册 2 处。

## ⑧ S12 四键值域 —— ✅ 已拍板 2026-11-14（按建议值域开工）

- **拍板结论**：提现手续费率 0–1000 bps（老系统 6‰=60bps，含商户降档覆盖空间）/ `balance.refund.max_cents` 0–5,000,000 分（¥5万）/ `checkout.preauth_cents` 0–1,000,000 分（¥1万）/ 券折扣两族维持既有局部校验（`CouponService` discountPercent 1..99、`PromotionActivityValidator` discountBps 0..10000 开区间）并纳入门禁视野（SETTER_RE 加 `setDiscountBps|setDiscountPercent` + 入口校验形态白名单）。
- **落点**：`CommissionParamBounds` 常量单源 + `SystemConfigService` 写侧天花板 + 读侧回落双保险 + 门禁 SETTER_RE 扩面。✅ **已落地 2026-11-14：代码 `9d5c724f`（fix-batch 登记 `e2e54807`，33 Java 用例+门禁夹具 21 断言全绿）；本文档本体落盘 = `d0da52d5`**。
- **拍板来源（2026-11-14 复核补留痕）**：上述值域由修复方按建议值域提出、用户确认拍板（「他推荐我的定的」），非修复方自行选口径；本条从「✅ 已拍板」补上来源，回应复核中「⑧ 已拍板的来源」一问。
- **竞品台账处置（2026-11-14）**：**不新增 CB 条目**，留痕于此。理由：四键值域是「防手填荒谬值」的**内部防御性边界**，不是对外业务决策，没有可对照的公开产品参数；`check-competitor-benchmark` 要求每条 ≥3 家独立主体、其中 ≥2 家开门柜同行 + ≥1 个真实 URL（fail-closed），为凑数把「同行未披露」写成证据属于编造，违反 AGENTS.md「不得凭记忆填」。故 AGENTS.md 第 6 条在此以**有意不入台账**的方式留痕，R23 L1 不得重复判为欠账。同批改动的 `consumerPublicConfig()` 展示面回落属同一防御性边界的执行面，同样不是新业务决策。

---

## 台账口径备忘（非拍板项，防止下轮误读）

- **登记制清零 ≠ 语义补齐**：S9 端点级 215 未归因（455/240，`maxUnattributed=215` 棘轮只减）、S10 回显字段/windowLabel 随修复批——数字清零的都是「分类/登记」维度。
- **S9 215 的分类构成（2026-11-14 实证订正，勿沿用「大头是 CONSUMER_WRITE/DEVICE_FACING」的说法）**：按 `write-attribution-registry.json` × `write-endpoint-attribution-baseline.json` 交叉（每控制器 `endpoints - attributed`），已带语义豁免分类的是 `DEVICE_FACING 68 + CONSUMER_WRITE 6 + CALLBACK 4 + DEV_ONLY 3 = 81`（37.7%）；其余 **134** 条落在「类级判可达、端点级未归因」的控制器（MerchantPortal 21 / Auth 14 / 商户盘点 6 / OpsRbac 5…），是两级判据的口径差，**不是已证实的真缺口，但也绝不能当噪音**。R23 L4 的「运营侧写端点漏网」镜头应重点重扫这 134 条，而不是把 215 整体当噪音收敛。
- **fixture 三口径并存**（引用时标来源）：独立 `.test.mjs` 文件数（47/144）≠ wiring 夹具覆盖计数（76/144）≠ `check-gate-test-coupling`（diff 范围，推送后「改动集为空」是预期行为非故障）。
- **提交说明勘误**已在 `AUDIT_FOUR_END_R22_2026-11-12.md` :302-303（e49b3556/48ae2de4 说明与 --stat 不符、ddbfc438 豁免口径冲突）——追溯一律以 `git show --name-only` 为准。
