# 孤儿表定策（2026-09-19）

> **范围**：Flyway 建表 **185** 张中，代码里**零引用**的 **42** 张该留还是该删、要不要建门禁。
> **上一批的结论**（`docs/evidence/2026-09-19-recognition-result-persistence/README.md` §4）：当时是 **43 张**，
> 建议独立立项定策。本文即该立项的产出。
> 🔴 **数已重取**：本批把 `recognition_result` 接上了写者（`RecognitionResultWriter`）⇒ **43 → 42**。
> 这条差额本身是对判据的负向对照：**接一个写者，判据就少一条** ⇒ 判据在动，不是恒定输出。

## 0. 一页结论

| 类别 | 张数 | 处置 |
|---|---|---|
| **A 有种子写入、但代码零引用** | 3 | 逐个判定：功能是否已废弃。默认并入 B2 |
| **B1 与已实现机制重复 / 被取代**（有实证） | 4 | **应删**（附建议迁移草案，本批**不执行**） |
| **B2 路线图功能占位、本仓未实现** | 35 | **保留**，登记为「未实现功能占位」，**不删、不建白名单门禁** |
| **C 有读者、无写者**（真正的断链形态候选） | 2 | 逐张核实（本批列为待办，见 §4） |

**门禁决策**：**不建**「零引用表」门禁（理由见 §5）；**改建议**只对 **C 类**（有读无写）建门禁 —— 那才是
`recognition_result` 所属的、真会出事的形态。本批**不建**，理由与判据设计写在 §5。

## 1. 口径与复现

```
python scripts/devops/orphan-table-triage.py
```

- 建表来源：`services/trade-service/src/main/resources/db/migration/V*.sql`（排除 `target/`）。
- 代码引用：`services/**`（java/xml/yml）+ `clients/**`（ts/vue/js）+ `scripts/**`（mjs/py），
  排除 `target`/`node_modules`/`dist`/`unpackage`；按 snake_case 整词匹配。
- 写者证据：① 实体 `@TableName("t")`（含 `value =` 写法）② mapper XML 的字面量 `insert into t`/`update t set`/`delete from t`
  ③ Java 里的字面量同类语句。**「有实体」即算有写者** —— MyBatis-Plus 的 `BaseMapper` 自带 `insert/updateById/deleteById`。
- 种子证据：迁移脚本里的 `insert into t`。
- ⚠️ **口径局限（如实标注）**：`services/trade-service/src/main/resources/static/admin/assets/*.js` 是**构建产物**，
  它含表名时也会被计成「引用」。这会**抬高**引用计数（如 `member_level` 的 6 处里有 2 处是产物）。
  但它**不可能**制造假孤儿（孤儿是零引用，排除产物只会让引用更少）⇒ **A/B 类清单不受影响**，仅 §4 的 C 类需留意。

## 2. A 类：有种子写入、代码零引用（3 张）

| 表 | 种子 | 建表于 |
|---|---|---|
| `payment_risk_config` | 1 | V74__payscore_payment |
| `rate_limit_config` | 2 | V10, V71__concurrency_control |
| `revenue_share_detail` | 1 | V76__complete_optimization |

**读法**：这三张**有人往里塞数据、却没有任何代码读它** —— 比 B 类更"假"（B 类至少没装成在工作）。
默认按 B2 处置；`rate_limit_config` 另见 §3（限流实际不走 DB）。

## 3. B1：与已实现机制重复 / 被取代（4 张，有实证）

| 表 | 零引用 | 被谁取代（证据） |
|---|---|---|
| `points_ledger` | ✅ | `member_points_log`：**有实体** `MemberPointsLog.java`、**有 mapper**、且被 `DataConsistencyService`、`GrowthLogArchiveScheduler` 实际使用（4 处引用）。同一领域两套表，`points_ledger` 是**弃用的一套** |
| `sys_oper_log` | ✅ | `admin_audit_log`：**有实体** `AdminAuditLog.java`（1 处引用），是实际在用的审计表 |
| `rate_limit_config` | ✅ | 限流实际由 Redis/Redisson 侧实现（本仓 `DistributedLockService`/Redisson 已就位）⇒ DB 版限流表未接线 |
| `rate_limit_record` | ✅ | 同上 |

这 4 张是**唯一有实证支持"删除"的一类**。

> ⚠️ 但删除**不在本批执行**：`DROP TABLE` 不可逆，须与 DB 备份 + 回滚方案成对做，且要确认**生产库是否已有数据**。
> 本批只给结论。

## 4. C 类：有读者、无写者（2 张，待核）

| 表 | 引用数 | 种子 | 为什么可疑 |
|---|---|---|---|
| `member_level` | 6（含 2 处是构建产物） | 1 | 有读者（`SysDictBootstrap`）但**无代码写者**；等级可能只由种子/字典初始化 ⇒ 需确认「等级是否允许运行期变更」 |
| `promotion_device` | 1（`DeviceIdRenameService`） | 0 | 读者仅出现在改机号的重命名逻辑里，无写者 ⇒ 需确认是「只读快照」还是断链 |

**为什么单列**：这才是 `recognition_result` 所属的形态 —— **有人读、没人写**，于是「读到的永远是空/旧数据」，
而门禁和监控都看不出来。我**不**在本文断言它们是缺陷：`member_level` 大概率由字典初始化（合法只读配置表），
`promotion_device` 需看业务。**结论：列入待办逐张核实，不下结论。**

## 5. 门禁决策：不建「零引用」门禁，改建议「有读无写」门禁

**为什么不建「零引用表」门禁**（沿用并强化上一批的否决）：

1. 需要 **35+ 条白名单**（B2 全是合法占位）——白名单本身会腐烂，且新增表默认要改白名单 ⇒ 门禁变成噪音源；
2. 更致命：它**只区分得出「完全无引用」**。`recognition_result` 当年若有人写过一个 `findById` 读方法，
   引用数就 > 0，**会被判过** —— 也就是说这条判据**恰好看不见它最该抓的那类缺陷**（失效形态⑥「信号在骗读者」）。

**建议建的门禁**（本批**不建**，属独立批次）：

- **判据**：对每张表，若「有读者证据」且「无写者证据」⇒ 红。
  - 读者证据：`@TableName` 实体 / mapper XML 的 `select ... from t` / SQL 里的 `from t`
  - 写者证据：字面量 `insert|update|delete` **或** 该表对应实体存在（MyBatis-Plus 自带 CUD）**或** 白名单（显式声明「只读配置表」）
- **当前会红**：仅 **2** 张（§4）⇒ 白名单只需 2 条，规模可控。
- **判据有效性必须自证**：`scripts/devops/verify-*-drift.py` 同款 —— 注入「删掉 recognition_result 的写者」必须变红，
  再还原必须回绿。（本批的 `orphan-table-triage.py` 已具备同一套读者/写者判定，可直接抽出门禁。）
- **可指出让它红的方式**：把任一有实体的表的 `@TableName` 删掉、或把 mapper XML 的 insert 删掉 ⇒ 必红。

**为什么本批不建**：新门禁要插进聚合链（且**必须插在链首附近**，否则排在已知红点后根本不执行）、
要跑 `check:audit-gates-wiring` 守着看门人、要配套 drift 脚本 —— 这是一个完整批次的工作量，
塞进"孤儿表定策"里做既超范围又无法充分自证。**决策本身（建什么、不建什么）在本批完成。**

## 6. 建议的后续动作（按优先级）

1. **B1 四张重复表**：确认生产数据 → 出 `DROP TABLE` 迁移（含回滚说明）→ 单独批次执行。
2. **C 类两张**：逐张核实是「合法只读」还是「断链」，把结论回填本文件。
3. **「有读无写」门禁**：按 §5 设计落地（含 drift 负向对照）。
4. **A 类三张**：判定功能是否废弃，并入 1 或 3。
