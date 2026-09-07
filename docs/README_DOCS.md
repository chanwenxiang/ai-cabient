# AI Cabinet 文档索引

> **现行真源**：日常开发/测试先读 [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) + [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md)。  
> 本文原为 2026-07 上线执行索引，已于 2026-09 收敛；历史分析见 `archive/`。

---

## 现行文档（优先）

| 文档 | 说明 |
|------|------|
| [CODEBASE_FOUNDATION.md](CODEBASE_FOUNDATION.md) | **全仓底稿**：架构、关键链路、测试资产、优化热点、验证矩阵 |
| [STARTUP_REFERENCE.md](STARTUP_REFERENCE.md) | 端口 / 账号 / 启动模式速查 |
| [LOCAL_SETUP.md](LOCAL_SETUP.md) | 本地联调完整说明 |
| [MODULES.md](MODULES.md) | 模块路径与职责 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 架构边界与识别策略 |
| [DEMO_ACCOUNTS.md](DEMO_ACCOUNTS.md) | 演示账号矩阵 |
| [BROWSER_MIN_UAT.md](BROWSER_MIN_UAT.md) | 最小浏览器 UAT |
| [BROWSER_FULL_UAT_PLAN.md](BROWSER_FULL_UAT_PLAN.md) | 全量 UAT 计划 |
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
| 开发 / 测试 | CODEBASE_FOUNDATION → STARTUP_REFERENCE → BROWSER_MIN_UAT |
| 后端 | CODEBASE_FOUNDATION §3–§5 → ARCHITECTURE → VISION_QUECTEL |
| 前端 | CODEBASE_FOUNDATION §6 → BROWSER_* → MODULES |
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

**索引更新日期**: 2026-09-07
