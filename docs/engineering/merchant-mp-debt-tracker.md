# merchant-mp 技术债追踪（2026-09-25 源码审计）

> **地位**：商户小程序（`clients/merchant-mp`）已知问题的**唯一进度表**。  
> **纪律**：每修完一项 → 状态改 `done` + 填「完成记录」→ 必要时追加 `lessons-learned` → `PROJECT_KNOWLEDGE` §9 一行。  
> **禁止**：只改代码不更新本表；用「感觉修好了」勾 done（须有门禁/命令或 diff 证据）。  
> **验收权威**：**mp-weixin**。**H5 不参与本表 UI / 验收结论**。

**来源会话**：2026-09-25 用户选定「对齐 admin-vue debt-tracker 打法，不管 H5」。  
**对照**：`docs/engineering/admin-vue-debt-tracker.md`；消费端见 `consumer-mp-debt-tracker.md`。  
**总判**：补货已拆多个 composable，主风险是 **静默 `softFallback` 伪装空列表**、**金钱写路径无契约测**、**`merchant-api` 裸路径无门禁**、**首页/异常多页扇出**、经营/争议肥页。

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
| M1 | P0 | done | 主路径 `softFallback` 静默吞错 → 故障被当成「暂无数据」（对齐 admin D3） | `merchant-api.ts` `softFallback` = `promise.catch(() => fallback)` **无 toast**；`useReplenishmentList` tasks/devices/lowStock → `[]`；`business.vue` 五路 softFallback；对比 `useHomeWorkbench.softErr` **会 toast** | 2026-09-25：`utils/soft-fallback.ts` 必带 label+toast；补货**主列表**硬失败；其余调用点补中文 label；3 测 |
| M2 | P0 | done | 钱包提现 / 争议 resolve 无前端金钱契约测（对齐 admin D5） | `WalletPage.vue` 提现；`disputes.vue` KEEP/WAIVE/CONFIRM；单测仅工具层，无 wallet/dispute money 测 | 2026-09-25：`money-ui-contracts.ts` + 8 测；WalletPage/disputes 接线 |
| M3 | P1 | done | `merchantApi` 上帝模块：大量 `/api/v2` 字面量，无 `MerchantEndpoints` / 门禁 | `utils/merchant-api.ts` ~785 行；`video.vue` 另拼订单视频 URL；仅有 admin 端点门禁 | 2026-09-25：`api/endpoints.ts` + `check-merchant-endpoints`；修 video；`merchant-api` 批量迁入 → M3b |
| M4 | P1 | open | 列表扇出 / 类 N+1：首页多路并行 + `openExceptions` 多页串行 | `useHomeWorkbench.fetchHomeDashboardBundle` 约 9 路；`openExceptions` OPEN+PROCESSING 各最多 3 页；补货详情证据逐文件 download | |
| M5 | P1 | open | 补货页仍肥 + 壳层弱类型残留 | `replenishment.vue` ~1130 行（style 过半）；`useReplenishmentShell` `devices: Ref<Record<string, unknown>[]>`、`open: any` | |
| M6 | P1 | open | `business.vue` 上帝页 + 静默 softFallback + 手写 `/100` 金钱展示 | ~948 行；load 五路 softFallback；多处 `(cents/100).toFixed(2)` 未统一 `fmtMoney` | |
| M7 | P1 | open | `disputes.vue` 上帝页；首屏 `size=100`；列表+详情+resolve 同文件 | ~839 行；`disputes(..., 0, 100)`；写路径无测 → M2 | |
| M8 | P2 | open | device-detail / 补货侧弱类型：`Record<string, unknown>` settings/devices | `merchant-api` deviceSettings；`useReplenishmentList` devices；slots PUT 无泛型 | |
| M9 | P2 | open | 共享 UI 副本未切到 `shared-uni`（易再漂） | 本地 `empty-state`/`app-nav-bar`/`app-button`/`error-state`；package 已 export shared 组件；lessons #109 已证双端同步痛 | |
| M10 | P2 | open | `request.vue` 仍大；draft/suggest softFallback；下拉 refresh 空 catch | ~856 行；`.catch(() => {})`；与补货域重叠未进 composable | |
| M11 | P2 | open | 金钱展示双轨：`fmtMoney` vs 手写 `/100` | `business`/`pricing`/`splits`/`WalletPage`/`sales-chart` 手写；orders/disputes/home 已用 `fmtMoney` | |
| M12 | P3 | open | video 旁路拼 URL；api 面仍大 | `video.vue` 自拼 + Bearer；宜并入 Endpoints（承接 M3） | |

---

## 建议首期切片

```
M4（首页/异常扇出）
 → M5（补货类型 + 样式拆）
 → M6 / M7（business / disputes）
 → M8 → M9 → M10 / M11 → M12；M3b（merchant-api 迁 Endpoints）穿插
```

**M1 首刀边界**：补货任务主列表 + 待办主列表；失败 → 可见 error-state / toast；**禁止** `[]` 伪装空。勿动履约写路径。  
**M5 边界**：履约/开门逻辑禁止回流 SFC；样式可先拆出。

---

## 已相对健康（勿重复开票）

- 登录明文密码存储已清（`clearStoredPassword`）
- 补货履约已拆 `useReplenishmentShell` / `List` / `Detail` / `Fulfillment` / `Scan` / `Door` 等
- `type-check` 已是 `vue-tsc --noEmit`
- orders 分页 `PAGE_SIZE=50`；splits 已有「禁止一次拉 100」注释
- 首页部分接口已用 `softErr`（有 toast）—— M1 已将静默 `softFallback` 对齐为 label+toast；补货主列表硬失败

---

## 完成记录模板

```md
| Mx | … | done | … | YYYY-MM-DD：做了什么；命令/测；续拆 → My |
```
