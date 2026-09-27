# 新会话提示词 · 双端小程序基础实测（先测后写脚本）

> **用法**：新开 Cursor 对话后，把下方「提示词正文」整段发给 Agent；或先 `Read` 本文件再执行。  
> **日期**：2026-09-27  
> **前置**：Admin 七册侧栏 UAT（以 L1/部分 L2 为主）已收口；观测栈 iframe 已复核；小程序回补仅 **P0#1 充值/余额** DONE。

---

## 提示词正文（复制从这里开始）

```
你是 AI Cabinet 仓库的 Agent。本会话只做一件事：先把消费者小程序 + 商户小程序的基础功能用微信开发者工具（mp-weixin）实际测完；在小程序基础实测收口之前，禁止编写「全业务/三端」自动化测试脚本大套。

### 必读
1. docs/PROJECT_KNOWLEDGE.md（§0–§4、§7、§9）
2. AGENTS.md
3. docs/uat/MINIPROGRAM_BACKLOG.md
4. docs/uat/OVERVIEW_FULL_BROWSER_UAT.md §6–§7
5. .cursor/skills/ai-cabinet-dev-test/SKILL.md
6. 本文件 docs/uat/MP_SMOKE_SESSION_PROMPT.md（约定全文）

### 背景与诚实口径（禁止撒谎）
- 运营后台七册 FULL_BROWSER_UAT 多数是 **L1（壳）**：能开、筛/空态、软写确认→取消、中文文案；有数则对数，无数则诚实空。
- **不是**「每页都有完整业务数据 + 三端金额状态一致」。
- 三端（Admin ↔ 商户 mp ↔ 消费者 mp）只对资金/履约挂钩页强制；系统/对账/OTA 等 **SKIP 三端**（见 backlog §2）。
- 验收权威是 **mp-weixin**（`clients/*/dist/dev/mp-weixin` + 微信开发者工具）；**禁止用 H5 :3001/:3002 冒充 mp PASS**。
- 写路径默认 **软写**：充值/提现/支付/补货完成等只点到确认或停在可取消处，禁止默认硬付、真提现、污染演示账（用户明确要求硬写除外）。

### 分层定义（报告必须标注）
| 层 | 含义 |
|----|------|
| L1 壳 | 页可开、主按钮可见、空态诚实、软写可取消 |
| L2 有数 | 至少 1 条真实业务数据 + 关键金额/状态可读 |
| L3 三端 | 同主键与 Admin（及对端 mp）金额/状态差 ≤1 分 |

本会话目标：**双端小程序基础包尽量到 L1，能碰到的有数路径做到 L2**；L3 全量业务脚本 **本会话不做**（留给后续会话，且须先有场景清单）。

### 本会话要做（阶段 1）
1. 编译双端：`pnpm --filter @aicabinet/consumer-mp run build:mp-weixin:dev` 与商户对应脚本；确认 `dist/dev/mp-weixin` mtime 新、urlCheck=false。
2. DevTools CLI：`D:\devTools\微信web开发者工具\cli.bat`；automator 端口 **9420**。切换工程必须 `cli close` 再 `cli open`，确认模拟器不是错端（商户登录文案含「补货与运营」≠ 消费者）。
3. 账号：消费者 `13800138000`/`123456`；商户 `13800138001`/`123456`；柜机**运行时解析**（`Resolve-E2eTestDevice` / `demo/ensure`，勿写死柜号）。
4. **消费者 mp 烟测清单**（尽量全页点开，软写）：
   - 登录 / 首页（开门购物）/ 订单列表·详情 / 购物视频（可播或诚实失败）
   - 我的：余额、优先支付方式切换（可改回）、充值页（不硬付）、余额明细
   - 争议/账单审核入口（有单则进）
   - 开通支付/账单结果（若可达）
   - 热门活动、会员、积分明细/兑换、优惠券、消息、公告、帮助、故障报修、意见反馈、条款
5. **商户 mp 烟测清单**（软写）：
   - 登录 / 工作台 / 柜机列表·详情 / 待办
   - 补货任务（签到/开门等到确认则取消；勿默认 complete）
   - 要货申请、经营分析、结算对账、争议处理、柜机订单·详情·视频
   - 分账明细、商户钱包、线长钱包（若角色可见）
   - 点位定价（改价确认→取消）、团队成员（写→取消）
   - 消息、公告、我的/通知偏好
6. 每个端写清：PASS/FAIL/BLOCK/SKIP + 截图目录 + 是否 L1/L2。
7. 更新 `docs/uat/MINIPROGRAM_BACKLOG.md` 中与本轮相关的状态；在对应 FINDINGS 或新建 `docs/uat-screenshots/2026-09-26/mp-smoke/` 下落 BUTTONS/FINDINGS；`docs/PROJECT_KNOWLEDGE.md` §9 Changelog 追加一行。

### 本会话禁止
- 编写「覆盖所有业务场景」的 Playwright/automator 大脚本或 CI 套件
- 用 H5 结果写成 mp-weixin PASS
- 未测完双端基础就宣称三端业务测试完成
- 默认硬充值/硬提现/硬完成补货
- 未读活文档就大段实现

### 本会话结束后留给下一会话（仅在提示里说明，本会话不写脚本）
- ~~根据双端 pages.json + consumerApi/merchantApi + 已有 e2e-*.ps1，整理 **尽量全的业务场景清单**（分 P0/P1/P2）~~ → **DONE** [`MP_THREE_END_SCENARIOS.md`](./MP_THREE_END_SCENARIOS.md)
- ~~造数闸门一键脚本~~ → **DONE** `scripts/mp-seed-gate.ps1`
- 再按清单写三端自动化（Admin Playwright + mp automator），P0 全覆盖、P1 抽样

### 工具
- UI：Playwright MCP 仅用于 Admin 对照；小程序必须 DevTools/automator
- 已有探测目录可参考：`.tmp/mp-auto/`、`docs/uat-screenshots/2026-09-26/recharges/MP_BACKFILL.md`（P0#1 已 DONE）

先 Read 必读文件，再编译→开消费者测完→切商户测完→落文档。用户说「继续」则按清单下一项推进。
```

（提示词正文到此结束）

---

## 约定摘要（给人看的）

| 约定 | 内容 |
|------|------|
| 顺序 | **先双端 mp 实测** → 再扩场景清单 → 再写脚本 |
| 分层 | L1 壳 / L2 有数 / L3 三端；报告必须标注 |
| 三端范围 | 仅资金/履约 P0；系统等 SKIP |
| 软写 | 默认确认→取消；禁默认硬资金 |
| 权威 | mp-weixin；禁 H5 冒充 |
| 已完成 | Admin 七册 L1 为主；充值余额 MP 回补 DONE |
|
