# Apifox Mock 演示场景清单（项目 8780097）

配合 `scripts/sync-apifox-oas.ps1`（已导入 OAS）与 `scripts/apifox-smoke-scenario.ps1`（CLI 对拍）。

## 环境

| 变量 | 值 |
|------|-----|
| Base URL | `http://127.0.0.1:18080`（Docker 全栈）或 `http://127.0.0.1:8080`（IDEA） |
| 消费者 | `13800138000` / `123456` |
| 商户 | `13800138001` / `123456` |
| 超管 | `13900000001` / `123456`（密码登录需图形验证码；CLI 脚本会从 Redis 自动补） |

在 Apifox：**环境 → 新建 `local-docker`**，变量：

- `baseUrl` = `http://127.0.0.1:18080`
- `consumerToken` / `merchantToken` / `opsToken`（由登录步骤写入）
- `deviceId` = `CAB-001`

## Scenario：Mock demo smoke（S01–S10）

在 Apifox：**自动化测试 / 测试场景 → 新建文件夹 `Mock demo smoke`**，按序添加接口（均已在 OAS 中）：

| 步骤 | 方法 | 路径 | 断言要点 | 写回变量 |
|------|------|------|----------|----------|
| S01 | POST | `/api/v2/auth/password-login` | `code==0`，有 `data.token` | `consumerToken=data.token` |
| S02 | POST | `/api/v2/auth/admin-password-login` | 商户账号登录成功 | `merchantToken` |
| S03 | POST | `/api/v2/auth/admin-password-login` | 超管登录成功（附 captcha） | `opsToken` |
| S04 | GET | `/api/v2/account` | Header `Bearer {{consumerToken}}`；有 `balanceCents` | — |
| S05 | GET | `/api/v2/devices/{{deviceId}}/status` | `online` 存在 | — |
| S06 | GET | `/api/v2/orders?page=0&size=5` | `data.items` 为数组 | — |
| S07 | GET | `/api/v2/merchant/orders?deviceId={{deviceId}}&page=0&size=5` | `data.items` 为数组 | — |
| S08 | GET | `/api/v2/ops/disputes?page=0&size=5` | Bearer ops；列表成功 | — |
| S09 | GET | `/api/v2/ops/admin/exceptions?page=0&size=5` | 列表成功 | — |
| S10 | GET | `/api/v2/ops/admin/stats` | 有 `deviceTotal` | — |

可选 **S11**（会改数据）：`POST /api/v2/sessions` 开门后立刻 `cancel` / `demo-close`。CLI：`.\scripts\apifox-smoke-scenario.ps1 -WithOpenDoor`。

## Scenario：争议闭环（对拍 e2e-three-end Joint）

完整购物 + KEEP/WAIVE/CONFIRM 依赖 MQTT 模拟器与 vision mock，**优先用脚本**：

```powershell
$env:E2E_BASE_URL='http://127.0.0.1:18080'
.\scripts\e2e-three-end.ps1 -IncludeHappyPath
```

若在 Apifox 手工演示，最小路径：

1. 消费者开门 → 会话进 `DISPUTED`（force-need-review / mock）
2. `GET /api/v2/ops/disputes?status=OPEN&sessionId=...`
3. `POST /api/v2/ops/disputes/{ticketId}/resolve`（`WAIVE` / `KEEP` / `CONFIRM`）
4. 核对消费者 / 商户 / 运营订单状态与金额一致

## CLI 对拍

```powershell
.\scripts\sync-apifox-oas.ps1          # 刷新 OAS 到 Apifox
.\scripts\apifox-smoke-scenario.ps1    # S01–S10，报告 .tmp/apifox-smoke-report.json
```

步骤 id（S01…）与上表一致，便于两边对照。
