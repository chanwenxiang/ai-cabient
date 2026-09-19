# P0-10：两端小程序补齐 vitest 单测 —— 纯逻辑从「只被 e2e 覆盖」到「可单独跑 + CI 真跑」

> 日期：2026-09-19　　提交：见 `git log --grep=P0-10`
> 上承：P0-9（`f4db228d`）、P0-8（`63cdffe1`，Docker 测试守卫）、P0-7（`3ca802af`）

## 一句话结论

`clients/{consumer-mp,merchant-mp}` 此前 **devDeps 里没有 vitest、`src/**` 下 0 个 `*.test.ts`**，
纯逻辑（待办合并去重、申诉库存回补策略、开门支付门槛）**只被 Playwright e2e 覆盖** ——
而 e2e 需要 dev 栈，环境门一关就等于**没人守**（改坏不会红）。
本批补齐 **7 个测试文件 / 71 条用例**（consumer 35 + merchant 36），新增一道**接线门禁**，
并让 CI 真的调用它们。

## 一、改前缺口取证（逐条落到命令级）

| 判据 | 取证 | 结果 |
|---|---|---|
| devDeps 无 vitest | `clients/consumer-mp/package.json`、`clients/merchant-mp/package.json` 的 `devDependencies` | 只有 vite `^5.2.8`，**无 vitest**（对照 `admin-vue` 有 `vitest ^4.1.11`） |
| 无测试文件 | `find clients/{consumer,merchant}-mp/src -name '*.test.ts'` | **0 个** |
| CI 不调用 | `ci.yml` 的 `mini-programs` job | 只有 `type-check`（`:526`、`:529`）与 `build:h5`（`:532`、`:535`）；`pnpm --filter … test` 仅出现于 `shared-rbac`（`:507`）与 `admin-vue`（`:510`） |
| 唯一覆盖是 e2e | `clients/*/tests/*-uat.mjs` | Playwright 套件，**必须 dev 栈**（本机当前只有 runner/sonarqube） |

> 同一类缺口在 `ci.yml:509` 的注释里就被写过：admin 单测「此前从不被 CI 调用」。
> admin 已补，两端 mp 一直没补 —— 本批收口。

## 二、改了什么

| 类别 | 文件 |
|---|---|
| 单测（consumer） | `src/utils/dispute-form.test.ts`(9)、`account.test.ts`(22)、`secure-id.test.ts`(4) |
| 单测（merchant） | `src/utils/todo-list.test.ts`(16)、`merchant-display.test.ts`(10)、`preferred-device.test.ts`(5)、`checkin-location-pref.test.ts`(5) |
| 配置 | `clients/{consumer,merchant}-mp/vitest.config.ts`（alias 与各自 `vite.config.ts` 对齐：shared-* 走 **src 源码**，避免与 dist 口径分裂） |
| 依赖/脚本 | 两端 `package.json`：`devDependencies.vitest ^3.2.7` + `scripts.test = vitest run` |
| CI | `ci.yml` 新增 `Consumer MP unit tests (vitest)` / `Merchant MP unit tests (vitest)` 两步（紧跟 admin 单测之后） |
| 门禁 | 新增 `scripts/check-mp-unit-tests-wired.mjs`，插入聚合链第 **3** 位 |
| 锁文件 | `pnpm-lock.yaml`：**268 增 / 33 删**，全部是 vitest 3.2.7 相关 + `@types/node` 解析收敛（`lockfile-diff.txt`） |

被测函数都是**有行为后果的纯逻辑**，不是为覆盖率凑数：

- `mergeTodoItems`：三源合并 / 「类型+柜机」同源屏蔽 / 有结构化临期行时丢弃 workbench 汇总 / 终态去重
- `inferRestoreInventory`：**C-10 契约** —— 无显式 chip 时必须返回 `undefined`，客户端**不许猜**库存回补
- `availableCents` / `isPayReady` / `resolveClientPreauthCents`：**C-21** 负余额不钳制、支付门槛优先级
- `isCorruptedMerchantName`：编码损坏店名（`????`）过滤，且**不误伤**零散问号
- `preferred-device` / `checkin-location-pref`：存储键与 fail-closed 语义（存储不可用 ⇒ 不默认跳过定位）

## 三、为什么在**最小容器**里跑（环境取证，不是偏好）

1. 本机 `.npmrc` 有 **`engine-strict=true`**，而根 `engines` 要求 `node >= 24.18.0`；
   bash 工具默认 node 是 **v22.22.2** ⇒ 宿主机直跑 `pnpm install` 会因引擎不满足直接失败。
2. 本机 `pnpm --version` 实测输出异常（打出 `}` + `Node.js v24.18.0`，不是版本号）—— corepack shim 不可信。
3. 宿主 `clients/*/node_modules` 是 **win32-x64** 构建产物（esbuild/rollup 原生二进制），
   Linux 容器**无法复用**；反之在容器里原地 install 会把宿主 node_modules 改写成 Linux 版，破坏本机开发。

⇒ 采用的隔离法：容器内把源码 `tar` 到 `/build`（`--exclude=node_modules`）后**在容器内 install**，
宿主 `node_modules` 一个字节都不动。可复现入口：`scripts/run-mp-unit-tests.sh`。

## 四、版本坑：vitest 4 不行，必须 3.2.x（先解析、后动手）

首次用与 admin-vue 相同的 `vitest ^4.1.11` 做 `pnpm install --lockfile-only`，解析器直接报：

```
clients/consumer-mp
└─┬ vitest 4.1.11
  ├── ✕ unmet peer vite@"^6.0.0 || ^7.0.0 || ^8.0.0": found 5.4.21
```

原因是 mp 端的 vite 实际解析为 **5.4.21**（`^5.2.8`，被 `@dcloudio/vite-plugin-uni` 绑定在 vite 5），
而 vitest 4 把 vite 当 peer。**vitest 3.2.7 不再把 vite 列进 peerDependencies**（自带 dependency），
因此与 vite 5 兼容 —— 改用 `^3.2.7`，重解析后无 peer 告警。

## 五、基线结果（容器 `node:24-slim` / node v24.21.0 / pnpm 9.15.9）

```
@aicabinet/consumer-mp   Test Files  3 passed (3)   Tests  35 passed (35)   exit=0
@aicabinet/merchant-mp   Test Files  4 passed (4)   Tests  36 passed (36)   exit=0
```

CI 前提也一并验了：容器内 `pnpm install --frozen-lockfile` ⇒ `Lockfile is up to date` / exit 0
（`container-frozen-lockfile.txt`）—— 即 CI 那 4 处 `--frozen-lockfile`（`ci.yml:217/439/483/640`）不会因为本批红。

原始日志：`container-install.txt`（10 分钟全量安装）、`container-consumer-test.txt`、
`container-merchant-test.txt`、`container-frozen-lockfile.txt`
（后缀是 `.txt` 而非 `.log`：仓库 `.gitignore:87` 有 `*.log`）。

## 六、A/B 漂移验证（6/6 符合期望，源码 sha256 全部逐字还原）

`ab-drift.txt`，脚本 `scripts/ab-drift.mjs`：

| 用例 | 注入的漂移 | 期望 | 结果 | 转红的用例 |
|---|---|---|---|---|
| M1 | `hasExpiryApi` 改恒真（丢掉结构化临期互斥） | 红 | ✅ | 互斥两条（2 failed / 36） |
| M2 | 临期行不再按 `OPEN` 过滤（终态混进待办） | 红 | ✅ | 「OPEN 过滤 + 汇总重新出现」联动条 |
| M3 | 反假红：**只改注释** | 绿 | ✅ | —（36 passed） |
| C1 | 无 chip 时开始「猜」库存回补（破 C-10） | 红 | ✅ | `inferRestoreInventory > 自由文本一律 undefined` |
| C2 | 可用余额负值钳制为 0（破 C-21） | 红 | ✅ | `availableCents > 负值不钳制` |
| C3 | 反假红：**只改注释** | 绿 | ✅ | —（35 passed） |

每条漂移都在**同一容器内**「改 → 跑 → 无条件还原 → 校验 sha256 一致 → 再跑确认复绿」；
M3/C3 两条反假红证明判据不是「只要动了文件就红」。

## 七、门禁：新增接线看门人 + 自证

新增 `scripts/check-mp-unit-tests-wired.mjs`（聚合链第 3 位，紧接 `check:line-endings`）。
它不看名单而是**自动跟随**：扫描 `clients/*` 里**凡是声明了 vitest 的包**，逐个校验

1. `scripts.test` 真的是 vitest（`echo skip` 之类不算）
2. `src/**` 下确实存在测试文件
3. `vitest.config.ts` 的 `include` 用 **glob→RegExp 真匹配**过这些文件（改窄 include ⇒ 红）
4. `mini-programs` job 里存在**可执行命令** `pnpm --filter <pkg> test`（剥掉 shell 注释后判定）
5. 反向：CI 调用的每个 `--filter … test`，其包必须真有 `test` 脚本

**自证 `gate-drift.txt`（5/5）**：G1 改 test 脚本 / G2 移走全部测试文件 / G3 改窄 include /
G4 把 CI 那行注释掉 —— 四条全红；G5 反假红（真命令保留、只加一行提到该命令的**注释**）必须仍绿，
证明规则 4 判的是「真命令」而不是「文件里出现过这个字符串」。

聚合链：`node scripts/run-audit-gates.mjs` ⇒ **28 个门禁，失败 0 个**（`aggregate-gates.txt`）。

## 八、顺带清掉的行尾噪音（非本批引入）

跑聚合链时 `check:line-endings` 报了 **10 个与本批无关**的文件（`favicon.svg`、`ChartPanel.vue`、
`README.md`、两个 `gen-tab-icons.py`、`shared-dict/package.json` …）磁盘是 CRLF。
取证：它们**只出现在 `git status`，不出现在 `git diff --name-only`** ⇒ 典型「纯行尾假改动」
（`HEAD` 仍为 `f4db228d`、`git reflog` 无并发切分支）。
按门禁自带方式 `--fix` 归 LF 后，`git status` 只剩本批的 5 个真实改动。

## 九、仍未覆盖 / 后续

- 本批只覆盖**纯逻辑层**：uni API 交互（扫码/上传/导航）、页面渲染、真实接口契约仍只由 e2e 覆盖（需 dev 栈）。
- `edge/android-app` 仍是**零测试**（无 Gradle/SDK，本机不可跑）。
- 宿主 `clients/*/node_modules` **未安装 vitest**（本批刻意不污染它）⇒ 本机开发者要本地跑单测需先 `pnpm install`；
  CI 会自行安装，不受影响。
