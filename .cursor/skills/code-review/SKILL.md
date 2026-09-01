---
name: code-review
description: >-
  AI Cabinet 代码质量审查：安全、逻辑、性能、风格四维度，输出 P0/P1/P2 报告。
  Use before commit/PR, after large diffs, or when user asks for 代码审查/质量检测.
---

# Code Review（AI Cabinet）

## 何时触发

- 用户要求审查 / 提交前 / PR 前
- 改动 >200 行或跨模块（前后端+小程序）
- 修 bug 后需确认无回归

## 审查顺序

1. **安全 P0** — 读 `security-scan` skill；查鉴权、SQL、XSS、密钥、内部 API Key
2. **逻辑 P0/P1** — 业务边界、空值、并发、keep-alive/query 同步、前后端 DTO 一致
3. **性能 P1** — N+1、全表 scan、前端大列表无分页、重复请求
4. **风格 P2** — 命名、与周边代码一致、无无关 diff

## 本仓必查项

| 模块 | 检查点 |
|------|--------|
| trade-service | `@Transactional`、权限注解、`/internal/v1` + `X-Internal-Api-Key` |
| admin-vue | keep-alive 页 `applyRouteQuery` / `reloadFromRouteQuery`；Browser 可验则必验 |
| uni-app | 自定义顶栏 `getBelowCapsulePadPx`；改 src 后 dist 时间戳 |
| Flyway | 仅 `V{n}__*.sql`；DTO 在 common-core |
| 配置 | 无密钥入库；用环境变量 |

## 输出格式

```markdown
## 代码审查摘要
- P0: n  - P1: n  - P2: n

### P0 — 必须修
- [ID-1] 文件:行 — 问题 — 建议

### P1 — 应修
...

### P2 — 建议
...

### 已确认 OK
- ...
```

## 与 UI 审查配合

- 涉及页面：追加 `web-design-guidelines` 或 `frontend-design`
- 需真实点击：`browser-real-testing`（优先 Playwright MCP/CLI）
