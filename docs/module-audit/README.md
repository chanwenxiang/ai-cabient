# 三端分模块审计（2026-10-29 启动）— 全局共享工作区

> **地位**：11 轮三端（admin-vue / merchant-mp / consumer-mp）分模块审计的唯一共享工作区。
> **参与者**：多 AI 并行（任何会话接手前必须先读本 README 并遵守本页规则）。
> **计划与轮次**：见 [PLAN.md](PLAN.md)。**问题台账（唯一编号源）**：[ISSUES.md](ISSUES.md)。
> 模板：[templates/page-checklist.md](templates/page-checklist.md)、[templates/round-report.md](templates/round-report.md)。

## 铁律（用户 2026-10-29 拍板，违反即返工）

1. **一切以当前代码实况为准。** 旧文档（含 `docs/` 历史审计、`.workbuddy/`、以及本目录自己上一轮写的清单）**只当线索，不当结论**；任何问题入账前必须在当前 HEAD 上复现（复现步骤+证据写进台账）。
2. **写前先看变化。** 每次动笔前依次做：
   - `git log --oneline -10` + `git status --porcelain | head` —— 看代码基线是否被并发会话推进/弄脏；
   - 回读本目录全部文件 —— 看其他 AI 追加了什么（编号走到哪、哪些页面已测）；
   - 然后**只追加 / 最小编辑**，**禁止整文件覆盖回写**（同工作区多会话并发是常态）。
3. **分步走，落盘勤。** 一轮只做一个模块；一页测完立即把 checklist 写进轮报告；问题当场入台账。禁止测完一整轮再补文档。
4. **审计与修复分离。** 轮内原则上只记录不修（P0：资金错误/数据损坏/安全除外——立即修并注明）；批量修复放轮末，走正常开发流程（Skill `ai-cabinet-dev-test`；push 前必跑 `node scripts/pre-push-ci-preflight.mjs`）。避免基线漂移毁掉后续轮的复测。
5. **真机项单列。** 扫码/开门/支付/推送等必须真机的动作，不阻塞页面审计——记入轮报告「需用户配合」清单，集中约时间真机验证。UI/逻辑审计先用开发者工具/浏览器完成。

## 问题编号与状态

- 编号 **MA-xxx** 全局递增，唯一发放处 = [ISSUES.md](ISSUES.md) 索引表。发放前先回读（规则 2），避免撞号。
- 与历史编号体系（`AU-xxx` 见 [AUDIT_LEDGER_2026-10-13.md](../AUDIT_LEDGER_2026-10-13.md)；技术债 `D/C/M-xx` 见 `docs/engineering/*-debt-tracker.md`）**不混编**；若复核发现与旧条重复/相关，在「关联」列引旧号。

| 级别 | 定义 |
|------|------|
| P0 | 资金算错 / 数据损坏 / 安全 / 整模块不可用 |
| P1 | 功能错误、权限错误（漏拦/多拦）、主流程阻断 |
| P2 | 体验/样式/文案缺陷、三端口径不一致、状态显示与真实数据不符 |
| P3 | 优化建议（可 wontfix） |

状态流转：`open → confirmed（已二次复核/他人复现）→ fixing → fixed（源码修复 + 实跑验证 + 相关门禁绿，三者齐备才可标）→ / wontfix（须记用户拍板）`。改状态必须带日期+执行人。

## 环境速查（⚠️ 全部是待验证线索，R0 实跑核准后方可当真）

- 本地栈：docker compose（trade `:18080`、Postgres `:15433`、gateway `:80`，admin 挂 `/admin/`）；admin 改源码后须 `node scripts/build-admin.mjs` 重建产物 + 浏览器 Ctrl+F5 才可见。
- 账号（历史记录，R0 逐个验登录）：`13900000001` 超管；`13800138001` 商户侧（绑 MCH-DEFAULT）；`13800138007` 演示补货员。admin 密码登录走 `POST /api/v2/auth/admin-password-login`（redis captcha）。
- 小程序：mp-weixin 为唯一验收权威（H5 不投入）；appid 来自进程环境 `MP_WEIXIN_APPID_*`（consumer=`wx6dacefb3671de630`，merchant=`wxf7de3625991a6431`）。
- 支付/资金类操作只走 dev mock 分支，**禁止真实资金操作**（财务商户轮尤其）。
- 造数纪律（R1 起生效）：测试数据一律 `AUDIT-` 前缀命名，造数脚本/SQL 落 `docs/module-audit/seeds/`；禁止手改共享库里的真实数据；用完清理或标注保留。

## 目录结构

```
docs/module-audit/
├── README.md                 # 本页：规则（先读这个）
├── PLAN.md                   # 11 轮计划 + 各轮页面清单快照（每轮开轮要重拉）
├── ISSUES.md                 # 问题台账（MA-xxx 唯一编号源）
├── seeds/                    # 各轮造数脚本（AUDIT- 前缀数据）
├── evidence/                 # 截图/trace 等证据，按 MA-xxx 或轮次命名
└── templates/
    ├── page-checklist.md     # 页面×选项卡审计清单模板（固定维度，保证多 AI 产出可比）
    └── round-report.md       # 轮报告模板
```

轮报告文件名：`rounds/R{NN}-{模块Slug}.md`（如 `rounds/R01-overview.md`），由执行 AI 从模板复制生成。
