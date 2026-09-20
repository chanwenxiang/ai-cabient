# 孤儿表定策（2026-09-19 初版 / **2026-09-20 重取并更正**）

> **范围**：Flyway 建表 **185** 张中，判定基础修好后**最终存活 155 张**，其中代码里**零引用**的 **13** 张该留还是该删、要不要建门禁。
>
> 🔴 **本文件 2026-09-19 初版的数字与分类已被推翻**（重取于 2026-09-20）。
> 初版写「零引用 42 张、B2 路线图占位 35 张、C 有读无写 2 张」，**这些数字建立在坏掉的判定基础上**：
> `scripts/devops/orphan-table-triage.py` 当时**只扫 `CREATE TABLE`、不减 `DROP TABLE`**，
> 并且用**文件名字典序**而非版本号数字序遍历迁移 ⇒ 被后续迁移删掉的表以「幽灵孤儿」形式留在清单里。
> 修好后：**零引用 42 → 13**、**C 有读无写 2 → 1**、**「路线图占位 35 张」实为大部分已不存在**。

## 0. 一页结论（2026-09-20 重取）

| 类别 | 张数 | 处置 |
|---|---|---|
| **A 有种子写入、但代码零引用** | 3 | 逐个判定功能是否已废弃；默认并入 B2 |
| **B1 与已实现机制重复 / 被取代**（有实证） | 3 | **应删**（本批**不执行**，见 §3） |
| **B2 其余零引用建表** | 10 | **保留**，登记为「未接线/未实现」占位，**不删、不建白名单门禁** |
| **C 有读者、无写者**（真正的断链形态） | 0 | ✅ **已核实：1 张并入 B2**（见 §4）⇒ **归零** |
| ~~已 DROP 的表（初版误列为孤儿）~~ | **30** | **不存在** ⇒ 从清单移除（见 §1） |

> 脚本口径的「零引用 13 张」= A 类 3 + B 类 10；B1 的 3 张是**从这 13 张里定性出来的**（不是额外一类）。

**门禁决策**：**不建**「零引用表」门禁（理由见 §5）；「**有读无写**」门禁**本批亦不建** ——
C 类核实后**归零**（§4），此刻落地就是一个**守着空集合**的门禁（失效形态③「判据恒真」）。

## 1. 口径与复现

```
python scripts/devops/orphan-table-triage.py
```

- 建表来源：`services/trade-service/src/main/resources/db/migration/V*.sql`（排除 `target/`）。
- 🔴 **按迁移版本号数字序 replay `CREATE` / `DROP`**，只对**最终存活**的表做孤儿判定。
  - **为什么必须数字化排序**：`sorted()` 的字典序会把 `V100..V199` 排在 `V1__init` **之前**（`'0'` < `'_'`），
    于是「后建的 DROP」被当成「先 DROP」⇒ 重放结果完全错乱（实测：`V136` 的 `DROP TABLE member_level`
    被排到 `V67` 建表之前 ⇒ `member_level` 假存活，并被当成「有读无写」写进了初版文档）。
  - **自证护栏**：出现「DROP 却找不到先前的 CREATE」即**拒绝输出**并以 `exit 2` 终止
    —— 这正是排序失效的指纹。

    **A/B 实测**：把排序改回字典序 ⇒ 立刻报出 20+ 条幽灵 DROP 并 `exit 2`（不输出任何清单）；还原后 `exit 0`。
- **独立交叉验证**：脚本算出的「最终存活 **155**」与库内
  `SELECT count(*) FROM pg_tables WHERE schemaname='public' AND tablename <> 'flyway_schema_history'` = **155**
  **完全一致** ⇒ 重放已能精确复现真实 schema（这也是判定基础可用的最强证据）。
- 代码引用：`services/**`（java/xml/yml）+ `clients/**`（ts/vue/js）+ `scripts/**`（mjs/py），
  排除 `target`/`node_modules`/`dist`/`unpackage`；按 snake_case 整词匹配。
- 写者证据：① 实体 `@TableName("t")`（含 `value =` 写法）② mapper XML 的字面量 `insert into t`/`update t set`/`delete from t`
  ③ Java 里的字面量同类语句。**「有实体」即算有写者** —— MyBatis-Plus 的 `BaseMapper` 自带 `insert/updateById/deleteById`。
- 种子证据：迁移脚本里的 `insert into t`。
- ⚠️ **口径局限（如实标注）**：
  1. 只按整词计数，**分不清「表名」与「恰好同名的字典类型键」**。`member_level` 初版被判「有读无写」，
     但它的 6 处引用**没有一处是 SQL** —— 全是 `displayLabel('member_level', …)` / `map.put("member_level", …)`
     这类**字典类型键**（`SysDictBootstrap` 用来渲染 `member.member_level` 的值）⇒ 它连「有读」都不成立，
     是**纯粹的判据假阳性**，且叠加了「表已被 `V136` DROP」这层错误。
  2. 构建产物（`services/**/static/admin/assets/*.js`、`clients/**/dist`）含表名也会被计成「引用」，
     会**抬高**引用计数（不会制造假孤儿）⇒ A/B 清单不受影响。
  ⇒ 本清单只是**候选集**；逐条定性必须另取 SQL / 实体级证据。

## 2. A 类：有种子写入、代码零引用（3 张）

| 表 | 种子 | 建表于 |
|---|---|---|
| `payment_risk_config` | 1 | V74__payscore_payment |
| `rate_limit_config` | 2 | V10, V71__concurrency_control |
| `revenue_share_detail` | 1 | V76__complete_optimization |

**读法**：这三张**有人往里塞数据、却没有任何代码读它** —— 比 B 类更"假"（B 类至少没装成在工作）。
默认按 B2 处置；`rate_limit_config` 见 §3（限流实际不走 DB）。

## 3. B1：与已实现机制重复 / 被取代（3 张，有实证）

| 表 | 现状 | 被谁取代（证据） |
|---|---|---|
| `sys_oper_log` | 存活、零引用 | `admin_audit_log`：**有实体** `AdminAuditLog.java`，是实际在用的审计表 |
| `rate_limit_config` | 存活、零引用（在 A 类里） | 限流实际由 Redis/Redisson 侧实现（`DistributedLockService`/Redisson 已就位）⇒ DB 版限流表未接线 |
| `rate_limit_record` | 存活、零引用 | 同上 |

这 3 张是**唯一有实证支持"删除"的一类**。

> 🔴 **初版把 `points_ledger` 也列在这里，已更正**：它被
> `V136__remove_points_and_messaging_legacy.sql` `DROP` 掉了，**表根本不存在**。
> 与它同批被删的还有 `member_points_log` / `points_redeem_item` / `push_record` / `message_template` /
> `share_reward` / `user_sign_in` / `member_level` 等 —— 这正是初版「零引用 42 张」虚高的来源。

> ⚠️ 但删除**不在本批执行**：`DROP TABLE` 不可逆，须与 DB 备份 + 回滚方案成对做，且要确认**生产库是否已有数据**。
> 本批只给结论。

## 4. C 类：有读者、无写者 —— **已核实归零（1 张 → 并入 B2）**

| 表 | 初版判读 | **重取结论** |
|---|---|---|
| `member_level` | 「有读者（`SysDictBootstrap`）但无代码写者」 | 🔴 **判据假阳性 + 表已不存在**：`V136` 已 `DROP TABLE member_level`；6 处「引用」全是**字典类型键**（`displayLabel('member_level', …)`），无一处 SQL ⇒ **从清单移除** |
| `promotion_device` | 「读者仅在改机号逻辑里，无写者」 | ✅ **核实为合法占位**：`V66__coupon_promotion_system.sql` 建的「促销活动-设备」绑定表，库内 **0 行**；唯一引用是 `DeviceIdRenameService.BLOCKING_TABLES` 的防御性 `SELECT count(*)`（改机号前检查是否被引用）。**无写者 ⇒ 该守卫恒不触发，无害** ⇒ **归入 B2 保留** |

**⇒ C 类（有读无写）当前为 0 张。** 也就是说：**当前仓里没有 `recognition_result` 那种「有人读、没人写」的断链形态**
（那张表已在更早批次接上写者 `RecognitionResultWriter`）。

**为什么当初单列**：那才是 `recognition_result` 所属的形态 —— **有人读、没人写**，于是「读到的永远是空/旧数据」，
而门禁和监控都看不出来。**结论：C 类已逐张核实完，无待办。**

## 5. 门禁决策

**为什么不建「零引用表」门禁**（沿用并强化上一批的否决）：

1. 需要大量白名单（B2 全是合法占位）——白名单本身会腐烂，且新增表默认要改白名单 ⇒ 门禁变成噪音源；
2. 更致命：它**只区分得出「完全无引用」**。`recognition_result` 当年若有人写过一个 `findById` 读方法，
   引用数就 > 0，**会被判过** —— 也就是说这条判据**恰好看不见它最该抓的那类缺陷**（失效形态⑥「信号在骗读者」）。
3. **本批新增的第 3 条理由**：它的输入是 `orphan-table-triage.py` 的清单，而那份清单刚被证明**长期带着假阳性**
   （30 张幽灵表 + 1 张假「有读无写」）。**在没修好的判定基础上建门禁＝把假阳性制度化。**

**为什么不建「有读无写」门禁（本批）**：C 类重取后**归零**（§4）。对**空集合**建门禁 ⇒ 判据恒真、
永远不会红 ⇒ 它就是一条**装饰**（失效形态③「判据恒真」＋⑤「无人消费」）。**要建，必须等它真有成员。**

**如果将来真出现成员，判据应当这样设计**（留档）：

- **判据**：对每张表，若「有读者证据」且「无写者证据」⇒ 红。
  - 读者证据：`@TableName` 实体 / mapper XML 的 `select … from t` / SQL 里的 `from t`
  - 写者证据：字面量 `insert|update|delete` **或**实体存在（MyBatis-Plus 自带 CUD）**或**白名单（显式声明「只读配置表」）
- 🔴 **前置条件**：`orphan-table-triage.py` 的护栏必须仍在（`exit 2` 路径可红），且「整词计数分不清字典类型键」
  这一局限要么被修掉、要么在门禁里显式排除非 SQL 引用 —— 否则 `member_level` 那类假阳性会再犯一次。
- **判据有效性必须自证**：仿 `scripts/devops/verify-*-drift.py` —— 注入「删掉某表的写者」必须变红，还原必须回绿。
- **可指出让它红的方式**：把任一有实体的表的 `@TableName` 删掉、或把 mapper XML 的 insert 删掉 ⇒ 必红。

**为什么不在本批建**：新门禁要插进聚合链（且**必须插在链首附近**，否则排在已知红点后根本不执行）、
要跑 `check:audit-gates-wiring` 守着看门人、要配套 drift 脚本 —— 这是完整批次的工作量；且 §4 归零后
**当下无成员可守**，先建只会得到一条不会红的门禁。

## 6. 建议的后续动作（按优先级）

1. **B1 三张重复表**（`sys_oper_log` / `rate_limit_config` / `rate_limit_record`）：确认生产数据 → 出 `DROP TABLE` 迁移（含回滚说明）→ 单独批次执行。
2. **A2 张 + B2 8 张**（共 10 张零引用）：判定功能是否废弃，并入 1 或登记为占位。
3. **`promotion_device`**：已判定为合法占位（§4），保留；若将来促销域落地，它的写者会自然出现。
4. **「有读无写」门禁**：**等 C 类出现真实成员再建**（§5 已留判据设计与前置条件）。
