# `docker-up.ps1` 退出码假红 —— 根因与修复

**日期**：2026-09-19 · **脚本**：仓库根 `docker-up.ps1` · **状态**：已修复并端到端验证

---

## 1. 现象（上一轮真实故障）

上一轮执行 `. \docker-up.ps1 -NoBuild`（外层用 `*>` 重定向到日志）时：

```
EXITCODE=1
日志内容只有一行：IMAGE_TAG=local
```

**但容器其实全部重建成功了**——12/12 容器 `Up`、创建时间为本次、`/actuator/health` = `UP`。
「脚本报错退出」与「实际成功」相互矛盾 ⇒ 典型**假红**（success 被读成 failure）。

## 2. 根因

`docker-up.ps1` 第 7 行设 `$ErrorActionPreference = "Stop"`，第 51 行直接调用原生命令：

```powershell
& docker @composeArgs
```

**PowerShell 5.1 在 `ErrorActionPreference='Stop'` 下，会把 native exe 写到 stderr 的每一行
包成 `NativeCommandError` 并立即终止脚本** —— 而当调用方用 `*>` / `2>&1` 把脚本输出重定向时，
native 的 stderr 会被并入 PowerShell 的 error 流，于是这条规则生效。

而 `docker compose up -d` **恰恰把进度与状态写到 stderr**（`Container xxx Running`、
`#5 [trade-service] Building` 等）。结果：compose 正常跑完、容器都已就绪，
**脚本却在 native 调用返回处被终止**，退出码 1、后续健康检查与提示全部没执行。

> 关键点：**这不是偶发**。即使容器无需重建（幂等 `up -d`），compose 仍会往 stderr 写
> `Container xxx Running` ⇒ 只要外层做了重定向，假红**必然**触发。

## 3. 证据 A：定向探针（隔离复现该机制）

`eap-probe-report.txt` —— 用 `node -e "console.error(...)"` 充当"往 stderr 写一行的 native 程序"，
两种写法在外层 `*>` 重定向下对比：

| 写法 | 退出码 | 是否走到末尾 | 捕获到的异常 |
|---|---|---|---|
| `$ErrorActionPreference='Stop'` + `& native`（修复前） | **-1** | **否**（`REACHED-END` 出现 0 次） | `RemoteException: ##5 trade-service Building` |
| `Invoke-NativeCommand`（修复后） | **0** | **是**（出现 1 次，`native_exit=0`） | 无 |

修复后 stderr 仍被记录（可以在日志里看到 `node.exe : ##5 ...` 与 `NativeCommandError` 字样），
**但不再终止脚本** —— 这正是期望行为：保留输出、按退出码判成败。

## 4. 证据 B：真实端到端对照（同目录、同一环境）

`ab-report.txt` + `run-A-HEAD-old.txt` + `run-B-fixed.txt`。
A 组是 `git show HEAD:docker-up.ps1` 导出的**未修改版**，B 组是工作区**已修复版**；
两者都在仓库根执行（`$PSScriptRoot` 指向同一 `infra/`），外层同样用 `*>` 重定向。

| | A（HEAD 旧版） | B（修复后） |
|---|---|---|
| 退出码 | **-1** | **0** |
| 耗时 | 1.8s | 4.5s |
| 日志行数 | **1**（只有 `IMAGE_TAG=local`） | **35** |
| 出现 `app stack is ready` | 0 次（中途终止） | **1 次（走到末尾）** |
| 捕获异常 | **`RemoteException: Container ai-cabinet-redpanda-1 Running`** | 无 |

> A 组的表现与上一轮真实故障**逐字一致**（日志同样只有 `IMAGE_TAG=local` 一行后中断），
> 且中止原因直接指向 `Container ... Running` 这行 **docker 的正常状态输出** ⇒ 根因确证。
>
> B 组日志尾部包含完整的就绪提示与访问地址清单（`Admin:` / `API:` / `XXL-JOB:` / `MinIO:`），
> 说明健康检查等后续步骤全部执行完毕。

## 5. 修复

新增 `Invoke-NativeCommand` 助手（`docker-up.ps1` 顶部）：调用原生命令期间**临时把
`$ErrorActionPreference` 降为 `Continue`**，`finally` 中恢复；退出码经 `[ref]` 带回，
成败**一律以 `$LASTEXITCODE` 为准**。输出不做任何管道包装，调用方的重定向照常生效。

两处调用点改为该助手：`docker compose up -d`（原 51 行）与失败诊断用的 `docker compose ps`（原 84 行）。

同时把 `$env:IMAGE_TAG` 的钉法与既有注释保留不动 —— 本修复**只改退出码语义，不改任何容器行为**。

### 附带：文件编码

编辑后回验发现该脚本被写成了 **CRLF**（仓库 63 个 `.ps1` **全部为 LF**），且 BOM 状态与 HEAD 不一致。
已统一为 **LF + UTF-8 BOM**（脚本本次新增了非 ASCII 注释，无 BOM 时 PS 5.1 会按 ANSI 读成乱码）。
`check:line-endings` 门禁复核通过。

## 6. 🔴 这份证据**没有**证明什么

- **没有**证明「所有 PowerShell 脚本都不会假红」——只证明了 `docker-up.ps1` 这两处 native 调用
  的退出码语义已正确。其它脚本里若存在「`ErrorActionPreference='Stop'` + 外层重定向 + native 写 stderr」
  的同一组合，仍会假红（本仓 `scripts/**` 尚未逐个排查）。
- **没有**做跨机器验证：`NativeCommandError` 的行为与 PowerShell 版本绑定，本证据限于
  **PowerShell 5.1.26100.9444**（`ab-report.txt` 首行有版本号）。
- **没有**证明容器重建本身的正确性——容器是否就绪由 `stop-grace-applied-after-rebuild.txt`
  与 `/actuator/health` 另行取证。
- 探针（第 3 节）用的是 **node 模拟** native stderr，不是真的 docker；真实 docker 的证据在第 4 节，
  两者是「机制复现 + 端到端确认」的关系，缺一不可。

## 7. 文件清单

| 文件 | 内容 |
|---|---|
| `ab-report.txt` | 真实端到端 A/B 对照的结构化报告（含退出码、耗时、日志行数、异常） |
| `run-A-HEAD-old.txt` | A 组（HEAD 未修改版）完整输出 —— 1 行即中断 |
| `run-B-fixed.txt` | B 组（已修复版）完整输出 —— 35 行，走到就绪提示 |
| `eap-probe-report.txt` | 定向探针 A/B 报告（隔离验证 `ErrorActionPreference` 机制） |
| `ps1-syntax-check.txt` | 三个相关 `.ps1` 的语法解析结果（errors=0）+ here-string 配对计数 |
