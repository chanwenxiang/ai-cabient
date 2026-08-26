# Cursor / VS Code 接入 SonarLint（开发期免全量扫描）

全量 Sonar 扫描仍由 **GitHub Actions**（`.github/workflows/sonar.yml`）在 push/PR 或后台「重跑 Sonar」时执行。  
日常写代码用 **SonarLint** 在 IDE 里即时标出问题，不必每次改完都跑全量 Scanner。

## 1. 安装扩展（Cursor）

1. `Ctrl+Shift+X` 打开扩展
2. 搜索 **SonarLint**（发布者 SonarSource）
3. 安装 `SonarSource.sonarlint-vscode`

本仓库已在 `.vscode/extensions.json` 里推荐该扩展，Cursor 会提示安装。

## 2. 连接本机 SonarQube（Connected Mode + MCP）

先确保 SonarQube 已启动：`.\docker-up.ps1 -DevOps`。  
官方 `mcp/sonarqube` 要求 **Community Build ≥ 25.1**（本仓 `infra/docker-compose.devops.yml` 使用 `sonarqube:25.12.0.117093-community`）。

### 2.1 给 AI 用的 SonarQube MCP（推荐，避开 Webview）

Cursor 侧栏「Configure SonarQube MCP」容易触发 Webview / Service Worker 报错。**不要点那个按钮**，直接改用户级 MCP 配置：

文件：`%USERPROFILE%\.cursor\mcp.json`

```json
"sonarqube": {
  "command": "docker",
  "args": ["run", "-i", "--rm", "--init", "--pull=always", "-e", "SONARQUBE_TOKEN", "-e", "SONARQUBE_URL", "mcp/sonarqube"],
  "env": {
    "SONARQUBE_TOKEN": "<infra/.env 里的 SONAR_TOKEN>",
    "SONARQUBE_URL": "http://host.docker.internal:19002"
  }
}
```

注意：Token **不要**写成字符串 `"null"`；Docker 访问本机 Sonar 须用 `host.docker.internal`，不能用容器内的 `localhost`。

若误点侧栏「Configure SonarQube MCP」并出现 *doesn't have a token configured*：

1. 弹窗选 **Cancel**（不要 Proceed Anyway），或已经写坏了就跑：
   ```powershell
   .\scripts\devops\fix-sonarqube-mcp.ps1
   ```
2. `Ctrl+Shift+P` → **Developer: Reload Window**
3. 若弹出 *Migrate tokens to secure storage* → 点 **Migrate**

改完后也可在 **Cursor Settings → MCP** 刷新 `sonarqube`。

### 2.2 IDE 波浪线（SonarLint Connected Mode）

工作区已绑定（`.vscode/settings.json` + `.sonarlint/connectedMode.json`）：

- 连接 ID：`ai-cabinet-local`
- 项目 Key：`ai-cabinet-dev`

用户设置里保留连接（Token 用 `infra/.env` 的 `SONAR_TOKEN`）。若侧栏 Webview 报错，忽略 UI，以文件配置为准。

## 3. 和全量扫描的分工

| 方式 | 何时用 | 覆盖 |
|------|--------|------|
| **SonarLint** | 边写边看 | 当前文件/模块，规则与门禁对齐 |
| **GHA `sonar.yml`** | push、PR、后台按钮 | 全仓 + 覆盖率 + 质量门禁 |

覆盖率、重复率、历史趋势只有服务端扫描才有；IDE 插件不能替代。

## 4. 常见问题

- **连不上 Sonar**：确认 `http://localhost:19002` 可访问，Token 有效。
- **规则不一致**：Connected Mode 绑定 `ai-cabinet-dev` 后会跟服务端项目配置走。
- **uni-app `rpx` 误报**：全量扫描已在 `sonar-project.properties` 压制；SonarLint 对 CSS 仍可能提示，可对该条规则在 IDE 里 Mark as resolved / 忽略。
