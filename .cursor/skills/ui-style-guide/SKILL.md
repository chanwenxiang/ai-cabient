---
name: ui-style-guide
description: >-
  AI Cabinet 三端（admin-vue / consumer-mp / merchant-mp）UI 样式与文案规范速查。
  Trigger when 写/改任何三端页面、组件、样式、提示文案、KPI、列表加载态，
  or when reviewing UI diffs for 裸色值/提示层级/热区/文案口径 compliance.
  来源：docs/UI_STYLE_UNIFICATION_PLAN.md（2026-11-03 定稿 v2，Phase 1–4 已实施）。
---

# UI 风格指南（三端各自统一）

方案 = **自绘 + 系统化**：shared-uni 是跨端小程序设计系统（不引第三方组件库），admin 扩展 `main.css` 令牌语义层。**各端内部统一**（admin=工作台密度、consumer=C 端轻松、merchant=B 端工具感），不追求三端同长相。

## 1. 硬规则（新代码违反即返工）

| 规则                     | 说明                                                                                                                                      |
| ------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------- |
| 颜色必用令牌             | 裸 hex 棘轮**只减不增**：`pnpm check:ui-barehex-ratchet`（admin hex6/hex3/hex8/rgba、mp 同四项）。迁完一页就把降下来的数写回脚本基线      |
| `var()` 兜底色也算裸 hex | 新组件写 `var(--card-bg)`，**不要** `var(--x, #fff)`——两端 theme.css/App.vue 已保证 95 令牌全量兜底（`check:mp-css-token-fallback`）      |
| WXSS 禁 `color-mix()`    | 小程序样式里不许；`color-mix` 只允许出现在 admin `main.css`（`check:wxss-no-color-mix`）                                                  |
| 提示四层                 | 见 §3；**规则/口径解释禁止常驻横幅**（admin `check:admin-banner-ratchet` 基线 52，只减不增）                                              |
| 热区 ≥ 88rpx             | 可点元素热区 88rpx，视觉可以更小 = **热区/视觉分离**（外层 hit 容器 + `margin:-28rpx` 负边距，参考 `shared-uni/components/help-tip.vue`） |
| 数字排版                 | 金额/单号/计数一律 `font-variant-numeric: tabular-nums`；KPI 数字 mp=20px/800、admin 走 `.kpi-*` 类                                       |
| 单号全量展示             | ❌ 不做「前 6…后 4」缩略（运营核对需要，`f14a8319` 已撤销过一次）                                                                         |
| 补货措辞                 | 仓→柜=补货员**自提自送**：界面动词只写「领货 / 上柜」，禁「发运 / 签收 / 在途 / 出库」字样（发运只属于采购）                              |

## 2. 语义色板与三阶（速查）

**admin `clients/admin-vue/src/styles/main.css`**（亮/暗两套，Element 语义色已接线到 `--el-color-*`）

- `--color-success #059669` 正向/完成 ｜ `--color-warning #d97706` 待处理 ｜ `--color-danger #dc2626` 失败 ｜ `--color-info #0284c7` 中性提示
- 图表数据系列固定 `--chart-1..6`（= DONUT_PALETTE）；**KPI 强调色**：营收=`var(--app-primary)`（随主色换肤）、成本=slate、毛利=amber、率=violet、订单/客单=info（`--kpi-accent-*`）
- 三阶：间距 `--space-1..8`(4..40px)、圆角 card/control/dialog=10/8/12px、阴影 card/dropdown/modal 三档

**mp `packages/shared-uni/src/theme.css`**（一处补两端，App.vue 全局导入）

- `--color-pos`(=success #16a34a) 涨/正向金额 ｜ `--color-neg`(=danger #b91c1c) 跌/负向 ｜ `--color-warning` / `--color-info` 既有别名
- 三阶：`--gap-xs/sm/md/lg`=8/16/24/32rpx、圆角 card/control/dialog=24/16/32rpx、阴影两档（mp 阴影宜轻）

## 3. 提示文本四层 + 现成组件

| 层        | 场景                    | 用什么                                                                                                                             |
| --------- | ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------- |
| L1 常驻   | 字段名 + 值本身         | 就是内容，不额外加样式                                                                                                             |
| L2 按需   | 规则/口径解释           | admin `HelpTip.vue`（el-tooltip + 问号）；mp `shared-uni/components/help-tip.vue`（88rpx 热区 + 32rpx 圆点，点击 `uni.showModal`） |
| L3 时机性 | 空态引导 / 错误修复步骤 | `empty-state` / 文字型错误（必含下一步）                                                                                           |
| L4 兜底   | 一句话 + 跳帮助页       | 通用兜底文案「操作失败，请重试或联系管理员」                                                                                       |

- mp 提示类名**新代码只允许三类**：`hint`（行内）/ `help-tip` / `empty-hint`（空态）。`menu-desc`/`intro-desc`/`order-hint`/`filter-tip` 等是页面语义样式，刻意不批量重命名。
- 加载态两标准：列表=**骨架屏** `shared-uni/components/list-skeleton.vue`（条件 `loading && !list.length`；静默刷新/加载更多不显示骨架）；操作按钮=`app-button :loading` spinner。
- 新增 shared-uni 组件必做三件事：`packages/shared-uni/package.json` 加 export → 两端 `pages.json` easycom → `scripts/patch-mp-weixin-appjson.mjs` 的 `OPTIONAL_SHARED_COMPONENTS`（漏了 build 必红 `usingComponents root-absolute`）。

## 4. 文案口径（Phase 5 审计定稿）

- 错误必含修复步骤；后端已给具体原因时**不叠加**泛化句；内部/技术错误不外露。
- 加载类文案以「…」结尾（「加载中…」「处理中…」）；「处理中」作业务状态时不强加省略号。
- 按钮具体化：确认动作写清楚（确认激活/确认提交/通过/驳回），扫码失败 → 「请重试或手动输入柜机编号」。
- C 端设备原文 = **剥离**（`｜设备原文：` 之后不展示）；争议详情/运营后台保留全量。

## 5. 验收面（不可妥协）

- **小程序只用微信开发者工具**（mp-weixin 产物），H5 不是验收面。consumer 自动化口 9421 / merchant 9420，配方见 memory `reference-mp-automator-verify`。
- admin 用 Playwright headed Chrome，亮/暗都看；`:3100` 起不来用独立端口，**不要杀用户的 dev server**。
- admin 某页只剩「跳到主内容」≈ **vite 依赖预构建缓存过期**（动态 import 回 504 `Outdated Optimize Dep`），不是页面缺陷：让用户重启 dev server，或用 `vite --port <自有端口>` 起一份隔离的。
- 骨架屏/加载态这类瞬时状态：`mockWxMethod('request', fn)` 挂起目标 URL 可冻住画面（consumer 实例实测可用；merchant 实例可能拦不到，改看构建产物 + usingComponents）。
- 验收交付 = **只对本次实际改动页面**拍「同会话、同数据快照」前后对比；不做 130+ 全量归档，证据留 `.tmp`（gitignore）。
- 🔴 **网络变更后先重烤 mp env**：两端 `.env.development` 由 `scripts/sync-consumer-mp-api.mjs` 烤的是**当时局域网 IP**，且 `clients/*/.env.development.local` 优先级更高。IP 漂移时请求连不到网关 ⇒ 列表永远停在加载态（骨架屏/「加载中…」），极易误判成前端 bug。判据：网关 access log 里**没有**模拟器的请求。修法：`node scripts/sync-consumer-mp-api.mjs` + 手工核对 `.local`，再 `build:mp-weixin:dev` 重烤。
- 🔴 **整页无样式（大字裸排/图标巨物/tabbar 变文字）＝`dist/dev` 被交叉写坏**，不是样式回归。判据：同页 wxml 与 wxss 的 scoped 哈希必须相等——
  `grep -o 'data-v-[a-z0-9]*' dist/dev/mp-weixin/pages/home/home.wxml | sort -u` 对 `.wxss` 同样取一次；不等即坏。
  成因：另一会话的 `uni.js -p mp-weixin` 增量 watcher 与你的全量 `build:mp-weixin:dev` 交叉写 `dist/dev`。
  处置顺序：① 先查 `clients/*/src` 近 30 分钟是否有人改（有 ⇒ watcher 是在用的，**禁止杀**）；② 把开发者工具指到 `dist/build/mp-weixin`（watcher 不写它，通常内部一致）继续验收；③ 只有确认 watcher 是残留的才按旧配方杀进程 + 删两个 dist + 全量重烤 + 清 `WeappCompileCache`。

## 6. 门禁命令（改 UI 后自查）

```bash
pnpm check:ui-barehex-ratchet        # 裸色棘轮（只减不增）
pnpm check:admin-banner-ratchet      # admin 常驻 el-alert 棘轮（基线 52）
node scripts/check-mp-a11y-coverage.mjs      # mp role/aria 覆盖（阈值 95%）
node scripts/check-mp-css-token-fallback.mjs # 两端 page 块 95 令牌兜底
node scripts/check-wxss-no-color-mix.mjs     # WXSS 无 color-mix
node scripts/sync-shared-uni-components.mjs --check  # shared 组件无本地副本
node scripts/pre-push-ci-preflight.mjs       # 推送前全链（规则 pre-push-ci-green）
node scripts/ui-screenshot-runner.mjs --surface=all   # 三端截图+健康检查（本地用，不进 CI；见 §5 前置）
```

- 截图 runner 判读：`FAIL`=白屏/console error/骨架屏 7s 未复位（真回归，必须修）；`CHANGED`=**像素差异占比 > 0.2%**（数据变化也会触发，交人工看 `current/` vs `baseline/`）；`--promote` 把当前一轮认作新基线；`--selftest` 验判据本身（同图必须 0%、两页互比必须显著，防「永远 SAME」假绿）。页面清单在 `scripts/ui-screenshot-pages.json`（加页面改 JSON，勿硬编码）。

## 7. 已拍板禁区（勿顺手改）

单号缩略 ❌ ｜ 设备原文折叠 ❌（是剥离） ｜ KPI 营收写死颜色 ❌（跟 `--app-primary`） ｜ 全量基线图集 ❌ ｜ 存量颜色一次性 codemod ❌（碰到就迁） ｜ devices 页「常驻柜」筛选 ❌ 已按用户拍板删除（保留设备行 ★ 与「清除常驻」） ｜ mp emoji 当图标 = 已知混用（41 文件），未拍板前不新增不批量改。
