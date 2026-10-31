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
4. **修复节奏（用户 2026-10-31 订正，替代原「轮内只记录、轮末批量修」）**：**P2/P3 轮内即修即回归**；**P0/P1 攒到轮末统一批次修**，走正常开发流程（Skill `ai-cabinet-dev-test`；push 前必跑 `node scripts/pre-push-ci-preflight.mjs`）。
   - 🔴 例外：若某个 P0 会让**后续页面/轮次的数据继续被污染或不可复现**（资损在累计、共享库在被写坏），不等轮末——立即上报用户并**先停轮**，绝不带病往下测。
   - 轮内边测边修会让「同一轮前后两次观测」不可比，所以每条 MA 必须记**双锚点**：`发现锚 <sha>` + `修复锚 <sha>`（详见 PLAN 工作法第 7 步）。
   - 轮末还要做一件事：把本轮缺陷过一遍「能否变成静态/运行约束」，能则入 PLAN 的**门禁化候选台账**——同类缺陷只该出现一次。
5. **真机项单列。** 扫码/开门/支付/推送等必须真机的动作，不阻塞页面审计——记入轮报告「需用户配合」清单，集中约时间真机验证。UI/逻辑审计先用开发者工具/浏览器完成。
6. **MA 领号即单独提交。** 两个会话同秒回读会撞号。流程＝回读 `ISSUES.md` → 索引表加一行（状态 open）→ **立刻只提交这一笔 docs 改动** → 再开始测这一条。证据文件命名钉死为 `evidence/R{NN}-<页>-<现象>.png`（比只写 MA-xxx 好定位）。

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

## 环境速查（R0 已于 2026-10-31 实跑核准，锚 `cb904b71`；R1 又逐条复用过，可直接当真）

- 本地栈：`infra/docker-compose.full.yml`（Windows 另加 `docker-compose.win-ports.yml`）——trade `127.0.0.1:18080→8080`、Postgres `127.0.0.1:15433→5432`、gateway `:80`，admin 挂 `/admin/`。**改过 trade-service 必须重建容器**，否则打到的是旧 jar（判据：扫服务里的 class 字面量，别看本地 target）。
- 🔴 **admin 产物（原写法在 Windows 上会坑死人）**：改 `clients/admin-vue` 源码后**别用** `node scripts/build-admin.mjs`——Windows 的 Vite 内容哈希与 GitHub Linux CI 不同，产物会被 CI 字节比对判红。正确做法：`bash scripts/build-admin-ci-fidelity.sh`（从干净 HEAD 检出重建），然后把 `services/trade-service/src/main/resources/static/admin` **一并提交**（可单独一笔 `chore(admin-artifacts)`）。
- 运营后台登录（R1 脚本化实测可用）：`GET /api/v2/auth/captcha` 取 `captchaId` → 读 redis `aicabinet:captcha:<captchaId>`（redis 有 requirepass，密码只从 env 取、不落本文档；`docker exec` 喂 stdin 要带 `-i`）→ `POST /api/v2/auth/admin-password-login`，🔴 **请求体字段是 `phoneNumber`，不是 `phone`**（`common-core/dto/PasswordLoginRequest`：`@NotBlank "手机号不能为空"`）。token 存 `sessionStorage.admin_token`、过期时间存 `localStorage.admin_expires`；报 `401 登录已失效` 就是重跑这三步。
- 账号（R0 逐个验过登录）：`13900000001` 超管；`13800138001` 商户侧（绑 MCH-DEFAULT）；`13800138007` 演示补货员。🔴 密码一律从 env / 用户处取，**不写进本目录任何文件**。
- 小程序：mp-weixin 为唯一验收权威（**H5 不投入**；e2e-h5 红不阻塞推送）；appid 来自进程环境 `MP_WEIXIN_APPID_*`（consumer=`wx6dacefb3671de630`，merchant=`wxf7de3625991a6431`）。自动化用 automator（`ws://127.0.0.1:9420`）；合成输入常失效 ⇒ 走 HTTP 登录 + storage 注入。
- 支付/资金类操作只走 dev mock 分支，**禁止真实资金操作**（财务商户轮尤其）。
- 造数纪律（R1 起生效）：测试数据一律 `AUDIT-` 前缀命名，造数脚本/SQL 落 `docs/module-audit/seeds/`；禁止手改共享库里的真实数据；🔴 **用完必须清理并断言计数归 0，再重打一次相关 API 确认口径已恢复**（R1 沉睡用户 22/22 → 0/0 即此流程，脚本见 `seeds/R01-user-behavior-dormant.sql`）。

## 目录结构

```
docs/module-audit/
├── README.md                 # 本页：规则（先读这个）
├── PLAN.md                   # 11 轮计划 + 各轮页面清单快照（每轮开轮要重拉）
├── step0-inventory.mjs       # 🔴 开轮必跑：各模块页面数 + 菜单↔路由差集 + 无权限码页 + 两端 mp 页数
├── ISSUES.md                 # 问题台账（MA-xxx 唯一编号源）
├── seeds/                    # 各轮造数脚本（AUDIT- 前缀，插入/清理成对，见 seeds/README.md）
├── evidence/                 # 截图/trace 等证据，命名 R{NN}-<页>-<现象>.png
└── templates/
    ├── page-checklist.md     # 页面×选项卡审计清单模板（固定维度，保证多 AI 产出可比）
    └── round-report.md       # 轮报告模板
```

轮报告文件名：`rounds/R{NN}-{模块Slug}.md`（如 `rounds/R01-overview.md`），由执行 AI 从模板复制生成。
