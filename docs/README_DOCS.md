# AI Cabinet 文档索引

> **现行真源**：日常开发/测试先读 [MASTER_TEST_PLAN.md](MASTER_TEST_PLAN.md)（测试总真源）+ [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md)（全仓底稿）+ [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md)（端口/账号速查）。  
> 本文原为 2026-07 上线执行索引，已于 2026-09 收敛；历史分析见 `archive/`。

---

## 现行文档（优先）

| 文档 | 说明 |
|------|------|
| [MASTER_TEST_PLAN.md](MASTER_TEST_PLAN.md) | **测试总真源 v2.1**：业务/UI/资金/权限/一致性/安全/性能/回归自动化/上线门槛；页面基线、**§2.7 UI/UX PR 最短验收**、改动→必跑映射（§7.0）、Playwright 纪律（§7.3）、CI 对照（§2.3）。**与其他测试文档冲突时以本文为准** |
| [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) | **全仓底稿**：架构、关键链路、测试资产、优化热点、验证矩阵 |
| [CODEBASE_INVENTORY.md](CODEBASE_INVENTORY.md) | **文件级清单**：Controller/端点/God 类/页面/测试包（精确测试点名） |
| [pass-notes/PASS_3A_MONEY.md](pass-notes/PASS_3A_MONEY.md) | **Pass 3A**：金钱链路分支表与补测清单 |
| [pass-notes/PASS_3B_DISPUTE.md](pass-notes/PASS_3B_DISPUTE.md) | **Pass 3B**：争议结案/退款/三端权限与补测清单 |
| [pass-notes/PASS_3C_MQTT.md](pass-notes/PASS_3C_MQTT.md) | **Pass 3C**：MQTT 开门/门事件/去重/ACK 与补测清单 |
| [pass-notes/PASS_3D_INVENTORY.md](pass-notes/PASS_3D_INVENTORY.md) | **Pass 3D**：库存/FEFO/货道实测/回库与补测清单 |
| [pass-notes/PASS_3E_MERCHANT_WALLET.md](pass-notes/PASS_3E_MERCHANT_WALLET.md) | **Pass 3E**：分账/钱包/提现/微信回退与补测清单 |
| [pass-notes/PASS_3F_OPS_GOD_CLASSES.md](pass-notes/PASS_3F_OPS_GOD_CLASSES.md) | **Pass 3F**：运营/商户神类切片；Ops Controller 按域拆分与 Facade 删除已落地 |
| [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md) | 端口 / 账号 / 启动模式速查 |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 本地联调完整说明 |
| [MODULES.md](MODULES.md) | 模块路径与职责 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构边界与识别策略 |
| [DEMO_ACCOUNTS.md](DEMO_ACCOUNTS.md) | 演示账号矩阵 |
| [BUSINESS_FULL_TEST_MATRIX.md](BUSINESS_FULL_TEST_MATRIX.md) | **全业务测试矩阵 v1.2**：页面+L3 挂钩+权限/字典/边界；含 **P0 子集/证据/环境/H5≠小程序/自动化对照** |
| [BROWSER_MIN_UAT.md](BROWSER_MIN_UAT.md) | 最小浏览器 UAT（抽样） |
| [BROWSER_FULL_UAT_PLAN.md](BROWSER_FULL_UAT_PLAN.md) | 全量 UAT 计划（抽样场景编排） |
| [PRODUCTION.md](PRODUCTION.md) | 生产部署 |
| [VISION_QUECTEL_INTEGRATION.md](VISION_QUECTEL_INTEGRATION.md) | 端侧识别对接（替代已删除的云端 YOLO 文档） |
| [DEVOPS.md](DEVOPS.md) | 监控 / Sonar / Runner |

---

## 上线与历史规划

| 文档 | 说明 |
|------|------|
| [GO_LIVE_EXECUTION_PLAN.md](GO_LIVE_EXECUTION_PLAN.md) | 2026-07 上线主计划（**含过时 YOLO 叙述**，文首有勘误） |
| [CODE_FIX_CHECKLIST.md](CODE_FIX_CHECKLIST.md) | 代码修复清单 |
| [production-launch-checklist.md](production-launch-checklist.md) | 上线门禁清单 |

### 历史 P0（2026-07 快照，供对照）

| 阻塞项 | 现行状态（2026-09） |
|--------|---------------------|
| 无营业执照 | 仍属行政阻塞 |
| Mock 支付 | 仍需真实商户号；dev 可 mock |
| 未对接硬件 | edge/android + 模拟器；真柜协议持续联调 |
| 通用 YOLO 模型 | **策略已变**：不做云端自研 YOLO，改端侧识别 |

---

## 快速导航

| 角色 | 推荐阅读 |
|------|----------|
| 开发 / 测试 | **MASTER_TEST_PLAN（测试总入口）** → CODEBASE_FOUNDATION → STARTUP_REFERENCE → BUSINESS_FULL_TEST_MATRIX（明细分册）→ BROWSER_MIN_UAT |
| 后端 | CODEBASE_FOUNDATION §3–§5 → ARCHITECTURE → VISION_QUECTEL |
| 前端 | CODEBASE_FOUNDATION §6 → BUSINESS_FULL_TEST_MATRIX → BROWSER_* → MODULES |
| 运维 | PRODUCTION → DEVOPS → production-launch-checklist |
| 项目经理 | GO_LIVE（勘误后）→ CODE_FIX_CHECKLIST |

---

## 历史归档

已归档至 [docs/archive/](archive/)：

- `ANALYSIS_*`（各端 2026-07 分析）
- `BROWSER_FULL_UAT_REPORT` / `TRACKING` / `REGRESSION_TRACKING`
- `TEST_REPORT_2026-08-13.md`、`THREE_END_DATA_VALIDATION_REPORT.md`（点状验收报告）
- `superpowers/`（设计会话）
- `uat-screenshots/`（旧截图证据）

**已删除（勿再引用）**：`OPEN_CABINET_DATA_COLLECTION.md`、`VISION_SKU_MODEL.md`、`VISION_YOLO_TEST.md`、`scripts/verify-vision-model.ps1` 及一批一次性 Sonar/编码修复脚本。

---

**索引更新日期**: 2026-09-12
