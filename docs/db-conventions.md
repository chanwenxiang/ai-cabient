# 数据库开发规约（Postgres ↔ 阿里手册适配）

本仓使用 **PostgreSQL** + Flyway + MyBatis-Plus。以《阿里巴巴 Java 开发手册》MySQL 章为检查清单，按下列规则落地；**不照抄 MySQL 字面**。

## 采纳

| 主题 | 本仓做法 |
|------|----------|
| 审计时间 | `created_at` / `updated_at`（`timestamptz`），等价手册 `gmt_create` / `gmt_modified`，**不改名** |
| 逻辑删除 | 主数据 / 配置 / 运维单据表：`is_deleted boolean NOT NULL DEFAULT false`；查询默认过滤 |
| 金额 | `*_cents`（`bigint`），禁止 float |
| 冗余展示 | 热点列表表冗余短稳字段（如 `device_name`、`merchant_name`），写入时同步 |
| Join | 列表查询尽量 ≤2 表；展示优先冗余列或批量 `IN` |
| 索引命名 | **新索引**一律 `uk_` / `idx_`；存量分批 rename |
| 业务唯一 | 幂等键、手机号、外部单号等补 `uk_`（可空列用部分唯一索引） |
| 布尔新列 | 列名 `is_xxx`，类型 Postgres `boolean` |
| 分库分表 | 未到量级不做 |

## 刻意偏离 / 豁免

| 主题 | 决定 |
|------|------|
| 物理外键 | **保留**已有 FK（演示完整性）；新增优先 UK + 应用校验；禁止业务级 `ON DELETE CASCADE` |
| 主键形态 | 保留业务键（`order_id` / `session_id` / `device_id` 等），不强制单一 `bigint id` |
| 金额类型 | 不用 `decimal` 替换支付金额的 `*_cents` |
| 流水软删 | **禁止**：`payment_operation`、钱包/线路流水、审计日志、会话/订单完成态流水等 |
| 存量布尔 | `verified` / `enabled` / `sales_locked` 等高风险列可长期豁免 `is_` 改名，新列必须 `is_` |
| 命名 | 不用 `gmt_*`、不用 MySQL `unsigned` / `tinyint(1)` |

## 软删范围（示例）

**启用 `is_deleted`：** `user_info`、`device_info`、`merchant`、`sku_catalog`、`repair_ticket`、`announcement`、`coupon_definition`、`supplier`、`warehouse`、`promotion_activity`、`notification_template`、`media_asset`、`ad_campaign`、`site_contract`、`ops_department`、`ops_org_node`、`member`、`member_level_rule`

**禁止软删：** 支付/钱包流水、对账、审计、RBAC 关联表、订单行/仓配明细等 junction 与账本表

## ORM

- `MybatisMetaObjectHandler`：insert 填 `createdAt`/`updatedAt`，update 填 `updatedAt`（字段需 `@TableField(fill=...)`）
- 软删实体：`@TableLogic(value="false", delval="true")` + `@TableField("is_deleted") private Boolean deleted`
- Java 布尔属性不加 `is` 前缀（手册 ORM 条）；DB 列用 `is_deleted`

## 变更方式

- 仅 Flyway `V{n}__*.sql` 做 schema 变更；**只做加法**（加列/索引/注释），不删业务列、不改业务主键名
- **新增表/列必须写中文注释**（`COMMENT ON TABLE/COLUMN`）；Windows 下 Flyway 建议用 Unicode escape（`U&'...'`）避免控制台编码损坏
- 迁移内业务字符串优先 ASCII / Unicode escape
