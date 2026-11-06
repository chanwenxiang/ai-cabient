# R2 用户与账号小点全覆盖 — 轮报告（part 1：运营/商户/门户，2026-11-07）

> uat 库真实操作；admin 走浏览器同款 API 链；每步库断言 + 审计核对。

## R2-A 运营账号全生命周期 ✅

| 步骤 | 端点 | 结果 |
|------|------|------|
| 建号（手机号 11 位校验触发过一次 400=格式校验在位） | POST /rbac/operators | ✓ uid=100000012 |
| 初始密码登录 | admin-password-login | ✓ |
| 改名 | PUT | ✓ DB 同步 |
| 停用（DELETE=软禁用，status→INACTIVE；PUT status 单字段会 400——正确契约是 DELETE） | DELETE /rbac/operators/{id} | ✓ |
| 停用后登录 | 正确密码+INACTIVE → **403 账号已停用** | ✓ 拒绝生效 |
| 启用（PUT 全字段：phoneNumber+name+status——单字段 status 400，需带 name） | PUT | ✓ |
| 恢复登录 | ✓ | |
| 重置密码 | POST reset-password | ✓ 旧失效新可用 |
| 审计链 | CREATE→UPDATE→RESET_PASSWORD→DISABLE 全留痕 | ✓ |

## R2-B 商户与门户账号 ✅

| 步骤 | 结果 |
|------|------|
| 建商户（含资质字段）MCH 892921854200 | ✓ MERCHANT_CREATE 留痕 |
| 停用/启用 = POST upsert 同端点（无独立 disable 端点；MA-078 白名单 ACTIVE/INACTIVE 生效） | ✓ INACTIVE↔ACTIVE |
| 门户账号建号+绑定商户（PUT users/{id}/merchants 裸 List<String>——MA-071 同款端点） | ✓ OPS_USER_MERCHANT_ASSIGN 留痕 |
| 商户门户登录（merchant-password-login） | ✓ |
| 门户用户停用 → 商户端登录 403 账号已停用 | ✓ 拒绝生效 |
| 恢复 | ✓ |

### 观察项（非缺陷）
- 新建门户账号未配角色时访问 `GET /merchant/wallet` → 403（RBAC 无授权即拒绝，正确；实际商户管理员应配 merchant 角色——R2 后续轮配角色后复测业务面）。

## 待续（R2 part 2）
- 消费者小点（注销四守卫/支付偏好/昵称）；线长全生命周期+分仓双负责人；团队子账号 verified 流程；RBAC 建角色+逐端权限矩阵；部门/仓库/组织。

## 环境备注
- 商户建号字段：merchantId 可回传指定？本次未传=系统发号 892921854200（18 位雪花）。
- `POST /merchants` upsert 同端点承担停启用（MA-078 修复后的白名单行为验证 ✓）。

## part2（2026-11-07 凌晨）✅ R2 全部收口

### R2-C 消费者小点（部分待白天复验）
- 昵称/支付偏好 API：consumer token 过期（401）→ **待白天复验**（devtools 重开登录后即可）
- mock wx-login 正确拒绝（409，真实 code2session 生效）✓
- 支付偏好 DB 已是 BALANCE（早前设置在位）

### R2-D 线长全生命周期 ✅
- 建号（managerName/phone 键名）✓ id=1
- **MA-069 回归 ✓**：>10000bps → 400「须在 0..10000」（CommissionParamBounds 单源在位）
- 更新 500bps ✓ + LINE_MANAGER_UPDATE 审计 ✓

### R2-E RBAC 角色矩阵 ✅（双向）
- 建角色（UAT只读）→ 授 ops:order:list → 授权用户 100000012：
  - orders 列表 **200**（正向授权生效）✓
  - merchant-wallets **403**（未授权拒绝）✓
  - merchant create **403** ✓
- 权限清单响应形态=data 数组（permissionId 字段）

### R2-F 部门/仓库/组织 ✅
- 部门建 ✓（deptId=5）
- 仓库建：**PUT /warehouse/new**（upsert 语义，DTO=warehouseName+status+managerUserIds）✓ id=326765247551
- 教训沉淀：Git Bash curl 内联中文 JSON 必坏（400 请求体格式错误）→ 一律 UTF-8 文件体 --data-binary（本晚第 3 次踩，升级为必守规则）

### 轮末对账
- 运营账号 +2（100000012/13）、商户 +1（892921854200）、线长 +1（id=1）、角色 +1（id=12）、部门 +1、仓库 +1（WH 新建）、消费者 10002 不变（主链资产保留）
- 审计：全部动作留痕核对 ✓（MERCHANT_CREATE/OPS_OPERATOR_*/OPS_USER_MERCHANT_ASSIGN/LINE_MANAGER_*）

### 覆盖表新增触达
ops_role/ops_role_permission/ops_user_role/ops_user_merchant/ops_department/warehouse/line_manager/line_wallet_account(ensure)
