---
name: api-backend-development
description: Design and implement backend APIs, services, auth, data access, and server logic. Use when building REST/GraphQL APIs, Node/Python/Go backends, database layers, middleware, or when the user asks for backend/server work.
---

# API / Backend Development

## Before coding

1. Context7 MCP for framework/library docs (Express, FastAPI, Nest, Prisma, Drizzle, etc.).
2. If Postgres/DB MCP is configured, inspect schema before inventing tables or queries.
3. For new behavior: follow `test-driven-development` (failing test first).
4. For bugs: follow `systematic-debugging` before patches.

## Defaults

- Explicit input validation at the boundary (schema/zod/pydantic/etc. matching the stack).
- Clear error shapes; no silent catch-and-ignore.
- AuthZ checks next to the resource, not only at the edge.
- Migrations for schema changes; never “edit prod by hand” in agent workflows.
- Secrets only via env / secret managers — never commit credentials.

## Delivery checklist

- [ ] Happy path + key failure cases covered by tests
- [ ] Status codes and error payloads documented or mirrored in existing style
- [ ] Logging/observability hooks match the project (Sentry MCP when investigating prod)
- [ ] No N+1 or unbounded queries introduced without note
