---
name: browser-real-testing
description: Verify UI, pages, and web flows with Cursor's built-in Browser MCP only. Use when testing UI, acceptance, smoke tests, visual checks, page behavior, or when the user asks to open/verify a local or remote web app in the browser.
---

# Browser Real Testing

## Hard rules

- Use **only** Cursor IDE Browser MCP (`cursor-ide-browser`).
- **Do not** use Playwright, Puppeteer, browser-use, or third-party browser MCP/skills for UI verification.
- Do not claim UI pass from curl, logs, or screenshots alone without Browser MCP evidence.

## Workflow

1. Confirm the app URL (local `http://localhost:...` or deployed preview).
2. `browser_tabs` list → `browser_navigate` to the URL.
3. On an existing tab: `browser_lock` → interact → unlock when fully done.
4. Prefer `browser_snapshot` for structure; `browser_take_screenshot` for visual checks.
5. Exercise the user path (click, type, fill, scroll). Re-snapshot after meaningful state changes.
6. Report: URL, what was verified, pass/fail, blockers (login, captcha, missing data).

## Stop conditions

- After ~4 failed attempts on the same action without new evidence, stop and report.
- If login/passkey/captcha/manual permission is required, hand off to the user.

## Evidence format

- What you opened
- Steps taken
- Observed result vs expected
- Residual risks (untested paths)
