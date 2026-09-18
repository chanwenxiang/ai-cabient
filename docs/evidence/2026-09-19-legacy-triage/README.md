# 4 项长期未决问题 · 分诊结论（2026-09-19）

> 触发：用户指令「现在重建；`e2e-replenishment.ps1` 第 3 步恒 no gaps、`edge/` 零测试资产、
> CAB-001 残留、可观测性死配置 —— 这些怎么办，需要修改吗？」
>
> 纪律：**每一条都重新取证**（不采信记忆/文档里的旧结论）；结论附「文件:行」或命令输出。
> 本文件记录**分诊判断**，不代替修复。

---

## 摘要（先看这张表）

| # | 项 | 旧口径 | **实测结论** | 要改吗 |
|---|---|---|---|---|
| 1 | `e2e-replenishment.ps1` 第 3 步恒 `no gaps` | 「本机跑不过」 | 脚本**已** fail-fast；根因是**默认柜机库存全满**，脚本**无造缺口能力** ⇒ 不可重复运行 | **要改**（真缺陷） |
| 2 | `edge/` 零测试资产 | 「22 `.kt` / 测试 0」 | 实为**两个不同性质的子项目**：23 个 `.kt` 全在 **Gradle/Android** 的 `edge/android-app`（`mvn` 不碰）；Maven 模块 `edge/device-simulator` 是 **Java**（845 行） | **部分要改** |
| 3 | `CAB-001` 残留 | 「刻意不动」 | 库里 **CAB-001 仍在**（孤儿柜）；档案头却写「已从真实环境删除」⇒ **信号在骗读者** | **不改迁移**，改记忆/文档 |
| 4 | 可观测性死配置 | 「已排除的假缺口／真缺 5 项」 | **已闭环**：草稿文件已标 `draft-unimplemented`；活规则 24 条指标名全有效、门禁守着 | **不需要改** |

---

## 1. `e2e-replenishment.ps1` 第 3 步恒 `no gaps`

### 实测
默认柜机 `330449777078` 的 8 个货道，`device_sku_lot.quantity` **与** `device_slot.max_level` **完全相等**：

| 货道 | A1 | A2 | A3 | A4 | B1 | B2 | B3 | B4 |
|---|---|---|---|---|---|---|---|---|
| quantity | 8 | 8 | 6 | 6 | 8 | 6 | 8 | 4 |
| max_level | 8 | 8 | 6 | 6 | 8 | 6 | 8 | 4 |

⇒ **库存全满** ⇒ `GET /replenishment/suggest?deviceId=…` 恒 0 条 ⇒ `POST /replenishment/plan` 必 400「当前无补货缺口」。

### 定性（纠正旧口径）
这不是「脚本跑不过」的缺陷，而是**脚本不可重复运行**：
它只做「补齐货道（`Ensure-E2eDeviceSlots`）+ 补仓库库存」，**没有任何造缺口的步骤**。
因此第一次跑通后库存被补满，**第二次必然卡住**。

脚本本身**已经修好了**——`scripts/e2e-lib.ps1:1118-1133` 是 09-18 加的 fail-fast：
不再只 `Write-Host "no gaps"` 就返回（那会把失败推迟到第 3 步、表现为看不懂的 400/500），
而是**即刻抛错**并列出三种成因 + 三条可执行处置（换柜机 / stocktake 造缺口 / apply-template）。

### 建议
**要改**。在 `Prepare-E2eReplenishmentPlan` 里补一步「确保至少一个货道有缺口」，
走正规 API：`POST /api/v2/ops/admin/devices/{deviceId}/slots/stocktake`
`{"slotCode":"<货道>","physicalQty":0,"adjustBookQty":true}`。

⚠️ **需产品/测试策略确认后再动手**：该步骤会**写真实库存数据**（每次跑清空一个货道）。
建议加 `-ForceGap` 开关显式开启，或跑完自动回补，避免默认行为改变。

---

## 2. `edge/` 零测试资产

### 实测（旧口径「22 `.kt`」已过期）
`edge/` 是**两个性质完全不同的子项目**，被一个名字盖住了：

| 子项目 | 语言 | 文件 | 构建体系 | `mvn test` 是否覆盖 |
|---|---|---|---|---|
| `edge/android-app` | Kotlin | **23 个 `.kt`** | **Gradle Kotlin DSL**（`build.gradle.kts` / `settings.gradle.kts`） | ❌ **完全不碰**（不在 Maven reactor） |
| `edge/device-simulator` | **Java** | `DeviceSimulator.java` **845 行** | **Maven**（根 `pom.xml:17` 的 module） | ✅ 会跑，但 `src/test` **为空** ⇒ 0 测试 |

两者的 `src/test` **都是空的**。

### 定性
- `edge/device-simulator`：**真实可修的缺口**。845 行 Java、Maven 落点现成、必含可测纯逻辑。
- `edge/android-app`：**范围外**。Android 项目要测需引入 Gradle + 测试基建（JUnit/Robolectric），
  且它不在任何门禁链里 ⇒ 属**工程决策**，不是顺手能补的。

### 建议
**部分要改**：
- `device-simulator` 补单元测试（低成本、高收益）→ **建议做**。
- `android-app` 是否引入 Gradle 测试基建 → **建议单独立项**，别混在本次。

---

## 3. `CAB-001` 残留

### 实测
**库里存在**（非「已删除」）：

```
device_info:  CAB-001 | CAB-001 | merchant_id = <NULL>     ← 孤儿柜（无归属商户）
device_slot:  CAB-001 有 8 个货道（A1..B4）
```

**迁移文件里的分布**：
- **仍在写入/引用**：`V25__demo_catalog_seed.sql:34-38` 仍 `INSERT` CAB-001 货道；
  `V123`、`V181:38`、`V209:1` 仍引用。
- **档案头声称「已删除」**：`V132:66`、`V133:142`、`V134:2`、`V135:2`、`V222:106,109,213,216`、`V223:32`、`V253:2`
  写的是「**该测试设备已从真实环境删除**，种子不再保留」。

### 定性
🔴 **档案头措辞与事实矛盾**（典型「信号在骗读者」，失效形态⑥）：
被移除的只是**种子语句**（`V134`/`V135` 已改成 `SELECT 1;`），
**不等于**「库里数据已删」——`V25` 当年插入的 CAB-001 记录**一直还在**，没人删过。

### 建议
**不建议现在改迁移文件。** 理由：
Flyway 的 checksum **覆盖整个文件内容（含注释）**，改注释里的一个字 ⇒
**所有已应用过旧版本的环境都必须再执行一次 `flyway repair`**
（本机刚在上批工作里对齐过 V134/V135）。**代价 > 收益。**

替代方案（按风险排序）：
1. **（推荐，零风险）** 把事实钉在 `PROJECT-REFERENCE.md`：
   「V134/V135 档案头的『已删除』= **种子语句**已移除，**不代表**库里数据已删；库里 CAB-001 仍在。」
   —— 防止下一个审计者再被档案头误导。
2. **（要动数据）** 真要清，就**新增**一个迁移删 `device_info` 的 CAB-001 + 关联行，
   而不是改老迁移。但这属产品决策（是否保留演示柜），需先确认。

---

## 4. 可观测性死配置

### 实测：**已经闭环，不需要改**

**① 草稿文件已显式标记**
`infra/monitoring/alerts.yml` 头部（1-32 行）就是 09-18 的逐条取证结论，并带标记
`# gate: draft-unimplemented`。它**不在任何 `rule_files` 里** ⇒ 从未被加载。
三份 prometheus 配置（`prometheus.yml` / `prometheus.compose.yml` / `prometheus-full.yml`）
的 `rule_files` **只引用** `/etc/prometheus/alert_rules.yml`。

**② 真正被加载的规则，指标名全部有效**
`infra/prometheus/alert_rules.yml`（24 条告警）经门禁 R2 校验通过；
`hikaricp_connections_*` 前缀正确（草稿文件写的 `hikari_` 是错的）。

**③ 门禁在链里，且有漂移验证**
`scripts/check-prometheus-metric-names.mjs`（聚合链**第 6 位**）+ `scripts/devops/verify-metric-names-drift.py`。
三条规则：R1 死配置（规则文件必须被加载，或显式标草稿）、R2 假指标名（规则）、R3 假指标名（看板/运维页）。
实测输出：`OK: 33 个注册点 → 24 个有效指标名；2 个规则文件（草稿 1 个）、1 个看板、152 个 admin 视图的引用全部可解析`。

**④ 旧记忆里「真缺 5 项」也已过期** —— 这些告警**现在都在**：

| 曾被记为「真缺」 | 现状（`alert_rules.yml`） |
|---|---|
| 关门完整率 | `DoorOpenSuccessRateLow` / `DoorOpenSuccessRateCritical` ✅ |
| 识别准确率/延迟 | `RecognitionLatencyHigh` ✅ |
| 结算时长/失败 | `SettlementFailureRateHigh` ✅ |
| 争议率 | `DisputeSessionRateHigh` ✅ |
| MQTT 转发失败 | `MqttTradeForwardFailures` / `MqttCommandAckTimeout` ✅ |

### 一条容易误判的取证陷阱（本轮亲历）
按 `cabinet_permission_denied_total` 在 `/actuator/prometheus` 里搜**搜不到**，一度以为又是死配置。
实际它在 `services/trade-service/.../metrics/CabinetMetrics.java:85` 注册为 **counter**
（`cabinet.permission.denied` → counter 自动加 `_total`）。
搜不到的原因是 **counter 从未被 `increment()` 过 ⇒ Micrometer 不导出该序列**。
⇒ **「运行时 /actuator 里没有」≠「代码里没注册」**；判死配置要看**注册点**（门禁 R2 就是这么做的）。

---

## 附：本轮附带纠正的两条过期认知

1. **「容器 `StopTimeout` 未设置」是读错字段名导致的假结论。**
   正确的是 `.Config.StopTimeout`；`.HostConfig.StopTimeout` 会抛
   `map has no entry for key "StopTimeout"`，被 `2>/dev/null` 吞掉后显示为空，看起来像「未设置」。
   实测正确值：postgres/minio/redpanda/xxl-job-mysql = **60**，其余 = **30**（与 compose 分层期望逐条一致）。
   证据：`docs/evidence/2026-09-18-compose-stop-grace/stop-grace-applied-after-rebuild.txt:46-50`。

2. **`edge/device-simulator` 是 Java 不是 Kotlin**（旧记忆写「22 `.kt`」把两个子项目混为一谈）。
