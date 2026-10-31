# R2 审计：交易履约（4 页）

- 执行 AI / 日期：Qoder（本会话）/ 2026-10-31
- 代码基线：HEAD `8e12c11f`（`git status --porcelain` 干净；远端 `refs/heads/dev` 同为 `8e12c11f`，`git ls-remote` 实证。本地 `origin/dev` 引用陈旧是 fetch 未带 refspec，非分叉）
- 环境：本地 docker（`infra/docker-compose.full.yml` + `win-ports`，trade `127.0.0.1:18080`，Postgres 库 `aicabinet` @15433）；admin 产物 = `c3e0e44d`（CI-fidelity 重建，其后两笔提交为 docs-only，未动 admin 源码 ⇒ 产物与 HEAD 同刻）
- 账号：超管 `13900000001`（受限角色快测本轮抽 1 个，见各页「1 入口与权限」）

## Step 0 清单重拉（以代码为准）

`node docs/module-audit/step0-inventory.mjs` @ `8e12c11f`：

```
admin 侧栏登记页合计：77（去重后）
  概览：9 / 交易履约：4 / 设备商品：10 / 履约仓储：4 / 仓储：8 / 财务商户：16 / 增长风控：11 / 系统：15
无 perm 码（登录即可见）：/profile
菜单有、路由无（死入口）：无
兼容重定向（非页面）：/index.html /oper-logs /rbac
路由有、菜单未登记（人工判）：/print
consumer-mp：main=7 sub=16 total=23
merchant-mp：main=5 sub=30 total=35
```

**R2 本轮范围 = 交易履约 4 页**，与 PLAN 快照一致（无漂移）。页×选项卡（从视图源码取，非菜单项）：

| 页面 | 路由 | 视图 | 选项卡（源码行） |
|------|------|------|------------------|
| 订单管理 | `/orders` | `views/orders/OrderListView.vue`（1791 行） | 全部 / `order_status` 字典：PENDING、PAID、DISPUTED、REFUNDED、CANCELLED（:28-33） |
| 开门记录 | `/sessions` | `views/sessions/SessionListView.vue`（1094 行） | 全部 + `stateOptions` 动态枚举（:23-24） |
| 争议审核 | `/disputes` | `views/disputes/DisputeListView.vue`（1750 行） | 全部类型 / 识别争议 RECOGNITION / …（:21-22，余下见正文） |
| 异常中心 | `/exceptions` | `views/exceptions/ExceptionListView.vue`（1651 行） | 动态（:63）/ 已归档 ARCHIVED / 全部（:69-70） |

联动端（R2 要点的 mp 面）：consumer-mp `orders`、`order-detail`、`dispute`、`result`；merchant-mp `alerts`、`orders`、`order-detail`、`disputes`、`video`。

## 逐页结果

（待填：每页一节，按 `templates/page-checklist.md` 九维；本轮 P2/P3 轮内即修 + 双锚，P0/P1 轮末批次）

## 发现汇总

| 级别 | 数量 | 编号 |
|------|------|------|
| P0 | 0 | — |
| P1 | 0 | — |
| P2 | 0 | — |
| P3 | 0 | — |

## 需用户拍板

- （待填）

## 需用户配合（真机）

- （待填：开门/扫码/支付类）

## 修复批次建议

- （待填；轮末还要过一遍 PLAN 的门禁化候选台账）

## 给下一轮的交接注记

- 线索 #2（`/reports` 离线 5 vs 工作台 3）**不属本轮范围**，但它读的是设备 lifecycle 口径——R3 设备商品轮开轮时先把它结掉（判缺陷还是口径差），别在 R2 半途领号。
