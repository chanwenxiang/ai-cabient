# 异常中心 · 按钮清点 · 2026-09-26

> `/exceptions` · Playwright **1366×768**（窄/宽另用 MCP resize，禁止脚本内 goto 冲掉视口）  
> [`EXCEPTIONS_FULL_BROWSER_UAT.md`](../../../uat/EXCEPTIONS_FULL_BROWSER_UAT.md)  
> 写路径：确认框 → **取消**

---

## A 筛选 / Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | `?status=OPEN` | ✓ | 待处理 (12) · 共 12 = API · `exc-01-open.png` |
| A-02 | Tab「全部」 | ✓ | URL `status=ALL` · 共 111 · `exc-02-tabs.png` / `exc-02b-all-direct.png`（#207 修复后） |
| A-03 | Tab「已解决」 | ✓ | 共 99 |
| A-04 | 仅超时 | ✓ | `exc-03-overdue.png` |
| A-05 | 查询 / 重置 | ✓ | |
| A-06 | 导出 | ✓ | `异常_20260926_*.csv` |
| A-07 | 刷新 / 列设置 | ✓ | |

## B 头 / 深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 设备运维 | ✓ | `/device-ops` · `exc-04-device-ops.png` |
| B-02 | 行「设备」 | ✓ | `/devices/777740024057` · `exc-13-device.png` |
| B-03 | 抽屉「开门记录」 | ✓ | `/sessions?deviceId=…&sessionId=…` · `exc-06-sessions.png` |
| B-04 | 抽屉「打开争议审单」 | ✓ | `/disputes?…` · `exc-07-disputes.png` |

## C 抽屉工作台（软写）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 行详情 / 处理 | ✓ | `exc-05-detail.png` |
| C-02 | 免单/全额退回 → 取消 | ✓ | `exc-08-waive-confirm.png` |
| C-03 | 添加备注 → 取消 | ✓ | `exc-09-note.png` |
| C-04 | 转派 → 取消 | ✓ | `exc-10-transfer.png` |
| C-05 | 重试识别/结算 → 取消 | ✓ | `exc-11-retry.png` |
| C-06 | 取消会话并释放设备 → 取消 | ✓ | `exc-12-cancel-session.png` |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `exc-ux-narrow.png` · vw=900 |
| D-02 | 1366 / 1920 | ✓ | `exc-ux-1366.png` / `exc-ux-1920.png` |
| D-03 | API 落盘 | ✓ | `exc-api-list.json` |

## 结案

- [x] 可见可点覆盖；五类写路径均取消
- [x] 「全部」Tab 与 API total=111 对齐（#207）
- [x] 视口主测 1366 结束仍为 1366
|
