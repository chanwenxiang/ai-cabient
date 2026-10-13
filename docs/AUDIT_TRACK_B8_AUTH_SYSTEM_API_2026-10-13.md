# Track B-8 — 认证/会话/系统配置面接口契约审计（2026-10-13）

> 范围：AuthController 全链（登录/验证码/refresh/logout）+ AuthInterceptor + JwtService/SessionCookieService/LoginThrottle + 短信面 + PublicConfig/SystemConfig 读写 + OpsRbac/通知/定时任务 + 内部端点扫尾。
> 基线：worktree `audit-pre-launch`。列名勘误：任务书所称 `AuthCookieService` 实为 **`SessionCookieService`**。

## ① 端点组 × 七项矩阵

| 端点组 | ① | ② | ③ | ④ | ⑤ | ⑥ | ⑦ |
|---|---|---|---|---|---|---|---|
| Auth 登录×7（sms/password/admin/2fa/wx/alipay） | ✔ | ✔ | ✘S-04 | ✔ | ✔ | ✔ | ✔ |
| Auth refresh/logout | ✔(公开+自证 token) | ✔ | ✔ | ✔ | ✘S-06 | ✘S-07 | ✔ |
| /auth/sms-code + SmsCodeService | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| /auth/captcha | ✔ | NA | ✔ | NA | ✔ | ✔ | ✔（S-11 属资源面）|
| PublicConfigController 5 个匿名 GET | ✔(豁免清单精确) | NA | NA | NA | ✔ | ✘S-08 | ✘S-08 |
| SystemConfigController list/upsert/delete/history/rollback/alert-test | ✔ | ✔ | ✘S-03 | ✔(锁+行锁重查) | ✔ | ✔ | ✘S-01 |
| OpsRbac 个人中心/me 系列 | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| OpsRbac operators 写（update/disable/reset） | ✔ | ✘S-02 | ✔ | ✔(锁) | ✔ | ✔ | ✔ |
| RBAC 角色/权限/分配端点 | ✔(三处一致抽查) | ✔ | ✔ | ✔ | ✔ | ✔ | ✔ |
| AdminGrowth /notifications send/update/delete | ✔ | ✔ | ✔ | ✘S-09 | ✔ | ✔ | ✔ |
| ScheduledTask list/edit/run | ✔ | NA | ✔ | ✔ | ✔ | ✘S-07 | ✔ |
| OpsDevOps / SysDict / DictRuntime | ✔ | ✔ | ✔ | NA | ✔ | ✔ | ✔ |
| /internal/v1/* | ✔(常量时间 key+prod 强密钥) | NA | ✔ | NA | ✔ | ✔ | ✔ |

## ② 问题清单

| ID | file:line | 描述 | 攻击/复现路径 | 判级 | 台账 |
|---|---|---|---|---|---|
| S-02 | OpsRbacService.java:461-467 vs :489-500 | **超管保护不对称**：`disableOperator`（DELETE）有 C16 admin 角色守卫+禁自禁，`PUT /rbac/operators/{userId}` 传 `status=INACTIVE` **缺 C16 admin 守卫**（主会话复核更正：禁自禁在 :463 本已存在，原表述「无禁自身校验」偏宽） | 持 `ops:rbac:assign:edit` 的中权运营可把超管置 INACTIVE，绕过 C16 | **P1** | AU-047 |
| S-03 | SystemConfigController.java:70-76；SystemConfigService.java:620-638,802 | 配置写**无键白名单、无值语义校验、无双人复核**；资金阈值键（`recharge.max_cents`、`refund.auto_approve.max_cents`、提现限额族）单权限 `ops:config:edit` 可改；审计开关**默认关** | 运营凭证泄露 → PUT 把充值/免审上限清零或拉满且**零留痕** | **P1**（至少审计开关默认改 true） | AU-048 |
| S-01 | SystemConfigController.java:56-58 | listAll 明文返回全部 configValue，含 `ops.alert.feishu_sign_secret`/webhook URL | 只读角色 `ops:config:list` 可拖出签名密钥→伪造告警 | **P2** | AU-049 |
| S-04 | LoginThrottleService.java:29-42；AuthService.java:316-326 | 限流仅手机号维度无 IP 维度；查无此号也计失败；admin 短信码登录无图形验证码 | 已知号 5 次错密锁 10 分钟（账户 DoS）；分布式撞库靠网关 IP 限流兜底（F 轨核对） | P2 | AU-050 |
| S-12 | application.yml:82,107；ProductionStartupValidator.java:77-82 | prod/staging 硬拒默认 JWT secret ✔，但**忘设 profile 启动 = warn-only + mock 开启 + 万能短信码可用** | 误部署即「任意手机号+万能码登录」；compose 模板有 `:?`，属部署门禁 | P2(部署面) | AU-051 |
| S-06 | AuthInterceptor.java:44-46,64-67；SessionCookieService.java:149-157 | CSRF 二层是 X-Requested-With 弱标记非真双提交 token；`/api/v2/auth/**` 豁免拦截器 → cookie 态 refresh/logout 不受该检查；主防护=SameSite=Strict（HTTP 期仍有效） | 只剩一层防护；上 TLS 后建议加真 double-submit token | P3 | AU-052 |
| S-07 | AuthController.java:201-203；ScheduledTaskController.java:142-145 | refresh 把一切异常折叠成 401→全员误登出掩盖故障；任务失败透 `e.getMessage()`（F-05 家族） | DB 抖动=全站登出风暴 | P3 | AU-053 |
| S-08 | SystemConfigService.java:483-541 | 匿名 consumer-config 透出 mockEnabled、渠道 live 状态、限额情报、模板 ID。**supportEmail 占位确认**：键 `ops.support_email`（seed :714），运营台可改（AU-046 关闭为运营动作） | 匿名侦察 | P3 | AU-054 |
| S-09 | SmsCodeService.java:92-118；NotificationService.java:296-324 | verifyCode 的 usedAt 读改写无原子置位（并发双提交同码，同持有者危害低）；sendManual 无幂等键双击重发 | 毫秒竞态窗口 | P3 | AU-055 |
| S-10 | SystemConfigService.java:589-597 | `GET /system-configs` 触发 ensureDefaults() 写库（GET 副作用） | DB 写放大；首读并发可能撞主键 | P3 | AU-056 |
| S-11 | CaptchaService.java:48-55 | 验证码签发无速率限制（TTL 300s 无限生成） | Redis 内存小额 DoS | P3 | AU-057 |

## ③ 存疑

1. **消费者登录不查 INACTIVE**（AuthService.login/loginByPassword 无 requireActiveAccount，仅运营路径+refresh 有）——status 语义若仅运营侧则设计如此；消费者封禁是否完全依赖风控黑名单，**待产品确认**。
2. SameSite=Strict 对微信 H5 首开顶层导航不带 cookie——前端是否有 Bearer 兜底引导需接真前 H5 冒烟必测。
3. `PUBLIC_BASE_URL` 与 `AUTH_COOKIE_SECURE` 阶段联动双向钳制正确，但**上 TLS 切换日两处必须同改** → go-live checklist。
4. `scripts/lib/redis-captcha.mjs` 直读 Redis 取码——前提是生产 Redis 不暴露给执行环境（F 轨部署核对）。
5. `AICABINET_CAPTCHA_ENABLED=false` 全站跳过图形验证——strict-profile 未管控项，确认部署默认。

## 审过且干净

- **JWT 链**：jjwt 钉死 HMAC（无 alg=none/RS256 混淆面）、弱密钥启动即拒、jti 唯一+boot claim 重启全员失效、吊销黑名单 TTL 对齐；2FA challenge 独立 scope 且业务面显式拒绝、不写 Cookie。
- **refresh 轮换**：旧 jti 先吊销后签发；logout 双 realm cookie 均吊销（H66a）。
- **短信防爆**：60s 冷却+每小时 5 条+错 5 次烧码+SecureRandom 6 位+TTL 300s；防枚举同文案；prod 禁万能码、mock 路径被 profile 门死杀。
- **wx 手机号绑定**只信服务端 getuserphonenumber（C13）；openId 建档锁+行锁。
- **RBAC**：默认拒绝拦截器清单、`ops:admin`/`:*` 保留码防线（C15/H65）、超管不跨 merchant 域（B-13）、admin 路径 DELETE 防禁防删干净（S-02 仅 PUT 旁路）、禁自禁/自重置、换绑手机强制新号验证（C14）。
- **内部端点**：constant-time key、strict-profile 强密钥、latest-code 双闸。
