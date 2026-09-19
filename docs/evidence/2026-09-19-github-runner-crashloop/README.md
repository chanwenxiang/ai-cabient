# 修 GitHub self-hosted runner 崩溃循环（2026-09-19）

## 症状

容器 `ai-cabinet-github-runner-1` 无限重启，`RestartCount=276`、`ExitCode=1`，日志里反复：

```
POST https://api.github.com/actions/runner-registration -> 404
```

## 根因（一条，但被两个坑放大）

镜像 `myoung34/github-runner` 的 entrypoint 用 `ACCESS_TOKEN` 在**容器启动时现取**一个注册令牌
（`ACCESS_TOKEN=… bash /token.sh → RUNNER_TOKEN=$(… | jq -r .token)`）。注册令牌**有效期仅 1 小时**。

而那个出问题的容器是**手工 `docker run` 起的**，传进去的是**一次性注册令牌本身**（29 字符的旧值）。
1 小时后令牌过期 ⇒ entrypoint 取不到新令牌 ⇒ 注册 404 ⇒ 容器退出 ⇒ `restart: unless-stopped` 拉起 ⇒ 再 404 …

放大它的两个坑：

1. **变量名误导**：compose 里写的是 `ACCESS_TOKEN: ${GITHUB_RUNNER_TOKEN}` —— 变量名带 `RUNNER_TOKEN`，
   但这里**必须**是 PAT。照名字填一次性注册令牌就必然踩坑。
2. **挂载只在手工容器里**：Maven 缓存（`D:/devTools/repository`）与阿里云 settings 两处挂载
   此前**只存在于手工容器、不在版本管理内** ⇒ 改用 compose 重建时会**静默丢失**（构建改走 Maven Central，
   慢甚至超时），且没有任何报错。

## 修复

| 改动 | 文件 |
|---|---|
| 删掉手工容器，改用 compose 重建 | 操作 |
| 明确 `ACCESS_TOKEN` 必须是 classic PAT，并把 404 成因写进注释 | `infra/docker-compose.devops.yml` |
| 补回两处 M2 挂载（带可覆盖默认值） | `infra/docker-compose.devops.yml` |
| **解耦 SonarQube**：去掉 `depends_on: sonarqube` | `infra/docker-compose.devops.yml` |
| 报错文案改为要求 classic PAT（原先引导去填注册令牌） | `scripts/devops/register-github-runner.ps1` |

`depends_on: sonarqube(service_healthy)` 原写法会让「只想起 runner」变成**必须连带拉起 3GB 的 SonarQube**
并等它健康（`start_period: 180s`）—— 两个互不相干的 devops 服务被绑死。

`register-github-runner.ps1` 另补了 **UTF-8 BOM**：该脚本含非 ASCII（`→`）而无 BOM，在 PowerShell 5.1 下会**乱码**。

## 复核（2026-09-19 重新取证，非自报）

```
$ docker ps -a --filter name=github-runner
ai-cabinet-github-runner-1   Up 33 minutes   ai-cabinet/github-runner:2.321.0

$ docker inspect ai-cabinet-github-runner-1
RestartCount=0  Status=running  Started=2026-09-19T15:21:08Z

$ docker inspect … --format '{{range .Mounts}}…'
D:/devTools/repository          -> /root/.m2/repository
C:/Users/cwx/.m2/settings-container.xml -> /root/.m2/settings.xml

$ curl -H "Authorization: token $PAT" …/actions/runners
name=ai-cabinet-local  status=online  busy=False     total_count=1
```

⇒ 四项判据同时满足：**不再重启（0）＋ 进程在跑 ＋ 两处挂载在场 ＋ GitHub 侧 online**。
（只报「容器 Up」是不够的：崩溃循环里的容器大部分时间也是 Up。）

## 残留风险 / 未做

- `ACCESS_TOKEN` 是 **classic PAT（40 字符，`repo` 作用域）**，**URL/令牌即凭据**；
  它只存在于 `infra/.env`，该文件已被 `.gitignore:36` 忽略（已核 `git check-ignore -v` ⇒ 未跟踪）。
- **没有**验证 runner 能真正跑通一个 job（那需要一次真实 workflow 触发）—— 本批只证「注册与在线」。
