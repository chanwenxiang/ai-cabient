# 附录：8 路子代理逐条核查明细（AUDIT_THREE_END_FULL_2026-10-18）

> **性质**：本文件是 2026-10-18 三端全量复审（HEAD=`28c9f99a`）8 路子代理的**原始明细输出**，
> 主报告 `docs/AUDIT_THREE_END_FULL_2026-10-18.md` 只收结论，明细在此留存。
> **证据等级**：附录内「已核实健康面/声明核查」为子代理逐行核查口径，未再经主审全量复验；
> 引用其中结论时以主报告 + 台账（AU-136~173）为准。终轮增补（AU-172/AU-173）不在本附录内——
> 它们产出于 8 路审计收口之后，见主报告 §2/§8。
> **本审计口径**：未跑测试套件、未做运行时探测/渗透（实跑验证属修复批：终轮已补 2030 单测 +
> `WalletRefIdempotencySqlIT` 5/5 + 消费者 token 实测，见主报告 §8）。

---

## 附录 A：资金域（trade-service）

**既有声明核查（15 条全属实，含 1 条活文档过时）**

| 来源声明 | HEAD 实况 | 结论 |
|---|---|---|
| B1 F-1 → V379 三表 partial unique | `V379__wallet_ledger_unique_ref.sql` 全量在位；应用层 findByRef→FOR UPDATE 竞态窗口靠唯一索引兜底 | 属实已修 |
| B1 F-5 已修 | `MerchantWalletService.debitedCentsByRef:196-206` 在位；幂等跳过返回 0 | 属实已修 |
| B1 F-6 判定暂不修 | 整除点在 `MerchantDailyIncomeNotifyScheduler.java:350` | 属实（判定一致） |
| B1 F-7 已修 | 两处对称「跳过负扣即抛异常回滚」（`CommissionClawbackService.java:160-167,226-232`） | 属实已修 |
| B1 F-8 已修 | 冲回基数=发佣行快照比例（`:128-146`） | 属实已修 |
| B1 F-9 已修（V384） | `CabinetOrder.java:41`、`CommissionBaseCalc.java:39-47`、`freezePaidAmount`（`:466-471`） | 属实已修 |
| B1 F-10 已修 | `PaymentOperationMapper.java:120-124` 含 PREAUTH_CAPTURE | 属实已修 |
| FIX_QUEUE P0-4 充值双记 | `applyBalanceCharge:416-445` 终段只记 remainDebit，无「CHARGE:PREAUTH」块；三场景测试钉住 | 属实修在位 |
| FIX_QUEUE 批次4 费率静默归零 | 三处改「缺失/停用跳过重算并留痕」 | 属实修在位 |
| FIX_QUEUE paidAt 静默回退 | 仍只有 log.error（`OpsSessionOrderQueryService.java:553`）；队列本就列「随迭代」 | 属实未修（无虚报） |
| FIX_QUEUE P0-5 裁定 | `BalanceLedgerService.java:41-50` 注释明示设计意图 | 属实（判定成立） |
| PK §6.1 装机抵扣默认 10000 分 | V383 已归 0（迁移+常量 `:143`+两处 null 处理） | **声明有误（已过时，已订正）** |
| PK §6.2 分账状态机+rate=0 三道闸 | `RevenueSplitService.java:117-125,509-524`、`WeChatProfitSharingService.java:117-126` | 属实 |
| PK §6.1 金额全链路「分」 | 六域抽查全部 Cents 整数 | 属实 |
| PK §6 分布式锁前缀 | 资金写路径逐一确认全部锁内 | 属实 |

**已核实健康面（要点）**：F1 净额三件套（`ConsumerPreauthService.java:271-276,289-293`、`OrderPaymentService.java:122,134,1106-1110`）；退款≤净额（`:592-597`）、capture≤usableHold（`:257-261`）；微信回调验签→±300s→nonce 防重→mchid 匹配（`WeChatPayNotifyService.java:53-131`）、入账 PAID 守卫+FOR UPDATE+稳定幂等键（`PaymentService.java:1044-1078`）；订单退款三类痕迹 REQUIRES_NEW（`:281,309,732`）、重试先 queryRefund、ABNORMAL 不自动重发（`:855-885`）；提现 markPaying 重试再冻结、PAYING 兜底 MOCK 自动失败/真实转人工（F3）、手续费 6‰+四维限额+T+1 不提；钱包 `*IfAbsent` 族+V379 库级兜底；B2 三段式+out_order_no 先落库；佣金发佣/冲回同源；流量费分/天+0 费不留行+账龄锁柜 lock_days=7；押金 settle 分支互斥完备；结算单幂等重建+排除 VOIDED/REVERSED；FundBill 实结优先/尾差归最大 gross；负向余额仅 3 处显式 AllowNegative。

## 附录 B：会话/识别/设备链

**既有声明核查（15 条）**：JIANGYI_MIGRATION_AUDIT P1（将邑商品库恒 400，`SkuJiangyiLinkService.java:112-115`）、P2（采集锁文案，`shared-uni/format.ts:163-165`）属实已修；AU-038（long 累加夹取）、AU-039（置信度 0~1+拒 NaN）、AU-040（录像归属比对）、AU-060（超时上报归属）、AU-061（tracker orderNo 白名单）属实已修；**AU-062 属实未修**（`AdminJiangyiController.java:76-77,92-93` 仍挂 ops:device:list，待 DC-11 拍板）；**AU-063 属实未修**（`DeviceReportController.java:152-163`）；AU-029 属实未修（影响面已被批 1 收窄）；批 6-F 标签层刻意全留属实；PK §6 三条链路声明（识别只有端侧、将邑停 RECOGNIZING、旁路禁 setState——全仓 `.setState(` 主源 5 处均为初态赋值/transition 内部）属实；`@EnableKafka` 挂 notify.async-enabled 属实；jiangyi-video 只靠桶规则无代码删除能力属实。

**已核实健康面（要点）**：状态机迁移表+transition 唯一写点带校验（`SessionService.java:483-500`）；设备级开门锁+幂等开门键防重放换绑（`:471-476,544-557`）；过期四清扫全部先抢锁+行锁重查（`SessionExpireService.java`）；FAILED 冻结预授权 1h 清扫；结算幂等三层（状态机→settle 锁内查重→落单锁内查重），同一 session 重复 edge-results 不会二次扣款；批 4 锁序修复落地；need_review/低置信一律 DISPUTED 不扣款；edge-results deviceId 必填+归属强校验先于状态判定（防状态泄露）；InternalApiAuthInterceptor 恒时比较+CIDR+fail-closed 503；LiveCart 客户端价「仅展示不参与计价」护栏注释；gateway 设备 JWT fail-closed+token_version 吊销；WS 握手 token↔identifier 绑定；生产启动期 fail-loud 三件套；OSS STS 收窄 PutObject 至设备级子目录+读路径服务端预签夹取；DoorEventDeduplicator 转发成功才 mark+滑动窗口限流；device-service pom 无 DB 驱动（桥边界成立）；MQTT clientId HOSTNAME 稳定后缀+心跳/门事件以 topic 为身份源拒 body 顶替；手动 ACK+失败重投；运维命令双闸白名单+merchantScope 归属校验；RETIRE 即拒上报；视频读路径 streamTo 拒 http(s)/file://+Range 支持+列表 N+1 已收口；AU-040 分片 split limit=-1 保留失败片；watchdog 三兜底失败不出队；将邑开门单次下发不重试（决策留痕）。

## 附录 C：认证/RBAC/后台 API

**既有声明核查（13 条）**：S1 属实已修（V290+ACL 命名空间+deny all）；S2 属实已修（`AuthInterceptor.java:37-38` 注释留痕）；S3 属实已修（CsvCells 统一）；AU-047 属实已修（C16 守卫 `OpsRbacService.java:463-467,498,524`）；AU-048 属实已修（审计默认开 `SystemConfigService.java:729`；键白名单部分未修）；AU-089 属实已修（门户面 `MerchantPortalGuard.java:33-46`；ops 全局语义刻意保留）；**AU-097/125 属实未修**（`AnnouncementController.java:36-39` 无权限码——终轮已升级运行时实证：真消费者 token 200，见主报告 §8）；**AU-058 属实未修**（`AuthService.java:98-124` 消费者登录不查 INACTIVE）；AU-010 属实已修（fleet key 移出+一机一密路径绑定 403）；AU-060 已过时（DeviceReportController 面已重构进 /internal/v1/sessions/{id}/open-failed）；CB-048 绑定/免密语义属实（409 抢绑+V301 唯一索引兜底竞态）；CB-050 设备控制/盘点/改密属实（perm 720 双闸、OPEN_DOOR 400 拒绝）；orgs 全树放行=用户拍板的前端过滤，属实（刻意设计）。

**已核实健康面（要点）**：/api/v2/** 默认拒绝+白名单仅 11 条（`WebConfig.java:55-77`）；Cookie CSRF 双保险+双 realm 防消费者会话串后台；JWT challenge scope 隔离+jti 吊销黑名单含宽限期重放防护+boot claim 重启全员失效；admin/商户登录无条件图形验证码+失败 2 次升级+重置=验证码+短信+PasswordPolicy；验证码一次性 300s；登录节流 5 次/10min 未知号码也计数防枚举；短信 60s 冷却/时 5 条/错 5 次作废/日志脱敏/mock 万能码双闸；InternalApiKey 恒时比较+未配 fail-closed；/internal/** 拦截全覆盖零逃逸+DevMock prod 不装配；ProductionStartupValidator 拒 dev 内部密钥/空 CIDR；RBAC 注解普查 93 控制器 63 带注解、其余 30 个均为消费者自域/公开/内部/回调面，抽样写端点全有权限码；scheduled-task enabled 端点有 ops:task:edit（⚠️ 但 enabled 开关对 AU-173 两任务被旁路，见主报告）；商户 IDOR 链（requireMerchantAccess+requireDeviceAccess+PortalGuard+盘点凭证 refType/refId 双匹配）；仓库盘点归属授权；文件上传类型白名单/5MB/重编码剥元数据/UUID 键/ops 读白名单宁窄勿宽；审计 detail 512 截断+DATA_UPDATE 留痕；GlobalExceptionHandler 通用 500 脱敏+prod 关 springdoc；2FA 开关均须 TOTP+后备码哈希一次性恒时比较；ops-avatars 有 requireOperator。

## 附录 D：admin-vue

**既有声明核查（10 条）**：A1（RechargeListView 契约复用）✅已修；dict-runtime 裸路径收口 AdminEndpoints ✅；`ops:rbac:assign:device` 后端已 OR 双轨 ✅；MerchantWithdrawView 手写 interface 债仍开放（与文档定位一致）；A6-4 15 处 yuan() 收敛 ✅ 且旧 15 处无回归（**但当日新增 AU-143 一处违例**）；A6-5 toLocaleString 4/4 在位；debt-tracker D9（2FA sessionStorage）/D10（RBAC 只删不写）/D3（softFallback）/D5（money-ui-contracts）✅ 全实。

**已核实健康面（要点）**：JWT 只入 sessionStorage+生产构建拒绝 Bearer 持久化（fail-closed）+token-storage 门禁实跑 OK；权限不从 localStorage 恢复+restore 并发去重+soft-expired 刷新失败即登出；路由守卫 fail-closed（未登记路径一律 forbidden）；双 toast 修复在位；401 静默刷新仅一次/403 不清会话；**v-html 全仓 0 处**、window.open 全带 noopener、Grafana iframe 只取后端下发 URL 的 path；CSV 公式注入防护+8 例回归测试+下载文件名去穿越；端点纪律全绿（390 条前端引用全命中后端 618 路由）；¥¥ 修复无回归；`ops:session:upload` 前端零残留；static/admin 产物晚于最后 src 提交（一致）；console.log 零残留+esbuild pure 剔除；资金表单二次确认+loading finally+yuanToCents 防浮点；深链按 name#path#dictEpoch key 防 stale；vue 3.5.40/element-plus 2.14.3/vite 6.4.3、sourcemap 未开；高德 key 走运行时 runtime-config.json 不入产物。

## 附录 E：双小程序 + shared

**既有声明核查（16 条）**：A1 withQuery ✅（残留类型签名见 P3-14）；⑥删记住手机号 ✅（启动清理时机见 P3-13）；A6-7 privacy-consent ✅；测试计数 consumer 138 ✅ / merchant 159 ❌ vs 声明 176（**数字不符，登记待核**）；consumer debt C1/C2/C3/C13/C9 全相符；merchant debt M1/M2/M9/M11 全相符；THREE_END C1（重试分类）/C2（轮询 10min cap）✅ 修在位；shared-dict dist 同步 ✅；shared-rbac dist 未复发 ✅；shared-types 无漂移（CB-050 全部新端点已生成）✅；消费端 video 页已删净 ✅；defineModel 禁令在位 ✅。

**顺带评估**：`sync-consumer-mp-api.mjs` 未提交改动（dev 探测局域网优先）**合理**——生产构建走 validate-miniapp-env HTTPS 硬门不经此脚本；小瑕疵：`forceLan` 变量被短路成死逻辑、`MP_API_USE_LAN` 语义从「=1 才生效」变为「非 0 即生效」。

**已核实健康面（要点）**：请求层 401 单飞刷新+仅传输层重试+H5 隐私拦截；双端 toLocaleString 0 命中全走 fmtMoney/centsToYuanDigits；时间统一东八区无 Intl 依赖（真机安全）；deviceId 白名单三处一致+CAB-* 拒识有测试钉住；充值链 fail-closed（mock 须后端显式声明+生产包强制关+体验按钮双门）；支付宝跳转域名精确白名单+表单 DOM 重建防 innerHTML；幂等键体系（充值/退款金额变化换键+开门 attempt）；一键登录 phoneCode 服务端换取；会话轮询防堆积+孤儿会话宽限接管+跨柜并发拦截；merchant 设备控制仅 LOCK/UNLOCK/REBOOT+确认弹窗+busy 门闩（OPEN_DOOR 双端 0 命中）；商户微信绑定 fire-and-forget 不阻断；改密带旧密码验证；orgs 切换如实标注「前端过滤、不影响账号权限」；提现三重校验+10s 跟单刷新；pages.json consumer 22/22、merchant 33/33 双向零缺；console.log 生产 0+硬编码密钥/IP 0+生产 API HTTPS 硬门+AppID 构建期 fail-closed；XSS 面 v-html/rich-text/innerHTML 0 命中；storage 键集中管理+登出清理彻底；401 全局防抖 reLaunch+导航守卫。

## 附录 F：infra / CI / edge

**既有声明核查（9 条）**：P0-6 端口门禁 ✅ 已修（实跑 OK：45 条/12 文件全回环，47→45 为时点漂移非回归；主审终轮已独立复跑 `node scripts/check-compose-ports.mjs` → 绿，源码 `:54` 注释「审计 P0-6：注释/空行不再截断块」在位）；P0-6b feishu-relay/alertmanager ✅ 已回环；P2-11 EMQX 端口策略 ✅；e2e 锁 TOCTOU ✅；check:ps1-bom ✅ 在链；A2 jiangyi 进 CI ✅（含防静默跳过守卫）；A5 redis 卷 ✅；A3 prod-server.yml 不在 git ✅ 定性属实（prod-managed.yml:20-22 已列为上线前置）；AU-074 check-env 六键强口令 ✅。**PRODUCTION_TOPOLOGY（未提交新文档）file:line 抽查 6 处全对上，可信度高。**

**已核实健康面（要点）**：12 份 compose 45 条端口映射全回环、唯一全网卡=gateway:80 带 EXPOSE-ALLOW 双留痕；生产 fail-loud env `:?` 全套（AUTH_COOKIE_SECURE/PUBLIC_BASE_URL/REDIS_PASSWORD/CIDRS/XXL/微信开关/MQTT 三件/JIANGYI_JWT_SECRET）；资金 mock 生产钉死；EMQX 5.8.6 password_based+bootstrap dev/prod 分离+NO_MATCH deny+ACL 按 clientId 隔离；镜像 0 个 latest+minio 双镜像钉 digest；Grafana 禁弱默认/禁注册/匿名仅 Viewer；Redis AOF+卷+密码；CI permissions 收敛+Testcontainers 零发现即红护栏+production env 模板门禁+secret scan+无日期炸弹（expiresOn 扫描 0 命中）；edge allowBackup=false+exported 仅 MainActivity+release 缺签名 fail-fast+MQTT dev 默认值决策注释在位+SecretStorage fail-closed+OTA 强制 64hex SHA-256+ApkSignatureVerifier 比对 fail-closed+双摄开关删除无残留；Dockerfile 非 root+多阶段+时区钉死；nginx /actuator 除 health/info 全 403+/internal/ 403+auth 端点严格限流+防缓存策略+WS map；deploy-prod.sh 脏工作区拒部署+只动 trade 不碰 postgres+三重部署后验证；门禁体系 57 脚本+聚合链 ~50 项+wiring 防失联；部署文档口径一致（PRODUCTION_TOPOLOGY「目标形态≠现状」、GO_LIVE 已打历史留存标、CLUSTER_READINESS 改口带源码锚点）。

## 附录 G/H：历史文档核查补充明细

**第一路补充**（主报告 §5.1–5.2 的数据来源）：CODE_AUDIT_REPORT_2026-10-05 P2×21 逐条验证 20 直 1 间接全属实（P2-9/P2-10 属实未修按计划）；P3 抽 8：bcrypt ✅、alipay-test 删 ✅、CI permissions ✅、镜像钉版本部分 ✗、视频深链面移除、admin_phone 未修（与「随迭代」申报一致）、EdgeRuntimeConfig 已修、税号校验未逐验。_FULL P1×17 逐条：P1-1 未见修复（低概率窗口仍在）、P1-5 InTransit 无幂等属实未修（原报告自认不可触发）、P1-6 部分（签名✓/OTA 验签✗）、P1-7 cleartext 属实未修（接真前）、P1-9 面移除、P1-10 修法更优（容器姿势）、P1-11 知情改口未回写、P1-14 部分，其余 9 条属实已修。FIX_QUEUE 批次 1：8/9 修在位；批次 3 五连全修；批次 4 大部分已修，仍未修=InTransit 幂等、WarehouseMonthlyCloseMath（已重构为 Acc 累加器形态，原缺陷不复现）、镜像钉版本剩余、check-android-tests-wired 基线（未见）。§11.3 挂账：F1/S1 ✅、V4 MinIO `mc anonymous set none` 两处在位 ✅、V1 RECON 默认 true+production 钉死 ✅。

**第二路补充**（主报告 §5.3–5.4 的数据来源）：台账非「已回归/判非缺陷」约 40 行中逐条核验 31 行关键条目，失实 3 条（AU-002/077/134）、其余处置标注相符；已回归抽样 16/16 全实（AU-006/007/038/039/040/047/048/060/061/074/075/076/080/087/088/090/110/111/124/133）。决策简报逐项：DC-26（V375 guard）/DC-27（V385）/DC-29（V377 等）已落地；§13#4 V383 在位（chargeMode 写死 ONLINE 仍无拍板，简报如实标注）；§13#5 C-admin 驱动器只在 origin 分支仍开放；§15 UPLOAD_STUCK 全撤（`fd583f18`+V386）在位 ✅。backlog 未提交 §六（P-1~P-5）抽验与 HEAD 一致仍开放（Inventory 无 @Version、槽位无 CHECK、V359 无唯一索引），标注「立项不实施」如实。
