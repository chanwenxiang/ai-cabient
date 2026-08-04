# AI开门柜项目上线执行文档索引

## 文档概览

本套文档详细分析了AI开门柜项目的各个端，识别了核心阻塞问题、业务缺失、代码Bug和UI问题，提供了完整的上线执行方案。

---

## 文档清单

### 主文档
| 文档 | 大小 | 说明 |
|------|------|------|
| [GO_LIVE_EXECUTION_PLAN.md](GO_LIVE_EXECUTION_PLAN.md) | 19KB | **主执行文档** - 项目概览、核心阻塞、时间规划 |

### 端分析文档
| 文档 | 大小 | 说明 |
|------|------|------|
| [ANALYSIS_ADMIN_VUE.md](ANALYSIS_ADMIN_VUE.md) | 8KB | 运营管理后台详细分析 |
| [ANALYSIS_CONSUMER_MP.md](ANALYSIS_CONSUMER_MP.md) | 9KB | 消费者小程序详细分析 |
| [ANALYSIS_MERCHANT_MP.md](ANALYSIS_MERCHANT_MP.md) | 8KB | 补货员/商户小程序详细分析 |
| [ANALYSIS_BACKEND_SERVICES.md](ANALYSIS_BACKEND_SERVICES.md) | 12KB | 后端服务详细分析 |
| [ANALYSIS_HARDWARE_INTEGRATION.md](ANALYSIS_HARDWARE_INTEGRATION.md) | 13KB | 硬件对接详细分析 |

### 问题修复文档
| 文档 | 大小 | 说明 |
|------|------|------|
| [CODE_FIX_CHECKLIST.md](CODE_FIX_CHECKLIST.md) | 8KB | 代码问题修复清单 |

### 浏览器 UAT（现行）
| 文档 | 说明 |
|------|------|
| [BROWSER_MIN_UAT.md](BROWSER_MIN_UAT.md) | 最小可执行 UAT 包 |
| [BROWSER_MIN_UAT_TRACKING.md](BROWSER_MIN_UAT_TRACKING.md) | MIN 执行跟踪（含 2026-08-04 复测） |
| [BROWSER_MIN_UAT_REPORT.md](BROWSER_MIN_UAT_REPORT.md) | MIN 执行报告 |
| [BROWSER_FULL_UAT_PLAN.md](BROWSER_FULL_UAT_PLAN.md) | 全量 UAT 计划 |
| [BROWSER_FULL_UAT_TRACKING.md](BROWSER_FULL_UAT_TRACKING.md) | 全量执行跟踪 |

---

## 核心发现

### 🔴 P0阻塞问题

| 阻塞项 | 影响 | 解决方案 | 预计时间 |
|--------|------|----------|----------|
| 无营业执照 | 无法发布小程序 | 注册个体工商户/公司 | 2-4周 |
| Mock支付 | 无法真实收费 | 申请微信/支付宝商户号 | 1-2周 |
| 未对接硬件 | 柜机无法开门 | 获取协议文档后对接 | 1-2周 |
| 通用YOLO模型 | 无法识别SKU | 训练专用模型 | 2-3周 |

### 🟡 P1业务缺失

| 缺失功能 | 端 | 影响 |
|----------|------|------|
| 退款入口 | 消费者端 | 用户无法自助退款 |
| 提现功能 | 商户端 | 商户无法提现 |
| 发票管理 | 商户端 | 商户无法获取发票 |
| 员工管理 | 商户端 | 无法管理多员工 |

### 🟢 P2-P3问题

- 6个前端Bug（4个已修复，2个待处理）
- 4个后端Bug（1个已修复，3个待验证）
- 10+个UI/按钮问题

---

## 快速导航

### 按角色查看
| 角色 | 推荐阅读顺序 |
|------|--------------|
| **项目经理** | GO_LIVE_EXECUTION_PLAN.md → CODE_FIX_CHECKLIST.md |
| **后端开发** | ANALYSIS_BACKEND_SERVICES.md → ANALYSIS_HARDWARE_INTEGRATION.md |
| **前端开发** | ANALYSIS_ADMIN_VUE.md → ANALYSIS_CONSUMER_MP.md → ANALYSIS_MERCHANT_MP.md |
| **测试工程师** | CODE_FIX_CHECKLIST.md → 各端验证清单 |
| **运维工程师** | ANALYSIS_HARDWARE_INTEGRATION.md → 生产部署配置 |

### 按阶段查看
| 阶段 | 推荐文档 |
|------|----------|
| **当前状态分析** | 各ANALYSIS_*.md文档 |
| **问题识别** | CODE_FIX_CHECKLIST.md |
| **上线规划** | GO_LIVE_EXECUTION_PLAN.md |
| **硬件对接** | ANALYSIS_HARDWARE_INTEGRATION.md |

---

## 上线时间线

`
Week 1-2:  营业执照申请、支付商户号申请
Week 3-4:  硬件协议对接、真实柜机联调
Week 5-6:  SKU模型训练、识别准确率验证
Week 7-8:  灰度部署（5-10台柜机）
Week 9-10: 正式上线
`

---

## 立即可执行项

### 📋 行政事项
- [ ] 启动营业执照注册流程
- [ ] 联系硬件厂商获取协议文档
- [ ] 准备微信小程序注册材料

### 💻 技术事项
- [ ] 申请微信支付测试商户号
- [ ] 申请支付宝沙箱账号
- [ ] 开始商品图片采集（每SKU 100+张）

### 🧹 清理事项
- [ ] 清理测试数据
- [ ] 移除/隐藏Mock相关UI
- [ ] 配置生产环境参数

---

## 联系与反馈

如有问题或建议，请联系项目负责人或在项目群内讨论。

---

**创建日期**: 2026-07-17
**文档版本**: v1.0
**下次更新**: 灰度测试阶段
