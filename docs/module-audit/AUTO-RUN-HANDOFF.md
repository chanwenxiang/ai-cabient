# 值守自动任务交接书（2026-11-02 起，用户已授权「按推荐自决、跑完为止、不要再停下来问」）

> 本文件是给**自动任务会话**读的唯一起点。每完成一批：更新本文件 §5 进度表 → 提交 → 推送。
> 仓库细则见 `AGENTS.md`、`docs/PROJECT_KNOWLEDGE.md`、`docs/module-audit/PLAN.md`。

## 0. 当前基线（开工前先复核，不要照抄本行）

- 分支 `dev`，远端 tip 以 `git rev-parse --short HEAD` + `git ls-remote` 为准（本文件写作时 `aceee87e`）。
- 活栈：docker compose `-f infra/docker-compose.full.yml -f infra/docker-compose.win-ports.yml`；
  trade `:18080`、网关 `:80`、admin vite `:3100`（**可能是别的会话在用，别重启**）、
  Grafana `:13000`、Prometheus `:9090`、模拟器容器 `device-simulator`（柜 `500909625160`）。
- Flyway 高水位：**代码侧 V425**（MA-029 的 ② 形种子 backfill）；**活库仍停在 v424**，
  V425 要等 trade 镜像重建/重启时才应用 ⇒ 动共享栈前先现算一次：
  `docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -At -c "select max(version::int) from flyway_schema_history where success;"`
  （下一个空号也请按这条现算，别照抄本行）
- 工作树必须干净才动共享容器；脏树时**逐文件归因**，别把别人的未提交源码烤进产物。

## 1. 第一优先：MA-029 落地（✅ 已于 2026-11-02 值守批落地，勿重做；下面是当时的口径与连带面清单，供 R10/R11 复核）

台账：`docs/module-audit/ISSUES.md` 的 MA-029（P1）。语义冲突：会员折扣有两套并存机制——
①`SettlementOrderSupport.applyItemsToOrder` 把会员价算进单价（行金额已含折扣）；
②`recalculatePayableAfterLineChange` / `applyProportionalRefundPayable` 把 `member_discount_cents`
当订单级减项**再扣一次**。②历史上恒 0 从未生效 ⇒ **①是权威**（选 B 等于重写定价链，已否决）。

四步，**必须同一批提交，不许只落一半**：
1. 删②的两处减项：两处都改成 `total = 行合计 − 券`；会员档只夹取/按比例留档，不再参与应付。
2. 开①的入账：`applyItemsToOrder` 写 `memberDiscountTotal = Σ(目录价 − 会员价) × 数量`；
   配合 V424 的 `gross_amount_cents`，恒等式 `gross − member − activity − coupon = total` 成立。
3. 改测试而不是绕测试（2026-11-02 实测已知的连带面）：
   - `SettlementConfirmDisputeTest.confirmDisputedItems_withMemberDiscount_preservesDiscountOnRecalc`
     断言的是②的假想语义（3×400−100=1100），要重写成①的真实形态（会员 2%：400→392，3 件＝1176 而非 1152），
     并改名成 `…discountsOnceNotTwice` 之类。
   - 🔴 同批还会挂两条（已实测到，别当成回归）：`confirmDisputedItems_withCoupon_recalculatesPayableAndAdjustsSplit`
     （期望 800 得 784）与 `confirmDisputedItems_increase_balanceInsufficient_skipsInventoryAndPayment`
     （抛出的异常类型变了，因为 delta 变了）⇒ 两条 fixture 也隐含②口径，一起改。
   - 新增反例钉测：会员 + 活动同时命中 ⇒ 只折一次。
4. 历史数据不回填金额（该列历史恒 0，去掉减法是恒等变换）；`gross` 已由 V424 回填。

验证：`mvn -pl services/trade-service -am -Dtest='Settlement*Test,Dispute*Test,DataConsistencyServiceTest,OrderPaymentLedgerTest' -Dsurefire.failIfNoSpecifiedTests=false test`
必须 0 fail；再按 §4 的数字实证补一层（活库真单）。

## 2. 第二优先：R9 商户端小程序 35 页（一页未测）

- 清单见 `docs/module-audit/PLAN.md` §R9；每批 ≤6 页，批末落 `docs/module-audit/rounds/R09-miniprograms.md` + 提交推送。
- 自动化配方（含全部已知坑）见记忆 `reference-mp-automator-verify.md`，要点：
  商户端 IDE 自动化端口 `ws://127.0.0.1:9420`（consumer 是 9421）；合成输入坏 ⇒ HTTP 登录
  `/api/v2/auth/merchant-password-login` + `setStorageSync` 注入；页面路径从
  `clients/merchant-mp/dist/dev/mp-weixin/app.json` 的 `subPackages` 取；
  截图按「序号+路由」命名（同路由会互相覆盖），并避开 `.gitignore` 的 `*verify*.png` 规则；
  `page.path` 是属性不是方法；探针文件用 `.cjs`。
- 写面（补货/盘点/定价/提现/组织等）**只开确认框**，不提交；金额一律走 dev mock。
- 🔴 探针 v2 才可信：uni-app 把**直接写在 `view` 里的文案**（空态句、hint、picker 当前值、tab 计数）
  放在 view 节点，只采 `text` 会漏读并**误报成缺陷**（M 批 2 实测）；`.tmp/r9-merchant-walk.cjs` 已改成
  `text` + `view` 双采并去重。另外：`page.data()` 是混淆键（读不到状态）、原生 `<picker>` 选项层与
  `page.callMethod(setup 函数)` 都不可用 ⇒ 「换柜机」这类交互只能留人工判据，别当成产品缺陷。
- 截图命名 `b<批>-<序号>_<路由>.png`，探针必须从**仓库根**跑（相对路径），并带 `START_INDEX` 避免重跑覆盖。

## 3. 第三优先：R10 三端横切 → R11 业务流转全链路

- R10（`rounds/R10-crosscut.md`）：只收三类可判定项——同一端点三端字段口径（金额分/元、状态名、时区）、
  权限面一致性、按 `docs/module-audit/regression-index.md` 复跑各轮回归。
- R11（`rounds/R11-e2e-flows.md`）：造数全链路剧本（下单→开门→识别→结算→分账→提现→退款/争议），
  三端点验落点；用模拟柜 + 模拟器，钱只走 dev mock。

## 4. 每批硬约束（违反即停）

- 推送前必跑 `node scripts/pre-push-ci-preflight.mjs`，**看真实退出码**（禁止管道掩盖）；非 0 不推。
- 推送只走：`git push ssh://git@ssh.github.com:443/chanwenxiang/ai-cabient dev:dev`（本机 22 端口被 reset）。
- 改了 admin 源码 → 先提交源码，再 `bash scripts/build-admin-ci-fidelity.sh`，产物有差异才提交产物；
  改了 Controller/DTO 形状 → 必须重烤 `packages/shared-types/src/generated/openapi.ts`
  （`OPENAPI_IGNORE_CACHE=1 OPENAPI_URL=http://127.0.0.1:18080/v3/api-docs pnpm gen:api-types`），
  否则 CI 的 `check-openapi-regen-required` 判红（2026-11-01 踩过）。
- MA 编号只能从 `docs/module-audit/ISSUES.md` 现取（先回读再领号，禁撞号）。
- 资金改动要数字实证：非零钉测 + 独立对账 SQL + 四层互证 + 留痕；「全是 0」不算验证。
- 迁移文件头必须 `-- MIGRATION_KIND:`（纯 DML 用 `backfill`；`schema` + 数据写语句会被门禁判矛盾）；
  新建业务表/feature 需 `-- COMPETITOR_REF: CB-xxx`（先查 `docs/COMPETITOR_BENCHMARK.md` 有没有真条目）。
- 不碰别的会话的容器/栈；不删业务数据；凭据不入库（只记键名与判据）。
- 上下文不够时：**把当前批做到自然边界**（报告落盘 + 提交 + 推送）再收口，并更新本文件 §5，
  绝不停在半批未提交状态。

## 5. 进度表（每批完成后更新本表并推送）

| 项 | 状态 |
|----|------|
| R0–R8 后台全模块 | ✅ 完成（R8 收口于 `55bac20f`） |
| R9 consumer 22/23 页 | ✅ 完成（`announcements/detail` 无公告数据可看） |
| MA-020 / 021 / 022 / 023① / 024 / 025 / 026 / 027 / 028 | ✅ 已落地，活库 v424 上运行时复验通过（见 R09 轮报告） |
| **MA-029（方案 A）** | ✅ 已落地（2026-11-02 本批）：② 减项实为 **5 处**（改单重算 / 比例退写侧 / 比例退**预估侧** / 巡检**检测式 SQL** / 脏券清理抹会员），① 入账 + `V425` 改 ② 形种子 + 结果页基准改 gross；四层实证齐（新钉测 8 例 + 指定套件 20 类 0 fail + `MemberDiscountLedgerSqlIT` 活库真单 + dev 只读对账 0 违规）；详见 `rounds/R09-miniprograms.md`「MA-029 方案 A 落地」 |
| MA-029 活栈 HTTP 会员单 | ⬜ 需在重建 `:18080`（新镜像含本批）后验 ⇒ 并入 **R11** 全链路那一轮（那轮本就要用新镜像跑一遍会员单）；脏树期不动共享容器 |
| R9 merchant-mp 35 页 | 🟡 **24/35**（批 1 主页 6 + 批 2 履约 6 + 批 3 交易/消息 6 + 批 4 财务 6）；MA-030/033 已修并活体复验，MA-031/032/034 领号 open（轮末文案批），批 3 零领号（三条假象已查证并留证据）← **从这里接 M 批 5（还剩 11 页）** |
| MA-031（待办类型标签「未知」） | ⬜ 轮末批：shared-dict 基线 4 值 + dist 重烤 + SysDictBootstrap + DB 字典迁移；判据 = 待办页 0 行「未知」 |
| R10 三端横切 | ⬜ 未开始 |
| R11 业务流转全链路 | ⬜ 未开始 |
| MA-022 在 `:3100` 的复验 | ⬜ 需人工重启 vite（会动别的会话），暂挂 |

## 5.1 本会话（2026-11-02 值守）推送后的 CI 状态（诚实记账，勿当「已绿」）

- 本地 `node scripts/pre-push-ci-preflight.mjs` 四批各跑一次，**全部退出 0**（聚合链 84 门禁 0 失败）。
- 远端 run：`MA-029 落地留痕` = **success**（含 integration job ⇒ 新 IT 在 CI 的 Docker 里真跑了）。
- `MA-030` = **failure**，红在 `JiangyiGatherConsoleServiceTest.vendorFailureStillUnlocksAndRecordsPhase:140`
  —— 该用例用 `await(...terminal())` 轮询异步终态，本地 `-Dtest=JiangyiGatherConsoleServiceTest` **退出 0**；
  且**纯文档提交 `f9905080` 那一跑也红**，红的是另一个用例（device-service `EmqxSharedSubscriptionIT` Errors: 1）
  ⇒ 判定＝**既有 CI 抖动（异步等待/共享 runner 负载），与本会话改动无因果关系**。
  下一会话若再见到这两个名字红，先按「重跑 + 本地复跑同名单测」归因，别顺着改业务码；
  若要根治抖动，方向是给这两类 `await` 轮询加显式超时上界与可注入时钟（属测试加固，另立 MA）。
- 截止本会话收口时，批 2/3/4 三个 run 仍在 in_progress（未看到结论），**下一条接手第一件事**：
  `gh run list --limit 5` 看这三个的结论，若红仍集中在上面两个抖动名，记录并重跑；若是新面红，按本文件 §4 归因。

## 6. 全部完成的判据

§5 表内所有行都是 ✅ 或明确标注「需人工 + 原因」，且 `docs/module-audit/PLAN.md` 的 R9/R10/R11 三行
状态更新为完成、最后一次 `pre-push-ci-preflight` 退出 0、远端 tip 与本地一致、CI 无本会话引入的红。
