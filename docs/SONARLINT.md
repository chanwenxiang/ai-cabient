# Cursor / VS Code 接入 SonarLint（开发期免全量扫描）

全量 Sonar 扫描仍由 **GitHub Actions**（`.github/workflows/sonar.yml`）在 push/PR 或后台「重跑 Sonar」时执行。  
日常写代码用 **SonarLint** 在 IDE 里即时标出问题，不必每次改完都跑全量 Scanner。

## 1. 安装扩展（Cursor）

1. `Ctrl+Shift+X` 打开扩展
2. 搜索 **SonarLint**（发布者 SonarSource）
3. 安装 `SonarSource.sonarlint-vscode`

本仓库已在 `.vscode/extensions.json` 里推荐该扩展，Cursor 会提示安装。

## 2. 连接本机 SonarQube（Connected Mode）

先确保 SonarQube 已启动（`.\docker-up.ps1 -DevOps`），并在 Sonar 生成 **User Token**（My Account → Security）。

在 **用户设置**（不要提交 Token）里加入：

```json
{
  "sonarlint.connectedMode.connections.sonarqube": [
    {
      "connectionId": "ai-cabinet-local",
      "serverUrl": "http://localhost:19002",
      "token": "<你的 SONAR_TOKEN>"
    }
  ]
}
```

工作区已配置项目绑定（见 `.vscode/settings.json`）：

- 连接 ID：`ai-cabinet-local`
- 项目 Key：`ai-cabinet-dev`（main 分支可改为 `ai-cabinet-main`）

保存后 SonarLint 会同步服务端规则与质量配置，打开 Java/Vue/TS 文件即可看到波浪线提示。

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
