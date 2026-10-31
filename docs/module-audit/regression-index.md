# 回归用例索引（MA-xxx ↔ 自动用例）

> 用途：PLAN 工作法第 7 步要求的落点。**每条修完的 P0/P1（有余力则含 P2/P3）都要在这里占一行**，写清用例在哪、跑什么命令。
> R10「三端横切 + 回归各轮问题」直接按本表执行；没有自动用例的行就是 R10 必须补的债。
> 状态只有三种：`钉测`（已在仓库、随 `mvn test` / CI 跑）、`UI`（Playwright / automator 脚本，注明路径）、`无`（只有当时的人工复验 ⇒ 欠债）。

| MA / 缺陷 | 级别 | 用例 | 位置 | 怎么跑 | 状态 |
|-----------|------|------|------|--------|------|
| MA-001 固定窗口口径（7d/30d 不随 `days` 漂移） | P2 | `summary_days7_active30AndNew30_stayFixed30dWindow` | `services/trade-service/src/test/java/com/aicabinet/trade/service/UserBehaviorAnalyticsServiceTest.java:84` | `mvn -pl services/trade-service -am test -Dtest=UserBehaviorAnalyticsServiceTest` | 钉测 |
| MA-001 反向（`days=90` 不得虚增）＋ MA-002 沉睡名单不截到 20 | P2/P3 | `summary_days90_active30_notInflated_andDormantList_notTruncatedAt20` | 同上 `:110` | 同上 | 钉测 |
| MA-003 召回弹窗文案「20 人（近 7 天）」→「30-90 天全量」 | P3 | 无（只有人工截图复验，`evidence/R01-useranalysis-fixed-dialog-22-full.png`） | — | — | **无**（R10 补 Playwright 弹窗文案断言） |
| MA-007 类型筛选服务端化（SQL 过滤 ⇔ Java `sessionKind()` 同源） | P1 | `SessionKindFilterSqlIT`（真库 Testcontainers） | `services/trade-service/src/test/java/com/aicabinet/trade/integration/SessionKindFilterSqlIT.java` | `mvn -pl services/trade-service -am verify -Dit.test=SessionKindFilterSqlIT -Dfailsafe.failIfNoSpecifiedTests=false`（🔴 `*IT` 只有 `mvn verify` 跑） | 钉测（IT） |
| R1 销售报表/大屏品类维度裸字典码「1」（提交 `355bb308`） | P2 | `salesReport_categoryDim_translatesDictCodeToLabel` | `services/trade-service/src/test/java/com/aicabinet/trade/service/CompetitiveGapSalesReportTest.java` | `mvn -pl services/trade-service -am test -Dtest=CompetitiveGapSalesReportTest` | 钉测 |
| R1 大屏头部窄视口重叠（提交 `5a62f77e` + 产物 `0eb90b49`） | P3 | 无（人工复验 `evidence/R01-big-screen-narrow-fixed-558.png`） | — | — | **无**（R10 补 1280/1920 双视口截图断言） |
| CB-080 下架 SKU 台账行「可见可清」 | P2 | `getDeviceDetail_filtersDelistedSkuFromSkuInventory` | `services/trade-service/src/test/java/com/aicabinet/trade/service/DeviceSlotServiceTest.java:188` | `mvn -pl services/trade-service -am test -Dtest=DeviceSlotServiceTest` | 钉测 |

## 补债规则

- 「无」状态的行**只能在 R10 收口前消失**，且补的是**能失败**的用例（先注回缺陷跑一遍红，再改回绿——与 `check-competitor-benchmark` 那类 negative gate 同思路）。
- 前端文案/样式类最容易被跳过，所以 R1 起每条 P3 文案缺陷在轮报告里都要写「人工判据 = 哪个元素哪段文字」，R10 才能直接翻译成断言。
