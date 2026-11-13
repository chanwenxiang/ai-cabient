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
- **评审注记（2026-11-12 复核会话）**：判据落点改**枚举推导**、不做全码字面量扫描——非终态的权威定义在状态枚举类里（有限集），字面量会散布在注释/测试夹具/日志/前端文案里，正是 §八 第 3 问要拦的「误报面大」。门禁形态：① 每个状态枚举常量 ∉ TERMINAL 显式白名单 ⇒ 必须在 Registry 有登记项；② 登记项的推进器类名与告警 code 必须源码可解析（锚点存在性，仿 `check-ledger-evidence`）；③ 字面量全扫降级为每次审计的人工辅助，不进门禁。此改不减弱 fail-closed，代价从「中」降「小」。
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
- **代价**：小-中。Java 侧改点 6 处 + 1 个工具类；XML 侧改 4 条 SQL。**（实测注记：改点勘测后，真正的「业务日窗口」点只有 1 处（AdRevenueSyncService）；预估的 6 处里其余是保质期/货架期算术，不属窗口语义，保持 S4② 棘轮钉住不强行单源化——见 §九 S4③。）**
- **评审注记（2026-11-12 复核会话）**：本条与 **R19-17 拍板正面相撞**，落地前必须写明顺从关系——R19-17（2026-11-12）已拍板「60 处裸 `LocalDate.now()` 不动：环境固化优先于大规模改造」，且 JVM 层 `-Duser.timezone` 兜底已落。因此拆三步：① `ENV TZ == -Duser.timezone` 相等校验先落（与拍板无冲突、判据精确、一行门禁）；② 「业务代码禁裸 `LocalDate.now()`」做**增量棘轮**（基线=当前 60 处，只拦新增），不得做成全量硬门禁；③ `TimeWindows` 业务日窗口单源与拍板不冲突，照做。不写明这条，未来实现者会重新诉讼 R19-17。

### S5 → 资金表保护集合与「对账规则」同源

- **落地形式**：① 保护集合由 schema/引用关系推导，不写死清单：`被 DataConsistencyService / GrowthLogArchiveScheduler 引用的表` ∪ `含 balance_after_cents|amount_cents 且被 SUM 校验的表` ⇒ 自动进 `AdminDataManageService` 的删除黑名单（沿用它自己的 `deleteProtectedTables` 递归 CTE 思路，把「谁消费我」也加进去）；② 门禁 `check-ledger-immutability`：`deleteInBatches` 的表名白名单只能含「非账本」表（现在里面还留着永不被调用的 `member_points_log` 分支）；③ `DATA_DELETE` 审计补 before 全值（复用现成的 `selectRowAsText`）；④ 关键不变量（「先冻结毛额再分账」「积分流水不可删」）从注释升级成测试锚点，注释只作解释。
- **消掉**：R22-5 全部三档后果、R20-2/R20-5 的同类再发。
- **代价**：小（黑名单 + 一条门禁 + 审计一行）；**收益直接**：这一族每次付的账都是资金凭证。

### S6 → 「开关只关一个行为」写法约定 + 形态门禁

- **落地形式**：约定：开关判定必须下移到**具体行为**处，禁止在含多个副作用的方法开头 `if (x <= 0) return;`；门禁 `check-config-early-return-scope`：`getInt(CONFIG_X)` 后紧跟早退、而该方法内还有 `alert*/report*/send*/resolve*` 类副作用调用 ⇒ 红。字典 `description` 文案必须与早退范围一致（写「0=关闭」就要说清关掉什么）。
- **消掉**：R22-6 及其同族（催缴腿已经写对，正好当基线样本）。
- **代价**：小。属于「把已经存在的正确写法钉成规则」。
- **评审注记（2026-11-12 复核会话）**：方法体抽取直接复用铁律 87 已踩平的两个坑——① `extractBracedBodies` 会把 `if (!gate(x)) {` 的**调用点**当方法体 ⇒ 定义体判据用 `some` 不用 `every`；② `includes('xxx(')` 会被 `if (false && xxx(` 绕过 ⇒ 判据钉 `if\s*\(\s*` 形态。两处修法现成在 `check-replenishment-checkin-contract` 规则 10/11（2026-11-12 批 A 刚修），照抄即可，别重新踩。

### S7 → 注册表完备性门禁（新表/新任务必须登记）

- **落地形式**：`RetentionRegistry`（每张表的保留策略或显式 `RETAIN_FOREVER + 理由`）+ 门禁校验 `information_schema.tables`（或迁移产物清单）⊆ 注册表；同形态已有先例可抄：`ScheduledTaskRegistry`（R19-A1 就是没登记导致的）、`check-scheduled-task-seed`、`check-audit-gates-wiring`。顺带把遥测类表的清理接进 `GrowthLogArchiveScheduler`（R22-9：4 机 11 天 55,287 行）。
- **消掉**：R22-9、R19-A1 同族、以及「新长一张表没人管」这一整类运维债。
- **代价**：小-中（首次要给 ~179 张表分类，但一次性）。

### S8 → 修复批对账工具 + CODE_FIX_CHECKLIST 三条落地（**最便宜、拖得最久**）

- **落地形式**：① 脚本 ~~scripts/check-fix-batch-coverage.mjs（**待建**）~~ **已于 2026-11-12 晚落地**（用户拍板映射表走**逐批手工登记**：修复批提交时登记「条目→应改文件」，门禁对 `git show --name-only` 交叉核；覆盖对账平 items+notAdopted=totalItems；cutoff 后 fix/perf 提交必须已登记或带理由豁免；存量不回填——见 §九）：② ~~把三条写进 CODE_FIX_CHECKLIST~~ **已于 2026-11-11 落地**（现文档 §「修复流程三条硬约定」第 1/2/3 条即同三条，锚 R3–R5 实证）⇒ 本项**已做，不再列为待办**；R2 §9 门禁候选终态也在同文档拍板（①裸 await 有意不实施、③由既有 mybatis-null-clear 覆盖、⑤部分由 xxl-job-wiring + scheduled-task-seed 覆盖）；③ ~~真正的缺口是**这三条没有可执行判据**~~ **已于 2026-11-12 晚落地**（`check-gate-test-coupling`，见 §九）；④ 门禁自身可靠性：每个 check 脚本应配负向夹具——现 `scripts/check-*.mjs` 共 130 个文件，其中 14 个是 `.test.mjs`，即 **116 条门禁只有 14 条带可执行反例**（2026-11-12 实测；这个分母会随新门禁漂移，引用前先 `ls scripts/check-*.mjs | wc -l` 重测）。**评审注记（2026-11-12 复核会话）**：「先给高价值门禁补夹具」靠人记，直接做成**接线级棘轮**——`check-audit-gates-wiring` 加一条：**新增** check-*.mjs（切线日期后）必须有同名 `.test.mjs`，存量 116 条 grandfather 进 allowlist 且 `.test.mjs` 计数只许涨不许跌。约 1 小时，把 14/130 从「人记」变「机制」，正是 §八「成对才是常态」用到本档自己身上。⇒ **④ 也已于 2026-11-12 晚落地**（`gate-fixture-registry.json` 棘轮，见 §九）。
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

> **评审注记（2026-11-12 复核会话，批次结构不变）**：建议把 **S5 拆两半**——S5-lite（`deleteInBatches` 表名白名单收紧 + `DATA_DELETE` 审计补 before 值，均为一行级改动、不碰行为）提前进第 1 批（符合第 1 批「纯规则/纯接线」精神；资金表物理删除这扇门每多开一天多一天风险），推导式黑名单与契约测试留在第 2 批。是否采纳由用户拍板。

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
* **评审注记（2026-11-12 复核会话）**：本条有**拍板前置依赖**——「合法空态」的定义依赖 R22 §八-8.4（两侧皆 0 是否算 MATCHED）的口径拍板；该题未拍板前 S15 只能先落机制框架（规则对象 + 双夹具要求），判据本身分批跟进，别把它当可独立开工项排期。

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

---

## 八、结构化 vs 门禁：判别规则（写代码前先问这四句）

两者不在同一层：**结构化改变「写错的代码还能不能写出来」，门禁改变「写出来了能不能合进去」。**

| 维度 | 结构化（原语/工厂/单源/注册表） | 门禁（check-*.mjs / 架构测试） |
|---|---|---|
| 生效时机 | 写代码那一刻（IDE 里只有那个方法可调） | 提交之后（红一次才学会） |
| 能否被绕过 | 要显式改依赖，评审看得见 | 加白名单/改判据即绕过，**且绕过不留痕** |
| 失败模式 | 抽错抽象 ⇒ 后人加第二个原语，多一套真相 | 存量太大 ⇒ 白名单越滚越长，最后「合法例外」掩盖缺陷本体 |
| 成本形态 | 一次改造 + 永久降低自由度 | 一次脚本 + 持续维护判据与棘轮 |

四条问句（按顺序）：

1. **正确写法能不能收敛成一个 API？** 能 ⇒ 先做原语（S1/S4/S5/S9）。不能（要做的是「别漏登记」）⇒ 只能注册表 + 完备性门禁（S2/S7/S11）。
2. **存量有多少？** 几处 ⇒ 直接改；上百处（M01 家族 75+、裸色 1000+）⇒ **棘轮门禁先封增量**，再按批换原语。反序会卡死在存量里。
3. **误报面有多大？** 判据可精确（裸 `RestClient.create()`、`to_char` 无时区）⇒ 硬门禁；判据需业务判断（「select 后无 CAS」）⇒ **不做全仓扫描**，改 diff 级声明或走结构化（S13 即按此降级）。
4. **绕过代价与后果是否对等？** 后果是资金/状态错乱（S2/S5/S9）⇒ 必须结构化，门禁只当回归锚；后果是可运维性/风格 ⇒ 门禁够用，别为它引抽象。

**成对才是常态**：工厂 + 禁裸建、原语 + 未调用即红、注册表 + 未登记即红。只做门禁会被白名单吃掉，只做原语会被新代码绕过。

**门禁自身两条硬要求**（否则等于没做）：

- **双向棘轮**：命中数**变少也判红**。只拦「新增违规」的门禁，在有人把闸门拆成空壳时会跟着变绿——这正是 114 条门禁里只有 13 条带反例所造成的后果。参照物：`check-commission-param-bounds`。
- **必须有能红的负向用例**：`check-*.test.mjs` 用夹具证明「故意写错就会红」，并证明合法形态不误报。参照物：`check-outbound-client-factory.test.mjs`（本次新增，32 项断言）。

## 九、落地记录

### S1 出站客户端工厂（2026-11-12，第一批）

**做了什么**

- 新增 [OutboundHttp.java](services/common/common-core/src/main/java/com/aicabinet/common/http/OutboundHttp.java)：出站 RestClient 的唯一构造点，档位具名（`publicApi` 3s/10s、`publicApiBuffered` 微信 Content-Length 档、`internalRpc` 10s/30s、`bulkDownload` 3s/60s、`barcodeLookup` 3s/8s、`callbackThreadRpc` 2s/5s）；`builder(int,int)` 对非正数**直接抛**（0/负数在 Spring 侧语义是无限等待，即本族事故形态）。
  告警投递是个例外：`OpsAlertDispatcher` 必须沿用 Boot 注入的 `RestClient.Builder`（别丢全局定制），所以它用原语 `factory(CONNECT_ALERT_MS, READ_ALERT_MS)` 而不是档位方法——**没为此造一个用不上的 profile**，第一次写完时我加了 `alertDelivery()`，复核调用面后删掉了。
- 迁移 13 个构造点到工厂：`WeChatPayV3Client`（含账单下载档）、`AlipayOpenApiClient`、`AgreementChargeClient`、`WeChatMiniAppClient`、`WeChatPublisherStatClient`、`WeChatWebOAuthClient`、`IdentityVerifyClient`、`JiangyiModelSyncService`、`OpsAlertDispatcher`、`BarcodeLookupClient`、`InternalRestClientFactory`（降级为语义入口）、`TradeInternalClient`、device `TradeServiceClient`。
- 新增门禁 [check-outbound-client-factory.mjs](scripts/check-outbound-client-factory.mjs) + 基线 [outbound-client-factory-allowlist.json](scripts/outbound-client-factory-allowlist.json) + 负向用例 [check-outbound-client-factory.test.mjs](scripts/check-outbound-client-factory.test.mjs)；已接进 `check:audit-gates` 聚合链。
  判据五条：① 裸 `RestClient.create()` 零容忍；② `RestClient.builder()` / `new Simple…Factory(` / `new Buffering…Factory(` 只许出现在 OutboundHttp 且**命中数与基线严格相等**；③ 闸门本体缺 `setConnectTimeout`/`setReadTimeout`/`requirePositive` 任一即红；④ JDK 栈专项（见下）；⑤ 裸 `HttpURLConnection` 专项（见下）。扫描面只含 `src/main/java`，扫描根缺失判红（不写「无输入即跳过」）。

**顺带挖到的新事实（原 R21 清单之外）**

- `WeChatPublisherStatClient:81` 与 `WeChatWebOAuthClient:34` 此前是裸 `RestClient.create()`——**不在 R20-4/R21-1 任何一次点名里**。这两处的缺陷只有「零超时」一个面：二者都只发 GET、无请求体，所以**不适用**微信系 `Content-Length` 那档问题（`WeChatMiniAppClient` 才需要缓冲档，它 POST 手机号/订阅消息）。迁移时先误给了缓冲档、复核请求形态后改回 `publicApi()`——记下来是因为这正是「顺手对齐同类文件」会犯的错：同是微信 client ≠ 同是需要缓冲。
- 这两处是本次为写门禁做全量清点时扫出来的，正好实证了本族的核心判断：**逐处补 setTimeout 不是收口，因为每轮的清单本身就可能不全**。

**第二条出站栈也收进来了（否则「超时已单源」是假话）**

清点时发现本仓除 RestClient 外还有 **4 处 `java.net.http.HttpClient`**，完全在 RestClient 判据之外：`AmapGeocodeService`（高德地理编码）、`DevOpsHubService`（GitHub/Hub）、`MerchantAiInsightService`（本机 Ollama）、`XxlJobWiringSelfCheck`（接线自检）。

- 新增 `OutboundHttp.jdk(connectMillis)` 作为 JDK 栈唯一构造点（同样拒绝非正数），4 处全部改走它，重定向策略留给调用方（XXL 自检刻意 `NEVER`：反代会把 404 伪装成「302 → 登录页 → 200 + HTML」，跟随重定向就把「配错」看成「通」）。
- **顺带修掉一个真实缺陷**：`MerchantAiInsightService` 原先**每次生成建议都 `HttpClient.newBuilder()` 新建一个客户端**——每个 HttpClient 自带 selector/executor 线程，不 close 只能等 GC。现改为 `final` 字段复用，并把这条判据固化成门禁第 ④ 条（`OutboundHttp.jdk(` 必须落在 final 字段/常量初始化上，写在方法体内即红）。
- 门禁基线随之从 4 个受管构造点变成 5 个（`builder` × 2、`simpleFactory`、`bufferingFactory`、`jdkClient`），`HttpClient.newHttpClient()`（等价零超时构造）单独判红。

**第三条出站栈也收进来了（第 ⑤ 条判据，2026-11-12 收尾）**

全量清点确认裸 `HttpURLConnection` 只有短信发送两处（[AliyunSmsSender.java:226](services/trade-service/src/main/java/com/aicabinet/trade/sms/AliyunSmsSender.java) / [WebhookSmsSender.java:47](services/trade-service/src/main/java/com/aicabinet/trade/sms/WebhookSmsSender.java)），两处现状已合规（connect/read 双设 10s、`setFixedLengthStreamingMode` 带 Content-Length、4xx/5xx 也读响应体——H14：流控/关键词拒发的原因在 body 里，只看状态码会把拒发当成功）。**代码一行未动**（不改写走 RestClient：收益为零且落在短信登录主链上），只纳管判据：出现 `openConnection(` 的文件必须同时出现 `setConnectTimeout(` 与 `setReadTimeout(`，缺哪行点哪行（只设 connect 时响应阶段仍是无限等待，正是本族最常见事故形态）；两处入 allowlist 基线（`urlConnection` ×1），双向棘轮——删掉任一 setTimeout、同文件新增一个 `openConnection(`、或迁走不更新基线，都红。负向夹具三态：只设 connect / 双缺 / 注释里写 openConnection 不误报。
清点同轮确认全仓**没有第四条栈**：`new RestTemplate(` / `WebClient.create` / OkHttp / Apache `HttpClients` 在 `src/main/java` 零命中（2026-11-12 grep 实测）——门禁暂不拦「将来新出现的第四条栈」，记为已知边界；哪天有人引入，本轮的判据模板三分钟就能加一行。

**验证**

- 编译：`mvn -o -pl common-core,trade-service,device-service,jiangyi-gateway compile` ⇒ `MVN_EXIT=0`。
- 定向单测：`-Dtest=MerchantAiInsightServiceTest,XxlJobWiringSelfCheckTest` ⇒ BUILD SUCCESS（1 + 16 例）。
  ⚠️ 诚实标注：`AmapGeocodeService` 与 `DevOpsHubService` **没有任何测试引用**（全仓 grep 0 命中），这两处本次只有编译与静态判据保障。
- 门禁：`node scripts/check-outbound-client-factory.mjs` ⇒ OK，扫 1409 个 .java，受管构造点 7 处全在基线内（OutboundHttp 5 + 第三条栈短信两处各 1）。
- 负向用例：32 项断言全过（裸建、自拼工厂、注释不误报、双向棘轮少/多命中、第二条栈方法体内新建、闸门本体丢 connectTimeout 或改用 newHttpClient、第三条栈 connect/read 缺一行即点行）。
- 接线门禁：`check-audit-gates-wiring` ⇒ 130 个 check-*.mjs 全部已接线。
- 全量单测：第一次跑被 peer 并发构建污染（日志里 `ClassNotFoundException: DeviceNameSupport` 成片，属撕裂 class，非真实缺陷）。**复跑结果至今未回填**（本行原文承诺「结果见下一行」，该行从未落盘）——推送前以 pre-push 预检 + CI 全量 `mvn test` 实跑为准；在此之前不得引用「全量单测绿」这一说法。
- 独立复核编译（收尾轮补做，2026-11-12）：`mvn -o -q -pl services/common/common-core,services/trade-service,services/device-service,services/jiangyi-gateway compile`（仓根）⇒ `MVN_EXIT=0`。⚠️ 勘误：本节上文记录的编译命令是模块名形态（`-pl common-core,…`），该形态在仓根或 `services/` 直跑均报 `Could not find the selected project in the reactor`，**不可复现**；可复现形态以上面这条仓根路径版为准（另踩一次实证：`| tail` 会吃掉 mvn 退出码，判定必须直接取 `$?`）。

### S8③④ + S6 落地（2026-11-12 晚，复核会话，第一批余项）

**S8④ 夹具棘轮**（`check-audit-gates-wiring` 新增规则 6 + 登记表 [gate-fixture-registry.json](scripts/gate-fixture-registry.json)）
- 判据：每个非测试 check-*.mjs 必须有可执行负向夹具——sibling `.test.mjs` / fixtureMap 登记的 devops 验证脚本（16 对，目标文件存在性强校验）/ 门禁自带 selfTest（源码**剥注释后**同时出现 `selfTest(` 与 `--self-test`，自动识别 3 个：compose-image-tags / nginx-gateway-parity / seq-success-path-guard）三者其一，否则入 exempt。
- 棘轮双保险：exempt=2026-11-12 切线存量 83 条（`seedExempt` 子集锁定——等量替换也红 + `maxExempt` 上限）；已带夹具的豁免条目=陈旧强制移出。**第一个触发者就是本门禁自己**：给它补 `.test.mjs` 后被红着移出 84→83——棘轮按设计自食其尾。
- 自身 14 项断言负向用例；接线判定扩张：preflight 直调也算可执行接线（锚=真实出现在 preflight 源里，为 S8③ 铺路）。首跑抓到三个真问题：注释里的 selfTest 字样被当覆盖（假阳）、S6 新门禁没接线、本门禁自身 test 未接线。

**S8③ 判据↔夹具同批对账**（[check-gate-test-coupling](scripts/check-gate-test-coupling.mjs)，diff 级，preflight 专用）
- 判据：本批（origin/dev...HEAD + 工作树 + 暂存 + **未跟踪**）改了非测试 check-*.mjs ⇒ 同批必须动 sibling test 或 fixtureMap 指向的验证脚本；**exempt 不免除本条**（没夹具的门禁改判据=先补第一条夹具）。
- 不进 CI 聚合链（CI 里 origin/dev 已含本次推送 ⇒ diff 恒空恒绿），只挂 preflight 第 5 段（与 migration-immutable 同性质同列）；接线=preflight 锚点。
- selfTest 形态豁免（夹具与判据同文件共存，改判据必然连夹具一起改）；**couplingDebt 显式记账**：切线前 9 条存量判据改动欠账（8 条 b8b76dd3 R19 基建批 + 1 条 17fe5fb8 R20批A 规则 10/11——当时全部只有手工负向注入），逐条带 commit 引用入册，`couplingDebtMax` + seed 子集双棘轮只许销不许欠新；debt 门禁一旦补夹具即催销账。
- 实跑即红过 12 条（11 条真存量债 + 1 条误报：git diff 三形态都不含未跟踪文件，漏算本批新增的 test——修法=`ls-files --others --exclude-standard` 并入改动集）；12 项断言负向用例。

**S6 开关作用域**（[check-config-early-return-scope](scripts/check-config-early-return-scope.mjs) + [allowlist](scripts/config-early-return-allowlist.json)）
- 判据：数字零值配置早退（`<= 0` 且分支 return）之后同一方法体仍有 alert/notify/report/dispatch/resolve/createOps/createTicket 类调用 ⇒ 红。配置来源必须走 config 接收者（内联 `.get*(` + config 语境，或 `Config*/Properties*` 接收者赋值）——**裸全大写常量不算配置证据**（实测误报 CAS 行数判据 `rows <= 0`，`STATUS_IN_PROGRESS` 撞了 CONFIG_KEY 启发式）。布尔整体门不命中；else 链消费（else 分支不算被短路）；正样本=R22-6 修后形态钉绿。
- 基线 2 条豁免（DevicePresenceService 温度告警 `maxC<=0` / 自动锁机 `lockAfterMinutes<=0`——`0=关整个功能`，方法内副作用属同一行为腿），`maxEntries=2` 双向棘轮。⚠️ 已知代价写在豁免 why：温度告警调 0 后「恢复自动结案」腿一并停，存量 open 的 TEMP_ABNORMAL 不会自动收口（P3 观察，拆腿待拍板）。
- `send*` 有意不进副作用词表（误报面大，§八 Q3）；14 项断言负向用例；接线：`check:config-early-return-scope`（test 先行）入聚合链尾。

**验证**：六门禁全绿——S6（8608 方法体，豁免 2/2）/ wiring（135 脚本全接线，夹具覆盖 35/118，豁免 83/83）/ coupling（148 文件对账通过）/ runtime-hygiene（135 脚本无同步子进程）/ ledger-evidence / line-endings；prettier 全过。

### S2 非终态注册表 + S4① TZ 双钉（2026-11-12 晚，第 2 批首批，复核会话）

**S2**（[check-pending-state-progress](scripts/check-pending-state-progress.mjs) + [pending-state-registry.json](scripts/pending-state-registry.json)，入聚合链）
- **侦察修正设计**：全仓**只有 ~5 个真状态 enum，30+ 条状态线是「裸 String 字段 + 散落字面量」**——「枚举推导」单层判据不够，实际用**两层**：
  - **宿主完备性**：`domain/` 下含 String 状态字段的实体（机械可枚举）必须登记为 stateLine 或 nonLifecycle——**新长一条状态线不登记即红**；
  - **枚举完备性**：enum 形态状态线的每个常量必须被 terminal/advancers/gaps/uncertain 覆盖——SessionState 新加状态常量不登记即红；
  - 推进器锚点：类文件存在 + 方法名**剥注释后**可寻 + 告警 code 字面量全仓可寻。
- **一次性盘点实测 96 个宿主**（§二 预估 10–15，差 6 倍——台账口径须按实测）：stateLine **65 条**（推进器锚点全部机械核实，含 5 条新线已核实锚点；未核实的 32 条写「推进器未定位」显式欠账，不编造）+ nonLifecycle **43**（配置态 25 + 流水/记录 8 + 既有豁免 10）。**真缺口 13 条**入册，最高危=**支付分单无查单 sweep**（会话异常路径没走完结/取消时 CREATED/DOING/USER_PAYING 永久悬挂，对比退款有 WeChatRefundReconciler），其次补货任务 PENDING 无人签到永久悬挂、提现 APPROVED 无 SLA、OpsException 告警行无自动收口。存疑 12 条（CabinetOrder 字典超集、OrderRevenueSplit.VOIDED 等）待业务拍板，不猜终态。
- 10 项断言负向用例（含注入新枚举常量的完备性、未登记宿主、锚点改名/删除）；seedStateLineCount=65 棘轮（宿主只增不许减）。

**S4①**（[check-tz-pinned](scripts/check-tz-pinned.mjs)，入聚合链）
- 判据：ENTRYPOINT 跑 java 的 Dockerfile 必须 ENV TZ 与 `-Duser.timezone` **双钉、取值相等、钉 Asia/Shanghai**（值棘轮）。
- **首跑抓到 R19-17 的漏网**：Dockerfile.java-service 双钉了，但 **infra/docker/trade-service.Dockerfile（full.yml:186 活构建）、device-service.Dockerfile（apps.yml:235 生产栈）只有 ENV TZ**，**edge/device-simulator 的 java 入口完全没钉**——三处已补齐（JVM 层 + 容器层 tzdata/localtime）。8 项断言。
- 衔接：S4② 裸 `LocalDate.now()` 增量棘轮已落（见下节）；S4③ `TimeWindows` 单源待下批按本档 S4 注记的顺从关系落地。

### S4② + S5（2026-11-12 晚，第 2 批第二批，复核会话）

**S4② 裸 LocalDate.now 增量棘轮**（[check-bare-localdate](scripts/check-bare-localdate.mjs) + [localdate-baseline.json](scripts/localdate-baseline.json)，入聚合链）
- 判据：业务主源码里裸 `LocalDate.now()` 与 `ZoneId.systemDefault()` 必须**逐文件登记、命中数与基线严格相等**（多命中=新增未登记即红；少命中=有人删了旧的却没销基线条目，同样红）。R19-17 拍板「存量不动」不反悔，只封增量；存量 convert 到 `LocalDate.now(ZoneId...)` / TimeWindows 是 S4③ 的活，届时逐批销基线条目（棘轮本身允许销账）。
- **基线实测：裸 44 处/15 文件、systemDefault 17 处/5 文件**——**R19-17 的「60 处」与 R22-10 的「4 处」均已漂移**（这正是要钉住的事实：台账数字必须实测，不可引用历史值）。10 项断言（含注释/字符串不计数、带参 `LocalDate.now(ZoneId...)` 不算裸）。

**S5 资金表删除保护不可退化**（[check-ledger-immutability](scripts/check-ledger-immutability.mjs)，入聚合链）
- **勘误本档 S5 描述**：本档写的「deleteInBatches 白名单里还留着 member_points_log 分支、DATA_DELETE 只记主键」**已被 R22-5（23c57e09）修复**——`deleteProtectedTables()` 已是**schema 推导式**（递归 CTE 推导 CASCADE 链 + RESTRICT/NO ACTION 阻挡，加资金集合），`deleteRow` 的 DATA_DELETE 审计带 `before=` 全值（selectRowAsText）。本门禁把 R22-5 的落地钉成机械判据防退化：①推导标记（WITH RECURSIVE / pg_constraint / confdeltype）不被手写清单替换；②FUNDS_LEDGER_TABLES 与基线 5 表**逐字一致**（删一个/加一个都红，新资金表要么改门禁基线、要么走后缀判定）；③FUNDS_LEDGER_SUFFIXES 6 后缀一致；④deleteRow 放行闸 + before 审计。10 项断言（含 Set.of 集合抽取器自检、推导注入全删才红——pg_constraint 在 SQL 里出现两次）。
- 待续：S5 ① 的「被一致性巡检/归档引用的表自动进保护集」推导扩展未做（现为后缀+点名），列入后续。

### S4③ TimeWindows 单源 + mapper XML 时区门禁（2026-11-12 深夜，第 2 批收官，复核会话）

**mapper XML 时区门禁**（[check-mapper-tz-timezone](scripts/check-mapper-tz-timezone.mjs)，入聚合链）
- 判据（**绝对规则**，非棘轮）：mapper XML 里 `to_char(` / `date_trunc(` 的调用区域必须带 `AT TIME ZONE '<zone>'`，zone 必须 Asia/Shanghai；例外（如参数是无时区语义的 `date` 列）必须逐条进 allowlist 带理由——门禁静态判不了列类型，「例外」必须显式记账。
- 全仓勘测：78 个 XML 中**唯一**含日桶函数的是 `CabinetOrderLineMapper.xml`（R22-4 已全修），Java 侧 `to_char` 零裸用。门禁从此刻起全量约束新增。7 项断言。

**TimeWindows 单源类**（[TimeWindows.java](services/trade-service/src/main/java/com/aicabinet/trade/support/TimeWindows.java) + [TimeWindowsTest](services/trade-service/src/test/java/com/aicabinet/trade/support/TimeWindowsTest.java)，8 项单测全绿）
- API：`now()` / `dayRange(daysBack)` / `monthRange(monthsBack)`（闭区间含今日/本月，非正参数抛）/ `dayKey(Instant)`（上海时区业务日归一）/ `sqlDayBucket()`（`AT TIME ZONE 'Asia/Shanghai'` 片段，由 ScheduleZones.ZONE_ID 渲染，单源）。时区复用 `ScheduleZones.ZONE`，不重复字面量。
- **窗口改点实证**：全仓窗口计算点勘测后，语义明确的「业务日窗口」只有 `AdRevenueSyncService.syncRecentEstimates` 一处——已切到 `TimeWindows.now()`（S4② 基线同步销账 15→14 文件，44→43 处）；其余裸 `LocalDate.now()` 点是**保质期/货架期算术**（非窗口语义），保持 S4② 棘轮钉住，**不强行单源化**（S4③ 的边界就是「窗口与日桶」，算术不属本族）。被影响的 `AdRevenueSyncServiceTest` 11/11 绿。
- 第 2 批至此收官：S2 / S4①②③ / S5 全部落地。

### 第 3 批收官：S7 保留注册表 + S3 双侧同源三件（2026-11-12 深夜，复核会话）

**S7 保留注册表**（[check-retention-registry](scripts/check-retention-registry.mjs) + [retention-registry.json](scripts/retention-registry.json)，入聚合链）
- **一次性盘点实证：迁移 456 个脚本，CREATE 235 张 − DROP 70 张 = 活表 165 张全量入册**（§二 预估 179 与实测 165 有出入，以实测为准）。分类：RETAIN 148（资金/业务/配置，资金表与 S5 保护集合同族）+ **已实现清理策略 2**（notification_log 6 个月、device_temperature_reading 90 天——R22-9 已接入 GrowthLogArchiveScheduler）+ **TBD 15**（流水形态待业务确认的显式欠账，maxTbd=15 棘轮只减）。
- 判据五条：完备性（新表不登记即红）/ 无幽灵（DROP 后留册红）/ **策略必须真实实现**（policy=days/months 的 config 键必须在 Scheduler 源码中真实出现——声明了保留策略却没人执行=假账）/ TBD 棘轮 / **S5 交叉校验**（资金语义表必须 RETAIN，两道闸不许打架）。10 项断言。

**S3-3b 元→分增量棘轮**（[check-yuan-to-cents](scripts/check-yuan-to-cents.mjs) + [yuan-to-cents-baseline.json](scripts/yuan-to-cents-baseline.json)，入聚合链）
- `* 100` 同时混杂元→分与百分比两种语义（§八 Q3 判据模糊）⇒ 不做硬扫，**26 文件 55 处全量登记、双向棘轮封增量**；新代码元→分必须走 yuanToCents。9 项断言。扫描面锚点按实测 412 文件校准（初稿拍 500 假红，教训：锚点必须实测）。

**S3-3a 组合字面量清单**（[check-composed-literal-roundtrip](scripts/check-composed-literal-roundtrip.mjs) + [composed-literal-registry.json](scripts/composed-literal-registry.json)，入聚合链）
- R22-1（充值退款 reason 写侧改 `#orderId`、读侧仍按空格解析 ⇒ 对账静默跳过）的族级控制。勘测：全仓 split/lastIndexOf/String.join 字面量分隔符 **44 组**（external 33：CSV/行/路径/协议，写侧不在仓内；**TBD 11：自定义「；/、」拼接点，写读同源待确认**——显式欠账，maxTbd=11 棘轮只减）。新增解析点不登记即红。8 项断言。
- 「无 writer 即红」的硬判据被否——外部协议解析天然无仓内 writer，硬扫会训练人加白名单（§八 Q3），登记制让每个解析点显式可见。

**S3-3a TBD 11 同源确认轮（2026-11-13）：TBD→0**
- **逐条核写侧拼接去向 + 全仓读侧扫描，11 条全部确认无写读错配，无一需改码**：
  - **DISPLAY_ONLY 10**（新分类：拼接结果仅作报错/日志/模板/回显文案，仓内无解析读侧=无往返=R22-1 风险不成立）——逐条实证去向：ExternalChannelFileParser 表头报错→errors→BAD_REQUEST 回显；ExternalChannelImportService×2（解析 0 行/阻断项报错）；JiangyiLearningLibraryService→log.warn+LearningLibraryView DTO；JiangyiShelfService→ShelfDeviceResult.notes 回显；MerchantNotifyService→微信订阅消息模板参数 thing2（sha256 去重不解析）；SkuJiangyiLinkService×2（Match SUSPECT 原因/CONFLICT 报错）；SkuSceneLimitService 容量超限报错；WarehouseMonthlyCloseService→RestockLineDto 字段 API 回显；WarehouseService→log.warn 日志。
  - **external 1**：SmtpAlertMailer `, `——MIME To 头地址列表=RFC 5322 外部协议格式，读侧为外部邮件服务（写侧在仓内但格式外部，归 external）。
  - **机械证据**：全仓（services+clients，含前端 TS/Vue）grep `split("、")`/`split("；")`/`split(" / ")`/`lastIndexOf("、"|"；")` **零命中**——不存在任何读侧按这些分隔符解析，写读错配无从发生。
- 登记表 11 条分类与证据全部更新，**maxTbd 11→0**；门禁头部词汇补 DISPLAY_ONLY。门禁复跑绿（1413 个 .java、44 组全登记、TBD 0/0），夹具 8 断言全过。
- **欠账台账终态：S 系列全部清零**（S9/S10/S11/S15/S3-3a 均 0，couplingDebt 0，todo 快照 37 类对齐）；仅剩提交时 fix-batch-registry 首次登记（S8①）。

**S3-3c Provider 契约测试**（[PlatformBillProviderContractTest](services/trade-service/src/test/java/com/aicabinet/trade/reconciliation/PlatformBillProviderContractTest.java)，**5/5 绿**）
- WECHAT / ALIPAY 两实现跑同一失败矩阵：①未配置 ⇒ IllegalStateException（fail-closed，禁止静默返回空——空账单=「空对空 MATCHED 假绿」）②download_url 缺失 ⇒ 抛（R22-8 两侧对齐口径钉死）③channel 唯一。Properties 是 record 非 POJO、`execute` 第二参是 Map——两轮编译红后对齐真实签名。
- **第 3 批至此收官：S7 / S3-3a/3b/3c 全部落地。8 族结构化全部完成**（S1–S8），剩余为第二层 7 族（S9–S15，第 4 批）。

### 第 4 批收官：S11 + S9 + S15 落地、S10 显式缓议（2026-11-13，复核会话）

**S11 回调入口契约**（[check-callback-endpoint-contract](scripts/check-callback-endpoint-contract.mjs) + [callback-endpoint-registry.json](scripts/callback-endpoint-registry.json)，入聚合链）
- 8 个外部回调端点全量登记（path+控制器+鉴权模式+白名单锚点+验签器）：微信支付/退款、支付宝、支付分（4 个 SIGNATURE_BYPASS）+ 网关采集回执（ONE_TIME_TOKEN）+ 2 个 dev mock（DEV_ONLY）+ 模型下发回执（DEVICE_TOKEN）。
- 判据五条双向：①新 notify/callback 映射不登记即红；②**MA-047 本体**——SIGNATURE_BYPASS/ONE_TIME_TOKEN 的 path 必须真实出现在对应白名单配置（渠道重投全 401=线上死路）；③**mock 裸奔防线**——DEV_ONLY 必须不在白名单（WebConfig:24 明令）；④设备面不豁免；⑤白名单里悄悄加 notify 路径也要登记（反向）。8 项断言（含 MA-047 删白名单注红、mock 入白名单注红、幽灵白名单路径注红）。
- 商户配置面（prefs/subscribe/wx-bind）经提取器排除规则显式豁免（非回调语义）。

**S9 写动作归因**（[check-write-action-attribution](scripts/check-write-action-attribution.mjs) + [write-attribution-registry.json](scripts/write-attribution-registry.json)，入聚合链）
- **架构勘测修正**：本仓 admin 审计在 controller 或其注入 service 调 `appendLog`（277 调用点），判定=控制器自身或**一层注入可达**含 appendLog——二层可达（service→service2）为已知盲区，方向安全（可达要求显式命中，只可能误判不可达）。
- **98 个写控制器全量判定：可达 62 / 零归因 36**。36 个全部分类登记：CALLBACK 3 / DEV_ONLY 4 / DEVICE_FACING 14（语义豁免带理由）/ **TBD 15=真缺口显式欠账**（OpsWarehouse 19 写、OpsReplenishment 9、OpsPlatformFund 4、**OpsOta=MA-115 仍然开着**、Payment/Session/SessionInternal/VisionInternal/RepairTicket/4 个小 Ops 控制器）。
- 判据四条：可达不退化（62 基线棘轮，拆留痕即红）/ 不可达必登记（新 MA-082 即红）/ TBD 销账（补了留痕必须删条目）/ maxTbd=15 只减。6 项断言。
- **修真缺口本身（给 15 个 TBD 补留痕）是修复批的活**——本批只把欠账显式化并钉住不新增。

**S15 巡检规则夹具（机制框架）**（[check-consistency-rule-fixtures](scripts/check-consistency-rule-fixtures.mjs) + [consistency-rule-registry.json](scripts/consistency-rule-registry.json)，入聚合链）
- DataConsistencyService **24 条巡检规则全量登记**，每条挂 fixtureClass 锚点。实测：**双夹具齐 6 条 / 缺 18 条**（范式=`checkOrderConsistency_consistentRowNotReturnedBySql`/`recordsTrueMismatch` 正负两用例）。
- 判据四条：新 check 方法不登记即红 / fixtureClass 锚点存在且含规则名 / **已齐夹具数只增不减**（seedFixtureOk=6，夹具被删=假绿通道重开）/ 缺夹具数只减（maxMissingFixtures=18）。6 项断言。
- 按评审注记：**只落机制框架**——语义级正负样例断言与 R22 §8.4 拍板（两侧皆 0 是否 MATCHED）联动，拍板前夹具判据到引用计数层；**补 18 条规则的正负夹具是修复批的活**。
- **S15 欠账修复完成（2026-11-13，修复批）**：18 条规则各补正负夹具（共 36 个 @Test，[DataConsistencyServiceTest](services/trade-service/src/test/java/com/aicabinet/trade/service/DataConsistencyServiceTest.java) 现 83 例）——**83/83 全绿**，双夹具齐 **24/24**，`maxMissingFixtures=0` 销账。范式=既有 `checkOrderConsistency` 正负对（`consistentRowNotReturnedBySql`/`recordsTrueMismatch`）：正例空行集 ⇒ 不落 FAIL + SQL 形态锚点钉住；负例构造真违规行 ⇒ save 断言（type/key/expected/actual）。
- **过程记实**：① 生成器模板两次踩坑（`sql.contains` 缺 Java 字符串引号、`assertEquals` 类型常量漏引号）——模板里所有字面量插值必须带引号；② crosslink 是 4 个子检查合集（正例改 `atLeastOnce` + 任取一条 SQL，负例 `getAllValues` 里找 `SALE|O5` 那条）；③ 并发会话期间文件两度被删，用户 WIP 备份在 `/tmp/DCS.backup2.java`（87KB）为权威版——工作树最终 = 备份 + 引号修复；④ 隔离沙箱（GATE_ROOT 范式）验证过引号修复版可编译后，转常规 mvn 定向验证（并发收口后）。

**S10 取数窗口诚实性：显式缓议（判据设计需拍板）**
- 缺口本体确认未变（MA-002/009/010/014：截断不回显 total、窗口写死不交代）。但门禁判据「硬编码窗口无回显字段」误报面大（§八 Q3），且 **DTO 回显字段名（total/appliedLimit/windowLabel/truncated）、落点（哪些 DTO 优先）、分批顺序需产品/前端设计拍板**——拍板后按「Page 类 DTO 固定携带 echo 字段 + 新增硬编码窗口须登记」两段落地。本批不硬上门禁。

### S10 设计拍板落地（2026-11-13，用户授权拍板，复核会话）

**[check-window-limit-honesty](scripts/check-window-limit-honesty.mjs) + [window-limit-registry.json](scripts/window-limit-registry.json)**（入聚合链）
- **拍板内容**：① 分页面 **PageResult 已携带 items/page/size/total=天然诚实豁免**（实测 record 形态）——S10 缺口只在非分页面；② 管两段：**截断点**（mapper `LIMIT N`，N≥2；LIMIT 1=单行锚点按设计豁免，实测 35 处）+ **窗口字面量**（service `minusDays/Months(N)`）；③ 判据=**登记制**（file|值 聚合计数棘轮），「无 total 即红」硬扫被否——消费面语义（批处理 vs 用户列表）静态判不了（§八 Q3）。
- **实测盘点**：截断点 **11 组** + 窗口字面量 **34 组**全登记。分类：窗口段 PARAM_OVERRIDABLE 4（CompetitiveGap/FundBill fromDate 参数可覆盖，签名已实证）/ KPI_FIXED 4（SalesVelocity 文案即口径）/ ARCHIVE 4（S7 保留域）/ **TBD 33**（截断 11 + 窗口 22，消费面待确认的显式欠账，maxTbd=33 只减）。语义细化（回显字段补齐/文案消费 windowLabel）随修复批——登记制先拦增量。
- 9 项断言（含两段提取器、计数双向、陈旧条目、TBD 棘轮）。
- **第二层 15 族终态：落地 6（S9/S10/S11/S15 + S12 已结构化 + 第一层 S1–S8 全部）、S13（diff 级并发声明）与 S14（迁移形态无关）未排批——归 R23 L4 镜头。**

**S10 TBD 33 分类轮（2026-11-13，登记制细化）**
- **逐条按消费面实证定类，TBD 33→0（maxTbd 33→0），登记表全量更新**。分类分布（截断 10 + 窗口 22 在册，promo 条目销账）：
  - **BATCH 12**（内部批消费）：审计扫描（DataChangeLog/DataConsistencyRecord×2）、设备流水与账本/调拨近单（InventoryMovement/WalletLedger/WarehouseTransferOrder，**未见端点直接暴露，登记防复活**）、进件批量查询（MerchantPaymentOnboarding，控制器仅 PUT/POST 复核）、分账重试调度（OrderRevenueSplit LIMIT 20→ProfitSharingRetryScheduler）、网关 trade-no（PaymentOperation）；窗口侧：广告收益/资金演练/佣金冲回/货道临期/月账单/月结调度与月结流程（AdRevenueSync/CommercialFlow/CommissionClawback/DeviceSlot/MachinePayeeBillJob/WarehouseMonthlyClose×2）。
  - **PARAM_OVERRIDABLE 9**：MerchantFinanceService Days(30)/(90)/Months(11)/(23)（`fromDate != null ? fromDate : minusDays(30)` 与 parseYearMonthOrDefault 实证）、ReconciliationService Days(30)×2（from/to 实证）、FundBillService×6、CompetitiveGapService×2（此前已定）。
  - **KPI_FIXED 10**：指标/文案即口径——MerchantAnalytics Days(29)、OpsDeviceAdmin Days(7)（CB-018 ② 注释「近 7 日在线率」）、PurchaseSuggestion Days(7)/(14)、SlaMetrics Days(1)、UserBehaviorAnalytics Days(6/29/30/90)（沉睡 30 天判据），加此前已定的 DeviceAvailabilityKpi/LineManagerKpi/SalesVelocity。
  - **TOP_N 1**：OrderRevenueSplit LIMIT 5——结算概览 `recentFailures`「最近 5 条」样本，**同 DTO 有 failedCount 总数回显**（MerchantFinanceService:209 实证）=诚实。
  - **DATE_ARITHMETIC 1**（新分类）：MerchantWithdrawService Days(1)——`unlockedThroughDate` 解冻日边界计算，非数据取数窗口（门禁头部词汇同步补记）。
- **分类中抓到一个真缺口并已修复**：**LinePromoTaskController.list** 原返回裸 `List` + mapper `LIMIT 200` 静默截断（`ops:line-manager:list` 用户面，MA-002 同款）→ **修复为分页**：Mapper `findByManager(+offset/size)`+`countByManager`、Service 返回 `PageResult`、Controller 加 `page/size`（默认 0/20，服务层钳制 [1,200]）、admin-vue 地推任务 tab 补真分页（PagePager，与线长/提现 tab 同构）、新增 [LinePromoTaskServiceListTest](services/trade-service/src/test/java/com/aicabinet/trade/service/LinePromoTaskServiceListTest.java) 3 夹具钉契约（total 走 count、钳制、负页归零）。LIMIT 字面量随分页参数化消失，登记条目销账。
- 门禁复跑绿：截断 10 组 + 窗口 34 组全登记（TBD 0/0）；trade-service `LinePromoTask*Test` 5/5 绿、admin-vue `vue-tsc` 绿。

**S8① 修复批覆盖对账**（[check-fix-batch-coverage](scripts/check-fix-batch-coverage.mjs) + [fix-batch-registry.json](scripts/fix-batch-registry.json)，同日晚收尾）
- **映射表来源用户拍板：逐批手工登记**（另两案「审计报告自动解析」=格式脆弱、「弱判据先行」=不校验文件，均不采纳）。存量不回填（A26 已由 R1–R17 核查报告人工收口），棘轮从 cutoff=`9c30038b` 起算。
- 三判据：① **A26 本体**——登记条目声称的文件必须真实出现在该 commit 的 `git show --name-only` 里且仓内存在；② **覆盖对账平**——items+notAdopted === totalItems（「既不在修复表、也不在有意未采纳清单」的红，点数在登记时锁定）；③ **增量完备性**——cutoff 后的 `fix(`/`perf(` 提交必须已登记或入 unregisteredAllowlist（带 why）。
- 工作流成本 ≈ 每修复批 5 分钟；跨批条目重复（report+id）也红。10 项断言负向用例；接线：`check:fix-batch-coverage`（test 先行）入聚合链尾；带 sibling test ⇒ 按 S8④ 自动达标无需豁免。

**S14 迁移/脚本表名声明集落地（2026-11-13，复核会话）**
- **[check-script-table-set](scripts/check-script-table-set.mjs) + [check-script-table-set.test.mjs](scripts/check-script-table-set.test.mjs)（12 断言，入聚合链）**：迁移按版本序建**逐表状态机**（最后一次事件定生死），DML 引用必须 ∈（当时存活 ∪ 本脚本自建）；清库脚本（scripts/*.ps1|sh|sql）引用必须 ∈ 最终活表集；与 retention-registry 交叉对账；`-- SCRIPT_TABLE_SET_EXEMPT:` 头声明豁免。FROM/JOIN 只查 DROP 复用（避开 EXTRACT 函数上下文噪声）。
- **首次实跑抓到 47 处迁移内引用已 DROP 表**（MA-042 家族）：根因是 DROP 语句后来补进已应用迁移（V129/V136/V155/V346），引用这些表的迁移（V165~V473）按表当时存在写——**全新空库按序构建必断**。immutability 铁律封死改迁移路线 ⇒ 给 **check-migration-immutable 加修复批豁免通道**（`-- MIGRATION_IMMUTABLE_EXEMPT: <理由>`，仅 M 开放，D/R 硬红不豁免）。
- **🔴 静态分析被 DROP→重建 序列系统性骗过（关键教训）**：集合差 `created−dropped` 把「掉了又重建」的表误判为已删。并发会话 3 判 1 对（merchant_replenishment_request 正确，points_redeem_item/line_manager 误判），dev `to_regclass` 实测证伪；我首轮重写也犯了同错——**11 个文件误删了有效语句**。改为**逐表状态机**后复核：line_manager（V129掉→V133建）、line_device（同型）、ad_campaign（V155掉→V174建）、member_points_log（V136掉→V163建）、points_redeem_item（同型）、line_route（V155掉→V358建）终态全存活。11 个误删文件回滚恢复（`git checkout HEAD --`），仅 5 个引用点在重建**之前**的移除正确（V165/V182/V200/V201/V217）+ V466（merchant_replenishment_request 确未重建，并行会话提交）。
- **retention-registry 补 13 张活表**（165→178）：7 张 S7 盲区（注释 ROLLBACK DROP 被当真实 DROP）+ 6 张 DROP→重建（状态机揭示）。S7 `extractTables` 同步改状态机 + 版本排序。
- **stripComments 未闭合 `/*` 修复**：V157/V421 有未闭合块注释，拼接串里非贪婪正则跨文件吞内容（夹具实证）——未闭合 `/*` 视为到串尾。
- **终态**：S14 门禁绿（472 迁移 + 8 清库脚本，178 活表对账一致）；S7 门禁绿（178 全登记）；immutability 门禁绿（V459 R19 存量红正式文档化）；wiring 180 门禁全接线。S14 ② 表名声明集落地，S14 ①（information_schema 推导）已由 S5 钉住、③（严格升序）已由 check-migration-number-order 覆盖。

**R18-2 批次账读改写收口（2026-11-13，S13 前置）**
- **前置核实**：51916f22（R18-2/4/5）已做**部分修复**——`deductQuantityAtomic`（条件 UPDATE 原子扣减）/`increaseQuantityAtomic`（原子累加）/`updateStatusAndSlotById`（只写 status/slot 不碰 quantity）入 Mapper，`doRestoreToBatch` 接入。但文档「仍未修」的表述过期——实际残留在**过期扫描**路径。
- **🔴 残留真凶**：`scanExpiryAlerts` 是**跨设备批量扫描、不在设备锁内**，其状态更新走 `save(lot)`（绝对覆盖整个实体含 quantity）——与并发扣减（在锁内原子扣减）竞争时，扫描的 save 用旧快照覆盖已扣量，正是 R18-2 描述的「账面虚高、可再卖」。
- **收口**：`processExpiredLot`/`processNearExpiryLot`/`createPullOffTaskIfAbsent` 三处 `save(lot)` → `updateStatusAndSlotById`（R18-2 配套专用 UPDATE，只写 status/slot 不碰 quantity）。`doAddRestock` 的 save 在设备锁内串行，安全，不动。
- **测试**：[InventoryLotExpiryScanR18Test](services/trade-service/src/test/java/com/aicabinet/trade/service/InventoryLotExpiryScanR18Test.java) 2 夹具钉住（过期/临期两条路径必须调专用 UPDATE、save 永不出现）；InventoryLot 全量 11/11 绿无回归。

**S13 diff 级并发写策略声明落地（2026-11-13，复核会话）**
- **[check-concurrency-declaration](scripts/check-concurrency-declaration.mjs) + [check-concurrency-declaration.test.mjs](scripts/check-concurrency-declaration.test.mjs)（8 断言，入聚合链）**：不做全仓扫描（误报面极大，§九 S13），做**新增面强制声明**——凡改到资金/库存/额度写路径的 diff，必须命中三选一：① 条件 UPDATE（`WHERE` 谓词 / 原子方法）② 乐观锁 version（CAS）③ 显式锁 key（tryLock/distributedLock），或附 `// CONCURRENCY: <策略>` 锚点。门禁**只校验被本次 diff 触碰的方法**（diff 级，方法签名定位 + 写路径领域词判定）。
- **当前 diff 实跑**：32 个变更文件，1 个 diff 触碰的写方法（R18-2 收口的过期扫描）有 `updateStatusAndSlotById` 策略证据，绿。wiring 182 门禁全接线。
- **第二层 15 族终态更新：落地 8**（S9/S10/S11/S12/S13/S14/S15 + 第一层 8 族全），**S 系列 15 族全部落地**；欠账台账全部清零。
