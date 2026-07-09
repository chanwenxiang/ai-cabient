# AI Cabinet（AI 开门柜）测试用例文档

| 项目 | 内容 |
|------|------|
| **文档版本** | v1.2 |
| **编写日期** | 2026-07-09 |
| **修订日期** | 2026-07-09 |
| **适用系统版本** | Phase 7+（免密支付、重力柜、争议 SLA、库存扣减、运营识别测试） |
| **文档状态** | 已同步最新代码 |

---

## 1. 文档说明

### 1.1 目的

本文档为 **AI Cabinet（AI 开门柜）** 系统提供结构化、可执行的测试用例，覆盖核心业务链路、API 接口、运营后台、小程序端及边缘设备联调场景，供功能测试、回归测试、冒烟测试及自动化测试编写参考。

### 1.2 系统概述

AI Cabinet 是一套基于 **AI 视觉识别** 的智能开门柜系统。消费者通过微信小程序扫码开门、取货关门后，系统通过视频识别商品并自动结算；支持 **余额扣款**、**微信支付分免密**、**支付宝代扣** 三种支付渠道。识别存疑时进入争议工单（48h SLA），支持运营 **CONFIRM/ADJUST/WAIVE** 三种结案方式。重力柜设备可在视觉识别失败时以 **重力传感器数据** 作为结算兜底。运营人员通过 Web 后台（含 Dashboard 图表、暗色主题）和小程序 Ops 页面进行设备管理、补货、争议处理及商业运营。

**核心架构：**

```
微信小程序 / 运营后台
        │
        ▼
 trade-service (:8080)  ←→  device-service (:8081)  ←→  EMQX (MQTT)
        │                          │
        ▼                          ▼
 vision-service (:8082)        edge/android-app / device-simulator
        │
        ▼
 PostgreSQL / MinIO / Kafka(可选)
```

### 1.3 测试范围

| 范围 | 包含 | 不包含 |
|------|------|--------|
| 功能测试 | 认证、账户、免密支付、购物会话、重力柜、识别结算、充值支付、争议 SLA、运营后台、库存、商业模块 | — |
| 接口测试 | `/api/v2/*`、`/internal/v1/*`、vision-service API | 第三方微信/支付宝真实支付沙箱（staging 除外） |
| 客户端测试 | 微信小程序（消费者 + Ops）、运营 Web SPA | 原生 Android App 全量 UI 自动化 |
| 集成测试 | MQTT 开门、MinIO 视频上传、YOLO 识别链路 | 生产 K8s 集群部署验证 |
| 非功能测试 | 基础性能冒烟、鉴权安全、风控限流 | 压力测试、渗透测试（另立专项） |

### 1.4 优先级定义

| 级别 | 含义 | 说明 |
|------|------|------|
| **P0** | 阻塞级 | 核心购物流程、支付到账、鉴权；发布前必须通过 |
| **P1** | 高优先级 | 运营必备功能、争议处理、补货、设备管理 |
| **P2** | 中优先级 | 商业模块、RBAC 细粒度、报表导出、OTA |
| **P3** | 低优先级 | 边界场景、UI 细节、兼容性 |

### 1.5 用例编号规则

```
TC-{模块缩写}-{序号}

模块缩写：
  AUTH   认证
  ACCT   账户
  SESS   购物会话
  ORDR   订单
  PAY    支付充值
  DISP   争议
  OPS    运营操作
  ADM    运营后台
  ADM-UI 运营后台 UI
  MP-UI  小程序 UI
  VIS    视觉识别
  DEV    设备/边缘
  RISK   风控
  COMM   商业运营
  RBAC   权限
  GRAV   重力柜
  PFREE  免密支付
  INV    库存
  INFRA  基础设施
```

---

## 2. 测试环境

### 2.1 环境配置

| 环境 | Profile | Mock | 用途 |
|------|---------|------|------|
| **本地开发** | `dev` | 开启（SMS=123456、识别 fallback、支付 mock） | 日常开发与联调 |
| **Staging** | `staging` | 关闭 mock，SMS webhook 模拟 | 上线前验证 |
| **生产** | `prod` | 全部关闭 | 线上验收（只读/灰度） |

### 2.2 服务与端口

| 服务 | 端口 | 健康检查 |
|------|------|----------|
| trade-service | 8080 | `GET /actuator/health` |
| device-service | 8081 | `GET /actuator/health` |
| vision-service | 8082 | `GET /health` |
| PostgreSQL | 15433 | — |
| EMQX MQTT | 11883 | — |
| MinIO API | 9000 | — |
| Nginx Gateway | 80 | — |

### 2.3 测试数据

#### 测试账号

| 角色 | 手机号 | 验证码 | userId | 初始余额 | 用途 |
|------|--------|--------|--------|----------|------|
| 消费者 | `13800138000` | `123456` | 10001 | ~100 元 | 购物、充值、争议 |
| 运营员 | `13900000001` | `123456` | 100000001 | — | 后台、补货、审核 |

#### 设备与商品

| 类型 | 值 | 说明 |
|------|-----|------|
| 设备 ID | `CAB-001` | 设备模拟器启动参数 |
| 演示 SKU | `SKU-DEMO-001` | 演示可乐，350 分（3.5 元） |
| 内部 API Key | `dev-internal-key-change-me` | Header: `X-Internal-Api-Key` |
| Vision API Key | `dev-vision-key-change-me` | vision-service 鉴权 |

#### 业务常量

| 常量 | 值 | 说明 |
|------|-----|------|
| 最低开门余额 | 500 分（5 元） | `CabinetConstants.MIN_BALANCE_CENTS`；已签免密则跳过 |
| 运营账号 ID 下限 | 100000000 | 跳过实名/余额/免密校验 |
| 每小时最大开门次数 | 5 次（可配置） | 超限返回 HTTP 429 |
| 7 天争议自动拉黑阈值 | 3 次（可配置） | 自动拉黑 30 天 |
| 争议 SLA 时限 | 48 小时（可配置） | `aicabinet.dispute-sla.hours` |
| SLA 临期提醒 | 到期前 12 小时（可配置） | `reminderHoursBefore` |
| SKU 最低扣款置信度 | 0.92（可 per-SKU 配置） | `sku_catalog.min_charge_confidence` |
| 支付渠道 | BALANCE / WECHAT / ALIPAY | 订单 `payChannel` 字段 |

### 2.4 自动化脚本对照

| 脚本 | 覆盖场景 |
|------|----------|
| `scripts/e2e-shopping.ps1` | 登录 → 创建会话 → 模拟开关门 → 上传视频 → 结算；校验 `payChannel` 与余额变动 |
| `scripts/e2e-recharge.ps1` | 充值预下单 → mock 微信回调 → 余额增加 |
| `scripts/e2e-vision-shopping.ps1` | 真实 YOLO 识别（bottle→成功，bus→争议） |
| `scripts/e2e-staging.ps1` | Staging 环境完整购物 |
| `scripts/e2e-sms-auth.ps1` | SMS 登录辅助 |
| `scripts/verify-local.ps1` | 本地冒烟（健康检查 + 充值 + 购物） |
| `scripts/verify-full.ps1` | 全量构建 + E2E |
| `scripts/verify-step4.ps1` | Vision 管道验证 |
| `scripts/verify-step5.ps1` | 生产/Staging 环境检查清单 |
| `scripts/upload-e2e-video.ps1` | 上传 sample MP4 至 MinIO |
| `scripts/ensure-sample-video.ps1` | 确保 E2E 测试视频存在 |
| `scripts/run-api-tests.ps1` | API 级别用例批量（认证/账户/免密/重力） |
| `scripts/run-extended-e2e.ps1` | 未覆盖场景：补货/离线/风控/争议结案/库存/重力 |
| `scripts/run-miniapp-api-smoke.ps1` | 小程序各页面后端 API 冒烟 |
| `scripts/run-admin-ui-check.ps1` | 运营后台各页面数据 API 可访问性 |

---

## 3. 购物会话状态机（测试基准）

测试会话相关用例时，须验证状态流转符合以下规则：

```
CREATED → OPENING → SHOPPING → RECOGNIZING → SETTLING → COMPLETED
                              ↘ WAITING_UPLOAD ↗
                              ↘ DISPUTED → COMPLETED / FAILED
                              ↘ FAILED / CANCELLED
```

| 状态 | 含义 | 关键验证点 |
|------|------|------------|
| CREATED | 会话已创建 | 设备被占用 |
| OPENING | 开门指令已下发 | MQTT 消息发出 |
| SHOPPING | 门已开，购物中 | 消费者可取货 |
| WAITING_UPLOAD | 关门但视频未上传 | 离线场景 |
| RECOGNIZING | 识别中 | 调用 vision-service |
| SETTLING | 结算中 | 创建订单；按渠道扣款（余额/微信分/支付宝） |
| COMPLETED | 完成 | 订单 PAID 或补货完成 |
| DISPUTED | 待人工审核 | 争议工单创建 |
| FAILED | 失败 | 识别/结算异常 |
| CANCELLED | 已取消 | 运营取消或超时 |

---

## 4. 测试用例

### 4.1 认证模块（AUTH）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-AUTH-001 | 消费者短信验证码发送 | P1 | API | dev 环境 | 1. `POST /api/v2/auth/sms-code?phoneNumber=13800138000` | 返回 code=0；dev 环境验证码为 123456 |
| TC-AUTH-002 | 消费者短信登录成功 | P0 | API | 无 | 1. 发送验证码<br>2. `POST /api/v2/auth/login` body: `{"phoneNumber":"13800138000","code":"123456"}` | 返回 token、refreshToken、userId=10001 |
| TC-AUTH-003 | 消费者短信登录-错误验证码 | P1 | API | 无 | 1. `POST /api/v2/auth/login` code=000000 | 返回错误，无 token |
| TC-AUTH-004 | 运营员短信登录成功 | P0 | API | 无 | 1. `POST /api/v2/auth/admin-login` phone=13900000001, code=123456 | 返回 token，userId=100000001 |
| TC-AUTH-005 | 消费者账号禁止运营登录 | P1 | API | 无 | 1. 用 13800138000 调用 admin-login | 返回错误，拒绝登录 |
| TC-AUTH-006 | 消费者密码登录 | P1 | API | 用户已设置密码 | 1. `POST /api/v2/auth/password-login` | 返回有效 token |
| TC-AUTH-007 | 运营员密码登录 | P1 | API | 运营账号已设置密码 | 1. `POST /api/v2/auth/admin-password-login` | 返回有效 token |
| TC-AUTH-008 | 微信登录 | P1 | API | 有效 wx code（或 dev mock） | 1. `POST /api/v2/auth/wx-login` | 返回 token；新用户自动注册 |
| TC-AUTH-009 | Token 刷新 | P1 | API | 持有有效 token | 1. `POST /api/v2/auth/refresh` 带 Authorization | 返回新 token |
| TC-AUTH-010 | 无 Token 访问受保护接口 | P0 | API | 无 | 1. `GET /api/v2/account` 不带 Authorization | HTTP 401 |
| TC-AUTH-011 | 过期 Token 访问 | P1 | API | 使用过期 token | 1. 访问受保护接口 | HTTP 401 |
| TC-AUTH-012 | 服务重启后旧 Token 失效 | P1 | API | 记录 server-boot epoch | 1. 重启 trade-service<br>2. 用旧 token 访问 | boot epoch 变化后 token 失效（401） |
| TC-AUTH-013 | 小程序登录页-消费者登录 | P0 | UI | 微信开发者工具 | 1. 打开 login 页<br>2. 输入手机号和验证码<br>3. 点击登录 | 跳转首页，本地存储 token |
| TC-AUTH-014 | 运营后台登录 | P0 | UI | 浏览器 | 1. 打开 `/admin/index.html`<br>2. 运营账号登录 | 进入 dashboard，侧边栏可见 |
| TC-AUTH-015 | 小程序 Token 静默刷新 | P1 | UI/API | 已登录，token 临近过期 | 1. 等待 token 临近过期<br>2. 发起任意 API 请求 | `api.js` 自动调用 `/auth/refresh`，无需重新登录 |

---

### 4.2 账户模块（ACCT）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-ACCT-001 | 查询账户信息 | P0 | API | 已登录消费者 | 1. `GET /api/v2/account` | 返回 balanceCents、verified、passwordFreeReady、payscoreEnabled、alipayAgreementEnabled、payPreferredChannel |
| TC-ACCT-002 | 实名认证提交 | P0 | API | 未实名用户 | 1. `POST /api/v2/account/verify` 提交姓名+身份证后四位 | verified=true |
| TC-ACCT-003 | 未实名用户禁止开门 | P0 | API | verified=false，余额充足 | 1. `POST /api/v2/sessions` | HTTP 401，提示未实名 |
| TC-ACCT-004 | 余额不足禁止开门 | P0 | API | verified=true，余额<500分，未签免密 | 1. `POST /api/v2/sessions` | HTTP 412，提示余额不足 |
| TC-ACCT-005 | 运营调整用户余额-增加 | P1 | API | 运营 token | 1. `POST /api/v2/ops/admin/users/{id}/balance` 正数调整 | 余额增加，审计日志记录 |
| TC-ACCT-006 | 运营调整用户余额-减少 | P1 | API | 运营 token | 1. 提交负数调整 | 余额减少，不低于 0 |
| TC-ACCT-007 | 运营手动核验用户 | P1 | API | 运营 token | 1. `POST /api/v2/ops/admin/users/{id}/verify` verified=true | 用户 verified 状态更新 |
| TC-ACCT-008 | 小程序实名认证页 | P1 | UI | 未实名消费者 | 1. 进入 verify 页<br>2. 填写信息提交 | 提示成功，mine 页显示已实名 |
| TC-ACCT-009 | 小程序我的页面展示 | P1 | UI | 已登录 | 1. 进入 mine 页 | 显示余额、订单入口、充值入口、免密签约入口 |
| TC-ACCT-010 | dev 环境绑定 openId | P2 | API | mock 开启 | 1. `POST /api/v2/account/bind-openid?openId=xxx` | 绑定成功；生产环境返回 403 |

---

### 4.3 购物会话模块（SESS）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-SESS-001 | 创建购物会话-成功 | P0 | API/E2E | 消费者已实名、余额≥5元、设备在线 | 1. `POST /api/v2/sessions` `{"deviceId":"CAB-001"}` | 返回 sessionId，state=CREATED 或 OPENING |
| TC-SESS-002 | 查询会话状态 | P0 | API | 已有会话 | 1. `GET /api/v2/sessions/{sessionId}` | 返回当前 state 及时间戳 |
| TC-SESS-003 | 完整购物流程-模拟器 | P0 | E2E | trade+device+vision+模拟器运行 | 1. 创建会话<br>2. 模拟门开 (door-event OPEN)<br>3. 上传视频<br>4. 模拟门关 (door-event CLOSED+videoUri)<br>5. 轮询状态 | 状态流转至 COMPLETED；参考 `e2e-shopping.ps1` |
| TC-SESS-004 | 开门后进入 SHOPPING | P0 | API | 会话 CREATED/OPENING | 1. 发送 door-event OPEN | state=SHOPPING |
| TC-SESS-005 | 关门触发识别 | P0 | API | state=SHOPPING | 1. 发送 door-event CLOSED + videoUri | state→RECOGNIZING→SETTLING→COMPLETED |
| TC-SESS-006 | 获取会话关联订单 | P0 | API | 会话已结算 | 1. `GET /api/v2/sessions/{sessionId}/order` | 返回订单详情、商品明细、金额 |
| TC-SESS-007 | 设备离线时创建会话 | P1 | API | 设备无心跳 | 1. `POST /api/v2/sessions` | 返回错误或设备不可用提示 |
| TC-SESS-008 | 设备被占用时创建会话 | P1 | API | 该设备有进行中会话 | 1. 再次创建同设备会话 | 返回冲突/设备忙 |
| TC-SESS-009 | 运营取消卡住会话 | P1 | API | 会话处于 OPENING/SHOPPING 等 | 1. `POST /api/v2/ops/admin/sessions/{id}/cancel` | state=CANCELLED |
| TC-SESS-010 | 离线关门-WAITING_UPLOAD | P1 | API | SHOPPING 状态 | 1. door-event CLOSED，uploadStatus=LOCAL_QUEUED，无 videoUri | state=WAITING_UPLOAD |
| TC-SESS-011 | 离线视频补传 | P1 | API | state=WAITING_UPLOAD | 1. `POST /internal/v1/sessions/video` 附加 videoUri<br>2. 触发识别 | state→RECOGNIZING→结算 |
| TC-SESS-012 | 识别服务不可用 | P1 | API | vision 不可用且 mock 关闭 | 1. 完成关门流程 | 抛出异常或 state=FAILED（网络/服务错误） |
| TC-SESS-012a | 空识别转争议 | P0 | API | vision 返回空 items，mock 关闭 | 1. 完成识别 | state=DISPUTED，非 FAILED（见 TC-VIS-016） |
| TC-SESS-013 | 小程序扫码开门 | P0 | UI/E2E | 完整环境 | 1. 首页扫码或输入 CAB-001<br>2. 确认开门 | 显示购物中状态，轮询至完成 |
| TC-SESS-014 | 小程序购物结果页 | P0 | UI | 购物完成 | 1. 自动跳转 result 页 | 显示订单金额、商品列表 |
| TC-SESS-015 | 运营账号开门跳过余额校验 | P1 | API | 运营 token，余额为 0 | 1. 运营创建会话或补货开门 | 不因余额/实名/免密拦截 |
| TC-SESS-016 | 关门事件携带重力数据 | P1 | API | SHOPPING 状态 | 1. door-event CLOSED 附带 `gravityDeltasJson` | 会话 `gravityDeltas` 字段合并保存 |

---

### 4.4 视觉识别与结算（VIS）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-VIS-001 | vision-service 健康检查 | P0 | API | vision 已启动 | 1. `GET /health` | status=ok，显示 recognizer 类型 |
| TC-VIS-002 | 同步识别接口 | P1 | API | MinIO 有测试视频 | 1. `POST /api/v2/vision/recognize` 传 videoUri | 返回 items、confidence、needReview |
| TC-VIS-003 | YOLO 识别成功-已知商品 | P0 | E2E | YOLO 映射已配置 | 1. 上传含 bottle 图片/视频<br>2. 完成购物流程 | 识别到对应 SKU，自动结算 |
| TC-VIS-004 | YOLO 识别失败-未知商品 | P1 | E2E | 上传 bus.jpg 等未映射图片 | 1. 完成购物流程 | needReview=true，会话 DISPUTED |
| TC-VIS-005 | dev mock 识别降级 | P1 | API | mock-enabled=true，vision 不可用 | 1. 完成购物 | 降级为 SKU-DEMO-001，正常结算 |
| TC-VIS-006 | 低置信度转争议 | P1 | API | 单品置信度 < SKU `minChargeConfidence` | 1. 触发识别 | `SettlementConfidenceService` 拦截，创建争议工单，**不自动扣款** |
| TC-VIS-007 | 中等置信度防误扣 | P1 | API | 置信度处于 0.80~阈值区间且整体≥0.90 | 1. 触发识别 | 转争议，提示「防误扣需人工审核」 |
| TC-VIS-008 | YOLO 映射管理-新增 | P1 | API | 运营 token | 1. `POST /api/v2/ops/admin/vision-mappings/yolo` | 映射保存成功 |
| TC-VIS-009 | YOLO 映射管理-删除 | P2 | API | 已有映射 | 1. `DELETE /api/v2/ops/admin/vision-mappings/yolo/{className}` | 映射删除 |
| TC-VIS-010 | 阿里云类目映射 | P2 | API | 生产配置 | 1. POST/DELETE aliyun mapping | 映射 CRUD 正常 |
| TC-VIS-011 | 运营识别预览 | P1 | API | 运营 token | 1. `POST /api/v2/ops/recognition-preview` multipart 上传图片 | 返回 `DevRecognitionPreviewDto`，不创建会话、不扣款 |
| TC-VIS-012 | 运营识别上传-仅预览模式 | P1 | API | 运营 token | 1. `POST /api/v2/ops/recognition-upload` settle=false | 返回识别结果，不结算 |
| TC-VIS-013 | 运营识别上传-触发结算 | P2 | API | 运营 token | 1. recognition-upload settle=true, mode=FULL | 可走完整会话结算（`allowDevFallback=false` 时不注入 mock SKU） |
| TC-VIS-014 | 多摄像头融合 | P2 | API | 配置 MULTI 模式 | 1. 上传多段 videoClips | 融合识别结果正确 |
| TC-VIS-015 | 内部映射接口 | P1 | API | Internal API Key | 1. `GET /internal/v1/vision/mappings` | vision-service 可拉取最新映射 |
| TC-VIS-016 | 空识别结果转争议（非 FAILED） | P0 | 单元测试 | mock 关闭 | 1. 运行 `SettlementDisputeTest` | 抛出 `DisputeRequiredException`，状态 DISPUTED 而非 FAILED |

---

### 4.5 订单模块（ORDR）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-ORDR-001 | 查询我的订单列表 | P0 | API | 已有订单 | 1. `GET /api/v2/orders?page=0&size=10` | 分页返回订单列表 |
| TC-ORDR-002 | 查询订单详情 | P0 | API | 已知 orderId | 1. `GET /api/v2/orders/{orderId}` | 返回商品明细、金额、status=PAID、payChannel |
| TC-ORDR-003 | 余额渠道购物后扣款 | P0 | API/E2E | payChannel=BALANCE | 1. 完成购物<br>2. 查询账户 | balanceCents = 原余额 - 订单金额 |
| TC-ORDR-003a | 免密渠道购物余额不变 | P0 | API/E2E | 已签微信分/支付宝代扣 | 1. 完成购物 | payChannel=WECHAT/ALIPAY，余额不变，有 payTradeNo |
| TC-ORDR-004 | 运营查询订单列表 | P1 | API | 运营 token | 1. `GET /api/v2/ops/admin/orders` | 返回全部订单，支持筛选 |
| TC-ORDR-005 | 运营导出订单 CSV | P2 | API | 运营 token | 1. `GET /api/v2/ops/admin/orders/export` | 下载 CSV 文件，字段完整 |
| TC-ORDR-006 | 小程序订单列表页 | P1 | UI | 有历史订单 | 1. 进入 orders 页 | 列表展示订单号、金额、时间 |
| TC-ORDR-007 | 订单金额与 SKU 价格一致 | P0 | API | SKU 价格已知 | 1. 购买单件商品 | 订单总额 = SKU 单价 × 数量 |

---

### 4.6 支付与充值模块（PAY）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-PAY-001 | 微信充值预下单 | P0 | API | 已登录消费者 | 1. `POST /api/v2/payment/recharge/prepay` channel=WECHAT, amountCents=1000 | 返回 prepay 参数/订单号 |
| TC-PAY-002 | 支付宝充值预下单 | P1 | API | 已登录消费者 | 1. prepay channel=ALIPAY | 返回支付宝 prepay 参数 |
| TC-PAY-003 | 微信 mock 支付回调 | P0 | E2E | dev 环境 | 1. 预下单<br>2. `POST /api/v2/payment/wechat/notify/mock/{orderId}` | 充值订单状态 PAID，余额增加 |
| TC-PAY-004 | 支付宝 mock 支付回调 | P1 | E2E | dev 环境 | 1. 预下单<br>2. `POST /api/v2/payment/alipay/notify/mock/{orderId}` | 余额增加 |
| TC-PAY-005 | 充值后余额更新 | P0 | API | 记录充值前余额 | 1. 完成充值回调 | balanceCents 增加对应金额 |
| TC-PAY-006 | 查询充值记录列表 | P1 | API | 有充值记录 | 1. `GET /api/v2/payment/recharges` | 返回充值订单列表 |
| TC-PAY-007 | 查询单笔充值详情 | P1 | API | 已知充值 orderId | 1. `GET /api/v2/payment/recharge/{orderId}` | 返回状态、金额、渠道 |
| TC-PAY-008 | 取消待支付充值 | P1 | API | 充值订单 PENDING | 1. `POST /api/v2/payment/recharge/{orderId}/cancel` | 状态变为 CANCELLED |
| TC-PAY-009 | 运营充值退款 | P1 | API | 运营 token，已支付充值 | 1. `POST /api/v2/ops/admin/recharge/{id}/refund` | 充值退款，余额扣回 |
| TC-PAY-010 | 余额不足→充值→购物 | P0 | E2E | 余额<5元 | 1. 尝试开门失败<br>2. 充值<br>3. 再开门 | 全流程成功 |
| TC-PAY-011 | 小程序充值页 | P1 | UI | 已登录 | 1. 进入 recharge 页<br>2. 选择金额<br>3. 发起支付 | 跳转支付或 mock 成功 |
| TC-PAY-012 | 小程序充值记录页 | P2 | UI | 有充值记录 | 1. 进入 recharges 页 | 列表展示充值历史 |
| TC-PAY-013 | 重复支付回调幂等 | P1 | API | 已支付订单 | 1. 再次发送 notify | 余额不重复增加 |
| TC-PAY-014 | 微信支付签名验证 | P1 | 单元测试 | — | 运行 `WeChatPayV3SignerTest` | 签名/验签通过 |

---

### 4.6a 免密支付模块（PFREE）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-PFREE-001 | 开通微信支付分 | P0 | API | 已登录消费者 | 1. `POST /api/v2/account/payscore/sign` | 返回 contractId；account.passwordFreeReady=true |
| TC-PFREE-002 | 开通支付宝代扣 | P1 | API | 已登录消费者 | 1. `POST /api/v2/account/alipay-agreement/sign` | 返回 agreementId；payPreferredChannel=ALIPAY |
| TC-PFREE-003 | 免密用户余额不足可开门 | P0 | API | 已签免密，余额<5元，已实名 | 1. `POST /api/v2/sessions` | 创建成功，不因余额拦截 |
| TC-PFREE-004 | 免密渠道订单扣款 | P0 | E2E | 已签微信分 | 1. 完成购物<br>2. 查订单 | payChannel=WECHAT，余额不变；mock 环境 tradeNo 以 MOCK-PS- 开头 |
| TC-PFREE-005 | 争议改单补扣-免密渠道 | P1 | API | 订单 payChannel=WECHAT | 1. 争议 CONFIRM 调高金额 | 通过 PayScore 补扣差额，非余额扣款 |
| TC-PFREE-006 | 争议免单退款-微信渠道 | P1 | API | 已扣款订单 payChannel=WECHAT | 1. 争议 WAIVE 结案 | 原路退款（mock 环境日志确认） |
| TC-PFREE-007 | 小程序开通免密入口 | P1 | UI | 未签免密消费者 | 1. mine 页点击「开通免密支付」 | 调用 signPayScore，入口消失 |

---

### 4.7 争议模块（DISP）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-DISP-001 | 识别争议自动创建工单 | P0 | API | 识别 needReview=true | 1. 完成购物流程 | 会话 DISPUTED，工单自动创建 |
| TC-DISP-002 | 消费者查看我的争议 | P0 | API | 有争议工单 | 1. `GET /api/v2/disputes/mine` | 返回工单列表 |
| TC-DISP-003 | 消费者主动申诉 | P1 | API | 会话 COMPLETED 或 DISPUTED | 1. `POST /api/v2/disputes` 提交 sessionId+reason | 工单创建成功 |
| TC-DISP-004 | 重复申诉拒绝 | P1 | API | 已有工单 | 1. 再次 POST disputes | HTTP 409，提示已有工单 |
| TC-DISP-005 | 运营查看争议列表 | P0 | API | 运营 token | 1. `GET /api/v2/ops/disputes` | 分页返回；含 slaDueAt、slaOverdue、slaHoursRemaining |
| TC-DISP-006 | 运营解决争议-CONFIRM 确认扣款 | P0 | API | 待处理工单，无已付订单 | 1. resolve resolutionType=CONFIRM + items | 返回 `ResolveDisputeResultDto`；首次扣款；会话 COMPLETED |
| TC-DISP-006a | 运营解决争议-ADJUST 改单退补 | P0 | API | 已有订单 | 1. resolve resolutionType=ADJUST 调整 items | adjustmentCents 反映退/补差额 |
| TC-DISP-006b | 运营解决争议-WAIVE 免单 | P1 | API | 已扣款订单 | 1. resolve resolutionType=WAIVE | 退还已扣款项；message 含退款金额 |
| TC-DISP-007 | 争议解决后扣款正确 | P0 | API | 争议涉及扣款 | 1. CONFIRM 解决<br>2. 查余额/订单 payChannel | 按渠道与调整后金额扣款 |
| TC-DISP-008 | 频繁争议自动拉黑 | P1 | API | 7天内≥3次争议 | 1. 触发第3次争议 | 用户自动进入黑名单 30 天 |
| TC-DISP-009 | 争议 SLA 超时统计 | P1 | API | 有超时未处理工单 | 1. `GET /api/v2/ops/admin/stats` | disputeOverdue > 0 |
| TC-DISP-010 | 争议 SLA 临期提醒 | P2 | API | 工单距到期<12h | 1. 查 stats 或 SLA 页 | disputeNearSla 计数正确 |
| TC-DISP-011 | 小程序争议列表页 | P1 | UI | 有争议 | 1. 进入 dispute-mine 页 | 展示争议状态、SLA 剩余时间 |
| TC-DISP-012 | 运营后台争议处理 | P1 | UI | 运营登录 | 1. 进入 disputes 页<br>2. 选择结案类型解决 | 支持 CONFIRM/ADJUST/WAIVE |

---

### 4.8 运营操作模块（OPS）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-OPS-001 | 补货开门-不结算 | P0 | API | 运营 token，设备在线，有 PENDING/IN_PROGRESS 补货任务 | 1. `POST /api/v2/ops/restock/open-door` `{"deviceId":"CAB-001","taskId":123}` | 门打开，任务变 IN_PROGRESS，关门后会话 COMPLETED，无订单 |
| TC-OPS-002 | 补货开门后设备释放 | P1 | API | 补货完成 | 1. 关门后查设备状态 | 消费者仍被 IN_PROGRESS 任务冻结，直至 `complete` 任务 |
| TC-OPS-003 | 获取 SKU 目录（运营） | P1 | API | 运营 token | 1. `GET /api/v2/ops/skus` | 返回商品列表供争议处理 |
| TC-OPS-004 | 小程序 Ops 补货页 | P1 | UI | 运营账号登录小程序 | 1. 进入 ops 页<br>2. 在任务卡片点击「开门补货」 | 门打开，无扣款 |
| TC-OPS-005 | 小程序补货任务列表 | P2 | UI | 有补货任务 | 1. ops 页查看 my-tasks | 展示待完成补货任务 |
| TC-OPS-006 | 完成补货任务 | P2 | API | 有 assigned 任务 | 1. `POST /api/v2/ops/admin/replenishment/tasks/{id}/complete` | 任务状态完成 |
| TC-OPS-007 | 小程序 Ops 识别预览 | P1 | UI | 运营登录小程序 | 1. ops 页上传商品图<br>2. 调用 recognition-preview | 显示识别结果，不扣款 |

---

### 4.8a 重力柜模块（GRAV）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-GRAV-001 | 上报重力变化-内部 API | P1 | API | 进行中会话 | 1. `POST /internal/v1/sessions/gravity-deltas` body: sessionId, deviceId, deltas[] | 会话 gravityDeltas 合并保存 |
| TC-GRAV-002 | 关门事件附带重力 JSON | P1 | API | SHOPPING | 1. door-event CLOSED + gravityDeltasJson | 与 TC-SESS-016 一致，数据持久化 |
| TC-GRAV-003 | 视觉空结果时重力兜底结算 | P0 | API/E2E | 有重力数据，vision 返回空 items | 1. 完成关门+识别 | 使用重力数据生成订单行；modelVersion 含 `+gravity` |
| TC-GRAV-004 | 视觉有结果时优先视觉 | P1 | API | 视觉与重力均有数据 | 1. 完成结算 | 以视觉识别结果为准，重力不覆盖 |
| TC-GRAV-005 | 重力数据合并去重 | P2 | API | 同 SKU 多次上报 | 1. 多次上报同 skuId delta | 同 SKU 数量累加合并 |

---

### 4.9 运营后台模块（ADM）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-ADM-001 | Dashboard 统计数据 | P0 | UI/API | 运营登录 | 1. `GET /api/v2/ops/admin/stats` | 含 disputeOverdue、disputeNearSla、recognitionAutoRate24h、lowStockSkuCount、pendingSplitCount、doorSuccessRate24h |
| TC-ADM-002 | 订单趋势图 | P1 | UI/API | 有历史数据 | 1. `GET /api/v2/ops/admin/trend`<br>2. dashboard-charts 渲染 | 返回趋势数据，图表正常展示 |
| TC-ADM-003 | 运营分析趋势 | P2 | UI/API | 运营登录 | 1. `GET /api/v2/ops/admin/trend/ops` | Ops 指标图表展示 |
| TC-ADM-004 | 设备列表查询 | P0 | API | 运营 token | 1. `GET /api/v2/ops/admin/devices` | 返回设备列表含在线状态 |
| TC-ADM-005 | 新增设备 | P1 | API | 运营 token | 1. `POST /api/v2/ops/admin/devices` | 设备创建成功 |
| TC-ADM-006 | 编辑设备 | P1 | API | 已有设备 | 1. `PATCH /api/v2/ops/admin/devices/{id}` | 信息更新 |
| TC-ADM-007 | 会话列表与筛选 | P1 | API/UI | 有会话记录 | 1. 按状态/设备/时间筛选 | 结果正确 |
| TC-ADM-008 | 会话视频预览 | P1 | API | 会话有视频 | 1. `GET /api/v2/ops/admin/sessions/{id}/video` | 返回可播放视频 URL |
| TC-ADM-009 | 会话导出 CSV | P2 | API | 运营 token | 1. `GET /api/v2/ops/admin/sessions/export` | CSV 下载成功 |
| TC-ADM-010 | SKU 目录 CRUD | P1 | API | 运营 token | 1. 新增/编辑/查询 SKU | CRUD 正常 |
| TC-ADM-011 | 用户列表查询 | P1 | API | 运营 token | 1. `GET /api/v2/ops/admin/users` | 返回用户及余额 |
| TC-ADM-012 | 设备报表 | P2 | API | 运营 token | 1. `GET /api/v2/ops/admin/reports/devices` | 报表数据正确 |
| TC-ADM-013 | 审计日志查询 | P2 | API/UI | 有操作记录 | 1. `GET /api/v2/ops/admin/audit-logs` | 记录含操作人、动作、时间 |
| TC-ADM-014 | 上传队列页面 | P1 | UI | 有 WAITING_UPLOAD 会话 | 1. 进入 upload-queue 页 | 展示待上传会话 |
| TC-ADM-015 | 后台主题切换 | P3 | UI | 运营登录 | 1. 切换明/暗主题（theme.js） | 主题持久化 |
| TC-ADM-016 | Dashboard 指标卡片跳转 | P2 | UI | 有 SLA 超时/低库存 | 1. 点击「SLA超时争议」/「低库存 SKU」卡片 | 跳转 disputes / replenishment 并带筛选 |

---

### 4.10 设备与边缘模块（DEV）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-DEV-001 | 设备心跳上报 | P0 | API | device-service 运行 | 1. 模拟器运行 CAB-001 | 每 30s 心跳，设备显示在线 |
| TC-DEV-002 | 查询设备状态 | P0 | API | 设备在线 | 1. `GET /api/v2/devices/CAB-001/status` | available=true, online=true |
| TC-DEV-003 | MQTT 开门指令 | P0 | 集成 | EMQX 运行 | 1. 创建会话<br>2. 观察 MQTT 主题 | device-service 发布 OPEN_DOOR |
| TC-DEV-004 | 门事件去重 | P1 | 单元测试 | — | 运行 `DoorEventDeduplicatorTest` | 重复事件不重复处理 |
| TC-DEV-005 | 设备模拟器完整流程 | P0 | E2E | 模拟器+后端 | 1. 小程序开门<br>2. 模拟器响应 | 门开关事件正确上报 |
| TC-DEV-006 | 内部 API 鉴权 | P0 | API | 无/错误 API Key | 1. 调用 internal API 不带 Key | HTTP 401/403 |
| TC-DEV-007 | OTA 版本检查 | P2 | API | 设备有心跳 | 1. `GET /internal/v1/devices/{id}/ota/check` | 返回最新版本信息 |
| TC-DEV-008 | Android 端视频录制上传 | P2 | 手动 | 真机/模拟器 | 1. 购物触发录制<br>2. 上传 MinIO | 视频可回放，识别正常 |
| TC-DEV-009 | 重力数据上报接口鉴权 | P1 | API | 无 Internal API Key | 1. `POST /internal/v1/sessions/gravity-deltas` | HTTP 401/403 |

---

### 4.11 风控模块（RISK）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-RISK-001 | 黑名单用户禁止开门 | P0 | API | 用户已被拉黑 | 1. `POST /api/v2/sessions` | HTTP 403，提示账号受限 |
| TC-RISK-002 | 手动添加黑名单 | P1 | API | 运营 token | 1. `POST /api/v2/ops/admin/risk/blacklist` | 用户被拉黑 |
| TC-RISK-003 | 移除黑名单 | P1 | API | 用户在黑名单 | 1. `DELETE /api/v2/ops/admin/risk/blacklist/{userId}` | 用户恢复，可正常开门 |
| TC-RISK-004 | 频繁开门限流 | P1 | API | 1小时内已开门5次 | 1. 第6次创建会话 | HTTP 429，提示过于频繁 |
| TC-RISK-005 | 风险事件记录 | P2 | API | 触发风控 | 1. `GET /api/v2/ops/admin/risk/events` | 事件列表含 BLACKLIST_HIT 等 |
| TC-RISK-006 | 黑名单过期自动解除 | P2 | API | 黑名单设了 expiresAt | 1. 过期后再开门 | 可正常开门 |
| TC-RISK-007 | 后台风控页面 | P1 | UI | 运营登录 | 1. 进入 risk 页 | 黑名单 CRUD 可用 |

---

### 4.12 商业运营模块（COMM）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-COMM-001 | OTA 发布管理 | P2 | API | 运营 token | 1. `POST /api/v2/ops/admin/ota/releases` | 版本发布成功 |
| TC-COMM-002 | 库存查询与更新 | P1 | API | 运营 token | 1. `GET/PUT /api/v2/ops/admin/inventory` | 库存数据正确；支持 `?lowStockOnly=true` |
| TC-COMM-002a | 购物后库存自动扣减 | P1 | API | 设备有库存记录 | 1. 完成购物结算 | 对应 device+sku 库存减少 |
| TC-COMM-002b | 争议改单库存调整 | P2 | API | 争议 ADJUST 改商品 | 1. 解决争议 | `InventoryService.adjustForOrder` 按差额调整 |
| TC-COMM-002c | 低库存 Dashboard 告警 | P1 | UI/API | 有 SKU 低于 lowThreshold | 1. 查 stats | lowStockSkuCount > 0；可跳转补货页 |
| TC-COMM-002d | 一键创建低库存补货路线 | P2 | UI | 有低库存 SKU | 1. replenishment 页「低库存补货」 | 自动创建路线与任务 |
| TC-COMM-003 | 补货路线管理 | P2 | API | 运营 token | 1. `GET/POST /api/v2/ops/admin/replenishment/routes` | 路线 CRUD |
| TC-COMM-004 | 生成补货计划 | P2 | API | 有库存数据 | 1. `POST /api/v2/ops/admin/replenishment/plan` | 生成补货任务 |
| TC-COMM-005 | SLA 指标查询 | P2 | API | 有历史会话 | 1. `GET /api/v2/ops/admin/sla` | 含开门成功率、识别延迟、争议超时数 disputeOverdue |
| TC-COMM-006 | 支付对账执行 | P2 | API | finance 角色 | 1. `POST /api/v2/ops/admin/reconciliation/run` | 对账记录生成 |
| TC-COMM-007 | 对账详情查看 | P2 | API | 有对账记录 | 1. `GET /api/v2/ops/admin/reconciliation/{id}` | 差异明细正确 |
| TC-COMM-008 | 商户 CRUD | P2 | API | 运营 token | 1. `GET/POST /api/v2/ops/admin/merchants` | 商户管理正常 |
| TC-COMM-009 | 分账记录查询 | P2 | API | 有分账数据 | 1. `GET /api/v2/ops/admin/merchants/revenue-splits` | 分账列表正确 |
| TC-COMM-010 | 微信分账提交 | P3 | API | 有 split 记录 | 1. `POST .../revenue-splits/{id}/wechat-submit` | 提交成功或 mock 响应 |

---

### 4.13 权限模块（RBAC）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-RBAC-001 | 查询角色列表 | P1 | API | 运营 token | 1. `GET /api/v2/ops/admin/rbac/roles` | 返回 admin/operator/replenisher/finance/viewer |
| TC-RBAC-002 | 查询权限列表 | P1 | API | 运营 token | 1. `GET /api/v2/ops/admin/rbac/permissions` | 返回全部权限码 |
| TC-RBAC-003 | 为角色分配权限 | P1 | API | admin 角色 | 1. `PUT /api/v2/ops/admin/rbac/roles/{id}/permissions` | 权限保存成功 |
| TC-RBAC-004 | 为用户分配角色 | P1 | API | admin 角色 | 1. `PUT /api/v2/ops/admin/rbac/users/{id}/roles` | 角色绑定成功 |
| TC-RBAC-005 | 查询当前用户权限 | P0 | API | 运营登录 | 1. `GET /api/v2/ops/admin/rbac/me/permissions` | 返回权限集合 |
| TC-RBAC-006 | admin 角色全权限 | P1 | API | admin 用户 | 1. 访问任意后台接口 | 全部成功（含 `*` 通配） |
| TC-RBAC-007 | viewer 角色只读 | P1 | API | viewer 用户 | 1. 尝试编辑设备/调整余额 | HTTP 403 |
| TC-RBAC-008 | replenisher 补货权限 | P2 | API | replenisher 用户 | 1. 补货开门<br>2. 尝试退款 | 补货成功，退款拒绝 |
| TC-RBAC-009 | finance 对账权限 | P2 | API | finance 用户 | 1. 执行对账<br>2. 尝试编辑 SKU | 对账成功，SKU 编辑拒绝 |
| TC-RBAC-010 | 后台按钮级权限隐藏 | P1 | UI | 无权限用户 | 1. 登录后台 | 无权限按钮不显示 |
| TC-RBAC-011 | 为用户分配商户范围 | P2 | API | 多商户环境 | 1. `PUT /api/v2/ops/admin/rbac/users/{id}/merchants` | 数据范围受限 |

---

### 4.14 基础设施模块（INFRA）

| 用例编号 | 用例名称 | 优先级 | 类型 | 前置条件 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|----------|
| TC-INFRA-001 | Docker 基础设施启动 | P0 | 运维 | Docker 可用 | 1. `cd infra && docker compose up -d` | postgres/emqx/minio 均 Running |
| TC-INFRA-002 | trade-service 健康检查 | P0 | API | 服务已启动 | 1. `GET :8080/actuator/health` | status=UP |
| TC-INFRA-003 | device-service 健康检查 | P0 | API | 服务已启动 | 1. `GET :8081/actuator/health` | status=UP |
| TC-INFRA-004 | vision-service 健康检查 | P0 | API | 服务已启动 | 1. `GET :8082/health` | 正常响应 |
| TC-INFRA-005 | API Gateway 路由 | P1 | API | gateway 容器运行 | 1. `GET http://localhost/api/v2/...` | 正确转发至 trade |
| TC-INFRA-006 | MinIO 视频存储 | P0 | 集成 | MinIO 运行 | 1. 上传测试视频<br>2. 通过 URI 访问 | 视频可读取 |
| TC-INFRA-007 | PostgreSQL 数据持久化 | P1 | 集成 | 有业务数据 | 1. 重启 postgres 容器<br>2. 查数据 | 数据不丢失 |
| TC-INFRA-008 | 本地冒烟 verify-local | P0 | 脚本 | 完整环境 | 1. 运行 `verify-local.ps1` | 全部 PASS |
| TC-INFRA-009 | 生产环境检查 verify-step5 | P1 | 脚本 | staging 配置 | 1. 运行 `verify-step5.ps1 -Staging` | 检查项通过 |
| TC-INFRA-010 | Flyway 数据库迁移 | P1 | 集成 | 全新数据库 | 1. 启动 trade-service | 迁移 V1~V23 全部成功（含 V23 免密/重力/争议 SLA/库存字段） |

---

### 4.15 运营后台 UI（ADM-UI）

> **入口：** http://localhost:8080/admin/index.html  
> **测试账号：** 13900000001 / 密码或验证码 123456  
> **自动化：** 浏览器快照验证 + `scripts/run-admin-ui-check.ps1`（页面可访问性）

#### 4.15.1 登录与全局

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-ADM-UI-001 | 登录页展示 | P0 | UI | 打开 `/admin/index.html` | 显示密码/验证码 Tab、手机号、登录按钮 |
| TC-ADM-UI-002 | 密码登录成功 | P0 | UI | 输入 13900000001/123456 点击登录 | 进入 dashboard，显示用户名与权限数 |
| TC-ADM-UI-003 | 验证码登录 Tab 切换 | P1 | UI | 点击「验证码登录」 | 显示验证码输入与获取验证码按钮 |
| TC-ADM-UI-004 | 登录失败提示 | P1 | UI | 输入错误密码 | `#loginErr` 显示错误信息 |
| TC-ADM-UI-005 | 主题切换 | P2 | UI | 点击「切换为浅色/深色主题」 | 页面主题变化并持久化（theme.js） |
| TC-ADM-UI-006 | 侧边栏分组折叠 | P2 | UI | 点击业务/运营/报表分组 | 子菜单展开/收起 |
| TC-ADM-UI-007 | 退出登录 | P1 | UI | 点击「退出」 | 返回登录页，token 清除 |
| TC-ADM-UI-008 | RBAC 菜单隐藏 | P1 | UI | 使用 viewer 角色登录 | 无权限菜单项不可见 |

#### 4.15.2 数据概览（Dashboard）

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-ADM-UI-010 | Dashboard 核心指标卡片 | P0 | UI | 登录后默认页 | 显示设备总数/在线/会话/订单/营收 |
| TC-ADM-UI-011 | 告警指标卡片 | P1 | UI | 查看 dashboard | 待审争议、SLA超时、SLA临期、待上传、低库存、待分账可点击 |
| TC-ADM-UI-012 | 卡片跳转争议页 | P1 | UI | 点击「待审争议」 | 跳转 `#/disputes` |
| TC-ADM-UI-013 | 卡片跳转补货页 | P2 | UI | 点击「低库存 SKU」 | 跳转 `#/replenishment` 并勾选仅低库存 |
| TC-ADM-UI-014 | 趋势图表渲染 | P1 | UI | 查看数据分析区 | 营收/订单/识别质量/关门会话量图表正常 |
| TC-ADM-UI-015 | 运营健康度指标 | P1 | UI | 查看健康度区 | 显示 24h 开门成功率、自动识别率、争议率 |
| TC-ADM-UI-016 | 刷新按钮 | P1 | UI | 点击「刷新」 | 数据重新加载，无报错 |

#### 4.15.3 业务模块页面

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-ADM-UI-020 | 设备管理列表 | P0 | UI | 导航 `#/devices` | 设备列表含在线状态徽章 |
| TC-ADM-UI-021 | 新增/编辑设备弹窗 | P1 | UI | 点击新增设备 | 弹窗表单可提交 |
| TC-ADM-UI-022 | 购物会话列表 | P0 | UI | 导航 `#/sessions` | 分页列表、状态筛选、导出按钮 |
| TC-ADM-UI-023 | 会话视频预览 | P1 | UI | 点击会话视频 | 视频/截图可播放 |
| TC-ADM-UI-024 | 取消会话 | P1 | UI | 对进行中会话点取消 | 确认后状态变 CANCELLED |
| TC-ADM-UI-025 | 上传队列页 | P1 | UI | 导航 `#/upload-queue` | 显示 WAITING_UPLOAD 会话 |
| TC-ADM-UI-026 | 订单管理列表 | P0 | UI | 导航 `#/orders` | 订单列表含金额、支付渠道 |
| TC-ADM-UI-027 | 充值管理列表 | P1 | UI | 导航 `#/recharges` | 充值记录、退款按钮（有权限时） |
| TC-ADM-UI-028 | 商品管理 CRUD | P1 | UI | 导航 `#/skus` | 新增/编辑 SKU 弹窗 |
| TC-ADM-UI-029 | 视觉映射管理 | P1 | UI | 导航 `#/vision-mappings` | YOLO/阿里云映射 Tab 可切换 |
| TC-ADM-UI-030 | 用户管理 | P1 | UI | 导航 `#/users` | 用户列表、调余额、核验按钮 |

#### 4.15.4 争议审核 UI

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-ADM-UI-040 | 争议列表与筛选 | P0 | UI | 导航 `#/disputes` | 状态/会话/设备筛选，分页 |
| TC-ADM-UI-041 | 争议 SLA 信息展示 | P0 | UI | 查看待审工单 | 显示截止时间与剩余小时（如 47h） |
| TC-ADM-UI-042 | 识别建议展示 | P1 | UI | 查看工单卡片 | 显示 suggestedItems |
| TC-ADM-UI-043 | 播放视频/查看截图 | P1 | UI | 点击「播放视频」或「查看截图」 | 媒体弹窗正常 |
| TC-ADM-UI-044 | 添加/移除商品行 | P1 | UI | 争议卡片内操作 | 商品下拉、数量 spinbutton 可编辑 |
| TC-ADM-UI-045 | 确认扣款按钮 | P0 | UI | 点击「确认扣款」 | 调用 CONFIRM 结案，工单消失 |
| TC-ADM-UI-046 | 免单退款按钮 | P0 | UI | 点击「免单退款」 | 调用 WAIVE 结案，提示退款金额 |

#### 4.15.5 运营与系统页面

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-ADM-UI-050 | SLA 监控页 | P1 | UI | 导航 `#/sla` | 开门成功率、识别延迟、争议超时 |
| TC-ADM-UI-051 | 风控页 | P1 | UI | 导航 `#/risk` | 风控事件列表 + 黑名单 + 添加按钮 |
| TC-ADM-UI-052 | 补货页 | P1 | UI | 导航 `#/replenishment` | 柜内库存、补货路线、仅低库存筛选 |
| TC-ADM-UI-053 | 补货录入库存 | P1 | UI | 点击「录入库存」 | 弹窗可设置 quantity/capacity |
| TC-ADM-UI-054 | 对账页 | P2 | UI | 导航 `#/reconciliation` | 对账记录、执行对账按钮 |
| TC-ADM-UI-055 | 商户分账页 | P2 | UI | 导航 `#/merchants` | 商户列表、分账记录 |
| TC-ADM-UI-056 | OTA 管理页 | P2 | UI | 导航 `#/ota` | 版本发布列表 |
| TC-ADM-UI-057 | RBAC 权限页 | P2 | UI | 导航 `#/rbac` | 角色/用户/权限分配 Tab |
| TC-ADM-UI-058 | 操作日志页 | P2 | UI | 导航 `#/audit` | 审计日志分页列表 |

---

### 4.16 微信小程序 UI（MP-UI）

> **入口：** 微信开发者工具打开 `clients/miniapp`  
> **消费者账号：** 13800138000 / 123456  
> **运营账号：** 13900000001 / 123456（ops/disputes 页面）  
> **API 冒烟：** `scripts/run-miniapp-api-smoke.ps1`（验证各页面后端接口）

#### 4.16.1 登录页（login）

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-MP-UI-001 | 登录页展示 | P0 | UI | 未登录打开小程序 | 显示手机号、验证码、登录按钮 |
| TC-MP-UI-002 | 短信验证码登录 | P0 | UI | 输入 13800138000/123456 | 跳转首页，token 存储 |
| TC-MP-UI-003 | 密码登录（如有） | P1 | UI | 切换密码登录 | passwordLogin 成功 |
| TC-MP-UI-004 | 错误验证码提示 | P1 | UI | 输入错误验证码 | showError 提示 |
| TC-MP-UI-005 | 未登录拦截 | P0 | UI | 清除 token 打开首页 | 自动跳转 login |

#### 4.16.2 首页（index）- 消费者购物

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-MP-UI-010 | 首页设备 ID 输入 | P0 | UI | 进入首页 | 显示设备 ID 输入框，默认 CAB-001 |
| TC-MP-UI-011 | 设备状态展示 | P0 | UI | 加载首页 | 显示在线/离线/占用状态文案与样式 |
| TC-MP-UI-012 | 余额展示与低余额提示 | P0 | UI | 查看余额区 | 显示余额；<5元且未免密时 balanceLow=true |
| TC-MP-UI-013 | 下拉刷新 | P1 | UI | 下拉页面 | 刷新余额与设备状态，toast「已刷新」 |
| TC-MP-UI-014 | 扫码开门 | P0 | UI | 点击扫码，扫设备二维码 | 解析 deviceId，触发 beginOpenFlow |
| TC-MP-UI-015 | 手动输入开门 | P0 | UI | 输入 CAB-001 点开门 | 创建会话，显示状态轮询 |
| TC-MP-UI-016 | 会话状态轮询 | P0 | UI | 开门后等待 | 状态标签随 OPENING→SHOPPING→… 更新 |
| TC-MP-UI-017 | 购物完成跳转结果页 | P0 | UI | 会话 COMPLETED | 自动跳转 result 页 |
| TC-MP-UI-018 | 余额不足拦截 | P0 | UI | 余额<5且无免密点开门 | 提示充值或开通免密 |
| TC-MP-UI-019 | 未实名拦截 | P0 | UI | 未实名用户点开门 | 引导实名认证 |

#### 4.16.3 我的页（mine）

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-MP-UI-020 | 账户信息展示 | P0 | UI | 进入 mine Tab | 显示手机号、余额、实名状态 |
| TC-MP-UI-021 | 开通免密支付 | P0 | UI | 点击免密入口 | ActionSheet 选微信分/支付宝，签约成功 |
| TC-MP-UI-022 | 跳转充值 | P1 | UI | 点击充值 | 进入 recharge 页 |
| TC-MP-UI-023 | 跳转订单列表 | P1 | UI | 点击我的订单 | 进入 orders 页 |
| TC-MP-UI-024 | 跳转充值记录 | P1 | UI | 点击充值记录 | 进入 recharges 页 |
| TC-MP-UI-025 | 跳转争议列表 | P1 | UI | 点击我的申诉 | 进入 dispute-mine，显示 openDisputeCount 角标 |
| TC-MP-UI-026 | 跳转实名认证 | P1 | UI | 点击实名认证 | 进入 verify 页 |
| TC-MP-UI-027 | 运营入口（运营账号） | P1 | UI | 运营账号登录 mine | 显示「运营工具」入口 |
| TC-MP-UI-028 | 退出登录 | P1 | UI | 点击退出 | 清除 token，跳转 login |

#### 4.16.4 子页面

| 用例编号 | 用例名称 | 优先级 | 类型 | 页面 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|------|----------|----------|
| TC-MP-UI-030 | 购物结果页 | P0 | UI | result | 购物完成后 | 显示订单号、金额、商品列表 |
| TC-MP-UI-031 | 充值页 | P0 | UI | recharge | 选择金额发起充值 | 调用 prepay，dev 可 mock 确认 |
| TC-MP-UI-032 | 充值记录页 | P1 | UI | recharges | 进入页面 | 列表展示历史充值 |
| TC-MP-UI-033 | 订单列表页 | P1 | UI | orders | 进入页面 | 分页订单列表 |
| TC-MP-UI-034 | 实名认证页 | P0 | UI | verify | 填写姓名+身份证后四位 | 提交成功，verified=true |
| TC-MP-UI-035 | 我的争议页 | P1 | UI | dispute-mine | 有争议记录 | 列表含状态、SLA 剩余时间 |
| TC-MP-UI-036 | 主动申诉 | P1 | UI | dispute-mine | 对已完成订单申诉 | 工单创建成功 |

#### 4.16.5 运营小程序页（ops / disputes）

| 用例编号 | 用例名称 | 优先级 | 类型 | 测试步骤 | 预期结果 |
|----------|----------|--------|------|----------|----------|
| TC-MP-UI-040 | Ops 补货开门 | P0 | UI | 运营账号进入 ops 页点补货 | 门打开，无扣款提示 |
| TC-MP-UI-041 | Ops 补货任务列表 | P1 | UI | ops 页查看任务 | 显示 my-tasks 列表 |
| TC-MP-UI-042 | Ops 识别预览上传 | P1 | UI | ops 页上传商品图 | 显示 recognition-preview 结果 |
| TC-MP-UI-043 | Ops 争议列表 | P1 | UI | 进入 disputes 页 | 展示待审争议 |
| TC-MP-UI-044 | Ops 移动端结案 | P1 | UI | disputes 页解决工单 | CONFIRM/WAIVE 与后台一致 |

---

## 5. 端到端测试场景（E2E Scenarios）

以下场景由多条用例组合，用于回归测试与发布验证。

### 5.1 场景 S1：新用户首次购物（P0）

```
步骤：
1. [TC-AUTH-002] 消费者登录
2. [TC-ACCT-002] 完成实名认证（若未实名）
3. [TC-PAY-001][TC-PAY-003] 充值（若余额不足）
4. [TC-SESS-001] 创建会话
5. [TC-DEV-003] 设备开门
6. [TC-SESS-005] 关门+视频+识别
7. [TC-ORDR-003] 验证扣款
8. [TC-SESS-014] 小程序查看结果

通过标准：会话 COMPLETED，订单 PAID，按 payChannel 验证扣款（余额扣减或免密不变）
自动化：e2e-shopping.ps1（含 payChannel 断言）
```

### 5.2 场景 S2：识别争议与人工处理（P0）

```
步骤：
1. [TC-VIS-004] 上传未识别图片完成购物（mock 关闭时）
2. [TC-DISP-001] 确认会话 DISPUTED，工单含 slaDueAt
3. [TC-DISP-005] 运营查看争议
4. [TC-DISP-006] 运营 CONFIRM 确认商品并解决
5. [TC-DISP-007] 验证扣款

通过标准：争议解决后会话 COMPLETED，ResolveDisputeResultDto 金额正确
```

### 5.3 场景 S3：运营补货（P0）

```
步骤：
1. [TC-AUTH-004] 运营登录
2. [TC-OPS-001] 补货开门
3. 模拟关门
4. 验证无订单生成
5. [TC-DEV-002] 设备恢复可用

通过标准：无扣款，设备可再次使用
```

### 5.4 场景 S4：离线视频补传（P1）

```
步骤：
1. [TC-SESS-001] 创建会话并开门
2. [TC-SESS-010] 离线关门（WAITING_UPLOAD）
3. [TC-ADM-014] 后台可见上传队列
4. [TC-SESS-011] 补传视频
5. [TC-SESS-005] 完成识别结算

通过标准：补传后正常结算
```

### 5.5 场景 S5：风控拦截（P1）

```
步骤：
1. [TC-RISK-002] 拉黑用户
2. [TC-RISK-001] 尝试开门
3. [TC-RISK-003] 解除黑名单
4. [TC-SESS-001] 再次开门成功

通过标准：拉黑拦截，解除后恢复
```

### 5.6 场景 S6：Staging 全链路（P0）

```
步骤：
1. 初始化 staging 环境（init-staging-env.ps1）
2. [TC-AUTH-001] 真实 SMS webhook 登录
3. 完整购物流程（e2e-staging.ps1）

通过标准：无 mock 模式下全流程通过
自动化：e2e-staging.ps1
```

### 5.7 场景 S7：免密支付购物（P0）

```
步骤：
1. [TC-AUTH-002] 消费者登录
2. [TC-PFREE-001] 开通微信支付分
3. [TC-PFREE-003] 余额调至 <5 元后仍可开门
4. [TC-SESS-003] 完成购物
5. [TC-ORDR-003a] 验证 payChannel=WECHAT，余额不变

通过标准：免密开通成功，低余额可开门，订单走微信渠道
```

### 5.8 场景 S8：重力柜兜底结算（P1）

```
步骤：
1. [TC-SESS-001] 创建会话并开门
2. [TC-GRAV-001] 上报重力 deltas（SKU-DEMO-001 ×1）
3. [TC-SESS-005] 关门+视频（vision 返回空或不可用且 mock 关闭）
4. [TC-GRAV-003] 验证重力兜底生成订单

通过标准：视觉无结果时以重力数据结算，非 FAILED
```

---

## 6. 非功能测试要点

### 6.1 安全性

| 编号 | 测试项 | 验证方法 |
|------|--------|----------|
| SEC-001 | JWT 不可伪造 | 篡改 token payload 后访问 |
| SEC-002 | 内部 API Key 保护 | 无 Key 调用 `/internal/v1/*` |
| SEC-003 | 消费者无法访问运营 API | 消费者 token 调用 `/ops/admin/*` |
| SEC-004 | 支付回调签名校验 | 伪造 notify 请求被拒绝 |
| SEC-005 | SQL 注入防护 | 参数化查询，异常输入不报错 |
| SEC-006 | 免密签约需登录 | 无 token 调用 payscore/sign | HTTP 401 |

### 6.2 性能冒烟

| 编号 | 测试项 | 基准 |
|------|--------|------|
| PERF-001 | 创建会话响应时间 | < 2s（含 MQTT 下发） |
| PERF-002 | 账户查询响应时间 | < 200ms |
| PERF-003 | 识别超时处理 | 超时后会话不无限挂起 |
| PERF-004 | 后台列表分页 | 1000 条数据分页 < 1s |

### 6.3 兼容性

| 编号 | 测试项 | 范围 |
|------|--------|------|
| COMPAT-001 | 微信小程序基础库 | 2.x / 3.x |
| COMPAT-002 | 运营后台浏览器 | Chrome、Edge 最新版 |
| COMPAT-003 | API 版本 | 仅 `/api/v2/*`，旧版 `/m8/v1/*` 不可用 |

---

## 7. 测试执行策略

### 7.1 冒烟测试（每次构建）

执行以下用例，预计 15 分钟：

- TC-INFRA-001 ~ TC-INFRA-004
- TC-AUTH-002、TC-AUTH-004
- TC-SESS-003（e2e-shopping.ps1，含 payChannel 校验）
- TC-PAY-003（e2e-recharge.ps1）
- TC-ADM-001（含 disputeOverdue、lowStockSkuCount 新字段）

### 7.2 回归测试（每次发布）

- 全部 P0 用例
- E2E 场景 S1~S3、S7
- 有变更模块的 P1 用例

### 7.3 全量测试（大版本发布）

- 本文档全部 P0~P2 用例
- E2E 场景 S1~S8
- 非功能测试抽样

---

## 8. 缺陷严重等级

| 等级 | 定义 | 示例 |
|------|------|------|
| **致命** | 系统崩溃、数据丢失、资金错误 | 重复扣款、余额为负 |
| **严重** | 核心功能不可用 | 无法开门、无法登录 |
| **一般** | 功能异常但有绕过方案 | 导出 CSV 乱码 |
| **轻微** | UI 问题、文案错误 | 按钮对齐、提示不准确 |
| **建议** | 体验优化 | 加载动画、交互优化 |

---

## 9. 测试交付物

| 交付物 | 说明 |
|--------|------|
| 测试用例文档 | 本文档 |
| 测试执行记录 | 用例执行结果（Pass/Fail/Blocked） |
| 缺陷报告 | 按严重等级分类 |
| 自动化测试报告 | E2E 脚本执行日志 |
| 测试总结报告 | 覆盖率、遗留风险、发布建议 |

---

## 10. 附录

### 10.1 权限码对照表

| 权限码 | 说明 |
|--------|------|
| `ops:dashboard:view` | 查看仪表盘 |
| `ops:device:list` | 设备列表 |
| `ops:device:edit` | 设备编辑 |
| `ops:session:list` | 会话列表 |
| `ops:session:cancel` | 取消会话 |
| `ops:session:upload` | 上传队列 |
| `ops:order:list` | 订单/充值列表 |
| `ops:sku:list` / `ops:sku:edit` | SKU 查看/编辑 |
| `ops:user:list` | 用户列表 |
| `ops:user:balance` | 余额调整/退款 |
| `ops:dispute` | 争议处理 |
| `ops:vision:list` / `ops:vision:edit` | 视觉映射 |
| `ops:audit:list` / `ops:audit:recent` | 审计日志 |
| `ops:sla` | SLA 指标 |
| `ops:ota:list` / `ops:ota:publish` | OTA 管理 |
| `ops:risk:list` / `ops:risk:blacklist` | 风控管理 |
| `ops:reconciliation:list` / `ops:reconciliation:run` | 对账 |
| `ops:replenishment:list` / `ops:replenishment:edit` | 补货管理 |
| `ops:merchant:list` / `ops:merchant:edit` / `ops:merchant:split` | 商户管理 |
| `ops:rbac:role` / `ops:rbac:assign` | RBAC 管理 |
| `*` | 超级管理员全权限 |

### 10.2 主要 API 路径速查

| 模块 | 基础路径 |
|------|----------|
| 认证 | `/api/v2/auth` |
| 账户 | `/api/v2/account`（含 `/payscore/sign`、`/alipay-agreement/sign`） |
| 设备 | `/api/v2/devices` |
| 会话 | `/api/v2/sessions` |
| 订单 | `/api/v2/orders` |
| 支付 | `/api/v2/payment` |
| 消费者争议 | `/api/v2/disputes` |
| 运营操作 | `/api/v2/ops` |
| 运营后台 | `/api/v2/ops/admin` |
| 内部接口 | `/internal/v1`（含 `/sessions/gravity-deltas`） |
| 视觉服务 | `:8082/api/v2/vision` |

### 10.3 已知测试覆盖缺口

以下场景当前**无自动化覆盖**，需手动或补充脚本：

1. 争议全流程 E2E（自动创建 → CONFIRM/ADJUST/WAIVE 结案）
2. 免密支付完整 E2E（签约 → 购物 → 原路退款）
3. 重力柜兜底结算 E2E
4. RBAC 各角色权限拒绝矩阵
5. 争议 SLA 超时告警 webhook（`DisputeSlaScheduler`）
6. 离线补传完整 E2E
7. 小程序 UI 自动化
8. Android 真机端到端（含重力上报）
9. 商户分账 / 微信分账提交
10. 多摄像头融合在 trade 管道中的集成

**已有自动化/单测覆盖（v1.1 新增）：**

- `e2e-shopping.ps1`：payChannel 与余额联动断言
- `SettlementDisputeTest`：空识别转争议（非 FAILED）
- `PaymentServiceTest`：充值与 mock 回调

### 10.4 修订记录

| 版本 | 日期 | 修订人 | 说明 |
|------|------|--------|------|
| v1.0 | 2026-07-09 | 测试工程师 | 初版，覆盖 Phase 7 全模块 |
| v1.1 | 2026-07-09 | 测试工程师 | 同步最新代码：免密支付、重力柜、争议 SLA 等 |
| v1.2 | 2026-07-09 | 测试工程师 | 补充未覆盖 E2E、运营后台 UI（ADM-UI）、小程序 UI（MP-UI）用例及自动化脚本 |

---

*本文档基于项目源码、`docs/` 设计文档及 `scripts/` E2E 脚本分析编写。测试执行时请以实际环境配置为准。*
