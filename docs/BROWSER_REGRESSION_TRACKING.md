# 浏览器回归跟踪表（2026-07-24 收口）

执行人：Agent（脚本门禁 + Browser MCP）  
环境：Docker full stack（trade `:18080` / vision `:18082` / Gateway `:80`）

## Step 0 — 脚本门禁

| ID | 状态 | 备注 |
|----|------|------|
| S-demo-smoke | PASS | 争议 mock/GRAVITY_FILL → WAIVE → 模拟充值；MQTT 卡住时走内部关门兜底 |
| S-admin-layout | PASS | orders/exceptions/sessions `.status-tabs` 高 39px；RESOLVED 带 orderId |
| S-shopping | PASS | DISPUTED → ops CONFIRM → PAID；扣款按 `payment_operation` 断言 |
| S-fund-safety | PASS | TC-5.9 无静默扣款；TC-6.1 宕机恢复；TC-5.7 幂等 |

## Step 1 — 树型菜单 + 多角色

| ID | 状态 | 备注 |
|----|------|------|
| M-TREE | PASS | 三级树：概览 / 业务(交易履约·设备与商品) / 运营(…) / 系统(…) |
| R-ADMIN | PASS | `13900000001` 全量菜单 |
| R-FINANCE | PASS | `13900000002` 仅工作台/财务毛利/商户/对账；`#/finance` 可开 |
| R-OPERATOR | PASS | `13900000003` 异常中心可开；无系统 RBAC 菜单 |
| R-REPLEN | PASS | `13900000004` 仅补货+仓库（上轮已验） |
| R-VIEWER | PASS | `13900000005` 列表只读（上轮已验） |
| R-403 | PASS | 财务受限 API 403 不踢登出（上轮已验） |

## Step 2 — P0/P1 页面

| ID | 状态 | 备注 |
|----|------|------|
| ADM-EXCEPTIONS | PASS | 运营角色待处理列表可加载 |
| ADM-DISPUTES | PASS | demo-smoke WAIVE 结案 |
| ADM-ORDERS/SESSIONS | PASS | layout-smoke |
| ADM-REPLEN/WAREHOUSE | PASS | 补货角色页可开（上轮） |

## 本轮已修复

| 项 | 文件 | 说明 |
|----|------|------|
| E2E 互斥锁 | `scripts/e2e-lib.ps1` + demo/shopping/fund | 防并发 Clear/Cancel 互踩 |
| OPENING 被取消重试 | `e2e-lib.ps1` Invoke-E2eMqttShopping | CANCELLED 最多重试 3 轮 |
| 购物余额断言 | `e2e-shopping.ps1` | CONFIRM 前快照 + `payment_operation` |
| 资金用例竞态 | `e2e-fund-safety.ps1` | 先 WAIVE 遗留争议再钉余额 |
| 商户乱码名 | `V119__fix_garbled_merchant_names.sql` | `MCH-EAST` → 华东演示商户 |
| 树型侧栏 + 演示账号 | menu/sidebar/V117/V118（上轮） | 多角色边界清晰 |
| 模拟器 MQTT 重连重订 | `DeviceSimulator.java` | `MqttCallbackExtended` + `connectComplete` 重订 command |
| 窄屏侧栏不写偏好 | `AdminLayout.vue` | ≤1200px 自动收起不写 localStorage |
| 财务 403 页内空态 | `FinanceView.vue` + `ElResult` 注册 | `el-result` 空态（重试/回工作台） |
| 充值超时自动取消 | `RechargeOrderScheduler` + `PaymentService` | 默认 30 分钟关 PENDING |
| 模拟购物默认时长 | `DeviceSimulator` | 默认 `AICABINET_SIM_SHOPPING_MS=20000` 对齐 compose |

## 遗留（非阻断）

| ID | 严重度 | 现象 |
|----|--------|------|
| ENV-FORCE-REAL | P2 | 真实视觉下模拟购物多进 GRAVITY_FILL（符合防静默扣款） |
| UI-MOCK-RECHARGE | P3 | 消费者「我的」仅 DEV 构建可见模拟充值（已按设计） |
| UI-DISPUTE-GARBLED | P3 | 争议 reason 乱码 | FIXED（展示兜底 + V120） |
| SBX-05 | P3 | 真实购买 mp4 全链路仍 PENDING |

## 统计

| PASS | FAIL | BLOCK | SKIP | 遗留 |
|------|------|-------|------|------|
| 全部门禁 + P0/P1 | 0 | 0 | 0 | 3（非 P0） |
