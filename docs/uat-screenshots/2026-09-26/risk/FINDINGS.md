# 风控 · FINDINGS · 2026-09-27

> [`BUTTONS.md`](./BUTTONS.md) · [`RISK_FULL_BROWSER_UAT.md`](../../../uat/RISK_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · FINDING 0 · BLOCK 0**

风险事件共 **24** ↔ API。处置状态中文（待处置等）。用户链跳转 `/users?keyword=` 正常。黑名单共 **0**，「暂无黑名单」诚实。加入黑名单弹层 **userId 不预填**（H08）→**取消**。移出因无行 SKIP。`?tab=blacklist` 深链 OK。双导出 CSV 成功。窄视口 900 无横滚；结束 **1366×768 DPR=1**。

## 数据对照

| 面 | UI / API |
|----|----------|
| 风险事件 | 共 **24** |
| 样例 | eventId 26 · DISPUTE_CREATED · OPEN · userId 10001 |
| 黑名单 | 共 **0** |

## 本轮缺陷

无 FAIL / FINDING。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **软写**：加黑仅测到弹层取消；移出有数据时须补测确认取消。 |
| 2 | userId **禁止预填 1**（源码 H08）——本轮实测为空，符合。 |
| 3 | 黑名单懒加载：首次切 Tab 才请求；无 `ops:risk:blacklist` 会警告不请求。 |
| 4 | 导出文件名后端为 `risk-events.csv` / `risk-blacklist.csv`（非中文前缀）——可接受。 |
| 5 | 事件行无「处置」写操作入口（只读展示 disposition*）；处置写路径若不在本页，不在本册范围。 |

## 证据

`rk-01`…`rk-10` · `rk-ux-*` · `rk-99-end`
|
