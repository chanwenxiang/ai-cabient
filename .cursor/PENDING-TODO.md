# 待办交接（本轮对话未完成）

> 生成时间：2026-09-03  
> 来源会话：[费用与死配置](f83e5b87-b16a-40ad-9317-717b4c1f7b97)  
> 分支：`dev` @ `50d56356`（已 push）  
> 本地：`trade-service` 已重建重启；Flyway V255/V256 已应用  
> 新对话开工：先读 `.cursor/skills/ai-cabinet-dev-test/SKILL.md`，再按下列优先级执行。

---

## ✅ 本轮已完成（勿重复做）

- 场地租金账单 `site_rent_bill` + 设备流量费账单 `device_data_fee_bill`（出账/标记已付台账，非自动打款）
- 死费用配置三连：临期价进结算、地推赏金 DONE 入账、提现手续费可配
- 管理后台构建 + 提交推送 + 删本地无用截图/sonar 日志 + 重建重启 trade-service
- 租金/流量费相关 Playwright 冒烟曾做过；**全菜单第二轮未做**
- **P0 全菜单第二轮实测（2026-09-03）**：Playwright MCP 有头；64/64 进页 PASS；费用深测 PASS；失败 0。报告见 `clients/admin-vue/output/playwright/p0-round2/REPORT.md`
- **P1 死 SystemConfig 三连（2026-09-03）**：`settlement.min_confidence` / `dispute.auto_open` / `ops.support_email` 已接到真实行为；trade-service 已重建；`/api/v2/public/consumer-config` 含 `supportEmail`
- **P1 支付/财务壳（2026-09-03）**：进件「仅登记」、发票「仅状态」、提现 `/payout-mode` Mock 强提示、分账「仅记账」文案；admin 已构建并随 trade-service 重建
- **P1 运营/设备壳（2026-09-03）**：广告/OTA/反馈/公告/异常SLA/选品替换均标明边界；告警键白名单；商户非 ACTIVE 拦截消费者开门

---

## P0 — 第二轮测试（用户已约定下一轮做）

- [x] **全菜单按钮实测（第二轮）**：运营后台 `http://localhost/admin`（`13900000001` / `123456` + Redis 验证码）
  - 优先 Playwright MCP；有头模式
  - 覆盖：登录、各一级菜单进页、列表筛选/分页、抽屉/对话框、安全写操作（勿只 curl）
  - 重点复核本轮费用相关页：组织场地「费用账单」、商品临期价、线长地推赏金、商户/线长提现手续费列
  - 产出：通过项 / 失败项 / 截图或 DOM 证据；失败当场修或记入缺陷待办
  - **结论（2026-09-03）**：全部通过，无当场缺陷。证据：租金/流量费样例行、临期价抽屉、地推「奖金」字段、提现「手续费」列；截图 `p0-round2-fee-bills.png` / `p0-round2-merchant-withdraw.png`

---

## P1 — 全仓「配了却不驱动行为」空实现（不只钱）

按影响与可交付性排序；每项二选一：**接上真实行为** 或 **UI/文案标明「仅登记/Mock」并避免误导**。

### 死 SystemConfig（有种子无人读）

- [x] `settlement.min_confidence`：已接到 `SettlementConfidenceService` 整体置信度门槛（单品仍用 SKU `minChargeConfidence`）
- [x] `dispute.auto_open`：已接到结算低置信路径；`false` 时跳过自动开单并继续结算（空车/超时等安全路径不受影响）
- [x] `ops.support_email`：已下发 `consumer-config.supportEmail`；帮助中心/争议详情可复制邮箱

### 支付 / 财务壳

- [x] **商户进件**：页面标明「仅登记」；`live-hints` 含 `registryOnly`；外部门店号 placeholder 提示不推送渠道
- [x] **发票开具**：文案/确认框标明「仅状态」；不生成税控 PDF/邮件
- [x] **提现打款骨架**：`/payout-mode` 强提示 Mock；打款成功文案「仅 Mock 成功」；非 Mock 明确「尚未接入」
- [x] **微信分账**：分账状态 note + 页面标明余额支付默认「仅记账」(LEDGER_ONLY)

### 运营 / 设备壳

- [x] **柜屏广告**：标明「预览/演示」；柜机播放器与曝光回写未接入
- [x] **SKU 下架审核 `replaceSkuId`**：标明「仅备注，不改货道」
- [x] **告警自定义键**：告警规则页限制仅白名单键可选/新建
- [x] **反馈回复**：标明「仅运营备注，不推送」；成功 toast 同步
- [x] **公告发布**：标明「仅 CMS，不主动推送」
- [x] **异常 SLA**：标明「仅筛选展示，无 webhook 催办」
- [x] **OTA**：标明「模拟器级：无柜机落地安装」
- [x] **商户状态 `PENDING`/`INACTIVE`**：开门路径校验 ACTIVE；组织表单可改状态

---

## P2 — 本轮对话衍生/可选

- [ ] 提现手续费演示：若要验收非零手续费，配置 `MERCHANT_WITHDRAW_FEE_*` / `LINE_WITHDRAW_FEE_*` 后重建验证（当前默认 0）
- [ ] `packages/shared-dict`：源码已加 `BOUNTY`；若 dist 未同步且有独立引用，确认构建链路
- [ ] 租金/流量费「标记已付」是否要后续接真实打款/应付凭证（当前明确为台账）
- [ ] 未跟踪本地垃圾复查：确认无新的 `*-after.png` / `sonar-*.log` 遗留

---

## 验收入口备忘

| 面 | 地址 |
|----|------|
| 运营后台 | `http://localhost/admin` |
| trade-service | `http://localhost:8080/actuator/health` |
| Gateway | `http://localhost` |
| 演示账号 | `13900000001` / `123456` + Redis captcha |

重建：`infra` 下  
`docker compose -f docker-compose.yml -f docker-compose.apps.yml --profile apps up -d --build trade-service`  
Admin 静态：`node scripts/build-admin.mjs`
