# 新会话续测提示词（S3 旁路 + 业务单号 · 2026-09-27）

> 复制下方「提示词」整块到新 Cursor 会话即可。本文件可 `@docs/uat/SESSION_PROMPT_S3_CONTINUE.md`。

---

## 提示词（复制从这里开始）

```text
你是 AI Cabinet 本仓 Agent。新开会话，先读再动手，禁止只靠聊天记忆。

## 开工必读
1. `AGENTS.md` + `docs/PROJECT_KNOWLEDGE.md`（§0–§4、§7、§9；注意 2026-09-27 Changelog）
2. `.cursor/skills/ai-cabinet-dev-test/SKILL.md`
3. 场景真源：`docs/uat/MP_THREE_END_SCENARIOS.md`（S0→S3）
4. 本轮 FINDINGS（已写结论，勿重复空测）：
   - `docs/uat-screenshots/2026-09-27/s3-recharge/FINDINGS.md`
   - `docs/uat-screenshots/2026-09-27/s3-coupon/FINDINGS.md`
   - `docs/uat-screenshots/2026-09-27/s3-merchant/FINDINGS.md`
   - `docs/uat-screenshots/2026-09-27/s3-ops/FINDINGS.md`
5. lessons：`docs/engineering/lessons-learned.md`（#212–#225，尤其 DemoData/柜号、渠道协议号、sim 门 /close）

## 已完成（勿重做代码主线，除非 CI 再红）
- CI：`b43d3283` 已绿（`DemoFixture` 替 CAB-001；渠道 `outRefundNo`/`outReturnNo` 保持 BR/PSR，勿改纯数字）
- 业务单号：库内/系统发号纯数字 + Admin `displayBizNo`；**渠道协议号禁止改数字**
- trade 本地曾 Flyway V287 checksum repair；Docker trade `:18080` / Admin `http://localhost/admin/`
- S3 Admin 软写抽样基本收口（见各 FINDINGS）：
  - A1–A6、B1–B3、C1–C6、D1；D2 SKIP；D3 PARTIAL（公开 help/policy API 404，mp 静态页存在）
  - 用户余额 L3：10001 ¥197.50 ↔ 19750
  - C5：¥1 自动 PAID；¥500 曾待审→软取消→后 API 驳回解冻（可用约 50440）
  - 仓配：盘点号纯数字已验；采购/调拨库空 SKIP
- 演示账号：运营 `13900000001`/`123456`；消费者 `13800138000`；商户 `13800138001`
- 柜/商户为运行时号（例柜 `166813762350`、商户 `892485912248`），**禁止写死 CAB-001**

## 当前缺口（按优先级做）
1. ~~H5/mp UI / 附录页矩阵~~ → **DONE**
2. ~~仓配采购/调拨~~ → **DONE**（采购单列 `4`；调拨 `1790507082502743938921` cancel）
3. ~~S3-B2 兑换写路径~~ → **DONE**（项#4 INACTIVE；须 couponDefId）
4. ~~商户 device-detail~~ → **DONE**（query `id=`；lesson #227）
5. **文档提交**（仅当用户明确要求 commit）：UAT 截图 + FINDINGS + Changelog；推前 `node scripts/pre-push-ci-preflight.mjs`。
6. ~~台子复检~~ → **DONE** `mp-seed-gate` pass（未 Wipe）；见 `s3-mp/GATE_CHECK.md`。整轮回归再 `-CleanupFirst` + `e2e-full-flow-milk`（会动账）。

## 铁律
- UI 验收优先 Playwright MCP；禁止只 curl 宣称 UI 通过
- 视口：先最大化窗口，再把视口对齐 outer（约 1395×794）；**禁止**把视口硬设成 1920 而窗口只有 ~1425（会灰边裁切）
- 软写默认：确认框点取消；硬写须用户明示
- 前台文案中文；密钥不入库
- 改完易复发问题写 lessons 三列表 + Changelog

## 本轮请你直接做
从「当前缺口」第 1 或第 2 条开始续测；每完成一块更新对应 FINDINGS + `PROJECT_KNOWLEDGE` §9 一行。不要复述旧会话长史，以仓库文件为准。
```

---

## 一句话状态（给人看）

| 项 | 状态 |
|----|------|
| CI `b43d3283` | 绿 |
| S3 Admin 软写 | 基本收口 |
| 业务单号数字展示 | 提现/退款/盘点已验 |
| H5 nginx | 仍未挂载（不测） |
| mp 附录 A | **PASS**（DevTools；见 `s3-mp/FINDINGS`） |
| 仓配/兑换软写 | **PASS** |
| 未 commit 的 UAT 文档/截图 | 工作区仍有未提交改动，commit 须用户明示 |
