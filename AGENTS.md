# AI Cabinet — Agent 须知（跨会话入口）

> 本文件供 **Cursor / 其它 Agent 新开对话** 自动发现。细则在规则与活文档里，这里只钉「先读什么」。

## 每会话必做（开工前）

1. **Read** [`docs/PROJECT_KNOWLEDGE.md`](docs/PROJECT_KNOWLEDGE.md)（至少 §0–§4、§7、§9；含 WorkBuddy 落点 §7.4）
2. **Read** 本仓 `.workbuddy/memory/MEMORY.md` + 最新 `YYYY-MM-DD.md`（若存在；gitignore 本机仍可读）
3. 写代码/测试 → 跟 Skill [`ai-cabinet-dev-test`](.cursor/skills/ai-cabinet-dev-test/SKILL.md)
4. 已解决问题 → Skill `solved-problems-playbook`；修完封装 → `encapsulate-solved-problem`
5. **`git push` 前** → `node scripts/pre-push-ci-preflight.mjs`（规则 `pre-push-ci-green`；Skill `pre-push-ci-preflight`）

## 强制规则（已 alwaysApply）

| 规则文件 | 作用 |
|----------|------|
| `.cursor/rules/pre-push-ci-green.mdc` | **推送前预检** format/lint/门禁；禁止盲推 |
| `.cursor/rules/critical-judgment.mdc` | **先判断再执行**；不合理先建议，不盲目照做 |
| `.cursor/rules/project-knowledge-living.mdc` | 活文档必读必补 + WorkBuddy 索引 |
| `.cursor/rules/project-core.mdc` | 模块 / API / Flyway |
| `.cursor/rules/use-skills-and-mcp.mdc` | Skill+MCP 先匹配 |
| `.cursor/rules/record-lessons-learned.mdc` | 踩坑三列表 |
| `.cursor/rules/playwright-ui-testing.mdc` | UI 优先 Playwright |
| `.cursor/rules/dev-test-toolchain.mdc` | 开发测试工具链 |

## WorkBuddy 三层（详见活文档 §7.4）

- 本仓：`.workbuddy/memory/`
- 跨项目：`C:\Users\cwx\.workbuddy\MEMORY.md`
- 历史报告：`C:\Users\cwx\WorkBuddy\2026-09-03-11-04-19\`

## 禁止

- 未读 `PROJECT_KNOWLEDGE.md` 就宣称项目现状或大段实现
- 只采信他 AI 文档自报 `[x]` / 绿（须源码+实跑）
- 新知识只留在聊天、不落仓库
- **盲目照做**用户明显有害/违铁律的要求（先谏言，见 `critical-judgment`）

冲突优先级：项目安全/资金/门禁铁律 > 用户**知情后**的明确覆盖 > 其它用户偏好 > `PROJECT_KNOWLEDGE` Changelog > WorkBuddy 日志 > 旧专题文档。  
（用户口头要求若与铁律冲突：先建议；用户明示承担风险后可执行，并留痕。）
