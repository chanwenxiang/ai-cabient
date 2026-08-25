# DevOps 工具链（Grafana / Prometheus / Jenkins / SonarQube / GitHub）

本文说明 **ai-cabinet 仓库内** 的 DevOps 集成：`infra/` 负责容器与监控配置，运营后台 **系统 → DevOps 中心** 提供统一入口。

## 架构关系

```
ai-cabinet/                         ← 整个 Git 仓库
├── .github/workflows/ci.yml        ← GitHub Actions（主 CI + Sonar 质量门禁）
├── Jenkinsfile                     ← Jenkins 发布流水线
├── sonar-project.properties
├── scripts/ci/                     ← GHA 与 Jenkins 共用脚本
└── infra/
    ├── docker-compose.full.yml     ← 业务全栈 + Grafana + Prometheus
    └── docker-compose.devops.yml   ← SonarQube + Jenkins + GHA Runner（profile devops）
```

## 协作流程

| 场景 | 触发 | 工具 |
|------|------|------|
| 日常 PR | push / pull_request | **GitHub Actions**：测试 + Sonar（self-hosted runner） |
| 发布 | tag / release 分支 | **Jenkins**：测试 → Sonar → Docker 部署 |
| 运行监控 | 持续 | **Prometheus** 抓取 → **Grafana** 看板 |
| 运营查看 | 登录后台 | **DevOps 中心** 嵌入 Grafana + 工具快捷入口 |

## 一键启动

### 全栈（含 Grafana / Prometheus）

```powershell
.\docker-up.ps1
```

### 全栈 + DevOps 工具（SonarQube / Jenkins / GHA Runner）

```powershell
.\docker-up.ps1 -DevOps
```

或：

```powershell
cd infra
docker compose -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops up -d
```

## 运营后台集成（可以）

**系统 → DevOps 中心**（权限 `ops:devops:view`）已接入：

| 能力 | 方式 |
|------|------|
| Grafana | **iframe 同源嵌入**（Gateway `/devops/grafana/` 反代） |
| Prometheus / Jenkins / SonarQube / GitHub | 状态探测 + 一键外链打开 |

说明：Jenkins / SonarQube UI 较重，后台以状态卡片 + 新窗口打开为主；Grafana 看板可直接在后台内查看。

## 访问地址

| 工具 | URL | 说明 |
|------|-----|------|
| **运营后台 DevOps 中心** | http://localhost/admin/index.html#/devops | 集成入口，嵌入 Grafana |
| Grafana（同源嵌入） | http://localhost/devops/grafana/ | 经 Gateway 反代 |
| Grafana（直连） | http://localhost:13000 | 容器端口 |
| Prometheus | http://localhost:9090 | 指标查询（**官方 UI 无中文**；日常用 Grafana 中文看板） |
| Grafana 中文 | http://localhost/devops/grafana/ | 已默认 `zh-Hans`；也可在个人偏好里改语言 |
| SonarQube | http://localhost:19002 | 首次登录 admin/admin 后改密 |
| Jenkins | http://localhost:19081 | CasC 预配置流水线（勿与 device-service 18081 冲突） |
| GitHub | 见 `infra/.env` 中 `DEVOPS_GITHUB_URL` | 源码与 PR |

Jenkins / SonarQube / Runner 容器默认 `TZ=Asia/Shanghai`（Jenkins 另设 `-Duser.timezone=Asia/Shanghai`），构建历史显示北京时间。

## 运营后台集成

路径：**系统 → DevOps 中心**（权限 `ops:devops:view`）

- 展示 Grafana / Prometheus / Jenkins / SonarQube / GitHub 在线状态
- **iframe 嵌入** Grafana「AI Cabinet 运营概览」看板（`/devops/grafana/...`）
- 各工具支持「新窗口打开」

Gateway 已配置 `/devops/grafana/` 反代；Grafana 开启匿名 Viewer + 允许嵌入（**仅本地 dev**）。

## GitHub Actions 配置

在 GitHub 仓库 **Settings → Secrets** 添加：

| Secret | 值 |
|--------|-----|
| `SONAR_TOKEN` | SonarQube 项目 Token |
| `SONAR_HOST_URL` | Runner 内网地址，如 `http://sonarqube:9000` |

注册 self-hosted runner：

```powershell
$env:GITHUB_REPO_URL = "https://github.com/your-org/ai-cabinet"
$env:GITHUB_RUNNER_TOKEN = "<一次性 registration token>"
.\scripts\devops\register-github-runner.ps1
```

Runner 标签：`self-hosted`, `linux`, `ai-cabinet`（与 `.github/workflows/ci.yml` 中 `runs-on` 一致）。

## Jenkins：本机现在 / 服务器后期

同一套 `Jenkinsfile`，两阶段用法：

| 阶段 | 用哪个任务 | 代码从哪来 |
|------|------------|------------|
| **现在本机** | `*-local`（推荐） | Compose 挂载的 `/workspace`，**不用先推 GitHub** |
| **后期服务器** | 无 `-local` 后缀 | GitHub SCM checkout |

```
本机：  /workspace → Jenkins(*-local) → Sonar →（可选）compose-local
正式：  GitHub → Jenkins → Sonar → compose-local（同机）或 ssh（远程）
```

| 任务 | 何时用 |
|------|--------|
| `ai-cabinet-sonar-dev-local` | 本机扫 `ai-cabinet-dev` |
| `ai-cabinet-release-local` | 本机完整构建；可试 `DEPLOY_MODE=compose-local` |
| `ai-cabinet-sonar-dev` | 正式：GitHub `dev`（需已推送 `Jenkinsfile.sonar-dev`） |
| `ai-cabinet-release` | 正式：GitHub → 部署（需已推送 `Jenkinsfile`） |

运营后台 **系统 → DevOps 中心 → SonarQube 卡片「重跑 Sonar」** 会调用  
`POST /api/v2/ops/admin/devops/sonar/scan`，排队 Jenkins 任务 `ai-cabinet-sonar-dev-local`（需 `ops:devops:scan` 权限）。

**部署参数 `DEPLOY_MODE`：** `none` / `compose-local`（Jenkins 与业务同机）/ `ssh`（凭据 id=`deploy-ssh-key`）。

### 迁到服务器时

1. 推送 `Jenkinsfile`、`Jenkinsfile.sonar-dev`
2. 服务器装 Docker/Compose，仓库放到 `/opt/ai-cabinet`
3. Webhook：`http://<jenkins>:19081/github-webhook/`
4. 改用无 `-local` 任务；同机用 `compose-local`，跳板机用 `ssh`

## SonarQube 中文界面

已内置中文包插件：`infra/sonarqube/plugins/sonar-l10n-zh-plugin-10.4.jar`（compose 自动挂载）。

1. 打开 http://localhost:19002 ，登录 admin
2. 右上角 **A → My Account（我的账户）→ Preferences（偏好设置）**
3. **Language** 选 **中文**（或浏览器语言设为中文后刷新）
4. 若仍是英文：`Administration → Marketplace` 确认 **Chinese Pack** 已安装，然后重启 SonarQube

```powershell
cd infra
docker compose -f docker-compose.full.yml -f docker-compose.devops.yml --profile devops restart sonarqube
```

## 手动检查清单（dev / main 是否扫对）

### 1. 看项目是否分开

打开 **Projects（项目）**，应有两个项目：

| 项目 | 看板 |
|------|------|
| AI Cabinet (dev) | http://localhost:19002/dashboard?id=ai-cabinet-dev |
| AI Cabinet (main) | http://localhost:19002/dashboard?id=ai-cabinet-main |

### 2. 对比代码量（最快判断是不是不同分支）

在各自项目 **Measures（指标）** 或 Overview 看 **Lines of code（代码行数）**：

- **dev** 约 **5000+** 行（当前 dev 分支较新、模块更多）
- **main** 约 **1400** 行（main 较旧）

若两个项目行数几乎一样，说明可能扫了同一份代码，需要重扫。

### 3. 对照 GitHub 提交（dev 可核对 revision）

GitHub 当前提交（可用浏览器或命令查看）：

```powershell
& "C:\Program Files\GitHub CLI\gh.exe" api repos/chanwenxiang/ai-cabient/branches/dev --jq .commit.sha
& "C:\Program Files\GitHub CLI\gh.exe" api repos/chanwenxiang/ai-cabient/branches/main --jq .commit.sha
```

在 Sonar 项目 **Project Settings → Background Tasks / Analysis**（或 Administration → Background Tasks）查看最新分析时间与版本。

`ai-cabinet-dev` 的 revision 应与 **dev 分支 SHA** 一致（本地有 git 时）。

### 4. 本地自己重扫

```powershell
$env:SONAR_TOKEN = "<你的 User Token>"
# dev（当前工作区须在 dev 分支）
.\scripts\ci\run-sonar.ps1 -Branch dev

# main：先切到 main 再扫，或从 GitHub 拉 main 代码后指定目录扫描
.\scripts\ci\run-sonar.ps1 -Branch main
```

### 5. GitHub CI 是否接上

仓库 **Settings → Secrets → Actions** 应有 `SONAR_TOKEN`、`SONAR_HOST_URL`。

**Settings → Actions → Runners** 应有 `ai-cabinet-local` 状态 **Idle/Online**。

推送到 `dev` 或 `main` 后，在 **Actions** 看 `sonar` job 是否绿。

## 本地 Sonar 扫描

Community Edition **不能**在同一项目里同时分析 `dev` / `main`。本仓库用两个项目：

| 分支 | Project Key | 看板 |
|------|-------------|------|
| `dev`（含 develop / PR） | `ai-cabinet-dev` | http://localhost:19002/dashboard?id=ai-cabinet-dev |
| `main` | `ai-cabinet-main` | http://localhost:19002/dashboard?id=ai-cabinet-main |

扫描范围（`sonar-project.properties`）：

| 模块 | 路径 |
|------|------|
| trade-service / device-service / common-core | `services/...` |
| admin-vue | `clients/admin-vue/src` |
| consumer-mp（消费者小程序） | `clients/consumer-mp/src` |
| merchant-mp（商户小程序） | `clients/merchant-mp/src` |

```powershell
$env:SONAR_TOKEN = "<token>"
.\scripts\ci\run-sonar.ps1 -Branch dev    # 默认
.\scripts\ci\run-sonar.ps1 -Branch main
```

若要真正的单项目多分支 / PR 装饰，需升级 SonarQube **Developer Edition+**。

旧项目 `ai-cabinet` 可在 UI 删除，避免混淆。

## 业务告警规则（Prometheus）

规则文件：`infra/prometheus/alert_rules.yml`（与 `CabinetMetrics` / `DeviceMqttMetrics` 指标一致）。

| 规则 | 阈值（摘要） | 业务含义 |
|------|--------------|----------|
| DoorOpenSuccessRateLow / Critical | 成功率 &lt; 95% / 90% | 开门履约恶化 |
| DeviceOfflineRateHigh / DeviceAllOffline | 离线率 &gt; 20% / 全离线 | 柜机在线 |
| RecognitionLatencyHigh | 识别均值 &gt; 5s | 视觉耗时 |
| SettlementFailureRateHigh | 结算失败率 &gt; 10% | 支付结算 |
| DisputeSessionRateHigh | 争议 QPS 偏高 | 客服 SLA |
| ReconciliationMismatch | 1h 内出现 MISMATCH | 对账差额 |
| MqttTradeForwardFailures / MqttCommandAckTimeout | MQTT 转发/ACK 异常 | 设备链路 |

查看：http://localhost:9090/alerts ；Grafana 看板：运营后台 **DevOps 中心**。

## 资源占用

SonarQube + Jenkins 约 **4–6 GB** 内存。日常开发可只跑 `docker-up.ps1`；需要质量门禁/发布时再 `-DevOps`。

## 安全提示

- Jenkins / SonarQube / Grafana 匿名嵌入 **勿暴露公网**
- Token 只放 GitHub Secrets 与 `infra/.env`（已在 `.gitignore`）
- 生产环境关闭 Grafana 匿名访问，改用 SSO 或独立监控域名
