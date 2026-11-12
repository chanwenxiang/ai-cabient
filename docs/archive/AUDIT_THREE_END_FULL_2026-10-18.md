# 三端全量复审审计报告（2026-10-18）

> **基线**：dev `28c9f99a`（含工作区另一会话的未提交改动：prod-managed 部署线 docs/infra/scripts 若干——本报告以工作区现状为准并单独评估）。
> **方法**：8 路并行只读子代理（资金域 / 会话·识别·设备链 / 认证·RBAC·后台 API / admin-vue / 双小程序+shared / infra·CI·edge / 历史文档核查×2）+ 主审对全部 P0/P1 逐行亲验。**未跑测试套件、未做运行时探测/渗透**——本报告为静态源码审计口径，实跑验证属修复批的事。
> **纪律**：既有审计文档（09-27 三端审查、10-05 两份+修复队列、10-13 台账、10-15 backlog、10-16 决策简报等）一律按「待验证声明」处理，逐条对照 HEAD 源码核实后才采信；用户明确要求不全文采信。
> **编号**：新发现已在 `docs/AUDIT_LEDGER_2026-10-13.md`（唯一编号源）登记为 **AU-136~AU-171**；本文 N-xx 为写作序号，引用请以 AU 号为准。

---

## 1. 总评

| 维度 | 结论 |
|------|------|
| 新发现 | **P0×1、P1×2、P2×10、P3×22**（详 §2–§4）；无资损级 P0（资金域唯一 P1 在余额退款冷链） |
| 历史文档核查 | 约 150 条声明逐条核验：**失实 17 条、虚报「已修」0 条**。判断层（缺陷定性）零误报；债务集中在**文档不回写**——FIX_QUEUE/台账/backlog 各有过时行 |
| 既有修复健壮性 | 抽验 16 条台账「已回归」+ 两份 10-05 报告全部 P0/P1：**修复全部真实在 HEAD 在位**，未发现「被后续重构打回原形」的回归 |
| 未提交改动评估 | prod-managed 部署线（Dockerfile ENTRYPOINT/JAVA_OPTS、production graceful shutdown、prod-managed compose、sync-consumer-mp-api 局域网优先）方向合理、自洽，残留风险见 AU-147 |
| 架构级确认 | DC-27(b) 识别链退役在 HEAD **全收口**（vision-service 整目录已删、重力兜底/结算短路已删、`@EnableKafka` 改挂 notify.async-enabled）；fleet 共享密钥已根治（APK 无 INTERNAL_API_KEY、V367 一机一密+路径绑定）；金额「分」口径全链路抽查通过 |

**一句话**：代码底盘经近三轮审计后扎实（资金写路径锁/幂等/净额口径、认证默认拒绝、内部 API fail-closed 均在位），本轮抓到的 1 个 P0 是「同一防线只修了一条路」的旁路（wx-login 修了、wx-h5-login 没修），2 个 P1 都是**改版当夜的交叉遗漏**（EG 编号规则 × 同日新页面；H63 续退链幂等键维度）。

---

## 2. P0 / P1（3 条，均已主审亲验）

### AU-136（P0）`/auth/wx-h5-login` 接受客户端明文手机号——已知手机号即可接管任意账号（含运营号段）
- 证据：`AuthController.java:150-156` 把 `request.phoneNumber()` 原样传入；`AuthService.java:250-253` → `loginOrCreateByOpenId(openId, phoneNumber)`；`AuthService.java:324-335` openid 未绑定时按该手机号 `requireExistingUser` → 绑 openid → **直接签发正式 token**。无短信验证、无 mock 门、运营账号照常放行。
- 对照组：C 端 `wx-login` 已按 C13 只认服务端 `getuserphonenumber` 换取的 phoneCode（`AuthService.java:227-247`），H5 路径完全绕开同一防线；consumer 端只发 `{code}`（`consumer-auth-session.ts:94-96`），该参数无合法消费方，纯攻击面。
- 触发条件：H5 OAuth 配置后可利用（`WeChatWebOAuthClient.java:38-46`）；当前生产未配 wechatH5OauthEnabled，**配置即炸**。
- 修法：H5 路径同源改走 `resolveWxPhoneNumber`，或删掉 H5 手机绑定分支（未绑定只允许注册游客/短信验证后再绑）。

### AU-137（P1）余额退款「续退链」幂等键按请求号恒定——多轮复审静默吞用户资金
- 证据：续退补扣 `BALANCE_REFUND_RESUME:{requestNo}`（`BalanceRefundService.java:485-488`）与失败冲回 `BALANCE_REFUND_REVERSAL:{requestNo}`（`:543-547`）均不含轮次；`BalanceLedgerService.doChange`（`:57-63`）撞键即返回旧行 no-op。而补扣是**先手动改账户余额、后写流水**（`:474-490` debitRemainingBalance）→ 第二轮起：余额被真实扣掉、流水 no-op；冲回再撞首轮键 no-op → 每多复审一次净吞一份；第三轮起余额扣减**无任何流水**（余额≠Σ流水不变式被破坏，对账无法发现）。PARTIAL 状态允许反复续退（`:551`）。
- 触发面：部分退款 + 多充值单 + 渠道失败 + 复审重试（事故处置期恰好全中）。
- 修法：幂等键加轮次/切片维度（如 `:{attempt}` 或用本次失败切片 outRefundNo）；补扣改走 change 的幂等语义，删手动扣减。

### AU-138（P1）商户端扫码盘点自写正则剥 EG 前缀——新版编号设备盘点必失败
- 证据：`device-stocktake.vue:39-56` `raw.match(/(\d{6,})/)` + 注释「12 位纯数字」（EG 改版前旧口径）；权威解析器是 `packages/shared-uni/src/qrcode.ts:7` `DEVICE_ID_RE = /^(?:EG[0-9]{12}|[0-9]{6,12})$/`（consumer 端与补货扫柜均走它）；`DeviceIdService.java:22-24` 新编号本体即 `EG+12位` 存储。扫 EG 二维码 → 得纯数字 → 与库存主键不匹配；贪婪首段还可能抓二维码中其它 ≥6 位数字段。
- 修法：改用 `scanCabinetDeviceId()`，删页内正则；连带修 AU-139（`device-stocktake.vue:161`、`tally-records.vue:121` 的 `EG{{deviceId}}` 双拼）。

---

## 3. P2（10 条）

| AU | 域 | 标题 | 锚点 | 修法方向 |
|----|----|------|------|----------|
| AU-139 | merchant-mp | EG 前缀模板双拼「柜机 EGEGL…」 | `device-stocktake.vue:161`、`tally-records.vue:121` | 直接显示 deviceId，随 AU-138 同批 |
| AU-140 | 资金 | 余额退款切片「结果未知=失败」即冲回+续退换单重退 ⇒ 双退窗口，`WeChatRefundReconciler` 不覆盖余额切片 | `BalanceRefundService.java:416-427,449-456,582-592`、`WeChatRefundReconciler.java:46-52` | 切片失败先按 out_refund_no 查单确认；对齐订单退款链 queryRefund 范本 |
| AU-141 | 资金 | B2 收口不完整：分账 return/refresh 族 + 对账账单下载仍 `@Transactional` 包 HTTP（不双付，但长事务占连接拖池） | `WeChatProfitSharingService.java:219,254,292-330,409,431`、`RevenueSplitService.java:151-167,254-274`、`ReconciliationService.java:116` | 照 submitSplit 三段式改造 |
| AU-142 | 认证 | 商户自助改密漏走 PasswordPolicy，可设纯数字弱密（CB-050 新路径绕开 CB-021 收口） | `AuthService.java:200-218` | 补一行 `PasswordPolicy.validate` |
| AU-143 | admin-vue | DeviceDetailView 新增自建 `yuan()`——A6 收敛当日晚间回归同类问题；门禁对「自建格式化函数」结构性盲区 | `DeviceDetailView.vue:1290-1292,464,476` | 改 `import { yuanTextZero as yuan }`；可给门禁加棘轮 |
| AU-144 | admin-vue | 「记住手机号」opt-out 被 `/me` 无条件回写击穿 + 登出不清理 `admin_phone` | `stores/auth.ts:204-208,252-256`、`auth-storage.ts:70-79` | 写前读开关门控；clearAuthStorage 补删两键 |
| AU-145 | consumer-mp | 「其他方式登录」明文手机号进请求体 → 生产必败被 catch 吞，微信绑定永远静默缺失 | `login.vue:584-597`、`consumer-auth-session.ts:81-89`、后端 `AuthService.java:240-244` 400 | 删 phoneNumber 参数，只传 code 走 openid 绑定 |
| AU-146 | infra | `grafana.htpasswd` 真实管理员口令哈希入库且 bcrypt cost=05；apps.yml「占位口令」注释失真 | `infra/gateway/grafana.htpasswd:1`、`apps.yml:242-243` | 待拍板：轮换+部署时 env 生成；或接受「仓库哈希视同公开」但 cost≥10+注释如实 |
| AU-147 | infra | git 层生产编排下自研柜机 HTTP 面不可达（全回环+nginx /internal/ 403），OTA/设备 API 依赖未入库的 prod-server.yml，无门禁看护；MQTT 面有 `${EMQX_MQTT_BIND}` 逃生口、HTTP 面无 | `apps.yml:60,166`、`nginx.compose.conf:137-139`、`OtaChecker.kt:55-58` | **待用户拍板首期柜型**；若含自研柜给端口加 `${BIND:-127.0.0.1}` 形态 |
| AU-148 | infra | gateway 是唯一对外入口，却是全栈唯一无 `restart: unless-stopped` 的常驻服务——宿主重启后入口不自愈 | `docker-compose.yml:153-161`、`apps.yml:238-252`、`full.yml:378-388` | 三处补 restart；可入 stop-grace 门禁锚点 |
| AU-149 | 合规 | 隐私政策承诺「注销账户联系客服」，全链路无注销/数据删除端点、服务端无同意留痕（PIPL/微信审核硬要求） | `policy/detail.vue:126`、trade 全仓 grep 零命中、`shared-uni/request.ts:222-227` 仅客户端拦截 | 待拍板：人工客服通道是否算数须运营确认；补服务端删除闭环 |

---

## 4. P3 卫生族（22 条，AU-150~AU-171，台账区间行登记）

| # | 锚点 | 问题 |
|---|------|------|
| 1 | `CommissionClawbackService.java:284-285`、`DeviceRentalService.java:224` | 两处过时注释（净额口径/安装费默认 100 元）会误导下一个改钱的人 |
| 2 | `MinioVideoService.java:52-54,98-105` | `presignDownloadUrl` 对 `file://` 原样透传 + `objectExists` 保留 file:// 分支，与 streamTo fail-closed 口径不一致（现无注入调用方） |
| 3 | `SessionService.java:344-347,407-411,226-242,274-277`、`SessionSettleService.java:264-266`、`SessionLiveCartService.java:55-58` | 低频路径事务包 Redis 锁（与批 4 已修反模式同形，锁等待期占 DB 连接） |
| 4 | `services/device-service/device-service-*.lck` ×4 | Paho 测试锁文件被 git 跟踪（f030f09f 误入） |
| 5 | `OpsTwoFactorService.java:128-135` | 2FA challenge 校验无尝试次数限制（前置=已持密码者） |
| 6 | `AuthService.java:504-505` + `GlobalExceptionHandler.java:50-54,100-105` | runWithLock 兜底透内部异常 message；IAE/ISE 直接回显 |
| 7 | `scripts/check-consumer-endpoints.mjs:52-63` | consumer 端点门禁仍是试点清单反查，admin 侧已拒绝式——不对称 |
| 8 | `sms/SmsCodeService.java:120-126` | `latestActiveCode` 可返回明文验证码且为死代码，建议删除 |
| 9 | admin 29 个 .vue | UI Token 裸 hex（AdminLayout.vue:398-399 等），无门禁 |
| 10 | `clients/admin-vue/.env.local` | 踩 example 自写红线「不要建 .env.local」（现仅 proxy 键、未入产物，无泄露） |
| 11 | admin 7 处 | `VITE_API_BASE` base 逻辑复制粘贴，`client.ts:44` getBaseUrl 未导出 |
| 12 | `DevOpsHubView.vue:155-156,213-215` | Prometheus 外链 fallback `http://localhost:9090`，生产点开是死链 |
| 13 | `merchant-mp/src/pages/login/login.vue:170-181` | ⑥残留清理挂登录页模块而非 onLaunch——已登录老用户不触发 |
| 14 | `merchant-api.ts:59-61,866-868`、`endpoints.ts:115-116` | merchant token 无 expires 本地判据；`URLSearchParams|string` 残留签名与铁律 40 收口方向相逆 |
| 15 | `tally-records.vue:59,64,122,180` | 「近 14 天」虚构窗口 + 徽标 total vs 首页 50 条截断无提示 |
| 16 | `Dockerfile.java-service:21` vs trade/device Dockerfile | 三套漂移：jiangyi-gateway 无 JAVA_OPTS/MaxRAMPercentage（768m 下堆仅 ~192MB）、maven tag 一钉一浮 |
| 17 | `.dockerignore` | 不含 `.env*`——本地 .env 进 build context 形态缺口（现镜像只 COPY jar，无实害） |
| 18 | `prod-managed.yml:67` | `hostname: device-${DEVICE_INSTANCE:-a}` 缺省跨机相撞 → MQTT 会话互踢（现单机 dormant） |
| 19 | `deploy-prod.yml:21-25` 等 | 漏顶层 permissions 收敛；三个 workflow 第三方 action 全 pin tag 不 pin SHA |
| 20 | 57 个 check-*.mjs | 仅 3 个有负向自测；`check-compose-ports.mjs:49` 内联数组不识别裸条目（现无实害） |
| 21 | `nginx.compose.conf:15-17,187` | 无 CSP/HSTS（HTTP 过渡期已知）；:187 空 add_header 组合行为正确但无注释防误删 |
| 22 | 双小程序 | 消费者「注销承诺」见 AU-149；merchant 测试计数声明 176 vs 静态实测 159（仅登记待核，未跑测试） |

---

## 5. 历史文档核查矩阵（本轮重点：先验证、后采信）

### 5.1 `THREE_END_FULL_REVIEW_2026-09-27.md`（11 条 P1）——**11/11 属实**
F1 竞态（净额三件套+P0-4 叠加）、F2 免单旁路（SHOPPING 超时→DISPUTED）、F3 提现盲置 FAILED（WithdrawPayoutPolicy 仅 MOCK 自动失败）、S1 设备共享凭据（V290+ACL 命名空间）、S2 GET token 已删、S3 CsvCells、S4 Grafana（回环+Basic；小残留 `GF_SECURITY_ALLOW_EMBEDDING:true`，full.yml:408）、A1/C1/C2、X1 preflight、X2 staging 钉死——全部修在位。

### 5.2 `CODE_AUDIT_REPORT_2026-10-05(.md/_FULL.md)` + `FIX_QUEUE_2026-10-05.md` ——**63/70 属实，7 条出入**
- P0×7（file:// 回显、fleet key 进 APK、edge-results 伪造、充值双记、端口门禁盲区、6b 裸奔、P0-5 降级裁定）**全部修复/定案在位**；P1×26 逐条验证，26 条中 24 属实、2 部分（见下）。
- 「存疑 18 条」抽 6 条复核：6/6 判定维持成立。
- **7 条出入**（均非虚报，是文档落后或改口未回写）：
  1. FIX_QUEUE「P1-11 docker.sock 改 :ro（1 行）」——实际知情后改口「不可 :ro」（devops.yml:81-83 注释裁定），队列未回写；
  2. FIX_QUEUE「唯一未本地做=架构批次 2」——实况 P0-2/P0-3 已于 2026-10-15 完成，真正剩余仅 OTA 验签+关 cleartext 两小项，队列严重低估完成度；
  3. FULL P1-6：signingConfigs 已加但 OTA 仍只验 SHA-256 无 APK 签名校验——「已闭环」错觉，**接真硬件前必须补**；
  4. FULL P1-14 镜像钉版本：仅 MinIO 两镜像钉 digest，postgres/mysql/nginx/redis 仍浮动；
  5. FULL P1-9/P2-13/视频深链：「面移除」消解（OfflineUploadQueue 类删除、staging 无 vision、消费端 video 页删除）未在文档标注，按字面会去找已不存在的代码；
  6. S4 残留 `GF_SECURITY_ALLOW_EMBEDDING:"true"` 未关（低危）；
  7. FULL P1-1 partialRefund 跨锁窗口：无幂等键，报告与队列均未收录进任何批次——**丢项风险**。

### 5.3 `AUDIT_LEDGER_2026-10-13.md`（唯一事实源）——**地位成立；16/16「已修」抽样全实；失实 3 条已本轮回写；仍开放核心 8 条**
- 已修抽样（AU-006/007/038/039/040/047/048/060/061/074/075/076/080/087/088/090/110/111/124/133）：16+ 条全部在 HEAD 在位，0 虚报。
- 失实 3 条（已随本报告在台账销账节回写）：**AU-002 已修未回写**（RefundRetryScheduler+V364，默认关）、**AU-077 已修未回写**（ab693d02）、**AU-134 批 6 状态过时**（28c9f99a/aa9d409a 已收口）。
- 仍开放（HEAD 复核确认）：**AU-063**（edge-results 转发失败仍回 200+无条件清兜底，`DeviceReportController.java:152-163`）、**AU-125**（`/ops/announcements/published` 无权限码，`AnnouncementController.java:36-39`）、AU-008（充值上限 fail-open，DC-6 待拍板）、AU-029（legacy 开门 HTTP 重试，面已收窄）、AU-112（补货域 0 设备 scope，随 DC-20）、AU-135（绑定入口环境闸门，待拍板）、AU-046（supportEmail 占位假域名，运营动作）、AU-058（消费者登录不查 INACTIVE，仍挂存疑）。

### 5.4 决策简报 / F2F3F4 / backlog ——**声明属实率高，4 处过时**
- `AUDIT_DECISION_BRIEF_2026-10-16.md`：§13 状态表抽查 6/6 实；**唯一过时**=批 6「剩余两项待拍板」已在 HEAD 落地（见 5.3）；§12 真待拍板剩余：C-admin 运行期实测、平台分账真收前置（CB-032 六问）、refund-retry 上线手册勾选、`frozen_cents` 死列、chargeMode 写死 ONLINE。
- `AUDIT_F2_F3_F4_DECISION_BRIEF` §8 状态表：抽查 3/3 实（分/天、SETTLED、lock_days=7、rerun 端点全在位）。
- `TRI_CLIENT_BACKLOG_2026-10-15.md`（含未提交 §六 集群就绪节）：**3 处失实**——L108 与 L44/45 自相矛盾（⑥/A7 状态未同步）、「Flyway 已到 V371 新号从 V372 起」已过时（HEAD 到 V386）、T2 行「净额=total−refunded」口径被 F-9（V384 冻结毛额）取代。提交前应先修这三处。
- `AUDIT_DEV_DRIFT_RECONCILIATION_2026-10-16.md`：「20 条移植落 dev」抽查 6/6 为 HEAD 祖先；DEV-07（C-admin 运行期实测）仍开放。
- `PROJECT_KNOWLEDGE.md` 抽查：§6/§6.2 全属实；**§6.1 一处过时**——「装机抵扣默认 10000 分」已被 V383 归 0 推翻（代码/迁移/常量三处确认），§6.1 文字待回写。

### 5.5 更旧文档定性

| 文档 | 定性 |
|------|------|
| `SECURITY_BEST_PRACTICES_REPORT.md`（2026-08-19） | **严重过时**，早于全部修复批次，扫描范围含已删除的 vision-service；仅历史参考，结论不可引用 |
| `three-end-full-audit-2026-09-15.md`、`audit-backlog-verification-2026-09-18.md` | 已自标过期快照，定性准确，无需动作 |
| `AUDIT_TRACK_*_2026-10-13.md`（14 份） | 台账的源报告：**缺陷定性仍有效**（93% 锚点复验成立），但修复状态列一律以台账+git 为准（DEV-03 教训）；TRACK_C 的 F2–F8 运行期实测仍是有效待办 |

---

## 6. 上线前剩余硬面（合并两路核查的收敛清单）

**代码（按序）**：AU-136 P0 → AU-137/AU-138（P1）→ AU-140~145、AU-148（P2 快改）→ AU-063、AU-125（台账挂账）→ OTA APK 签名校验 + 关 cleartext（FULL P1-6/7 残余，接真硬件前）→ partialRefund 幂等键（丢项风险）。
**拍板**：首期柜型（AU-147）、grafana.htpasswd 口径（AU-146）、注销合规路径（AU-149）、DC-6/9/10/11/14/18/20、CB-032 平台分账六问、refund-retry 上线手册。
**文档回写**：PROJECT_KNOWLEDGE §6.1 押金默认值、TRI_CLIENT_BACKLOG 三处、FIX_QUEUE 批量关闭（依据 5.2 核查表）。
**运营动作**：supportEmail 假域名（AU-046）、镜像钉版本（postgres/mysql/nginx/redis）。

## 7. 范围附注

- 8 路子代理覆盖：trade 资金域全量、会话/识别/视频/设备链、认证/RBAC（93 Controller 权限注解普查+15 Controller 深查）、admin-vue（83 视图+门禁实跑）、双小程序+shared 四包、infra 12 份 compose+nginx+CI+门禁 57 脚本+edge android、历史文档两路。
- 明确未覆盖：测试套件实跑、运行时探测/渗透、真机 UAT、商户/线长门户的逐端点 IDOR 全遍历（已抽 15 Controller）、jiangyi 真租户外部副作用面（只读红线）。

---

## 8. 终轮源码级复核增补（2026-10-18 下午，同 HEAD=`28c9f99a`）

> 本节为收口前的最后一轮独立复核，两条**确定性新发现**已登记台账 **AU-172 / AU-173**（编号唯一源=AUDIT_LEDGER）。另：审计执行期间 `origin/dev` 已前进至 `d5dee737`（+15 提交，含 A 案钱包统一 V387–V390），两条均已 `git diff` 核对**在远端仍未修**。

### AU-172（P0）`slot_template`/`slot_template_line` 主键映射错误——陈列模板三链全断

- `SlotTemplate.java:13-14` / `SlotTemplateLine.java:13`：`@TableId(type = IdType.AUTO)` 无 `value="id"`，MyBatis-Plus 默认按 `template_id`/`line_id` 拼列；`V368__slot_template.sql:18,25` DDL 主键列名是 **`id`**。
- 触发：`SlotTemplateService.java:120` insert / `:133` selectById / `:206` deleteById ⇒ `column "template_id" does not exist` 500。
- 单测 `SlotTemplateServiceTest` 纯 Mockito 不连库，拦不住。
- 全仓 `@TableId` 探针扫 225 张表，仅此 2 处真实失配（另 2 条告警人工核为误报：`device_http_credential`/`device_mqtt_credential` 的 `@TableId(type=INPUT) String deviceId` 对 V367 DDL 正确）。
- **修法**：两处改 `@TableId(value = "id", type = IdType.AUTO)` + 补一条真库 IT（Testcontainers 范本：`WalletRefIdempotencySqlIT`）。

### AU-173（P1）两个资金日检任务不取锁、不受启用开关管——上线手册的「保持关」关不住

- `platform-fund-daily` / `device-rental-daily` 是 registry 39 个任务中**唯二** action 无 `tryBegin` 的（全仓 40 处调用点逐一 A/B 对照；其余 37 个全部走 `UnpaidOrderScheduler.java:45` 范式）；XXL 具名 handler `runKey`（`ScheduledTaskXxlJobHandler.java:250`）只 `descriptor.action().run()`，自身不加锁。
- 后果：① 无 `job:*` 分布式锁 ⇒ 多副本双跑；② `scheduled_task.enabled=false` 被旁路 ⇒ 运营台停用按钮无效——`d8563c6b` 上线手册「资金定时任务上线当天保持关」**依赖的正是这个开关**；③ `last_run_at` 恒空（dev+UAT 两库实查旁证，unpaid-cancel 正常写入）。
- 叠加：`DeviceRentalService.runDailyCheck`（`:536` `@Transactional`）整方法事务内逐设备同步 HTTP 下发锁/解锁（`DeviceServiceClient.requestOpsCommand:133-145`）；`persistSalesLockState`（REQUIRED）加入外层事务，「MQTT / 通知在事务提交后执行」注释在该路径不成立。
- **修法**：两方法首行补 `tryBegin(key, 1800)`（返回 skipped 语义），锁柜循环移出事务；建议新增门禁 `check-scheduled-task-trybegin`（registry 每个 action 方法体必含 tryBegin）——负向验证：临时删 unpaid-cancel 的 tryBegin 看门禁红。

### 本轮实跑证据（补充 §0）

- `WalletRefIdempotencySqlIT` 单独实跑：**Tests run: 5, Failures: 0**（Testcontainers 真库）——钱包 DB 级幂等兜底（V379→V388 统一为 `uk_wallet_ledger_ref`）真实在位。
- 真消费者 token 运行时实测：`/api/v2/ops/announcements/published` 用 act=CONSUMER token 返回 **200**（list 返回 403 对照成立）——AU-125 越权从静态断言升级为运行时实证。
- dev 库停 V378 / UAT 库到 V390 / dev `announcement` 表 0 行。
