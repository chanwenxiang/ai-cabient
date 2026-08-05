# SKU 商品主数据拆分 Implementation Plan

> **For agentic workers:** Implement task-by-task. Steps use checkbox (`- [ ]`) syntax.

**Goal:** 拆分「商品管理」与「识别入驻」；为 `sku_catalog` 增加自增 `sku_code`（≥100001）及 brand/spec/unit；强化条码唯一；运营 UI 暴露完整主数据字段。

**Architecture:** 保留字符串 `sku_id` 作内部主键；新增 `sku_code` 作运营编号。Flyway V130 加列+回填+序列。商品页走 `/skus`，识别页新路由 `/sku-vision`。DTO/实体/shared-types 同步。

**Tech Stack:** PostgreSQL/Flyway、Spring Boot trade-service、Vue3 admin-vue、common-core DTO

**Spec:** `docs/superpowers/specs/2026-08-05-sku-master-data-design.md`

## Global Constraints

- `sku_code` 自增起点 100001，客户端不可改
- `barcode` 空→NULL；有值 UNIQUE
- 不改订单/库存中的 `sku_id` 语义
- 不做图片上传、不做类目树

---

### Task 1: Flyway + Entity/DTO/shared-types

**Files:**
- Create: `services/trade-service/src/main/resources/db/migration/V144__sku_master_code_and_fields.sql`
- Modify: `SkuCatalog.java`, `SkuCatalogDto.java`, `UpsertSkuRequest.java`
- Modify: `packages/shared-types/src/index.ts` (+ dist if needed)

- [x] Add columns `sku_code`, `brand`, `spec`, `unit`; backfill codes; sequence; partial unique on barcode
- [x] Wire entity getters/setters + `toDto()`
- [x] Extend DTO/Upsert/shared-types with `skuCode`, `brand`, `spec`, `unit`

### Task 2: Service + list `q` + barcode uniqueness

**Files:**
- Modify: `AdminDashboardService.java` (createSku/updateSku/applySkuRequest/listSkus)
- Modify: `AdminDashboardController.java` if query params needed
- Modify: `SkuCatalogMapper.java` if custom queries

- [x] On create: allocate `sku_code`, default `sku_id=SKU-{code}` if blank, normalize barcode
- [x] On update: ignore skuCode changes; 409 on barcode conflict
- [x] listSkus: filter by `q` (code/name/barcode/brand/skuId), status, category; sort by skuCode ASC

### Task 3: 商品管理 Vue 页

**Files:**
- Modify/refactor: `clients/admin-vue/src/views/skus/SkuListView.vue` → product master focus
- Or Create: `SkuMasterView.vue` and slim router

- [x] Keyword + status + category filters
- [x] Columns: skuCode, barcode, name, brand, spec, unit, price, cost, category, status
- [x] Create/edit dialog with master fields; skuCode read-only
- [x] CSV headers updated

### Task 4: 识别入驻页 + 路由菜单

**Files:**
- Create: `clients/admin-vue/src/views/skus/SkuVisionEnrollView.vue` (extract vision UI)
- Modify: `router/index.ts`
- Create: Flyway menu rename/insert (can fold into V130 or V131)

- [x] Move vision enrollment UI to `/sku-vision`
- [x] Menu: 商品管理 `/skus`, 识别入驻 `/sku-vision`
- [x] Product picker shows `skuCode + name`

### Task 5: Deploy + browser verify

- [x] `vite build` + `docker compose ... --build trade-service`
- [x] Browser: create SKU → see skuCode; barcode duplicate fails; both menus work
