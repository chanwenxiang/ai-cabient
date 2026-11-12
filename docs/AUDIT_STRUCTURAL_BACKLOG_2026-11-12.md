# 结构化收口清单 —— 从 R1–R22 二十二轮审计里提出的「一次消掉一族」工程项（2026-11-12）

> 口径：本轮**不改代码**，只把 22 轮里反复出现的同族缺陷收敛成**结构性控制**（单源 / 门禁 / 注册表 / 契约测试）。
> 判据：只有「修完之后再也不会以新落点复现」的才算结构化；逐条补洞不算。
> 依据文件：`docs/AUDIT_FOUR_END_2026-11-09.md`、`_R2_`～`_R4_`、`_R11_`～`_R16_`、`_R18_`～`_R22_`、`_OVERNIGHT_FINAL_2026-11-11/12`。

---

## 一、结论：22 轮里真正反复出现的只有 8 族

| 族 | 一句话根因 | 历史条目（已核） | 轮次跨度 |
|---|---|---|---|
| **S1 出站客户端各写各的** | 每个 client 自建 `RestClient`，超时/重试/错误封装无单源 | R20-4、R20-10、R21-1（6 处）、R19 网关零超时、R22-2（短信额度顺序） | R19→R22，4 轮 5 次 |
| **S2 非终态没有推进器** | 状态机有中间态但没有「谁来收口 + 超时阈值 + 兜底告警」的登记 | R18-1（FAILED 不在迟到兜账范围）、R20-1（ABNORMAL 仍记已退）、R20-8（DONE 把 REFUNDED 改回 PAID）、R22-1（PROCESSING 永不推进）、R22-6（CHARGE_PENDING 巡检被开关短路） | R18→R22，4 轮 5 次 |
| **S3 两侧约定各写一份** | 写入格式/解析格式、手动路径/自动路径、阈值、单位、Provider 语义没有同源 | R12-P2-1…P2-4 + R13-MA-174（M01 `|| undefined` × `updateById` 跳 null，75+ 处）、R18-1（15s vs 60s）、R20-9（覆盖赋值 vs 单值读）、R22-1（写空格 / 读 `#`）、R22-3（手动 1–10 层 vs 自动恒 1）、R22-4（Java 上海 vs PG 会话 UTC）、R22-8（微信 fail-fast vs 支付宝空列表） | R2→R22，贯穿 |
| **S4 时间口径没有单源** | 业务日/月窗口在 Java、SQL、cron、镜像四处各定各的 | R21-2（16 文件裸 `LocalDate.now()`）、R22-4（`to_char` UTC 日桶）、R22-10（4 处 `systemDefault()`） | R21→R22 |
| **S5 资金不变量只写在注释里** | 「禁止 DELETE」「必须先冻结再分账」这类不变量没有跨通道强制 | R20-2（未实收即分账）、R20-5（积分重复过期=流水完整性）、R22-5（25 张资金表可物理删除 + 积分禁删不变量失守） | R20→R22 |
| **S6 配置开关连带短路** | 「方法级早退」把不相关的副作用一起关掉 | R22-6（`auto_cancel_hours=0` 关掉资金巡检）、R3/R4 的巡检/告警被开关吞（同族早期形态） | R3→R22 |
| **S7 新表/新端点不登记** | 生命周期与注册表靠人记得补，fail-open | R19-A1 同族（`session-failed-hold-clear` 未登记 `ScheduledTaskRegistry`）、R22-9（遥测表无保留策略） | R19→R22 |
| **S8 修复流程本身不闭环** | 「修一半 / 同族漏扫 / 判据无正向用例」被连续三轮点名，**至今未写进 CODE_FIX_CHECKLIST** | R2 §6.2、R3 §6.1、R4 §6.1/§6.2/§6.4 | R2→R4，之后每轮仍在付账 |

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

- **落地形式**：① 脚本 `scripts/check-fix-batch-coverage.mjs`：输入「条目 → 应改文件」表，对 `git show --stat` 交叉核；**既不在修复表、也不在「有意未采纳」清单里的条目 = 红**（这正是我手工在做的范围对账）；② 把 R2 §6.2 / R3 §6.1 / R4 §6.2 三次点名、至今未落地的三条写进 `.cursor/rules` 或 CODE_FIX_CHECKLIST：「修复必须同文件+同链全扫同族」「改判据必须附一条『真实输入能命中判据』的正向用例」「跨端/跨通道契约必须两端同批改 + 门禁锚双侧」；③ 门禁自身可靠性（R3 §6.4 棘轮 env/结构漂移可静默假绿）：每个 `check-*.mjs` 必须配 `.test.mjs` 负向夹具——现在 131 个里只有一小批带 test。
- **消掉**：不是某个缺陷族，而是**「每轮都在重新发现同一族的漏网」这个流程成本**。R4 §6.4 已经量化过：同 HEAD 双轮并集比单轮多 4 条 P1。
- **代价**：小。①一个下午；②纯文档；③可分批（先给高价值门禁补）。

---

## 三、建议执行顺序（三批，每批一个可验证收口）

| 批 | 内容 | 为什么先做 | 收口判据 |
|---|---|---|---|
| **第 1 批（1–2 天）** | S8②③（CHECKLIST 三条 + 门禁负向夹具）、S1（工厂 + 禁裸建）、S6（开关早退形态） | 三条都是**纯规则/纯接线**，不碰资金路径；S1 直接清掉 R21 唯一未收口的 P1 | 门禁能红（负向用例）+ `RestClient.create()` 裸建计数=0 |
| **第 2 批（3–5 天）** | S2（非终态注册表）、S5（资金表保护与对账同源 + 删除审计 before）、S4（TimeWindows + 三条时区门禁） | S2 是最高价值族，S4/S5 的改点都很局部且可测 | 非终态登记完备（门禁红于新增未登记态）；25 张暴露表里资金表清零；`to_char` 无 `at time zone` 命中=0 |
| **第 3 批（1 周+）** | S3（双侧同源三规则）、S7（保留注册表 + 遥测清理）、S8①（修复批对账脚本接进预检） | S3 需要先盘点分隔符/阈值清单，S7 要一次性给 179 表分类 | 契约测试覆盖所有 Provider 实现；表未登记即红；预检含修复批覆盖对账 |

---

## 四、明确不做（避免过度结构化）

1. **不为「一次性逻辑错」建机制**：R20-3（券绕活动禁券）、R20-6/R20-7（状态出边范围）这类是业务规则实现问题，补测试即可，抽成机制只会多一层间接。
2. **不新建通用「状态机框架」**：S2 只要一张登记表 + 门禁；引框架会把 22 轮的复杂度变成第 23 轮的陌生面。
3. **不放宽现有判据来换绿灯**：`check-no-inline-money-math` 的方向缺口（元→分）应补规则，而不是把 `Math.round(yuan*100)` 加白名单。
4. **不把拍板题伪装成工程项**：R22 §八-8.4（两侧皆 0 是否算 MATCHED）、余额退款上限、活动期禁券优先级——这些要你给口径，机制只能负责「口径落点后不再漂」。

---

## 五、一句话方案

**把 22 轮里出现 ≥3 次的 8 个形状，各换成一个「新代码不接线就编译/门禁过不去」的控制点**：
S1 工厂、S2 非终态注册表、S3 双侧同源、S4 时间单源、S5 资金表保护与对账同源、S6 开关作用域、S7 登记完备性、S8 修复批对账。
优先级按「历史命中数 × 严重度 ÷ 成本」，S8 的文档与夹具部分成本最低、却已拖了 18 轮。
