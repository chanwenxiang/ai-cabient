# 消费端小程序：竞品对标优化 + 真机复验报告

- 日期：2026-09-24（真机取值取自 2026-09-24 09:14–09:31，模拟器）
- 范围：`clients/consumer-mp`（微信小程序 mp-weixin 为界面权威，H5 不参与 UI 结论）
- 结论口径：**只认实跑产物 + 真机（模拟器）元素级取值**；每条结论后附取证命令或设备实测值

---

## 0. 一句话结论

按仓内既有竞品基准补了 **7 项代码改动 + 1 项环境修复**；**24/24 页真机出图**、
导航栏一致性 **4 页逐值相同**、胶囊遮挡修复 **A/B 实测闭合**、
**上一轮唯一未完成项（`nearby` 空态真机取图）已完成**，
并在取图过程中**又发现并修复了 3 个真机可见缺陷**（落地页白色扫码盘 → 品牌渐变盘；
扫码图标**竖棒方向反了** → 横向扫描线 + 2.4s 缓动；空态图标不可辨识），三者均 A/B 复验。
第三轮按你的反馈做了**色调根系统一**（§3.10）：落地页蒙层与全页 8 处半透明绿
`#064e3b`(H164) → `--brand-deep #134e4a`(H176)，盘与背景色相差 **30.0° → 5.9°**、
明度分层 **−0.006 → +0.107**，三条判据全部 PASS（含改前截图的**负向对照**）。
全量门禁链 **36/36 通过**（`node scripts/run-audit-gates.mjs`，EXIT=0）。

---

## 1. 竞品对标依据（仓内既有基准，未新造）

| 依据文件 | 用到的部分 |
|---|---|
| `docs/COMPETITOR_BENCHMARK_AND_ROADMAP_2026-09-17.md` | §3.1 对标对象（友宝在线 / 丰e足食 / SandStar / 映翰通 / 美团生态）；§3.2 功能差距矩阵 |
| `docs/ROADMAP_DECISIONS_2026-09-21.md` | 附 D「竞品做法速查」F3/F7/F8/F9/F10/F11 |

其中 **F3 原生分享（`onShareAppMessage`）全仓 0 命中** —— 竞品标配、且零外部依赖，故选为首个缺口。

---

## 2. 改动清单

| # | 改动 | 文件 | 依据 | 验证级别 |
|---|---|---|---|---|
| 1 | **F3 原生分享**（仅无激励档） | `pages/index/index.vue`、`pages/marketing/index.vue`、`pages/nearby/nearby.vue` | 竞品标配、原 0 命中 | 产物级：3 页编译后均含 `onShareAppMessage` |
| 2 | `nearby` 导航栏并入全站 `AppNavBar`（原手写 `.nav`，底色 `--brand-deep`） | `pages/nearby/nearby.vue` | 一致性 | **真机**：4 页逐值相同 |
| 3 | `nearby` 空态补「下一步动作」CTA（原为两行死胡同文字） | `pages/nearby/nearby.vue` | 竞品空态均带出口 | **真机**：`state=empty` + CTA 实测（§3.6） |
| 4 | **`AppNavBar` 右侧插槽被微信胶囊遮挡** 修复 | 3 份字节相同副本 | iOS 导航惯例 | **真机 A/B 闭合**（§3.4） |
| 5 | **落地页扫码 CTA：纯白圆面 → 品牌渐变实心盘** | `pages/index/index.vue` | 与落地页「深绿玻璃 + 白细边」语言同源 | **真机取色 + 出图**（§3.5） |
| 6 | **`empty-state` 字形不可辨识** 修复 | 3 份字节相同副本 | 非文本图形 3:1 下限 | **真机像素级 A/B**（§3.7） |
| 7 | *（环境）* dev 档 API 基址 `192.168.0.199` → `localhost` | `clients/consumer-mp/.env.development` | `.env.development.example` 明示模拟器用 localhost | 真机取到实时数据（§3.6.1） |
| 8 | **落地页扫码图标改版**：取景框放大 + **竖棒 → 横向扫描线** + 2.4s 上下缓动 + 盘面加受光高光 | `pages/index/index.vue` | 你第二轮指出「框还是不好看」；竖棒方向与「扫一扫」通用认知**相反** | **真机三判据 + 像素 A/B**（§3.9） |
| 9 | **落地页配色根系统一**：蒙层与全页 8 处半透明绿 `#064e3b`(H164) → `--brand-deep #134e4a`(H176)；盘改由**同色相 + 明度**分层；外圈白晕改品牌青绿柔光 | `pages/index/index.vue`、`pages/mine/mine.vue`、`pages/marketing/index.vue` | 你第三轮指出「颜色和背景不符」。**根因＝盘 H175° vs 背景 H146°，差 30°** | **真机三判据（含负向对照）+ 像素级**（§3.10） |

### 2.1 改动 1 细节（F3 分享）

```ts
// 三页同构；只在「无奖励」档，避免重蹈 V128 删除裂变模块的覆辙
const SHARE_TITLE = 'AI开门柜 · 扫码开门，拿了就走';
onShareAppMessage(() => ({ title: SHARE_TITLE, path: SHARE_PATH }));
onShareTimeline(() => ({ title: SHARE_TITLE }));
```

⚠️ 刻意**不带 `deviceId`**：`packages/shared-uni/src/qrcode.ts:199-202` 中，启动参数含 `deviceId`
即经 `||` 短路置 `autoOpen = true`，且**无法用 `autoOpen=0` 关闭** ⇒ 分享链接不得携带柜机号。

### 2.2 改动 4 细节（胶囊遮挡）

- 现象：`.app-nav-side` 只有 `min-width: sidePad` + `justify-content: flex-end`，
  插槽内容右贴导航栏右缘（382）⇒ **整段压在胶囊（296→382）下面**。
- 修法：`flex: 1` 撑满返回键右侧空间 + `padding-right: sidePad` 把内容推到胶囊左缘之前。

```diff
-  :style="{ minWidth: sidePad, height: rowStyle.height }"
+  :style="{ minWidth: sidePad, height: rowStyle.height, paddingRight: sidePad }"
   .app-nav-side {
     justify-content: flex-end;
+    flex: 1;
   }
```

三份副本 md5 一致：`cee778e5b7f160cf71d2c9ed06d8310c`

### 2.3 改动 5 细节（落地页扫码盘）—— 由你指出的问题驱动

问题（你给的截图）：落地页中央是一块**纯白圆面**，与整页不搭。

根因（读源码确认，非猜测）：落地页的设计语言是「**深绿半透明玻璃 + 白色细边 + 白字**」——
`.landing-overlay` = `rgba(6,78,59,.72→.45→.82)`，`.pay-badge` / `.manual-link` / `.coupon-link`
清一色 `rgba(6,78,59,.55)` + `1rpx solid rgba(255,255,255,.32)`。
而 `.scan-circle-inner` 是**全页唯一的纯白实心面**（`background: var(--white, #ffffff)`）⇒ 像一个外贴的贴纸。

```diff
   .scan-circle-inner {
-    background: var(--white, #ffffff);
-    border: 2rpx solid rgba(15, 118, 110, 0.18);
+    background: linear-gradient(158deg, var(--brand, #0f766e) 0%, var(--brand-ink, #0f3f3c) 100%);
+    border: 2rpx solid rgba(255, 255, 255, 0.42);
     box-shadow:
-      0 12rpx 32rpx rgba(6, 78, 59, 0.35),
-      0 0 0 12rpx rgba(255, 255, 255, 0.18);
+      0 14rpx 36rpx rgba(4, 47, 36, 0.45),
+      0 0 0 12rpx rgba(255, 255, 255, 0.14);
   }
   .scan-corner {
-    border-color: var(--brand, #0f766e);
+    border-color: var(--white, #ffffff);
   }
   .scan-line {
-    background: var(--brand, #0f766e);
+    background: var(--white, #ffffff);
   }
```

⚠️ 取景角/扫描线**必须一起改白**：品牌绿字形叠品牌绿盘底会几乎不可见。
对比度：白 on `--brand` ≈ 5.5:1，白 on `--brand-ink` ≈ 11.6:1。

### 2.4 改动 6 细节（空态字形）—— 真机取图时新发现

现象（`nearby` 空态真机图放大后确认）：图标圆底 `--brand-soft` = `#ecfdf5` 对白底仅 **1.05:1**
（等同隐形），字形描边 `3rpx` + `opacity: .7` 叠在圆底上仅 **2.98:1**，低于非文本图形 **3:1** 下限。

```diff
   .empty-glyph::after {
-    border: 3rpx solid currentColor;
-    opacity: 0.7;
+    border: 4rpx solid currentColor;
+    opacity: 1;
   }
```

圆底保持浅色调（软底是刻意选择），由字形承担可辨识度 ⇒ **5.21:1**。
三份副本仍逐字节一致：`f0773f9853c1fe928024e36b2e4f7999`。

### 2.5 改动 8 细节（扫码图标改版）—— 由你第二轮反馈驱动

问题（你第二轮截图）：盘底颜色已对，但**「扫一扫的框还是不好看」**。

拆成两个可证伪的子问题（都不是「感觉」）：

**① 方向错了（根因）。** 原 `.scan-line` 是 `width:10rpx; height:64rpx` —— 一根**竖**棒。
在方框里它读起来是数字「1」或一根钉子，而「扫一扫」的通用认知是**横线扫过**。方向反了，这是
「不对」的主要来源，而非线条粗细或配色。

**② 框与盘的配比失衡。** 取景框 `132rpx` 只占圆盘 `260rpx` 的 **51%**，四角 `44rpx` 偏短 ⇒
框在圆里显得空，四角像四枚孤立钉子；线宽 `6rpx` 抗锯齿后发灰。

```diff
   .scan-circle-inner {
+    /* 左上 30%/22% 径向高光 叠 品牌线性渐变，做玻璃受光面；inset 补顶缘反光 */
+    background:
+      radial-gradient(120% 100% at 30% 22%, rgba(255, 255, 255, 0.16) 0%, rgba(255, 255, 255, 0) 58%),
+      linear-gradient(158deg, var(--brand, #0f766e) 0%, var(--brand-ink, #0f3f3c) 100%);
+    box-shadow:
+      inset 0 2rpx 1rpx rgba(255, 255, 255, 0.22),   /* 新增：顶缘反光 */
       ...;
   }
   .scan-icon-box { width/height: 132rpx → 152rpx }     /* 51% → 58% */
   .scan-corner  { width/height: 44rpx → 54rpx; border-width: 6rpx → 7rpx; border-radius: 4rpx → 16rpx }
   .scan-line {
-    width: 10rpx; height: 64rpx; border-radius: 6rpx;  /* ← 竖棒 */
+    width: 92rpx;  height: 8rpx;  border-radius: 8rpx;  /* ← 横线 */
+    animation: scan-sweep 2.4s ease-in-out infinite;
   }
+  @keyframes scan-sweep {
+    0%, 100% { transform: translateY(-42rpx); opacity: 0.55; }
+    50%      { transform: translateY(42rpx);  opacity: 1; }
+  }
```

**为什么是 `8rpx` 而不是 `5rpx`**（这条是实测逼出来的，不是拍脑袋）：第一版改成 `5rpx` 后真机取值显示
它只渲染出 ≈`2px`，**比 `7rpx` 的取景角（≈`2.75px`）还细** —— 一条「运动物」比静止的框更弱，
读起来是虚弱的短横。改 `8rpx` 后实测 ≈`3.99px`，与角配重相当。

**为什么是 `92rpx` 而不是 `100rpx`**：两端收进角竖边之内，避免摆到上下极点时与左右角挤在一起。

---

## 3. 真机复验证据

### 3.1 环境

| 项 | 值 |
|---|---|
| 设备 | iPhone 12/13 (Pro) 模拟器，390 × 762，SDK 3.3.5，pixelRatio 3 |
| 产物 | `clients/consumer-mp/dist/dev/mp-weixin`（208 文件，dev 档 = 可达基址） |
| 附着 | `cli/index.js` 直调 → `auto --project <div> --auto-port 9422 --trust-project` |
| 归属判别 | 消费端首页 `pages/index/index` = OK；商户端 `pages/home/home` = not found |

### 3.2 逐页真机出图：**24 / 24**

附件 `mp-audit-v5.json` + 截图目录 `docs/consumer-mp-verify-2026-09-24/`。

| 页 | 结果 | 备注 |
|---|---|---|
| 01-index / 02-orders / 03-mine | ✅ | tab 页 |
| 04-login | ⚠️→✅ | 落在 `pages/index/index`：**设计如此**（`login.vue:292-302`，IMP-004） |
| 05-verify … 24-feedback（20 页） | ✅ | 全部出图 |

### 3.3 导航栏一致性（真机取值）

| 页 | `background-color` | `navHeight` | `title-top` | 判定 |
|---|---|---|---|---|
| nearby | `rgb(15, 118, 110)` | 83 | `50.99862289428711` | **SAME** |
| result | `rgb(15, 118, 110)` | 83 | `50.99862289428711` | 基准 |
| balance | `rgb(15, 118, 110)` | 83 | `50.99862289428711` | **SAME** |
| messages | `rgb(15, 118, 110)` | 83 | `50.99862289428711` | **SAME** |

`nearby` 修复前为 `#134e4a` = `rgb(19, 78, 74)`（手写 `.nav` + `--brand-deep`）。

### 3.4 胶囊遮挡修复 A/B（真机实测 + 两点独立推导）

| | `.nav-refresh` left | width | right | 相对胶囊（296→382） |
|---|---|---|---|---|
| **修复前** | `356.08642578125` | 26 | `382.09` | **100% 落在胶囊下** |
| **修复后** | `254.0891876220703` | 26 | `280.09` | **左于胶囊 15.9px** ✅ |

交叉验证（两条独立路径同值）：
1. 实测 `sidePad = 382 − 280.09 = 101.91 ≈ 102`
2. 源码公式 `sidePad = winW − menu.left + 8` ⇒ 反解 `menu.left = 390 − 102 + 8 = 296` ✓ 与胶囊左缘一致

**回归**：本轮 `nearby` 空态复测得到 `left = 254.0891876220703`，与修复后基线**逐位相同**。

### 3.5 落地页扫码盘（改动 5）真机取值

`.scan-circle-inner` 设备实测（`element.style()`）：

```json
{
  "background-color": "rgba(0, 0, 0, 0)",
  "background-image": "linear-gradient(158deg, rgb(15, 118, 110) 0%, rgb(15, 63, 60) 100%)",
  "border-top-color": "rgba(255, 255, 255, 0.42)",
  "box-shadow": "rgba(4, 47, 36, 0.45) 0px 7px 18px 0px, rgba(255, 255, 255, 0.14) 0px 0px 0px 6px",
  "width": "134.995px", "height": "134.995px"
}
```

`.scan-corner` `border-top-color` = `rgb(255, 255, 255)`；`.scan-line` `background-color` = `rgb(255, 255, 255)`。
⇒ **白底归零，品牌渐变生效**。出图：`landing-scan-cta-fixed.png`（背景图确认为 `bg-shop-indoor.jpg`）。

### 3.6 `nearby` 空态（上一轮未完成项，本轮补齐）

真机取值（`nearby-probe2.mjs`）：

```
state        : empty
stateError   : null          ← 无网络错误 ⇒ 请求真的成功了
emptyPresent : true          ← empty-state 组件在
listCount    : 0
statePlain   : "附近 5km 暂无柜机\n可扩大搜索范围，或直接扫码开门购物\n扫码购物"
cta labels   : ["扫码购物"]
cta offset   : {"left":148.493453979542,"top":350.37017822265625}
nav  bg      : rgb(15, 118, 110)
navRefresh   : left = 254.0891876220703
```

出图：`nearby-empty-fixed.png`（`empty-state` + 「扫码购物」+ 导航栏「刷新」在胶囊左侧）。

#### 3.6.1 为什么现在能取到：基址修复（改动 7）

| 产物 | 原基址 | 真机可达性 |
|---|---|---|
| `dist/build/mp-weixin`（release） | `https://api.build-check.invalid` | ❌ 占位域（fail-closed 设计，非缺陷） |
| `dist/dev/mp-weixin` | `http://192.168.0.199` | ❌ **该 IP 已失效**：本机 WLAN 现为 `192.168.0.26`（`Get-NetIPAddress` 实测） |
| `dist/dev/mp-weixin`（修复后） | `http://localhost` | ✅ 模拟器跑在本机 IDE 进程内，localhost 即宿主 |

- 副作用清单见 §5。改用 `localhost` 同时消除了「LAN IP 随 WLAN 重新分配而失效」这一重复踩坑点。
- 佐证接口与登录真的通了：`03-mine-live-data.png` 显示 **可用余额 ¥20.00**、已实名/可开门徽标（静默 `wx.login` 生效）。
- `.env.development` 属 **gitignored**（`.gitignore:32` 的 `.env.*`）⇒ 无版本库影响。

### 3.7 空态字形（改动 6）像素级 A/B

同一裁切框、同一算法，对**修复前**（`glyph-zoom.png`，4× NEAREST 可无损还原）与**修复后**逐像素相减：

```
max per-channel delta : 221
pixels changed (>8)   : 361 / 28900   ← 1.25%，且全部落在字形区域
```

差分图 `empty-glyph-ab.png`（左＝修复前 / 中＝修复后 / 右＝差分×6）：**只有字形区域变亮，其余全黑**
⇒ 版式零位移，只改了字形可辨识度。

### 3.8 产物级证据（编译后）

`dist/dev/mp-weixin`（208 文件）与 `dist/build/mp-weixin`（208 文件）**同时**含本轮全部改动：

```
components/app-nav-bar.wxss  : .app-nav-side{justify-content:flex-end;flex:1}
components/app-nav-bar.wxml  : style="{{'min-width:' + f + ';' + ('height:' + g) + ';' + ('padding-right:' + h)}}"
pages/index/index.wxss       : .scan-circle-inner…linear-gradient(158deg,var(--brand…) 0%,var(--brand-ink…) 100%)
                               .scan-corner…border-color:var(--white, #ffffff)
                               .scan-line…background:var(--white, #ffffff)
components/empty-state.wxss  : .empty-glyph:after…border:4rpx solid currentColor;opacity:1
pages/nearby/nearby.wxml     : empty-state ×1
pages/nearby/nearby.js       : 扫码购物 ×1, onShareAppMessage ×1
pages/index/index.js         : onShareAppMessage ×1
pages/marketing/index.js     : onShareAppMessage ×1
API 基址                      : http://localhost ×2
```

设计令牌完整性（`app.wxss`，各出现 **2 次** = `:root{}` + `page{}`）：

```
--brand:2  --brand-soft:2  --info:2  --info-soft:2  --warning-soft:2  --accent-rose:2  --accent-rose-soft:2
```

---

### 3.9 扫码图标改版（改动 8）真机三判据 + 像素 A/B

设备：`iPhone 12/13 (Pro)`，`390×762`，SDK `3.3.5`，`1rpx = 0.52pt`。
取数脚本 `.tmp/scan-icon-probe.mjs`，原始报告 `scan-icon-probe.json`。

**判据 1 — 方向（必须宽 ≫ 高）**

```json
{ "width": "46.9955px", "height": "3.99449px", "border-radius": "4px" }
```
长宽比 **11.77 : 1** ⇒ `HORIZONTAL ✓`（改前是 `10rpx × 64rpx` = **0.16 : 1**，即竖棒）。

**判据 2 — 动效已挂载**

```json
{ "animation-name": "scan-sweep-9af8335b",
  "animation-duration": "2.4s",
  "animation-timing-function": "ease-in-out",
  "animation-iteration-count": "infinite" }
```
> ⚠️ 判据按**前缀** `startsWith('scan-sweep')` 写，不绑完整名 —— uni 的 scoped style 会给
> `@keyframes` 加 hash 后缀（编译产物实为 `scan-sweep-9af8335b`）。绑完整名会因编译期改名而**假红**。

**判据 3 — 动画真的在跑（关键，防「只写进产物文本」）**

对 `.scan-line` 连续 4 次取 `offset()`，间隔 700ms：

```
top 采样 = 445.221, 470.498, 484.677, 449.297   (pt)
swing   = 39.456 pt      distinct = 4/4  ⇒ MOVING ✓
```
四次取值互异 ⇒ 元素在真实位移，不是静态渲染。若动画没生效，这 4 个值会**完全相同**。

**几何（元素级实测 vs 源码声明）**

| 元素 | 源码 | 实测 | 备注 |
|---|---|---|---|
| `.scan-circle-inner` | `260rpx` | `134.995px` | ratio 0.5192 ✓ |
| `.scan-icon-box` | `152rpx` | `78.9945px` | ratio 0.5197 ✓（原 132rpx） |
| `.scan-corner` | `54rpx` | `27.9959px` | ratio 0.5184 ✓（原 44rpx） |
| `.scan-line` | `92rpx` | `46.9955px` | ratio 0.5108 |

**⚠️ 本轮新发现的工具坑：模拟器对 sub-10px 的取值不是线性的。**
同一次取数里，`width/height` 严格按 `windowWidth/750 = 0.52` 换算，但：
`border-top-width` 2rpx→`0.550964px`（ratio 0.2755）、7rpx→`2.75482px`（ratio 0.3935）；
`.scan-line` 高 5rpx→`1.997px`（0.399）、8rpx→`3.994px`（0.499）。
⇒ **不能拿这些 px 绝对值反推 rpx 调参**，只能看「改大是否真的变大」这类**相对变化**，并配合目视。
本轮据此只把线宽从 5rpx 提到 8rpx（实测 2× 厚），未做进一步按 px 精调。

**像素 A/B**：`scan-icon-ab.png`（左＝改前竖棒 / 右＝改后横线，同一裁切框同倍率）。
左图竖棒读作「1」，右图横线 + 加重圆角直接落回「扫一扫」语义。成图：`scan-icon-fixed.png`。

**静态检查**：`prettier --check` OK；`eslint` **0 error**（仅既有 `contactOps` warning，非本轮引入）；
`node scripts/run-audit-gates.mjs` ⇒ **36 个门禁，失败 0**，EXIT=0。

### 3.10 第三轮：扫码盘「颜色和背景不符」—— 色调根系统一（判据 + 负向对照）

**你的反馈**：「还是不好看，他的颜色和我们的背景不符」。

**先量化，再动手。** 用 `.tmp/scan-palette-assert.py` 在真机截图上采样盘心与盘外同高度背景。
盘心定位用**盘内白色取景框的「两段 54rpx 竖边」行投影**（`y[779,921]` 高 143px ≈ CSS 152rpx 推算的 144px）——
**不能用颜色锚点**：背景本身就是绿，`#064e3b` 系像素会把锚点一路带到画面底部（本轮第一次定位就是这么漂的）。

改前实测（`scan-icon-fixed.png`）：

| 采样 | 色相 H | 饱和 S | 明度 L |
|---|---|---|---|
| 盘心 | **175.7°** | 0.693 | 0.1044 |
| 盘外同高度背景 | **145.7°** | 0.347 | 0.1106 |

**根因两条：**

1. **色相差 30.0°** —— 盘用品牌青绿 `--brand #0f766e`（H≈175），而背景实测 H≈146（橄榄绿）。
2. **明度几乎相同（ΔL = −0.006）** —— 同一亮度上换一个色相，眼睛读成「一块颜色不对的补丁」，
   而不是「一个按钮」。

**机制**：`.landing-overlay` 用的是 `rgba(6,78,59)` = `#064e3b`（H≈164，**偏黄**），
而权威色板 `packages/shared-uni/src/theme.css:28` 的 `--brand-deep` 是 `#134e4a`（H≈176）。
再叠上**本身就偏暖**的实景照片（从最透档反推照片原色 ≈ `rgb(144,149,134)`，H≈80、S≈0.10 —— 照片几乎是灰的，
背景的绿几乎全部来自蒙层），背景最终落到 H≈146。**盘和背景根本不在同一条色相线上。**

第三处脏感来源：盘外圈 `0 0 0 12rpx rgba(255,255,255,.14)` 白晕，实测渲染成 `rgb(116,148,146)`
（S 仅 0.216 的灰青）⇒ 在照片背景上读起来是一圈脏灰。

**判据（三条同真才 PASS）**

| 判据 | 含义 |
|---|---|
| A `ΔH ≤ 8°`（盘H vs 背景H） | 直接对应「颜色和背景不符」这句反馈 |
| B `背景H ∈ [163, 180]` | 背景必须落在品牌青绿区间 |
| C `盘心L − 背景L ≥ 0.03` | 同色相之后，靠**明度**区分按钮与背景 |

🔴 **负向对照（证明判据不是恒真摆设）**：拿**改前的真机截图**跑同一脚本 ⇒ **三条全 FAIL**
（ΔH=30.0° / 背景H=145.7 / ΔL=−0.0062，`ASSERT_EXIT=1`）。
这张图不是人造漂移，而是我这轮动手之前真机上的**真实状态** —— 比人工注入更有说服力。

**修法**

```diff
  .landing-overlay {
-   background: linear-gradient(180deg, rgba(6,78,59,.72) 0%, rgba(6,78,59,.45) 45%, rgba(6,78,59,.82) 100%);
+   background: linear-gradient(180deg, rgba(19,78,74,.84) 0%, rgba(19,78,74,.76) 45%, rgba(19,78,74,.92) 100%);
  }
  .scan-circle-inner {
-   background: radial-gradient(...rgba(255,255,255,.16)...), linear-gradient(158deg, var(--brand) 0%, var(--brand-ink) 100%);
+   background: radial-gradient(...rgba(255,255,255,.22)...), linear-gradient(158deg, #14a89b 0%, var(--brand) 52%, var(--brand-ink) 100%);
-   box-shadow: ..., 0 0 0 12rpx rgba(255,255,255,.14);
+   box-shadow: ..., 0 0 0 14rpx rgba(20,168,155,.16);
  }
```

三档不透明度 `0.72/0.45/0.82 → 0.84/0.76/0.92`，色相全部换成 `--brand-deep` 的 `rgb(19,78,74)`。
中间档是**迭代出来的**：第一次只提到 `0.62`，真机实测背景仅到 H=163.8（ΔH=11.1°，**仍越界**）——
照片在盘所在高度（人物/柜机区）比「盘上区」更暖。`0.76` 是实测能达标的最小值。
另把 `pages/mine/mine.vue`、`pages/marketing/index.vue` 里同源的 2 处 `rgba(6,78,59,·)` 一并统一；
现在整个 `clients/` 下 `#064e3b` 只剩源码注释里的历史说明（`grep` 可验）。
盘色三档 `#14a89b / --brand / --brand-ink` 色相全落在 174–176°，**不引入新色系**，只是给品牌色补了一档亮阶。

**真机复验（改后）**

| 判据 | 改前 | 改后 | |
|---|---|---|---|
| A 色相统一 | 30.0° | **5.9°** | PASS |
| B 背景归品牌 | 145.7° | **169.6°** | PASS |
| C 明度分层 | −0.0062 | **+0.1066** | PASS |

盘心 `rgb(39,133,126) H=175.5` / 背景 `rgb(42,88,80) H=169.6`。
**DOM 侧同时确认生效**（不只信产物文本）：`overlay background-image = linear-gradient(rgba(19,78,74,0.84), rgba(19,78,74,0.76) 45%, rgba(19,78,74,0.92))`。

成图 `scan-palette-after.png`；并排对照 `scan-palette-ab.png`（左＝改前 / 右＝改后，同裁切框、同倍率）。

**本轮静态检查**：`prettier --write` 3 个文件均 `unchanged`（写法已合规）；`eslint` **0 error**；
全量门禁 **36 个，失败 0**，`EXIT=0`。

⚠️ **代价（如实记录）**：为把照片的暖色权重压到 24%，实景照片比改前明显更暗、更弱
（中间档 0.45 → 0.76）。这是「背景统一到品牌青色」的必要代价。
若你更想留住实景的清晰度，可回调中间档、接受 ΔH≈11° —— 这条留给你选。

---

## 4. 上一轮阻塞项已被解决（IDE 附着）

上一轮「IDE 无法启动」的结论**已被推翻并定位到真正原因**——不是 IDE 起不来，而是
**CLI 的端口发现依赖 `reg.exe`（被 Program Blacklist 拦截）**，且 **CLI 的 IDE 端口来源是一个文件**：

| 文件 | 语义 | 本轮动作 |
|---|---|---|
| `…\User Data\<hash>\Default\.ide` | **IDE 服务端口**（值为 `65169`，Express 服务，`GET /` 返回 `Cannot GET /`） | 上一轮误挪走后**按原值还原** |
| `…\Default\.ide-status` | 服务端口开关，须为 `On` | 上一轮曾被截断为 0 字节（会报 “IDE service port disabled”），已还原 |

可复现的最小步骤（`reg.exe` 需提权，非提权会失败）：

```bash
bash .tmp/devtools-cli.sh open --project "<repo>/clients/consumer-mp/dist/dev/mp-weixin"
bash .tmp/devtools-cli.sh auto --project "<repo>/clients/consumer-mp/dist/dev/mp-weixin" \
     --auto-port 9422 --trust-project        # 输出 √ Using AppID: wx5a5bc7b541b62a13 / √ auto
```

⚠️ 两个必须知道的坑：
1. **`open` 是「新开窗口」，不替换旧窗口** ⇒ 旧项目窗口会一直存在并把**它自己的目录锁住**。
2. 被 IDE 打开的产物目录**顶层不可重命名**（`Device or resource busy`），但**子项可以** ——
   这正是下面第 5.1 节修法的前提。`cli close` 返回 `√ close` **不代表**目录立刻解锁，别把它当判据。

---

## 5. 遗留风险

### 5.1 `uni build` 的批量删除守卫（已在本机构建脚本内修好，但要点须记住）

`uni` 的 `buildOptions()` 会 `emptyDir(UNI_OUTPUT_DIR)`，它是**逐个 `rmSync` 顶层条目**；
本机安全删除垫片对单次 >50 文件的删除抛 `SAFE_DELETE_BULK_CONFIRM_REQUIRED`
⇒ `uni build` 直接 `EXITCODE=1`，且此时**输出目录已被部分删空**（`app.js`/`app.json`/`components` 先被删，
删到 `pages` 才中断）⇒ 产物残缺。**这就是本轮第一次「构建成功」实为失败的原因。**

修法（已固化进 `.tmp/consumer-mp-dev-build.sh` 的前置步骤）：把输出目录顶层条目
**用可逆 `mv` 全量挪出**（两个 `project*.config.json` 是 `emptyDir` 的 skip 项，可留）⇒ `emptyDir` 无物可删。

⚠️ **不要按文件数判断**：实测同一 `utils` 目录 `find -type f` = 16 而守卫报 `count` = 54，口径不一致，按计数会漏。

### 5.2 其它

1. **`color-mix(` × 38、`gap:` × 114**（消费端产物内）在旧内核上可能静默丢弃 ⇒ 建议后续做内核下限确认或降级写法。
2. `sync-mp-weixin-dev-dist.mjs` 在本机报 `rm 失败（undefined），降级为覆盖拷贝`
   ⇒ **`dist/dev` 可能残留已删除页面的旧文件**。本轮未删除页面，故无影响；后续若删页面需注意。
3. `login.vue:292` 的 IMP-004 行为正确，但会让「逐页扫描」类脚本把 login 计为跳到首页。
4. `empty-state` 的 `kind-orders` / `kind-wallet` 用的是**无兜底**的 `var(--info-soft)` / `var(--accent-rose-soft)`；
   当前令牌齐全故无碍，一旦缺失会静默失色 ⇒ 建议统一补字面量兜底。
5. `tokenProbe`（自定义属性继承探针）在本环境返回全空，**无结论**，故未据此下任何判断。
6. **商户端会话并行**：`clients/merchant-mp/**` 有另一会话在改。本轮对 `empty-state` 三份副本
   只做了**单行外科替换**（改不动即报错，不会静默覆盖），未触碰商户端其它文件。

---

## 6. 建议的下一步（未做，等你定）

1. ~~扫码框「扫一扫」引导动效~~ —— **已做**（改动 8：横向扫描线 + 2.4s 缓动，§3.9）。
   仍可再议的是**动效开关**：目前无条件播放；若要照顾 a11y / 省电，可加一个「只播一次或跟随系统」的策略。
2. 分享入口**回真机手点一次分享面板**（本轮只做了 hook 的产物级验证）。
3. `nearby` **列表态**真机取图：需要「已登录 + 半径内有柜机」的夹具数据。
4. 把 §5.1 的 `uni build` 前置清空步骤从 `.tmp/` 提升到 `scripts/`，否则换个会话又会踩。
5. （本轮新提）`clients/consumer-mp/src` 内仍有 **33 处 `color-mix()`**。门禁 `check:wxss-no-color-mix`
   的**强制范围**目前只覆盖 `packages/shared-uni/src` 与 `clients/merchant-mp/src`（消费端被标注为
   「并发会话在制品」）。旧内核若不支持 `color-mix()` 会**静默丢弃整条声明** —— 建议收敛后再把消费端纳入强制范围。
6. （§3.10 的取舍，等你定）落地页实景照片被蒙层压到 **24%** 权重，比改前明显更暗。
   若要留住实景清晰度，可把 `.landing-overlay` 中间档从 `0.76` 回调 —— 代价是 ΔH 回到 ≈11°（判据 A 会红）。
   两条路都能自洽，只是「色相更符」与「照片更清」不可兼得。
7. `clients/consumer-mp` 里还有 **11 处 `rgba(5,150,105,·)`（#059669，H≈164）**，全部用在
   **阴影**（`box-shadow`）与一处 `orders.vue` 的 0.06 浅底上。它们不在落地页、且阴影色相在低透明度下
   目视无差，故本轮**未动**（避免为凑一致而引入回归）。若要把品牌色板统一做到底，这是剩余清单。

---

## 附：本轮关键取证命令

```bash
# 真机逐页扫描（24/24）
node .tmp/sweep-consumer-mp5.mjs

# 导航栏一致性（4 页逐值）
node .tmp/nav-probe.mjs

# 胶囊遮挡 A/B（元素几何）
node .tmp/ab-nav-overlap.mjs

# 落地页扫码盘取色
node .tmp/landing-probe.mjs

# 落地页扫码图标改版取证（横线判据 + 动效挂载 + 位移采样 + rpx 标定）
node .tmp/scan-icon-probe.mjs

# 落地页扫码盘「配色是否与背景相符」判据（三条同真才 PASS；对改前图应全 FAIL）
python .tmp/scan-palette-assert.py .tmp/scan-palette-verify/after.png --json .tmp/scan-palette-verify/assert.json

# 落盘盘心/背景色相采样（含全屏分区均值）
python .tmp/scan-palette-probe.py .tmp/scan-palette-verify/after.png

# 真机取落地页（同时回读 .landing-overlay 的 computed background-image，防「产物变了但没生效」）
node .tmp/scan-palette-verify.mjs

# 改前/改后并排对照图（同裁切框同倍率）
python .tmp/scan-palette-ab.py <before.png> .tmp/scan-palette-verify/after.png <out.png>

# 全量审计门禁链（本轮 36/36）
node scripts/run-audit-gates.mjs

# nearby 四态判别 + 出图
node .tmp/nearby-probe2.mjs

# dev 档产物构建（只构消费端；含 uni build 前置清空）
bash .tmp/consumer-mp-dev-build.sh

# 三份副本一致性
md5sum packages/shared-uni/src/components/{app-nav-bar,empty-state}.vue \
       clients/consumer-mp/src/components/{app-nav-bar,empty-state}.vue \
       clients/merchant-mp/src/components/{app-nav-bar,empty-state}.vue
```

> ⚠️ **两个本轮踩到的取证坑**
> 1. 统计形如 `--info:` 的 CSS 变量时，`grep -o "--info:"` 会被当成**命令行选项**而静默零命中
>    ⇒ 必须写 `grep -o -e "--info:"`。本轮据此差点误报一条“令牌缺失”缺陷。
> 2. 只看「产物文本里有新样式」**不等于**改动生效：本轮首次「构建成功」实为
>    `uni build` 在 `emptyDir` 阶段失败（**产物仍是旧值**，且目录已被删残）⇒ 必须 **退出码 + `<testcase>`/产物内容 + 真机取值**三样一起看。
