# 表头固定 + 商品最后修改人

日期：2026-08-05  
状态：已批准（实现中微调滚动容器）

## 目标

1. 运营后台列表表头在纵向滚动时保持固定。
2. 商品管理列表增加「添加时间」「操作人（最后修改人）」。

## 方案

### 表头固定

- 根因：
  1. 旧 CSS 将 `.table-scroll .el-table__header-wrapper` 设为 `position: relative !important`，盖掉 sticky。
  2. EP 2.x（`table-layout="auto"` + 横滚）表头在 `thead.el-table__body-header`（嵌在 scrollbar wrap 内）。
  3. 中间层 `overflow-x: auto` / `overflow-y: hidden` 会打断 sticky 对祖先滚动容器的依附。
- 修复（实测可行）：
  - 筛选/页头留在卡片上方；`.table-scroll` 占剩余高度并作为纵滚 + 横滚容器。
  - EP 内部 `.el-scrollbar__wrap` / body-wrapper 设为 `overflow: visible`，避免再建滚动层。
  - 对 `__header-wrapper` 与 `__body-header` 均 `position: sticky; top: 0`。
- UX：主区卡片高度铺满；滚动发生在表格区域内，筛选项不跟着滚走。

### 商品审计列

- 展示已有 `createdAt` 为「添加时间」。
- Flyway 增加 `sku_catalog.updated_by_user_id`、`updated_by_name`（写入时快照显示名）。
- 新建 / 编辑 / 视觉录入相关写路径写入当前运营账号；历史空值列表显示「—」。
- DTO / shared-types / SkuListView（及导出）同步字段。

## 不做

- 不单独做「创建人」列。
- 不批量回填历史操作人。
