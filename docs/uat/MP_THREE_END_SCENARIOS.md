# 三端业务测试场景真源（价值链 + 页矩阵）

> **叙事主轴（A）**：测前清数 → **台子（必要条件）** → **主价值链** → **旁路业务链**。  
> **附录（B）**：页/API 覆盖矩阵 + 原 P0/P1/P2 ID，供自动化扫页与对账。  
> **脊骨脚本**：[`scripts/e2e-full-flow-milk.ps1`](../../scripts/e2e-full-flow-milk.ps1)（采购→入库→补货→购物→结算→分账）。  
> **清数**：[`scripts/cleanup-test-data.ps1`](../../scripts/cleanup-test-data.ps1)（**测前必跑**，防脏会话/争议干扰）。  
> **闸门**：`scripts/mp-seed-gate.ps1`（`-CleanupFirst` / `-SeedMinimal`）。  
> **权威**：mp-weixin；禁 H5 冒充 PASS。软写默认；硬写须显式开关。  
> **日期**：2026-09-27 · 结构版 A+B

---

## 0. 总流程（必须按序）

```text
S0 测前清数（防干扰）
  → S1 台子就绪（组织/商户/路线/仓/柜/货道/账号）
    → S2 主价值链（采购入库 → … → 分账结算）
      → S3 旁路链（充值退款 / 营销券 / 运维 …）
        → 附录：页矩阵扫漏
```

| 阶段 | 不过会怎样 | 自动化 |
|------|------------|--------|
| **S0 清数** | 柜被占、争议堆积、余额冻结 → 假红/假绿 | **每轮硬前置**；失败 = BLOCK |
| **S1 台子** | 主链无法解释失败根因 | 台子缺一项 = BLOCK，禁止进 S2 |
| **S2 主链** | 钱货闭环未证明 | 节点 **P0 全覆盖**；L3 对数 |
| **S3 旁路** | 增长/运维能力未覆盖 | **P1 抽样**；可挂主链结果上 |
| **附录页矩阵** | 漏页 | 扫 L1；不替代价值链 |

**优先级（挂在链节点上，不是按页散排）**

| 级 | 用在哪 |
|----|--------|
| **P0** | S0 清数、S1 台子关键项、S2 主链每个节点 |
| **P1** | S3 旁路；台子增强项（线长钱包、团队） |
| **P2** | 帮助/条款/导出壳 |

端：`C` 消费者 mp · `M` 商户 mp · `A` Admin。柜机/商户编号均**运行时解析或系统发号**（12 位数字）；**禁止**写死 `CAB-*` / `MCH-*` / 任意具体号。

分层：L1 壳 / L2 有数 / L3 三端（金额差 ≤1 分）。

---

## S0 · 测前清数（防干扰）【P0 · BLOCK 门闩】

> **原则**：开跑前可把**业务交易数据清干净**，再从主链重新造数；台子主数据保留。  
> **禁止**：跳过清数直接宣称主链 PASS；**禁止**写死柜机号（见下「柜机动态」）。

| ID | 动作 | 工具 | 验收 |
|----|------|------|------|
| S0-01 | 取消**全部柜**阻塞会话 | `Clear-E2eDeviceBlockingSessions -AllDevices` | 无进行中会话 |
| S0-02 | 关闭 OPEN 争议 / 异常 | API waive/resolve | OPEN = 0 |
| S0-03 | **FullBusiness** 删业务账 | `cleanup-test-data.ps1`（默认） | 订单/会话/分账/充值/补货任务/采购出库/钱包流水 = 0 |
| S0-04 | 恢复演示消费者余额基线 | `-RestoreBalanceCents`（默认 50000） | `balanceCents` = 基线 |
| S0-05 | 解析本轮柜机（不写死） | `Resolve-E2eTestDevice` / `E2E_DEVICE_ID` | `.tmp/mp-cleanup-meta.json` 写出 deviceId |
| S0-06 | DryRun / 轻量模式 | `-DryRun` · `-Light` | 预览或不做 Full 删除 |

```powershell
# 推荐：全量清业务数据 + 动态选柜 + 台子检查
powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst

# 指定柜（可选）；不指定则按 ONLINE/库存/商户评分挑选（库中不应再有 CAB-001）
powershell -File scripts/cleanup-test-data.ps1
$env:E2E_DEVICE_ID = 'your-device-id'

# 仅清阻塞、不删历史订单（旧行为）
powershell -File scripts/cleanup-test-data.ps1 -Light
```

**清数边界**

| 清（FullBusiness 默认 = **WipePlatform**） | 保留 |
|---------------------------------------------|------|
| 会话/订单/支付/争议/异常/流水/事件 | **登录账号 + RBAC**（S1-08 仍可登） |
| **柜机、商户、SKU、仓、供应商、库存** | — |
| **补货路线、券定义、对账批次、数据一致性记录、公告** | — |
| **全部用户余额归零**（默认不回填 50000） | — |
| 清前 **停止 device-simulator**（防固定柜号心跳再登记） | 测主链时再 `docker start` |

> S0 之后库应接近空台子：`devices=0 merchants=0 skus=0 warehouses=0 routes=0 coupons=0 recon=0 consistency=0 balances=0`。  
> 仓库页「基础 1 / 采购 5」是**分组内列表个数**，不是数据条数（列表空时仍显示）。  
> **严格空台**：Wipe 后**禁止重启 trade**（`DemoDataBootstrap` mock 启动会回种默认商户/柜）；清前停 device-simulator（见 lessons #218）。  
> 旧行为：`cleanup-test-data.ps1 -KeepPlatform -RestoreBalanceCents 50000`。


**柜机动态**：场景与闸门**不得**写死柜号。挑选：`-DeviceId` → `E2E_DEVICE_ID` → DB 评分（ONLINE → DEPLOYED → 库存 → 商户）；无柜时 `DemoDataService` 走 `DeviceIdService.allocateRandomDeviceId()`。

---

## S1 · 台子（必要条件）【缺则 BLOCK】

不过台子，禁止解释「购物失败 / 分账为 0」。

```text
组织/点位 → 商户+分账规则 → 线长/路线(若有) → 仓库+供应商
  → 柜机投放+货道 SKU+价格 → 运营/商户/消费者账号权限
```

| ID | 节点 | 端 | 最低验收 | 挂载场景 ID | 脚本/入口 |
|----|------|----|----------|-------------|-----------|
| S1-01 | 组织与点位可读 | A | 演示点位存在 | — | Admin 组织/点位 |
| S1-02 | 商户主数据 + 可分账 | A·M | 默认演示商户；分账规则生效 | P0-M-06/07 | Admin 商户；M 钱包 |
| S1-03 | 线长/路线（若产品启用） | A | 有则可见；无则 SKIP | P1-M-05 | Admin 线长；M 线长钱包 |
| S1-04 | 仓库 + 供应商 | A | 运行时解析/系统发号（勿写死 WH/SUP-DEMO） | — | `e2e-full-flow-milk` 前置 |
| S1-05 | 柜机投放 + 测时非销售锁死 | A·M | **本轮解析出的 deviceId** 在库/列表；可开门 | P0-M-03/04 | `Resolve-E2eTestDevice`；解锁 `sales_locked` |
| S1-06 | 货道绑定 SKU + 可售库存 | A·M | 至少 1 货道有演示 SKU | P0-M-04 | 库存健康 / slots |
| S1-07 | 点位价格可读 | M·A | 单价与货道一致 | P1-M-03 | M 定价（软写） |
| S1-08 | 三端账号可登录 | C·M·A | `13800138000` / `13800138001` / `13900000001` | P0-C-01 · P0-M-01 | 闸门登录检查 |
| S1-09 | 商户团队/角色（增强） | M | 列表可达 | P1-M-04 | 软写 |

**台子检查出口**：写入 `.tmp/mp-seed-gate.json`；全绿才允许 S2。

---

## S2 · 主价值链（采购入库 → 分账结算）【P0 全覆盖】

> 对齐 `e2e-full-flow-milk.ps1` 步骤顺序。每节点：**造数/动作 → 三端可见 → 金额差 ≤1 分（L3）**。

```text
采购审批收货 → 仓内库存 → 出库/补货计划 → 商户补货入柜
  → 消费者开门购物 → 识别结算出单 →（退款/争议旁路可插入）
  → 分账入账 → 商户钱包/结算对账 → Admin 对账抽样
```

| 序 | ID | 节点 | 端 | 最低验收 | 挂载 ID | 脚本 |
|----|-----|------|----|----------|---------|------|
| 1 | S2-01 | 采购下单 + 审批 + 收货 | A | PO → RECEIVED；仓批次增加 | — | full-flow `procurement` |
| 2 | S2-02 | 仓储出库 / 补货计划 | A | 出库单可发运；计划挂柜 | P0-M-05 | full-flow · `e2e-replenishment` |
| 3 | S2-03 | 商户补货：签到→开门→完成 | M·A | 任务完成；货道库存↑ | P0-M-05 | 软写默认；硬写显式开关 |
| 4 | S2-04 | 消费者开门（演示柜） | C | 会话建立；可软取消 | P0-C-02 | `e2e-shopping` |
| 5 | S2-05 | 结算出单（余额/微信等） | C·A·M | 同 orderId；状态可读 | P0-C-03/04 · P0-M-08 | shopping · three-end |
| 6 | S2-06 | 购物视频 | C·M | 可播或诚实失败 | P0-C-05 | — |
| 7 | S2-07 | 分账入账 | A·M | 分账明细/流水与订单关联 | P0-M-07 | full-flow `finance` |
| 8 | S2-08 | 商户钱包余额变化 | M·A | 入账后余额↑；与 Admin ≤1 分 | P0-M-06 | backlog #2 |
| 9 | S2-09 | 结算对账 / Admin 对账抽样 | M·A | 有批次或诚实空；主链单可追 | P0-M-10 | full-flow recon |
| 10 | S2-10 | 工作台 KPI 反映本轮 | M·A | 待办/营收非「脏历史独奏」 | P0-M-02 | 清数后对照 |

**主链插入点（仍算 P0，可插在 S2-05 后）**

| ID | 节点 | 挂载 | 脚本 |
|----|------|------|------|
| S2-R1 | 按行部分退款 + 库存回补 | P0-C-10 | `e2e-partial-refund-line` |
| S2-R2 | 争议 KEEP/WAIVE/CONFIRM 三端 | P0-C-11 · P0-M-09 · P0-X-01 | `e2e-three-end` · `e2e-demo-smoke` |
| S2-R3 | 资金安全（余额不足/幂等） | P0-X-02 | `e2e-fund-safety` |

**推荐一键（API 主链，含清数）**

```powershell
powershell -File scripts/e2e-full-flow-milk.ps1
# 从补货续跑： -FromStep replenishment
# 跳过清数（不推荐）： -SkipCleanup
```

**UI 自动化顺序（在 API 主链绿之后）**  
S0 → S1 闸门 → 抽 S2-05/06/07/08 三端截图对数 → 再补 S2-03/04 软写 UI。

---

## S3 · 旁路业务链【P1 抽样】

每条仍是「前置 → 动作 → 结果」，可挂在主链订单/用户上，**不替代 S2**。

### S3-A 消费者资金旁路

| ID | 链 | 挂载 | 备注 |
|----|-----|------|------|
| S3-A1 | 充值下单 → 到账 → Admin 充值管理 | P0-C-07 | 默认软写；DONE 抽样 |
| S3-A2 | 余额明细流水 | P0-C-06 | L3 用户余额 |
| S3-A3 | 申请退余额 | P0-C-08 | 软写 |
| S3-A4 | 优先支付方式切换 | P0-C-09 | 可改回 |
| S3-A5 | 开通支付 / 账单结果 | P0-C-12 | L1 |
| S3-A6 | 开票（有入口） | P0-C-13 | 无则 SKIP |

### S3-B 增长营销

| ID | 链 | 挂载 |
|----|-----|------|
| S3-B1 | 发券/领券 → 下单抵扣 | P1-C-01/04 |
| S3-B2 | 会员 / 积分 / 兑换 | P1-C-02/03 |
| S3-B3 | 消息 / 公告 | P1-C-05/06 · P1-M-06 |

### S3-C 商户运营旁路

| ID | 链 | 挂载 |
|----|-----|------|
| S3-C1 | 要货申请 | P1-M-01 |
| S3-C2 | 经营分析 | P1-M-02 |
| S3-C3 | 点位改价（确认→取消） | P1-M-03 |
| S3-C4 | 团队成员 | P1-M-04 |
| S3-C5 | 提现申请（确认→取消） | P0-M-06 写路径 |
| S3-C6 | 货道差异 / 临期 | P1-M-07 |

### S3-D 客服与运维壳

| ID | 链 | 挂载 |
|----|-----|------|
| S3-D1 | 故障报修 / 反馈 | P1-C-07/08 |
| S3-D2 | Live 购物车 | P1-C-09 |
| S3-D3 | 帮助 / 条款 / 导出 | P2-* |

---

## 4. 执行清单（给人看的）

| 轮次 | 做什么 | 命令 / 入口 |
|------|--------|-------------|
| 1 | **清数** | `mp-seed-gate.ps1 -CleanupFirst` 或 `cleanup-test-data.ps1` |
| 2 | 台子检查 | 闸门 `pass=true` 且含 device/账号 |
| 3 | API 主链 | `e2e-full-flow-milk.ps1` |
| 4 | UI 主链抽样 | mp automator + Admin Playwright：订单/钱包/分账/补货 |
| 5 | 旁路抽样 | S3-A/B/C 按需 |
| 6 | 页矩阵扫漏 | 附录 A，L1 |

---

## 附录 A · 页面覆盖矩阵（自动化扫页）

### A.1 消费者

| path | 链节点 | 原 ID |
|------|--------|-------|
| `login` | S1-08 | P0-C-01 |
| `index` | S2-04 | P0-C-02 |
| `orders` / `order-detail` | S2-05 | P0-C-03/04 |
| `video` | S2-06 | P0-C-05 |
| `dispute/detail` | S2-R2 | P0-C-11 |
| `mine` / `balance` | S3-A2 | P0-C-06/09 |
| `recharge` | S3-A1/A3 | P0-C-07/08 |
| `verify` / `result` | S3-A5 | P0-C-12 |
| `marketing` / `member` / `points*` / `coupons` | S3-B* | P1-C-01..04 |
| `messages` / `announcements*` | S3-B3 | P1-C-05/06 |
| `report` / `feedback` | S3-D1 | P1-C-07/08 |
| `help` / `policy` | S3-D3 | P2-C-01/02 |

### A.2 商户

| path | 链节点 | 原 ID |
|------|--------|-------|
| `login` | S1-08 | P0-M-01 |
| `home` / `alerts` | S2-10 | P0-M-02 |
| `devices` / `device-detail` | S1-05/06 | P0-M-03/04 |
| `replenishment` | S2-03 | P0-M-05 |
| `orders*` / `video` | S2-05/06 | P0-M-08 |
| `wallet` / `splits` / `settlements` | S2-07..09 | P0-M-06/07/10 |
| `disputes` | S2-R2 | P0-M-09 |
| `request` / `business` / `pricing` / `team` | S3-C* | P1-M-01..04 |
| `line-wallet` | S1-03 | P1-M-05 |
| `messages` / `announcements` / `mine` | S3-B3 | P1-M-06 |
| `policy` | S3-D3 | P2-M-01 |

---

## 附录 B · 原 P0/P1/P2 明细（兼容旧引用）

执行顺序以正文 **S0→S3** 为准；下列 ID 供用例命名与 backlog 对照。

### B.1 P0（25）— 资金与履约

| ID | 场景 | 链锚点 | 端 | 页面 | 关键脚本 |
|----|------|--------|----|------|----------|
| P0-C-01 | 登录进首页 | S1-08 | C | login→index | e2e-consumer-mp-flow |
| P0-C-02 | 扫码开门 | S2-04 | C | index | e2e-shopping |
| P0-C-03 | 结算出单三端 | S2-05 | C·A·M | orders | shopping · three-end |
| P0-C-04 | 订单详情金额 | S2-05 | C | order-detail | 上同 |
| P0-C-05 | 购物视频 | S2-06 | C | video | — |
| P0-C-06 | 余额 ↔ Admin | S3-A2 | C·A | mine/balance | backlog #1/#5 |
| P0-C-07 | 充值记录 | S3-A1 | C·A | recharge | marketing-recharge |
| P0-C-08 | 余额退款申请 | S3-A3 | C·A | recharge | backlog #4 |
| P0-C-09 | 优先支付方式 | S3-A4 | C | mine | — |
| P0-C-10 | 自助退款 | S2-R1 | C·A | order-detail | partial-refund* |
| P0-C-11 | 账单申诉 | S2-R2 | C·A·M | dispute | dispute-recognition |
| P0-C-12 | 开通支付/结果 | S3-A5 | C | verify/result | — |
| P0-C-13 | 开票 | S3-A6 | C·A | 订单开票 | backlog #12 |
| P0-M-01 | 商户登录 | S1-08 | M | login | — |
| P0-M-02 | 工作台/待办 | S2-10 | M·A | home/alerts | — |
| P0-M-03 | 柜机列表 | S1-05 | M | devices | — |
| P0-M-04 | 柜机详情/库存 | S1-06 | M | device-detail | — |
| P0-M-05 | 补货签到→完成 | S2-03 | M·A | replenishment | e2e-replenishment |
| P0-M-06 | 钱包 ↔ Admin | S2-08 | M·A | wallet | backlog #2 |
| P0-M-07 | 分账明细 | S2-07 | M·A | splits | backlog #3 |
| P0-M-08 | 柜机订单同单 | S2-05 | M·C·A | orders | three-end |
| P0-M-09 | 商户争议 | S2-R2 | M·A | disputes | three-end |
| P0-M-10 | 结算对账 | S2-09 | M | settlements | — |
| P0-X-01 | 三端争议结案 | S2-R2 | C·M·A | 各争议入口 | three-end · demo-smoke |
| P0-X-02 | 资金安全 | S2-R3 | C·A | API | fund-safety |

### B.2 P1（16）— 旁路抽样

| ID | 场景 | 链锚点 |
|----|------|--------|
| P1-C-01..08 | 活动/会员/积分/券/消息/公告/报修/反馈 | S3-B / S3-D |
| P1-C-09 | Live 购物车 | S3-D2 |
| P1-M-01..07 | 要货/分析/定价/团队/线长钱包/消息/货道告警 | S3-C / S1-03 |
| P1-A-01 | Admin 对照抽样 | S2 / S3 |

### B.3 P2（5）— 壳

| ID | 场景 | 链锚点 |
|----|------|--------|
| P2-C-01/02 · P2-M-01/02 · P2-X-01 | 帮助/条款/导出/配置失败提示 | S3-D3 |

---

## 附录 C · e2e 脚本 ↔ 链节点

| 脚本 | 链节点 |
|------|--------|
| `cleanup-test-data.ps1` | **S0** |
| `e2e-full-flow-milk.ps1` | S0→S2 脊骨 |
| `e2e-replenishment.ps1` · `e2e-checkin-contract.ps1` | S2-02/03 |
| `e2e-shopping.ps1` | S2-04/05 |
| `e2e-three-end.ps1` · `e2e-demo-smoke.ps1` | S2-05 · S2-R2 |
| `e2e-partial-refund*.ps1` · `e2e-refund-restore-compare.ps1` | S2-R1 |
| `e2e-fund-safety.ps1` | S2-R3 |
| `e2e-dispute-recognition.ps1` | S2-R2 |
| `e2e-consumer-marketing-recharge.ps1` | S3-A1 · S3-B1 |
| `e2e-live-cart.ps1` | S3-D2 |
| `e2e-inventory-inout-refund.ps1` | S2-01 外围 |
| `mp-seed-gate.ps1` | S0 可选 + S1 检查 |

---

## 附录 D · 闸门与自动化铁律

```powershell
# 测前 FullBusiness 清数 + 动态选柜 + 台子检查（推荐）
powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst

# 清数 + 最小造购物单（会写演示账；柜机用解析结果）
powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst -SeedMinimal

# 指定柜（可选）
powershell -File scripts/mp-seed-gate.ps1 -CleanupFirst -DeviceId <id>
```

闸门绿条件：trade UP · **device_resolve** · 三端登录 · 柜在 `device_info` · 消费者余额可读 · 商户钱包可读 ·（`-CleanupFirst` 清数 exit 0）· Seed 后订单≥1。

产物：`.tmp/mp-seed-gate.json` · `.tmp/mp-cleanup-meta.json`（含本轮 `deviceId`）。

写自动化时：

1. 用例名带链 ID（如 `s2-05-order-paid`）或兼容旧 `p0-c-03`。  
2. **柜机从闸门 JSON / `E2E_DEVICE_ID` 读取，禁止常量写死。**  
3. mp = DevTools automator；Admin = Playwright。  
4. 切端：`cli close` → `open` → `auto --auto-port 9420`。  
5. 首页截图 wait≥3.5s（lessons #211）。  
6. 禁止默认硬充值/硬提现/硬 complete。  
7. **未过 S0+S1 禁止宣称 S2 L3 PASS**。

---

## 统计

| 块 | 数量 |
|----|------|
| S0 清数 | 6 |
| S1 台子 | 9 |
| S2 主链 + 插入 | 10 + 3 |
| S3 旁路 | 约 20 |
| 附录原 P0/P1/P2 | 46（兼容） |

下一会话：先 `-CleanupFirst` 闸门绿 → `e2e-full-flow-milk` → 再按 S2 节点写 UI 自动化。
