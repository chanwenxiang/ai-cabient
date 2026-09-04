# Design Tokens 收敛专项（波次 3）

> 色板 + 圆角 4 档已落到 `@aicabinet/shared-uni/theme.css`，三端引用。
> 存量页面魔法值按模块分批替换（勿一次全仓扫改）。

## 当前主色 / 语义色

| Token | 值 | 用途 |
|-------|-----|------|
| `--brand` | `#0f766e`（consumer 覆写 `#047857`） | 主色 |
| `--success` | `#16a34a` | 成功 |
| `--danger` | `#b91c1c` | 危险 |
| `--warning` | `#b45309` | 警告 |
| `--accent-orange` | `#c2410c` | 强调橙（WCAG AA） |

## 圆角 4 档

| Token | Web | 小程序（page 覆写） | 用途 |
|-------|-----|---------------------|------|
| `--radius-pill` | `999px` | `999rpx` | 胶囊按钮 |
| `--radius-card` | `24px` | `24rpx` | 卡片（`--card-radius` 指向本 token） |
| `--radius-control` | `12px` | `12rpx` | 输入/控件；admin `--admin-radius-lg` |
| `--radius-tag` | `8px` | `8rpx` | 标签；admin `--admin-radius` |

## 已落地

1. `packages/shared-uni/src/theme.css` 定义色板 + 圆角
2. admin `main.css` 引用并映射 `--admin-radius*`
3. merchant / consumer `App.vue` 在 `page` 覆写 rpx 圆角

## 后续

- 禁止页面内新增裸魔法圆角/语义色；存量按模块替换为 `var(--radius-*)` / `var(--brand)` 等

更新日期：2026-09-04
