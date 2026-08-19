# AI Cabinet 安全 / 静态扫描报告

- **日期**: 2026-08-19
- **范围**: 运营后台 `admin-vue`、两端小程序、Java 后端（common-core / trade / device / simulator）、`vision-service` 语法层
- **工具**:
  - 编译：`vue-tsc` / `tsc` / `mvn compile` / `python -m compileall`
  - 质量：ESLint、SpotBugs（trade-service，threshold=High）
  - 安全：`security-best-practices`（Vue 规范）+ 本仓重点人工/正则审计（SQLi、密钥、内部 API、XSS）
- **模式**: 扫描项 SEC-M1～M5、SEC-L1～L2 已落地；SEC-H1 已通过多层缓解（网关 + 启动校验 + 脚本/文档警示）

## 执行摘要

- **语法/编译**：三端前端 + Java 全模块 + vision Python **均通过**。
- **SpotBugs（High）**：WeChatBillCsvParser 无用条件 **已修**；`WeChatBillCsvParserTest` 通过（复扫可再跑 SpotBugs 插件确认）。
- **安全**：报告内 Medium 项均已加固或缓解；演示栈密钥风险靠配置分离 + 启动校验 + 运维脚本警示治理。
- 网关对 `/internal/`、`/actuator/` **返回 403**；生产启动校验会拒绝默认 JWT/内部密钥与 mock。

## Critical

（无）

## High

| ID | 位置 | 问题 | 影响 | 建议 |
|---|---|---|---|---|
| SEC-H1 | `infra/docker-compose.full.yml` 等默认 `INTERNAL_API_KEY=dev-internal-key-change-me` | 演示栈默认内部密钥弱 | **已缓解**：网关 `/internal/` 403；`ProductionStartupValidator` strict 拒绝默认密钥；dev 启动 WARN；`docker-compose.full.yml` / `.env.example` / `up.ps1` / `check-env.ps1` 明示勿暴露公网 | 生产必须用 `docker-compose.production.yml` + 强随机密钥 |

> 注：`ProductionStartupValidator` 在 strict profile 下会拒绝 `dev-internal-key-change-me` / 默认 JWT，降低误上线风险。SEC-H1 针对「演示配置被当生产用」场景。

## Medium

| ID | 位置 | 问题 | 影响 | 建议 |
|---|---|---|---|---|
| SEC-M1 | `clients/admin-vue` 会话 | 曾依赖 localStorage JWT | **已修/已验收**：`AUTH_COOKIE_ENABLED` 默认 true；登录写 HttpOnly `aicabinet_session`；前端 `cookieEnabled` 时不落 `admin_token`（仅 `admin_cookie_auth=1`）；上传/二维码等改 `authFetch`。浏览器验收：dashboard 正常、权限接口 code=0、`document.cookie` 不可见会话 Cookie | 生产保持 cookie 开启；Strict profile 已要求 cookie |
| SEC-M2 | `clients/consumer-mp/src/utils/recharge.ts` | 曾用 `innerHTML` 注入支付宝表单 | **已修**：`DOMParser` 只取 form/action + 重建 hidden input；action/`payUrl` 白名单支付宝域名；限制字段数与长度 | — |
| SEC-M3 | `clients/admin-vue` ChartBox / `charts.ts` | 图表 SVG `v-html` | **已修**：`sanitizeChartSvg` 剥离 script/事件/外链 href 等；tooltip 文案走 `escapeXml`；颜色走 `safeCssColor` | — |
| SEC-M4 | `GrowthLogArchiveScheduler.java` | 动态表名/条件拼 SQL | **已修**：表名白名单 + `created_at < ?`；过期条件改为布尔开关，不再拼用户字符串 | — |
| SEC-M5 | SMS mock 固定码 | 开发短信固定码 `123456` | **已修**：mock 码仅 `application-dev.yml`（`${SMS_MOCK_CODE:123456}`）；base/staging 无内置码；`SmsCodeService` mock 码空则走随机；strict profile 拒绝 123456/000000 | 生产配置 `SMS_WEBHOOK_URL` |

## Low / 质量

| ID | 位置 | 问题 | 建议 |
|---|---|---|---|
| SEC-L1 | SpotBugs：`WeChatBillCsvParser.java` | `cols.length > 5/6` 在 `length < 13` 之后恒真 | **已修**：直接取 `cols[5]`/`cols[6]` |
| SEC-L2 | javac：`AlipayOpenApiClient`、`OpsExceptionService` | javadoc `@deprecated` 未配 `@Deprecated` | **已修**：补上注解 |
| SEC-L3 | `merchant-mp/.../text-prompt.ts`：`innerHTML` | 自建弹层 HTML | 已对用户字段 `escapeAttr`；保持勿拼接未转义内容 |

## 已确认 OK

- MyBatis `@Select` 等注解中 **未见 `${}` 注入**；未见 `createNativeQuery` / `createStatement`。
- `/internal/v1/**` 有 `InternalApiAuthInterceptor`（常量时间比较密钥）；网关 **403** 阻断外网路径。
- `DevMock*Payment/RechargeController` 均 `@ConditionalOnProperty(mock-enabled=true)`。
- CORS `allowed-origins` 为显式列表（localhost），非 `*`。
- 生产启动校验拒绝默认 JWT / 内部密钥 / mock 提现与结算。

## SpotBugs 摘要（trade-service，High）

```
原扫描：UC_USELESS_CONDITION ×2（WeChatBillCsvParser.java:35-36）
现状：已改为直接读取 cols[5]/cols[6]；单测 WeChatBillCsvParserTest 通过。
```

## 建议下一步（修复优先级）

1. 可选：把 SpotBugs / Checkstyle 固化进 `pom.xml` CI。
2. 上线前跑 `scripts/check-env.ps1 -Prod` + `infra/up.ps1 -Prod -Smoke`。
3. 定期复扫：三端 `vue-tsc`/`tsc` + `mvn compile` + 本报告清单。

---

报告路径：`docs/SECURITY_BEST_PRACTICES_REPORT.md`
