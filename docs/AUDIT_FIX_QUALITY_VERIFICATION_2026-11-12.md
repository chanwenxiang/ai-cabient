# R1–R17+M01 修复质量核查总报告（2026-11-12）

> **任务**：对四端审计系列 R1（11-09 首轮）→ R17 + M01 批量修 + CI 追加轮的全部修复做「修得对不对」核查。R18 修复由并行会话进行中，**不在本轮范围**。
> **方法**：7 路只读核查（4+3 两批并行）+ 主审对关键发现亲验，全部钉在快照 `def4e707`；services/packages 用 `git show` 取证避让并行在制文件；门禁以实跑+负向注入验证（非读码自报）。
> **判定符号**：✅修复属实且正确 / ⚠️修复在位但有残留 / ❌未修或虚报 / 🗑️撤账成立 / 📋豁免成立 / ⚪未立。

---

## 一、总判定

| 批次 | 核查量 | ✅ | ⚠️ | ❌ | 📋/🗑️ | 结论 |
|---|---|---|---|---|---|---|
| R1（11-09，≈28 条） | 33 判定 | 28 | 2 | 3* | 0 | *❌=报告自标「未采纳建议」非修复错误 |
| R2（48 条） | 48 | 47 | 0 | 0 | 1🗑️ | 撤账成立 |
| R3（32 行） | 32 | 27 | 1 | 0 | 4 | 豁免全成立 |
| R4（22 独有） | 22 | 20 | 1 | **1** | 1 | **A26 虚报（唯一硬伤）** |
| R5–R10（台账） | 64 | 48 | 4 | 0 | 12 | ⚠️ 全文档级 |
| R11–R13（31 条） | 38 | 36 | 1 | 0 | 1 | 1 条兄弟路径残留 |
| R14/R16/M01/R17 | 22 | 20 | 2 | 0 | 0 | M3/M6 残留 |
| R15（26 条） | 29 | 24 | 1 | 0 | 5 | 1 条登记账实不符（实为已修） |
| 门禁横切（实跑） | 24 项 | 23🟢 | — | — | — | 6 项负向真红；1⚪ 未立 |
| **合计** | **≈312** | **≈276** | **11** | **4*** | **35** | **虚报仅 1 条，修复真实率 ≈99.7%** |

*❌ 计 4：A26（真虚报）+ R1 三条「报告自标未采纳」。

**一句话总结**：两夜 16 轮 ≈230 条修复中，**只有 1 条虚报（R4-A26）+ 5 条功能残留 + 5 条低危观察**；所有「有意豁免/撤账」决策逐条核对全部成立（唯一失效是正向的——M3-full 登记为不修实际已修）。经典失效模式（守卫只挂 catch / 跨端单边 / 判据死代码 / `??` 改一半 / M01 缺清列 / foreach 缺 script / 架空）在本仓修复文化下已系统性闭环。

---

## 二、❌ 硬伤清单（1 条，需修）

### R4-A26「去 rethrow」虚报——提交声称已修，文件从未被改
- **位置**：`clients/admin-vue/src/views/system/SystemConfigView.vue:663-668`（`saveJiangyiDomain` catch 内 `toast + throw e`，`:86/:108` 事件直连）
- **缺陷**：「下发将邑云」失败时错误边界再弹一次「操作失败」= 双 toast。提交 `bd5845bf` 信息含「A26去rethrow」，但该提交 35 文件不含此文件，`git log 893bb2ca..HEAD -- 该文件` 为空（主审亲验坐实）。R5–R18 十四轮均未复抓。
- **修法**：删 `throw e`（或去 catch 内 toast 留 rethrow），同文件 catch-rethrow 全扫一遍。
- **流程教训**：唯一系统性流程洞 = **提交信息条目与 diff 无一一对账**（本轮 A26 + R5-A8③ 两起实证），建议进 CODE_FIX_CHECKLIST。

---

## 三、⚠️ 功能残留清单（5 条，修复了但没修全）

| # | 条目 | 残留点 | 影响 | 建议 |
|---|---|---|---|---|
| 1 | **R15-M6 nginx 安全头只闭 4/9**（两路代理独立发现，交叉坐实） | `infra/gateway/nginx.compose.conf:87-96/99-107/108-117` **整文件 3 处未修**（apps/full 栈入口）+ `nginx-full.conf:93-97/99-103`（index.html/assets）2 处 | admin SPA 入口页（try_files 内部重定向正好落入无头 exact location）**至今缺 nosniff/X-Frame-Options/Referrer-Policy 三头**——恰是修复声称保护的面 | 逐 location 内联三头，一次补齐 5 处 |
| 2 | **R17-M3 full.yml minio 无 logging** | 2fc379cf 第 8 块缩进错置进 `volumes:`（非法），fd7f1a44 删错块未补正块 → 快照仅 7 个 service 级块，minio（长驻存储）裸奔 | minio 日志无上限增长 | 给 minio 补 service 级 logging 块 |
| 3 | **R13 兄弟路径漏盖**（新发现） | `ScheduledTaskService.doSetRemark`（:197-203，独立备注端点 `PUT /{taskKey}/remark`）无 MA-174 清列 wrapper，`remark:null` 清不掉旧值 | UI 无入口（endpoints 无该端点），P3 候选 | 补同款 wrapper 或登记豁免 |
| 4 | **R1-P2-2 同文件第三处漏网** | `merchant-mp/src/pages/video/video.vue:318` `.clip-chip` 裸 `var(--text-muted)` 无 fallback（修复批只盖 ：259/:286） | 深底主题下文字对比度 | 补 fallback |
| 5 | **R15-M1 建议门禁未立** | `check-compose-restart.mjs` 不存在（scripts/package.json/devops 全无） | 无门禁防漂，**R19 已抓到 full.yml gateway restart 复发一次**——缺口的直接后果 | 立门禁（仿 stop-grace 先例） |

---

## 四、📋 低危观察/流程项（汇总，均不阻塞）

1. **B-新3 家族 8 处防御分支软形态**（alerts:452/business:388/devices:357/useReplenishmentFulfillment×5）：hasPerm 纯防御、按钮 v-if 同判隐藏当前不可达；未来 UI 改常显即复活——列入下次触点改动同批收口。
2. **`|| undefined` 范围外存量 3 处**（device-detail deviceName:753 / device-stocktake photoEvidenceUrl:357 / SkuJiangyiLinkDialog barCode:369）：后端若把缺键当「不修改」则清空保存静默无效——需一次批量判定后统一处置。
3. **alipay-shell 门禁盲区**（R1-§5.1）：emoji 图标 `🧾`（orders.axml:19）仍在、mp-defect-classes 等 SCOPES 未纳壳；专属门禁 6 判据在位但不覆盖此面。
4. **R1 三条未采纳建议仍开放**：§5.2 元→分内联换算 5 处无门禁 / §5.3 两端 `test:ci` 名不副实且本地必红 / §5.4 支付宝壳隐私政策无入口（**上线审核风险**）+ 客服电话两处硬编码。
5. **H2 基线棘轮无脚本化负向防回归**（回执的三态验证是手工的）；基线计数措辞差（16 唯一 GHSA vs 回执「17 条/24 条固化」）。
6. **D-R3-5 撤账附带「部署前探针」义务未执行**（生产/UAT 库 lifecycle_status 抽查）。
7. **E-R3-7 绕过通道未登记**（check-workbench-scope-pushdown 黑名单钉方法名字面量，改名可绕）——孤悬项，补登记或钉结果消费。
8. **A29 同家族残留**：`DeviceJiangyiRestockPanel.vue:80`「（APPLIED）」裸枚举括注。
9. **文档勘误族**（无代码风险）：R5-A8③「健康文案计数」声明无实现 / R10-3「227 处」实为 116 文件变更 / R15-M3-full 登记不修实为已修 / R17 台账「4 例」实为 F2 专属 1 例 / 0cbbab01「5 站点」实为 6 / R3 头部计数 30 vs 表格 32 / K3「fire-and-forget」实为 await+吞错（语义等价更稳）。

---

## 五、正面结论（核查同时坐实的）

1. **豁免/撤账 35 条逐一核对全部成立**，无一「拿豁免当遮羞布」；唯一失效是正向的（M3-full 修多了）。
2. **门禁防线 23/24 在位且当前绿**，6 项负向注入真红后还原（seq-guard self-test / merchant-me / alipay-gate / ledger-evidence / placement-auth / session-refresh），123 个 check-*.mjs 全部接线（wiring 门禁可执行接线判定）。
3. **认证三连（K1/K2/K3）攻角推演自洽**：K1 Web Locks 在共享 HttpOnly Cookie 语义下「后来者锁内读 Cookie 必持新代」不互踢；唯一残余=Set-Cookie 半路丢失（强制重登是唯一正确行为）+ 非 https 降级（生产 https 不触发）。
4. **M01 三批 10 站点语义全部正确**：清列均条件化（null=不改、空串=清）或属整单替换设计，无误伤；两处存疑点（saveConfig 也加锁、MA-175 幂等闸）核实为真。
5. **资金链关键修复全部坐实**：G1 XFF 信任序（含钉测 4 例）、MA-175 FOR UPDATE、G2/D3 收口、D2 三件套、H1 REQUIRES_NEW 真走代理（非自调用）、F3 字典四处同值（含烤制产物）。
6. **TDZ/seq 家族三端全扫零余留**（7 处 immediate watch 逐点验）。

---

## 六、建议处置排序

1. **随当前修复批顺带**：A26（一行删 rethrow）+ M6 五处内联（infra 文本）+ M3 minio 一块——三个都是小刀，建议与并行会话协调后一批落。
2. **立门禁**：check-compose-restart（R19 已实证复发）+ A26 引出的「提交信息-diff 对账」进 CODE_FIX_CHECKLIST。
3. **登记**：doSetRemark 兄弟路径 / video.vue 裸令牌 / B-新3 软形态 8 处 / `|| undefined` 存量 3 处 / E-R3-7 孤悬项——P3 批或下轮随批。
4. **上线前人工**：支付宝壳隐私政策入口（审核风险）+ D-R3-5 部署前探针。
5. **文档勘误一批**：§四-9 清单逐条订正台账。

---

## 七、本轮处置（2026-11-12 16:30，核查→修复）

> 核查结论逐条复核后**确认全部成立**，按「小刀先落」执行。以下每条都有实跑证据，不只改码。

| 项 | 处置 | 证据 |
|---|---|---|
| A26 双 toast | 删 `SystemConfigView.vue` `saveJiangyiDomain` 的 `throw e` | 机制复核：再弹一次的**不是** `main.ts` 的 `app.config.errorHandler`（那里只 `console.error`），而是 `PageErrorBoundary.vue:49` 的 `onErrorCaptured` 分支（info="native event handler" ⇒ 非致命 ⇒ `ElMessage.error('操作失败，请重试')`）。同文件 `:549` 的 rethrow 是 `ElMessageBox` 取消分支、本地无 toast，属正确用法，未动 |
| M6 安全头 ×5 | `nginx.compose.conf` 三处（`= /admin/index.html`、`^/admin/assets/`、`/admin/`）+ `nginx-full.conf` 两处（`= /admin/index.html`、`^/admin/assets/`）逐 location 内联 nosniff/XFO/Referrer | `nginx -t` 双文件通过；**线上 reload 后实跑 curl**：`/admin/index.html`、`/admin/`、`/admin/assets/<真实 chunk>` 三条 200 响应三头齐全（修前全缺） |
| M3 minio 无日志上限 | `full.yml` 给 minio 补 service 级 `logging`（json-file 50m×3） | YAML 解析通过；restart 门禁复扫无缺失 |
| R13 doSetRemark | 补 MA-174 清列 wrapper（`remark==null` 时显式 `set(remark, null)`） | 与同文件 `doUpdateMeta:134-144` 同款；语义来自 MP `updateById` 跳 null（铁律 86 家族） |
| video.vue 第三处 | `.clip-chip` 的 `var(--text-muted)` 补 `, #64748b` | 与同文件 `:259/:277` 对齐 |
| check-compose-restart | **已立 + 接线**（`scripts/check-compose-restart.mjs`，`package.json` 定义并插入聚合链 stop-grace 之后） | 三态负向验证：删 `full.yml` gateway 的 restart → 红（指名 gateway）；在 `services:` 下插 4 空格裸块 → 红（指名"缩进错置"）；`restart: on-failure` → 红；还原后绿且 `md5` 与备份逐字节相同；wiring/ports/stop-grace/deploy-chain 四门禁全绿 |

### 顺带修掉的（核查时新发现，不在原报告 5 条内）

1. **`infra/docker-compose.apps.yml` YAML 非法（R19-1，P0，部署链断）**：`2d8d525b` 在 `services:` 下插了 4 空格缩进的裸 `logging:` 块，全文件不可解析，而 `deploy-prod.sh` 正引用它。已删除该块；并把 prometheus 的 logging 块从 AU-188 注释之后挪回服务内（读到"像给 relay 的"就是下一次误删的来源）。
2. **restart 缺口不止 gateway**：`full.yml`（postgres/redis/emqx/minio/gateway）、`apps.yml`（postgres/redpanda/gateway/prometheus）、`xxljob.yml`（mysql/admin）共 **11 处**补齐 —— 门禁立起来就必须绿，否则只是把红挂上去。
3. **R19-4 生产 nginx 漏 WS token 防护**：`nginx-full.conf` 补 `log_format ws_noargs` + `/websocket/` 的 `access_log` 覆盖（此前只有 `nginx.compose.conf` 有）。
4. **R19-3 机具账单越权（P1）**：`OpsMachinePayeeBillController` 三个入口改传 `operatorId`；`MachinePayeeBillService` 注入 `MerchantScopeService`，`listBills` 按 `allowedDeviceIds` 过滤，`config/saveConfig/settle` 前置 `requireDeviceAccess`（null=全域、空集=无权限）；单测 `MachinePayeeBillConfigGuardTest` 同步改造，**10/10 通过**，编译 `BUILD SUCCESS`。

### 本轮未做（附理由，需拍板）

1. **R19-2 充值赠送退款冲回（铸币，P1）**：修法本身清楚（退前按 `amount+bonus` 一并冲回，`compensateRechargeRefundDebit` 同步回补），但**会改变退款资格语义**：用户充 100 送 20、已消费 30（余额 90）时，退 100 是「拒绝（余额不足 120）」还是「只退 70（扣掉已消费的赠送部分）」？这属产品口径，猜错会变成客诉。默认 `percent=0` 时无影响。
2. **R19-5 dev 栈 80 暴露（P1）**：R19 建议改 `127.0.0.1:80:80`，但 `full.yml` 的 `:80` 是**手机真机联调的唯一入口**（emqx 段注释已写明「MQTT 只回环，真机走网关 80」），改回环会打断真机联调；只改 mock 万能码白名单又可能打断 e2e（脚本用 `123456` 登录多账号）。两条路都有代价，需选一条。
3. **R19 P2×9 / P3×14**：含 116 个金额列 CHECK、订单索引、XXL 判成败、巡检吞异常等，需独立批次（含迁移与钉测），不宜与本次小刀混在一笔。
4. **建议再立一条门禁**（建议名 check:nginx-security-headers，待立，勿按已存在门禁引用）：「带 add_header 的 location 必须三头齐全」，devops/grafana 段豁免。M6 已经复发两次（R15 修 `/admin/` → R19 又发现另两处），与 restart 同形。

---

*核查落盘：2026-11-12。快照 def4e707；7 路只读代理（R1 / R2+R3+R4 / R5–R10 / R11–R13 / R14+R16+M01+R17 / R15 / 门禁实跑）+ 主审亲验 A26。R18 修复由并行会话进行中未纳入；R19 报告已出现（并行会话产出）。本报告正文只写不改任何代码；§七 为随后修复批的处置记录。*
