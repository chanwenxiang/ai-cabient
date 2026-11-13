# Flyway repair 一次性运维工具

## 它解决什么

**已经 apply 过的迁移文件被再次编辑**（给历史种子迁移补 `${seed_env}` 环境守卫、修 SQL
笔误、调索引名……）之后，`schema history` 表里记的 checksum 与文件当前内容不再一致。
Flyway 默认 `validate-on-migrate=true`，于是**应用直接拒绝启动**：

```
Migration checksum mismatch for migration version 244
-> Applied to database : 1234567890
-> Resolved locally    : 987654321
```

`repair()` 只把 schema history 表里**已记录行**的 checksum/description 重写为「当前文件算出的
值」——**不动任何业务数据**，也不把 pending 迁移标记为已应用。

> 实测依据：2026-09-18，zcode 给 6 条已应用的种子迁移补了 `${seed_env}` 守卫
> （V202 / V244 / V252 / V253 / V254 / V262）。repair 恰好改写 6 条 checksum、**0 行数据丢失**，
> 二次 repair 显示改写数 = 0（证明已对齐）。证据见
> `docs/evidence/2026-09-18-replenishment-checkin/README.md`。

## 🔴 坑 ①：placeholder 必须与运行时一致

`application.yml` 里迁移的 placeholder 是 `${SEED_ENV:local}`，所以本机/开发库默认 `local`。
**若 repair 时漏传 `seed_env`（或传了别的值），Flyway 算出的 checksum 与下次应用启动时算出的
仍不一致** —— repair 会「成功」，但重启照旧失败，表现为「修了没用」。

所以：`-Dseed.env=` 必须等于目标环境的 `SEED_ENV`。

## 🔴 坑 ②：locations 必须是**两个目录**，漏 `db/seed` 会删掉种子历史（本 README 原文案写着"唯一的坑"，是错的，已订正）

运行时是 `spring.flyway.locations: classpath:db/migration,classpath:db/seed`——
本仓迁移分两目录：`db/migration` 放结构（`V*.sql`），`db/seed` 放可重复种子（`R__seed_*.sql`，共 3 个）。

只把 locations 指到 `db/migration` 时，Flyway 认为那 3 条**已应用**的 R__ 种子"本地不存在"，
`repair()` 于是给它们写 `type=DELETE` 的历史行。后果不是报错而是**静默改历史**：

| 症状                                    | 说明                                                                                                                                            |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------- |
| `repair` 输出仍 `REPAIR_OK`             | 它不认为这是问题，日志里也不会点名                                                                                                              |
| 行数变化 +N（N=本地缺失的已应用迁移数） | 判据：`select count(*) filter (where type='DELETE') from flyway_schema_history`                                                                 |
| 下次应用启动重跑这些种子                | 种子是幂等的（`WHERE NOT EXISTS`），所以数据不动，但历史又长一轮 SQL 行                                                                         |
| 反复循环                                | 2026-11-13 实测已是**第 4 次**：历史里留着 3 轮同样的 `DELETE→SQL` 抖动（rank 414-416/420-422/473-475，第 4 次 500-502 由本会话误触发并已撤销） |

两个工具的默认值已改为两目录（`Repair.locations()` / `ValidateCheck.locations()`），
覆盖 `-Dflyway.locations` 时自行按逗号切分并 trim，不依赖 Flyway 的拆分行为。

**跑完必须做的两条对账**（别只看 `REPAIR_OK`）：

```bash
# 1) 只应有 8 条 checksum 变化，DELETE 行数不得增加
docker exec ai-cabinet-postgres-1 psql -U aicabinet -d aicabinet -tAc \
  "select count(*), count(*) filter (where type='DELETE') from flyway_schema_history"
# 2) 权威判据：应用自己或 ValidateCheck 必须 PASS
java -Dfile.encoding=UTF-8 -Dseed.env=local -cp "<CP>" scripts/devops/flyway-repair/ValidateCheck.java
#    期望 VALIDATE=PASS
```

⚠️ 在 Git Bash 里传 `-Dflyway.locations=a,b` 曾被 shell 拆坏过（结果 475 条全报
"not resolved locally"）：判据以**应用启动日志**
（`Successfully validated N migrations` / `Schema "public" is up to date`）为准，
代理脚本报红先怀疑自己的传参。

## 怎么跑

在**仓库根**执行（`flyway.locations` 是相对路径，默认指向 trade-service 的
`db/migration` + `db/seed` 两目录，见坑 ②）。

需要的 4 个 jar（路径按本机 `.m2` 实际位置，版本须与 `pom.xml` 一致）：

```bash
M2=~/.m2/repository
CP="$M2/org/flywaydb/flyway-core/9.22.3/flyway-core-9.22.3.jar"
CP="$CP;$M2/org/postgresql/postgresql/42.6.2/postgresql-42.6.2.jar"
CP="$CP;$M2/com/google/code/gson/gson/2.9.1/gson-2.9.1.jar"
CP="$CP;$M2/org/slf4j/slf4j-api/1.7.13/slf4j-api-1.7.13.jar"
```

> `gson` 不能省。缺它在 `postgresql` 驱动初始化时会抛
> `NoClassDefFoundError: com/google/gson/JsonElement`（Flyway 用它解析 schema history，
> 不是 PostgreSQL 依赖自带的）。

然后（单文件源码启动，JDK 11+）：

```bash
# 1) 对齐 checksum
java -Dfile.encoding=UTF-8 -cp "$CP" Repair.java
#     期望：REPAIR_OK

# 2) 验证真的对齐了（必须带 -Dseed.env=，与上一步一致）
java -Dfile.encoding=UTF-8 -cp "$CP" ValidateCheck.java
#     期望：VALIDATE=PASS
```

`-Dfile.encoding=UTF-8` 不能少：源码注释是中文，Windows 默认代码页会让 javac 读乱。

可覆盖的系统属性：`db.url` / `db.user` / `db.password` / `flyway.locations` / `seed.env`。

## 跑完之后

`repair` 只对齐 checksum，**不会**应用 pending 迁移。若刚引入的迁移还没应用，正常重启应用即可
（启动走 `migrate()`）。

## 什么时候**不要**用它

- 迁移**已经应用到生产**、且不允许回溯时 —— 生产应走「新增一条修正迁移」，而不是改写历史文件
  再 repair（那会让历史不可复现）。本工具定位是**开发/测试库**的救援通道。
- 只是「迁移文件写错了但还没 apply」：直接改文件即可，不需要 repair。
