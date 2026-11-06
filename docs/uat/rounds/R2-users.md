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
