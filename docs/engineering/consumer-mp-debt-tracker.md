# consumer-mp 技术债追踪（2026-09-25 源码审计）

> **地位**：消费者小程序（`clients/consumer-mp`）已知问题的**唯一进度表**。  
> **纪律**：每修完一项 → 状态改 `done` + 填「完成记录」→ 必要时追加 `lessons-learned` → `PROJECT_KNOWLEDGE` §9 一行。  
> **禁止**：只改代码不更新本表；用「感觉修好了」勾 done（须有门禁/命令或 diff 证据）。  
> **验收权威**：**mp-weixin**（微信开发者工具 / 真机）。**H5 不参与本表 UI / 验收结论**（与 `docs/CONSUMER_MP_OPTIMIZATION_REPORT_2026-09-24.md` 一致）；H5 仅影响 CI 的条目标 `deferred` 或低优先。

**来源会话**：2026-09-25 用户选定「对齐 admin-vue debt-tracker 打法，不管 H5」。  
**对照**：`docs/engineering/admin-vue-debt-tracker.md`（D1–D25 已清）。  
**总判**：鉴权/开门主路径相对可控；主风险是 **God 首页、无端点门禁的裸 `/api/v2`、soft-fail 空列表、金钱写路径无契约测、token 过期只写不读、三端 UI 副本漂移**。

---

## 状态图例

| 状态 | 含义 |
|------|------|
| `open` | 未开工 |
| `doing` | 进行中 |
| `done` | 已合入本仓并有完成记录 |
| `deferred` | 知情延后（须写原因） |

---

## 待办清单（按建议顺序）

| ID | 优先级 | 状态 | 问题摘要 | 主要证据 | 完成记录 |
|----|--------|------|----------|----------|----------|
| C1 | P0 | done | Soft-fail 把可选接口失败伪装成「无数据」 | `member/index.vue` `myCoupons().catch(() => [])`；`messages/messages.vue` `pendingOrderCount().catch(() => ({ count: 0 }))`；`verify/verify.vue` `consumerPublicConfig().catch(() => null)` | 2026-09-25：新增 `utils/soft-fallback.ts`（label+toast，401 不 toast）；三处改 `softFallback(...)`；4 测 |
| C2 | P0 | done | 无 `ConsumerEndpoints` + 无门禁；路径散落字面量 | `utils/consumer-api.ts`（大量 `/api/v2`）；`dict-runtime.ts`；`video/video.vue` 页内裸拼；仅有 `check-admin-endpoints`，无 consumer 等价 | 2026-09-25：Endpoints+门禁；C2b 核心域；2026-09-26 C2c：devices/member/marketing/coupons/feedback/announcements 迁入；`consumer-api` 无余 `/api/v2` 字面量 |
| C3 | P0 | done | JWT 进 Storage；`expires` 只写不读 | `consumer-api.ts` `EXPIRES_KEY` 写入 `applyTokenSession`；`isConsumerLoggedIn()` 只看 token / Cookie 标记，**不校验过期** | 2026-09-25：`getConsumerToken` 读 expires，Bearer 到期清会话；Cookie 路径不硬清；`consumer-session` 2 测 |
| C4 | P1 | done | 金钱写路径无前端契约测（对标 admin D5） | 已有 `account`/`pay-channel`/`dispute-form` 测；**无**充值/退款/余额退申请契约测；写路径在 `recharge.ts`、`order-detail`/`result`/`recharge.vue` | 2026-09-25：`money-ui-contracts` + 8 测；order-detail/result/recharge/consumer-api 接线 |
| C5 | P1 | done | `pages/index/index.vue` 上帝页 | 约 **3400+** 行（script 约 1800+）；扫码/鉴权/开门/目录/live-cart/轮询一体 | 2026-09-25：landing-session；C5b：开门可用性/超时；2026-09-26 C5c：轮询/开门编排决策纯函数 + 测；定时器与 createSession API 仍页内 |
| C6 | P1 | done | 退款/申诉 UI 与逻辑在 order-detail ↔ result 双份 | `order-detail.vue` ~1350；`result.vue` ~1200；平行 `submitRefund` / 确认文案 | 2026-09-26：order-appeal 种子/校验；C6b：弹层文案；C6c：`OrderAppealSheet` + partial slot；提交写路径仍页内 |
| C7 | P1 | done | 次级肥页：orders / mine / recharge / login | 行数均约 950–1150；`mine` 内嵌 mock 充值 | 2026-09-26：mine 首刀 + C7b：orders/login/recharge 样式外置（1140/1034/960→663/518/576）；逻辑拆分另开 |
| C8 | P2 | done | 弱类型口袋（规模小于 admin） | 少量 `as any`（如 `order-detail`、`verify`）；query/flag 的 `Record<string, string>` 可保留 | 2026-09-26：verify 支付宝签约去 `import.meta as any`，改 `#ifdef H5`；order-detail 已无 `as any`；query `Record` 保留 |
| C9 | P2 | deferred | 平台敏感 `uni.*` 缺守卫（mp 权威；H5 deferred） | `scanCode`/`makePhoneCall`/`setClipboardData`/`chooseImage` 等；支付已有 `#ifdef MP-WEIXIN`；H5 崩溃仅 CI 相关（已有 `setBackgroundColor` 先例 lessons #139） | 知情延后：本表验收不认 H5；仅当 CI 再红或抽 `safeUniCall` 时开 |
| C10 | P2 | done | easycom 本地镜像 vs `shared-uni`；`error-state` 已漂移 | `app-nav-bar`/`empty-state`/`app-button` 三端曾对齐；`error-state` consumer≡merchant≠shared；注释要求 Keep in sync | 2026-09-26：正文已与蓝本一致；新增 `sync-shared-uni-components` + `--check` 并入 `check:shared-component-sync`；easycom 仍本地路径 → C10b/M9 |
| C11 | P3 | done | `settleWithin` / 开门超时吞错易被误改成「空失败」 | `index.vue` `promise.catch(() => null)` + 幽灵会话注释；与 C1 外观相似、意图不同 | 2026-09-26：`settleWithin` 文档化「故意失败→null」+ 失败/成功单测；语义≠ softFallback；调用方仍走 activeSession 轮询 |
| C12 | P3 | done | `consumer-api` 上帝模块 + 页面外裸 URL | `consumer-api.ts` ~886 行；`video.vue` 绕开 API 层 | 2026-09-26：视频 URL；C12b open-attempt；C12c：`consumer-download` 鉴权下载头/结果分类 + 测；登录内核另开 C12d |

---

## 建议首期切片

```
C12d；C10b（easycom 直指 package）与 M9 对齐（mp 风险知情延后）；C9 保持 deferred
```

**C5 首刀边界**：只抽无 UI 的会话/开门 composable；**禁止**同 PR 改落地页布局与视觉。  
**C5b 边界**：开门常量 + withTimeout/可用性/并发决策/弱网文案；**禁止**同 PR 挪 createSession/轮询编排（→ C5c）；**禁止**改 settleWithin 语义。  
**C5c 边界**：轮询/开门编排决策纯函数（`classifyPollSessionState` / `beginCabinetEntryGate` 等）；**禁止**同 PR 挪定时器与 createSession API 出页。  
**C6 首刀边界**：只抽表单种子/校验/请求体；**禁止**同 PR 大改申诉弹层布局。  
**C6b 边界**：弹层标题/副文/证据/提交文案 + `validateAppealForm`；**禁止**同 PR 抽 `OrderAppealSheet`（→ C6c）。  
**C6c 边界**：抽 `OrderAppealSheet`（含 surface 修饰 + partial slot）；**禁止**同 PR 挪 submitDispute/submitRefund 写路径。  
**C7 边界**：体验充值文案/键 + 次级肥页样式外置；**禁止**同 PR 改支付主路径与登录鉴权。  
**C8 边界**：去运行时 `as any`；平台分支用 `#ifdef`；query `Record` 可保留。  
**C10 首刀边界**：同步脚本 + 门禁；**禁止**同 PR 改 easycom 指向（mp 风险 → C10b）。  
**C11 边界**：`settleWithin` 失败/超时→null 是孤儿开门故意语义；**禁止**改成 toast softFallback；**禁止**删掉后续 activeSession 轮询。  
**C12 首刀边界**：视频绝对 URL 收口；**禁止**同 PR 大拆 `consumer-api`。  
**C12b 边界**：开门幂等 `consumer-open-attempt`；consumer-api 仍 re-export；**禁止**同 PR 大拆登录/request 内核（→ C12c）。  
**C12c 边界**：鉴权下载头/结果分类纯函数；**禁止**同 PR 大拆 applyTokenSession/login（→ C12d）。  
**C2b 边界**：auth/account/payment/orders/sessions/disputes 进 Endpoints；**禁止**同 PR 扫设备/会员/营销字面量（→ C2c）。  
**C2c 边界**：devices/member/marketing/coupons/feedback/announcements；扫完 `consumer-api` 字面量即可收口。  
**C1 边界**：勿改 `orders.vue` 主 `load()`（已有 try/catch + error）；孤儿会话宽限期见 C11（已 done）。

---

## 已相对健康（勿重复开票）

- `account` / `pay-channel` / `dispute-form` 已有单测
- H5 Cookie 路径规避 JWT 落盘（`isConsumerCookieAuth`）
- 订单主列表失败路径相对干净（相对 member/messages soft-fail）
- `setBackgroundColor` H5 能力检测已合入（lessons #139；本表 C9 仍 deferred）

---

## 完成记录模板

```md
| Cx | … | done | … | YYYY-MM-DD：做了什么；命令/测；续拆 → Cy |
```
