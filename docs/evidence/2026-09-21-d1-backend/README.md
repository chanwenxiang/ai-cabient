# D1 储值等级 —— 后端实施与取证

> 决策来源：用户拍板 **D1 选 A** —— 先做后端（迁移 + 「消费或储值取高」+ 单测），
> admin 那一列等 `static/admin` 产物争用结束后再补。
> 前置调研（竞品对比 → 选 A 复用等级框架而非新建第二套体系）见
> `docs/evidence/2026-09-21-d1-deposit-level/README.md`。

## 1. 语义

```
会员等级 = max( 累计消费档 , 累计净充值档 )
累计净充值 = Σ (recharge_order.amount_cents − recharge_order.refunded_cents)
             WHERE user_id = ? AND status IN ('PAID','REFUNDED') AND paid_at IS NOT NULL
```

- 两条口径**都单调不减** ⇒ 等级只升不降，与项目既有「`applyMemberStatsDelta` 只加不减」一致，
  因此**不需要**引入保级/降级机制。
- 储值门槛取消费门槛的**一半**（0/500/2000/5000 vs 0/1000/5000/10000），运营台可调。
- **不新增折扣权益**：`recharge.bonus.percent`（充值赠送）与等级 `priceDiscountPct` 当前可同时
  生效，再给储值等级加折扣会折上折穿透毛利。本设计只让用户**更快达到**既有权益。

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `db/migration/V283__member_level_min_recharge.sql` | 新增：加 `min_recharge` 列 + 补回 4 行等级规则 + 回填门槛 |
| `domain/MemberLevelRule.java` | +8 行：新增 `minRecharge` 字段 |
| `mapper/RechargeOrderMapper.java` | +8 行：`sumNetRechargeByUser(userId)` |
| `resources/mapper/RechargeOrderMapper.xml` | +18 行：净充值聚合 SQL（复用 `sumPaidAmountBetween` 口径） |
| `service/MemberService.java` | +106/-14：判定改「取高」、新增 `refreshLevelOnRecharge`（`REQUIRES_NEW`） |
| `service/PaymentService.java` | +21 行：构造器注入 `MemberService`；充值入账后尽力而为地刷新等级 |
| `test/.../MemberServiceDepositLevelTest.java` | **新增** 9 例 |
| `test/.../PaymentServiceTest.java` | 5 处构造点补 `memberService`（见 §7 坑 1） |
| `test/.../MemberStatsConcurrencyTest.java`、`MemberOnOrderPaidConcurrencyTest.java` | 构造点补 `rechargeOrderRepository` |

`MemberLevelRuleDto` **未改** —— admin 列属第二段；见 §7 坑 3 的风险闸门。

## 3. 关键取舍：实时聚合而不是在 member 上物化一列

物化 `member.total_recharge` 需要**在每个入账点与退款点**挂钩子：漏一处就长期漂移，
且历史数据要单独 backfill，退款还要再减一次。

实时聚合 `recharge_order` 天然幂等、可自愈，退款语义由既有的 `refunded_cents` 直接表达
（**全退** → `status='REFUNDED'`；**部分退** → 保持 `PAID` 且累加 `refunded_cents`，
两种都靠 `amount − refunded` 得到净额）。

代价是每次等级重算多一次 `SUM` 查询，而重算只发生在两个低频入口：
订单支付后（`onOrderPaid`）与充值入账后（`refreshLevelOnRecharge`）。

代价可控的另一个原因：`PaymentService` **原本没有** `MemberService` 依赖，本次是新增注入；
两者无循环（`MemberService` 不依赖 `PaymentService`）。

## 4. 迁移 V283

### 4.1 为什么同时「补回 4 行」

`V79:66-71` 与 `V99:57-62` 都 `INSERT INTO member_level_rule ... ON CONFLICT (level_code) DO NOTHING`
种过这 4 档。但取证发现：

```
$ docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tA -c "select count(*) from member_level_rule;"
0
$ ... select version, description, success from flyway_schema_history where version in ('79','99');
79|member management|t
99|member marketing consumer|t
```

即：**迁移都成功应用过，但行现在不在** —— 种子只在首次生效，行一旦被删，后续迁移不会补
（`V163:30-33` 的四条 `UPDATE ... WHERE level_code = ...` 在 0 行时同样什么都不会发生）。

补充旁证：`V136` 只 `DROP COLUMN`，**不含** `DELETE FROM member_level_rule`；
`.tmp/uat-snapshot/before-uat.sql`（Sep 20 00:21 的快照）里 `member_level_rule` 的
`COPY ... FROM stdin;` 段**紧接 `\.` 即为空** ⇒ 0 行状态至少持续到 Sep 20。

后果：`calculateMemberLevel` 恒落 `NORMAL` ⇒ **会员等级 / 积分倍率 / 会员价实际全部未生效**。
储值门槛无处可挂，故一并幂等补回。

### 4.2 为什么不加 `seed_env` 守卫（与 V244 不同）

等级规则是**功能必需配置**而非 demo 数据 —— 表为空时等级体系就是坏的。口径与 `V79/V99` 原始种子一致。

### 4.3 幂等性证据

三处语句分别幂等（`ADD COLUMN IF NOT EXISTS` / `ON CONFLICT DO NOTHING` /
`UPDATE ... WHERE min_recharge IS NULL`）。手工在同一 dev PG 上连跑两遍：

`docs/evidence/2026-09-21-d1-backend/logs/v283-manual-apply-twice.log`

```
== 1. 执行前状态 ==
rows|0
has_min_recharge|0
== 2. 第 1 次应用 ==
ALTER TABLE / COMMENT / INSERT 0 4 / UPDATE 1 ×4
== 3. 中间态 ==
NORMAL|0.00|1000.00|0.00|1|ACTIVE
SILVER|1000.00|5000.00|500.00|2|ACTIVE
GOLD|5000.00|10000.00|2000.00|3|ACTIVE
PLATINUM|10000.00||5000.00|4|ACTIVE
== 4. 第 2 次应用（幂等性）==
NOTICE: column "min_recharge" of relation "member_level_rule" already exists, skipping
INSERT 0 0 / UPDATE 0 ×4
== 6. 幂等性判据 ==
rows|4
null_min_recharge|0
```

复现脚本：`scripts/verify-v283-manual.sh`。

### 4.4 门禁

```
$ node scripts/check-flyway-seed-separation.mjs
[check-flyway-seed-separation] 新增 V 迁移 2 个、种子 0 个（文件共 2）
[check-flyway-seed-separation] OK：种子与结构分离策略得到遵守

$ node scripts/check-migration-safety.mjs
[check-migration-safety] reviewed=false .../V283__member_level_min_recharge.sql
[check-migration-safety] OK (2 new script(s))
```

**A/B 负向对照**（证明门禁真的在看这份文件，而不是「跑了但没扫到」）：摘掉
`-- MIGRATION_KIND: backfill` 再跑 ⇒ 应付诸红：

```
[check-flyway-seed-separation] FAIL .../V283__member_level_min_recharge.sql: 含数据写语句但未声明种类。
[check-flyway-seed-separation] 1 处不合规
```

**全量门禁链**（`node scripts/run-audit-gates.mjs`，挂 nopipe 垫片）：

```
[run-audit-gates] 聚合链 34 个门禁，失败 1 个
✗ check:line-endings
```

失败项**与本批无关**：`clients/admin-vue/src/views/dashboard/BigScreenView.vue`（w/crlf）是并发会话
正在写的前端文件。`services/**` 不在 `.gitattributes` 的 `eol=lf` 约束内（只钉了 `clients/**`、
`packages/**`、`static/admin/**`），且邻居 `V281/V282` 磁盘同样是 CRLF ⇒ 本批文件不违规。
原日志：`logs/run-audit-gates.log`。

## 5. 判据

### 5.1 语义单测（9 例）

`test/.../MemberServiceDepositLevelTest.java`：

| 用例 | 判什么 |
|---|---|
| `rechargeOnly_upgradesByDepositTier_whileSpentIsZero` | 消费 0 + 净充值 5000 ⇒ PLATINUM（储值路径可达） |
| `rechargeOnly_doesNotUpgradeBelowFirstDepositTier` | 净充值 499 < 500 ⇒ 仍 NORMAL，且**不写库** |
| `spentOnly_stillUpgradesByLegacySpendTier` | 消费 1500 ⇒ SILVER（**既有行为回归**） |
| `takesHigherTierOfBothPaths` | 消费 1200(SILVER) + 净充值 3000(GOLD) ⇒ GOLD |
| `nullMinRecharge_meansTierHasNoDepositPath` | 该档无 `min_recharge` ⇒ 充值再多也不升 |
| `emptyRuleTable_fallsBackToNormalWithoutThrowing` | 规则表为空 ⇒ NORMAL，不抛异常 |
| `refreshLevelOnRecharge_createsMemberWhenAbsentThenUpgrades` | 还不是会员 ⇒ 创建 + 升级 |
| `refreshLevelOnRecharge_isNoOpWhenLevelAlreadyCorrect` | 等级已正确 ⇒ **零写库** |
| `adminUpsert_leavesMinRechargeUntouched` | 运营台保存规则**不得**清掉 `min_recharge`（見 §7 坑 3） |

### 5.2 结果

**① 目标类 + 受影响既有类**（`mvn -B -pl services/trade-service -am clean test -Dtest=...`）：

```
EXITCODE=0
MemberLevelAdminConcurrencyTest.xml    testcase=3  failure=0 error=0
MemberOnOrderPaidConcurrencyTest.xml   testcase=2  failure=0 error=0
MemberServiceDepositLevelTest.xml      testcase=9  failure=0 error=0   <-- 新增
MemberStatsConcurrencyTest.xml         testcase=3  failure=0 error=0
PaymentServiceTest.xml                 testcase=21 failure=0 error=0
PointsRedeemServiceTest.xml            testcase=5  failure=0 error=0
```

⇒ **43 例 / 0 失败 / 0 错误**。

**② 整模块全量**（改的是共享 service，纪律要求跑整模块，不能只跑新增类）：

```
CMD=mvn -B -pl services/trade-service -am clean test
EXITCODE=0
TOTAL classes=295 testcase=1291 failure=0 error=0
[INFO] BUILD SUCCESS
```

原始判据：`logs/d1-target-tests.txt`、`logs/d1-module-tests.txt`。

> 日志里另有一行 `[ERROR] Surefire is going to kill self fork JVM. The exit has elapsed 30 seconds
> after System.exit(0).` —— 这是 Maven 对「测试内部调用 `System.exit`」的**非致命**告警，
> `BUILD SUCCESS` 才是判据。

### 5.3 未覆盖边界

- **SQL 的金额口径**（`amount − refunded` / `status` / `paid_at`）在 XML 里，Java 单测用 mock
  覆盖不到。已用真实数据对照验证：dev 库里 2 张充值单，`CANCELLED`（`paid_at IS NULL`）被正确
  排除，`PAID` 单计入 ⇒ 净 2000 分，与手工逐单核对一致。
- **`REQUIRES_NEW` 的事务语义**（异常不把外层标记 rollback-only）需要 Spring 上下文，单测覆盖不到。

## 6. A/B 漂移（判据有效性负向对照）

**注入**：把等级判定的「或」条件改成只判消费口径 ——

```diff
- if (matchesSpentTier(rule, totalSpent) || matchesRechargeTier(rule, netRecharge)) {
+ if (matchesSpentTier(rule, totalSpent)) {
```

**结果**（`scripts/ab-deposit-tier.ps1`，原始输出 `logs/ab-deposit-result.txt`）：

```
A_INJECT   applied=True
A_DRIFT    exit=1 testcase=9 failure=3 error=0
RESTORE    anchor_present=True
B_RESTORED exit=0 testcase=9 failure=0 error=0
VERDICT    drift_red=True restored_green=True
```

A 臂变红的**恰好是 3 个储值用例**：

```
[ERROR] MemberServiceDepositLevelTest.rechargeOnly_upgradesByDepositTier_whileSpentIsZero:71
        expected: <PLATINUM> but was: <NORMAL>
[ERROR] MemberServiceDepositLevelTest.refreshLevelOnRecharge_createsMemberWhenAbsentThenUpgrades:180
        expected: <PLATINUM> but was: <NORMAL>
[ERROR] MemberServiceDepositLevelTest.takesHigherTierOfBothPaths:120
        expected: <GOLD> but was: <SILVER>
```

其余 6 例（含消费口径回归 `spentOnly_stillUpgradesByLegacySpendTier`）**保持绿** ⇒
判据既有灵敏度（sensitivity）又有精确性（precision），不是「一变就全红」的粗判。

**还原**：`cmp` 备份与工作区源码 ⇒ `IDENTICAL`（逐字节一致），注入无残留。

> 这一步刻意**不是**「跑一遍绿就算数」：判据「存在」与判据「有效」是两件事，
> 只有注入漂移后**亲眼看到它红**，绿色才有意义。

## 7. 踩坑

1. **改共享类的构造器签名，必须扫全部构造点，而不只是同名类的**。
   本次给 `PaymentService` 加参数后，只 grep 了 `new MemberService(` 就动手，
   漏掉 `PaymentServiceTest.java` 里 5 处 `new PaymentService(...)` ⇒ `testCompile` 直接失败。
   **正确做法**：改动构造器后按 `new <被改类>(` 全仓 grep，而不是按调用方的类别名去猜。
2. **`Remove-Item` 删 `target/surefire-reports` 会被安全删除垫片拦下**：
   `SAFE_DELETE_FAIL_CLOSED ... reason=trash-failed`，脚本直接抛错。
   `mvn clean` 自身就会清 `target`，不需要手动删。
3. **新增列可能在既有「全量 upsert」里被静默抹掉** —— 这是本次特意设闸门的地方：
   `MemberLevelAdminService.doUpsert` 逐字段 `set*`，没有 `setMinRecharge`；
   因为 `MemberLevelRuleDto` 里也没有 `min_recharge`（MyBatis-Plus 3.5.5 默认
   `updateStrategy=NOT_NULL`，`application.yml` 未覆盖该项），所以**实体的 null 与
   「DTO 缺字段」都不会清列**。`adminUpsert_leavesMinRechargeUntouched` 锁住这个前提：
   一旦有人给 doUpsert 加 `setMinRecharge(dto.minRecharge())` 而 DTO 仍无该字段，测试必须先红。
4. `member_level_rule` 在 dev 库 0 行这件事**不是本批引入**，但它会让「只写 `UPDATE`」的迁移
   变成「写了没跑」—— 所以 V283 必须同时 `INSERT ... ON CONFLICT DO NOTHING`。
5. **`.gitignore` 的 `*.log` 会把证据包整片吞掉**（收尾时才发现）：本包 4 份 `.log`
   （`ab-drift.log` / `ab-restored.log` / `run-audit-gates.log` / `v283-manual-apply-twice.log`）
   全部落在 `.gitignore:87 *.log` 的忽略范围内 ⇒ **本 README 里写着路径、点进去却没有**
   ⇒ 「证据对后来者不存在」。同批 G9 复核包也有 2 份中招。
   **修法**（本批已落地，改的是仓库根 `.gitignore`）：紧随 `*.log` 之后加

   ```
   !docs/evidence/**/*.log
   ```

   并用 `git check-ignore` 双向验证：证据包内 15 个文件全为 `TRACKABLE`，
   而 `services/trade-service/foo.log` 仍被忽略（确认否定规则没有放大范围）。
   ⚠️ 不要用 `git add -f` 绕过 —— 那是一处一次的，下一个人照样会漏。

## 8. 未做 / 待验

- **admin 列**（`MemberLevelsView.vue` + `MemberLevelRuleDto` + 重建 `static/admin`）：第二段，
  等 `static/admin` 产物争用结束。
- **`shared-types` 重生成**：本次未改任何 DTO/端点，`openapi.ts` 无需变更。
- **真机/emulator**：本机无 emulator（已实测，见 `docs/evidence/2026-09-21-g9-review/README.md`）。
- **Flyway 正式应用 V283**：需要重建 `trade-service` 镜像（迁移打在 jar 里）。
