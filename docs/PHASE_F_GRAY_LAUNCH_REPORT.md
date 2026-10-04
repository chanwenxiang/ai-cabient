# Phase F 灰度启动验收报告

执行日期：2026-07-12  
环境：Docker `ai-cabinet` + 消费者 H5（5174）+ trade-service :8080  
脚本：`scripts/phase-f-gray-launch.ps1`

## 统计

| 检查项 | 结果 |
|--------|------|
| 灰度门禁（CheckOnly） | **17/17 PASS** |
| API smoke（含在完整运行） | **13/13 PASS** |
| 真实 YOLO 购物浏览器验收 | **PASS** |
| 数据清洁度 | open_exceptions=0, open_disputes=0 |

## 1. 视觉服务生产就绪（§10）

| 项 | 值 | 状态 |
|----|-----|------|
| `recognizer_available` | `true` | PASS |
| `mock_enabled` | `false` | PASS |
| `recognizer_backend` | `yolo` | PASS |
| `model_version` | `/app/models/yolov8n.pt` | 通用模型（待换 SKU 专用） |

> 当前为通用 YOLOv8n；正式上线前需替换为商品专用模型并完成准确率/漏识别率实测（FINAL §10）。

## 2. 基础设施 smoke

| 服务 | 状态 |
|------|------|
| trade-service :8080 | UP |
| device-service :8081 | UP |
| vision-service :8082 | ok |
| gateway :80 | 200 |
| grafana :13000 | 200 |
| prometheus :9090 | 200 |

## 3. 灰度配置

| 项 | 值 |
|----|-----|
| 灰度柜机 | CAB-001（1 台） |
| 观察窗口 | 14 天（配置已满足，**尚未开始正式计时**） |
| 灰度消费者 | 13800138000，余额 11300 分 |
| 运营账号 | 13900000001 |
| CAB-001 | online=true, available=true |

## 4. 测试数据清理

执行 `scripts/cleanup-test-data.ps1`：

- 关闭 UAT 遗留 `DEVICE_FAULT` 异常 `EX4C1D21BE31134D6DB2`
- 恢复消费者余额至 **11300 分**
- 清除 CAB-001 阻塞会话

## 5. 真实 YOLO 购物浏览器验收

| 步骤 | 操作 | 结果 |
|------|------|------|
| 1 | 消费者 H5 token 注入 + 首页加载 | 支付分标签、扫码按钮正常 |
| 2 | 手动输入 CAB-001 → 确认并开门 | 「门已开 · 购物中」 |
| 3 | 模拟器 25s 购物 + 关门识别 | 跳转 result 页 |
| 4 | 账单结果 | **已支付 ¥3.50**，可口可乐 330ml ×1 |
| 5 | 余额 | ¥113.00 → ¥109.50 |

**DB 证据：**

| 实体 | ID | 状态 |
|------|-----|------|
| 会话 | `S4B158A802CD14B7E` | COMPLETED |
| 订单 | `OEB0670DDDAE944E5` | PAID / BALANCE / 350 分 |

截图：`docs/uat-screenshots/2026-07-12/PHASE-F-YOLO-shopping-paid.png`

## 6. 仍须人工完成的上线项

以下不在本地 Docker 单次验收范围内，需部署/运维阶段完成：

1. **训练并部署 SKU 专用模型** — 见 [`VISION_SKU_MODEL.md`](VISION_SKU_MODEL.md)
2. **生产 `.env`** + `docker-compose.production.yml`（模板已就绪）
3. **HTTPS 正式域名 + 小程序合法域名**
4. **1–3 台真实柜机** + 白名单消费者
5. **连续 14 天**灰度观察 + 每日对账（FINAL §12 阶段 F / §14）

配置脚手架：`infra/.env.production.example`、`scripts/package-vision-model.ps1`、`scripts/verify-vision-model.ps1`

## 结论

**开发/预发布灰度前置条件已满足**（17/17 门禁 + API smoke + 真实 YOLO 识别购物闭环）。

**尚未满足正式生产放量**：专用模型、14 天观察、真机柜部署、生产 mock 全关、HTTPS 域名。
