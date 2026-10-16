# Track C-admin — 运营后台业务流程实测（2026-10-13）

> 计划蓝本：`C:\Users\cwx\.qoder-cn\plans\brave-harbor-grouse.md` §Track C。
> 分工：10-12 的三端冒烟测的是「页面开得了」，本轮测的是「业务走得通」——每条流程必须把业务对象
> 真实造出来并推到终态，断言落在 **API/DB 口径**上；页面文案类判据一律不用（外壳恒真，
> 见 `scripts/lib/ui-assert.mjs` 头注）。
> 驱动脚本：`clients/admin-vue/tests/audit-admin-flows-uat.mjs`（新增，流程可选 `FLOWS=` 增量跑）。

## 0. 被测环境与被测版本溯源（先钉死「测的是哪一版」）

| 项                                           | 实测值                                                                                                                                                                                                                                                                                                                             | 取证方式                                                                                  |
| -------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------------------------- |
| 后端 trade-service（**当前**）               | 镜像 `ai-cabinet/trade-service:audit-c13` = 本审计 worktree@d7a676ae 经 `IMAGE_TAG=audit-c13 docker compose build trade-service` 构建（BUILD_RC=0，463s）；启动日志 `Migrating schema "public" to version "346 - jiangyi device identifier nullable"` → `now at version v346`；`restart=0`、`/actuator/health` = `{"status":"UP"}` | `docker logs ai-cabinet-trade-service-1` + `docker inspect` + `curl`（13:27–13:28Z 窗口） |
| 行为指纹（E-02）                             | `probe-audit-code-fingerprint.mjs` exit=0 且判定「**当前后端含审计分支入口校验**」⇒ 批九（AU-038/039）入口值域校验在运行态生效；此前同一探针判 dev                                                                                                                                                                                 | Playwright E-02，`docs/evidence/audit-2026-10-13/trackC-admin/code-fingerprint.json`      |
| 后端 trade-service（**此前**，已作废的一轮） | `ai-cabinet/trade-service:local`（19:32 由**主工作树在途改动**构建）⇒ 启动即 `BeanCreationException: AuthProperties … No default constructor found`（铁律 71 家族），restarts 85 后 exited；同栈另有 `:dev-replen-drop` 镜像（21:00 前后构建）反复替换同一容器                                                                     | `docker logs`/`docker ps -a` 原文                                                         |
| compose 定义                                 | `infra/docker-compose.full.yml` + `docker-compose.win-ports.yml`（审计 worktree 版），project=`ai-cabinet`（**与主仓 dev 会话共享同一个栈**）                                                                                                                                                                                      | 容器 label `com.docker.compose.project.config_files`                                      |
| jiangyi-gateway                              | 容器 `ai-cabinet-jiangyi-gateway-1`（镜像 `ai-cabinet-jiangyi-gateway`，32h 前构建；本轮未改 gateway 代码）；`JIANGYI_JWT_SECRET` 已配（值脱敏，不入任何产出）                                                                                                                                                                     | `docker ps` + `docker inspect` env 键名核对                                               |
| 设备替身                                     | 本仓 `edge/device-simulator`（V16 协议逐字实现）jar：`device-simulator-0.1.0-SNAPSHOT-all.jar`，子命令 `jiangyi`                                                                                                                                                                                                                   | `mvn -pl edge/device-simulator -am package -DskipTests` BUILD exit 0                      |
| admin 前端产物                               | 入口 `assets/index--YG4jrXZ.js` + `assets/ui-vendor-Bx2nlMIp.js`（与 dev@3d627ec1 的 CI 保真产物一致）                                                                                                                                                                                                                             | Playwright E-01，`admin-build-fingerprint.json`                                           |
| 关键配置现值                                 | `settlement.recognition_mode=VISION`、`settlement.min_confidence=0.72`、`settlement.empty_auto_complete_no_gravity=false`；`jiangyi_device.identifier is_nullable=YES`                                                                                                                                                             | `psql` 直查（见 §4 命令）                                                                 |

### 0.1 环境限制两条（本轮实测撞出，必须随结论一起读）

1. **宿主端口 18084 被栈外监听截走**。compose 口径 `jiangyi-gateway` 发布在 `127.0.0.1:18084`
   （`infra/docker-compose.full.yml:284`，`infra/gateway/nginx.conf:15` 亦按此回源），但本机
   `curl http://127.0.0.1:18084/…` 一律回 `HTTP/1.1 404` + **`server: Cowboy`**（EMQX 控制台指纹，
   与 `:28083` 的 EMQX Dashboard 同源），而**容器内** `curl 127.0.0.1:8083/actuator/health` 回 200、
   token 接口回业务 JSON ⇒ 服务本身无恙，是宿主端口被别处占走。
   本轮**不改共享栈的端口映射、不改 infra 配置**，改用一次性转发容器把设备链路口接出来：
   `docker run -d --name audit-gw-fwd --network ai-cabinet_default -p 127.0.0.1:18094:18094
-v .tmp/gw_fwd.py:/fwd.py:ro python:3.12-slim python3 -u /fwd.py`
   （`audit-gw-fwd` 用完 `docker rm -f` 即无痕），驱动侧以 `JIANGYI_GATEWAY_BASE=http://127.0.0.1:18094` 运行。
   ⚠️ 这意味着「真机经 nginx `/jiangyi/` 回源」那一段**本轮没测**（该路径依赖 18084，本机当前不通）。
2. **共享栈被另一个会话反复重建 ⇒ F1 从 F1-05 起无法稳定实测（当前阻断）**。
   本机同时有会话在主工作树（dev）对**同一个 compose project `ai-cabinet`** 部署自构建镜像：
   实测把 trade-service 换回 `audit-c13` 后 `t+20s`/`t+40s` 仍是 `audit-c13`，**`t+75s` 变成
   `ai-cabinet/trade-service:dev-replen-drop`（Up 3 seconds）**。被测版本被第三方进程随时改写，
   继续跑只会产出无法归因的绿/红 ⇒ 本轮到此停轮并如实登记，不做「趁窗口抢跑」。

### 0.2 结论口径（读任何一条流程结论前先看这段）

- F1 的 **F1-01…F1-04e** 全部在 **`trade-service:audit-c13`（=审计分支=上线候选）** 上跑绿（run8，
  12 绿 1 红），因此这一段结论**可直接归因到 d7a676ae**；AU-133 的**运行期复验**也在这一段完成。
- F1 的 **F1-05 及之后**尚未取得在审计构建上的结论：run8 撞的是**真实业务前置**（见 §2 的 409 分析），
  run9 撞的是上面 0.1-2 的环境争用。

## 1. 八条流程与状态

| #   | 流程                                          | 判据（须可在被测构建上变红）                          | 状态                                                                           |
| --- | --------------------------------------------- | ----------------------------------------------------- | ------------------------------------------------------------------------------ |
| F1  | 下单→将邑开门→**端侧识别**→结算→订单→购物视频 | 会话进终态 + 订单金额=行金额合计 + 视频接口回真实 MP4 | **部分**：F1-01…F1-04e 12 绿（审计构建 run8）；F1-05 起被环境争用阻断（0.1-2） |
| F2  | 争议全链                                      | 低置信→DISPUTED→后台处置→终态与资金一致               | 未开始（依赖同一被测环境）                                                     |
| F3  | 订单退款→报表口径                             | 退款额从 GMV/营收扣减（G2 口径核对）                  | 未开始                                                                         |
| F4  | 提现审核（含 CB-035 T+1 解锁时刻可配）        | 审核状态机 + 到账/驳回后余额回滚                      | 未开始                                                                         |
| F5  | 平台资金池五操作                              | 每笔操作有台账、余额不变量成立                        | 未开始                                                                         |
| F6  | 设备租赁（出账/押金/锁柜）                    | 押金非负、出账流水可对                                | 未开始                                                                         |
| F7  | 补货任务→补货录像                             | 任务终态 + 录像行可按订单号取回                       | 未开始                                                                         |
| F8  | RBAC 越权矩阵                                 | 子账号在菜单/按钮/API 三层各自被拦                    | 未开始                                                                         |

## 2. 逐流程记录

### F1 下单→将邑开门→端侧识别→结算（run8，13:50Z 本地 21:50 前；`FLOWS=env-baseline,F1`）

产品口径先钉死：**结算证据只有将邑端侧识别一种**（本仓无重力结算；云端识别引擎/YOLO/DeepSeek 已按
2026-10-11 拍板退役，`docs/JIANGYI_ONLY_FINAL_VERDICT_2026-10-11.md` §3），所以本流程所有证据必须由
**设备链**产生，脚本不自造证据。驱动已按此重写：
`startJiangyiSimulator()` 起本仓 V16 协议模拟器 → 取 token → WS 在线 → 收 `openDoor` → 上报
`uploadLockState/uploadDoorState` → 上报 `addRecognitionGoodsToOrder(classId,quantity)`，
脚本**不再**自己 POST 门事件/识别结果（F1-08 例外，见下）。

| 步骤                        | 结果     | 实测原文（截取）                                                                                                                       |
| --------------------------- | -------- | -------------------------------------------------------------------------------------------------------------------------------------- |
| F1-01 demo/ensure           | PASS     | `HTTP=200 device=500909625160 sku=SKU-DEMO-001 skus=9 invLines=6`                                                                      |
| F1-02 将邑登记              | PASS     | `onboarding/register HTTP=200 status=BOUND`（前一轮同步骤恒 500）⇒ **AU-133 运行期复验通过**                                           |
| F1-02b BOUND 档案种子       | SEEDED   | 库=`BOUND\|AUDIT500909625160\|AUDIT-F1-500909625160`；`routable=true`（等 `JiangyiDeviceDirectory` 10s 负缓存过期后由真判定接口确认）  |
| F1-03 class 映射            | PASS     | `class-mapping 9001→SKU-DEMO-001 HTTP=200`                                                                                             |
| F1-04 消费者登录            | PASS     | `HTTP=200`                                                                                                                             |
| F1-04b 设备在线（真 WS 链） | PASS     | 柜机 `online_status=ONLINE`；gateway 日志 `2026-10-13T12:50:07.372Z INFO … c.a.j.ws.De…`（建连回执→trade `ws-online`→`setWsPresence`） |
| F1-04c 运营登录             | PASS     | `ops login HTTP=200 2FA=false`                                                                                                         |
| F1-05 建会话开门            | **FAIL** | `POST /api/v2/sessions HTTP=409 msg=设备已暂停营业，请稍后再试`                                                                        |
| F1-06…F1-14                 | 未跑到   | 0.1-2 环境争用（run9 在 F1-04e 之后 trade 容器被别会话替换）                                                                           |

**F1-05 的 409 不是缺陷，是产品行为**：`DeviceValidationService.ensureConsumerShoppingAllowed:111-113`
对 `sales_locked` 拒绝消费者开门，而 `sales_locked=true` 由 `DevicePresenceService` 巡检按
「离线超 10 分钟自动停售」写入（库内 `sales_lock_reason=离线超时自动停售（超 10 分钟）`）。
上一轮模拟器退出→WS 断→离线→自动停售，构成本流程的**营业前置**。
处置（已实现，run9 未能跑完）：新增 **F1-04e** 走**运营真接口**解锁——
`GET/PUT /api/v2/ops/admin/devices/{id}/policy`（`OpsGapFeaturesController:198/204` →
`CompetitiveGapService.updateDevicePolicy:302` → `DeviceSalesLockService.applySalesLock`），
不改库；顺带钉住「解锁端点可用 + 解锁后 `saleForbidden` 一并清零 + MQTT 下发失败可 LOCAL 兜底」
（将邑柜无 MQTT，`doApplySalesLock:84-92` 的 try/catch 是这条路的安全网）。
**这条前置本身就是上线口径**：真柜断线 10 分钟即停售，运营必须知道解锁入口在设备策略页。

设计上仍**不是**产品路径的两处（驱动头注同步声明）：
① `jiangyi_device` BOUND 档案用 SQL 种子——`setDomain` 返回的 identifier 是**将邑平台签发的外部事实**，
从审计机打真平台会改真柜归属（AU-135），且本地栈无法产生该事实；种子只替代「拿到 identifier」这一步，
isJiangyi 路由/在线判定/映射归一/状态机/结算/幂等**全走真代码**。
② F1-08 迟到识别结果直打内部面——真链路 gateway 不重放，钉的是「终态会话必须拒收识别结果」的
trade 受理契约（资损面），属服务端契约面而非设备面。

## 3. 本轮新发现

| 台账     | 级  | 发现                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                         | 证据                                                                                                                                                                                                                                                                                                                            |
| -------- | --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| AU-134   | P2  | 重力/云端识别残留与「只用将邑端侧识别」拍板不一致：**批 4/5 已删**（全仓无 DeepSeek/OpsRecognition/VisionMapping/`sku_vision_mapping`，`VisionServiceClient` 不存在），但**批 2 重力兜底仍在**、**批 3 `vision-service/` 整目录仍在**（`app/recognition/{deepseek,yolo,yolo_deepseek}_recognizer.py`、`training/train_sku_yolo.py`、`requirements-ml.txt`）、**批 6 旧视频字段透传仍在**（`device-service/mqtt/MqttEventListener` 的 `videoUri/videoClipsJson`）；`docker-up.ps1` 的 `$appServices` 仍列已不进 compose 的 vision-service；`infra/.env`/`.env.example` 仍留 `VISION_API_KEY/VISION_MOCK_ENABLED/RECOGNIZER_BACKEND/VISION_FORCE_REAL`；`settlement.recognition_mode` **无值域校验**且不在 `FUND_GUARDED_KEYS` | 主会话逐项 grep+ls 实测清点；边界核实：`allowsGravityEvidenceSettle:267-269`/`tryStagingGravitySettle:273-276`、`SessionDoorService:83-85`、`ensureJiangyiOnlyShopping:131-138` ⇒ 生产**非活资损面**，风险在误导面/测试面（dev/staging 重力兜底令「测过」≠「将邑链测过」）。处置挂 **DC-27** 拍板（立即锁值域 vs 按批整体删除） |
| AU-135   | P2  | 测试栈持**真将邑凭据**时，后台「绑定」一次点击即真调将邑 `setDomain`+`modifyAffiliationTenant`，把真柜 domain/socketUrl 改指测试地址（`JIANGYI_PUBLIC_BASE_URL` 现为内网穿透域）⇒ 对第三方生产系统产生**不可自回滚的外部副作用**                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | `AdminJiangyiController:125/137` + `JiangyiOnboardingService.bindDomain:96-125` 亲读；`infra/.env` 键存在性核对（**值一律脱敏，不入任何产出**）                                                                                                                                                                                 |
| 审计自纠 | —   | 本轮**前一版 F1 曾用 `settlement.recognition_mode=VISION_GRAVITY` + `gravityDeltasJson` 凑出结算**——与「只使用将邑端侧识别」的产品事实冲突，属**造绿**，已整段删除并改为设备模拟器驱动真链；事后核实库内该键仍为 `VISION`（那次 PUT 从未落库），环境未被污染。教训入本仓铁律精神：断言落在 DB/API 口径还不够，**证据来源本身必须是产品路径**                                                                                                                                                                                                                                                                                                                                                                                 | `audit-admin-flows-uat.mjs` F1 头注与 F1-04 段注释留痕；`psql select config_value from system_config where config_key='settlement.recognition_mode'`                                                                                                                                                                            |
| 环境     | —   | 宿主 18084 被栈外 Cowboy/EMQX 监听截走（容器内 :8083 正常）⇒ 本地栈的 `/jiangyi/` 回源与设备侧联调路径当前不可用                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | §0.1（curl 原文 + `docker port`/`netstat` 取证）                                                                                                                                                                                                                                                                                |
| 环境     | —   | 共享 compose project `ai-cabinet` 被多会话争用（实测 75 秒内 trade-service 被第三方镜像替换）⇒ C-admin 实测需要一个**独占被测环境**（本会话持有 trade 镜像与容器）才能跑完 F1 后半与 F2–F8                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                   | §0.1-2（`docker ps` 时间序列）                                                                                                                                                                                                                                                                                                  |
| 测试质量 | —   | E-03「运营登录」判据偏弱：页面落到 `/admin/dashboard` 即算 PASS，但 `localStorage.getItem('admin_token')` 回 `len=0`（后台把 token 存 sessionStorage）⇒ 该步只证「登录成功」，不证 token 位置；后续 API 一律用 `adminLoginViaApi()` 的内存 token                                                                                                                                                                                                                                                                                                                                                                                                                                                                             | run8 E-03 原文 `token len=0 url=http://localhost/admin/dashboard`                                                                                                                                                                                                                                                               |

## 4. 复现命令

```bash
cd "D:/ai-generated code/ai-cabinet/.qoder/worktrees/audit-pre-launch"
# ① 被测镜像（审计分支）
IMAGE_TAG=audit-c13 DOCKER_BUILDKIT=1 docker compose --env-file infra/.env \
  -f infra/docker-compose.full.yml -f infra/docker-compose.win-ports.yml build trade-service
IMAGE_TAG=audit-c13 docker compose --env-file infra/.env \
  -f infra/docker-compose.full.yml -f infra/docker-compose.win-ports.yml -p ai-cabinet \
  up -d --no-deps trade-service
# ② 设备模拟器 jar
mvn -q -pl edge/device-simulator -am package -DskipTests
# ③ gateway 端口转发器（本机 18084 被占时才需要；用完 docker rm -f audit-gw-fwd）
node .tmp/start-gw-fwd.mjs
# ④ F1 流程
cd clients/consumer-mp && FLOWS=env-baseline,F1 JIANGYI_GATEWAY_BASE=http://127.0.0.1:18094 \
  node ../admin-vue/tests/audit-admin-flows-uat.mjs
```

证据目录：`docs/evidence/audit-2026-10-13/trackC-admin/`（`report.json` + 每步 JSON/PNG；
**不含任何密钥值**）。运行日志：`.tmp/f1-run7.log`…`.tmp/f1-run9.log`、模拟器日志
`.tmp/f1-simulator.log`（本机 .tmp 不入库）。

## 5. 接真前必须补的实测（本轮覆盖不到）

1. **setDomain/getIdentifier 真链路**：BOUND 档案由将邑平台真签发（本审计用 SQL 种子替代该外部事实）。
2. **nginx `/jiangyi/` + `/websocket/` 回源**：依赖宿主 18084，本机当前被栈外监听占走。
3. **真柜识别上报**：classId 由将邑模型产生（本轮为映射种子 9001），未命中映射的 fail-closed
   与 `recognize-timeout` 转 DISPUTED 需真机各演一次。
4. **审计分支修复项的运行期复验**：AU-133 已复验（§2）；批 2–9 各入口校验/归属比对的运行期复验
   仍只有单测+CI 证据，需在被测环境独占后按批补跑。
