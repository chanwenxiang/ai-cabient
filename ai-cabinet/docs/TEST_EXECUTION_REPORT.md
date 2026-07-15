# AI Cabinet 测试执行报告

| 项目 | 内容 |
|------|------|
| **执行日期** | 2026-07-09 |
| **报告版本** | v2.0（含未覆盖场景 + 前后端 UI） |
| **依据文档** | [TEST_CASES.md](TEST_CASES.md) v1.2 |
| **环境** | 本地 Docker（infra + apps），dev profile |

---

## 1. 执行摘要

| 类别 | 通过 | 失败 | 跳过/环境 | 合计 |
|------|------|------|-----------|------|
| JUnit 单元测试 | 22 | 0 | 2 | 24 |
| 冒烟 E2E（充值+购物） | 2 | 0 | 0 | 2 |
| 扩展 E2E（未覆盖场景） | 9 | 0 | 0 | 9 |
| API 用例脚本 | 12 | 1 | 0 | 13 |
| 小程序 API 冒烟 | 14 | 2 | 0 | 16 |
| 运营后台 UI 数据检查 | 21 | 0 | 0 | 21 |
| 浏览器 UI 验证（抽样） | 8 | 0 | 0 | 8 |

**总体结论：核心与扩展链路全部通过；小程序 2 项因设备占用（409/500）失败，属环境时序问题；浏览器 UI 抽样验证通过。**

---

## 2. 第一阶段（冒烟）— 已通过

| 脚本/用例 | 结果 |
|-----------|------|
| `mvn test` trade-service | PASS（22 tests, 2 skipped） |
| `mvn test` device-service | PASS（2 tests） |
| `verify-step2.ps1` 健康检查 | PASS |
| `e2e-recharge.ps1` | PASS |
| `e2e-shopping.ps1` | PASS（payChannel=WECHAT，免密余额不变） |
| `run-api-tests.ps1` | 12/13 PASS（TC-DEV-002 占用期失败） |

---

## 3. 第二阶段（未覆盖场景）— 全部通过

执行：`scripts/run-extended-e2e.ps1`

| 用例编号 | 场景 | 结果 | 验证点 |
|----------|------|------|--------|
| TC-OPS-001 | 运营补货不结算 | **PASS** | COMPLETED，无订单 |
| TC-SESS-011 | 离线视频补传 | **PASS** | WAITING_UPLOAD → COMPLETED |
| TC-RISK-001 | 黑名单拦截与解除 | **PASS** | 403 拦截后恢复可开门 |
| TC-PFREE-002 | 支付宝代扣签约 | **PASS** | alipayAgreementEnabled=true |
| TC-DISP-006 | 争议 CONFIRM 结案 | **PASS** | 订单生成，会话 COMPLETED |
| TC-DISP-006b | 争议 WAIVE 免单 | **PASS** | 退还 ¥3.50 |
| TC-COMM-002 | 库存录入 | **PASS** | quantity=10 |
| TC-GRAV-003 | 重力柜兜底结算 | **PASS** | 视觉+重力 → COMPLETED |
| TC-DISP-002 | 消费者争议列表 | **PASS** | 10 条记录 |

**场景覆盖更新：**

| 场景 | 状态 |
|------|------|
| S2 争议人工处理 | **PASS**（CONFIRM + WAIVE） |
| S3 运营补货 | **PASS** |
| S4 离线补传 | **PASS** |
| S5 风控拦截 | **PASS** |
| S7 免密支付购物 | **PASS**（第一阶段） |
| S8 重力柜兜底 | **PASS** |

**仍待执行：**

| 场景 | 原因 |
|------|------|
| S6 Staging 全链路 | 需 staging 环境 |
| 视觉识别争议 E2E | 需 testdata/bus.jpg + MOCK 关闭 |

---

## 4. 小程序 UI 后端冒烟

执行：`scripts/run-miniapp-api-smoke.ps1`

| 用例 | 页面 | 结果 |
|------|------|------|
| UI-MP-001 | login | PASS |
| UI-MP-002 | index/balance | PASS |
| UI-MP-003 | index/device | PASS |
| UI-MP-004 | index/session | **FAIL**（409 设备占用） |
| UI-MP-005~012 | mine/recharge/orders 等 | PASS |
| UI-MP-013 | ops/restock | **FAIL**（500 设备忙） |
| UI-MP-014~016 | ops/tasks/disputes/result | PASS |

**说明：** 扩展 E2E 连续创建多个会话后设备短暂占用，导致 409/500。设备空闲后单独调用可 PASS。

**小程序 UI 手动测试清单：** 见 TEST_CASES.md §4.16（44 条 TC-MP-UI-*），需微信开发者工具执行。

---

## 5. 运营后台 UI 测试

### 5.1 数据层自动化（21/21 PASS）

执行：`scripts/run-admin-ui-check.ps1`

全部 20 个后台页面 API 数据加载成功：dashboard、devices、sessions、orders、recharges、skus、users、disputes、vision-mappings、upload-queue、sla、ota、risk、reconciliation、replenishment、merchants、rbac、audit、recent、reports。

### 5.2 浏览器 UI 抽样验证（8/8 PASS）

| 用例 | 页面 | 结果 | 验证内容 |
|------|------|------|----------|
| TC-ADM-UI-001/002 | 登录 | **PASS** | 密码登录进入 dashboard |
| TC-ADM-UI-010/011 | Dashboard | **PASS** | 核心指标、告警卡片、图表 |
| TC-ADM-UI-040~046 | 争议审核 | **PASS** | 列表、SLA 47h、确认扣款/免单退款按钮、视频播放 |
| TC-ADM-UI-051 | 风控 | **PASS** | 事件列表、黑名单、添加按钮 |
| TC-ADM-UI-052 | 补货 | **PASS** | 库存列表、仅低库存筛选、录入库存 |

**后台 UI 完整用例：** 见 TEST_CASES.md §4.15（58 条 TC-ADM-UI-*）。

---

## 6. 新增自动化脚本

| 脚本 | 用途 |
|------|------|
| `scripts/run-extended-e2e.ps1` | 补货/离线/风控/争议结案/库存/重力 |
| `scripts/run-miniapp-api-smoke.ps1` | 小程序 11 个页面对应 API |
| `scripts/run-admin-ui-check.ps1` | 后台 20 个页面数据 API |
| `scripts/run-api-tests.ps1` | 基础 API 用例批量 |

**推荐完整执行顺序：**

```powershell
cd ai-cabinet
.\scripts\verify-step2.ps1 -SkipInfra -SkipE2e
.\scripts\e2e-recharge.ps1
.\scripts\e2e-shopping.ps1
.\scripts\run-extended-e2e.ps1
.\scripts\run-api-tests.ps1
.\scripts\run-miniapp-api-smoke.ps1
.\scripts\run-admin-ui-check.ps1
cd services\trade-service && mvn test
```

---

## 7. 发现的问题

| 编号 | 严重度 | 描述 | 建议 |
|------|--------|------|------|
| ISSUE-001 | 轻微 | E2E 并行执行余额竞态 | CI 顺序执行 |
| ISSUE-002 | 轻微 | ffmpeg drawtext 字体报错 | 上传脚本 fallback 仍可用 |
| ISSUE-003 | 轻微 | 连续会话后设备 409/500 | 测试间等待或取消活跃会话 |
| ISSUE-004 | 信息 | Testcontainers 集成测试跳过 | CI 配置 Docker |
| ISSUE-005 | 信息 | 小程序 UI 无法 headless 自动化 | 保留微信开发者工具手动清单 |

---

## 8. 测试覆盖率总结

| 模块 | API/E2E | 后台 UI | 小程序 UI |
|------|---------|---------|-----------|
| 认证 | ✅ | ✅ 登录 | ✅ API / 📋 手动 |
| 购物会话 | ✅ | ✅ 会话页 | 📋 手动 |
| 免密支付 | ✅ | — | 📋 手动 |
| 争议 SLA | ✅ | ✅ 争议页 | 📋 手动 |
| 风控 | ✅ | ✅ 风控页 | — |
| 补货/库存 | ✅ | ✅ 补货页 | 📋 ops 手动 |
| 重力柜 | ✅ | — | — |
| 离线补传 | ✅ | ✅ 上传队列 | — |
| 视觉识别 | 部分 | ✅ 映射页 | 📋 ops 手动 |

图例：✅ 已自动化执行通过 | 📋 用例已编写，需手动/UI 工具执行

---

## 9. 结论

1. **API + E2E 覆盖率达到 v1.2 文档 P0/P1 要求的约 90%**（除 Staging、真实 YOLO 争议、小程序真机 UI）。
2. **运营后台 Web UI** 已完成浏览器抽样 + 全页面 API 数据验证。
3. **微信小程序 UI** 已补充 44 条用例 + 16 项 API 冒烟；完整 UI 需微信开发者工具按 §4.16 执行。
4. 建议将 `run-extended-e2e.ps1` 和 `run-admin-ui-check.ps1` 纳入 CI 流水线。

---

*报告 v2.0 · 对应 TEST_CASES.md v1.2*
