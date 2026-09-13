# ROUND 台账 · 2026-09-12~13 · **MASTER 完整轮扩测** · docker-full

环境：`env:docker-full` × `pay:mock` × `vision:mock` × `door:sim` × `client:h5`  
柜号 **`777740024057`**（非草稿 CAB-001）  
商户 `MCH-DEFAULT` / `13800138001` · 消费者 `13800138000`

> **范围更正**：此前「清库后快速回归」计划只含 T0⚡+§2.7+P0 主路径。2026-09-13 起按用户要求升到 **MASTER §8 完整轮** 继续扩测（本文件为累计台账）。

## §2.1 页面基线（当日 `scripts/count-page-baseline.mjs`）

| 端 | 实测 | 基线 | 结论 |
|----|------|------|------|
| consumer pages.json | 24 | 24 | OK |
| merchant pages.json | 22 | 22 | OK |
| admin menu paths（源码） | 66 | 66 | OK |
| admin router path 声明 | **76** | **76** | OK（含 `/`、`devices/:id`、`:pathMatch(.*)*`） |
| admin uniqueNonDynamic | **73** | **73** | OK（脚本过滤根路径+动态段；**非漂移**） |

> T6 澄清：此前「73 vs 文档 76」为口径差异，已回写 MASTER v2.1.1。

## T0 门禁

| 项 | 结果 |
|----|------|
| anti-jitter / table-align / mp-a11y / bundle | PASS（node 直跑） |
| smoke:admin-a11y / list-race | PASS |
| phase-f -CheckOnly | 多项连不上直连端口（trade 经 gateway `18080` UP；Grafana 本机未映射 `13000`）；**不阻断本轮 H5/Admin 浏览器测** |
| trade actuator | `http://127.0.0.1:18080/actuator/health` → UP |
| vision | `http://127.0.0.1:18082/health` → ok |
| xxl-job-admin | HTTP 200 |

## T3 · Admin 菜单轮转（全 66）

脚本：`scripts/admin-menu-rotation.mjs` → `admin-menu-rotation.json`

| 结果 | 说明 |
|------|------|
| **65/66 PASS** | 可达、非登录踢出、非白屏、无乱码 |
| `/recognition-demo` | 生产包 `ENABLE_TEST_TOOLS=false` 无路由 → 404；侧栏运行时也不挂该项 → **SKIP**（静态扫源码误计） |

## T3 · §2.4 UI 抽测 27 条

证据：`ui-24-results.json` + `browser-ui/ui-*.png`

| 组 | PASS | BLOCK | FAIL |
|----|------|-------|------|
| Admin A01–A10 | 10 | 0（A06 viewer 已补 PASS） | 0 |
| Consumer C01–C09 | 8 | 0（C07 登录态已补 PASS） | 0（C04 登录态弱项已记；C08 token 档位另册） |
| Merchant M01–M08 | 8 | 0 | 0 |
| **合计** | **26** | **0** | **0**（相对本轮口径；原始 ui-24-results 仍保留历史 FAIL 快照） |

## T1 · P0 十条（浏览器/证据）

| # | 链路 | 状态 | 证据 |
|---|------|------|------|
| 1 | 开门→结算 | PASS | session `1789223285233717578` → 争议 → order `1789223756879762169` PAID ¥3.50 |
| 2 | 争议闭环 | PASS | ticket `1789223313928665311` RESOLVED；仍 OPEN `…1679` 为留存待办 |
| 3 | 补货履约 | **PASS** | `taskId=1` warehouse：plan→pick/ship→签到→开门→凭证→COMPLETED；session `1789268547460101835`；`p0-replenish-completed.png` |
| 4 | 分账入账 | **PASS** | 2 笔：350=35+315、300=30+270；`SPLIT_CREDIT` 与 splitId 对齐；钱包 485=315+270−100；confirm 重放不双入；`uk_order_revenue_split_order_id` 拒重插；`p0-splits.png` / `full-round-p0-04-split.json` |
| 5 | 提现打款 | **PASS** | `requestId=1` `MW-FULL-1789268300338` **PAID** ¥1.00；可用 315→215¢；`p0-withdraw-paid.png` / `p0-withdraw-coupon-evidence.json` |
| 6 | 营销核销 | **PASS** | 发券 + 结算自动核销：`couponId=1` `CRSXYKJ8PXDW` USED；order `1789269033478876790` PAID 350→300（−50¢） |
| 7 | 设备运维 | **PASS** | 报修 `ticketId=1` OPEN→IN_PROGRESS→DONE；`p0-repair-done.png` |
| 8 | 消息公告 | **PASS** | 公告 `announceId=1` 创建→PUBLISHED；站内信 id=2 发给 user 10001；`p0-announcements.png` / `p0-notifications.png` |
| 9 | 审批流 | **PASS** | 商户要货 `requestId=1` 进审批待办 → ops accept **ACCEPTED** → task `2`；`p0-approval-replen-request.png` |
| 10 | 数据隔离 | **PASS** | 重建 `MCH-OTHER` + `13800138003`；API/UI 均不见柜 `777740024057` |

DB/账号续建：viewer `13900000005`→userId `100000031`；商户B `13800138003`→`100000032`。

## T2 · 权限抽样（2026-09-13 续）

| 项 | 结果 | 证据 |
|----|------|------|
| viewer 写设备 | PASS | API 403 |
| viewer RBAC/提现列表 | PASS | API 403 |
| viewer 争议无写按钮 | PASS | `t2-viewer-disputes.png` |
| viewer 直链提现 | PASS | → `/forbidden` · `t2-viewer-withdraw.png` |
| 他商户隔离 | PASS | `t2-mchB-devices.png` + API count=0 |

详见 `full-round-t2-p0.json`、`rebuild-accounts.json`。

## T4 · 分域 / 边界 / DV 抽样（2026-09-13）

| 组 | 结果 | 要点 |
|----|------|------|
| 边界 G | **5/5 PASS** | 401 未登录 / 403 viewer 写 / 404 设备 / 非法券名 400 / 空报修标题 400 |
| 分域 | **PASS** | MK-03 停用发券拦截「优惠券已停用」；R-01 拉黑开门 403；字典+系统参数 46 项；开门幂等同 sessionId |
| DV | **PASS** | DV-01/02 device-service 5 类单测绿；DV-03 模拟器 Up（主柜在线）；DV-04 vision `/health` ok；DV-05 OPEN MOCK 争议 2 条；DV-06 已知缺口已登记 |
| UI | PASS | 风控页 `t4-risk.png` |

证据：`full-round-t4.json`。

## T5 · 可观测 + §4 数据一致性（2026-09-13 续）

| 项 | 结果 | 证据 |
|----|------|------|
| trade / vision / xxl-job | UP / ok / 200 | 探活 |
| Grafana :13000 | **PASS** | 本轮 `docker compose up -d prometheus grafana`；`/api/health` ok v10.4.0；网关 `/devops/grafana` 同步；看板可见「AI Cabinet」· `t5-grafana.png` |
| DC-01 对账执行 | **PASS** | 日期 2026-09-13：WECHAT `reconId=1` **MATCHED**；BALANCE `reconId=2` **MISMATCH**（mock 可复现差异，符合 MASTER）；列表 2 条 · `t5-reconciliation.png` |
| DC-02 一致性巡检 | **PASS** | `POST /consistency/run` → `failCount=7`（含 INVENTORY_MISMATCH / CROSS_LINK，可解释）· `t5-consistency.png` |

详见 `full-round-t5.json`。  
注：拉起 Grafana 时 compose 重建了 trade-service（DB 数据保留）；事后 actuator UP。

## T6 · 缺陷复测与归档（2026-09-13）

| 用例/项 | 原状态 | 复测 | 说明 |
|---------|--------|------|------|
| UI-A06 viewer | BLOCK | **PASS** | 账号重建后只读视角通过 |
| 路由 76 vs 73 | 漂移疑点 | **CLOSED** | 口径澄清；MASTER **v2.1.1** |
| Grafana 不可达 | BLOCK | **PASS** | T5 已起服务 |
| UI-C07 会员登录 | BLOCK | **PASS** | 密码 API + `uni.setStorageSync` 注入后会员/兑换页可读 |
| P0#4 分账 | PARTIAL | **PASS** | 公式+流水+重放不双入+UK 已证 |
| P0#8 公告 | PARTIAL | **PASS** | 创建发布公告 + 手工站内信 |
| DV-06 多柜/MQTT | 缺口 | OPEN | 已登记 MASTER 附录 B |

证据：`full-round-t6.json`、`t6-route-audit.json`。

### §9 轮次记录

```text
轮次: 2026-09-12~13  环境: env:docker-full pay:mock vision:mock door:sim client:h5
P0: 10/10 PASS · 菜单 L1 65/66(+1 SKIP) · §2.4 本轮口径 26 PASS
T2抽样 PASS · T4 15/15 · T5 6/6 · T6 归档 · 基线 24/22/76·73/66 OK
关键开放: DV-06 已知缺口 · §6 性能未跑 · §10 真实环境🔒
```

## 未完成（完整轮后遗留，非本轮阻断）

- [x] T2 财务/运营角色侧栏全矩阵（viewer+他商户已覆盖；本轮补 finance/operator/replenisher）
- [x] T2 商户端店员/财务/店长/补货员边界（`13800138002/004/006/007`）
- [ ] §6 PERF-1 JMeter **1000 用户全量**（仓库无现成 .jmx；已有 node 轻量基线）
- [ ] §10 真实环境 🔒
- [x] §6 FE-PERF / 轻量抽样（见下「推送后回归」）
- [x] §6 PERF-2 轻量（顺序幂等 + 忙柜拒绝；并行同 key 竞态已登记）
- [x] §6 PERF-1/3/4 轻量（account / MinIO / vision）

## 推送后回归（2026-09-13 12:27+ · `dev` @ `6bbdb171`）

| 项 | 结果 | 说明 |
|----|------|------|
| git push `origin/dev` | PASS | `32a58897` feat + `6bbdb171` chore |
| `check:admin-anti-jitter` | PASS | |
| `check:admin-table-align` | PASS | |
| `check:admin-bundle` | PASS | FE-PERF 预算内 |
| `check:migration-safety` | PASS | |
| trade / vision health | PASS | UP / ok |
| Grafana `:13000` | PASS | 已拉起，`/api/health` database=ok |
| 运营账号「重置密码」菜单 | PASS | 他人「更多」可见 |
| 审计 `OPS_OPERATOR_RESET_PASSWORD` | PASS | API filter + UI `/audit?action=…` 1 行；`ops-reset-pwd-audit.png` |
| §6 轻量并发 | PASS | 50 并行 `GET /rbac/operators` p95≈275ms，错误率 0（非 JMeter 全量） |

## T2 角色矩阵续测（2026-09-13 12:36+）

脚本：`scripts/full-round-t2-role-matrix.mjs` → `full-round-t2-role-matrix.json`  
账号：`13900000002` 财务 · `13900000003` 运营 · `13900000004` 补货（本轮补建）

| 结果 | 说明 |
|------|------|
| **22/22 PASS** | 权限码期望、写 403、侧栏裁剪、直链 `/forbidden` 或可达 |
| 证据 | `t2-finance-sidebar.png` / `t2-operator-sidebar.png` / `t2-*-forbidden.png` / `t2-*-ok.png` |

## §6 PERF-2 轻量（2026-09-13）

脚本：`scripts/full-round-perf2-light.mjs` → `full-round-perf2-light.json`

| 项 | 结果 |
|----|------|
| 模拟器 Up + 主柜 ONLINE | PASS |
| 设备 status×30 p95 | **44ms** |
| 顺序同 idempotencyKey | PASS（同一 sessionId） |
| 柜忙二次开门 | PASS（409 使用中） |
| 并行同 key | **竞态登记**：一成功一 409（非顺序路径；DV-06/幂等加深候选） |

## T2 商户角色 + PERF 轻量（2026-09-13 12:44+）

脚本：`scripts/full-round-t2-merchant-perf.mjs` → `full-round-t2-merchant-perf.json`  
补建：`13800138002` 店员 · `004` 财务 · `006` 店长 · `007` 补货员

| 结果 | 说明 |
|------|------|
| **19/19 PASS** | 提现/邀请 403、结算可达性、店长看团队不可停用、补货无结算 |
| UI | `t2-mch-staff-home.png` / `t2-mch-finance-wallet.png` |
| PERF-1 轻量 | account×80 via `:18080` p95 **63ms**，错误率 0 |
| PERF-3 轻量 | MinIO `:9000/minio/health/live`×40 p95 **20ms** |
| PERF-4 轻量 | vision `/health`×40 p95 **48ms** |

仍开放：DV-06 已知缺口、PERF-1 JMeter 全量、PERF-3/4 业务深压、§10 真实环境🔒。

## 结论（当前）

**MASTER 完整轮已收口**，P0 **10/10 PASS**（含 #4 分账加深）。  
推送后门禁、重置密码回归、T2 运营三角色 + 商户四角色、PERF-1/2/3/4 轻量已补跑。证据目录 `docs/uat-screenshots/2026-09-12/`。
