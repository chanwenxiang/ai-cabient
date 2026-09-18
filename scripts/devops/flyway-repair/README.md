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

## 🔴 唯一的坑：placeholder 必须与运行时一致

`application.yml` 里迁移的 placeholder 是 `${SEED_ENV:local}`，所以本机/开发库默认 `local`。
**若 repair 时漏传 `seed_env`（或传了别的值），Flyway 算出的 checksum 与下次应用启动时算出的
仍不一致** —— repair 会「成功」，但重启照旧失败，表现为「修了没用」。

所以：`-Dseed.env=` 必须等于目标环境的 `SEED_ENV`。

## 怎么跑

在**仓库根**执行（`flyway.locations` 是相对路径，默认指向 trade-service 的迁移目录）。

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
