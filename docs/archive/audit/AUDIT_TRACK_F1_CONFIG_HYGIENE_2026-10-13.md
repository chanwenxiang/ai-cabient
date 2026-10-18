# Track F-1/F-6 — 生产配置卫生 + 启动校验器复验（2026-10-13）

> 只读仓内文件（未访问生产机）。密钥值一律脱敏。子代理产出 + 主会话抽查复核（CHANGE-ME 占位符无黑名单实证：模板 8 处占位 vs validator/check-env grep 零命中）。

## ① 配置面清单（键→来源→默认形态→启动校验覆盖）

| 键 | 来源 | 默认形态 | 校验覆盖 |
|---|---|---|---|
| JWT_SECRET | trade application.yml:107 | dev 字面默认 | ✔ requireSecret（拒空/拒 dev 默认/≥32，validator:82） |
| INTERNAL_API_KEY | trade:91/device:18/**jiangyi:18** | dev 字面默认 | ✔ trade:83、device:34；**jiangyi 无校验器 ✘** |
| POSTGRES_PASSWORD | compose.yml:15 | `aicabinet` | ✔ rejectDefaultDatasourcePassword:323 |
| Redis 密码 | apps.yml | `devredis`；prod compose 仅 `:?` | ✘ 无强度检查 |
| MINIO_ROOT_* | apps.yml | `minioadmin` | ✔ :332 |
| MQTT_PASSWORD/CLIENT_ID | device app.yml:24-26 | 默认值可猜 | ✔ 长度≥16+拒默认（device:58-66）+compose `:?`；占位符仍可过 |
| MQTT_ALLOW_TCP | device validator:51 | 缺省=硬拒 | ✔ |
| JIANGYI_JWT_SECRET | jiangyi app.yml:26 空默认 | 运行期 503 fail-closed（DeviceTokenService:82-91） | ✘ 无启动校验、prod compose 无 jiangyi 段、.env.production.example 无此键 |
| JIANGYI_SECRET / MERCHANT_MOBILE/PASSWORD | apps.yml:84-87 | 空→运行期 IllegalStateException | ✘ |
| JIANGYI_OSS_AK/SK/ROLE_ARN | jiangyi:35-37 空 | 运行期 500 | ✘ |
| AICABINET_PAYOUT_ENCRYPTION_KEY | trade:220 空 | 运行期 500（PayoutFieldCipher:137） | ✘ 启动不校验、prod 模板无此键 |
| WECHAT_PAY/MINIAPP_ENABLED | app.yml:129/139；prod 段 `${}` 无默认+`:?` | 显式声明制 | ✔ 分档：true 且凭据不全硬拒；false 醒目 WARN（:152-171） |
| CHECKOUT_BALANCE_ONLY | app.yml:71 | — | ✔ 与支付开关配对一致（:175） |
| AUTH_COOKIE_ENABLED/SECURE | app.yml:109-110 | compose `:?` | ✔ ×PUBLIC_BASE_URL 协议配对，错配硬拒（:250-273） |
| RECON/PAYSCORE/分账/提现 mock | 各 app.yml | — | ✔ prod 钉 false+validator 双保险（:118-135,213-225,281-296） |
| SMS_WEBHOOK_URL / SMS_MOCK_CODE | app.yml:114-116 | — | ✔ 缺 webhook 硬拒、内置 123456/000000 硬拒（:86-89,227-235） |
| XXL_JOB_ACCESS_TOKEN | app.yml:362 | `dev-xxl-token`；compose 仅 `:?` | ✘ 无值检查 |
| REDPANDA | compose.yml:104-120 | PLAINTEXT 无 SASL | ✘（内网信任模型；=AU-015 续） |
| RECHARGE_MAX_CENTS/BALANCE_REFUND_MAX_CENTS/DISPUTE_AUTO_OPEN | 非 env——DB system_config（SystemConfigService:734/773/722） | seed 500000/500000/true；**0=不限制** | 不适用启动校验（关联 DC-6/AU-048） |

## ② 发现表

| ID | 级 | 位置 | 发现 | 证据 | 建议 |
|---|---|---|---|---|---|
| AU-074 | **P1** | scripts/check-env.ps1:39-49 + .env.production.example:7-11,59,105-109 | **模板占位符自身能过所有门禁**：`CHANGE-ME-…`（43 字符）≠dev 默认且≥32 ⇒ validator requireSecret(:382-389) 与 check-env Test-StrongSecret 全绿；忘改=公开已知密钥上生产 | 主会话 grep 实证：validator 与 check-env 均无 CHANGE-ME/change-me/YOUR_ 拒绝逻辑 | ✅**已修(批八-8a 76ae9cc4)**：12 前缀黑名单三端同口径（trade validator+check-env.ps1+门禁 check-env-placeholders 负向自测五形态）；PlaceholderTest 4/4、PowerShell 占位样本 8 键全拒、trade 1861/1861 |
| AU-075 | **P1** | infra/docker-compose.production.yml:8-55；jiangyi-gateway 无 validator；apps.yml:203-212 | **jiangyi-gateway 生产脱管**：prod 叠加层无其段、不设 INTERNAL_API_ALLOWED_CIDRS（空=不限来源 IP，InternalApiProperties:31-33）、不注入 JIANGYI_*、未设 SPRING_PROFILES_ACTIVE ⇒ 生产栈恒跑 dev profile、/internal/** 仅共享 key 且 dev 默认 key 无告警 | production.yml 全文无 jiangyi | ✅**已修(批八-8b/8c 2f17b14a)**：jiangyi ProductionStartupValidator（拒 dev key/空 CIDR/缺失或占位 JIANGYI_JWT_SECRET，fail-loud 前移）+prod 叠加层 jiangyi 段+模板将邑全键；骨架 7 例红 5→7/7、jiangyi 域 74/74 |
| AU-076 | **P1** | infra/.env.production.example 全文 | **生产模板漂移**：缺 WECHAT_MINIAPP_ENABLED、MQTT_CLIENT_ID（compose `:?` 必填→照抄模板部署即红）；整段缺 JIANGYI_*（staging 模板:40-56 有）——识别已只认将邑端侧，缺 JIANGYI_JWT_SECRET=设备 token 503 柜机无法激活；缺 AICABINET_PAYOUT_ENCRYPTION_KEY | .env.staging.example:40-56 对照；production.yml:18,37-38,55 | ✅**已修(批八-8c 2f17b14a)**：模板补齐三处+分档注释（含余额-only 阶段组合示例）；新模板过 check-env-placeholders |
| AU-077 | P2 | scripts/verify-production-readiness.ps1:233 | **P1-6b 护栏死代码**：credCsv 路径含 BEL 控制字符（`emqx^Guth-bootstrap…` cat -A 实证）⇒ Test-Path 恒 false，EMQX bootstrap↔库内凭据一致性检查静默不执行 | grep+cat -A | 修路径字符；文件缺失应 FAIL/WARN 而非静默跳过 |
| AU-078 | P2 | docs/PRODUCTION.md:159,240,315 | 文档仍写「AUTH_COOKIE_SECURE 生产强制 true」，与现行「按 PUBLIC_BASE_URL 协议配对、非 https 必须 false」相悖（validator:250-273） | 同左 | 更新三处，写明「两处一起翻」 |
| AU-079 | P2 | 校-verify/validator:77-79 | 忘设 profile 兜底仍 warn-only（=AU-051/S-12 原状，本轨复验未变） | 同 AU-051 | 维持部署门禁口径 |
| AU-080 | P2 | compose `:?` 三键 | REDIS_PASSWORD/GRAFANA_ADMIN_PASSWORD/XXL_JOB_ACCESS_TOKEN 仅查存在，弱值可过 | production.yml:24,35,100-101 | ✅**已修(批八-8a 76ae9cc4)**：check-env prod 档六键统一 ≥16+拒占位+已知弱值黑名单；PowerShell 弱值样本 4 键拒+强密钥零误报 |
| AU-081~085 | P3 | ①SessionWatchdog 过渡期告警无到期机制（validator:269-273）②WECHAT_MERCHANT_SUBSCRIBE_TEMPLATE 默认硬编码真实模板 ID（apps.yml:114）③check-env 对 tcp:// 一律 FAIL 与 MQTT_ALLOW_TCP 合法过渡相悖(:120-121 vs device:47-57)④system_config 资金限额 0=不限制误填即静默解除（关联 DC-6）⑤Redpanda 无 SASL=AU-015 续 | 各 file:line | 登记延后 |

## ③ 明文密钥进 git 专项结论（F-1 核心问题）

`git ls-files` 仅 10 个 `*.example` 被追踪；`git log --all --diff-filter=ACDMR` 全历史检索 infra/.env*、clients/*/.env* ——**从未有非 example 的 .env 入库**（HEAD 工作区两个 .env.development 被 .gitignore:37 覆盖，check-ignore 实证）。形态扫描（AKIA/LTAI/sk-/长随机串）HEAD 无命中。dev 默认凭据为刻意公开且三层点名拒绝——不构成泄露，缺口仅在 AU-074 占位符可过。grafana.htpasswd 入库的是 bcrypt 哈希（M12 要求上线前替换）。

## ④ 干净面声明

- fail-closed 正确实现：JIANGYI_JWT_SECRET（503+≥32）、OSS STS（500）、打款加密密钥（500 绝不落明文）、SMS 缺 webhook/内置万能码硬拒、MinIO/DB/CORS localhost/dev key 硬拒、RECON/mock prod 硬拒钉死、MQTT_ALLOW_TCP 缺省=拒、compose `:?` 显式声明制。
- 阶段配对语义自洽：AUTH_COOKIE_SECURE×PUBLIC_BASE_URL 双向错配硬拒+WARN；WECHAT_PAY_ENABLED=false 时 412/对账/实名/分账同档降级全留 WARN；与 CHECKOUT_BALANCE_ONLY 组合无矛盾。
- 端口暴露面：postgres/redis/emqx/minio/prometheus/redpanda 宿主映射均钉回环。

## 存疑

1. 生产实机 `.env.production`/`auth-bootstrap.production.csv` 仓外不可见——「占位符是否已原样上生产」无法证伪（AU-074 按接真必修口径）。**建议：运维在服务器实机跑一次 check-env 并把 REDIS/GRAFANA/XXL 三键值人工核对**（登记为接真前 checklist 项）。
2. 将邑柜生产是否近期需提现（决定 PAYOUT_ENCRYPTION_KEY 缺口判级，现按运行期 fail-closed 不判资损）。
