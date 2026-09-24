---
name: project-knowledge
description: >-
  Reads and updates the AI Cabinet living project knowledge doc
  (docs/PROJECT_KNOWLEDGE.md). Use at conversation start for repo context,
  when answering architecture/ports/modules questions, after fixing bugs, or
  when the user asks to refresh/supplement 全局文档 / 项目知识 / living doc.
---

# 全局项目知识维护

## 何时用

- 会话开工、不清楚仓库现状
- 用户问模块 / 端口 / 约定 / 已解决问题
- 修完问题或发现文档过时，需要补充活文档

## 步骤

1. **Read** `docs/PROJECT_KNOWLEDGE.md`（§0–§4、§7、§9 必读；按任务再读其它节）。
2. **并行读 WorkBuddy**（`PROJECT_KNOWLEDGE` §7.4）：
   - 本仓：`.workbuddy/memory/MEMORY.md` + 最新 `YYYY-MM-DD.md`；指到 `§n` 再读 `PROJECT-REFERENCE.md`
   - 工具链假绿/假红：`C:/Users/cwx/.workbuddy/MEMORY.md` → `CROSS-PROJECT-REFERENCE.md`
   - 早期审查报告：`C:/Users/cwx/WorkBuddy/2026-09-03-11-04-19/`（易过期）
   - ⚠️ 勿写 `~/.workbuddy/memory/<uuid>_memory.md`（云缓存会被覆盖）
3. 按 §7 跳转领域规则 / Skill / 专题文档；**不要**把全部专题塞进上下文。
4. 完成任务后判断是否触发补充（见规则 `project-knowledge-living`）：
   - 规模/端口/模块变了 → 改 §2/§3/§5
   - 新坑已修 → §9 Changelog 一行 + `lessons-learned` 明细
   - 缺口 → §10
5. Changelog 格式：

```md
| YYYY-MM-DD | 一句话摘要 | lessons #N / evidence 路径 / Skill 名 |
```

6. 若流程可复用 → 继续执行 `encapsulate-solved-problem`。

## 边界

- 本 Skill **不替代** `ai-cabinet-dev-test`（开发测试仍走总路由）。
- 明细踩坑仍写 `docs/engineering/lessons-learned.md`，此处只索引。
- WorkBuddy 日志若更新了未决项，同步改本文件 §10 或 Changelog，避免两套真相。
- 冲突：用户指令 / 项目 `.mdc` > 活文档最新 Changelog > 本仓 `.workbuddy` 日志 > `~/WorkBuddy` 历史报告 > 旧专题数字。
- **不采信**他 AI 文档自报 `[x]` / 绿：须源码+实跑重取证。
- `.workbuddy/` 已在 `.gitignore`：本机可读，但**不会进 git**；可对外结论请同步到 `docs/`。
