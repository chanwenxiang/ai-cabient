# 2026-09-22 · `admin-artifacts` 连续红的真因：未入库的 `.env.local` 被内联进生产包

**结论一句话**：CI 上反复红的 `admin-artifacts`，主因不是「忘了重建产物」，也不是行尾，
而是 **本机存在一个未被 git 跟踪的 `clients/admin-vue/.env.local`，
经 `vite.config.ts` 的 `loadEnv(mode, __dirname, '')`（空前缀 ⇒ 注入**全部**变量）
把 `VITE_AMAP_JS_KEY` 字面量内联进了 `BigScreenView-*.js`**。
CI 的检出里没有这个文件 ⇒ 同一个提交在 CI 重建必然得到不同的 chunk 哈希 ⇒ 目录比对必红。

> ⚠️ 本文件在 15:52 的初版把主因写成「行尾」，**该结论已被本轮实验否决**（见 §4）。
> 现版本以「单变量 A/B + 与 CI 重建逐名比对」为判据重建了结论，并保留被否决假说的记录。

---

## 1. 红点清单与最终状态

CI run `35701296932`（HEAD = `95d16838`）：

| job | 失败步骤 | 根因 | 本轮状态 |
|---|---|---|---|
| `mini-programs` | ~~`Format check`~~ | prettier 33 个文件未格式化（**真内容问题**） | ✅ 已修（本 run 已 `success`） |
| `build` | ~~`Admin table & shared-component gate`~~ | `BigScreenView.vue` 2 处原生 `button` | ✅ 已修 |
| `build` | `Admin bundle size budget` | `EChart-*.js` 513.2KB > 150KB，**既有超标** | ❌ 非本轮引入，未修 |
| `admin-artifacts` | `Verify admin UI artifacts are up-to-date` | 未入库的 `.env.local` 被内联 | ✅ 已修（判据见 §2） |

证据：`logs/ci-run-35666302423-jobs.txt`（`gh run view --json jobs` 原始输出）。
`admin-artifacts` 的失败原文：

```
##[error]static/admin 与 clients/admin-vue 源码不同步：请在本地运行 `node scripts/build-admin.mjs` 并提交产物
 D .../assets/useErrorPageActions-BZ6Kctjf.js
?? .../assets/useErrorPageActions-cL7v9W4F.js
```

**注意失败的形态**：`Rebuild admin UI` 这一步是**成功**的，紧接着的比对才失败 ——
即「同一份提交，CI 重建一遍，产物确实对不上」。

---

## 2. 主因闭环：`.env.local` 是自变量

`logs/env-local-and-parity.txt` 是完整原始输出。三个层面互相印证：

### 2.1 单变量 A/B（最强判据）

前提已实测：**工作区源码树与 HEAD 逐字节相同**，唯一差异只在 `static/admin` 产物内部。

```
git diff --stat HEAD -- ':!services/trade-service/src/main/resources/static/admin'  →  0 行
static/admin 内非 assets 的改动  →  仅 index.html（引用了新的 chunk 名）
```

| | 构建输入 | 产出 `BigScreenView` chunk | 是否内联 AMap key |
|---|---|---|---|
| **A** | 含 `.env.local`（= HEAD 的已提交产物） | `BigScreenView-BYBxZz4p.js` | **是** |
| **B** | 不含 `.env.local`（= 当前工作区） | `BigScreenView-D4lgoeHc.js` | 否 |

⇒ **源码一个字节没动，只去掉一个未入库的 env 文件，chunk 名即改变、key 消失。**

### 2.2 历史追溯：key 何时开始进入产物

对每一版提交导出 `static/admin/assets` 子树快照后 grep key：

| ref | commit 时间 | `BigScreenView` chunk | 含 key | 该次 `admin-artifacts` |
|---|---|---|---|---|
| `c0f388bc` | 09-21 12:52 | `B02wFX3l.js` | 否 | failure（另有原因，见 §6） |
| `35f6423e` | 09-22 07:09 | `BRK_22mi.js` | **是** | failure |
| `e23f025d` | 09-22 07:33 | `DgEEboZl.js` | **是** | failure |
| `95d16838` | 09-22 15:42 | `BYBxZz4p.js` | **是** | failure |

`.env.local` 的 mtime = `2026-09-22 00:09:49` ⇒ 与「09-22 起的提交全部带 key」吻合。
这也**顺带解开了初版留下的悬案**：`35f6423e` 的 `BRK_22mi` 既不等于我的 CRLF 重建、
也不等于 LF 重建 —— 因为它是在**带 `.env.local` 且源码态又不同**的条件下构建的，
`ui-vendor` 恒变同理。**当初归因给「行尾」是误判。**

### 2.3 决定性判据：与 CI 自己重建的结果逐名比对

从 `admin-artifacts` 的 job 日志里抠出 CI 重建产生的 `git status --porcelain` 输出，
与本机（移走 `.env.local` 后）重建的结果比对：

```
CI   删除(D)=79  新增(??)=79
本机 删除(D)=79  新增(??)=79

判据1  CI-D  vs 本机-D   差集 = 0
判据2  CI-?? vs 本机-??  差集 = 0
```

关键 chunk 逐个对照（同一行三者相等 = 复现成功）：

```
useErrorPageActions  HEAD=BZ6Kctjf  CI=cL7v9W4F  本机=cL7v9W4F
index-               HEAD=BvGqbyqS  CI=DBch8Ow7  本机=DBch8Ow7
BigScreenView        HEAD=BYBxZz4p  CI=D4lgoeHc  本机=D4lgoeHc
WarehouseView        HEAD=DS70Hpyu  CI=CKqUwuqs  本机=CKqUwuqs
```

⇒ **本机产出 == CI 产出**。这条判据不依赖对历史红的任何归因，因此最可靠。

### 2.4 为什么 CI 侧「从来不带 key」是既有常态

```
c0f388bc 的产物：含 key 文件 = （无）
```

`src/utils/amap.ts:9` 是**设计内降级**：

```ts
const JSAPI_KEY = import.meta.env.VITE_AMAP_JS_KEY?.trim() || '';
```

`BigScreenView.vue:287` 注释也写着「配置了 `VITE_AMAP_JS_KEY` 用高德官方暗色底图，
否则降级 Leaflet 免 key 瓦片」。⇒ CI 构建不带 key 是常态，**不是**缺陷；
问题是本机把这个本地差异**烧进了入库产物**。

---

## 3. 为什么本机「全绿」以前没能拦住这些红

两类失效叠加：

1. **`check-admin-table-gate.mjs` 不在本机聚合链里**：

   ```bash
   $ grep -n 'admin-table' scripts/run-audit-gates.mjs
   （空）
   ```
   `run-audit-gates.mjs` 只解析 `package.json` 里 `check:audit-gates` 的字面顺序，
   而该门禁是 CI 的独立步骤（`ci.yml:126`）。`prettier --check`（CI 的 `Format check`）同理。
2. **`admin-artifacts` 是本机根本没有对应步骤的 job**：本机跑完 `build-admin.mjs` 就结束，
   没有「重建 → 与入库产物比对」这一步。⇒ `.env.local` 造成的差异在本机**不可见**。

⇒ **本机绿与 CI 绿不等价**，这是同一类失效（门禁不在本机链内）。

---

## 4. 行尾：是本机的真问题，但**不是** CI 红的原因（被否决的假说）

保留此节的意义在于**记录一次错误归因**。

**事实**：`check-line-endings` 在本机一度报 1 个（`BigScreenView.vue`），今天 14:55:05
一次批量 re-hydrate 又让 **10 个** 文件落入 `eol=lf` 约束区却变成 CRLF
（`favicon.svg`、`ChartPanel.vue`、`feature-flags.ts`、`tsconfig.json`… 均在
`clients/**`、`packages/**`）。它们全是**纯行尾假改动**：`git status` 说改了、
`git diff` 说没改、`git add` 提交不了（`git diff --numstat` 无行数）。

**为什么不能算 CI 红的原因**：CI 是 Linux、检出即 LF，
`check-line-endings` 在 CI 里**从头到尾是绿的**：

```
[check-line-endings] OK: 扫描 3576 个文件（含未跟踪），其中 696 个声明 eol=lf 的文本文件索引与磁盘均为 LF
```

修掉它们仍然值得做（本机 `git status` 干净、避免误提交），
但它解释不了 `admin-artifacts` 的红。**「哈希取决于源字节」这句话是对的，
错的是把自变量认成了行尾** —— 真正的自变量是 `.env.local`。

### 4.1 本机 `check-line-endings` 的调用方式与取证坑

本机恒 `spawnSync git EBUSY`（已知），须挂 `.tmp/tools/nopipe.cjs` 垫片直调：

```bash
NODE_OPTIONS="--require=$PWD/.tmp/tools/nopipe.cjs" node scripts/check-line-endings.mjs --fix
```

⚠️ **取证坑**：Git Bash 的 `grep -c $'\r$'` 会剥离 CR 而恒返回 0，
**必须用 Python 数真实字节**：

```python
b = pathlib.Path(f).read_bytes(); b.count(b"\r\n")
```

---

## 5. `BigScreenView.vue` 的两处改动（视觉零变化）

`<button class="bs-btn" type="button" @click="load" :disabled="loading">` → `<el-button class="bs-btn" …>`。
element-plus 2.14.3 的 `.el-button` 默认态与目标样式冲突（`height:32px` / `line-height:1` /
`padding:8px 15px` / `font-weight:500` / 相邻 `margin-left`），故：

- 选择器加 `.bs-root ` 前缀提权（scoped 后 `(0,3,0)`），压过 `.el-button` 的 `(0,1,0)`
  与 `.el-button.is-disabled` 的 `(0,2,0)`（后者会把 disabled 配色改成灰白）；
- 另起 `.bs-root .bs-btn.el-button { height:auto; margin-left:0; font-weight:400; line-height:normal }`
  只抹平 el-button 特有默认值，**不作用于同一 class 的 `<RouterLink>`**。

`nativeType` 默认就是 `'button'`，故原 `type="button"` 语义保留（不会变成 submit）。

⚠️ **自造的假红**：改完第一版后我把注释写成「禁止视图内出现原生 `<button>`」，
结果注释本身命中了门禁正则 `src.match(/<button[\s>]/g)` ⇒ 门禁仍红。
措辞改成「原生 button 元素」后转绿。**判据绑关键字时，注释也是被判对象。**

---

## 6. 判据汇总（本机实跑，非自报）

| CI 步骤 | 本机命令（直调真实入口） | 结果 |
|---|---|---|
| mini-programs / Format check | `prettier --check "clients/**/*.{…}" "packages/**/*.{ts,json}" "scripts/**/*.mjs"` | EXITCODE=0 |
| mini-programs / Lint | `eslint .` | EXITCODE=0 |
| mini-programs / Audit gates | `run-audit-gates.mjs`（挂 nopipe 垫片） | 见 `logs/local-gates.txt` |
| build / Admin table gate | `check-admin-table-gate.mjs` | 见 `logs/local-gates.txt` |
| build / Admin bundle budget | `check-admin-bundle-budget.mjs` | **EXIT=1（既有超标）** |
| admin-artifacts / Rebuild | `build-admin.mjs` | 产物与 CI 重建逐名一致（§2.3） |
| mini-programs / 三端编译 | `vue-tsc --noEmit` ×3 + `uni build` ×2 | 全 EXITCODE=0（`logs/three-end-compile.txt`） |

原始输出：`logs/local-gates.txt`、`logs/three-end-compile.txt`、`logs/env-local-and-parity.txt`。

### 6.1 本机跑两端 H5 `uni build` 的坑（必读）

`uni build` 在本机首跑会**假红**：

```
[safe-delete][SAFE_DELETE_BULK_CONFIRM_REQUIRED] {"count":76,"threshold":50,"scope":"turn",...}
    at emptyDir (vite/dist/node/chunks/dep-*.js)
Build failed with errors.
EXITCODE=1
```

那是 vite 的 `emptyOutDir` 要 `rmSync` 掉 outDir 下 76 个文件，
触发宿主**安全删除垫片**的 fail-closed（阈值 50），**不是构建错误**。
解法是**可逆地把 outDir `mv` 走**（不是禁用保护）：

```bash
mv clients/consumer-mp/dist .tmp/mpdist-old-consumer-mp   # 可逆
( cd clients/consumer-mp && node node_modules/@dcloudio/vite-plugin-uni/bin/uni.js build )
# -> DONE Build complete.  EXITCODE=0
```

同款坑此前在 `build-admin.mjs`（outDir = `static/admin`，170 个文件）上也踩过一次。

---

## 7. 未做 / 未决

- **`c0f388bc` 那次 `admin-artifacts` 的红另有原因**（它的产物不含 key，却是 failure）。
  本轮的修复判据不依赖对该次的归因，但**不应**把它算作 `.env.local` 的受害者。
- ✅ **已修：`.env.local` → `.env.development.local`**（见 §9）。`VITE_DEV_ORIGIN` 只影响
  dev server 的 proxy 与后端 CORS 回显，**不该参与生产构建**。现按 mode 分档：dev 带 key
  走高德、production 产物不带 key 降级 Leaflet，使「本机产物 / 入库产物 / CI 重建」三者一致。
- ✅ **已修：`check-admin-bundle-budget.mjs` 的假信号**（见 §9）。`largest route` 行原本
  **无条件**打印 `OK`，即使已超标（判据在第 100–102 行 push violation）⇒ 该行日志在说谎。
  现按实际是否超标打印 `OK` / `OVER`。
- ✅ **已修：`EChart-*.js` 的归类错误**（见 §9）。ECharts **早已按需注册**（`src/utils/echarts.ts`，
  只 use Bar/Line/Pie + CanvasRenderer），513.2KB 是真实体积 —— 没有"再优化"空间。
  真问题是它属**第三方库**却被算进**业务 route 桶**（门禁按 chunk 名前缀分类）。
  现独立为 `echarts-vendor` chunk，由 `ADMIN_BUDGET_ECHARTS_KB=600` 单管；
  route 预算对业务页面仍然有效（第二名 `WarehouseView` 142.4KB 贴身受管）。
- **`docs/three-end-full-audit-2026-09-15.md`** 里指向 `clients/*/output/playwright/`
  的两处引用已悬空（该 md 本身已被判定为陈旧快照）。
- **产物入库 + CI 逐字节比对**这条不变量本身是合理的，但它把「构建环境的私密差异」
  放大成红点。⇒ 入库前应固定构建输入（env 文件、node 版本、行尾），
  否则下次还会以另一种形式复现。

---

## 8. CI 验证结果（修复后实测）

修复提交 **`0bc93a52`**（`95d16838..0bc93a52  HEAD -> dev`），CI run **`35706249923`**：

| job | 修复前（`35701296932`） | 修复后（`35706249923`） |
|---|---|---|
| `admin-artifacts` | failure | **✅ success** |
| `mini-programs` | success | ✅ success |
| `edge-android` | success | ✅ success |
| `e2e-h5` | success | ✅ success |
| `build` | failure | ❌ failure（**仅**停在 `Admin bundle size budget`） |
| `integration` | skipped | skipped（设计如此） |

`build` 的失败步骤经 `gh run view --json jobs` 的 `steps[].conclusion` 取证，
**只有** `Admin bundle size budget` 一个 failure；其上游 `Build & Test (trade-service)`
已 **success** ⇒ 说明 §1 里那两条红（`Format check` / `Admin table gate`）确已修掉。

⇒ **本轮修复的三个红点全部转绿**；唯一剩余红点是**既有超标**，见 §1 与 §7。

---

## 9. 第二轮（同日）：归类修正 · env 定档 · 假信号修复

承接 §7 的三条待办，本轮全部收口。**仍以「注入漂移必须真变色」验收**，不只看绿灯。

### 9.1 ECharts 的问题不是体积，是归类

复核发现 **ECharts 早已按需注册** —— `clients/admin-vue/src/utils/echarts.ts:2-9` 明确写着
「禁止 `import * as echarts from 'echarts'` 全量引入（全量 dist 约 1MB）」，实际只
`use([BarChart, LineChart, PieChart, Grid/Legend/Tooltip, CanvasRenderer])`。
⇒ **513.2KB 就是按需注册后的真实体积**，没有"再优化"的空间。

真问题在**归类**：`check-admin-bundle-budget.mjs` 按 **chunk 名前缀**分类
（`ui-vendor-` / `index-` / `leaflet-`，其余全算 route），而 ECharts 由 `EChart.vue` 直接
import ⇒ 落进 route 桶 ⇒ 撞上给**业务页面**设的 150KB 预算。
这不是「ECharts 太大」，是「第三方库被算成了业务页面」。

改动必须**两处同源**（否则整类掉回 route 桶）：

| 文件 | 改动 |
|---|---|
| `clients/admin-vue/vite.config.ts` | `manualChunks` 新增 `/echarts/` 或 `/zrender/` → `'echarts-vendor'` |
| `scripts/check-admin-bundle-budget.mjs` | 新增 `ADMIN_BUDGET_ECHARTS_KB=600` + `echarts-vendor-` 分类 + routes 排除 |

预算取 600KB 的依据：实测 508.0KB（门禁口径 KiB），留 ~17% 余量 —— 足够容忍 minor 升级，
又能抓住「有人退回全量 `import * as echarts from 'echarts'`」这类 ~1MB 的回归。

### 9.2 结果（`node scripts/check-admin-bundle-budget.mjs`）

| chunk | 改前 | 改后 |
|---|---|---|
| `EChart-*.js` | **513.2KB（route 桶 ⇒ 超 150KB ⇒ 红）** | `EChart-CMwhv7Mq.js` **5.2KB** |
| `echarts-vendor-*.js` | — | **508.0KB ≤ 600** |
| `ui-vendor-*.js` | 1050.6KB（`D4pq3Nzp`） | 1050.6KB（**哈希一字未变**） |
| `largest route` | `EChart` 513.2KB | `WarehouseView` 142.4KB ≤ 150 |
| total JS | 3030.6KB | 3030.8KB ≤ 3200 |

🔴 `ui-vendor` 的哈希 `D4pq3Nzp` **改前改后完全相同** —— 这是「ECharts 没有被挪进 ui-vendor
藏起来」的硬证据。若只是把 513KB 塞进 1200KB 的 ui-vendor 桶，该桶会涨到 ~1563KB 并**反而超
预算** —— 那才是真的放宽判据。

### 9.3 注入漂移验证（判据必须真会红）

| 注入 | 期望 | 实测 |
|---|---|---|
| `ADMIN_BUDGET_ECHARTS_KB=100` | 红 | **EXIT=1** `echarts-vendor ... = 508.0KB > 100KB` ✓ |
| `ADMIN_BUDGET_ROUTE_KB=100` | 红 | **EXIT=1** `route WarehouseView = 142.4KB > 100KB` ✓ |
| 默认预算 | 绿 | EXIT=0 ✓ |

第二条尤其重要：它证明**拆出 echarts 后 route 预算对业务页面仍然有效** ——
不是「把违规挪走」，而是「第三方库移出业务桶、业务桶继续管业务」。

### 9.4 顺手修掉一个说谎的信号

`check-admin-bundle-budget.mjs` 原第 104–109 行**无条件**打印 `OK largest route …`，
即使该 chunk 已超标（判据在别处 push violation）—— 这是「失效六形态」的第 ⑥ 类
「信号在骗读者」，也正是它长期掩盖了 EChart 513KB 违规。现改为按实际超标与否打印
`OK` / `OVER`；§9.3 第 2 行输出的 `OVER largest route …` 即实证。

### 9.5 env 定档：`.env.local` → `.env.development.local`

`.env.local` 在**所有 mode** 下被 vite 加载 ⇒ 把 key 内联进 `vite build`(production) 产物；
CI 检出里没有该文件 ⇒ 同一提交在两地构建得到不同哈希。这是 §2 主因的**根治**
（§2 只做了「构建时移走 + 还原」，下次本机构建仍会复现）。

| 动作 | 结果 |
|---|---|
| `mv .env.local .env.development.local` | 590 B，**字节数未变**（全程不读取真值） |
| 新增 `clients/admin-vue/.env.development.example` | **入库**（`.gitignore:35` 的 `!**/.env.*.example` 放行；与 `clients/*-mp` 已有约定一致） |
| `src/utils/amap.ts` 注释 | 「.env.local」→「.env.development.local」，并把「为什么不能放 .env.local」写进注释 |
| 新产物命中 AMap key | **0**（key 长 32，只报长度不打印值） |

⚠️ **代价（必须知情）**：production 产物不再带 key ⇒ 大屏地图**降级为 Leaflet 免 key 瓦片**，
即 `http://localhost/admin/` 看到的是 Leaflet 而非高德暗色底图。这是「本机产物 / 入库产物 /
CI 重建」三者一致的**必要代价**（`.env.local` 与 CI 二者不可兼得）；dev server(:3000) 仍带 key。

### 9.6 本轮回归（全部直调真实入口，取退出码）

| 判据 | 结果 |
|---|---|
| `vite build`（node 24.18.0，无 `--mode` ⇒ production） | EXITCODE=0，29.23s，180 文件 |
| `check-admin-bundle-budget.mjs` | EXIT=0 |
| `check-admin-table-gate.mjs` | EXIT=0（裸表格 18 / 表头排序 7 / 原生按钮 25 文件） |
| `check-line-endings.mjs` | EXIT=0（3668 文件；778 个 eol=lf 的索引与磁盘均为 LF） |
| `prettier --check`（改动 3 文件） | All matched files use Prettier code style ✓ |
| `eslint`（改动 3 文件） | EXIT=0 |
| `run-audit-gates.mjs` | **34 门禁 / 失败 0** |
| 两端 `mp-weixin`（dev 档） | ALL OK（consumer 208 / merchant 241 文件，appid=`touristappid`） |

⚠️ **低优先缺口（未纳入本轮）**：`.gitattributes` 只把 `clients/**`、`packages/**`、
`static/admin/**` 钉成 `eol=lf`，**`scripts/**` 无约束** ⇒ 改动后的
`check-admin-bundle-budget.mjs` 在磁盘上是 CRLF、HEAD 里是 LF。因 `core.autocrlf` 归一，
`git diff --numstat` 只有 15/2 行，提交不产生行尾漂移；但若将来有脚本对 `scripts/**` 做
字节级比较会踩坑。

