# 进件工作台 · 按钮清点 · 2026-09-26

> `/merchant-onboarding` · Playwright **1366×768**（DPR=1）  
> [`MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md`](../../../uat/MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **确认→取消**；禁止真通过/驳回/落库新建。

---

## A 头 / 空态 / 工具

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开页 · 仅登记 | ✓ | 共 0 · 暂无进件记录 · `onb-01-home.png` |
| A-02 | live-hints Alert | ✓ | 仅登记 · 三渠道测试 |
| A-03 | 批量通过/驳回 | ✓ | 无勾选 disabled |
| A-04 | 导出 / 刷新 | ✓ | `onb-14-export.png` · `onb-15-refresh.png` |

## B 筛选

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | 渠道选项中文 | ✓ | 微信/支付宝/支付分 · `onb-04-channel-opts.png` |
| B-02 | 筛微信 | ✓ | 共 0 · `onb-05-channel-wechat.png` |
| B-03 | 状态选项中文 | ✓ | 草稿/已提交/已生效/已驳回 · `onb-06-status-opts.png` |
| B-04 | 筛已提交 | ✓ | 共 0 · `onb-07-status-submitted.png` |
| B-05 | 商户编号无匹配 | ✓ | 共 0 · `onb-08-merchant-empty.png` |

## C 写路径（软）

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | 新建进件 → 取消 | ✓ | `onb-02`/`onb-03` |
| C-02 | 空保存校验 | ✓ | toast「请填写商户与渠道」· `onb-12-validate.png` |
| C-03 | 行编辑 | — SKIP | 本机无行 |
| C-04 | 行通过/驳回 | — SKIP | 本机无待审行 |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `onb-ux-narrow.png` |
| D-02 | 1366 / 1920 | ✓ | `onb-ux-*.png` |
| D-03 | 结束视口 1366 | ✓ | `onb-99-end.png` |

## 结案

- [x] FAIL 0；软写取消；视口结束 1366
|
