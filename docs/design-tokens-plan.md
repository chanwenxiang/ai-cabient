# Design Tokens 收敛专项（波次 3 另立）

> 本文件为计划「设计 token 收敛」的落点说明，**不在本轮安全加固波次内改 CSS**。
> 目标：三端共用单一来源，减少圆角/色板/字号散落。

## 当前主色（已对齐）

| Token | 值 | 用途 |
|-------|-----|------|
| `--brand` / primary | `#0f766e` | 主色 |
| `--success` | `#16a34a` | 成功 |
| `--danger` | `#b91c1c` | 危险 |
| `--warning` | `#b45309` | 警告 |
| `--accent-orange` | `#c2410c` | 强调橙（WCAG AA，替代 `#ea580c`） |

## 建议圆角 4 档

| Token | 值 | 用途 |
|-------|-----|------|
| `--radius-pill` | `999px` / `999rpx` | 胶囊按钮 |
| `--radius-card` | `24px` / `24rpx` | 卡片 |
| `--radius-control` | `12px` / `12rpx` | 输入/控件 |
| `--radius-tag` | `8px` / `8rpx` | 标签 |

## 落地顺序（后续专项）

1. 在 `packages/shared-uni` 或新建 `packages/design-tokens` 输出 JSON/CSS 变量
2. admin-vue `src/styles`、consumer-mp / merchant-mp 全局 scss 引用
3. 禁止页面内新增裸魔法值；存量按模块分批替换

创建日期：2026-09-04
