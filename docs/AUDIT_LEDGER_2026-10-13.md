# 上线前全量审计 · 问题总台账（2026-10-13）

> **唯一事实源**。基线 commit `9911a2ee`（dev 最新；工作分支 `worktree-audit-pre-launch`）。
> 纪律：不采信自报绿——每条"已修"必须 file:line + 实跑证据；"判非缺陷"同样必须给证据。
> 等级：P0=当前即资损/安全事件 ｜ P1=接真前必修或现存实质功能缺陷 ｜ P2=质量 ｜ P3=债务。
> 处置：待修 / 修复中 / 已回归 / 判非缺陷 / 挂起(阻塞原因) / 待拍板。

## 环境基线（审计时点）

- 服务器 `http://120.25.246.179`（HTTP 过渡期，`AUTH_COOKIE_SECURE=false` 阶段模式）
- `WECHAT_PAY_ENABLED=false`、`CHECKOUT_BALANCE_ONLY=true`（余额-only）
- 无正式小程序号；识别=将邑端侧（模拟器测，真硬件未接）
- 生产库账号仅剩 user_id=0（系统）+ 100000012（超管）；演示 SKU/商户种子部分已手工清理
- 微信开发者工具自动化：**已探明可用**（`D:\devTools\微信web开发者工具\wechatidecli.cmd`，islogin=true，IDE HTTP 服务 127.0.0.1:22277；旧 cli.bat reg.exe 问题绕开）
- 双小程序 dev 产物已构建并指向服务器（`clients/{consumer,merchant}-mp/dist/dev/mp-weixin`，touristappid、urlCheck=false，vendor.js 含 120.25.246.179 已验证）
- 服务器只读探测：`/admin/` 200、`/api/v2/auth/captcha` 200、`/api/v2/public/consumer-config` code=0（mockEnabled=false、wechatPayLive=false、alipayPayLive=false、充值上限 5000 元）
- ⚠️ 发现：`public.consumer-config` 透出 `supportEmail=ops@aicabinet.local`（占位假域名，用户可见）——待 Track B-8/F 判级登记

## 台账

| ID | 轨道 | 等级 | 域/文件 | 发现 | 证据 | 与存量关系 | 处置 | 修复提交 | 回归证据 |
|----|------|------|---------|------|------|-----------|------|----------|----------|
| AU-001 | A | — | FIX_QUEUE 批次1–4 | 复扫定案：原 P0 全部修复/消解；未修=P0-2 残余(P1)+P1-6 余项(P2×2)+P3×4 | `docs/AUDIT_TRACK_A_FIXQUEUE_RESIDUAL_2026-10-13.md` 逐条 file:line | 复验存量 | 已回归(复扫)；未修项另立 AU-010~016 | — | — |
| AU-002 | A/D | P1 | trade 退款链 | G1 退款提交补偿 job 仍缺（Java 侧 refundRetry 零命中）；接支付腿前置，余额-only 档无资损路径 | grep 零命中 + TRACK_A 报告 §G1 最小 job 设计 | GAP_SCAN G1 确认 | 待修(接真前) | — | — |
| AU-003 | E | — | 商户→将邑回调 | ~~P1候选~~ **更正：判非缺陷**——§5.4.2–5.4.7 未实现与 2026-10-09「模式一」拍板一致（只接第四章）；5.4.10 confirmActivate 另立 AU-009 | `docs/AUDIT_TRACK_E_JIANGYI_INTERFACE_GAP_2026-10-13.md` E-2 定案 | 推翻原候选 | 判非缺陷 | — | — |
| AU-004 | F | P1 | Flyway 版本迁移 | V2/V11/V25/V54/V56/V62 内嵌演示种子：全新生产库重建即再长出演示数据 | .workbuddy/memory/2026-10-13.md | 新增(部署实证) | 待修(守卫迁移删 demo 或种子外提) | — | — |
| AU-005 | B | — | B-1/B-2/B-3 | 第 1 波三域接口契约审计完成并落盘 | TRACK_B1/B2/B3 三份报告 | 新增 | 已回归(报告落盘) | — | — |
| AU-006 | B-1 | **P1** | MerchantWithdrawService:422-425 / LineWithdrawService:292-294 | 提现幂等仅按全局 requestNo，不校验归属/金额→跨租户返回他人提现 DTO+本方请求被静默吞 | 主会话读码复核属实；B1 报告 F-01 | 新增 | **待修**（幂等命中加归属+金额比对；负向测试先行） | — | — |
| AU-007 | B-1 | **P1** | MerchantWithdrawService:380-399 / LineWithdrawService:225-244 | cancelFailed 锁内用锁前陈旧快照判 FAILED→与 payout 并发可对 PAID 单重复 releaseFrozen，余额虚增（可复现资损） | 主会话读码复核属实；B1 报告 F-02 | 新增 | **待修**（锁内重查+先锁后事务；负向测试先行） | — | — |
| AU-008 | B-1/C | P2 | 资金面存量 | F-03/F-03b/F-04/F-05/F-06 五条 P2（充值无 @Max 且上限可被配置失效、消费者调账无限幅、PlatformFund 裸 Map+负数充值、异常 message 透 500、调账无幂等键） | B1 报告 ② | 新增(部分与 B-2 Q7/B-3 同族) | 待修(随批) | — | — |
| AU-009 | E | P1 | 将邑 5.4.10 | confirmActivate 未接：将邑侧扩机/激活流程会被卡（上线可运营性） | TRACK_E 报告 E-2 | 新增 | 待拍板兜底方式后修 | — | — |
| AU-010 | A | P1 | edge/android-app/build.gradle.kts:32 | fleet 共享默认 INTERNAL_API_KEY 仍内嵌 release flavor（P0-2 残余）；逐设备内部凭据未实施 | FIX_QUEUE 复扫表 P0-2 行 | 存量降级续项 | 待修(接真硬件前) | — | — |
| AU-011 | A | P2 | OTA | 无 versionCode 防回滚（服务端+端侧 grep 零命中） | 同上 P1-6 行 | 存量 | 待排期 | — | — |
| AU-012 | A | P2 | AndroidManifest.xml:19 | `usesCleartextTraffic="true"` 且全仓无 networkSecurityConfig | 同上 | 存量 | 待排期 | — | — |
| AU-013~016 | A | P3 | admin yuan()×13 / paidAt 回退 / Redpanda SASL / devops docker.sock | 见 FIX_QUEUE 复扫表未修项汇总 | 同上 | 存量 | 登记(延后) | — | — |
| AU-017~027 | B-1 | P2/P3 | 资金面 | F-05/F-07~F-12（泄漏透传、limit 未夹、DTO 越面、parseLong 脏 400、403/404 oracle、渠道报文透、RBAC 码语义） | B1 报告 ② | 新增 | 登记(随批修) | — | — |
| AU-028 | B-2 | P2 | 会话超时 | Q1 失败/取消后门的物理状态无人管=免单窗口（迟到事件静默 no-op 无告警） | B2 报告 ③ | 新增 | 待拍板(是否补锁门指令/告警) | — | — |
| AU-029 | B-2 | P2 | legacy 开门链 | Q2 HTTP 重试→重复 MQTT OPEN（jiangyi 链已不重试，本链未对齐） | B2 报告 ③ | 新增 | 待修(随批) | — | — |
| AU-030~037 | B-2 | P3 | 会话/设备面 | Q3~Q10（CAS 语义漂移、事务内锁族、WAITING_UPLOAD 死态、403/404 不一致、异常透传、@Valid 级联缺失、inflight 契约漂移、幂等竞态 500） | B2 报告 ③ | 新增 | 登记(延后) | — | — |
| AU-038 | B-3 | P2 | SettlementOrderSupport:89 | quantity 无上界→int 溢出可造负金额单（chargeOrder 负额跳过扣款标 PAID）；**建议接真前必修** | B3 报告 A 行复现路径 | 新增 | 待修(入口上界+long) | — | — |
| AU-039 | B-3 | P2 | VisionResultIngestService:129-155 | 置信度未 clamp，99.0 可绕过低置信转人工；建议接真前必修 | B3 报告 B 行 | 新增 | 待修(随批) | — | — |
| AU-040 | B-3 | P2 | JiangyiInternalController:161 | order-video-report 不校验 orderNo 归属上报设备→可覆写他人录像行；建议接真前必修（一行比对） | B3 报告 C 行 | 新增 | 待修(随批) | — | — |
| AU-041~045 | B-3 | P3 | 上报/告警/争议面 | D/E（anomaly 条数无界+dedup 抢占+事务内发 webhook、ops-alert 无长度上界/CRLF）、F（/retry 幂等键格式永不命中）、G（事务内抢锁残留）、I（detail 透 previewUrl/裸 videoUri 口径不一） | B3 报告 ③ | 新增 | 登记(随批/延后) | — | — |
| AU-046 | B/C | 待定 | consumer-config | `supportEmail=ops@aicabinet.local` 占位假域名透出到 C 端 | 环境基线节 curl 实测 | 新增 | 待 Track B-8/F 判级 | — | — |

## 待用户拍板清单（执行中滚动）

| ID | 事项 | 背景 |
|----|------|------|
| ~~DC-1~~ | ~~生产/staging 共用将邑租户 426 凭据~~ | **已撤销（Track E-3 证伪：无法从仓内证据证实共用）** |
| DC-2 | 生产环境是否允许开门类实测 | 建议开门类只在本地/staging |
| DC-3 | 低置信识别 DISPUTED 先不扣款 vs 旧系统 NOT_FALL 自动退款 | 业务口径拍板（Track D-3） |
| DC-4 | getArtificialCheckByIdentifier 恒 false（只传异常视频）产品口径确认 | Track E-1 |
| DC-5 | 商户大额调账 `adjust:185-192` 是「事后登记」还是应「先审后动钱」 | Track B-1 存疑①：若预期先审后动钱则属 P0 级门禁缺失 |
| DC-6 | `RECHARGE_MAX_CENTS/BALANCE_REFUND_MAX_CENTS ≤0=关闭上限` 是运营开关还是 fail-open | Track B-1 存疑②，决定 AU-018 判级 |
| DC-7 | 将邑正常单视频回拉（R2）是否上线阻塞 | Track E-1：现仅异常视频链，正常单录像缺来源 |
| DC-8 | open-failed 内网端点是否需要按设备 token 限域 | Track B-2 存疑② |

## 轨道状态

- Track A（存量复扫）：✅ A-1~A-5 全部落盘（`AUDIT_TRACK_A_FIXQUEUE_RESIDUAL` + `AUDIT_TRACK_A_THREE_END_GAP_SCAN`）
- Track B（接口契约）：B-1/B-2/B-3 ✅ 落盘；B-4~B-9 待第 2 波
- Track C（三端实测）：基础设施已探明（DevTools 可用+双产物指服务器）；C-admin/C-mp 待第 2 波
- Track D（easygo diff）：待第 2 波
- Track E（将邑专项）：✅ 落盘（E-1~E-4，AU-003 判非缺陷、AU-009 confirmActivate 新立）
- Track F（补充项）：待第 2 波（AU-004 演示种子已预登记）
