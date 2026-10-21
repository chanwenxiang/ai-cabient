# 数据迁移安全清单（Flyway / PostgreSQL）

> 目标：避免生产大表上裸 `DROP COLUMN` / 重索引长时间锁表。历史迁移（如 V128）已执行的不可改；**新增**迁移必须过本清单。

## 1. PR 必答（复制到 PR 描述）

- [ ] 影响表与预估行数（staging 实测或 `pg_stat`）
- [ ] 是否在线 DDL / 分批 backfill（见下）
- [ ] staging 已跑通；记录锁等待 / 耗时
- [ ] 回滚方案（新 migration 回补，或明确不可逆）
- [ ] 是否需停写窗口（是则写清时长与公告）

## 2. 危险操作与推荐做法

| 操作 | 风险 | 推荐 |
|---|---|---|
| `DROP COLUMN` / 改类型 | AccessExclusiveLock，大表可卡分钟级 | 先停写引用 → 发版去读列 → 下个版本再 DROP；或低峰 + 短停写 |
| `ALTER COLUMN ... TYPE` / `SET NOT NULL` | 可能全表重写或长锁 | 热表必须 `MIGRATION_REVIEWED` + staging 锁等待实测；分批 backfill 后再加约束 |
| `ADD COLUMN ... DEFAULT`（旧 PG） | 全表重写 | PG 11+ 常量默认较安全；非标量默认仍慎用 |
| `CREATE INDEX`（无 CONCURRENTLY） | 写锁/阻塞 | **大表**用 `CREATE INDEX CONCURRENTLY`（单独 migration，不可与事务型语句混写） |
| `DROP INDEX` | 可能阻塞 | 大表优先 `DROP INDEX CONCURRENTLY` |
| `ALTER ... SET NOT NULL` + 全表 UPDATE | 长事务 | 分批 backfill → 再加约束 |
| `DELETE` 大范围 | 膨胀 / 锁 | 分批 `DELETE ... LIMIT` + `VACUUM` |
| 改主键 / 大表 FK | 长锁 | 新列双写 → 切换 → 删旧 |

PostgreSQL **没有** MySQL 的 `pt-osc`/`gh-ost` 同款；大表变更依赖 `CONCURRENTLY`、分批、或逻辑复制切换。

## 3. Flyway 多实例

- Flyway 在 PostgreSQL 上使用 **advisory lock**，多副本启动时只有一个实例执行迁移。
- **禁止**关闭 `spring.flyway.enabled` 后手工并行跑脚本。
- 危险 DDL 不要塞进「与业务同发」的紧急热修；单独 migration + 观察窗口。

## 4. 仓库门禁

```bash
pnpm check:migration-safety
```

对 **新增** `V*.sql`（相对 `origin/dev` 或 `origin/main`）扫描：

- 裸 `DROP COLUMN`（无评审注释）→ 失败
- 热表上的 `ALTER COLUMN` / `TYPE` / `SET NOT NULL`（无 `MIGRATION_REVIEWED`）→ 失败
- 非热表 `ALTER COLUMN` 无评审 → 警告
- 大表名（`cabinet_order` / `shopping_session` / `payment_operation` 等）上的非 CONCURRENTLY 建索引 → 警告/失败

历史已合并脚本不重审；只拦 PR 增量。

## 5. 脚本头注释模板（推荐）

```sql
-- MIGRATION_REVIEWED: yes
-- TABLES: cabinet_order (~估行数)
-- LOCK_RISK: low|medium|high
-- ROLLBACK: Vxxx__rollback_note.sql 或 irreversible
-- NOTES: 低峰执行；已在 staging 验证锁等待 < Ns
```

含 `MIGRATION_REVIEWED: yes` 时，若脚本含高风险 DDL（`DROP COLUMN` / 热表 `ALTER` / 热表非 CONCURRENTLY 建索引），**必须同时**具备：

- `LOCK_RISK: low|medium|high`
- `NOTES:` 非空（说明 staging 验证或执行窗口）
- `TABLES:` 非空（表名与估行数）

禁止只写 `MIGRATION_REVIEWED: yes` 空头豁免。`LOCK_RISK: high` 时 NOTES 同样强制非空。

## 5.5 方言：业务库只有 PostgreSQL，Flyway 执行的 SQL 里不许出现 MySQL 语法

**门禁**：`pnpm check:migration-mysql-isms`（`scripts/check-mysql-isms-in-migrations.mjs`），
已进聚合链 `check:audit-gates`（本机 `pnpm preflight` 由此覆盖）；CI 的 build job 另在
`mvn verify` **之前**单跑 gate + self-test 两步，把反馈从「整个后端 job」降到秒级。
扫描面 = Flyway 执行的 `services/trade-service/src/main/resources/db/`（migration + seed + rollback）；
它**不依赖基线 ref**，与 §4 的 `check:migration-safety`、§6 的 `check:flyway-seed-separation` 不同
（那两道按「相对基线新增」判定，故刻意不进聚合链）。

**为什么单独立一道**：`V395__sku_pack_quantity.sql` 写过 MySQL 的行内列注释
`ADD COLUMN pack_qty INT NOT NULL DEFAULT 1 COMMENT '件装数'`。纯 Mockito 单测**全绿**（根本不起
Spring 上下文），而 PostgreSQL 直接 `syntax error at or near "COMMENT"` ⇒ Flyway 失败 ⇒ 56 个
`@SpringBootTest` 上下文加载集体 error（lessons #328）。**方言错不会让写它的人失败，只会让后面
所有起库的人失败**，所以必须在 CI 早于 mvn 拦下。

| MySQL 写法 | PostgreSQL 写法 |
|---|---|
| `ADD COLUMN x INT COMMENT '…'` | `ADD COLUMN x INT` + `COMMENT ON COLUMN tbl.x IS '…'` |
| `` `col` `` 反引号 | 直接去掉（只在大小写/保留字时用 `"col"`） |
| `AUTO_INCREMENT`、`ENGINE=InnoDB DEFAULT CHARSET=utf8mb4` | `BIGSERIAL` / `GENERATED … AS IDENTITY`；删掉表尾选项 |
| `ON UPDATE CURRENT_TIMESTAMP` | `BEFORE UPDATE` 触发器（本仓既有做法） |
| `ON DUPLICATE KEY UPDATE` / `REPLACE INTO` / `INSERT IGNORE` | `ON CONFLICT (键) DO UPDATE SET … / DO NOTHING` |
| `IFNULL` / `GROUP_CONCAT(… SEPARATOR ',')` / `DATE_FORMAT` / `CURDATE` | `COALESCE` / `string_agg(…, ',')` / `to_char` / `CURRENT_DATE` |
| `TINYINT(1)` / `INT(11) UNSIGNED` / `DATETIME` / `MEDIUMTEXT` / 裸 `DOUBLE` | `boolean` / `int` / `timestamp` / `text` / `double precision`（`numeric(10,2)` 合法，别改） |
| `ADD COLUMN x … AFTER y` | 删掉 `AFTER`（PG 不支持列顺序，新列只能加在末尾） |
| `LIMIT 10, 20` | `LIMIT 20 OFFSET 10` |
| `IF(cond, a, b)` | `CASE WHEN cond THEN a ELSE b END` |
| `DELIMITER //` | 不需要；写函数/匿名块用 `DO $$ … $$;` |
| `FORCE INDEX` / `ALGORITHM=INPLACE, LOCK=NONE` | 由规划器决定；在线建索引走 `CREATE INDEX CONCURRENTLY` |
| `ALTER TABLE … ADD CONSTRAINT IF NOT EXISTS` | 直接 `ADD CONSTRAINT`（V 迁移天然只跑一次），或查 `pg_constraint` 后 `DO` 块条件执行 |

判据实现取向（改门禁前必读）：**先剥 `--` / `/* */` 注释**（本仓注释里成片用反引号包裹标识符是
markdown 习惯，不剥 ⇒ 恒红 ⇒ 门禁被人关掉）；**匹配整段代码文本而不是逐行**
（`ADD COLUMN … AFTER …` 会跨行）；**扫到 0 个文件必须红**（目录被改名时的「0 命中」是假绿，
故有 `MIN_SQL_FILES` 下界锚点）；红/绿两个方向由
`node scripts/check-mysql-isms-in-migrations.test.mjs` 在**真 Flyway 目录**里跑 28 个用例证明
（含 `CREATE TYPE … AS ENUM` 必须放行、`numeric(10,2)` 必须放行、注释里的 MySQL 片段必须不算数）。

**不在门禁范围内**（已知漏口，别当成全覆盖）：

- `infra/xxl-job/*.sql` 是 **MySQL**：`docker-compose.full.yml` 里 xxl-job 用 `mysql:8.0` 容器 +
  `jdbc:mysql://xxl-job-mysql:3306/xxl_job`，由 xxl-job 官方表结构决定，刻意排除。
- MyBatis `*Mapper.xml` / `@Select` 里的运行期 SQL：方言错在那里只坏一条查询，失败形态不同，
  需要另一道门禁（2026-10-21 实测该面当前无 MySQL 专有写法）。
- `docs/` 里的示例/证据 SQL：不执行，不扫。

## 6. 种子数据与结构分离（O9，2026-09-19）

`db/migration/` 里 277 个迁移中，种子数据一直与结构 DDL 混在一起
（`V11__production_sku_seed`、`V25__demo_catalog_seed`、`V56__vision_mapping_seed`、
`V134/V135__seed_*`、`V222/V223__*_seed`、`V252__seed_*`、`V275__scheduled_task_seed_gap` …）。
后果：改一次种子就要吃掉一个不可变的版本号；「当前基线长什么样」没人能一眼看出。

**新约定（只向前生效）：**

| | 位置 | 前缀 | 语义 |
|---|---|---|---|
| 结构 | `db/migration/` | `V*` | 一次性、checksum 不可变 |
| 种子 | `db/seed/` | `R__seed_*` | **可重复**，checksum 变了就重跑 ⇒ **必须幂等** |

`spring.flyway.locations` 已含 `classpath:db/seed`（见 `application.yml`）。

**新增 `V*.sql` 的规则：**

- 含数据写语句（`INSERT INTO` / `UPDATE … SET` / `DELETE FROM` / `TRUNCATE`）⇒ 必须声明一行
  `-- MIGRATION_KIND: backfill`（一次性数据修复，V 的正当用途）或 `-- MIGRATION_KIND: schema`；
- 声明 `MIGRATION_KIND: seed` ⇒ **直接红**（种子不许留在 V 里，请移入 `db/seed/R__seed_*.sql`）；
- 声明 `schema` 却写数据 ⇒ 红（二者矛盾）。

**`R__seed_*.sql` 的规则：** 文件名必须 `R__seed_<用途>.sql`；必须幂等
（至少出现 `ON CONFLICT` 或 `WHERE NOT EXISTS`）；禁止 `TRUNCATE` 与无条件 `DELETE FROM`。
生产保护沿用 `seed_env` 占位符（prod 固定 `none`）。

**历史迁移怎么办 —— 不动。** 已应用的 `V*.sql` 一旦改写就是 checksum 漂移，需要每台已应用环境
`flyway repair`；门禁按「相对基线新增」判定，277 个历史迁移天然 grandfather。
不要为了「看起来整齐」去重构历史迁移。

**已应用过的迁移文件不能删（`V*` 与 `R__` 同理）。** Flyway 校验是「台账有记录 ⇒ 本地必须能解析到该
脚本」，解析不到就抛 `Detected applied migration not resolved locally: <description>`，Spring 侧表现为
`flywayInitializer` bean 创建失败 ⇒ **整个服务起不来**，不是「少跑一条迁移」那么轻。
2026-10-21 实证：提交 `6786fa64` 删掉 `R__seed_consumer_ad_banner.sql`（该库 2026-10-08 已应用过它），
重建后 trade-service 崩环约 4 分钟、dev API 全停。
**正确做法 = 墓碑文件**：文件留在原位，内容改成「幂等清理 + `seed_env` 守卫」（可重复迁移本就允许改内容，
改 checksum 会重跑一次），例如现版 `R__seed_consumer_ad_banner.sql` 只剩三条 `DELETE`。
先例：V134/V135 当年也是改成 `SELECT 1;` 而非删文件。
⚠️ 若已经删过并靠手工 `DELETE FROM flyway_schema_history …` 恢复启动（dev 本次即如此），那笔手工操作
**每个跑过旧版的环境都要重做一次** —— 这正是改用墓碑文件的理由。

```bash
pnpm check:flyway-seed-separation        # 门禁（CI 步骤同样跑，带 PR 基线 ref）
node scripts/check-flyway-seed-separation.test.mjs   # 自带 A/B 自测（10 用例）
```

