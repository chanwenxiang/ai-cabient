---
name: ui-implementation
description: Implement product UI and frontend visuals with design intention and browser verification. Use when building pages, components, layouts, styling, landing pages, dashboards UI, or when the user asks for frontend/UI work.
---

# UI Implementation

## Skill chain

1. Read and follow `frontend-design` for visual direction (palette, type, layout, signature).
2. If Figma is the source of truth, also use `figma` / `figma-implement-design` / `figma-use`.
3. Prefer Context7 MCP for framework/component library APIs (React, Next.js, Tailwind, etc.).
4. After meaningful UI changes, verify with `browser-real-testing`.

## Implementation order

1. Clarify page job, audience, and brand constraints.
2. Define CSS variables / design tokens before spraying utilities.
3. Build structure → typography/color → interaction states → motion (2–3 intentional motions max unless brief asks more).
4. Mobile + desktop both must work; check reduced-motion when adding animation.
5. Verify in Browser MCP; fix before declaring done.

## Avoid

- Generic purple gradients, cream+terracotta+serif defaults, broadsheet newspaper layouts (unless the brief requires them).
- Card spam in heroes; dashboard chrome on marketing first viewport.
- Declaring UI done without Browser MCP check when a runnable URL exists.
