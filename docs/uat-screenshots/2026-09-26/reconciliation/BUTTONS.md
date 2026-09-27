# 对账 · 按钮清点 · 2026-09-26

> `/reconciliation` · Playwright **1366×768**（DPR=1）  
> [`RECONCILIATION_FULL_BROWSER_UAT.md`](../../../uat/RECONCILIATION_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **确认→取消**；禁止真执行对账跑批。

---

## A 头 / Alert / KPI

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 | ✓ | 共 2 · KPI · `rec-01-home.png` |
| A-02 | T+1 Alert | ✓ | 结算说明 |
| A-03 | 执行对账 | ✓ | 可见 |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 渠道选项中文 | ✓ | 微信/支付宝/其他/余额/未知 · `rec-04-channel-opts.png` |
| B-02 | 筛微信 | ✓ | 共 1 · `rec-05-channel-wechat.png` |
| B-03 | 重置 | ✓ | `rec-06-reset.png` |
| B-04 | 状态选项中文 | ✓ | 已平账/存在差异/… · `rec-07-status-opts.png` |
| B-05 | 筛存在差异 | △ FINDING | URL 同步 · 列表仍 2 · `rec-08-status-mismatch.png` |
| B-06 | 关键词无匹配 | △ FINDING | 仍共 2 · `rec-09-kw-empty.png` |

## C 写 / 详情 / 工具

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 执行对账 → 取消 | ✓ | `rec-02`/`rec-03` |
| C-02 | 详情抽屉 | ✓ | `rec-10-detail.png` |
| C-03 | 导出 CSV | ✓ | `对账_*.csv` · `rec-12-export.png` |
| C-04 | 刷新 | ✓ | `rec-13-refresh.png` |

## D 深链 / UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | `?status=MISMATCH` | △ | 筛选项「存在差异」· 列表未滤 · `rec-11-deeplink-mismatch.png` |
| D-02 | 900 无整页横滚 | ✓ | `rec-ux-narrow.png` |
| D-03 | 1366 / 1920 | ✓ | `rec-ux-*.png` |
| D-04 | 结束视口 1366 | ✓ | `rec-99-end.png` |

## 结案

- [x] 软写取消；渠道筛 OK；status/keyword FINDING
|
