# §12 待拍板 #1 · 三域钱包是否抽泛型账本原语（2026-10-18）

> 触发：用户 2026-10-18 拍板 §12 后续两条已闭合（#2 staff_wallet 死列 / #3 vision 草稿告警，均已 refspec 推 `0105ba04`），
> 只剩 #1「三域钱包能统一好一点吧？」——用户软性表态、**未下 go**。
> 纪律：本简报**只做取证与选项**，不替用户改资金链（规则 `critical-judgment` + 用户「不明白先停下来问」铁律 + 记忆 `feedback-decision-brief-before-acting`）。
> 事实来源：主会话亲读三份 Service、三对 entity/mapper、六张表 DDL、`DistributedLockService`、
> `MerchantFeaturePacks`、`WalletRefIdempotencySqlIT` 与全部消费者/测试类；所有行号已核。

## 0. 一句话

三域钱包的**表结构、双锁语义、幂等键、LedgerLine 快照**已高度同构；
但**统一**的正确答案取决于**你更怕哪一种债**——
A) **代码债**（三份 Service 各自漂移），还是
B) **上线前的资金链回归窗口**（12 注入面 × 14 测试类 × 20 admin 视图 × 6 张表数据迁移）。
资金链等价性已实证绿（2005/0 + IT 5/0），当前无 bug，只是形状重复 ⇒ **抽象收益不匹配回归成本**。

## 1. 三域结构实证（逐行核对，行号=我亲验）

| # | 事实 | 证据 |
|---|---|---|
| S1 | **表结构高度同构**：`{owner}_wallet_account` (owner PK + balance_cents + updated_at) 与 `{owner}_wallet_ledger` (ledger_id BIGSERIAL + owner_id + entry_type + amount_cents + balance_after + frozen_after + ref_type + ref_id + remark + created_at) | merchant/line/staff 六个 DDL（V241 起、V359 staff） |
| S2 | **owner PK 类型异**：merchant_id `VARCHAR(64)` / manager_id `BIGINT` / staff_user_id `BIGINT`；ledger uk_ref 三张都是 `(owner_id, ref_type, ref_id)` | `uk_merchant_wallet_ledger_ref`、`uk_line_wallet_ledger_ref`、`uk_staff_wallet_ledger_ref` |
| S3 | **frozen_cents 只在 account 侧异**：merchant/line 有并**真的在用**（freeze/release/consume 全流程），staff 那份**从来没人写非零**（V387 已删）；ledger 三张都保留 `frozen_after` 列（商户/线长写快照、staff 恒 0） | `MerchantWalletService:360-537`、`LineWalletService:254-446`；staff 无对应方法 |
| S4 | **双锁完全同款**：三份都用 `DistributedLockService.tryLock(key, 60, 5)` + mapper `findByIdForUpdateRaw (FOR UPDATE)`；`LOCK_PREFIX = "aicabinet:lock:"` 由 `DistributedLockService` 拼，业务侧只传业务键 | `DistributedLockService.java:12-24`；Staff `StaffWalletService:190`、Line `:458`、Merchant `:550` |
| S5 | **lock key namespace 不一致**：Merchant=`"merchant:wallet:"+id`、Line=`"line:wallet:"+id`、Staff=`"staff-wallet:"+id`（**冒号 vs 连字符**），且 staff 无 `static walletLockKey()` helper | `MerchantWalletService:542-546`、`LineWalletService:450-454`、`StaffWalletService:192` |
| S6 | **MerchantFeaturePacks 的前缀消费是「权限码」命名空间不是锁 key**（`"merchant:wallet:"` 用作 perm code 前缀集合），所以 S5 的锁 key 漂移**没有运维/看板消费者**——改齐只是代码美感 | `MerchantFeaturePacks.java:25-40` 遍历 `permCode.startsWith` |
| S7 | **LedgerLine 结构漂移**：Merchant/Line 各自持 `record LedgerLine(... BalanceSnapshot)`（**未共享**，各自私有）；Staff 用**裸参数** `appendLedger(long, String, long, long, String, String, String)`（本轮 #2 已把 frozenAfter 形参删了） | `MerchantWalletService:626-629`、`LineWalletService:526-529`、`StaffWalletService:167-182` |
| S8 | **Merchant ledger 快照列 `merchant_name` 是**商户特化**（V241 加）——appendLedger 里额外 `merchantMapper.selectById` 反查一次；line/staff 都没这个字段 | `MerchantWalletService:598-604`、`V241__finance_display_and_uk.sql:6` |
| S9 | **Merchant ledger 有 FK 约束** `merchant_wallet_ledger_merchant_id_fkey`；line/staff 没 | grep 迁移 |
| S10 | **公共语义 = `requirePositive` / `value(Long)` / `trim(String,int)`** 三份都是**各自私有 static**、逻辑一致（我逐字对比过：`trim(v, maxLen)` 三份完全等价） | `MerchantWalletService` 尾段、`LineWalletService:533-571`、`StaffWalletService` 尾段 |
| S11 | **API 面差异（真语义差，不是重复）**：Merchant 有 `reverseCreditIfPresent`（原流水命中才冲，非幂等 flag）、`debitedCentsByRef`（读侧「实扣多少」给 F-5 日结通知用）、`debitIfAbsent`、非幂等 `debit/credit`；Line 有非幂等 `debit/credit`；Staff **只有幂等三件套** `creditIfAbsent/payoutIfAbsent/debitIfAbsentAllowNegative` + `listWallets/listLedger` | Merchant 服务 14 个 public、Line 10 个、Staff 7 个 |
| S12 | **提现链差异**：Merchant/Line 走 **申请→冻结→审批→打款→consume 拆 net+fee（V308）**完整链（`MerchantWithdrawService:962`、`LineWithdrawService:666` 都持自己 walletLockKey 复用）；Staff 提现=**运营线下打款后 `payoutIfAbsent` 单步扣款，余额不足 412**（对齐用户口径「没存钱就会提现失败」）| `StaffWalletService:82-103` |
| S13 | **reload 路径微差**：Merchant/Line `doEnsureAccount + findByIdForUpdate.orElseThrow("xx钱包不存在")`；Staff `findByIdForUpdateRaw==null ⇒ doEnsureAccount`（**先读后建**） | `StaffWalletService:159-165`、`LineWalletService:488-496` |

**结论（实证面）**：**同构 ≠ 相同**。三份 Service 在「加/扣/幂等/双锁/ledger 快照列名/uk_ref」5 条骨架上确实等价；
在「owner PK 类型 / frozen 生命周期 / 提现模型 / API 面扩展位（reverseCredit/debitedCentsByRef/merchant_name 快照）」4 条上**语义有别**。
统一原语必须同时容住这 4 条不同，否则会把商户侧的反向冲正/日结实扣查询硬塞进泛型签名（要么降级要么暴露 domain-specific hook）。

## 2. 消费者与回归面（blast radius 实测计数）

| 面 | 数量 | 备注 |
|---|---|---|
| Service 注入点 | 12（Staff 3 / Merchant 4 / Line 5） | 见 `grep -rln 'private final \w*WalletService'` |
| Entity/Mapper 类 | 12（各域 2 实体 + 2 Mapper 接口 + 若干 XML） | Staff 无 XML 例外（走 `SELECT a.*`）|
| DB 表 | 6 张（3 account + 3 ledger） | |
| 迁移影响 | A 案需 ≥3 个 V388+（union 建表 + backfill + drop）| 高水位现 V387 |
| 单测类 | 20 个（Wallet/Withdraw/Commission/Promo/Rental/RevenueSplit/Daily 系列） | grep 结果，全部会走公共原语 |
| IT 类 | 1 个 `WalletRefIdempotencySqlIT`（三域共用 ref 幂等 SQL） | 已实证 V387 绿 |
| admin 视图 | 20 个 finance 页 + `endpoints.ts` 三条 staffWallets 路由 | |
| 门禁 | `check:balance-hold-types`（现成，跨三域）+ `check:money-yuan-prefix` + `check:prometheus-metric-names` | |
| 隔离 UAT 驱动器 | F4（Merchant 提现全链）+ 部分 F3/F6 | 已在 `audit-admin-flows-uat.mjs` |

## 3. 三个选项

### A · 合并到单表 + 单 Service（`wallet_account` + `wallet_ledger` + `domain` 列）

- **动作**：V388 建 union 表（`domain VARCHAR(16)`, `owner_id VARCHAR(64)`——`manager_id`/`staff_user_id` 转字符串或保留 BIGINT + 域区分），V389..391 三份 INSERT SELECT 迁数据，V392+ drop 老表；三份 Service 合一 `WalletService<K>`（enum domain）、12 注入点全改；20 视图改前端 API 或加 alias；F4 驱动器重跑；LedgerLine / BalanceSnapshot 上提公共包；merchant_name 变通用 `owner_display_name`（三域 backfill）。
- **收益**：彻底消除漂移；uk_ref 单表；后续加第四域（平台资金池已是同型）零成本；F-5 的「实扣查询」`debitedCentsByRef` 一份即可。
- **风险**：
  1. **资金链核心表结构迁移**：上线前窗口做，回滚=重灌数据或双写过渡（新成本）；
  2. `owner_id` 类型冲突（VARCHAR 64 vs BIGINT）⇒ 只能统一 VARCHAR，line/staff 索引选择性变差、`ORDER BY balance_cents DESC` 分页仍可用但 `ORDER BY owner_id` 语义变；
  3. merchant_name 快照列的**审计追溯**（V241 加它就为了"记账时的商户名"）需要三域都 backfill `owner_display_name` 的历史值，line/staff 现在没这概念；
  4. **12 注入点 + 20 视图 + 20 测试类**同一轮全改，回归窗口至少 3–5 天，peer 现在脏 dev，合流复杂；
  5. uk_ref 索引 `(domain, owner_id, ref_type, ref_id)`——domain 一旦漏填会**跨域串号**（现有代码没这个字段，忘填即事故）；
  6. FK `merchant_wallet_ledger_merchant_id_fkey` 需要重指向新 union 表 + 加 domain 检查约束。

### B · 保 schema，服务侧抽 `WalletPrimitive<K>`（credit/debit/freeze/consume/appendLedger 公共原语下沉）

- **动作**：新增包 `com.aicabinet.trade.wallet`：`WalletAccountSnapshot` 接口（getBalance/getFrozen/setBalance）、`WalletLedgerWriter<L>` 接口、`LedgerLine` 顶层 record、`WalletPrimitive<K extends Comparable<K>>` 抽象基类；三份 Service 保留 public API 但把 `runWithWalletLock / doCredit / doDebit / doFreeze / doRelease / doConsumeSplit / appendLedger` 全部委托给基类，域差异（`merchant_name` 快照、`reverseCreditIfPresent`、`debitedCentsByRef`、`payoutIfAbsent`）以 hook/子类覆盖。
- **收益**：漂移集中在原语层，加第 N 个钱包 20 行子类；锁 key 命名空间统一（原语持 `LockKeyNamespacer<K>`）；LedgerLine/requirePositive/value/trim 从 3 份 → 1 份；S7 的裸参数 appendLedger 消失。
- **风险**：
  1. **仍是资金链代码改动**：12 注入面签名不变但**内部行为等价性**要证明——需要三份 concurrency 测试（Merchant/Line 有、**Staff 没有**）+ `WalletRefIdempotencySqlIT` 回归全跑；补 StaffWalletConcurrencyTest 是新成本；
  2. **非幂等 debit/credit**（Merchant `debit/credit`、Line `debit/credit`）在抽泛型时要么把「是否幂等」做成 flag（一旦 flag 走偏，现有 4 处提现链会静默变幂等/非幂等 ⇒ 与 V271 类守卫冲突），要么基类只提供幂等版、把非幂等版留在子类——那"统一"只覆盖 60%；
  3. **owner PK 泛型化**（`WalletPrimitive<Long>` vs `WalletPrimitive<String>`）会让 mapper 的 `findByIdForUpdate` 参数类型变 `Object`/泛型，`FOR UPDATE` SQL 里 cast 处理需要每个 mapper 都改；
  4. **S13 reload 路径差异**（Merchant/Line 先建后锁读 vs Staff 先锁读 null 才建）要在基类里选一种——两种都对但换实现会引入**行为漂移**（例如 staff 现在如果 ensureAccount 与 debit 之间被别的进程并发插入，走 Merchant 那种「先 ensure 再 orElseThrow」的路径可能突然抛）；
  5. 泛型擦除 + Spring bean 注入歧义：三份 Service 若都 extends 同一 `WalletPrimitive<K>`，`@Autowired WalletPrimitive` 会有三候选 ⇒ 要加 `@Qualifier`，12 注入点全 touch。

### C · 只做「已证伪的小漂移」，不抽原语（推荐）

- **动作**（都在 3 个 Service 文件内部，零 schema、零 consumer 变更）：
  1. **补 `StaffWalletService.walletLockKey(long)` static helper**——现在 staff 用 inline `"staff-wallet:" + id`（S5），改成 `"staff:wallet:" + id` 与其他两域对齐；同时**新增 `StaffWalletConcurrencyTest`**（对齐 Merchant/Line 那两份 concurrency 测试的 lock 断言，闭合 S5 里测试面的不对称）。
  2. **LedgerLine 顶层化**（可选，看是否愿意引入新公共包）：把 `LedgerLine` record 从 Merchant/Line 私有 → `com.aicabinet.trade.wallet.LedgerLine`，Staff 也切过去。这是**代码层重构、非行为改动**，测试类不受影响。
  3. **三份 `requirePositive/value/trim` 抽 `WalletSupport` 静态工具类**：纯机械 dedup，无行为改动。
- **收益**：漂移清单里 **S5 / S7 / S10** 三条解决；测试覆盖 S5 里"锁真的被拿到"在 staff 侧从 0 → 1（真补上）；改动 4 个文件，1 天内可完成 + 全套回归。
- **风险**：**没有消除跨域漂移**——未来加第 4 域（平台资金池若走同款钱包语义）时三份重复照旧。但**这是设计层欠债不是当前 bug**；A/B 案在上线前窗口做的**回归成本**比这笔债本身大得多。
- **不做**：S1–S4（同构已达标）、S8–S9（商户特化本就应保留）、S11–S12（提现模型不同是老系统语义，用户 2026-10-16 拍板过 staff 走线下打款口径）、S13（reload 差异在锁到位时行为等价，改了要重测并发）。

## 4. 推荐

**C**。理由（诚实评估，不粉饰）：
1. **等价性已挣绿**：`mvn -pl services/trade-service -am test` 2005/0、`WalletRefIdempotencySqlIT` 5/0、F2–F8 隔离栈整合跑 30P/0F/3S；抽象动作**只能维持等价、不能提升正确性**，而**回归窗口成本**在上线前（2026-10-19 真将邑柜复跑 + §12 只剩这一条 + peer 在 dev 脏）**明显高于收益**。
2. **A 案与上线时点硬冲突**：六张表迁移 + 20 视图改数据源 = 5 天窗口起，peer 并行改动会撞。资金链表动结构是「难撤销操作」——按项目「executing actions with care」原则属于「用户明示承担风险才能做」，用户没表态。
3. **B 案是"看起来便宜"的中间派**：签名不变但**内部行为等价性证明**要重写并发测试 + 泛型 Spring 注入歧义 + 非幂等 debit 归属决策 3 条硬骨头；单看 diff 3 小时，跑绿 3 天。
4. **C 案是"最小可行"**：只做**已验证的真漂移**（S5 锁 key 前缀、S7 appendLedger 裸参数、S10 三份 helper 拷贝），加**已发现的真测试缺口**（Staff 无 concurrency 测试）。风险边界=3 个 Service 文件 + 1 个新测试类，preflight 直接跑绿。

**但**：如果用户判断**这笔账必须现在结**（比如上线后计划快速加"供应商钱包 / 骑手钱包 / 品牌钱包"多域钱包），那 **B** 是次优解——**前提**是先补上 `StaffWalletConcurrencyTest`（C 里的第 1 步）作为基线，再抽原语。A 只在**平台资金池也并入**同一原语时才划算（那是第 4 域）。

## 5. 拍板结果（2026-10-18 用户定）

**选 A · 全量彻底统一**（用户：「要干就彻底解决干净」；上线尚早 + 空库从头 Flyway ⇒ A 最大的成本「数据迁移」直接归零）。两个范围点已由用户确认：

1. **平台资金池不并入**（用户选「不并入，保留独立领域」）。实证：`platform_fund_account` 是**我方资产/备付台账**（`account_id BIGSERIAL + channel UNIQUE`、`operator_id`、`status`、`balance_after_cents`、无冻结/无幂等 uk），与三域**应付钱包**语义正交；CB-033 结论 1 早已定性「本仓新增领域、无竞品先例」。强行并 = 泛型被 `asset/liability + 渠道主键 vs 主体主键 + 无冻结/无幂等` 撑成满屏 if(domain)，反而不干净。**资金池维持 `PlatformFundService` 独立。**
2. **只统底层：存储 + 写原语统一；各域 REST 端点 / DTO / 后台 20 视图维持现状**（用户选「只统底层」）。⇒ facade 三 Service 的 public 签名与返回类型不变 ⇒ 12 注入点 + `openapi.ts` + 视图零改；统一发生在表与写原语层，读模型由 facade 把统一行**映射回**现有 `Merchant/Line/StaffWallet*` 返回类型。

### 5.1 目标设计

- **两表**：`wallet_account`(PK `(domain, owner_id)`, balance_cents, frozen_cents, updated_at) + `wallet_ledger`(id, **domain**, owner_id, entry_type, amount_cents, balance_after, frozen_after, `owner_snapshot_name` NULL, ref_type, ref_id, remark, created_at; `uk(domain, owner_id, ref_type, ref_id)` + owner 索引)。`domain ∈ {MERCHANT, LINE, STAFF}`（enum，**由 facade 固定注入，调用方永不传** ⇒ 杜绝「漏填串号」）。
- `owner_id VARCHAR(64)`：merchant 原样（本就是 String），line/staff 的 `long` 经 `String.valueOf` 规范入列，读回再 parse；domain 已隔离 id-space，无碰撞。
- `frozen_cents`：统一列，merchant/line 活、staff 恒 0（V387 删的是旧独立表的死列；统一表里它按 domain 有语义，非死列）。
- `owner_snapshot_name`：merchant 记账快照专用（保 `MerchantWalletLedger.merchant_name` 的审计追溯，映射回 DTO 的 `merchantName`），line/staff 留 NULL。
- **写原语 `WalletEngine`**：ensure / creditIfAbsent / debit(可用校验) / debitIfAbsentAllowNegative / freezeForWithdraw / releaseFrozen / consumeFrozenSplit / payout / appendLedger + 统一 lock 命名空间。**域能力用「domain→支持的动作集」表驱动**，不是散 `if`。
- **三 facade 委托引擎**，签名不变；merchant 专属 `reverseCreditIfPresent`/`debitedCentsByRef`/快照写入留 facade。

### 5.2 扩大后的真实 blast radius（本简报 §2 之外，取证新发现）

| 新发现消费者 | 位置 | 处理 |
|---|---|---|
| **资金对账裸 SQL** | `DataConsistencyService.java:691-785`（4 段：MERCHANT/LINE_WALLET_LEDGER_SUM 余额=流水和、MERCHANT/LINE_WALLET 冻结一致） | 改 domain-scoped 统一表 SQL + `DataConsistencyServiceTest` |
| **P0 审计脚本** | `scripts/full-round-p0-04-split.mjs:144-235`（psql 直查 merchant_wallet_*） | 加 domain/换表名 |
| **C-admin UAT 驱动器** | `audit-admin-flows-uat.mjs` F4/F6/F8 psql 段（1694-2068+） | 加 `and domain='MERCHANT'` |
| 3 mapper XML | `*WalletAccountMapper.xml`（`findByIdForUpdateRaw` FOR UPDATE `SELECT a.*`） | 并入统一 mapper |
| `CommissionClawback.java:15` 注释 | 「见 line/staff_wallet_ledger」 | 措辞回写统一表 |
| `WalletRefIdempotencySqlIT` | 三域共用 ref 幂等 IT | 重指统一表 + domain 维度 |

### 5.3 实施路线（负向先行 · 分步提交 · worktree→refspec）

1. **绿基线**：补 `StaffWalletConcurrencyTest`（Merchant/Line 有、Staff 没有的真缺口）pin 住锁语义。
2. V388 建两统一表（含 domain/uk/索引）；V389 drop 六旧表（空库无 backfill）。FK `merchant_wallet_ledger_merchant_id_fkey`：改指统一账本不可仅域生效 ⇒ **降为应用层保证（ensureAccount 先于 appendLedger）+ 记账 CHECK 约束**，本条在提交说明显式记。
3. 共享层：`WalletDomain`、`WalletAccount`/`WalletLedger`、两 Mapper + 统一 XML。
4. `WalletEngine` 写原语 + domain 能力表驱动。
5. 三 facade 委托（签名/返回不变，owner long↔String，merchant 快照/反冲/实扣留 facade）。
6. 读映射：统一行 → 现有三 DTO 返回类型（openapi 零改）。
7. `DataConsistencyService` 4 段 recon SQL domain-scoped 化 + `DataConsistencyServiceTest`。
8. harness：`full-round-p0-04-split.mjs` + `audit-admin-flows-uat.mjs` psql 段加 domain。
9. `WalletRefIdempotencySqlIT` 重指统一表。
10. 全量 `mvn trade-service` 绿 + 隔离栈空库重建跑幂等 IT + F4/F8 余额侧 + preflight + refspec 推 dev。
11. 文档回写：§12 #1 闭合 + CB-033/活文档资金池不并定性。

> 纪律：每步保持资金链**行为等价**；负向测试先行；隔离栈空库重跑对账 + 幂等 + F4/F8；分步提交，peer 脏 dev 走 refspec（[[feedback-refspec-push-while-peer-dirty]]）。

## 6. 执行结果（2026-10-18 落地）

§5.3 路线 1–9 步**全部完成**，行为等价、资金链不造假绿。分支 `refactor/wallet-unify-3domain`（①基线/简报 + ②建表/删表 已提交，③④⑤代码+测试+本回写为后续提交）。

- **共享层已建**：`WalletAccount`/`WalletLedger` + 两 Mapper + `service/wallet/{WalletDomain,WalletEngine}`；六旧 Mapper + 三 `*WalletAccountMapper.xml` 已删（删 Mapper 连带删 XML，避 lesson #233）。
- **facade 委托 + 读映射**：三 `*WalletService` public 签名/返回类型不变，内部转调引擎并把统一行映射回各域 view 实体；对账（`PayoutReconciliationService`/`MerchantWithdrawPayoutService`）、裸 SQL（`DataConsistencyService` 4 检查）、两个 .mjs 审计脚本改 domain-scoped 统一表。
- **两处与 §5.3 原计划的偏差（均为收敛更优，非妥协）**：
  1. **六 view 实体不删**——为守「各域 DTO 维持现状」的锁定范围，`Merchant/Line/StaffWallet{Account,Ledger}` 保留为**纯内存读模型**；其 `@TableName` 指向已被 V389 DROP 的旧表，但因 Mapper 全删、无任何 MP 操作触达，属**惰性无害**。后续若要做彻底去注解的收尾，应连同 openapi/序列化契约一起验，另议。
  2. **`COMPETITOR_REF` 指向 CB-054,CB-033**——不为「存储收敛」这一已基准化能力新造无实证台账条目（门禁 N/A 通道因 SQL 含业务语义词被拒），复用真实存在的 CB-054（三域钱包本体）+ CB-033（资金池独立领域）。
- **测试口径坑**：`MerchantWalletFeeSplitTest` 必须 stub `WalletAccountMapper.findByOwnerForUpdate`（返回 `Optional` 的 default 包装），而非底层 `findByOwnerForUpdateRaw`——Mockito 会拦截 default 方法、不委托底层，否则 `reload()` 拿到 `Optional.empty` 抛 500「钱包不存在」。
- **验证（本 worktree，Docker 可用）**：clean compile 877；全量单测 **2031/0/0**；`run-audit-gates.mjs` **46 门禁 0 失败**；`WalletRefIdempotencySqlIT` **一次性空库 Flyway 从零**（含 V388/V389）5/5 绿。
- **仍待监督环境**：隔离栈空库重跑对账 + F4/F8 余额侧 + `pre-push-ci-preflight` + refspec 推 dev；F1 结算链 2026-10-19 真·将邑柜复跑。

## 附录 A · 关键行号索引

- `MerchantWalletService.java:542`（walletLockKey）、`:580`（reload）、`:592-624`（appendLedger + merchant_name 快照）、`:626-629`（LedgerLine record）
- `LineWalletService.java:450`（walletLockKey）、`:488-496`（reload）、`:500-524`（appendLedger）、`:526-529`（LedgerLine record）
- `StaffWalletService.java:190-200`（runWithWalletLock inline key）、`:159-165`（reload raw）、`:167-182`（appendLedger 裸参数，本轮 #2 改）
- `DistributedLockService.java:12-24`（LOCK_PREFIX = `aicabinet:lock:`）
- `MerchantFeaturePacks.java:25-40`（perm code 前缀，非锁 key 前缀）
- `V387__drop_staff_wallet_frozen_cents.sql`（#2 已推）

## 附录 B · 相关记忆

- [[feedback-decision-brief-before-acting]]（简报纪律）
- [[feedback-no-dead-knobs-and-divergence-claims]]（S5/S6 判定依据）
- [[project-money-fee-rules-2026-10-16]]（提现口径：6‰ 手续费、staff 走线下打款标记）
- [[feedback-refspec-push-while-peer-dirty]]（若走 C，推送路径）
