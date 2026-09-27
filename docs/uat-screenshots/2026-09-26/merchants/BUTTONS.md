# 商户与分账 · 按钮清点 · 2026-09-26

> `/merchants` · Playwright **1366×768**（DPR=1）  
> [`MERCHANTS_FULL_BROWSER_UAT.md`](../../../uat/MERCHANTS_FULL_BROWSER_UAT.md)  
> 规则：写路径一律 **确认→取消**（#200）；功能包开关即时写 → **禁止拨动**。

---

## A 组织树

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| A-01 | 打开 · 仅记账 hint | ✓ | 树 2 节点 · `mch-01-org.png` |
| A-02 | 新建商户 → 取消 | ✓ | `mch-02-new-open.png` · `mch-03-new-cancel.png` |
| A-03 | 编辑 → 取消 | ✓ | `mch-04-edit-open.png` |
| A-04 | 挂载货柜 → 取消 | ✓ | `mch-05-assign-open.png` |

## B 商户列表 / 运营配置

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| B-01 | Tab 商户列表 | ✓ | 共 2 · 功能包列 · `mch-06-list.png` |
| B-02 | 关键词无匹配 | ✓ | 共 0 · `mch-07-kw-empty.png` |
| B-03 | 重置 | ✓ | 共 2 · `mch-08-reset.png` |
| B-04 | 功能包开关×3 | ✓ 可见 · 未拨 | 10 个 switch |
| B-05 | Tab 运营配置 | ✓ | 理货/保存 · 未点保存 · `mch-09-ops.png` |

## C 分账明细

| # | 控件 | 判定 | 证据 |
|---|------|------|------|
| C-01 | Tab 分账明细 | ✓ | 共 24 · 仅记账 · `mch-10-splits.png` |
| C-02 | 确认完结 → 取消 | ✓ | 「确认仅记账完结」· `mch-12-confirm-open.png` |
| C-03 | 提交微信分账 | — SKIP | 本轮无可见「提交」 |
| C-04 | `?tab=splits` | ✓ | 深链 · `mch-14-deeplink-splits.png` |
| C-05 | 导出 / 刷新 | ✓ | `分账明细_*.csv` · `mch-15`/`mch-16` |

## D UX

| # | 项 | 判定 | 证据 |
|---|-----|------|------|
| D-01 | 900 无整页横滚 | ✓ | `mch-ux-narrow.png` |
| D-02 | 1366 / 1920 | ✓ | `mch-ux-*.png` |
| D-03 | 结束视口 1366 | ✓ | `mch-99-end.png` |

## 结案

- [x] FAIL 0；软写均取消；功能包未拨；视口结束 1366
|
