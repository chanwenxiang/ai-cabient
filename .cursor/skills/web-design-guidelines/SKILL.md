---
name: web-design-guidelines
description: >-
  Review UI code for Web Interface Guidelines compliance (Vercel).
  Use when asked to review UI, check accessibility, audit design/UX,
  or check site against web best practices.
---

# Web Interface Guidelines

Review files for compliance with Vercel Web Interface Guidelines.

## How It Works

1. Fetch latest rules from:
   `https://raw.githubusercontent.com/vercel-labs/web-interface-guidelines/main/command.md`
2. Read target files (user指定或改动涉及的 `.vue` / `.tsx` / `.css`)
3. Check against all rules
4. Output terse `file:line` findings

Use **fetch MCP** or WebFetch when rules may have updated.

## When to Use

- 改 admin-vue / uni-app 页面后做 UI/UX/a11y 自查
- 用户要求「审查 UI / 无障碍 / 设计规范」
- code-review 的 UI/样式维度补充

## Output Format

按文件分组，`file:line` 格式，简洁列问题；无问题写 `✓ pass`。

## Install note

`npx skills add … -g` 会失败：`PromptScript does not support global skill installation`。  
本 skill **只装项目级**（`.cursor/skills/web-design-guidelines/`），不要加 `-g`。  
正确命令示例：`npx skills add vercel-labs/agent-skills@web-design-guidelines -y -p`

## Notes for AI Cabinet

- 小程序：重点看 touch/safe-area/typography；部分 Web 规则（`<a>` 导航）用 uni 等价物
- Element Plus：icon-only 按钮补 `aria-label`；表单 label 关联
- 与 `frontend-design` 分工：本 skill 查规范合规，frontend-design 管视觉方向
