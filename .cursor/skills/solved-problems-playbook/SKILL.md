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
| 点滚动条内容闪缩 | body `overflow-y:scroll` + `scrollbar-gutter:stable`；禁 `auto` |
| 长文案悬停盖邻列 | **禁** `show-overflow-tooltip`；用 native title / cell-ellipsis |
| 列表卡顿 pageSize=100 | `ADMIN_LIST_PAGE_SIZES`，最大 50 |

规则：`admin-layout-anti-jitter.mdc`。门禁：`pnpm check:admin-anti-jitter`。

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
