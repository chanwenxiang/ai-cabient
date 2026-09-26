---
name: solved-problems-playbook
description: >-
  Applies already-solved AI Cabinet problem recipes (admin anti-jitter, RBAC,
  MQ/vision ack, payment idempotency, mp packaging, CI gates). Use when fixing
  regressions, debugging similar symptoms, or before reinventing a fix that may
  already be in lessons-learned / PROJECT_KNOWLEDGE §8.
---

# 已解决问题配方手册

先读 `docs/PROJECT_KNOWLEDGE.md` §8，再按下表套用。**禁止**对总册已有症状从零猜改。

## 1. 查阅顺序

```
症状关键词 → 本表领域 → lessons-learned 行号 → 领域 .mdc / 门禁脚本 → 按「必须怎么做」改
```

明细表：`docs/engineering/lessons-learned.md`。

## 2. 高频配方

### A. 运营后台布局抖 / 盖字 / 弹宽

| 症状 | 必须 |
|------|------|
| 操作列横滑跑走 | 右侧 `fixed-column--right` **sticky**；禁为防抖改 static |
| 抽屉松手变宽 | 先钉 `finalW` + `nextTick`；禁 pointerup 清空 width |
| 点滚动条内容闪缩 | body `overflow-y:scroll` + `scrollbar-gutter:stable`；禁 `auto`（含客流页 `:has(.footfall-page)` 覆盖） |
| 长文案悬停盖邻列 | **禁** `show-overflow-tooltip`；用 native title / cell-ellipsis |
| 列表卡顿 pageSize=100 | `ADMIN_LIST_PAGE_SIZES`，最大 50 |

规则：`admin-layout-anti-jitter.mdc`。门禁：`pnpm check:admin-anti-jitter`。

### A2. 工作台设备 KPI / 待办口径

| 症状 | 必须 |
|------|------|
| 缺货 N 点进库存健康空页 | 计数与深链均限 `lifecycleStatus=DEPLOYED`（#186） |
| 离线待办出现 CAB-001 / INBOUND | 离线计数与告警明细仅 `isDeployedDevice`（#193） |
| 在线率 1/3 分母含入库柜 | `stats`/`globalStats` 分子分母仅投放柜；深链带 `lifecycleStatus=DEPLOYED`（#194） |
| SLA 在线率仍 33% / 开门时长裸 ms | SLA 与工作台同口径仅 `isDeployedDevice`；时长 `formatDoorDurationMs`（#198） |
| 大屏总数 1 但排行 3 台 | 排行/区域营收须滤投放柜 ID；禁直接用全量 `reports/devices`（#199） |
| 「仅滞留」仍见已完成单 | `stuckOnly` 必须活跃态 ∩ `updatedBefore`；禁只按时间（#195） |
| 订单详情 lines 为 `[{}]` | 嵌套 `OrderLineDto` 必须标 `@JsonView(Public)`（#196） |

### A3. 弹窗 UAT「取消关不掉」假红

| 症状 | 必须 |
|------|------|
| Playwright 点「取消」弹窗仍在；`el.click()` 却能关 | 等 overlay `opacity>0.99` 且无 `dialog-fade-enter-*` 再点（#200） |
| open 后 300–700ms 内点 footer | **禁止**；入场 opacity≈0 时点击不可靠 |

### A4. 一致性巡检演示脏数据

| 症状 | 必须 |
|------|------|
| 未通过含库存汇总≠批次 | 可点「修复」：汇总改对齐在架批次（#CAB-001 类） |
| 积分恒等式 total≠三分项 | 「修复」按 available+used+expired 回写累计 |
| 退款字段≠流水 / 结案争议无订单 / 缺 SALE 流水 | **不可**点修复；退款对齐 `refunded_cents`；孤儿 RESOLVED 争议可删；SALE 补 `inventory_movement` |
| 修完不验收 | **必须**再点「立即巡检」至「全部通过」 |

### B. Admin 鉴权 / RBAC / 端点

| 症状 | 必须 |
|------|------|
| 退出连弹「请先登录」 | `logoutSession` begin+end；inbox 判 `isLoggedIn` |
| 无菜单路由任意可进 | `canAccessPath` 未知路径 **deny** |
| JWT 在 localStorage | Cookie 优先；禁 `localStorage.setItem(admin_token)` |
| views 裸写 `/api/v2/ops/...` | 走 `AdminEndpoints`；`check:admin-endpoints` |

### C. MQ / 视觉识别

| 症状 | 必须 |
|------|------|
| 失败消息静默丢 | 失败重抛或不 ACK；禁吞异常后正常返回 |
| Kafka 无限重试 | 入 DLT 后 ack；topic Bean 必须注册 |
| 识别挂起无结果 | HTTP+Kafka wall-clock 超时 → `need_review` 仍发 result |
| 高峰 rebalance | `max-poll-records` 显式限制 |

总册 #19,22,45,75,95。

### D. 资金 / 幂等

| 症状 | 必须 |
|------|------|
| 渠道失败本地已提交 | 渠道前置或两段式 CHARGE_PENDING；禁先落库后调渠道无补偿 |
| 重试双扣双退 | 幂等键只含不变要素；禁 reason/随机尾缀 |
| 前端限额被绕过 | 服务端同款 `max_cents` + 单测 |
| 按行退款一键落账 | 提交前必须 `ElMessageBox.confirm`；弹层 `append-to-body`（#197） |

总册 #17,96–97。

### E. 小程序

| 症状 | 必须 |
|------|------|
| 主包过大 | tab/登录留主包；其余 `subPackages` |
| 列表 N+1 / 预拉 pricing | 列表 DTO 聚合；详情懒加载 |
| 拒定位仍见上海柜 | 无定位 error 空态；禁默认坐标假附近 |
| H5 出「绑定微信」 | `v-if=isMpWeixin` |

### F. CI / DevOps

| 症状 | 必须 |
|------|------|
| OpenAPI types stale | 改 API 后 `pnpm gen:api-types` 并提交 generated |
| 本地门禁绿 CI 仍红 | 新 `check:*` 同步进 `ci.yml` + `check:audit-gates` |
| Git Bash docker 路径被改写 | `MSYS_NO_PATHCONV=1 MSYS2_ARG_CONV_EXCL='*'` |

## 3. 改完

1. 跑对应 `pnpm check:*`。
2. UI 相关：Playwright 真开页面（见 `browser-real-testing`）。
3. 若是**新**坑：`encapsulate-solved-problem`；若是旧坑回归：Changelog 记「防回归」一行即可。

## 4. 延伸阅读

- 活文档：`docs/PROJECT_KNOWLEDGE.md`
- 总册：`docs/engineering/lessons-learned.md`
- 后台专表：`.cursor/rules/admin-layout-anti-jitter.mdc`
