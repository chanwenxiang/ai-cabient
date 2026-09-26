# 争议审核 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`DISPUTES_FULL_BROWSER_UAT.md`](../../../uat/DISPUTES_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

OPEN 中文「待审核」、认领、免单/调整确认取消、异常/订单/设备/会话深链、导出均实测通过。结束视口 **1366×768**。

## 数据对照

| 项 | UI | API `GET /api/v2/ops/disputes` |
|----|-----|--------------------------------|
| OPEN | 共 8 条 · 待审核 | total=8 |
| 全量 | — | total=13 |
| 样例 | 兜底识别 · 参考 ¥3.50 | `reviewCode=MOCK` · claimed 350¢ |

## 视口（用户投诉「比例又不对」）

| 现象 | 根因 | 必须怎么做 |
|------|------|------------|
| 脚本里 `setViewportSize(900)` 后 `goto`，量到仍是 1366 | 导航/多 tab 会冲掉 Playwright 视口 | **主测只用 MCP `browser_resize` 钉 1366**；窄/宽抽检：先到目标页 → resize → **立刻截图，禁止中间 goto** |

本轮：窄 900 无整页横滚；1920 已截；结案前恢复 1366。

## UAT 操作注意（非产品 FAIL）

| # | 说明 |
|---|------|
| 1 | 「列设置」popover 未关会挡住「详情」点击 → Escape 后再点。 |
| 2 | 免单须先「无录像」再「已对照」（与履约册一致）。 |
| 3 | API 路径是 `/api/v2/ops/disputes`，curl `/ops/admin/disputes` 会 404。 |
| 4 | 登录用 `/api/v2/auth/admin-password-login` + captcha；Bearer 与 cookie 会话勿混用过期 JWT。 |

## 证据

`dsp-01`…`dsp-11` · `dsp-ux-*` · `dsp-api-list.json` · `dsp-99-end`
|
