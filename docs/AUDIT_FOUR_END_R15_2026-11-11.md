# 四端审计 R15 报告（2026-11-11）

> **基线**：HEAD `21f55938`（R14 报告入库时点；工作树的 R14 修复在制改动——CabinetOpenLandingController/CouponsView 等——属并行修复会话，**一律不采信、不纳入**，全部结论出自 `git show HEAD:<path>` 已提交内容）。
> **方法**：4 路并行只读审计，全部换「从未被 R1–R14 扫过的机制层」：路 1 = CI workflow 本体（ci.yml 1093 行全文精读 + deploy-prod/sonar）+ wiring 算法；路 2 = 认证/token/刷新机制三端横切（含后端签发与 Cookie realm）；路 3 = 媒体上传/下载/存储链全横切；路 4 = infra 配置/依赖健康/e2e 脚本质量。
> **主审复核**：8 条 P2 全部亲验坐实（H1/H2/H4/M1/M2 为客观零命中/形态摘录，K1/K2/K3/L1 代码路径实锤）；P3×18 按四路证据一致性暂记成立。
> **总账**：**新发现 26 条 = P2×8 + P3×18**；P0/P1 为零。**去重确认**：四路均被要求先对齐 R1–R14 十五份报告的已知/豁免清单后仅报新增——G1(XFF) 为 R14 已知在修（路 1 已列入勿报）；无重复上报。

---

## 一、总览

| 路 | P2 | P3 | 机制层 |
|---|---|---|---|
| 路 1 CI workflow | H1（无 timeout）/ H2（CVE 审计假绿） | H3-H7×5 | 14 轮从未审过 ci.yml 本体 |
| 路 2 auth/token 横切 | K1（多 tab 刷新互踢）/ K2（admin 刷新瞬断=硬登出）/ K3（merchant 登出无服务端吊销） | K4-K7×4 | 三端过期/刷新/登出矩阵首次成表 |
| 路 3 媒体链 | L1（SKU 图 sha 去重 × release ⇒ 跨 SKU 悬空+对象误删） | L2-L4×3 | 上传端点校验口径差异表首次成表 |
| 路 4 infra/依赖/e2e | M1（gateway+redpanda 无 restart）/ M2（admin-uat 假断言进 CI） | M3-M8×6 | compose/依赖/e2e 脚本首次系统审 |

**关键定性**：①R15 的 8 条 P2 全部出自「从未被审的层」——不是老问题复发，而是覆盖面扩张的真实产出；②其中 K1/K2/K3（认证横切）与 L1（媒体数据丢失）是用户可直接感知的缺陷，H1/H2/M1 是基础设施韧性缺陷，M2 是 CI 内假信号；③四路各带「已核对无问题」清单（见各路原报告），为后续轮次划定新边界。

---

## 二、P2（8 条，全部主审亲验坐实）

### H1 [P2] ci.yml 五个 job 全部无 `timeout-minutes`（默认 360 分钟）
- **证据**：ci.yml（1093 行）`timeout-minutes` 零命中；对照 sonar.yml:32 有 `timeout-minutes: 90`。挂死场景（mvn 下载阻塞/Testcontainers 拉镜像/backend 起来后僵死）下单 job 烧 6 小时 runner，叠加 H4（无 concurrency）排队堵死。
- **修法**：逐 job 加 timeout（build/integration/e2e-h5≈60、mini-programs≈45、admin-artifacts≈20、deploy-prod≈15）。

### H2 [P2] `audit-frontend-deps.mjs`（CI mini-programs job 的 CVE 审计执行体）基础设施失败时**假绿**
- **证据**：`spawnSync` 返回的 `r.status` 从未检查；registry 故障时 `JSON.parse(r.stdout || '{}')` → 空 `{}` → `report.vulnerabilities || {}` → actionable 空 → **exit 0**。对照同族 `audit-backend-deps.mjs` 是 fail-closed（throw）。registry 抖动/代理故障时 CVE 门禁静默绿，「信号在骗读者」。
- **修法**：解析前判 `r.status`/`r.error`；要求 `report.vulnerabilities` 键必须存在，缺失即 exit 1。

### K1 [P2·横切] refresh 轮换对并发刷新零容忍——多 tab 同源并发续期必然互踢登出
- **证据**：后端 `AuthController:203-205` validate→**revoke**→refreshSession（第二个同代刷新必吃 REVOKED 401）；三端刷新单飞全是单 JS 上下文粒度（admin refreshPromise 实例字段 / mp 模块变量），跨 tab 零协调。**论据勘误（修复批核实）**：admin 生产是**共享 HttpOnly Cookie 会话**（client.ts:89-94 PROD 禁 Bearer 持久化、:124 fail-closed），dev 才是每 tab 独立 sessionStorage（A-P2-006）——原报告「token/expires 在共享 localStorage」表述有误，但「跨 tab 共享会话令牌」的本质成立且更强；多 tab 并发刷新败者 `clearSession+onUnauthorized()`，且 restore 失败还会 `logout()` 广播**所有** tab 强制登出。
- **修法**：①服务端轮换链（旧 jti 记 rotatedTo，并发续期照常签发）；或 ②前端跨 tab 刷新锁（admin 已有 BroadcastChannel auth-tab-sync 可扩展）。
- **置信度**：高（串行到达即必败，不依赖微妙时序；admin 双 tab 是常态用法）。

### K2 [P2·横切] admin 刷新「瞬时失败」（429/5xx/网络）= 硬登出+整页跳转——与 mp 端 R13 口径相反
- **证据**：shared-api `refreshSilently` catch-all 返回 false → 原请求分支无条件 `clearSession()+onUnauthorized()`（跳登录）；刷新端点 429（nginx auth_ratelimit 5r/s）/部署窗口 502/504 一律落此。mp 端 2026-11-11 已修成「429→REFRESH_RATE_LIMITED、5xx→保会话」，同一论证未同步 shared-api。
- **影响**：发版/网关抖动瞬间，admin 任一业务请求 401+一次刷新失败 ⇒ 会话清空整页跳登录（7 天宽限完全可续却被杀）；叠加 K1 restore 失败广播放大。
- **修法**：移植 mp 的失败分类——仅「刷新响应本身 401/403」清会话，429/5xx/网络保会话返回 false。

### K3 [P2·横切] merchant 登出只清本地，从不调服务端吊销——三端登出矩阵唯一缺口
- **证据**：merchant `mine.vue onLogout` 只 `clearSession()+reLaunch`；`endpoints.ts` AuthEndpoints **无 logout 定义**（admin/consumer 均先 POST /auth/logout 吊销再清本地）。登出后 JWT 服务端仍有效 30 分钟，且 7 天宽限内仍可 refresh 换新——与代码自证的「补货员共用手机」威胁模型直接冲突。
- **修法**：onLogout 增加一次性 `POST /auth/logout`（fire-and-forget，本地清理保持无条件先行）。

### L1 [P2·媒体链] SKU 图 sha256 去重共享 fileId × release 只数附件行 ⇒ 跨 SKU 悬空引用+对象物理删除
- **证据**：`uploadSkuImage` 同 sha 直接返回**既有行**（不插新行）⇒ 两个 SKU 共用同一 fileId；`releaseSkuImageIfUnused` 的存活判定只在 file_attachment 表内数同 path 行——去重后本来就只有 1 行，删除后必然判「无引用」→ `removeObject` 物理删除 ⇒ **另一 SKU 的图 404 且不可恢复**（R2-E1「文件已删 URL 还在」的反向变体）。sku_catalog 层零引用计数（grep 无 existsByImageUrl）。
- **触发**：SKU-B 编辑换图（或 CSV 同 URL）即触发；与 R12-E1 修复的 release 链组合后必然命中。
- **修法**：release 前加 `sku_catalog` 层存活判定（`existsByImageUrlAndSkuIdNot`）；或去重改为「共享 storagePath、各行独立」的行级引用计数。
- **置信度**：高（全链亲读；未实跑）。

### M1 [P2·infra] 生产栈 gateway 与 redpanda 无 restart 策略——宿主重启后业务入口 100% 不可用
- **证据**：base compose 中 postgres/redis/emqx/minio/prometheus/grafana 均有 `restart: unless-stopped`，唯 `gateway`（:157）与 `redpanda`（:109）无；production 层 AU-148 注释明言目标是「入口自动拉起」但入口本身不在清单。
- **影响**：宿主/Docker 重启后全站入口与 Kafka 不可用，需人工 up。
- **修法**：base 补两行 `restart: unless-stopped`；建议同批立 `check-compose-restart` 门禁（仿 stop-grace 先例）防再漂。

### M2 [P2·e2e] admin-uat.mjs 恒真/假 PASS 断言进 CI 零容忍强跑——「订单/设备/工作台渲染」只有假信号
- **证据**：A-QUAL-01 统计 4xx 资源数后**无条件 record PASS**（severe 只进日志）；A-05~A-08 对 `document.body.innerText` 匹配 `/订单管理|设备|运营工作台/`——侧栏菜单常驻文案恒真（`ui-assert.mjs` 头注释点名的假绿反模式，three-end 套件已修，旧 admin-uat 未迁移）；CI ci.yml:996 零容忍强跑。
- **影响**：内容区白屏/接口全 4xx 时这 5 条依旧全绿。
- **修法**：换 `adminPageState(page, {title})` 结构化断言；A-QUAL-01 改 `severe===0 ? PASS : FAIL`。

---

## 三、P3（18 条）

| 路 | # | 一句话 |
|---|---|---|
| 路 1 | H3 | deploy-prod.yml 唯一缺顶层 `permissions: contents: read`（GITHUB_TOKEN 最小化缺口） |
| 路 1 | H4 | ci.yml 无 concurrency 组——多会话并发推 dev 全量并行跑（成本/反馈延迟） |
| 路 1 | H5 | sonar.yml `${{ inputs.ref }}` 内插 shell（choice 输入服务端不校验取值域；写权限前置，低危必修形态） |
| 路 1 | H6 | check-audit-gates-wiring 三绕过形态：子串匹配「提及即接线」/scripts 不递归/CI 原文子串匹配 |
| 路 1 | H7 | deploy-prod 不校验目标 SHA 的 CI 结论（半自动档定位，建议级） |
| 路 2 | K4 | consumer `applyRefreshedToken` 在 uni.request success 回调内可抛 ⇒ 刷新单飞 Promise 永不落定 ⇒ 全应用刷新死锁+原请求悬挂（机制确定，触发依赖契约回归） |
| 路 2 | K5 | consumer 本地硬过期门先于刷新执行 ⇒ 7 天宽限对 bearer 永不可达（时钟前拨=硬清会话；MP 有静默重建、H5 有 cookie 双重缓解） |
| 路 2 | K6 | 下载通道 401 三端不对齐：仅 merchant 有刷新+重放（H3 修复），consumer/admin 下载 401 直接清会话（consumer 顺带抹 cookie） |
| 路 2 | K7 | Cookie realm 选路：非 ops 路径 consumer 优先 + 双 Cookie 靠 `Referer.contains('/admin')` 判域 ⇒ 身份错挂（读操作审计主体错）/ admin Cookie 轮换停滞 |
| 路 3 | L2 | 8 个图片上传端共用的 EXIF 剥离 `ImageIO.read` 无像素上限 ⇒ 5MB 解压炸弹单请求 OOM（多端共用 trade-service） |
| 路 3 | L3 | 消费者 PENDING 争议凭证无数量配额、全库无孤儿巡检 ×「图片永久保存」ILM ⇒ 无界存储放大 |
| 路 3 | L4 | warehouse-stocktake 上传缺 `assertLocalImageSize`（三同族页均有，独此页漏；后端 5MB 门在，仅体验差） |
| 路 4 | M3 | 16 compose 文件 76 service 全部无 logging/日志轮转（json-file 无上限，gateway 访问日志磁盘单调增长） |
| 路 4 | M4 | full.yml grafana 缺 `GRAFANA_PROMETHEUS_URL` 注入 ⇒ dev 监控栈 16 面板自 2026-10-13 起静默损坏（docker-up 默认不起 grafana 所以没人发现） |
| 路 4 | M5 | AU-189 数据卷/healthcheck 只进 base/apps——full.yml 的 prometheus/grafana/redpanda 数据仍易失（uat 切栈 `--force-recreate` 即丢） |
| 路 4 | M6 | nginx server 级安全头被带 `add_header Cache-Control` 的 location 继承规则吞掉 ⇒ /admin/* 响应缺 nosniff/X-Frame-Options/Referrer-Policy（/devops/grafana 是刻意摘除不算） |
| 路 4 | M7 | Spring Boot 3.5.16 OSS 支持已止（2026-06-30，铁律 22 现状确认）+ resilience4j DM 硬编码版本不走属性 + playwright 双版本并存 |
| 路 4 | M8 | 文档漂移：Redpanda 宿主端口两份文档写 9092，full 栈实况 19092（其余 17 条抽点全对） |

---

## 四、四路「已核对无问题」要点（详细清单见各路报告，划新审计边界）

- **路 1**：admin-artifacts job 设计扎实（checkout 钉 event sha 无迸发错位、字节级比对、零缓存无旧产物混入、node/pnpm 三处钉定一致）；无 paths-ignore（门禁改自己必触发 CI）；secrets 零 echo；全 workflow 无手动 cache 键错位；build 的迁移门禁/OSV fail-closed 正确；integration 的 Testcontainers 自备无 services 需求是正确设计。
- **路 2**：CSRF 双保险三端恒带；Cookie 属性（HttpOnly/Strict/Secure/Max-Age=exp+grace）正确；默认拒绝面无裸奔写路径；refresh 失败即吊销（fail-closed 正确向）；宽限吊销 TTL 含时钟漂移缓冲；JWT 算法/密钥/生产启动校验无弱密钥通道；2FA challenge scope 隔离；accountType 提升面逐层 fail-closed；三端存储键空间完全隔离。
- **路 3**：证据类上传四路径均有锁/幂等绑定（去重竞态仅 SKU 链=L1）；media 读取公开面为刻意设计且 nosniff 在位；presigned 仅 minio:// fail-closed；对象键不可预测不可覆盖；三端 `<image>` 全走鉴权下载本地渲染（token 不进 src）；Content-Disposition 全仓无注入；本地回退防穿越。
- **路 4**：compose 镜像版本 tag 全仓零漂移（仅 digest 钉 vs 浮动的维度差）；base/apps healthcheck 覆盖良好；depends_on 叠加语义处理正确；告警链 22 条规则指标对齐；UAT_MAX_FAIL 消费正确；e2e ps1 硬断言；pnpm-lock 与 overrides 落地一致；STARTUP/LOCAL_SETUP 其余 17 条抽点全对。

---

## 五、修复批建议排序

1. **安全/数据丢失（P2 内优先）**：G1(XFF 信任序)——修复已在工作树在制；L1（release 前加 sku_catalog 存活判定）；K3（merchant logout 加服务端吊销，~5 行）。
2. **认证体验一致性（P2）**：K1（轮换容忍或跨 tab 刷新锁，二选一）+ K2（admin 刷新失败分类，移植 mp 口径）——这两条同根（shared-api vs shared-uni 口径分叉），建议同批。
3. **基础设施韧性（P2）**：H1（timeout×5）+ M1（restart×2）+ H2（CVE 审计 fail-closed）+ M2（admin-uat 结构化断言）。
4. **P3 随批**：H3-H7、K4-K7、L2-L4、M3-M8；其中 K5/K6/K7 与 K1/K2/K3 同根（认证横切口径统一），建议一个「认证口径对齐批」一次收口；M4/M5 一行级配置随手修。
5. **流程**：R15 四路再次证明「从未扫过的层」仍有 8 条 P2 级产出——覆盖面账本（哪层审过/没审过）值得进 PROJECT_KNOWLEDGE 固化。

---

*报告落盘：2026-11-11。基线 HEAD=21f55938；主审亲验 8 条 P2 全部坐实，P3×18 按四路证据一致性暂记成立（关键 4 条已抽查）。R14 的 11 条由并行修复会话处置中（G1 修复已在工作树在制），本轮未重复上报、未改动任何代码。*

## 六、修复批回执（2026-11-11 同日修复，P2×8 + P3×17）

> 修复轮亲验再证两处报告偏差：①K1 论据「共享 localStorage」→ 实为共享 HttpOnly Cookie（见上勘误）；②**H2 比报告更严重**——pnpm 9 `--json` 输出是 npm6 老格式（`advisories`），根本没有 `vulnerabilities` 键，旧脚本 `report.vulnerabilities || {}` 恒空 ⇒ **该 CVE 门禁自落地起从未真正拦过任何漏洞**（实测 24 条 advisory 全部漏网）。

**P2（8/8 全修）**
- **L1**：`SkuCatalogMapper.existsByImageUrl` + `updateSku` 释放前档案层存活判定（含「imageUrl=null=不改」误触发路径一并堵死）；钉测 ×2（共享不释放/无引用才释放）。注：判定**不排除自己**——以释放时刻库态为准，clearExplicitlyBlankColumns 先行落库保证清空场景正确。
- **K3**：merchant endpoints 补 `logout` 定义 + `logoutMerchantSession()`（fire-and-forget 吊销，本地清理无条件先行）。
- **K2**：shared-api `refreshWithOutcome()` 专用刷新通道（ok/invalid/transient/no-session 四态）——仅刷新端点明确 401/403 才清会话跳登录；429/5xx/网络保会话。restore 同步改四态判定，断掉「瞬态失败→logout→广播全 tab」放大链。
- **K1**：`doRefreshWithCrossTabLock()`——Web Locks 把跨 tab 刷新**串行化**（后来者持前者新代 token 再刷必成功）；无 locks 环境降级单飞。
- **H1**：ci.yml 五 job（build 90/integration 60/admin-artifacts 30/mini-programs 60/e2e-h5 60）+ deploy-prod.yml deploy 20 补 timeout。
- **M1**：base compose gateway/redpanda + full.yml redpanda 补 `restart: unless-stopped`。
- **H2**：spawn 失败/空 stdout/缺结构一律 fail-closed + 解析兼容 `advisories` 老格式 + 存量基线棘轮（17 条 moderate+ 固化 KNOWN_BASELINE_GHSAS，新增必红，依赖升级只减不增）。三态验证：绿→砍基线注入红→还原绿逐字节一致。
- **M2**：A-05 改页头标题精确匹配 + `.stat-tile` 内容区断言；A-06/07/08 换 `adminPageState` 结构化断言（标题/表格在位/非 loading/行或水合空态）；A-QUAL-01 改 `severe===0 ? PASS : FAIL`。

**P3（17 修 + 3 登记）**
- **H3** deploy-prod 补顶层 `permissions: contents: read`；**H4** ci.yml 补 concurrency（cancel-in-progress: true，deploy-prod 保持不取消）；**H5** sonar.yml inputs 改 env 传递（去 `${{ }}` shell 内插）；**H6** wiring 门禁三收紧（scripts 递归扫描/「提及即接线」→可执行接线+可达闭包共享/规则 3 支持子目录），负向验证注入红复绿。
- **K4** refreshTokenSilently 两处 `applyRefreshedToken` 包 try/catch 收口 reject（防单飞 Promise 永不落定死锁）；**K5** consumer 本地过期门改「过期票不用于业务请求但保留会话」（getConsumerToken 返空串不清会话 + `getRefreshToken` 原始票据通道）——C3 语义保留、7 天宽限首次可达；**K6** consumer/admin 下载 401 对齐 merchant（静默刷新重放一次，明确拒绝才登出）；**K7** 判域子串精确化（"merchant"→"/merchant"），双 Cookie 各自续期立项（见下）。
- **L2** `ImageMetadataStripper` 解码前 header 探测尺寸（MAX 8000px/32MP），超限 `OversizedImageException` → 400 拒收；钉测 ×2（窄图 KB 级构造）；**L3** 争议凭证上传加单用户 PENDING 配额（20 张）；**L4** warehouse-stocktake 补 `assertLocalImageSize`。
- **M3** base×8 + apps×4 常驻服务 `x-logging` 锚点统一 json-file 轮转（50m×5）；**M4** full.yml grafana 补 `GRAFANA_PROMETHEUS_URL`；**M5** full.yml prometheus/grafana/redpanda 数据卷 + prometheus/grafana 顺带补 restart；**M6** nginx 三处带 Cache-Control 的 location 内联补齐安全头（继承规则）；**M8** 三文档 Redpanda 端口勘误（full 栈 19092）。
- **登记不修（3+2）**：H7（deploy-prod 校验目标 SHA CI 结论——建议级，半自动档已有人工显式兜底）；K7 双 Cookie 各自续期（需 refresh 端点 realm 契约，跨端变更）；L3 孤儿凭证超龄巡检（保留期属产品决策）；M3 full.yml 本地栈 logging（磁盘增长风险在生产路径，本地登记）；M7（EOL/双版本现状确认，无代码动作）。

**验证**：trade-service 定向测试 32/32 绿（含新钉测 4 条）；consumer 195/195、merchant 278/278、admin 140/140 vitest 全绿；shared-api/shared-uni/shared-types 产物重烤；wiring 门禁基线绿 + 负向注入红；CVE 审计脚本三态验证；compose base/stack config 校验（redpanda-init 报错为改动前既有 profile 状态）。

*修复批落盘：2026-11-11 同日。P2×8 全修、P3×17 修 + 5 条登记。修复轮勘误两处：K1 论据（localStorage→HttpOnly Cookie）、H2 升级（门禁从未真正工作，advisories 格式恒空）。*
