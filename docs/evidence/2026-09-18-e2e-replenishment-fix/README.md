# 补货 E2E「恒 no gaps」失效修复 —— 证据（2026-09-18）

## 一、问题

`scripts/e2e-replenishment.ps1` 长期跑不到第 3 步之后：第 3 步恒返回 `no gaps`，
第 5 步的签到契约用例永远不执行 ⇒ 这条「补货全链路」的自动化**事实上不存在**，
但脚本从未报错（静默无效 = 失效形态「信号在骗读者」）。

## 二、根因（三个，全部取证到文件/数据）

| # | 根因 | 取证 |
|---|---|---|
| 1 | 目标柜机 **0 货道** ⇒ 无从产生补货缺口 | `device_slot` 对 `330449777078` 计数为 0 |
| 2 | 前一轮遗留的**在途出库**把本轮的缺口「抵消」掉 | 每次重跑都出现 `suggest=0`；取消在途后立刻 `suggest=6` |
| 3 | 脚本硬编码 `-MerchantUserId 100000002`，该用户在 `user_info` **不存在** ⇒ 建任务时 FK 违约 500 | `replenishment_route_assignee_user_id_fkey` |

修法（均在 `scripts/e2e-lib.ps1` / `scripts/e2e-replenishment.ps1`）：
`Ensure-E2eDeviceSlots`（缺货道则套用模板）、`Clear-E2eReplenishmentInTransit`
（取消在途出库）、`Prepare-E2eReplenishmentPlan` 改为 **fail-fast 并给出可执行诊断**
（不再静默返回 `no gaps`）、`MerchantUserId` 默认改 `0`（用登录返回的真实 userId）。

## 三、第 5 步的**假红**判据（本轮主要修复）

修完上面三条后第 5 步暴露出一条**判据错误**：

```
签到负向用例不应推进任务，但 taskId=14 已出现在 IN_PROGRESS 列表
```

这是**假红**，不是产品缺陷。判据用的是「存在性」而非「值」：

* 第 4c 步「发运出库」会调 `OpsWarehouseAdminService.shipWarehouseOutbound()`
  （`OpsWarehouseAdminService.java:101`）→ `ReplenishmentService.generateLinesFromOutbound()`
  → `generateLinesForTask()`，该方法**只改 `status`、不写 `check_in_at`**
  （`ReplenishmentService.java:547-550`）。
* ⇒ 任务在负向签到**之前**就已经是 `IN_PROGRESS`，与负向用例无关。
* 判据成立的前提（「PENDING 才是正常态」）在走仓库链路时**必然不成立**。

反向确认这是**设计内的合法稳态**：`ReplenishmentTimeoutScheduler` 只收口
`check_in_at < cutoff` 的行（`:64-65`），SQL 里 `NULL` 不参与比较，未签到的在途任务
不会被误收口 —— 即「`IN_PROGRESS` + `check_in_at=NULL`」是被依赖的中间态。

修法（`scripts/e2e-replenishment.ps1`）：改为**逐字段比「值」**——负向用例前用
`Get-E2eTaskRow` 拍整行快照，之后用 `Assert-E2eTaskRowUnchanged` 比对
`status` + `check_in_at/lat/lng` 四字段全等。它同时覆盖原意：`doCheckInTask` 里
`setCheckInAt`（`:621`）先于 `setStatus`（`:624`），闸门顺序一旦被改坏，`check_in_at` 必被改写。

## 四、A/B 对照（本目录的核心证据）

| 文件 | 判据 | 结果 |
|---|---|---|
| `e2e-replenishment.RUN5-old-judgment-RED.txt` | **旧**（存在性） | `CHILD_EXIT=1`，红在第 5 步断言 |
| `e2e-replenishment.RUN6-fixed-GREEN.txt` | **新**（值比对） | `CHILD_EXIT=0`，`OK replenishment E2E passed (warehouse) taskId=15` |
| `e2e-replenishment.RUN4.txt` | 过渡轮（fail-fast 诊断首次生效，报「无补货缺口」） | 红，但**红得可诊断** |

RUN6 里新判据**自己打印**了关键事实：

```
✓ 签到负向用例(P1/P2 均被拒)后整行未动(DB) → 库内 task=15 整行未动（status=IN_PROGRESS check_in_at=）
```

## 五、DB 通道证据：`task-rows-14-15.tsv`

| task | 状态 | 说明 |
|---|---|---|
| 14 | `CANCELLED`，`check_in_at` / `lat` / `lng` **全空** | RUN5 那轮。旧判据称「负向用例推进了任务」，但**签到三字段为空** ⇒ 负向用例零写入（若真有写入，`check_in_at` 必非空） |
| 15 | `COMPLETED`，`check_in_at=2026-09-18 08:46:02`，经纬度有值 | RUN6 那轮。正式签到确实写入并跑完全链 |

两行并排即是「判据说错了」与「产品做对了」的分野。

## 六、编码规范化说明（证据可信度前提）

本目录的 `.txt` 由 PowerShell 产出，原始重定向（`*>`）是 **UTF-16LE**，
且脚本尾部还会用 `Add-Content` 追加 **ANSI** 的标签 ⇒ 原始文件是
「UTF-16LE 正文 + ANSI 尾部」的**混杂编码**，直接阅读会得到乱码
（`= >  0 .` 之类，尾部则整体变成伪码位如 `䡃䱉彄塅呉〽`）。

> 📌 扩展名用 `.txt` 而非 `.log`：仓库 `.gitignore:87` 忽略 `*.log`，
> 而证据目录的既有惯例是 `.txt`/`.tsv`（姊妹目录 `2026-09-18-replenishment-checkin/` 为 8 txt + 3 tsv）。
> 若留 `.log`，**这两份唯一的 A/B 反证会被静默排除在提交之外**。

规范化只动编码、**未改任何正文**，工具：
`scripts/devops/normalize-ps-redirect-encoding.py`（按**字节序列**分段，不做文本改写）。
两点如实声明：

1. 行首的 `✓` 在**捕获阶段**就已因 PS 5.1 按 ANSI 解码管道内容而丢失（显示为 `?`），
   不是规范化造成的；其后紧跟的用例名与结论完整，不影响判据语义。
2. 未出现 U+FFFD 替换字符 ⇒ 规范化过程中无字符丢失。

## 七、复现

```powershell
# 前提：dev 栈已起（docker compose -f infra/docker-compose.full.yml up -d）
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\e2e-replenishment.ps1
# 期望：结尾 OK replenishment E2E passed (warehouse) taskId=<n>
```

⚠️ 脚本会自建缺口（补货道模板 + 仓库入库 + 取消在途出库），
并**在目标柜机上留下真实数据**（任务/出库/会话记录）。这属于 E2E 的预期副作用。
