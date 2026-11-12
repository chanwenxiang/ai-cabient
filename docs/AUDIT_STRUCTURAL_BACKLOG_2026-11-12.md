# 结构化收口清单 —— 从 R1–R22 二十二轮审计里提出的「一次消掉一族」工程项（2026-11-12）

> 口径：本轮**不改代码**，只把 22 轮里反复出现的同族缺陷收敛成**结构性控制**（单源 / 门禁 / 注册表 / 契约测试）。
> 判据：只有「修完之后再也不会以新落点复现」的才算结构化；逐条补洞不算。
> 依据文件：`docs/AUDIT_FOUR_END_2026-11-09.md`、`_R2_`～`_R4_`、`_R11_`～`_R16_`、`_R18_`～`_R22_`、`_OVERNIGHT_FINAL_2026-11-11/12`。

---

## 一、结论：22 轮里真正反复出现的只有 8 族（第一层；其他文档再挖 7 族见 §六）

| 族 | 一句话根因 | 历史条目（已核） | 轮次跨度 |
|---|---|---|---|
| **S1 出站客户端各写各的** | 每个 client 自建 `RestClient`，超时/重试/错误封装无单源 | R20-4、R20-10、R21-1（6 处）、R19 网关零超时、R22-2（短信额度顺序） | R19→R22，4 轮 5 次 |
| **S2 非终态没有推进器** | 状态机有中间态但没有「谁来收口 + 超时阈值 + 兜底告警」的登记 | R18-1（FAILED 不在迟到兜账范围）、R20-1（ABNORMAL 仍记已退）、R20-8（DONE 把 REFUNDED 改回 PAID）、R22-1（PROCESSING 永不推进）、R22-6（CHARGE_PENDING 巡检被开关短路） | R18→R22，4 轮 5 次 |
| **S3 两侧约定各写一份** | 写入格式/解析格式、手动路径/自动路径、阈值、单位、Provider 语义没有同源 | R12-P2-1…P2-4 + R13-MA-174（M01 `|| undefined` × `updateById` 跳 null，75+ 处）、R18-1（15s vs 60s）、R20-9（覆盖赋值 vs 单值读）、R22-1（写空格 / 读 `#`）、R22-3（手动 1–10 层 vs 自动恒 1）、R22-4（Java 上海 vs PG 会话 UTC）、R22-8（微信 fail-fast vs 支付宝空列表） | R2→R22，贯穿 |
| **S4 时间口径没有单源** | 业务日/月窗口在 Java、SQL、cron、镜像四处各定各的 | R21-2（16 文件裸 `LocalDate.now()`）、R22-4（`to_char` UTC 日桶）、R22-10（4 处 `systemDefault()`） | R21→R22 |
| **S5 资金不变量只写在注释里** | 「禁止 DELETE」「必须先冻结再分账」这类不变量没有跨通道强制 | R20-2（未实收即分账）、R20-5（积分重复过期=流水完整性）、R22-5（25 张资金表可物理删除 + 积分禁删不变量失守） | R20→R22 |
| **S6 配置开关连带短路** | 「方法级早退」把不相关的副作用一起关掉 | R22-6（`auto_cancel_hours=0` 关掉资金巡检）、R3/R4 的巡检/告警被开关吞（同族早期形态） | R3→R22 |
| **S7 新表/新端点不登记** | 生命周期与注册表靠人记得补，fail-open | R19-A1 同族（`session-failed-hold-clear` 未登记 `ScheduledTaskRegistry`）、R22-9（遥测表无保留策略） | R19→R22 |
| **S8 修复流程本身不闭环** | 「修一半 / 同族漏扫 / 判据无正向用例」被连续三轮点名；**规则文字已于 2026-11-11 落进 CODE_FIX_CHECKLIST，但缺可执行的覆盖对账** | R2 §6.2、R3 §6.1、R4 §6.1/§6.2/§6.4 | R2→R4 点名，R5 后仍复发 |

⇒ **其余不属结构化对象**：纯文案/样式（已有棘轮与门禁）、一次性业务逻辑错（如 R20-3 券绕活动禁券）、需拍板的口径题（R22 §八-8.4 MATCHED 语义）。把这三类塞进结构化清单只会稀释优先级。

---

## 二、8 族对应的结构化控制（可直接派工）

### S1 → `OutboundClientFactory` 单源 + 「禁裸建」门禁

- **落地形式**：① 现存 `InternalRestClientFactory`（10s/30s）升为唯一入口，再加 `PublicHttpRestClientFactory`（公网 HTTPS，显式 connect/read + 可选下载档）；② 门禁 `check-outbound-client-factory`：全仓出现 `RestClient.create()` / `RestClient.builder().build()` / `new RestTemplate()` / 裸 `WebClient` 即判红，白名单只允许两个工厂自身。
- **消掉**：R20-4、R20-10、R21-1 全 6 处、R19 网关零超时；并让「超时之后怎么办」有唯一处可写（配合 S2）。
- **判据**：故意把某 client 改回 `RestClient.create()` ⇒ 门禁红；`grep -c` 裸建=0。
- **代价**：小。改动集中在 6 个类的构造，行为差异只有「不再可能无限挂」。
- **注意**：`WeChatMiniAppClient`（登录主链）与 `IdentityVerifyClient` 至今仍未修，别把「peer 已改的 3 处」当成全清。

### S2 → 非终态注册表 `PendingStateRegistry` + 完整性门禁

- **落地形式**：一张表（代码内常量类即可）登记每个非终态：`状态 → 推进器类 → 超时阈值 → 兜底告警 code → 是否允许人工收口`。门禁 `check-pending-state-progress`：扫 `PaymentOperation`/`shopping_session`/`cabinet_order`/`recharge_order`/`payment_reconciliation` 等状态字面量里出现的**非终态**，未登记即红；已登记的必须能在源码里找到对应推进器类与 `ops_exception`/`OpsAlertDispatcher` 的 code 字面。
- **消掉**：R18-1、R20-1、R20-8、R22-1、R22-6 的**同类再发**；顺带把「提现 PAYING 已有兜底」这类正面样本变成基线。
- **判据**：新增一个 `"XXX_PENDING"` 状态但不登记 ⇒ 门禁红；删掉某推进器引用 ⇒ 门禁红。
- **代价**：中。需要先把现存非终态枚举全（约 10–15 个），一次性盘点后进入棘轮。
- **这是我认为价值最高的一条**：R18→R22 五次的资金/状态类 P1/P2 全落在「有中间态、没人收口」这同一个形状上。

### S3 → 「双侧同源」三条硬规则

- **3a 格式常量**：跨进程/跨方法传递的字符串结构（reason 尾注、幂等键、批次号、`join/split` 分隔符）必须由常量/构造器单源，禁止写侧手拼 + 读侧手解。门禁 `check-composed-literal-roundtrip`：出现 `lastIndexOf("<分隔>")` / `split("<分隔>")` 而同一分隔符没有对应 writer 的锚点测试 ⇒ 红。
- **3b 阈值/单位成对**：两侧阈值（gateway watchdog vs trade 开门窗口）、单位（元/分、bps/百分比）必须引用同一常量；已有 `check-commission-param-bounds`、`check-no-inline-money-math` 只覆盖了「分→元」方向，**补元→分**（R2026-11-09 §5.2 已点名未做）。
- **3c Provider/通道语义一致**：同一 SPI 的多个实现（`PlatformBillProvider` 的两个 provider、支付渠道 A/B 语义）必须有**一张契约表 + 一组契约测试**（对每个实现跑同一组用例：未配置 / 账单缺失 / 非 200 / 业务 code 失败 ⇒ 各自必须抛还是返回空）。
- **消掉**：M01 家族（已由 `check-mybatis-null-clear` 结构化，本条是它的推广）、R18-1、R20-9、R22-1、R22-3、R22-4、R22-8。
- **判据**：契约测试对「返回空列表」的实现判红（除非契约表显式声明并附理由）。
- **代价**：中。3c 最省事（一个测试基类），3a 需要先定分隔符清单。

### S4 → `TimeWindows` 单源 + 三条门禁 + TZ 双钉一致

- **落地形式**：① `TimeWindows`（`ScheduleZones` 的扩展）提供 `dayRange/monthRange/dayKey(Instant)/sqlDayBucket()`，SQL 侧的 `AT TIME ZONE 'Asia/Shanghai'` 由同一个常量渲染成片段，禁止 mapper XML 裸写；② 门禁（三条）：业务代码禁 `ZoneId.systemDefault()` 与裸 `LocalDate.now()`（现 `check-scheduled-zone` 只管 cron）；mapper XML 里对 `timestamptz` 的 `to_char(`/`date_trunc(` 未带 `at time zone` ⇒ 红；`Dockerfile` 的 `ENV TZ` 与 `-Duser.timezone` 取值必须相等（peer 在制刚加了后者，恰好是这条门禁的前提）；③ R22-4 的账单/对账日桶改造按此单源做。
- **消掉**：R21-2、R22-4、R22-10，以及任何「新页面自己算日窗口」。
- **代价**：小-中。Java 侧改点 6 处 + 1 个工具类；XML 侧改 4 条 SQL。

### S5 → 资金表保护集合与「对账规则」同源

- **落地形式**：① 保护集合由 schema/引用关系推导，不写死清单：`被 DataConsistencyService / GrowthLogArchiveScheduler 引用的表` ∪ `含 balance_after_cents|amount_cents 且被 SUM 校验的表` ⇒ 自动进 `AdminDataManageService` 的删除黑名单（沿用它自己的 `deleteProtectedTables` 递归 CTE 思路，把「谁消费我」也加进去）；② 门禁 `check-ledger-immutability`：`deleteInBatches` 的表名白名单只能含「非账本」表（现在里面还留着永不被调用的 `member_points_log` 分支）；③ `DATA_DELETE` 审计补 before 全值（复用现成的 `selectRowAsText`）；④ 关键不变量（「先冻结毛额再分账」「积分流水不可删」）从注释升级成测试锚点，注释只作解释。
- **消掉**：R22-5 全部三档后果、R20-2/R20-5 的同类再发。
- **代价**：小（黑名单 + 一条门禁 + 审计一行）；**收益直接**：这一族每次付的账都是资金凭证。

### S6 → 「开关只关一个行为」写法约定 + 形态门禁

- **落地形式**：约定：开关判定必须下移到**具体行为**处，禁止在含多个副作用的方法开头 `if (x <= 0) return;`；门禁 `check-config-early-return-scope`：`getInt(CONFIG_X)` 后紧跟早退、而该方法内还有 `alert*/report*/send*/resolve*` 类副作用调用 ⇒ 红。字典 `description` 文案必须与早退范围一致（写「0=关闭」就要说清关掉什么）。
- **消掉**：R22-6 及其同族（催缴腿已经写对，正好当基线样本）。
- **代价**：小。属于「把已经存在的正确写法钉成规则」。

### S7 → 注册表完备性门禁（新表/新任务必须登记）

- **落地形式**：`RetentionRegistry`（每张表的保留策略或显式 `RETAIN_FOREVER + 理由`）+ 门禁校验 `information_schema.tables`（或迁移产物清单）⊆ 注册表；同形态已有先例可抄：`ScheduledTaskRegistry`（R19-A1 就是没登记导致的）、`check-scheduled-task-seed`、`check-audit-gates-wiring`。顺带把遥测类表的清理接进 `GrowthLogArchiveScheduler`（R22-9：4 机 11 天 55,287 行）。
- **消掉**：R22-9、R19-A1 同族、以及「新长一张表没人管」这一整类运维债。
- **代价**：小-中（首次要给 ~179 张表分类，但一次性）。

### S8 → 修复批对账工具 + CODE_FIX_CHECKLIST 三条落地（**最便宜、拖得最久**）

- **落地形式**：① 脚本 scripts/check-fix-batch-coverage.mjs（**待建**）：输入「条目 → 应改文件」表，对 `git show --stat` 交叉核；**既不在修复表、也不在「有意未采纳」清单里的条目 = 红**（这正是我手工在做的范围对账）；② ~~把三条写进 CODE_FIX_CHECKLIST~~ **已于 2026-11-11 落地**（现文档 §「修复流程三条硬约定」第 1/2/3 条即同三条，锚 R3–R5 实证）⇒ 本项**已做，不再列为待办**；R2 §9 门禁候选终态也在同文档拍板（①裸 await 有意不实施、③由既有 mybatis-null-clear 覆盖、⑤部分由 xxl-job-wiring + scheduled-task-seed 覆盖）；③ 真正的缺口是**这三条没有可执行判据**（纯文字约定，靠人遵守）：建议把第 1 条做成「修复批覆盖对账」（即①）、第 2 条做成「判据类改动必须新增一条正向用例」的 diff 级检查（同批改了判据但没有新增/改动测试文件 ⇒ 红）。④ 门禁自身可靠性：每个 check 脚本应配负向夹具——现 `scripts/check-*.mjs` 共 127 个文件，其中 13 个是 `.test.mjs`，即 **114 条门禁只有 13 条带可执行反例**。
- **消掉**：不是某个缺陷族，而是**「每轮都在重新发现同一族的漏网」这个流程成本**。R4 §6.4 已经量化过：同 HEAD 双轮并集比单轮多 4 条 P1。
- **代价**：小。①一个下午；③可分批（先给高价值门禁补夹具）。

---

## 三、建议执行顺序（三批，每批一个可验证收口）

| 批 | 内容 | 为什么先做 | 收口判据 |
|---|---|---|---|
| **第 1 批（1–2 天）** | S8①③（修复批覆盖对账脚本 + 判据类改动的正向用例检查 + 门禁负向夹具）、S1（工厂 + 禁裸建）、S6（开关早退形态） | 都是**纯规则/纯接线**，不碰资金路径；S1 直接清掉 R21 唯一未收口的 P1 | 门禁能红（负向用例）+ `RestClient.create()` 裸建计数=0 |
| **第 2 批（3–5 天）** | S2（非终态注册表）、S5（资金表保护与对账同源 + 删除审计 before）、S4（TimeWindows + 三条时区门禁） | S2 是最高价值族，S4/S5 的改点都很局部且可测 | 非终态登记完备（门禁红于新增未登记态）；25 张暴露表里资金表清零；`to_char` 无 `at time zone` 命中=0 |
| **第 3 批（1 周+）** | S3（双侧同源三规则）、S7（保留注册表 + 遥测清理） | S3 需要先盘点分隔符/阈值清单，S7 要一次性给 179 表分类 | 契约测试覆盖所有 Provider 实现；表未登记即红 |
| **第 4 批（第二层，见 §六）** | S9（写动作归因三必达）、S11（回调入口契约登记）、S15（一致性规则的正负夹具）、S10（取数窗口诚实性） | 四族的落点全在既有门禁的**邻面**（write-perm-codes / ledger-evidence / list-filter-server-side），是在已验证有效的机制上加面，不是新建机制 | 写端点无归因即红；回调未登记即红；每条巡检规则有「合法空态不报红」夹具；截断/窗口回显 total |

---

## 四、明确不做（避免过度结构化）

1. **不为「一次性逻辑错」建机制**：R20-3（券绕活动禁券）、R20-6/R20-7（状态出边范围）这类是业务规则实现问题，补测试即可，抽成机制只会多一层间接。
2. **不新建通用「状态机框架」**：S2 只要一张登记表 + 门禁；引框架会把 22 轮的复杂度变成第 23 轮的陌生面。
3. **不放宽现有判据来换绿灯**：`check-no-inline-money-math` 的方向缺口（元→分）应补规则，而不是把 `Math.round(yuan*100)` 加白名单。
4. **不把拍板题伪装成工程项**：R22 §八-8.4（两侧皆 0 是否算 MATCHED）、余额退款上限、活动期禁券优先级——这些要你给口径，机制只能负责「口径落点后不再漂」。

---

## 五、一句话方案

**把 22 轮里出现 ≥3 次的形状，各换成一个「新代码不接线就编译/门禁过不去」的控制点**：
第一层 8 族＝S1 工厂、S2 非终态注册表、S3 双侧同源、S4 时间单源、S5 资金表保护与对账同源、S6 开关作用域、S7 登记完备性、S8 修复批对账；
第二层（来自其他文档，见 §六）7 族＝S9 写动作归因、S10 取数窗口诚实性、S11 回调入口契约、S12 资金乘数限幅（已结构化，只需扩面）、S13 并发写策略声明、S14 迁移/脚本形态无关、S15 巡检规则正负夹具。

---

## 六、第二层：从 `CODE_FIX_CHECKLIST` / `module-audit/ISSUES`（MA-001…163）/ 其他活文档里再挖的 7 族

> 取样面：`docs/CODE_FIX_CHECKLIST.md`、`docs/module-audit/ISSUES.md`（1449 行、MA-001~MA-163）、
> `docs/AUDIT_LEDGER_2026-10-13.md`、`docs/AUDIT_B1_EXTENDED_FUND_2026-10-16.md`、
> `docs/MIGRATION_SAFETY.md`、`docs/CLUSTER_READINESS.md`、`docs/ADMIN_QUERY_CONVENTIONS.md`。
> 逐条核过**现存门禁**是否已覆盖（114 条 check 门禁 + `TradeArchitectureTest` 架构测试），已覆盖的写成「扩面」而不是新建。

### S9 → 写动作「归因三必达」：权限码 + 审计留痕 + 前后值

* **族**：MA-082（营销域 15 个运营写端点**整体零留痕**）、MA-115（OTA 固件发布/下架零归因，全仓最高危运维动作）、MA-116（数据一致性修复零归因**且证据被覆写**）、MA-148（布尔型资金动作开关在审计总开关关闭时零留痕）、R22-5（`DATA_DELETE` 只记主键不记原值）。
* **现状**：`check-admin-write-perm-codes`（权限码）与 `check-ledger-evidence`（台账锚点真实性）都在，但**没有任何机制要求「写端点必须落审计」**，也没有「审计行 append-only」的约束。
* **手段**：① 一个写侧原语（权限校验 + `appendLog` + before/after），新写端点只能经它；② 门禁：带 `@RequiresPermissions` 的 `POST/PUT/PATCH/DELETE` 方法体必须调用该原语，豁免须进带理由的 allowlist（棘轮双向，仿 `check-commission-param-bounds` 的「少命中也红」）；③ 审计表禁止 UPDATE 既有行（MA-116 的「证据覆写」）。
* **判据**：新写一个不记审计的端点 ⇒ 红；改一行审计历史 ⇒ 红。
* **代价**：中（要先把存量端点入棘轮基线）。

### S10 → 取数窗口/截断的**诚实性**（回显口径 + 参数可达）

* **族**：MA-002（沉睡名单静默截断 20 条，导出与弹窗按截断值显示）、MA-009（设备详情关联单据截断 8 条、无总数无段级查询）、MA-010（后端写死 14 天窗口且不可调不交代）、MA-014（账务明细默认 7 天且不交代、空态误导）、MA-001（KPI 随窗口漂移）。
* **现状**：`check-list-filter-server-side` 只治「本页客户端 `.filter(`」这一形态；上面五条是**另一种形态**——窗口/上限写死且不回显。
* **手段**：列表/明细类 DTO 固定携带 `total` + `appliedLimit` + `windowLabel`（或 `truncated` 布尔）；门禁：方法里出现硬编码天数/条数常量（`LIMIT n`、`minusDays(n)` 的字面量）而响应无对应回显字段 ⇒ 红；文案模板必须消费 `windowLabel`，禁止前端自己写「近 {{days}} 天」（MA-003 即文案与实际窗口不符）。
* **判据**：把某页窗口从 7 天改成 30 天但不同步文案 ⇒ 红；截断不加 `truncated` ⇒ 红。
* **代价**：中（DTO 面较广，可按页分批入棘轮）。

### S11 → 外部回调入口的**契约登记**（鉴权模式 + 必需头 + 错误映射）

* **族**：MA-047（支付分回调漏免登录白名单）、MA-065（缺 `Wechatpay-*` 请求头冒 500，是 MA-047 落地后**活栈新挖**）、R22 子代理面的「验签失败语义」、R20-8（回调把 REFUNDED 改回 PAID）。
* **现状**：验签逻辑本身有（且 fail-closed），但**没有一张「回调入口清单」承载「该路径应免登录 / 需要哪些头 / 失败应回什么码」的契约**。所以漏配白名单、错误映射成 500 这类只能靠上线后被打到才发现。
* **手段**：`CallbackEndpointRegistry`（path + 鉴权模式 + 必需头 + 期望状态码 + 验签器），门禁双向校验：①代码里带 `notify/callback` 的映射方法必须登记；②登记的 path 必须真实存在（防文档漂移，同 `check-ledger-evidence` 思路）；③新增集成测试用「缺头/错头/重放/乱序」四件套打每个登记项。
* **判据**：新加一个回调不登记 ⇒ 红；把验签失败映射成 500 ⇒ 集成测试红。
* **代价**：小-中（现有回调约 5–7 个，一次性列全）。

### S12 → 资金乘数限幅（**已结构化，只需扩面**，正例）

* **现状**：`check-commission-param-bounds` + `CommissionParamBounds` 已经把 MA-069（线长佣金 bps）、MA-090（机器账单 share_bps）、MA-100（线路成员佣金参数）三个同族收口，且门禁是**棘轮双向**（命中数变少也红，防止闸被拆后门禁跟着失效）——这是全仓最成熟的范式，S9/S10/S11 的 allowlist 设计应当照它抄。
* **待扩面**：同一形态还散落在别的乘数上——提现手续费率（6‰ 可被商户覆盖）、`balance.refund.max_cents`、`checkout.preauth_cents`、券门槛/折扣比例。这些现在靠 `system_config` 手填，没有统一限幅。
* **手段**：把这些列并进 `CommissionParamBounds` 的受管列清单（门禁判据不变），并禁「裸 Map/裸实体直落资金乘数」。
* **代价**：小（改清单 + 若干写侧接原语）。

### S13 → 并发写策略必须**显式声明**（谨慎：误报面最大的族）

* **族**：MA-017（短信验证码校验非原子，可双花）、MA-044（活动限购 check-then-act 并发穿透）、R18-2（批次账读改写、`DeviceSkuLot` 无 version，**仍未修**）、MA-160（组合腿重复扣，P1）、R22-2（先烧额度后发送，同族语义倒置）、MA-045（部分退款渠道成功、本地收尾失败后 409 死路）。
* **诚实边界**：「select 后无 CAS 即 save」的全仓形态扫描**误报面极大**（很多读改写本来就安全），做成硬门禁会把人训练成加白名单。
* **建议形态**：不做全量扫描，做**新增面强制声明**——凡改到资金/库存/额度写路径的 diff，必须命中三选一：条件 UPDATE / 乐观锁 version / 显式锁 key（并附 `// CONCURRENCY: <策略>` 锚点）；门禁只校验**被本次 diff 触碰的方法**（diff 级，不是全仓级）。R18-2 的 lot version 落地是这条的前提。
* **代价**：中；收益：把「每轮新挖一条并发」这条最贵的线截住。

### S14 → 迁移与清理脚本的**形态无关性**（空库 / 已有库都要成立）

* **族**：MA-042（清库脚本仍 DELETE 两张已 DROP 的表）、MA-048（V431 删表守卫是**环境依赖**的）、R19 的 P0（`apps.yml` YAML 损坏阻断部署＝同类：部署链前置未验证）。
* **现状**：`check-flyway-seed-separation`、`check-deploy-compose-chain`、`check-compose-*` 已在，但「脚本/迁移是否在新空库与已有库两种形态都成立」仍靠人跑。
* **手段**：① 清库/校验脚本的表清单必须由 `information_schema` 推导（不再手写名单，沿用 `AdminDataManageService.deleteProtectedTables` 的「由 schema 推导」教训）；② 门禁：迁移/脚本里出现的表名必须存在于**声明集**（已 DROP 的表出现在脚本里即红）；③ CI 里保留一条「V1→head 严格升序 + 全新空库」实跑（已实证过，需固化成门禁而不是靠人）。
* **代价**：小（判据现成，缺接线）。

### S15 → 巡检/对账规则必须有**正负两条夹具**

* **族**：MA-015（一致性巡检对「无批次历史订单」误报 `MISSING_SALE`）、MA-016（「已结案但无关联订单」是否一律算不一致＝待拍板）、R22-8（两侧皆 0 记 MATCHED＝假绿；支付宝空账单静默＝假差异）、GrowthLogArchiveScheduler 曾「100% 失败且无人发现」。
* **手段**：每条 `DataConsistencyService` / 巡检规则登记为对象（规则码 + 前提 + 合法空态 + 真异常样例），并强制两夹具：**合法空态必须不报红**（治假阳）+ **构造真异常必须报红**（治假阴）；规则的「前提」写在规则对象里而不是注释。
* **判据**：新增规则没有正负夹具 ⇒ 红；把某规则前提改掉但夹具没跟上 ⇒ 红。
* **代价**：小（一条规则一对夹具，可随修复批补）。

---

## 七、已结构化正例（写下来是为了**防止重复提案**）

| 机制 | 收口的族 | 形态 |
|---|---|---|
| `check-mybatis-null-clear` | M01 清列家族（R12/R13，75+ 处） | 棘轮 + 写法判据；CODE_FIX_CHECKLIST §R2§9 明确「无需再新建」 |
| `check-commission-param-bounds` | MA-069 / MA-090 / MA-100 资金乘数 | 单源原语 + allowlist **双向棘轮**（最值得抄的设计） |
| `TradeArchitectureTest` | 远程调用包进事务、`@Scheduled` 未 `tryBegin` | 架构测试（`AllowTransactionalRemote` 白名单 + `scheduledMustCallTryBegin`） |
| `check-seq-success-path-guard` | 列表加载序号/成功路径守卫 | 门禁（R12-P1-1 曾指出只扫 admin，已扩三端） |
| `check-list-filter-server-side` | 本页客户端过滤致总数谎报 | 棘轮（注意：与 S10 是**两种形态**，别当成已覆盖） |
| `check-scheduled-task-trybegin` / `-seed` / `check-xxl-job-wiring` | 任务登记/cron 配对/双口径 | 双向配对校验（R19-A1 类漏登记的既有解法） |
| `check-ledger-evidence` | 台账报假账 | 文档锚点真实性（门禁脚本名、仓库路径、迁移文件名必须真实存在） |
| `check-audit-gates-wiring` | 门禁写了没接线 | 门禁接线完备性（**S8 的负向夹具缺口的现成挂载点**） |
| CODE_FIX_CHECKLIST §修复流程三条硬约定 | R2/R3/R4 三点的流程族 | **文字已落（2026-11-11），可执行判据未落**＝S8 的真实剩余量 |
