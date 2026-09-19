# `db/seed` —— 可重复种子数据（O9）

> 本目录由 Flyway **`R__` 可重复迁移** 执行：文件 checksum 变化时**重新运行**。
> 结构变更（DDL）不在这里，在 `../migration/V*.sql`。

## 为什么要分开

`../migration/` 里 277 个迁移中，种子数据与结构 DDL 一直是混在一起的
（`V11__production_sku_seed`、`V25__demo_catalog_seed`、`V56__vision_mapping_seed`、
`V134/V135__seed_*`、`V222/V223__*_seed`、`V252__seed_*`、`V275__scheduled_task_seed_gap` …）。
带来的实际代价：

1. **改一次种子就要新增一个 V 迁移** —— 种子是可反复调整的演示/基线数据，不该消耗不可变的版本号；
2. 种子内容散在 277 个文件里，**没人能一眼看出「当前基线长什么样」**；
3. `V` 迁移只有 checksum 不可变这一条约束，**种子与结构无法分别演进**。

## 约定（`check:flyway-seed-separation` 守）

- 文件名必须是 **`R__seed_<用途>.sql`**。
- 必须**幂等**：至少出现 `ON CONFLICT` 或 `WHERE NOT EXISTS` 之一。
  可重复迁移会在 checksum 变化时重跑，不幂等 = 重复插入或直接报错。
- **禁止** `TRUNCATE` / 无条件 `DELETE FROM` —— 重跑会反复清库。
- 生产保护沿用现有机制：`spring.flyway.placeholders.seed_env`（prod profile 固定 `none`），
  种子语句应包在守卫里，不要把生产数据当种子写。

## 历史迁移怎么办

**不动。** 已应用的 `V*.sql` 一旦改写就是 checksum 漂移，需要每台已应用环境 `flyway repair`
—— 收益为零、风险极高。本项治理**只向前生效**：门禁按「相对基线新增的文件」判定，
277 个历史迁移天然 grandfather。

因此**不要**为了「看起来整齐」去重构历史迁移；要做的是**新种子走这条路**。
