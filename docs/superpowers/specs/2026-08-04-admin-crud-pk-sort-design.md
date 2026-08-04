# 运营后台：主数据 CRUD 补齐 + 列表主键规范

**日期**：2026-08-04  
**范围**：`clients/admin-vue` + 必要的 `trade-service` / Flyway / `common-core` DTO  
**依据**：竞品（友宝/中吉/美团智能柜运营台一类）惯例；用户确认「就按照你的推荐」

---

## 1. 目标

1. 补齐主数据类缺口的增删改，并与**菜单管理** C/F 权限树一致。  
2. 所有业务列表：第一数据列展示**主键**；默认按主键排序（主数据 ASC，流水/工单 DESC）。  
3. **不**给履约流水、审计、监控报表做「新建实体」类 CRUD。

---

## 2. CRUD 边界（竞品对齐）

| 类型 | 做法 | 本仓库模块 |
|------|------|------------|
| 主数据 | 新建 / 编辑 / 启停（软删） | 设备、商品、识别映射、商户、仓库、营销/券、字典、参数、公告、账号角色菜单 |
| 履约流水 | 只读 + 详情 + 有限动作 | 订单、开门记录、充值、资金账单、审计 |
| 工单流 | 处理/结案，不造假单据 | 争议、异常、反馈回复、维修 |
| 监控报表 | 只读 + 导出 | 工作台、报表、运维事件、录像队列、SLA |

### 2.1 本轮要做的 CRUD

| # | 模块 | 页面能力 | 权限 | 后端现状 |
|---|------|----------|------|----------|
| 1 | 识别映射 | 本页编辑绑定（SKU、最低置信度）；可选删除映射 | 已有 `ops:vision:edit` | 已有 `POST/DELETE .../vision-mappings/yolo|aliyun` |
| 2 | 设备管理 | 「新建设备」对话框（deviceId / 名称 / 类型 / 商户） | 新增 `ops:device:create`（F），API 接受 create **或** edit | 已有 `POST /api/v2/ops/admin/devices`（当前仅 `ops:device:edit`） |
| 3 | 优惠券 | 行内「编辑」打开与新建同结构表单 | 已有 `ops:coupon:edit` | **缺** `PUT /definitions/{id}`，仅有 status |
| 4 | 参数配置 | 行内「删除」；系统内置种子键禁止删或二次确认后仍可删自定义键 | 新增 `ops:config:delete`（F）挂在参数配置下 | **缺** DELETE API |

### 2.2 本轮明确不做

- 订单 / 开门记录 / 充值 / 资金账单 / 审计：不新建、不物理删实体。  
- 用户反馈：保持回复，不造假反馈。  
- 设备运维 / 录像上传 / 报表：只读 + 导出。

---

## 3. 菜单与权限一致性

约定：`ops:{module}:{action}` 与菜单管理树同步；页面用 `v-hasPermi` / `auth.hasPerm`，后端 `@RequiresPermissions` 同码。

### 3.1 Flyway（建议 `V142__admin_crud_button_perms.sql`）

1. **`ops:device:create`**  
   - 类型 F，父节点 = `ops:device:list` 所在菜单节点  
   - 名称：新建设备  
   - 赋权：已有 `ops:device:edit` 的角色一并授予（演示超管 / 设备运营角色）  
   - Controller：`createDevice` 改为 `ops:device:create` **OR** `ops:device:edit`

2. **`ops:config:delete`**  
   - 类型 F，父节点 = `ops:config:list`  
   - 名称：删除参数  
   - 赋权：已有 `ops:config:edit` 的角色一并授予  
   - Controller：`DELETE /api/v2/ops/admin/system-configs/{configKey}` 需要该权限

3. **已有且无需新建**：`ops:vision:edit`、`ops:coupon:edit`（编辑含义从「仅启停」扩展为「改定义 + 启停」）

4. 同步清理：菜单展示名与 sidebar 对齐（若 V138 已覆盖则本迁移只插 F 权限）。

---

## 4. 列表主键与排序规范（全模块）

### 4.1 展示

- 选择列之后、业务名称列之前，增加独立列：**主键**（或「ID」），`class="cell-id"` 等宽。  
- 若当前把 ID 塞在名称 `small` 里（如优惠券 `ID xxx`），改为独立主键列，名称列只留可读名。  
- 复合主键 / 报表行：用稳定 `rowKey`（已有则展示该字段；无则合成并标注）。

### 4.2 默认排序

| 类别 | 方向 | 示例模块 |
|------|------|----------|
| 主数据 | ASC（字符串自然/字典序，数字按数值） | 设备、SKU、映射 className、参数 key、商户、仓库、字典、公告、优惠券定义、用户 |
| 流水 / 工单 | DESC | 订单、开门记录、充值、审计、资金明细、争议、异常、维修、上传队列、运维事件 |

### 4.3 实现策略

- **客户端全量/半全量列表**（当前多数 admin 页）：统一工具 `sortByPrimaryKey(rows, key, 'asc'|'desc')`，在 filter 后、分页前排序。  
- **服务端分页列表**（设备、订单等）：查询参数增加 `sort=deviceId,asc` 一类；若短期改 SQL 成本高，至少保证**当前页内**按主键排，并在实现计划里标出后续 API 全量排序任务。本轮优先：能改 mapper/service 的改默认 order；不能的先做客户端可见一致性。  
- 用户手动点列头排序时，以 Element Plus 列排序为准，不强制覆盖。

### 4.4 主键字段对照（核心页）

| 模块 | 主键字段 | 默认方向 |
|------|----------|----------|
| 设备 | `deviceId` | ASC |
| 商品 | `skuId` | ASC |
| 识别映射 | `className` | ASC |
| 优惠券 | `couponDefId` | ASC |
| 参数 | `configKey` | ASC |
| 用户 | `userId` | ASC |
| 商户/分账相关 | 各自业务 ID | ASC |
| 订单 | `orderId` | DESC |
| 开门记录 | `sessionId` | DESC |
| 充值 | 充值单 ID | DESC |
| 审计 | `logId` | DESC |
| 争议 | `ticketId` | DESC |
| 异常 | `exceptionId` | DESC |
| 资金账单 | 条目 ID / `entryId` | DESC |
| 上传队列 | `sessionId` | DESC |

其余列表页按同一规则扫一遍补齐。

---

## 5. 分模块交互设计

### 5.1 识别映射

- 操作列（`ops:vision:edit`）：编辑、删除。  
- 编辑对话框：类别（只读）、SKU 下拉（可搜）、最低置信度。  
- 提交：`POST /api/v2/ops/admin/vision-mappings/yolo`（及 aliyun 若列表含）。  
- 删除：确认后 `DELETE .../yolo/{className}`。  
- 无权限时隐藏整列操作（与现网空操作列约定一致）。

### 5.2 设备新建

- 列表头按钮「新建设备」（`ops:device:create`）。  
- 表单字段对齐 `UpsertDeviceRequest`：`deviceId*`、`deviceName`、`deviceType`、`merchantId`。  
- 成功后刷新列表并可选跳转详情。

### 5.3 优惠券编辑

- `UpdateCouponRequest`（与 create 字段同形，不含 status；status 仍走现有 status API）。  
- `PUT /api/v2/coupons/definitions/{id}` + `CouponService.updateDefinition`。  
- 行操作：编辑 + 启停；操作列在有任一权限时显示。

### 5.4 参数删除

- `SystemConfigService.delete(key)`：删除行；内置种子键（`upsertIfAbsent` 那批）删除后下次服务启动会重新插入——UI 文案提示「系统默认项删除后可能被重新初始化」。  
- 操作列：编辑 + 删除（分权限）。

---

## 6. 非目标 / 风险

- 不做跨端（商户/消费）列表改造。  
- 大表全库排序若仅客户端 slice，翻页后全局顺序不完全正确——服务端分页页会标为 follow-up。  
- 参数物理删除与种子重写：可接受；若产品后续要「禁用」再改为 soft-flag。

---

## 7. 验收标准

1. 菜单管理可见 `ops:device:create`、`ops:config:delete`；演示账号具备对应按钮。  
2. 识别映射可改 SKU/置信度并刷新；无 `ops:vision:edit` 无操作列。  
3. 设备列表可新建并在列表出现。  
4. 优惠券可改名称/面值等字段（非仅启停）。  
5. 参数可删除自定义键；无删除权限无删除按钮。  
6. 抽查 ≥10 个列表页：独立主键列 + 默认排序方向符合 §4。  
7. 浏览器实机：登录 → 上述 4 个 CRUD 页各走通一次；抽查订单/设备列表主键与排序。

---

## 8. 实现顺序（供 writing-plans）

1. 共享排序工具 + 批量列表主键列/排序  
2. 识别映射 UI（API 已有）  
3. 设备新建 UI（API 已有）+ Flyway `ops:device:create` + Controller OR 权限  
4. 优惠券 Update DTO/API + 编辑 UI  
5. 参数 DELETE API + Flyway `ops:config:delete` + UI  
6. 构建静态资源进 trade-service + 浏览器验收
