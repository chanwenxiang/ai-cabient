---
name: fullstack-web-development
description: End-to-end web feature work spanning frontend, backend, and integration. Use when building full-stack features, connecting UI to APIs, scaffolding apps, or when the user asks for 前后端 / full-stack development.
---

# Full-Stack Web Development

## Process skills first

- New feature / greenfield: `brainstorming` → `writing-plans` (then implement).
- Bug: `systematic-debugging` first.
- Implementation: `test-driven-development` unless user explicitly waives.

## Split the work

| Layer | Skill / MCP |
|-------|-------------|
| UI / pages | `ui-implementation` + `frontend-design` |
| API / data | `api-backend-development` + Context7; DB MCP if available |
| Docs / libs | Context7 (`resolve-library-id` → `query-docs`) |
| UI verify | `browser-real-testing` |
| Ship | `devops-operations` + `vercel-deploy` / `cloudflare-deploy` as relevant |
| Prod errors | Sentry MCP + `sentry` skill |

## Integration order

1. Contract first: request/response shapes shared or typed across boundary.
2. Backend + tests green for the contract.
3. Frontend against real local API (not mocks-only) when feasible.
4. Browser MCP smoke on the critical path.
5. Only then deploy/preview.
