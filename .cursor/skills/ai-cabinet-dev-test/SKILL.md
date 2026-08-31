---
name: ai-cabinet-dev-test
description: >-
  AI Cabinet 本仓库写代码 / 改 UI / 联调 / 验收 / 调试时的 Skill+MCP 路由。
  Trigger when developing, testing, fixing bugs, verifying admin/H5/mini-program,
  or when the user asks to use skills/MCP for 测试改代码开发.
---

# AI Cabinet 开发与测试路由

开工先读本 skill，再按表加载对应 skill / MCP。**项目规则优先于通用 skill**（尤其 Browser 只能用 Cursor IDE Browser）。

## 1. 必做顺序

```
匹配本表 → Read 命中的 SKILL.md → 调 MCP 取事实 → 再改代码 → 用 MCP/脚本验收 → verification-before-completion
```

## 2. 四类推荐工具（每类选一个，均免费）

| 类别 | 选用 | 路径 |
|------|------|------|
| 安全/漏洞扫描 | `security-scan` → `security-best-practices` | `.cursor/skills/security-scan/` |
| 代码质量审查 | `code-review` | `.cursor/skills/code-review/` |
| 全流程 UI 测试 | `browser-real-testing` | `.cursor/skills/browser-real-testing/` |
| UI 设计规范 | `web-design-guidelines` + `frontend-design` | `.cursor/skills/` |

**未采用（原因）：** Playwright MCP / webapp-testing（项目禁止）；SkillSpector 需 git clone（网络受限时用手动 security-scan）。

## 3. 场景 → Skill → MCP

| 场景 | Skill | MCP |
|------|-------|-----|
| 开工路由 | **本 skill** | — |
| 安全扫描 / SQLi / XSS | `security-scan` | fetch（拉 guidelines） |
| 提交/PR 前审查 | `code-review` | GitHub（PR） |
| 管理后台 / H5 UI 验收 | `browser-real-testing` | **cursor-ide-browser** |
| 小程序 UI | `ui-implementation`；二审 `zhipu-ui-acceptance` | Browser（H5）；dist 核对 |
| 用户录屏 / mp4 / 页面切换动效 | `read-video`（抽帧）+ MCP `media-context`（若已启用） | — |
| UI 规范/a11y | `web-design-guidelines` | fetch |
| 视觉方向 | `frontend-design` | — |
| 全栈联调 | `fullstack-web-development` | Context7；Browser |
| Bug | `systematic-debugging` | Sentry；shell |
| 可测改动 | `test-driven-development` | — |
| 宣称完成 | `verification-before-completion` | Browser / health |
| 后端 API | `api-backend-development` | Context7 |
| 库文档 | — | **Context7** |
| 生产报错 | — | **Sentry** |
| 外部文档 | — | **fetch** |

## 4. 本仓库验收入口

| 面 | URL / 方式 |
|----|------------|
| 运营后台 | `http://localhost/admin/index.html`（`13900000001` / `123456` + 验证码） |
| Admin 构建 | 根目录 `node scripts/build-admin.mjs`（Cursor 下 `pnpm run build:admin` 可能因 script-shell 失败，直接用 node） |
| Gateway | `http://localhost` |
| trade-service | `:8080` 或 `:18080` |
| 消费者 H5 | `pnpm --filter @aicabinet/consumer-mp dev:h5` → `:3002` |
| 商户 H5 | `pnpm --filter @aicabinet/merchant-mp dev:h5` → `:3001` |
| 微信小程序 | 开发者工具 → `clients/consumer-mp/dist/dev/mp-weixin`；**确认 dist mtime** |

## 5. 小程序特别注意

1. 改 `src/` 后 dist 必须更新；watcher 僵死则重启 `dev:mp-weixin`
2. 自定义顶栏用 `getBelowCapsulePadPx()`
3. 余额等放右侧时用 flex + `margin-left: auto`，并避胶囊

## 6. 禁止

- Playwright / browser-use / 第三方浏览器 MCP 冒充 UI 验收
- 未读 skill 就大段实现或宣称通过
- 提交密钥
