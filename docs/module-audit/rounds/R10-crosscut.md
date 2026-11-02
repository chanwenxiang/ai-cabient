# R10 三端横切审计（2026-11-02，锚 HEAD `914b166e`）

> 按 PLAN §R10 的口径：本轮**不做「三端重测一遍」**，只收三类可判定项。
> 环境：活栈 trade `:18080`（镜像 `f9905080`，**不含 MA-029**）、网关 `:80`、dev 库 Flyway 高水位 **v424**
> （代码侧 V425 未应用，等镜像重建）；IDE 自动化 商户端 `:9420` / 消费端 `:9421` 并存。
> 全程只读；无写面提交；`shopping_session` 3 小时内新增 0 行（SQL 实证）。

## 10.1 同一端点三端字段口径（抽样判定，不宣称全量）

| 维度 | 判据 | 结论 |
|------|------|------|
| 状态名 | 三端是否各自维护一张「状态→中文」映射 | ✅ **单源**。`packages/shared-uni/src/format.ts:257-259 orderStatusLabel = displayLabel('order_status', …)`；admin 侧 `DeviceRelatedRecordsTab.vue:183 dictLabel('order_status', …)`、`OrderListView.vue:30` tab 标签也取字典 ⇒ 同一基线。dev 字典 `order_status` 实有 10 值（PENDING 待支付…CANCELLED 已取消），三端能对上。**唯一差异是空值回退**：mp 回退「未知状态」、admin 多处回退 `''`——属各端 UI 选择，不算口径分裂 |
| 金额单位 | 是否存在某端把分当元 / 双重换算 | ✅ 沿用 2026-10-16 三端审计结论 + 本轮再证：三端都只经各自唯一格式化函数（admin `yuanText*`、双 mp `fmtMoney`），且有门禁 `check:money-yuan-prefix` 拦「模板写 ¥ 又调自带 ¥ 的函数」与「剥符号手术」。本轮商户端财务 6 页读数逐笔对平见 R09 M 批 4 |
| 时间/时区 | 同一 `Instant` 在三端渲染是否同刻 | ✅ **单源**。admin 组件直接 `import { formatDateTime } from '@aicabinet/shared-uni/format'`，该实现走 `shanghaiClock()`（`format.ts:45` 注释「中国无夏令时，UTC+8 固定」，用 `getUTC*` 在偏移后的时间戳上取值）⇒ 不依赖浏览器 locale。🟡 残留面：admin 仍有 **8 处 `toLocaleString*`**（未逐一判定是数字还是时间用途），列为后续抽查项，本轮不虚报成缺陷 |
| 「缺货」数（R9 留下的首查项） | 商户端补货任务页「6 项缺货」vs 备货单「待补 1 种」 | ✅ **查证后不领号**：`/api/v2/merchant/replenishment/shortage-summary` 对货道柜返回 `slots.size()`（货道数）、对开门柜返回「待补商品数」，而 `replenishment.vue:88` 有显式注释「「项」兼容两种柜型：有货道柜=缺货货道数，开门柜=待补商品数（CB-076）」⇒ 已拍板的刻意兼容，非缺陷。🟡 可选优化（不擅改，需产品判）：按柜型把量词写成「6 个货道缺货 / 2 种商品待补」。另：该端点控制器签名只取 `userId`，**任何 `?deviceId=` 都会被静默忽略**（实测带/不带返回逐字节相同）⇒ 前端未传该参数，无实际影响，但落门禁时别再引入带误导性的查询串 |

## 10.2 权限闭环三方对账（本轮主产出）

脚本：`scripts/audit-perm-closure.mjs`（psql 走 `execFileSync` 参数数组，绕开 Windows 引号拆分）。
三方集合：A＝后端 `@RequiresPermissions` 字面量 **162** 码；B＝`ops_permission` **241** 行；C＝前端引用 **234** 码（71 个来自 `menu.ts`/`merchant-nav.ts`，其余为 `.vue/.ts` 散引，按 `['"\`]` 字面量扫）。

| 方向 | 语义 | 结果 |
|------|------|------|
| **A∖B** 后端有码、表无行 | `hasPerm` 恒 false（除超管通配）⇒ 功能对任何角色不可达 | **0 条** ✅ |
| **C∩B 且 status≠ACTIVE** | 过 `hasPerm` 却卡 `isNavMenuActive` ⇒ 整页 403（D28-B15 栽过的形态，正是既有 `check:menu-permissions`/`check:frontend-perm-codes` 都没覆盖的反向） | **0 条** ✅ |
| **C∖B** 前端导航引用、表无行 | 菜单显示但无人能进 | **0 条** ✅ |
| **B∖(A∪C) ACTIVE** 两端零引用 | 幽灵权限码（角色树可勾、勾了不生效） | **20 条候选**，逐一复核后 **4 条为真** ⇒ 领 **MA-036** |

20→4 的收敛过程（每一步都留了判据，防止把脚本盲区当缺陷）：
1. `ops:nav:*`（7 个）与 `ops` / `merchant` 两个 realm 根节点：结构性/动态组合，MA-021 已写明「判据不同刻意保留」⇒ 排除。
2. `ops:operlog`、`ops:pricing:view`、`ops:recognition-demo:view`、`ops:sku:demo`：前者 MA-021 明文保留；后两者只出现在 `check-frontend-perm-codes.mjs` / `check-menu-permissions.mjs` 两个门禁脚本的白名单里（= 已被登记的遗留码，非新账）。
3. `ops:audit:recent`：**脚本假阳性**——它在 `AdminDashboardController` / `OpsAuditQueryService` 里以非注解形式被使用，而我的 A 集只扫 `@RequiresPermissions` 字面量 ⇒ 排除，并把这条写成落门禁时的反例护栏（A 集必须同时扫注解与字符串用法）。
4. 真幽灵 4 个（`git grep` 全仓 0 命中 + 动态拼码模式 `hasPerm(\`${…}\`)` 经查只存在于「读 nav 配置对象」的 4 处，不构成字符串拼接）：
   `merchant:coupon:view`(C，1 角色持有)、`ops:operlog:export`(F，1)、`ops:upload:export`(F，2)、`ops:vision:export`(F，2)。
   修法沿用 MA-021/V421 的 `RETIRED` 口径；本轮**未动数据**（改角色权限树可见性属写面，且需先确认运营没有把它当「预留位」）。

## 10.3 回归复跑（按 `regression-index.md`）

| 命令族 | 结果 |
|--------|------|
| `mvn -pl services/trade-service -am -Dtest='UserBehaviorAnalyticsServiceTest,CompetitiveGapSalesReportTest,DeviceSlotServiceTest,RevenueSplitServiceTest,DataConsistencyServiceTest,DisputeTicketSyncTest,SettlementMemberDiscountOnceTest'` | 退出 **0**；按 XML `<testcase>` 实取 **3/4/13/24/46/7/6 = 103 例**（🔴 不用 surefire `tests="N"`，它对 @Nested 少报） |
| `mvn … verify -Dit.test='SessionKindFilterSqlIT+MemberDiscountLedgerSqlIT'` | **BUILD SUCCESS**，退出 0（真库 Testcontainers，Flyway 从零） |
| `node scripts/pre-push-ci-preflight.mjs` | 退出 **0**（84 门禁 0 失败；含 mp type-check / mp vitest / lint / 迁移门禁 / admin 产物） |

索引里状态为「**无**」的三行本轮状态（诚实记账，不虚报补成钉测）：
- MA-003 召回弹窗文案、R1 大屏窄视口重叠 —— 未补 UI 断言（要动 `:3100` 或另起端口，值守期不碰共享 vite）⇒ 仍为「无」。
- MA-029 consumer 结果页基准=gross —— **被镜像卡住**：`:18080` 镜像不含 MA-029，会员单只能在重建后造 ⇒ 归 R11（那轮要重建镜像并跑会员单全链路）。
- 本轮新增「无」1 行：MA-035 公告详情缺参数文案（判据＝两端详情页出现「请检查网络后重试」即红），待轮末文案批一起做断言。

## 10.4 本轮未覆盖（留给 R11 / 下一会话）

1. ~~`check:perm-closure` 门禁未落~~ ✅ 已落（2026-11-02，提交 `3d0e999e`）：以集成测试 `PermClosureSqlIT` 实现（§10.5），不再单独做静态门禁（B 集只有真库知道）。
2. ~~8 处 admin `toLocale*` 未判用途~~ ✅ 已判完（2026-11-02）：8 处中 **4 处**是带 `{hour12:false, timeZone:'Asia/Shanghai'}` 的时间显示（正确，不动）；另 **4 处**在 `useReplenishmentRoutePlanning.ts` 用 `toLocaleDateString('zh-CN')` 拼**补货路线名**、**未带时区**（跨零点会与平台日期不一致）⇒ 已补 `timeZone:'Asia/Shanghai'`，`vue-tsc` 退出 0。无金额格式化误用。
3. ~~MA-036 的 RETIRED 迁移未做~~ ✅ 已做（`V427`，2026-11-02 接手批）：4 个幽灵 + `ops:operlog` 共 5 行置 RETIRED；判据与「为什么把父行也带上」写在迁移头。dev 事务内试跑 `UPDATE 5` 后回滚。剩：`check:perm-closure` 门禁仍未落。
4. R10 全部结论建立在 `:18080` 旧镜像上 ⇒ **凡涉及 MA-029 新口径的三端读数，必须在 R11 重建镜像后复走一遍**（含会员单在 admin 订单列表/商户端/消费端三处的「会员优惠」行是否一致）。

## 10.5 `PermClosureSqlIT`：权限闭环反向门禁（已实现）

> ✅ **已实现（2026-11-02，提交 `3d0e999e`）**：`services/trade-service/src/test/java/com/aicabinet/trade/integration/PermClosureSqlIT.java`
> （failsafe / CI `integration` job；已登记 `scripts/check-docker-tests-guarded.mjs`）。
> 3 例：① 闭环方向全 0（B∖(A∪C)、A∖ALL、C∖ALL、C∩非ACTIVE、A∩非ACTIVE）；② A 集双扫护栏（`ops:audit:recent` 注解 `value={}` + `requireAnyPermission` 两形态都必须收入）；
> ③ 负向自测（插入 `ops:__negative_probe__:view` 后必须报出，事务回滚）。实测：fresh 库 `tests=3/errors=0/failures=0`；dev 库（v428）四方向全 0（A=186 / C=197 / B=89 / ALL=241，白名单 14 行）。
> 变异验证：把检测函数改成恒返回空后，负向自测按预期变红（`expected: <true> but was: <false>`）。

**为什么不能只做静态门禁**：本门禁的核心是「库里的 ACTIVE 权限码 ⊆ 代码引用集」，B 集（`ops_permission` 行）只有连库才知道；而哥门禁链是无库的静态链。种子里解析 B 集又极脆（三种 INSERT 变体 + 后续 RETIRED 抵扣 + 幂等 ON CONFLICT），半解析会误红。

**落点（推荐）**：新增一个 **IT**（`PermClosureSqlIT`），跑在 CI 的 `integration` job（有 docker + 迁移后的真库）：
1. B 集：`SELECT perm_code, perm_type, status FROM ops_permission`（只取 ACTIVE 且 type ∈ C/M）。
2. A 集：扫 `services/**/*.java` 里 **`@RequiresPermissions` 注解字面量 + `hasPerm|requirePermission|hasAnyPermission` 的字符串实参**（🔴 必须双扫——`ops:audit:recent` 就是非注解用法，只扫注解会把它误判成幽灵）。
3. C 集：扫 `clients/admin-vue/src/config/menu.ts` 的 `perm:` + `clients/**/src/**/*.{ts,vue}` 里散落权限码字面量。
4. 断言：`B ∖ (A ∪ C) = ∅`，白名单只允许三类：`ops:nav:*`（结构目录行）、realm 根（`ops`/`merchant`）、V421/V427 已 RETIRED 的（本就不在 ACTIVE 集）。
5. 负向自测：测试内插入一行假幽灵码（如 `ops:__negative_probe__:view`）后断言检测函数**必须**报出它，再回滚——防止"查询写错 ⇒ 恒真绿"。
6. 加分项（非阻塞）：把两侧扫描抽成 `scripts/lib/perm-scan.mjs`，静态门禁复用 A/C 两集做**正向**检查（已存在），IT 只补反向。

**判据**：真库上三方向（`A∖B`、`C∩B 非 ACTIVE`、`C∖B`）全 0；负向自测必须红；`mvn verify` 该 IT 通过。

## 10.6 `BalanceTransactionDto` 余额字段改可空 —— **已评估后放弃**（2026-11-02）

- 动机：MA-025 之后可见性查询已排除「非余额通道」流水，但 `toDto` 仍把 null 兜成 0，将来若有新通道带 null 快照会显示假的「余额 ¥0.00」。
- 试改结果：DTO 两个字段 `int → Integer` + 原样透传，`BalanceLedgerServiceTest 13/0` 通过；
  **但 `check-openapi-regen-required` 判红**——生成的 schema 里这两个字段本来就是 `?: number`（可选），
  `Integer` 只多一个 `nullable` 语义、生成器不体现 ⇒ 变更集里没有 `generated/openapi.ts`，门禁要求必须有。
- 结论：**撤回**（`git revert`）。理由：客户端本来就要处理 undefined，这改动零收益；要真做，得先让生成器表达 `nullable`，属独立小工程。
- 现状安全：MA-025 的可达性已由可见性查询封住（该类行根本不出现在余额明细里），假 ¥0.00 不会被渲染。
