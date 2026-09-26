# 开门记录 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`SESSIONS_FULL_BROWSER_UAT.md`](../../../uat/SESSIONS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**（修 #205 后）

列表 / 十一 Tab / 关键词 / 滞留深链 / 导出 / 时间线 / 播放 / 上传队列 / 订单·设备链均实测通过。演示环境无活跃可取消会话，「取消会话」记 N/A。

## 数据对照

| 项 | UI | API |
|----|-----|-----|
| 总量 | 共 57 | 57 |
| 仅滞留 `stuckOnly=true` | 共 0 | 0 |
| 误参 `stuck=1`（不经前端） | — | 57（**被忽略**，非 UI 路径） |
| 已完成 / 待审核 / 已取消 Tab | 35 / 8 / 14 | 与 Tab 一致量级 |

## 本轮修复（#205）

| 现象 | 根因 | 必须怎么做 |
|------|------|------------|
| 类型选「补货」表格空，底栏仍「共 57 条」 | `kindFilter` 只滤本页 `items`，`total` 仍用服务端全量 | 本页有剔除时 `total=过滤后长度`；全保留时仍用服务端 total 以翻页 |

复测：补货 → 共 0；消费 → 有行。片：`ses-retest-kind-*.png`。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 滞留 API 须 `stuckOnly`（+可选 `stuckMinutes`）；路由 `?stuck=1` 由前端映射，勿 curl 只传 `stuck=1` 当回归。 |
| 2 | #195 仍成立：UI 滞留空态无已完成历史。 |
| 3 | 类型筛选仍为前端本页过滤；长期应落服务端 `sessionKind`（记 §10 可选）。 |
| 4 | 「查看订单」深链带 `deviceId`（非 orderId），与源码 `goOrders` 一致。 |
| 5 | 取消会话：活跃态 Tab 均 0 行 → N/A。 |

## 证据

`ses-01`…`ses-10` · `ses-retest-kind-*` · `ses-ux-*` · `ses-api-list.json` · `ses-99-end`
|
