# 争议审核 · 按钮清点 · 2026-09-26

> `/disputes` · Playwright **1366×768**（窄/宽另用 MCP resize，禁止脚本内 goto 冲掉视口）  
> [`DISPUTES_FULL_BROWSER_UAT.md`](../../../uat/DISPUTES_FULL_BROWSER_UAT.md)  
> 资金写路径：确认框 → **取消**

---

## A 筛选 / Tab

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | `?status=OPEN` | ✓ | 状态「待审核」· 共 8 = API · `dsp-01-open.png` |
| A-02 | Tab 识别争议 | ✓ | 共 8 · `dsp-02-recog.png` |
| A-03 | 识别子码（低置信等） | ✓ | 可切（本轮有数/空均中文） |
| A-04 | 重置 | ✓ | |
| A-05 | 关键词工单号 | ✓ | ≥1 行 |
| A-06 | 导出 | ✓ | `争议_20260926_*.csv` |
| A-07 | 刷新 / 列设置 | ✓ | 列设置后须 Escape 再点行（防 popover 挡点击） |

## B 审单工作台

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 行链接 / 详情开抽屉 | ✓ | `dsp-05-detail.png` |
| B-02 | 认领工单 | ✓ | toast「已认领：运营超管」· `dsp-06-claim.png` |
| B-03 | 重新加载录像 / 新窗口打开 | ✓ | 可见可点 |
| B-04 | 无录像 + 已对照 → 免单 → 取消 | ✓ | `dsp-07-waive-confirm.png` |
| B-05 | 按调整明细落账 → 取消 | ✓ | |
| B-06 | 异常中心 | ✓ | `/exceptions?status=OPEN` · `dsp-08-exc.png` |
| B-07 | 关联订单 | ✓ | `/orders?deviceId=…` · `dsp-09-orders.png` |

## C 列表深链

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 设备 | ✓ | `/devices/330449777078` · `dsp-10-device.png` |
| C-02 | 会话 | ✓ | `/sessions?deviceId=…&sessionId=…` · `dsp-11-session.png` |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `dsp-ux-narrow.png` · vw=900 |
| D-02 | 1366 / 1920 | ✓ | `dsp-ux-1366.png` / `dsp-ux-1920.png` |
| D-03 | API 落盘 | ✓ | `dsp-api-list.json` |

## 结案

- [x] 可见可点覆盖；免单/调整均取消
- [x] 视口主测 1366 结束仍为 1366
|
