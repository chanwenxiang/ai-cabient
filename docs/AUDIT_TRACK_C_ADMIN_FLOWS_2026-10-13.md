# Track C-admin — 运营后台业务流程实测（2026-10-13）

> 计划蓝本：`C:\Users\cwx\.qoder-cn\plans\brave-harbor-grouse.md` §Track C。
> 分工：10-12 的三端冒烟测的是「页面开得了」，本轮测的是「业务走得通」——每条流程必须把业务对象
> 真实造出来并推到终态，断言落在 **API/DB 口径**上；页面文案类判据一律不用（外壳恒真，
> 见 `scripts/lib/ui-assert.mjs` 头注）。
> 驱动脚本：`clients/admin-vue/tests/audit-admin-flows-uat.mjs`（新增，流程可选 `FLOWS=` 增量跑）。

## 0. 被测环境与被测版本溯源（先钉死「测的是哪一版」）

| 项 | 实测值 | 取证方式 |
|---|---|---|
| 后端 trade-service | 镜像 `ai-cabinet/trade-service:local` = `sha256:c906408274e9`，镜像构建 2026-10-13 11:20:34 CST，容器创建 11:23 | `docker inspect ai-cabinet-trade-service-1` |
| compose 定义 | `D:\ai-generated code\ai-cabinet\infra\docker-compose.full.yml` + `docker-compose.win-ports.yml`（**主仓 dev 工作树**，project=`ai-cabinet`） | 容器 label `com.docker.compose.project.config_files` |
| 行为指纹 | 三例畸形识别上报（行置信度 99 / 整体置信度 99 / quantity 2e9，会话号不存在）**全部 HTTP 404「购物会话不存在」** ⇒ 入口值域校验不存在 ⇒ **被测后端 = dev 构建，不含审计分支批九（AU-038/039）等入口校验** | `node scripts/devops/probe-audit-code-fingerprint.mjs`（exit 0，原文存 `docs/evidence/audit-2026-10-13/trackC-admin/code-fingerprint.json`） |
| admin 前端产物 | 入口 `assets/index--YG4jrXZ.js` + `assets/ui-vendor-Bx2nlMIp.js`（与 dev@3d627ec1 的 CI 保真产物一致；审计分支 merge 后同 hash） | Playwright E-01，`admin-build-fingerprint.json` |
| 运营登录 | 13900000001/123456 + Redis 图形码（`aicabinet:captcha:<id>`，本机经 TCP 127.0.0.1:6379 读取）→ 落 `/admin/dashboard` | Playwright E-03 + `e03-admin-login.png` |
| 页面基线 | 订单管理 rows=0（平台已被清空）、争议审核 rows=2、异常中心 rows=2 | Playwright E-04/05/06 |

**限制（必须随结论一起读）**：本轮流程实测跑在 **dev 构建** 上。把审计分支代码装进本地栈的动作
（用 worktree 打好的 jar 叠镜像 + `docker compose up -d trade-service`）被本机会话的安全策略拦下，
未强行绕过。因此：

1. 流程类结论（业务能否走通、UI/权限/状态机是否正确）在 dev 与审计分支之间**同构**——审计分支的九批修复
   全是入口校验/归属比对/幂等守卫，不改写正常业务路径；
2. 但「审计分支修复项的运行期复验」**不在本轮覆盖范围**，其证据目前是单元测试（每批先红后绿）+ CI；
   接审计分支版本后的运行期复验清单已并入收尾报告的「接真前 checklist 增量」。

## 1. 八条流程与状态

| # | 流程 | 判据（须可在 dev 上变红） | 状态 |
|---|---|---|---|
| F1 | 下单→将邑开门→端侧识别→结算→订单→购物视频 | 会话进终态 + 订单金额=行金额合计 + 视频接口回真实 MP4 | 未开始 |
| F2 | 争议全链 | 低置信→DISPUTED→后台处置→终态与资金一致 | 未开始 |
| F3 | 订单退款→报表口径 | 退款额从 GMV/营收扣减（G2 口径核对） | 未开始 |
| F4 | 提现审核（含 CB-035 T+1 解锁时刻可配） | 审核状态机 + 到账/驳回后余额回滚 | 未开始 |
| F5 | 平台资金池五操作 | 每笔操作有台账、余额不变量成立 | 未开始 |
| F6 | 设备租赁（出账/押金/锁柜） | 押金非负、出账流水可对 | 未开始 |
| F7 | 补货任务→补货录像 | 任务终态 + 录像行可按订单号取回 | 未开始 |
| F8 | RBAC 越权矩阵 | 子账号在菜单/按钮/API 三层各自被拦 | 未开始 |

## 2. 逐流程记录

（随跑随填：每条含命令、file:line、截图路径、PASS/FAIL/SKIP 与新登记台账号）

## 3. 本轮新发现

| 台账 | 级 | 发现 | 证据 |
|---|---|---|---|
| 待填 | | | |
