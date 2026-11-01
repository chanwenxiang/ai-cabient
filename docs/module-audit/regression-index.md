# 回归用例索引（MA-xxx ↔ 自动用例）

> 用途：PLAN 工作法第 7 步要求的落点。**每条修完的 P0/P1（有余力则含 P2/P3）都要在这里占一行**，写清用例在哪、跑什么命令。
> R10「三端横切 + 回归各轮问题」直接按本表执行；没有自动用例的行就是 R10 必须补的债。
> 状态只有三种：`钉测`（已在仓库、随 `mvn test` / CI 跑）、`UI`（Playwright / automator 脚本，注明路径）、`无`（只有当时的人工复验 ⇒ 欠债）。

| MA / 缺陷 | 级别 | 用例 | 位置 | 怎么跑 | 状态 |
|-----------|------|------|------|--------|------|
| MA-001 固定窗口口径（7d/30d 不随 `days` 漂移） | P2 | `summary_days7_active30AndNew30_stayFixed30dWindow` | `services/trade-service/src/test/java/com/aicabinet/trade/service/UserBehaviorAnalyticsServiceTest.java:84` | `mvn -pl services/trade-service -am test -Dtest=UserBehaviorAnalyticsServiceTest` | 钉测 |
| MA-001 反向（`days=90` 不得虚增）＋ MA-002 沉睡名单不截到 20 | P2/P3 | `summary_days90_active30_notInflated_andDormantList_notTruncatedAt20` | 同上 `:110` | 同上 | 钉测 |
| MA-003 召回弹窗文案「20 人（近 7 天）」→「30-90 天全量」 | P3 | 无（只有人工截图复验，`evidence/R01-useranalysis-fixed-dialog-22-full.png`） | — | — | **无**（R10 补 Playwright 弹窗文案断言） |
| 平台抽成金额正确性（5%/取整/部分退款/0 费率） | — | `RevenueSplitServiceTest` 新增 4 条 | `services/trade-service/src/test/java/com/aicabinet/trade/service/RevenueSplitServiceTest.java` | `mvn -pl services/trade-service -am test -Dtest=RevenueSplitServiceTest` | 钉测 |
| 资金三层对账（分账↔订单↔钱包↔日账单） | — | `money-recheck.sql` 6 段，期望全 0 | `docs/module-audit/seeds/money-recheck.sql` | `docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -f - < docs/module-audit/seeds/money-recheck.sql` | 只读脚本（R11 每次造有钱数据后必跑） |
| MA-007 类型筛选服务端化（SQL 过滤 ⇔ Java `sessionKind()` 同源） | P1 | `SessionKindFilterSqlIT`（真库 Testcontainers） | `services/trade-service/src/test/java/com/aicabinet/trade/integration/SessionKindFilterSqlIT.java` | `mvn -pl services/trade-service -am verify -Dit.test=SessionKindFilterSqlIT -Dfailsafe.failIfNoSpecifiedTests=false`（🔴 `*IT` 只有 `mvn verify` 跑） | 钉测（IT） |
| R1 销售报表/大屏品类维度裸字典码「1」（提交 `355bb308`） | P2 | `salesReport_categoryDim_translatesDictCodeToLabel` | `services/trade-service/src/test/java/com/aicabinet/trade/service/CompetitiveGapSalesReportTest.java` | `mvn -pl services/trade-service -am test -Dtest=CompetitiveGapSalesReportTest` | 钉测 |
| R1 大屏头部窄视口重叠（提交 `5a62f77e` + 产物 `0eb90b49`） | P3 | 无（人工复验 `evidence/R01-big-screen-narrow-fixed-558.png`） | — | — | **无**（R10 补 1280/1920 双视口截图断言） |
| CB-080 下架 SKU 台账行「可见可清」 | P2 | `getDeviceDetail_filtersDelistedSkuFromSkuInventory` | `services/trade-service/src/test/java/com/aicabinet/trade/service/DeviceSlotServiceTest.java:188` | `mvn -pl services/trade-service -am test -Dtest=DeviceSlotServiceTest` | 钉测 |
| MA-029 会员+活动同时命中只折一次（恒等式 1200−24−150=1026） | P1 | `SettlementMemberDiscountOnceTest`（6 例：入账/反例/非会员/改单重算/比例退写侧/无券比例退） | `services/trade-service/src/test/java/com/aicabinet/trade/service/SettlementMemberDiscountOnceTest.java:88,108` | `mvn -pl services/trade-service -am test -Dtest=SettlementMemberDiscountOnceTest` | 钉测 |
| MA-029 争议改单不再减第二次会员（1176 而非 1152） | P1 | `confirmDisputedItems_memberDiscountAppliedOnceNotTwice` | `services/trade-service/src/test/java/com/aicabinet/trade/service/SettlementConfirmDisputeTest.java:240` | `mvn -pl services/trade-service -am test -Dtest=SettlementConfirmDisputeTest` | 钉测 |
| MA-029 退款「预估 == 写侧」且会员档不进应付（392 而非 397） | P1 | `partialRefund_withBookedMemberDiscount_estimateMatchesWriteSide` | `services/trade-service/src/test/java/com/aicabinet/trade/service/SettlementPartialRefundTest.java:186` | `mvn -pl services/trade-service -am test -Dtest=SettlementPartialRefundTest` | 钉测 |
| MA-029 活库真单：生产写入口 + 真 SILVER 2% 种子 + 恒等式/巡检式对账 SQL 全 0 违规 | P1 | `MemberDiscountLedgerSqlIT`（2 例） | `services/trade-service/src/test/java/com/aicabinet/trade/service/MemberDiscountLedgerSqlIT.java:118,167` | `mvn -pl services/trade-service -am verify -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=MemberDiscountLedgerSqlIT -Dfailsafe.failIfNoSpecifiedTests=false` | 钉测（IT） |
| MA-029 活库每次造钱后复跑（恒等式 + 折后式 + 入账可见性三段） | P1 | `money-recheck.sql` 7a/7b/7c | `docs/module-audit/seeds/money-recheck.sql` | `docker exec -i ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -f - < docs/module-audit/seeds/money-recheck.sql` | 只读脚本（R11 必跑） |
| MA-029 consumer 结果页「商品合计 − 各档 = 实付」基准 = gross | P1 | 无（人工判据：`pages/result` 商品明细卡首行数字 = 后端 `grossAmountCents`，且与会员/活动/券三行相减等于「实付金额」） | — | — | **无**（被 `:18080` 镜像卡住：须先重建到含 MA-029 的镜像并造一笔会员单 ⇒ 归 R11） |
| R10.2 权限闭环三方对账（后端码 ⊆ 表行；前端导航引用的行必须 ACTIVE） | P3 | `scripts/audit-perm-closure.mjs`（三方集合差集；本轮三条危险方向 0 条、真幽灵 4 个） | `scripts/audit-perm-closure.mjs` | `node scripts/audit-perm-closure.mjs`（从仓库根跑，需活库；🔴 未进 `check:audit-gates`，落门禁时 A 集要扩成「注解 + 字符串用法」双扫，否则 `ops:audit:recent` 这类非注解用法会被误判成幽灵码） | 无（分析脚本，非钉测；MA-036 修完再决定固化） |
| MA-035 公告详情缺参数不得显示「请检查网络后重试」/不得挂重试 | P3 | `announcements-detail.test.ts`（2 例：缺编号 ⇒ `missingId` 真 + 文案不提网络 + 不发请求；带编号 ⇒ 正常取数且 `missingId` 假） | `clients/merchant-mp/src/utils/announcements-detail.test.ts` | `cd clients/merchant-mp && npx vitest run src/utils/announcements-detail.test.ts` | 钉测（活体复验另见 R09 轮报告「轮末文案批」） |
| MA-034 客单两套分母的标注（实付 vs 原价不得同名） | P3 | 无（人工判据：`pages/business` 两个 hero 指标必须写「客单(实付)」「件均(原价)」；`pages/cabinet-reports` 行内必须写「单均原价」而非「客单」） | `clients/merchant-mp/src/pages/business/business.vue:43-46`、`.../cabinet-reports/cabinet-reports.vue:69-71` | 商户端 automator 走查（`.tmp/r9-merchant-walk.cjs`） | UI（已活体复验，无自动断言） |
| R11 段1 会员单全链路（结算→分账→钱包）四层互证 + 「会员+券同时生效只减一次」 | P1 | `memberOrder_settlesOnceAndMoneyClosesAcrossFourLayers` | `services/trade-service/src/test/java/com/aicabinet/trade/e2e/MemberOrderChainE2ETest.java` | `mvn -pl services/trade-service -am -Dtest=MemberOrderChainE2ETest -Dsurefire.failIfNoSpecifiedTests=false test`（真 PG + 真 Redis，Flyway V1→V425 从零；断言含 `member > 0`、两条恒等式、两端读面、`platform+merchant=gross=实付`、台账↔账户、全库折后式违规 0） | 钉测 |

## 补债规则

- 「无」状态的行**只能在 R10 收口前消失**，且补的是**能失败**的用例（先注回缺陷跑一遍红，再改回绿——与 `check-competitor-benchmark` 那类 negative gate 同思路）。
- 前端文案/样式类最容易被跳过，所以 R1 起每条 P3 文案缺陷在轮报告里都要写「人工判据 = 哪个元素哪段文字」，R10 才能直接翻译成断言。
