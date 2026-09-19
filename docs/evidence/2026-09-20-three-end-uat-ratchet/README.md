# 三端 H5 UAT：把 `UAT_MAX_FAIL_{BUSINESS,DISPUTE}` 从 3 / 2 压到 0（2026-09-20）

> 一句话结论：这两个基线里那 **5 条失败，逐条实跑复现后证实全是「测试/种子缺陷」，没有一条是产品缺陷**；
> 修完 **business 10/10、dispute 9/9 全绿**，两个基线都已下调到 **0**（`ci.yml`）。

---

## 1. 为什么要做这一轮

`ci.yml` 里有两行 ratchet 基线：

```yaml
UAT_MAX_FAIL_BUSINESS: '3'
UAT_MAX_FAIL_DISPUTE: '2'
```

按 2026-09-15 审计（`docs/three-end-full-audit-2026-09-15.md` §12.6）的**反推**，这 5 条应是
`T-C01` / `T-C03` / `T-M03` / `D-C01` / `D-A04`。但那份归因是**靠代码读出来的猜测** ——
`ci.yml` 里 consumer / merchant 两行基线都带逐用例注释，**只有这两行没有**。

同时，consumer / merchant 两套早已修到 0，business / dispute 一直挂着 3 / 2，
且**从未被逐条追根因**。本轮把它从「反推」变成「实证」，并修掉。

## 2. 方法与环境

| 项 | 值 |
|---|---|
| dev 栈 | 13 个容器在跑（`trade-service` / gateway / postgres / redis / minio / emqx / vision-service / device-simulator / xxl-job / redpanda …） |
| H5 dev server | `clients/consumer-mp` → `:3002`、`clients/merchant-mp` → `:3001`（两处都 `nohup node ./node_modules/@dcloudio/vite-plugin-uni/bin/uni.js`，与 CI 同款入口） |
| admin | 网关 `:80` → `http://localhost/admin`（`200`）；API 直连 `:18080`（`/actuator/health` = UP） |
| Node | **`24.18.0`**（`.nvmrc` / `engines` 要求 ≥24；PATH 里的 22.22.2 不满足） |
| 浏览器 | `PW_CHANNEL=chrome`（系统 Chrome 在位，避开 Playwright revision 问题） |
| 验证码取码 | `REDIS_HOST=127.0.0.1 REDIS_PORT=6379`（走 TCP 直连，正是 CI 的路径） |
| 调用方式 | **直调** `node tests/xxx-uat.mjs`（本机 `pnpm/npm run` 会空转假绿） |

### 🔴 本机一个必须先排掉的坑：`http_proxy` 会让「没监听」看起来像「502」

本机 shell 注入了 `http_proxy=http://127.0.0.1:11651`。于是：

```bash
curl -s -o /dev/null -w "%{http_code}" http://127.0.0.1:3001/            # → 502（代理回的！）
curl -s -o /dev/null -w "%{http_code}" --noproxy "*" http://127.0.0.1:3001/  # → 000（真没监听）
```

⇒ 判「端口是否在听」**必须** `--noproxy "*"`；跑脚本时对子进程 `env -u http_proxy -u https_proxy
-u HTTP_PROXY -u HTTPS_PROXY`，避免 Node `fetch` / 浏览器把 `127.0.0.1` 也走代理。

### 实验纪律

跑之前先 `pg_dump` 全库快照（`.tmp/uat-snapshot/before-uat.sql`，53 868 行）；
跑完把种子脚本改过的数据**逐字还原**（见 §7）。

## 3. 修复前：实测基线（不是反推）

### 3.1 business（基线 3）

```bash
cd clients/admin-vue
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
  REDIS_HOST=127.0.0.1 REDIS_PORT=6379 PW_CHANNEL=chrome UAT_MAX_FAIL=3 \
  node tests/three-end-business-uat.mjs
```

```
✗ T-C01 消费者登录 — no token
✓ T-C02 消费者订单列表
✗ T-C03 消费者订单购物视频 — readyState=0 err=null failedUi=true
✓ T-M01 商户登录 / ✓ T-M02 商户柜机订单
✗ T-M03 商户订单购物视频 — btn=false readyState=0 err=null
✓ T-A01 运营登录 / ✓ T-A02 / ✓ T-A03 / ✓ T-A04
{"pass": 7, "fail": 3}       ← EXIT=0（未超基线）
```

**恰好是 `T-C01` / `T-C03` / `T-M03` —— 与审计的反推 100% 一致。** 用例总数 10 也解释清楚了：
7 条固定 + 3 条仅在 `adminOk` 时执行的运营页用例（`T-A02/03/04`）。

### 3.2 dispute（基线 2）

`.tmp/open-dispute.json` 已存在但**指向不存在的工单**（`ticket_id=1789459658301361828` 与
`session_id=1789459576844846197` 在库里**都是 0 行**）—— 脚本只校验文件存在 + 有 id，**不校验工单是否还有效**：

```
✓ D-A01 运营登录（含图形验证码）
✓ D-A02 打开待审争议页
✓ D-A03 打开争议详情 — click=false
✗ D-A04 运营 UI 免单结案 — hasWaive=false resolvedUi=true
✗ D-C01 消费者登录（短信，无图形码） — fail
✓ D-C02 / ✓ D-M01 / ✓ D-M02 / ✓ D-M03
{"pass": 7, "fail": 2}       ← EXIT=0
```

**恰好 `D-A04` + `D-C01`，同样与反推一致。** 附带确认：跑完 OPEN 工单数仍是 6 ⇒ 失效种子**没有误伤**别的工单。

## 4. 逐条根因（`文件:行` 级）

### 4.1 `T-C01` / `D-C01`：消费者短信登录没填图形验证码

- `clients/consumer-mp/src/pages/login/login.vue:418`
  ```js
  if (!captchaId.value || !captchaCode.value.trim()) {
    err.value = '请先填写图形验证码'; if (!captchaImage.value) void loadCaptcha(); return;
  }
  ```
  ⇒ 不填图形验证码，**短信根本发不出去**，登录必然拿不到 token。
- 而 `captchaId` 只在 Vue 状态里：`login.vue:258` `captchaId.value = data.captchaId || '';`
  **不落任何 DOM 属性** ⇒ 脚本无法像 admin 端那样读 `button.captcha-img-btn[data-captcha-id]`。
- 两套脚本的 `consumerLogin()` 却仍在「点获取验证码 → 直接填 123456」，**从不碰图形验证码**。

👉 这是典型的「**修了一处、没扫其它端副本**」：`consumer-h5-uat.mjs` 早就修好了自己的登录
（文件头第 13 行就写明「拦截 `/api/v2/auth/captcha` 取 captchaId，再 Redis GET」），
business / dispute 这两套是**没跟上的旧副本**。

### 4.2 `T-C03` / `T-M03`：硬编码的演示订单不存在

- `clients/admin-vue/tests/three-end-business-uat.mjs:26` `const DEMO_ORDER = … || '1788233752744411094'`
- 全仓检索：`1788233752744411094` **在任何 `.sql` 迁移里 0 命中**，只出现在测试脚本、
  `docs/STARTUP_REFERENCE.md:177`（「演示订单」）和历史的 playwright 报告里。
- 库里核对：`select count(*) from shopping_session where order_id='1788233752744411094'` → **0 行**
  （同样，`docs/STARTUP_REFERENCE.md` 里的「演示会话 1788233611382431271」也是 0 行）。
- ⇒ 演示库一重建，视频页必然「视频加载失败」，用例**恒红**，却一直占着 ratchet 额度。

这与 merchant 侧早已修掉的一处**是同一个病**，且项目里已留下明确判词
（`merchant-h5-uat.mjs:34`）：
> 「原实现硬编码 `1788252219672817302`，演示库重建后该单号 0 行命中 → 用例恒红，却一直计入
> `UAT_MAX_FAIL_MERCHANT` 基线，白占一个失败额度。」

### 4.3 `D-A04`：消耗性种子没有 setup/teardown

- `three-end-dispute-ui-uat.mjs:201` 只判 `fs.existsSync(DISPUTE_FILE)`，不判工单有效性。
- `D-A04` 会**把种子里那条 OPEN 工单免单结案** ⇒ 第二次跑必然找不到可结案的工单 → 恒红。
- 雪上加霜：`.tmp/` 被 `.gitignore:153` 忽略 ⇒ **CI 里永远没有这个种子文件**，
  脚本 `process.exit(2)`（环境未就绪），再由 ci.yml 的重试逻辑转成 `::warning::` + `exit 0`。
  **也就是说 dispute 套件在 CI 里本来就一条用例都不跑** —— 它那 2 条基线在 CI 中从未被消费过。

## 5. 改动清单（只改测试驱动与共享工具，**未改任何业务源码**）

| 文件 | 改动 |
|---|---|
| `scripts/lib/h5-login.mjs` | **新增**。把「切短信 Tab → 拦截 `/api/v2/auth/captcha` 取 captchaId → Redis 读码 → 填手机号+图形码 → 取短信 → 提交」抽成 `consumerLoginViaSms()`，连同 `clickByText` / `clickByTestId` / `fillPlaceholder` / `dismissPrivacyConsent` / `fetchCaptchaWithRetry` 一起供三套 H5 UAT 共用（含 429 退避，见下） |
| `clients/admin-vue/tests/three-end-business-uat.mjs` | `consumerLogin` 改为调用共享实现；新增 `probeOrderWithVideo()`（API 探测真录像订单）；`T-C03`/`T-M03` 改为「探测到 → 真断言；探测不到 → SKIP」；summary 增加 `skip` 计数 |
| `clients/admin-vue/tests/three-end-dispute-ui-uat.mjs` | 同上改 `consumerLogin`；新增 `seedTicketInOpenList()`；`D-A04` 在种子失效时 **SKIP 且一个按钮都不点**（避免误结无关工单）；summary 增加 `skip`；`D-C01` 用例名由「（短信，无图形码）」更正为「（短信 + 图形验证码）」 |
| `.github/workflows/ci.yml` | `UAT_MAX_FAIL_BUSINESS: '3' → '0'`、`UAT_MAX_FAIL_DISPUTE: '2' → '0'`，并补上逐条根因注释（原先这两行唯独没有注释） |
| `docs/STARTUP_REFERENCE.md` | 「演示订单 / 演示会话」两个常量标注为**会过期、勿硬编码**，给出「现场两步生成」的命令 |
| `docs/three-end-full-audit-2026-09-15.md` §12.6 | 追加更正块：5 条已实证 + 已修复 + 基线归零，指向本文件 |

两个「探测」的实现口径都照抄项目既有范式，并做了**同一档防护**：

```js
const r = await fetch(`${prefix}/${oid}/video`, { headers: authHeaders });
if (!r.ok) return false;
const buf = await r.arrayBuffer();
if (buf.byteLength < 1024) return false;                            // 过短的假成功
return String.fromCharCode(...new Uint8Array(buf.slice(4, 8))) === 'ftyp';  // 非 MP4
```

## 6. A/B 对照（证明 SKIP 不是空判据）

单跑绿了不代表判据有效 —— 必须能指出「**让它红的方式**」。三条对照全部实跑：

### 6.1 正向对照：真造一条有录像的订单 → 真的全绿

```bash
# 找一条带 order_id 的会话（user_id=10001 正是演示消费者 13800138000）
# 1789830778248602719681 → order 1789830779488265753766
.\scripts\seed-demo-shopping-video.ps1 -SessionId 1789830778248602719681   # UPDATE 1，22.45 KiB 上传成功
DEMO_ORDER_ID=1789830779488265753766 UAT_MAX_FAIL=0 node tests/three-end-business-uat.mjs
```

```
✓ T-C03 消费者订单购物视频 — order=1789830779488265753766 readyState=4 err=null failedUi=false
✓ T-M03 商户订单购物视频 — order=1789830779488265753766 btn=true readyState=4 err=null
{"pass": 10, "fail": 0, "skip": 0}      ← EXIT=0
```

⇒ 「探测到就真断言」这条路径是活的，**10/10**，不是靠 SKIP 凑绿。

### 6.2 负向对照：注入「能过探测但浏览器解不了」的假 MP4 → 立刻变红

构造 2048 字节、`bytes[4..8] = 'ftyp'` 的文件（骗过探测的长度+魔数检查），上传并绑到另一条订单：

```
✗ T-C03 消费者订单购物视频 — order=1789830647582270225241 readyState=0 err=4 failedUi=true
✗ T-M03 商户订单购物视频 — order=1789830647582270225241 btn=true readyState=0 err=4
{"pass": 8, "fail": 2, "skip": 0}
[uat] 失败 2 条，超出基线 0
EXIT=1
```

⇒ 判据落在「**浏览器真的能播**」这一层（`readyState` / `MediaError`），不是橡皮图章。

### 6.3 消耗性种子的 SKIP 分支：新种子 9/9 → 被自己结案后正确 SKIP

```
# 第一次（create-open-dispute.ps1 新种子）
[种子] 工单 1789835598000124265478 出现在 OPEN 列表中: true
✓ D-A04 运营 UI 免单结案 — hasWaive=true resolvedUi=true
{"pass": 9, "fail": 0, "skip": 0}      ← EXIT=0

# 第二次（同一种子，已被上一次结案 → 库里 status=RESOLVED）
[种子] 工单 1789835598000124265478 出现在 OPEN 列表中: false
○ D-A04 运营 UI 免单结案 — 种子工单 … 不在 OPEN 列表（已被上一次 UAT 结案 / 演示库重建）⇒ 请重跑 scripts/create-open-dispute.ps1
{"pass": 8, "fail": 0, "skip": 1}      ← EXIT=0
```

⇒ 种子失效不再**假红**，而是给出**可执行的下一步**；同时 fail=0 与种子可用时一致。

## 7. 数据还原（实验纪律）

种子脚本会动演示数据，跑完逐字还原（原始值取自 `.tmp/uat-snapshot/before-uat.sql`）：

| 对象 | 原始值 | 动作 |
|---|---|---|
| `shopping_session 1789830778248602719681` / `1789830646265281902918` 的 `video_uri` | `NULL`（`upload_status` 保持 `UPLOADED`，该列 NOT NULL） | 已还原为 `NULL` ⇒ 全库 `video_uri is not null` 计数 **9 = 9**，与快照一致 |
| `user_account 10001.balance_cents` | `11300`（种子会置 20000） | 已还原为 `11300` |
| `device_info 330449777078.sales_locked` | `false`（种子也置 false） | 无需动作 |
| 设备上的阻塞会话 | 快照 `COMPLETED=21 / DISPUTED=3` | 复核后 **未被种子取消任何会话**（新造的 1 条即 21→22 的差额） |
| MinIO 假对象 `demo/broken-not-a-video.mp4` | 不存在 | 已 `mc rm` 删除 |

保留的：`create-open-dispute.ps1` 新建的那 1 条会话 + 1 条（已 RESOLVED 的）工单 —— 这是**文档化种子脚本的正常产物**，
且库里本来就有 6 条历史遗留 OPEN 工单。真实录像对象 `cabinet-videos/demo/sample-shopping.mp4` 保留
（`scripts/seed-demo-shopping-video.ps1` 的既定 demo 资产）。

## 8. 门禁与静态检查（全绿）

| 检查 | 结果 |
|---|---|
| `node scripts/run-audit-gates.mjs` | **32 个门禁，失败 0** |
| `node scripts/check-line-endings.mjs` | OK（3 个改动文件 `git ls-files --eol` 均 `w/lf`） |
| `prettier --check`（3 个改动文件） | All matched files use Prettier code style! |
| `eslint`（3 个改动文件） | 0 error（1 条**既有** warning：`'API' is assigned a value but never used`） |
| 语法 | 3 个文件 `node --check` 全过 |

## 9. 诚实边界（没做到的 / 不确定的）

1. **CI 里 business 的 T-C03/T-M03 仍会是 SKIP**：CI 没有 MinIO，探测必然落空。这是**预期**，
   不是回归；基线降到 0 后这两条在 CI 中等于「有种子才验、没种子则跳过」。
2. **dispute 套件在 CI 里仍然一条用例都不跑**：种子文件被 gitignore，脚本 `exit 2` → warning → `exit 0`。
   本轮**没有改变**这个现状（要改需要让 CI 现场造种子：模拟器 + MQTT + vision force-need-review）。
   所以 `UAT_MAX_FAIL_DISPUTE: '0'` 在 CI 中是**未被消费**的 —— 它现在的价值在本机。
3. **`D-A03`（打开争议详情）判据偏弱**：`clicked || /工单|会话/.test(text)` 里后半个条件几乎恒真
   （侧栏菜单就有「争议审核」）。本轮**未动**它 —— 要收紧需要先确认稳定的详情容器选择器，
   否则又是一条「恒真判据」。**登记为待办。**
4. **`T-A02/T-A03/T-A04` 同样偏弱**：只断言整页文本含「争议审核 / 异常中心 / 订单」，其实导航栏就含这些词。
   本轮**未动**（会显著扩大改动面）。**登记为待办。**
5. **`D-A04` 依旧不可重复执行**：SKIP 只是把它从「假红」变成「如实跳过」，
   真正的治本是给种子加 setup/teardown（`create-open-dispute.ps1` 需要每次跑）。
6. **`testdata/sample-shopping.mp4` 只有 22 KB**：够验证播放管线，但不足以覆盖大文件/分片/超时。
7. 本轮全程用 **`PW_CHANNEL=chrome`（系统 Chrome）**；CI 用 `PW_CHANNEL=chromium`，理论上行为一致但未在 CI 复核。

## 10. 复现命令（照抄即可）

```bash
cd clients/admin-vue
export PW_CHANNEL=chrome REDIS_HOST=127.0.0.1 REDIS_PORT=6379
# business（无录像种子 → 8P/0F/2SKIP）
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
  UAT_MAX_FAIL=0 node tests/three-end-business-uat.mjs
# dispute（先造种子 → 9P/0F/0SKIP）
#   PowerShell: .\scripts\create-open-dispute.ps1
env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY \
  UAT_MAX_FAIL=0 node tests/three-end-dispute-ui-uat.mjs
```
