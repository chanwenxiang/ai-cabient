---
name: pre-push-ci-preflight
description: >-
  Runs local CI preflight before git push (format, lint, audit-gates, migration
  gates, admin artifact sync). Use when committing, pushing, fixing CI, or when
  the user asks to 保证 CI 不报错 / format check / 推送前检查.
---

# 推送前 CI 预检

## 何时用

- 任何 `git push` / 「提交并推送」之前
- CI 红了要本地复现 format/lint/门禁时

## 步骤

1. **直调**（禁止只信 `pnpm run` 退出码）：

```bash
node scripts/pre-push-ci-preflight.mjs
```

2. 失败则按输出修：
   - format → `node node_modules/prettier/bin/prettier.cjs --write <paths>`
   - lint → `node node_modules/eslint/bin/eslint.js <paths> --fix`
   - audit-gates → 修对应 `scripts/check-*.mjs` 指出的违规
   - static/admin 脏 → `node scripts/build-admin.mjs` 并提交产物
   - OpenAPI 提示 → 起 trade → `pnpm gen:api-types` 并提交 generated

3. 再跑预检至 exit 0，再 push。

4. Push 后：`gh run list --branch <branch> --limit 3`；失败用 `gh run view <id> --log-failed`。

## 档位

| 场景 | 命令 |
|------|------|
| 常规推送 | `node scripts/pre-push-ci-preflight.mjs` |
| 仅 md/规则小改 | `… --quick`（仍建议默认档） |
| API/admin 大改 | `… --full` |

## 不覆盖

e2e-h5、整模块 `mvn verify`。见规则 `pre-push-ci-green`。

## 相关

- `package.json`: `preflight` / `preflight:quick` / `preflight:full`
- CI：`.github/workflows/ci.yml`
- 门禁链：`node scripts/run-audit-gates.mjs`
