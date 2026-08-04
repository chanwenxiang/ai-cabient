# Admin CRUD + List Primary-Key Sort Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 补齐运营后台识别映射编辑、设备新建、优惠券编辑、参数删除（含菜单 F 权限），并让所有业务列表展示主键且按主键默认排序（主数据 ASC、流水 DESC）。

**Architecture:** 后端补缺失 API / Flyway 按钮权限；前端加 `sortByPrimaryKey` 工具并批量改列表列；CRUD 页接已有或新建 endpoint。静态资源仍经 `admin-vue` build 进 `trade-service` static。

**Tech Stack:** Vue 3 + Element Plus、Spring Boot、Flyway、PostgreSQL、`common-core` DTO

**Spec:** `docs/superpowers/specs/2026-08-04-admin-crud-pk-sort-design.md`

## Global Constraints

- 不给订单/会话/审计/资金账单等做实体「新建」CRUD。
- 权限码与菜单树一致：`ops:device:create`、`ops:config:delete`；vision/coupon 复用已有 edit。
- 列表：选择列后第一数据列 = 主键，`cell-id`；主数据 ASC，流水/工单 DESC。
- 未明确要求时不要 `git commit`（本仓库用户约定优先于计划里的 commit 步骤）。
- UI 验收用浏览器 MCP，打开 `http://localhost/admin/...`（history 路由，非 hash）。

## File map

| File | Responsibility |
|------|----------------|
| `clients/admin-vue/src/utils/sort-by-pk.ts` | `sortByPrimaryKey` / `comparePrimaryKey` |
| `clients/admin-vue/src/views/**/*View.vue` | 主键列 + 排序；CRUD 对话框 |
| `services/common/common-core/.../UpdateCouponRequest.java` | 券定义更新 DTO |
| `services/trade-service/.../CouponController.java` + `CouponService.java` | `PUT definitions/{id}` |
| `services/trade-service/.../SystemConfigController.java` + `SystemConfigService.java` | `DELETE` |
| `services/trade-service/.../AdminDashboardController.java` | createDevice 权限 OR |
| `.../db/migration/V142__admin_crud_button_perms.sql` | 新 F 权限 + 角色授予 |

---

### Task 1: Primary-key sort utility

**Files:**
- Create: `clients/admin-vue/src/utils/sort-by-pk.ts`

**Interfaces:**
- Produces: `sortByPrimaryKey<T>(rows: T[], key: keyof T | ((row: T) => unknown), direction: 'asc' | 'desc'): T[]` — 不修改入参，返回新数组；数字字符串按数值比，否则 localeCompare。

- [ ] **Step 1: Add utility**

```ts
export type SortDirection = 'asc' | 'desc';

export function comparePrimaryKey(a: unknown, b: unknown): number {
  if (a == null && b == null) return 0;
  if (a == null) return 1;
  if (b == null) return -1;
  const sa = String(a).trim();
  const sb = String(b).trim();
  const na = Number(sa);
  const nb = Number(sb);
  if (sa !== '' && sb !== '' && Number.isFinite(na) && Number.isFinite(nb) && /^-?\d+(\.\d+)?$/.test(sa) && /^-?\d+(\.\d+)?$/.test(sb)) {
    return na === nb ? 0 : na < nb ? -1 : 1;
  }
  return sa.localeCompare(sb, 'zh-CN', { numeric: true, sensitivity: 'base' });
}

export function sortByPrimaryKey<T>(
  rows: T[],
  key: keyof T | ((row: T) => unknown),
  direction: SortDirection = 'asc'
): T[] {
  const get = typeof key === 'function' ? key : (row: T) => row[key];
  const sign = direction === 'desc' ? -1 : 1;
  return [...rows].sort((x, y) => sign * comparePrimaryKey(get(x), get(y)));
}
```

- [ ] **Step 2: Smoke in Node**

Run: `node -e "const {sortByPrimaryKey}=require('./clients/admin-vue/src/utils/sort-by-pk.ts')"` — 若 TS 不可直接 require，用临时 `npx tsx -e "..."` 断言 `sortByPrimaryKey([{id:'2'},{id:'10'}],'id','asc')` 得到 `10` 在后（numeric）且 desc 时 `10` 在前。

- [ ] **Step 3: Commit（仅用户要求时）** — skip by default

---

### Task 2: Flyway button perms + controller permission wiring

**Files:**
- Create: `services/trade-service/src/main/resources/db/migration/V142__admin_crud_button_perms.sql`
- Modify: `services/trade-service/src/main/java/com/aicabinet/trade/api/AdminDashboardController.java` (`createDevice` annotation)
- Modify: `services/trade-service/src/main/resources/db/migration/V89__coupon_edit_perm.sql` — **不要改历史**；在 V142 里把 `ops:coupon:edit` 的 `perm_name` 更新为「编辑优惠券」

**Interfaces:**
- Produces: DB perms `ops:device:create` (id 560)、`ops:config:delete` (id 561)；createDevice 接受 create OR edit

- [ ] **Step 1: Write V142**

```sql
-- 设备新建、参数删除按钮权限；优惠券 edit 文案对齐真实能力
INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 560, permission_id, 'ops:device:create', '新建设备', 'F', NULL, 3
FROM ops_permission WHERE perm_code = 'ops:device:list'
ON CONFLICT (perm_code) DO NOTHING;

INSERT INTO ops_permission (permission_id, parent_id, perm_code, perm_name, perm_type, path, sort_order)
SELECT 561, permission_id, 'ops:config:delete', '删除参数', 'F', NULL, 3
FROM ops_permission WHERE perm_code = 'ops:config:list'
ON CONFLICT (perm_code) DO NOTHING;

UPDATE ops_permission SET perm_name = '编辑优惠券' WHERE perm_code = 'ops:coupon:edit';

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_edit
JOIN ops_role_permission rp ON rp.permission_id = p_edit.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:device:create'
WHERE p_edit.perm_code = 'ops:device:edit'
ON CONFLICT DO NOTHING;

INSERT INTO ops_role_permission (role_id, permission_id)
SELECT rp.role_id, p_new.permission_id
FROM ops_permission p_edit
JOIN ops_role_permission rp ON rp.permission_id = p_edit.permission_id
JOIN ops_permission p_new ON p_new.perm_code = 'ops:config:delete'
WHERE p_edit.perm_code = 'ops:config:edit'
ON CONFLICT DO NOTHING;
```

- [ ] **Step 2: Update createDevice permission**

```java
@RequiresPermissions(value = {"ops:device:create", "ops:device:edit"}, logical = RequiresPermissions.Logical.OR)
@PostMapping("/devices")
public ApiResponse<AdminDeviceDto> createDevice(...)
```

- [ ] **Step 3: Verify migration applies** after trade-service rebuild（Task 7）— 查库：`SELECT perm_code FROM ops_permission WHERE perm_code IN ('ops:device:create','ops:config:delete');`

---

### Task 3: Coupon update API

**Files:**
- Create: `services/common/common-core/src/main/java/com/aicabinet/common/dto/UpdateCouponRequest.java`
- Modify: `services/trade-service/.../CouponService.java` — add `updateDefinition`
- Modify: `services/trade-service/.../CouponController.java` — `PUT /definitions/{id}`
- Optional test: `services/trade-service/src/test/java/.../CouponServiceTest.java`（若已有同类测试则补一条）

**Interfaces:**
- Consumes: same fields as `CreateCouponRequest`
- Produces: `PUT /api/v2/coupons/definitions/{id}` → `CouponDefinitionDto`，权限 `ops:coupon:edit`

- [ ] **Step 1: DTO**

```java
package com.aicabinet.common.dto;

public record UpdateCouponRequest(
    String couponName,
    String couponType,
    int denominationCents,
    int minSpendCents,
    Integer discountPercent,
    int validityDays,
    int maxIssueCount,
    String description
) {}
```

- [ ] **Step 2: Service method** — load by id or 404；set fields like `createDefinition`（不改 status / issuedCount）；save；return `toDefDto`

- [ ] **Step 3: Controller**

```java
@RequiresPermissions("ops:coupon:edit")
@PutMapping("/definitions/{id}")
public ApiResponse<CouponDefinitionDto> updateDefinition(
        HttpServletRequest request,
        @PathVariable("id") Long id,
        @Valid @RequestBody UpdateCouponRequest body) {
    return ApiResponse.ok(couponService.updateDefinition(id, body));
}
```

- [ ] **Step 4: Compile** `mvn -pl services/trade-service -am -DskipTests compile`（或仓库惯用模块路径）

---

### Task 4: System config delete API

**Files:**
- Modify: `SystemConfigService.java` — `delete(String key)`
- Modify: `SystemConfigController.java` — `DELETE /{configKey}`

**Interfaces:**
- Produces: `DELETE /api/v2/ops/admin/system-configs/{configKey}`，权限 `ops:config:delete`；key 不存在 → 404

- [ ] **Step 1: Service**

```java
@Transactional
public void delete(String configKey) {
    if (configKey == null || configKey.isBlank()) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置键不能为空");
    }
    SystemConfig existing = repository.findById(configKey)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "参数不存在"));
    repository.deleteById(existing.getConfigKey()); // 或 mapper 等价方法
}
```

（若 mapper 用 MyBatis-Plus：`deleteById(configKey)`。）

- [ ] **Step 2: Controller**

```java
@RequiresPermissions("ops:config:delete")
@DeleteMapping("/{configKey}")
public ApiResponse<Void> delete(..., @PathVariable String configKey) {
    systemConfigService.delete(configKey);
    return ApiResponse.ok(null);
}
```

注意：`configKey` 可能含点号，Spring 默认 OK；前端 `encodeURIComponent`。

---

### Task 5: Vision mapping edit/delete UI

**Files:**
- Modify: `clients/admin-vue/src/views/vision/VisionMappingView.vue`

**Interfaces:**
- Consumes: `POST /api/v2/ops/admin/vision-mappings/yolo` body `{ className, skuId, minConfidence, mappingSource? }`；`DELETE .../yolo/{className}`
- Uses: `sortByPrimaryKey(..., 'className', 'asc')`；主键列 `className`

- [ ] **Step 1: Table** — 选择列后加 `主键` 列显示 `row.className`；名称列保留商品信息；操作列 `v-if="auth.hasPerm('ops:vision:edit')"`：编辑、删除

- [ ] **Step 2: Dialog** — 类别只读、SKU 可搜下拉（可复用 `/api/v2/ops/admin/skus?page=0&size=200` 或现有 sku 列表 API）、最低置信度 number 0–1

- [ ] **Step 3: filtered computed** — keyword 过滤后 `sortByPrimaryKey(..., 'className', 'asc')`

- [ ] **Step 4: Handlers** — save → POST yolo；delete → `ElMessageBox.confirm` → DELETE

---

### Task 6: Device create + Coupon edit + Config delete UI

**Files:**
- Modify: `DeviceListView.vue` — 新建按钮 + dialog；主键列 `deviceId` ASC（服务端分页：请求加 sort 若已支持，否则当前页客户端排 + 文档 follow-up）
- Modify: `CouponsView.vue` — 编辑；主键列 `couponDefId` ASC；`PUT definitions/{id}`
- Modify: `SystemConfigView.vue` — 删除；主键列已是 configKey 时拆成独立列；`ops:config:delete`

**Device create:**
- Button `v-hasPermi="['ops:device:create']"` → dialog fields `deviceId*` `deviceName` `deviceType` `merchantId`
- `POST /api/v2/ops/admin/devices`

**Coupon edit:**
- `rowActions` 增加 edit；`openEdit(row)` 填表；save 分支 create vs update

**Config delete:**
- TableActions 增加 delete；confirm 文案含「系统默认项删除后可能被重新初始化」
- `DELETE /api/v2/ops/admin/system-configs/${encodeURIComponent(key)}`

- [ ] **Step 1–3:** 三页按上实现并本地 `npm --prefix clients/admin-vue run build` 无错

---

### Task 7: Batch list PK column + default sort

**Files:** 所有带业务表的 admin views（见下表）。每页：独立主键列 + `sortByPrimaryKey`（或服务端 order）。

| View | PK | Dir |
|------|----|-----|
| DeviceListView | deviceId | ASC |
| SkuListView | skuId | ASC |
| VisionMappingView | className | ASC |
| CouponsView | couponDefId | ASC |
| SystemConfigView | configKey | ASC |
| UserListView | userId | ASC |
| AnnouncementsView | announceId | ASC |
| PromotionsView | 活动 ID | ASC |
| DictManageView | dict 条目 ID / type+value | ASC |
| OperatorManageView / RoleManageView / MenuManageView | 各自 ID | ASC |
| MerchantSplitsView | 业务主键 | ASC |
| WarehouseView 各子表 | 各 ID | ASC |
| OrderListView | orderId | DESC |
| SessionListView | sessionId | DESC |
| RechargeListView | 充值单 ID | DESC |
| AuditLogView | logId | DESC |
| DisputeListView | ticketId | DESC |
| ExceptionListView | exceptionId | DESC |
| FundBillView | entryId / rowKey | DESC |
| UploadQueueView | sessionId | DESC |
| RepairTicketsView | ticket ID | DESC |
| DeviceOpsMonitorView | event ID | DESC |
| FeedbackView | feedbackId | DESC |
| 其余报表/流水页 | 稳定 row key | DESC 若流水否则 ASC |

- [ ] **Step 1:** 对每个 view：在 `filtered`/`items` computed 中接入 `sortByPrimaryKey`；模板加主键列（宽约 120–160）。
- [ ] **Step 2:** 服务端分页页（设备、订单）：检查 list API 是否已有 `sort`；无则至少保证本页排序，并在 PR 说明 follow-up。
- [ ] **Step 3:** 构建 admin：`npm --prefix clients/admin-vue run build`，按仓库惯例拷入 `services/trade-service/.../static/admin/`。

---

### Task 8: Deploy + browser acceptance

**Files:** none（运行时）

- [ ] **Step 1:** `docker compose -p ai-cabinet -f infra/docker-compose.full.yml up -d --build trade-service`（或当前环境等价命令）
- [ ] **Step 2:** 浏览器登录 `http://localhost/admin/index.html`（`13900000001` / `123456`）
- [ ] **Step 3:** 验收清单
  - 识别映射：编辑 SKU/置信度成功；无权限无操作列
  - 设备：新建设备出现在列表；主键列可见；ASC
  - 优惠券：编辑名称保存成功；主键列
  - 参数：删除自定义键；菜单有「删除参数」
  - 订单或开门记录：主键列 + DESC
- [ ] **Step 4:** 失败则修并重测对应页

---

## Spec coverage checklist

| Spec § | Task |
|--------|------|
| 2.1 Vision edit/delete | 5 |
| 2.1 Device create | 2 + 6 |
| 2.1 Coupon edit | 3 + 6 |
| 2.1 Config delete | 2 + 4 + 6 |
| 2.2 No transactional CRUD | Global Constraints |
| 3 Menu perms | 2 |
| 4 List PK + sort | 1 + 7 |
| 7 Acceptance | 8 |

## Placeholder / consistency self-review

- 权限 ID 560/561 避开 V106 的 410–443；若库中已占用，改为 `SELECT MAX(permission_id)+1` 写法再插入。
- `UpdateCouponRequest` 字段与 `CreateCouponRequest` 对齐。
- 前端路径一律 `/api/v2/...`。
