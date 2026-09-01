---
name: browser-real-testing
description: >-
  Verify UI, pages, and web flows with Playwright MCP/CLI first (fallback to
  Cursor IDE Browser). Use when testing UI, acceptance, smoke tests, visual
  checks, page behavior, or when the user asks to open/verify a local or remote
  web app in the browser.
---

# Browser Real Testing

## Hard rules

- Prefer **Playwright MCP**, then **Playwright CLI** (`playwright` / `playwright-cli` skill).
- Fallback to Cursor IDE Browser MCP (`cursor-ide-browser`) only when Playwright is unavailable; tell the user.
- Do not claim UI pass from curl, logs, or screenshots alone without real browser evidence.

## Workflow (Playwright first)

1. Confirm the app URL (local `http://localhost:...` or deployed preview).
2. Drive the browser via Playwright MCP tools, or CLI (`playwright-cli` / skill wrapper).
3. Prefer headed mode when the user should see the run; note headless if used.
4. Exercise the user path (navigate, click, type, fill). Re-snapshot/screenshot after meaningful state changes.
5. Report: URL, what was verified, pass/fail, blockers (login, captcha, missing data).

## Fallback (IDE Browser)

1. `browser_tabs` list → `browser_navigate` to the URL.
2. On an existing tab: `browser_lock` → interact → unlock when fully done.
3. Prefer `browser_snapshot` for structure; `browser_take_screenshot` for visual checks.

## Stop conditions

- After ~4 failed attempts on the same action without new evidence, stop and report.
- If login/passkey/captcha/manual permission is required, hand off to the user.

## Evidence format

- What you opened
- Tool used (Playwright MCP / CLI / IDE Browser)
- Steps taken
- Observed result vs expected
- Residual risks (untested paths)
