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

含 `MIGRATION_REVIEWED: yes` 且 `LOCK_RISK: high` 时，CI 仍要求 `NOTES` 非空。
