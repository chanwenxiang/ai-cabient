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
- 微信开发者工具报 `getRootFactory Not Define Env`（点预览时）＝**编译缓存陈旧**，与仓库代码无关：
  `cli.bat cache --clean compile --project <dist/dev/mp-weixin>` 后 `cli.bat preview --project <同路径> --qr-format terminal` 即通过
  （2026-11-02 商户端/消费端各验一次，35/23 页均编译成功）。自动化实例（`cli auto --auto-port 9420/9421`）里点预览尤其容易撞上。
- 🔴 迁移文件里**禁止**出现美元符+花括号的模板字面量：Flyway 连注释一起做占位符替换，会让 trade 启动即失败（lessons #370）。
  原地修一条「已入 dev 但从未成功应用」的迁移是允许的，但必须在提交信息与 lessons 留证据（#371）；否则一律「保留旧文件 + 新增迁移」。

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
| MA-029 端到端腿 | ✅ **活栈真单腿已挣到**（2026-11-02 10:0x）：会话 `1793584788418202321898` → 订单 `1793584809445376908254` PAID，`gross=350 / total=243 / member=7 / activity=100 / coupon=0`，恒等式差额 **0**（350−7−100=243，各折只折一次）。路径要点：①夹具 RETIRED 时**先开门**（BOUND 会把开门指令路由到将邑云，MQTT 模拟柜收不到 ⇒ `开门指令下发失败`）；②进 SHOPPING **之后**再翻 BOUND；③关门进 RECOGNIZING 后推 `/internal/v1/vision/edge-results`，**必须带 deviceId**（漏了会 400「设备与会话不匹配」）+ modelVersion；④结束恢复 RETIRED。脚本 `.tmp/step-open.cjs` + `.tmp/step-settle.cjs`。前端点击「扫码开门」用文本匹配最稳（class 会改）。另：R11 段 1 的仓内 E2E 早已挣得，两者互证。 |
| R9 merchant-mp 35 页 | ✅ **35/35 走完 + 轮末文案批 4/4 收口**（六批 6+6+6+6+6+5；MA-030/031/032/033/034/035 全部已修并活体复验）。🔴 三条「未挣」仍在：公告内容腿需造数、仓拓扑只有 1 仓、提现确认框披露 ⇒ 见 `rounds/R09-miniprograms.md` 待拍板 #11（全部并入 R11 段 2 前置） |
| R9 轮末文案批 | ✅ **4/4 完成**：MA-032（盘点待录入改「实盘 未录入（默认同账面）」）、MA-034（两处标注同批改：「客单(实付)/件均(原价)」+「单均原价」）、MA-035（shared-uni 一处修两端 + 2 例钉测）、MA-031（字典四件套：shared-dict 基线 + dist 重烤 + SysDictBootstrap + V426 迁移）全部活体复验 |

| R10 三端横切 | 🟡 **主体完成**（`rounds/R10-crosscut.md`，锚 `914b166e`）：10.1 状态名/金额/时区三维度确认**单源**（admin 也 import shared-uni）；10.2 权限闭环三方对账 ⇒ **A∖B / C∩B非ACTIVE / C∖B 三条危险方向全 0**，真幽灵码 4 个领 **MA-036**（含一条脚本假阳性护栏：`ops:audit:recent` 是非注解用法）；10.3 回归复跑 103 例 Java + 2 IT BUILD SUCCESS + preflight 0。**遗留 4 条**写在 R10 §10.4（门禁未落 / 8 处 toLocaleString / MA-036 迁移 / 全部结论建立在旧镜像上） |
| R10 首查项（缺货口径） | ✅ 判为**已拍板的刻意兼容**（`replenishment.vue:88` 注释 CB-076「项」兼容两柜型）⇒ 不领号；另发现 shortage-summary 端点静默忽略 `?deviceId=`（前端未传，无影响） |
| MA-036（4 个 ACTIVE 幽灵权限码） | ✅ 已落地（`V427`，2026-11-02 06:4x 接手批）：本人复核四码确为零引用后连同 `ops:operlog` 一起 RETIRED（该父行 MA-021 的保留理由随子节点被证伪而消失）；`ops:pricing:view` 有 2 个在用子节点 ⇒ 继续保留。dev 事务内试跑 `UPDATE 5` 后回滚，等 trade 重启生效 |
| MA-031（待办类型标签「未知」） | ✅ 已修：shared-dict 基线补 `OPEN_TIMEOUT`/`RESTOCK_TASK_TIMEOUT`/`SLOT_DISCREPANCY`/`INVENTORY_MISMATCH` + `DOOR_OPEN_TOO_LONG` 让位改名「长时间未关门」+ dist 重烤 + `SysDictBootstrap` 同值 + `V426`（backfill）；`DictLabelUniquenessIT` 1/0/0、活体「未知」0 次。🔴 唯一未落点：admin 侧读的 `sys_dict_data` 要等 trade 容器重启才应用 V426（活栈有他在用，见下行） |
| R11 业务流转全链路 | 🟡 **段 1 交付**（`rounds/R11-e2e-flows.md`）：消费下单→开门→端侧识别→结算→分账→商户钱包，会员单六层互证全平（含「会员+券同时生效只减一次」的反例护栏）。🔴 段 2 的四条前置（第 2 仓 / 争议退款+月结提现 / 公告造数 / 活栈复走）已按「谁做-做什么-看哪张表」写在轮报告 §「段 2+ 待做」 ；**段 2 进度（2026-11-02）**：B3 公告造数+读面 ✅（列表/详情/无网络错误文案）、MA-029 活栈真单腿 ✅；余 B1 第 2 仓 / B2 争议退款+月结提现 / B4 三端同读 |
| MA-022 在 `:3100` 的复验 | ✅ 完成（2026-11-02 07:2x 用户批准后重启 `:3100` vite）：`/devops/grafana/api/health` → **200**、两个真实看板 URL → **302**（跳 Grafana 登录，非 404），admin 首页 200 |
| 活栈复走（用户 06:4x 批准重启 trade） | 🟡 **部分完成**（2026-11-02 07:0x）：镜像重建到 `8912bcd2`、health 200、0 ERROR、Flyway 高水位 **v427**（V425/426/427 全落）；MA-036 五行活库确认 RETIRED、MA-031 字典「未知」0 命中、**MA-023① 真端挣到**（小程序商品卡实测「原价 ¥3.43 / ¥2.43 罐」，改前是 3.50）。🔴 **未挣**：MA-029 的活栈真单腿——`.tmp/mp-buy-cb082-2.cjs` 跑到「翻将邑绑定」后 `fetch failed`（trade 刚重启，模拟器/内部接口会话需重建），下单未闭环 ⇒ 下一会话先修这条脚本或直接走 `MemberOrderChainE2ETest` 的活栈版 |
| 🔴 本会话自伤记录 | 我的 `V427` 注释里写了模板字面量（美元符+花括号），Flyway **连注释做占位符替换** ⇒ trade 启动即失败一次（`psql` dry-run 与 preflight 都看不到）。已改注释、重建、恢复，并记入 `docs/engineering/lessons-learned.md` **#370**：迁移文件禁写该形态，重启共享容器后必须看 `docker logs` 不能只看 health 码 |

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

## 5.2 剩余三项执行清单（每项：谁做 → 做什么 → 判据）

### A. MA-029 活栈真单腿（唯一「代码已绿、活栈未闭环」的一条）

1. **谁做**：值守会话。**做什么**：修 `.tmp/mp-buy-cb082-2.cjs` 的收尾——它把演示柜 `jiangyi_device.status` 翻成 `BOUND` 后靠 `finally` 恢复 `RETIRED`，中途 `fetch failed` 就没跑到（已被 `check:jiangyi-ui-copy-and-fixtures` 抓到一次）。改成「先登记、后翻转、`try/finally` 必恢复」，并在脚本开头先断言当前是 `RETIRED`。
2. **做什么**：重跑脚本跑完一单（模拟器 `AICABINET_SIM_SHOPPING_MS=20000`，门开→端侧识别→关门结算）。若 `fetch failed` 复现，先 `docker ps` 确认 trade 容器与 `jiangyi-gateway` 都在，再查 `docker logs ai-cabinet-trade-service-1 --since 2m | grep -i error`，别直接改业务码。
3. **判据**（三项同时成立才算挣到）：
   - 新订单行：`select gross_amount_cents, total_amount_cents, member_discount_cents, activity_discount_cents from cabinet_order order by created_at desc limit 1` ⇒ `gross − member − activity − coupon = total` 且 `member>0`（银卡 2%：350→343 应记 7）；
   - 消费端订单详情渲染「商品合计 / 会员优惠 / 活动优惠 / 券 / 实付」五档且能减平；
   - 脚本退出时 `select status from jiangyi_device where device_id='500909625160'` = **RETIRED**，随后 `node scripts/check-jiangyi-ui-copy-and-fixtures.mjs` 退出 0。

### B. R11 段 2（四条前置，逐条可独立收口）

| # | 谁做 | 做什么 | 判据 |
|---|------|--------|------|
| B1 第 2 个仓 | 值守会话 | 造第 2 个 `warehouse`（1 仓→2 仓），走一次跨仓调拨与「生成备货单」 | 调拨后两仓 `quantity` 之和不变；备货单汇总行按全柜在架 SKU 算（不是只看缺口行） |
| B2 争议退款 + 月结提现 | 值守会话 | 一笔争议单走 CONFIRM 结案 → 退款；再跑月结提现 | `refunded_cents` 与支付流水对平；提现申请在 `withdraw` 侧状态机连贯（不出现 PAYING 重入）；金额只用 dev mock |
| B3 公告造数 | 值守会话 | 新建 1 条公告（`announcement` 表当前 0 行），再走 consumer `announcements` 列表 + `announcements/detail` | 列表条数=1；详情页正常渲染且**不再出现「请检查网络后重试」**（MA-035 判据） |
| B4 活栈复走 | 值守会话 | 全部三端在同一 HEAD 上重读会员单（admin 订单列表/商户端/消费端） | 三处「会员优惠」行数值一致，且与 `cabinet_order` 账面同值 |

### C. R10 遗留两条

1. **perm-closure 校验落地（R10 裁决：以 `PermClosureSqlIT` 集成测试形态，不做静态门禁）**：把 `.tmp/r10-perm-closure.mjs` 搬进 `scripts/` → A 集改成「`@RequiresPermissions` 注解 **+** `hasPerm`/`requirePermission` 字符串用法」双扫（反例护栏：`ops:audit:recent` 是非注解用法，必须被认作「有引用」）→ 加白名单文件（`ops:nav:*`、realm 根、V421/V427 已 RETIRED 的）→ 负向自测（造一个假幽灵码必须红）→ 进 `run-audit-gates` 聚合链。**判据**：真库上跑 `A∖B`、`C∩B非ACTIVE`、`C∖B` 三方向全 0；故意加一条幽灵码时门禁退出 1。
2. **8 处 admin `toLocaleString*`**：逐处判「是否展示金额」——**是** ⇒ 改走 `@/utils/display` 的 cents→元 helper（金额禁止 `toLocaleString` 直接格式化分）；**否**（纯计数/日期）⇒ 原地加一行注释说明用途。**判据**：`grep -rn "toLocaleString" clients/admin-vue/src` 每处都有归属（改走 helper 或注释），且 `pnpm -C clients/admin-vue test` 绿。

## 6. 全部完成的判据

§5 表内所有行都是 ✅ 或明确标注「需人工 + 原因」，且 `docs/module-audit/PLAN.md` 的 R9/R10/R11 三行
状态更新为完成、最后一次 `pre-push-ci-preflight` 退出 0、远端 tip 与本地一致、CI 无本会话引入的红。

## R12–R17 过夜自动跑断点（2026-11-02 晚）

- **驱动器**：定时自动化 `automation-cd7b6a5d`（每 45 分钟一枪共 12 枪）+ 闲时任务 offpeak-4ab2e85c 兜底；互斥锁 `.tmp/r12-overnight.lock`（mtime<100 分钟视为在跑）。
- **T0**：✅ 关闭（MA-039~067 由修复会话代领，本会话对账无缺号；见 T0-INBOX 对账结论）。
- **R12 批 1**：✅ 完成（23 Controller/137 写端点，P2×3/P3×11 → MA-068~081；轮报告 rounds/R12-write-surface.md 含五批分组表）。
- **R12 批 2**：✅ 完成（17 Controller/45 写端点，P3×8 → MA-082~089，2026-11-03 凌晨）。
- **R12 批 3**：✅ 完成（15 Controller/55 写端点，P2×1+P3×9 → MA-090~099，2026-11-03 凌晨）。
- **R12 批 4**：✅ 完成（14 Controller/79 写端点，P2×2+P3×13 → MA-100~114，2026-11-03 凌晨；两条 P2 主会话已亲自复核源码）。
- **R12 批 5**：✅ 完成（23 Controller/98 写端点，P2×2+P3×9 → MA-115~125）。**R12 全轮收官**：95 Controller / 395 写端点，58 条（P2×7/P3×51），无 P0/P1，零裸奔；总结见 rounds/R12-write-surface.md 末节。
- **R17 批 1**：✅ 完成（2026-11-03 凌晨）：E2E 会员链复验全绿（gross=700/member=14/coupon=200/total=486 六层判据过）+ 提现边界五发全 400 正确 + 并发同 requestNo 幂等重放零双花（三行流水自洽）；资质临时填已还原；遗留 R17-RACE-002 测试单已留档。新发现 MA-126（@Positive 文案误导，P3）。
- **R16 静态前置**：✅ 完成（2026-11-03 凌晨）：20 实体金额口径全覆盖 + 60+ 字典类型三源比对，P2×10+P3×10 → MA-127~146；三条头部 P2 主会话已亲自复核。轮报告 rounds/R16-cross-end-static.md 含三大家族归纳与批量修法建议。
- **剩余未开工面**：R17 批 2（组合矩阵，需拍板）、R13/R14/R15（三端写面实测，需活栈+automator/授权）、R16 运行时三端同数（需拍板）——**全部需要用户拍板或活栈人工，无人值守枪已无可推进的静态/活栈批**。
- **R17 批 2 + R16 运行时**：✅ 完成（2026-11-03 上午，用户授权矩阵 E2E）：`SettlementComboMatrixE2ETest` 入仓随 CI（8 组合+退款三面+补扣链，1/0 绿、R11 链回归 2/0）；新发现 **MA-147 全额退不还券（P2）**；CB-082 禁券/MA-029 只折一次/MA-039 补扣禁券/MA-040/066/067 全部拿到端到端腿；R16 运行时订单实体三端读面固化进矩阵断言。详见 rounds/R17-runtime-flows.md 批 2。
- **R13 + R14**：✅ 完成（2026-11-03 上午）：R13 admin 79 路由读面 100% 干净 + 调拨弹窗修复复验 + 字典/公告/部门写操作端到端（零残留，无新领号）；R14 consumer 23/23 页遍历（22 OK + login 守卫按设计），修复本地 API 地址配置（.env.development 旧 IP → localhost，两端）；环境坑留痕（admin=history 路由+cookie 模式；automator 元素交互协议兼容问题）。轮报告 rounds/R13-admin-write.md、R14-consumer-write.md。
- **R15 进行中**：merchant automator（9421）已连接；密码登录的 UI 点击被 automator 元素协议/混淆键阻断（setData 已写入 phone/password），**等用户在 devtools 手动点登录**后接管遍历 35 页。
- **剩余未开工面（更新）**：R17 批 3 剩余=余额+微信组合支付（需双渠道 mock）；R13/R14/R15（三端写面实测，需活栈+automator/Playwright 人工时段）。无人值守枪继续空转。
- **下一枪动作**：空转退出。**等用户决定**：①R13/R14/R15 活栈人工时段；②MA-147 与 MA-127~146 是否交白天修复 AI（建议「字典回填家族」+「全额退还券」一批）；③终局报告 R17-FINAL 待 R13/R14/R15 收口后写（或按当前形态先出「静态+运行时已挣面」版终局）。
- 🔴 接手修复会话在同一工作树活跃：git add 只加 docs/module-audit/ 显式路径；ISSUES.md 只追加；发现冲突立即停手落盘。

## 六轮全收口（2026-11-03）：R17-FINAL 已出

- R12/R13/R14/R15/R16/R17 全部 ✅（R17 批 3 组合支付留待双渠道 mock 排期）。
- 终局报告 `rounds/R17-FINAL.md`：80 条新发现（P2×18/P3×62）、4 个修复批次建议、3 项拍板、4 个门禁化候选。
- **此后每枪一律空转退出**（终局已出，无剩余无人值守面）。等用户：①修复交接（4 批次）；②MA-129/147 拍板；③真机清单排期。

## 订正（2026-11-05，R17 批 3 收口）

- 上文「R17 批 3 组合支付留待双渠道 mock 排期」的**前提判错**：`OrderPaymentChargeInvariantTest` 的内存
  `payment_operation` 表 + 桩 `chargeExplicit` 就能造「钱包已冲抵 → 渠道补缴」前置态，不需要双渠道环境。
  一条用例当场抓出 **MA-160（P1：组合腿按应付全额重复扣，用户实付=冲抵+全额）**，修复锚 `d7b37278`；
  同批另立 **MA-161（P1，open 待拍板：组合单退款按渠道实扣额拆分，本批只把静默少退变成显式失败）**。
  详见 `rounds/R17-runtime-flows.md`「未挣 → 已挣」段、`ISSUES.md` MA-160/MA-161、lessons #416。
