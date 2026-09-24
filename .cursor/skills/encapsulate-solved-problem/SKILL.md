---
name: encapsulate-solved-problem
description: >-
  Turns a fixed non-obvious AI Cabinet bug into lasting knowledge: lessons-learned
  row, PROJECT_KNOWLEDGE changelog, optional domain rule/gate, and optional Skill
  update. Use after resolving regressions, UI jitter, MQ/payment pitfalls, or when
  the user asks to 记录问题 / 封装成 skill / 写入文档.
---

# 已解决问题封装

修完**非显而易见 / 易复发**问题后执行本流程；纯 typo 可跳过。

## 清单（同一回合完成）

```
Task Progress:
- [ ] 1. 三列表写入 lessons 或领域 .mdc
- [ ] 2. PROJECT_KNOWLEDGE §9 Changelog 一行
- [ ] 3. 可机器检查 → check 脚本 + audit-gates / CI
- [ ] 4. 高频可复用 → 更新 solved-problems-playbook（或新建领域 Skill）
- [ ] 5. 回复用户时指向落点路径
```

## Step 1 — 现象 → 根因 → 必须怎么做

写入优先级：

1. 已有领域表（例：后台布局 → `admin-layout-anti-jitter.mdc`）
2. 否则 `docs/engineering/lessons-learned.md` 追加一行

```md
| N | 领域 | 一句话现象 | 一句话根因（有证据） | 禁止/必须… | 脚本或路径 |
```

根因必须有实测或代码证据；禁止「可能是」。

## Step 2 — 活文档 Changelog

`docs/PROJECT_KNOWLEDGE.md` §9 顶部追加：

```md
| YYYY-MM-DD | 摘要（含领域） | lessons #N / evidence/... / Skill |
```

若改变规模/端口/模块约定：同步 §2–§5；关闭 §10 对应缺口。

## Step 3 — 门禁（能量化时必做）

- 新增或扩展 `scripts/check-*.mjs`
- 接入 `package.json` 的 `check:audit-gates`（或既有聚合）
- **同步** `.github/workflows/ci.yml`（总册 #89）

## Step 4 — Skill 升格（满足任一时）

| 条件 | 动作 |
|------|------|
| 同域已有配方（布局/MQ/支付/小程序/CI） | 更新 `solved-problems-playbook` 对应小节 |
| 新域且步骤 ≥3、会反复触发 | 新建 `.cursor/skills/<name>/SKILL.md`（见 create-skill 规范） |
| 仅一次性冷门 | 只写 lessons + Changelog，不硬造 Skill |

新 Skill 要求：

- `description`：第三人称 + WHAT + WHEN + 触发词
- 正文 < 500 行；细节链到 lessons / evidence
- 在 `PROJECT_KNOWLEDGE.md` §7.3 与 `ai-cabinet-dev-test` 路由表各加一行

## Step 5 — 回复模板

```
已记录：lessons #N；活文档 Changelog YYYY-MM-DD；
门禁：pnpm check:…（若有）；Skill：…（若有）。
```

## 禁止

- 只说「已修好」不落三列表
- 新知识只留在聊天
- 新建 check 脚本不进 CI
- 为凑数创建空话 Skill（无触发场景、无步骤）
