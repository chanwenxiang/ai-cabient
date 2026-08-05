# 商品主数据与识别入驻拆分

**日期**：2026-08-05  
**范围**：`clients/admin-vue`、`trade-service`、Flyway（V144）、`common-core` DTO、`packages/shared-types`、菜单权限种子  
**依据**：用户确认方案 A——保留字符串 `sku_id`；新增自增数字 `sku_code`；强化条码；拆「商品管理」与「识别入驻」两菜单；系统自增编号从 100001 起只读。

---

## 1. 目标与非目标

### 1.1 目标

1. 运营侧具备与竞品（友宝 / 中吉 / 美团柜类）接近的**商品主数据**能力：编号、条码、名称、品牌、规格、单位、售价、成本、类目、状态、主图等。  
2. **识别入驻**独立菜单，不再淹没在商品页里。  
3. 对外运营展示优先用**数字编号 `skuCode`**；内部链路继续用字符串 `skuId`，避免改订单/库存/映射全链路。  
4. 条码有值时全局唯一；编号全局唯一且自增。

### 1.2 非目标（本期不做）

- 不把 `sku_id` 改成纯数字主键。  
- 不做主图文件上传组件（继续 URL）。  
- 不做多级类目树（继续字符串 `category`；V68 `category_id` 暂不接线）。  
- 不改订单行 / 库存表里的 `sku_id` 语义。  
- 不新建独立 `product` 表。

---

## 2. 数据模型

### 2.1 `sku_catalog` 变更（Flyway 新版本，当前库已到 V129+）

| 列 | 类型 | 约束 | 说明 |
|----|------|------|------|
| `sku_code` | `BIGINT` | `NOT NULL UNIQUE` | 运营数字编号，自增，起点 **100001** |
| `brand` | `VARCHAR(64)` | 可空 | 品牌 |
| `spec` | `VARCHAR(128)` | 可空 | 规格，如 `330ml` |
| `unit` | `VARCHAR(16)` | `NOT NULL DEFAULT '件'` | 销售单位 |
| `barcode` | 已有 | **有值时 UNIQUE**（部分唯一索引 / 条件唯一） | 条形码 / EAN |

实现要点：

1. PostgreSQL 序列或 `GENERATED`/`nextval`：`sku_code_seq START 100001`。  
2. 存量行按 `created_at, sku_id` 顺序赋 `sku_code`。  
3. 新建：`INSERT` 时取 `nextval`；API/UI **只读展示**，禁止客户端改写。  
4. `barcode`：空串规范为 `NULL`；`CREATE UNIQUE INDEX ... WHERE barcode IS NOT NULL`。  
5. 内部 `sku_id`：新建时若未指定，服务端生成 `SKU-{skuCode}`（或保持现有入驻生成规则，但须保证唯一）；运营界面**不强调**字符串 ID。

### 2.2 DTO / 类型同步

- `SkuCatalog` / `SkuCatalogDto` / `UpsertSkuRequest` / `shared-types` 增加：`skuCode`、`brand`、`spec`、`unit`（及已有 `barcode` 在 upsert/列表中完整透出）。  
- `UpsertSkuRequest`：**忽略**客户端传入的 `skuCode`（服务端分配）。  
- 列表/导出排序：主数据默认按 `skuCode ASC`（与主键规范文档一致）。

---

## 3. API

沿用 `/api/v2/ops/admin/skus`：

| 方法 | 行为变更 |
|------|----------|
| `GET /skus` | 返回 `skuCode` 等新字段；支持 `q` 关键词（编号/名称/条码/品牌/skuId）+ `status` + `category` |
| `POST /skus` | 分配 `skuCode`；校验条码唯一；写入 brand/spec/unit/barcode/成本/类目/克重等主数据 |
| `PUT /skus/{skuId}` | 不可改 `skuCode`；可改主数据；条码冲突返回 409 |

识别入驻继续走现有 `/api/v2/ops/admin/sku-vision/**`，选品展示改为 `skuCode + skuName`。

---

## 4. 菜单与权限

| 菜单名 | 路由 | 权限 | 说明 |
|--------|------|------|------|
| 商品管理 | `/skus` | `ops:sku:list` 及 edit/import/export | 原「商品与识别」改名 |
| 识别入驻 | `/sku-vision` | 复用 `ops:sku:list` + `ops:sku:edit`（或现有 vision 相关 F 码，与现 SkuList 入驻能力一致） | 新路由；侧栏「设备商品」下紧挨商品管理 |

Flyway：更新 `ops_permission` 中 `ops:sku:list` 的 `perm_name` / `path`；插入识别入驻菜单项（`C` 类型）并挂到设备商品分组；超管/演示角色授权与商品菜单对齐。

前端 `router`：`skus` 指向商品主数据页；新增 `sku-vision` 指向拆出的识别页。旧书签 `/skus` 仍为商品管理。

---

## 5. 前端页面

### 5.1 商品管理（新/重构自 SkuListView 主数据部分）

**筛选**：关键词 + 状态 + 类目 + 查询/重置（与订单关键词模式一致）。  

**列表列（建议）**：选择、编号(`skuCode`)、条码、名称、品牌、规格、单位、售价、成本、类目、状态、操作。  

**新建/编辑表单**：

- 只读：数字编号（新建提交后回显；编辑直接展示）  
- 可编：名称*、条码、品牌、规格、单位、售价*、成本、类目、克重、状态、主图 URL、描述  
- 不展示识别类名/阈值（留给识别入驻）

**CSV**：表头含编号、条码、品牌、规格、单位等；导入时编号列忽略（系统分配），按名称/条码匹配更新策略在实现计划中写清（建议：有条码按条码更新，否则新建）。

### 5.2 识别入驻（从现 SkuListView 拆出）

- 入驻状态筛选、pipeline、类名、阈值、参考图、智能建议。  
- 关联商品选择器：显示 `#{skuCode} 名称`。  
- 权限与现网「新建商品/入驻」按钮一致，避免超管丢能力。

---

## 6. 迁移与兼容

1. Flyway 加列 → backfill `sku_code` → 序列推进到 `MAX(sku_code)+1`。  
2. 旧客户端不传新字段：读接口多字段向后兼容；写接口缺省 `unit=件`、`brand/spec` 空。  
3. 演示种子在迁移或后续 seed 中带上 `sku_code` / 示例条码（可选）。

---

## 7. 验收标准

1. 侧栏可见「商品管理」「识别入驻」两项，原「商品与识别」文案消失。  
2. 新建商品得到只读递增 `skuCode`（≥100001），且全局唯一。  
3. 列表默认按编号升序；关键词可搜编号/名称/条码/品牌。  
4. 条码重复保存失败并提示；空条码允许多条。  
5. 识别入驻页可选中商品并完成类名/状态编辑，不影响主数据页字段完整性。  
6. 浏览器真实打开两页完成新建商品 + 打开识别入驻冒烟。

---

## 8. 实现顺序（供后续 writing-plans）

1. Flyway + Entity/DTO/shared-types  
2. AdminDashboardService CRUD + 列表 `q`  
3. 商品管理 Vue 页 + 路由/菜单  
4. 拆分识别入驻页  
5. CSV 与种子对齐  
6. 构建部署 + 浏览器验收  

---

## 9. 已确认决策摘要

| 项 | 选择 |
|----|------|
| 主键策略 | 保留 `sku_id` 字符串；新增 `sku_code` |
| 编号生成 | 系统自增，起点 100001，只读 |
| 页面结构 | 两个菜单：商品管理 + 识别入驻 |
| 条码 | 可空；有值唯一 |
| 新增主数据字段 | brand / spec / unit + UI 暴露 barcode/cost/category/weight |
