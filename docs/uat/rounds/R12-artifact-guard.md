# R12 — admin 产物配对门禁闭环 · 账期窗口取证复核（2026-11-08）

> 本轮不改业务语义：只把 R11 barcode 改动遗留的**上线阻塞项**（admin 产物未随源码提交）闭环，
> 并对 §六账期窗口做一次源码级复核与单测窗口消歧。属「推不推得上」的收口，不含新业务测试。

## 一、为什么 R11 提交后预检红

R11 提交（`68ea10de`）改了 `clients/admin-vue/src/views/skus/SkuListView.vue`，随后
`node scripts/pre-push-ci-preflight.mjs` **唯一红**：`check:admin-artifact-pairing exit=1`
（「聚合链 98 个门禁，失败 1 个」）。

- 门禁口径（`scripts/check-admin-artifact-pairing.mjs`）：
  区间 `merge-base(origin/dev, HEAD)..HEAD` 内如果出现
  `ADMIN_INPUT_PATTERNS`（`clients/admin-vue/src/`、`packages/<x>/src/`、`packages/shared-dict/dist/`）
  且 `ARTIFACT_PREFIX = services/trade-service/src/main/resources/static/admin/` 产物为空 → FAIL。
- **这是设计如此，不是告警噪声**：Admin 前端是构建产物（hash 化文件名）且**随源码被 git 跟踪**，
  CI 的 `admin-artifacts` job 会对干净检出重建产物做**字节比对**。源码改了但产物没跟着重建提交 ⇒ CI 必红。

## 二、为什么不能「绕过」而是要重建

- `ADMIN_PAIR_PROVE_INERT=1` **买不到绿**：barcode 那一行文案 + 编辑态闸门逻辑确实会进 bundle，
  属于“真实影响产物”的改动，不是「可证明无关」。
- `build-admin.mjs` 产物进 `services/trade-service/src/main/resources/static/admin`；
- 产物入口 = `vue-tsc --noEmit` -> `vite build` -> `gen-admin-runtime-config.mjs`。

## 三、修法：**干净检出**重建（不是本机 node_modules 凑）

🔴 `scripts/build-admin-ci-fidelity.sh` 的头部注释已钉死这个坑：本机 `clients/admin-vue/.env.local`
里的 `VITE_DEV_PROXY/VITE_DEV_ORIGIN` 会被 vite **内联进产物** ⇒ 工作区构建产物与 `git checkout`
干净检出产物**内容哈希全不同** ⇒ CI 字节比对必然报红。所以必须 clone 干净检出重建。

本轮落地（Windows 无 bash，脚本的 `bash` 起不来）：

1. `docker run -i node:24.18.0`，挂载本仓为**只读** `/src`，传容器脚本到 `/tmp/build.sh`。
2. 容器内：`npm i -g pnpm@9.15.9` → `git clone --no-hardlinks /src /w/repo`（**干净**，
   无 `.env.local`/无宿主 `packages/shared-types/dist`）→ `git checkout --quiet HEAD_SHA` →
   `pnpm install --frozen-lockfile` → `node scripts/build-admin.mjs`。
3. 稳定性自检：`test -f .../index.html` + 产物文件数打印；容器自检干净检出里不得有 `.env.local`。
4. 回传：**先 `move` 旧产物到 `.tmp/stale-admin-ci-r12-admin`（保留 241 份，不做 `rm`，
   避开 safe-delete 拦截把退出码抹成 0）** → `docker cp` 新产物 → 校验文件数 ≤400 防叠加。
5. 真实性自检：`rg "新条码校验位不正确" static/admin/assets` **命中** ⇒ barcode 改动确实进了 bundle。

## 四、本轮踩到并记录的真坑

| # | 坑 | 后果 | 解法 |
|---|----|------|------|
| 1 | Windows 宿主无 `bash`（`system32\bash.exe` 需 WSL Relay，报 `execvpe failed`） | `build-admin-ci-fidelity.sh` 直接起不来 | 改由 **`cmd /c "call driver.bat"`** 驱动等价的 `docker run` 序列 |
| 2 | PowerShell 里 `--mount "type=bind,source=..."` 等长串 | 解析器拆参数报错 | 迁移到 **batch**（cmd 对双引号拼接不挑剔） |
| 3 | batch 里 `docker cp "%STAGE%:/w/..."` → `$STAGE:/path` 的 `:` 不是合法变量字符 | 变量名含 `:` 报错 | 写成 `${STAGE}` / 先 `set` 到独立变量 |
| 4 | 产物回传用 `rm` | safe-delete 守卫会掩盖退出码，还可能叠加出两倍文件数 | **先 `mv` 到 stale 再 `docker cp`**，回传后按文件数阈值校验 |
| 5 | 把 commits 写在 R11 之后、但区间里没有产物 | 门禁按区间判，**同一推送区间**才有产物才算数 | 产物与源码提交**同区间**（本例上叠加成第二个 commit 后仍属同一区间） |

## 五、结果

- **commit `1a32a93f`**：`static/admin` 随源码重建提交——241 份中 **114 份因 content hash 变名**
  （hash 文件名随内容改变，属正常）；**旧名残留 0**（`AdAssetsView-b-vIG6qB*` 等已消失）；
  `runtime-config.json` 被 gitignore，不计。
- 重跑 `node scripts/pre-push-ci-preflight.mjs`：**exit 0**，且
  `✓ check:admin-artifact-pairing`、`✓ admin-bundle-budget`、`✓ openapi-types`，
  汇总 `✓ 预检通过（format / mp type-check / lint / audit-gates / migration 门禁 / admin 产物）`。
- 文档修正：`docs/uat/rounds/R11-barcode-gs1-consistency.md` §六 wording + 新增 §6.7 单测窗口消歧；
  `docs/uat/UAT-FINAL.md` 账期切换窗口行。

## 六、账期窗口取证复核（防回归）

- `WarehouseMonthlyCloseService.java:138-140` 实读确认 §六.4 口径：
  `start = period.minusMonths(1).atDay(26).atStartOfDay(SHANGHAI).toInstant()`，
  `end = period.atDay(26).atStartOfDay(SHANGHAI).toInstant()`，**上海本地 00:00 左闭右开**。
  例：2026-11 账期 = `[2026-10-25T16:00Z, 2026-11-25T16:00Z)`。
- 单测窗口辨析：`WarehouseMonthlyCloseMathTest.java:17-18` 的
  `LocalDate.atStartOfDay().toInstant(ZoneOffset.UTC)` **是测试自己的夹具**（只传给
  `WarehouseMonthlyCloseMath.aggregate(movements, start, end)`，不经过 `closeSheet`），
  不构成生产期口径冲突 → 已按 §6.7 消歧，**勿当 bug**。

## 七、仍未覆盖（不得当作已验）

1. **CI `admin-artifacts` 的真实字节比对**（非 Windows）：本机只是把 node 版本与重建路径对齐到 CI，
   最终仍需 **push 后由 Actions 验证**。
2. `claimForPayout` 并发抢行：活库无 `APPROVED(非 mock)` 路径，需真实审批实例。
3. 小程序四闸门人工复验（微信开发者工具）、弱网/重签链接 UI：**需人工**。
