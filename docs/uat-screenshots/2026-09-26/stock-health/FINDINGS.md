# 库存健康 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`STOCK_HEALTH_FULL_BROWSER_UAT.md`](../../../uat/STOCK_HEALTH_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

维度/URL/生命周期 ALL、柜机筛选、双路径导出、一键补货规划、行「设备/补货」均实测通过。默认「仅投放」下 KPI 全 0 为演示数据特征，非丢数。

## KPI vs API（`lifecycleStatus=` 空 = 全部状态）

| 项 | UI | API |
|----|-----|-----|
| 断货行 | 6 | 6 |
| 低库存行 | 1 | 1 |
| 临期行 | 0 | 0 |
| 涉及柜机 | 2 | 2 |
| planDeviceIds | 一键规划（2 台） | `330449777078`,`CAB-001` |

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **默认生命周期=DEPLOYED**：演示异常库存多在 INBOUND → 首屏 KPI 0 /「暂无异常库存」诚实。看板深链应带 `lifecycleStatus=ALL` 或改默认。 |
| 2 | **一键规划 URL**：弹层打开后 `clearPlanQuery` 清掉 `plan`/`deviceIds`，地址栏只留 `?tab=shortage` — 设计行为。 |
| 3 | **临期下架**：无二次确认直接写任务；本环境无临期行未点。若可达建议加 confirm（UX 债）。 |

## 补测：规划弹层（2026-09-26 晚）

首轮只验「跳到 shortage」。补测发现深链不弹窗 → 修 `onMounted` 顺序（lessons **#203**）后复测：

| 入口 | 结果 |
|------|------|
| 补货页「规划补货路线」 | PASS · 弹层 → 取消 · `sh-plan-route-dialog.png` |
| 库存健康「一键补货规划」 | PASS · 预填 2 台 → 取消 · `sh-plan-from-health.png` |
| 深链 `plan=1&deviceIds=` | PASS · 预填 2 台 · `sh-plan-from-query.png` |
| 缺货 Tab「一键规划补货」 | PASS · `sh-plan-from-shortage-tab.png` |
| 设备详情等价深链 | PASS · 预填 1 台 · `sh-plan-from-device-equiv.png` |

未点「创建路线」（会写库）；一律取消关闭。

## 证据

`sh-01`…`sh-07` · `sh-plan-*` · `sh-ux-*` · `sh-api-all.json` · `sh-99-end`
|
