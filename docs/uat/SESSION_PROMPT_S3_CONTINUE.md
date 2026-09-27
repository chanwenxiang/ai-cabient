# 新会话续测提示词（S3 旁路 + 业务单号 · 2026-09-27）

> 复制下方「提示词」整块到新 Cursor 会话即可。本文件可 `@docs/uat/SESSION_PROMPT_S3_CONTINUE.md`。  
> **本轮已收口**：S0–S3 + 附录 A + 整轮 milk 回归 + push CI 绿。新会话勿重复空测；有新缺口再开任务。

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
   - `docs/uat-screenshots/2026-09-27/s3-mp/FINDINGS.md` + `GATE_CHECK.md`
   - `docs/uat-screenshots/2026-09-27/full-regression/FINDINGS.md`
5. lessons：`docs/engineering/lessons-learned.md`（#212–#228，尤其柜号、渠道协议号、sim `/close`、mp API 127.0.0.1、milk 对账 `@()`）

## 已完成（勿重做，除非 CI 再红或用户点名复测）
- Commit/push：`559ed362` docs(uat) S3；`08fca633` fix milk 对账假红；CI run `36316095037` **success**
- 业务单号：库内/系统发号纯数字 + Admin `displayBizNo`；**渠道协议号禁止改数字**（#225）
- S3 Admin 软写：A1–A6、B1–B3、C1–C6、D1；D2 SKIP；D3 公开 help/policies API + 网关 `/consumer/` stub
- 附录 A mp：消费/商户各 22 路由 L1 PASS（DevTools；sync 优先 127.0.0.1，#226）
- device-detail query `id=`（#227）；兑换须 `couponDefId`；仓配采购/调拨号纯数字 soft-cancel
- 整轮回归：KeepPlatform → gate → `e2e-full-flow-milk -SkipCleanup`；主链绿；对账假红已修（#228）
- 演示账号：运营 `13900000001`/`123456`；消费者 `13800138000`；商户 `13800138001`
- 柜/商户运行时号（例柜 `166813762350`），**禁止写死 CAB-001**

## 已知非阻断 / 未挂载
- H5 `/consumer/` nginx：**已挂 stub**（`http://localhost/consumer/` 不再 302 后台）。完整包 `node scripts/build-mp-h5-gateway.mjs`；权威仍 mp-weixin DevTools
- 公开帮助/条款：`GET /api/v2/public/help`、`/policies`（trade 重建后生效）
- Gray CheckOnly：vision mock / open disputes 残留等为 dev 项，不挡 milk
- sim 购物常 DISPUTED→内部关门兜底（主链仍能出单）

## 若用户要求「再回归」
1. `cleanup-test-data.ps1 -KeepPlatform -RestoreBalanceCents 50000`（保台子）
2. `mp-seed-gate.ps1`（勿盲目 Wipe）
3. `e2e-full-flow-milk.ps1 -SkipCleanup`
4. 推前：`node scripts/pre-push-ci-preflight.mjs`

## 铁律
- UI 验收优先 Playwright MCP；mp 用 DevTools/automator，禁 H5 冒充 PASS
- 软写默认；硬写须用户明示
- 前台文案中文；密钥不入库；改易复发问题写 lessons + Changelog

## 本轮请你直接做
本文件对应链路**已收口**。仅当用户给出新缺口（新页/新 bug/再跑 CI）时动手；否则先读 FINDINGS 回答现状，勿空转复测。
```

---

## 一句话状态（给人看）

| 项 | 状态 |
|----|------|
| CI `08fca633` | **绿**（run 36316095037） |
| S3 Admin 软写 | 收口 |
| 业务单号数字展示 | 已验；渠道协议号勿动 |
| H5 nginx | 未挂载（不测） |
| mp 附录 A | **PASS** |
| 仓配/兑换软写 | **PASS** |
| 整轮 milk | **主链 PASS**；对账断言已修 |
| 文档/修复 | 已 push `origin/dev` |
