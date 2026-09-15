# 审计整改核验 · 第二轮（R2）

- **核验时间**：2026-09-15
- **核验范围**：`20b361a3` → `496802de`（**5 个新提交**）
- **核验方式**：逐条读源码 + 实跑门禁/单测/编译（**不采信文档勾选**）
- **上一轮报告**：[`audit-fix-verification-2026-09-15.md`](audit-fix-verification-2026-09-15.md)

---

## 〇、结论

| 维度 | 结论 |
|------|------|
| **响应完整度** | **7/7**。上一轮提出的 1 个 P0 + 2 个 P1 + 4 个 P2 **全部对应处理**，无遗漏 |
| **修复质量** | **优**。修法正当，非「改文档不改代码」；TLS/乐观锁/队列三处设计到位 |
| **实跑验证** | 9 门禁全绿；后端 **30 tests pass + BUILD SUCCESS** |
| **新发现** | ✅ R2 三项已跟进：删 `AppDialog`、清洗报告矛盾、stocktake 契约 Javadoc + 删便捷构造器；`PrefsJsonQueue` 改 `commit()` |

---

## 一、上一轮问题闭环核验

| 上轮问题 | 严重度 | 处理 | 验证证据 |
|----------|--------|------|----------|
| **10 个门禁 0 接入 CI** | P0 | ✅ **已修** | `package.json:26` 聚合由 5 扩至 **9 个**（新增 page-size / anti-jitter / table-align / mp-a11y）；`.github/workflows/ci.yml:234-235` `mini-programs` job 新增 `run: pnpm check:audit-gates`；触发条件含 push/PR → main/develop/dev |
| **乐观锁前端缺位** | P1 | ✅ **已闭环** | 新增 `support/OptimisticLocking.java`：null → **400** `EXPECTED_VERSION_REQUIRED`，不匹配 → **409** `OPTIMISTIC_LOCK_CONFLICT`；`MerchantSkuPricingService.java:219` 已有行强制校验；前端 `pricing.vue:307` 传 `expectedVersion`、`:318-322` 409 → 提示「他人已修改，请刷新」+ 自动 `load()` 重载；列表 `MerchantSkuPricingDto.java:20` 返回 `priceVersion`，无 spurious 冲突 |
| **E-P1-1 TLS 仅默认信任库** | P1 | ✅ **已修** | 新增 `MqttSslSocketFactories.kt`（81 行）：私有 CA truststore + mTLS keystore + `strictCustomTrust` **fail-fast**；`build.gradle.kts:20-28` 补全 `MQTT_TLS_STRICT` / `TRUST_STORE_*` / `KEY_STORE_*`（默认 `MQTT_USE_TLS=false`，向后兼容） |
| **AdminVirtualTable 死代码** | P2 | ✅ **已删** | 323 行文件已删除（Glob 确认不存在） |
| **SessionService 755 行** | P2 | ✅ **续拆** | 抽出 `SessionLiveCartService.java`（223 行）；`SessionService.java` **755 → 582 行**，`private final` 21 → 20 |
| **M-P1-4 无统一 AppSheet** | P2 | ✅ **已落地** | 新增 `AppSheet.vue`（62 行）；实测被 `ReplenishDetailSheet` / disputes / mine / pricing / team **5+ 处复用** |
| **E-P1-2 双队列未统一** | P2 | ✅ **已统一** | 新增 `PrefsJsonQueue.kt`（54 行，泛型 + `@Synchronized` + 原子 `mutate`）；`OutboundMqttQueue` / `OfflineUploadQueue` 均改用 |

---

## 二、实跑证据（本轮）

| 项目 | 命令 | 结果 |
|------|------|------|
| 9 个审计门禁 | 逐个 `node scripts/check-*.mjs` | **全部 ok**（见下） |
| 后端单测（锁 + Session + 状态机） | `mvn -pl services/trade-service -am test -Dtest=...` | **Tests run: 30, Failures: 0, Errors: 0** + **BUILD SUCCESS** |

门禁明细：

```
dialog-a11y:      ok
scheduled-zone:   ok
cache-names:      ok
admin-endpoints:  ok (63 pilot literals)
token-storage:    ok
page-size:        OK: no :page-sizes literal exceeds 50
anti-jitter:      OK
table-align:      col-text+align conflicts: 0 / bare align=center: 0 / money-label: 0 / ok
mp-a11y:          clickables with role/aria 381/381 (100%) / icons 37/37 (100%) / ok
```

后端含新增 `MerchantSkuPricingServiceTest`（3 用例：null→400、不匹配→409、一致通过）与拆分后全套 Session 测试（`SessionLiveCartTest` / `SessionServiceRecoveryTest` / `SessionOpenConcurrencyTest` / `SessionLifeConcurrencyTest` / `SessionDoorClosedIdempotencyTest` / `SessionAttachVideoSettleTest`）。

---

## 三、⚠️ 新发现（3 项，均非阻塞）

### 3.1 新引入死代码 `AppDialog.vue`（89 行）— ✅ 已删

- 全仓零引用；与 `AppConfirmDialog` 职责重叠
- **已处理**：删除 `AppDialog.vue`；文档只保留 `AppSheet` + `AppConfirmDialog`

### 3.2 报告文档自相矛盾 — ✅ 已清洗

- 删除重复 E-P1-2 行；同步 A-P1-002 / TL;DR / 小计；去掉 AppDialog 成果表述

### 3.3 `inventory/stocktake` 破坏性契约 — ✅ 已标注

- `StocktakeAdjustRequest.expectedVersion` Javadoc 标明已有行必填（400/409）
- 删除无调用方的便捷构造器（避免 `expectedVersion=null` 静默绕过）
- 当前仍无前端调用方；后续接入须带 `inventoryVersion`
---

## 四、修复质量点评

### 做得好的

1. **响应精准**：7 项一一对应，无遗漏、无敷衍
2. **修法正当**（非绕过）
   - TLS 用 `strictCustomTrust` **fail-fast**，而非静默降级 —— 正对应上一轮担心的「开了 TLS 却集体掉线难排查」场景，且给出台账级提示文案
   - 乐观锁冲突给**用户可读文案 + 自动重载**，而非吞错
   - `PrefsJsonQueue` 是**真抽象**（泛型 + 锁内原子 `mutate`），不是复制粘贴
3. **测试补齐**：`MerchantSkuPricingServiceTest` 覆盖 400 / 409 / 通过三态，非占位测试
4. **主动清理**：顺手删掉了我指出的 `AdminVirtualTable` 死代码
5. **门禁扩展而非仅接线**：把 page-size / anti-jitter / table-align / mp-a11y 一并纳入聚合，避免"接了个空的"

### 小瑕疵

1. `PrefsJsonQueue.save()` 用 `prefs.edit().putString(...).apply()`（异步落盘）。可靠性敏感的出站队列若在 `apply()` 后进程立即被杀，存在丢数据窗口；`commit()` 更稳（有阻塞代价，需权衡）
2. `AppDialog.vue` 建了没用（见 3.1）
3. `SessionService.java` 582 行 / 20 依赖，仍是最大单类，可继续拆（DTO 构造、锁编排）

---

## 五、剩余待办（按优先级）

| 优先级 | 事项 | 说明 |
|--------|------|------|
| **P0** | 两端 `manifest.json` `mp-weixin.appid` 仍为空 | 真机/发布硬阻塞（需密钥） |
| ~~P2~~ | ~~删除 `AppDialog.vue`~~ | ✅ 已删 |
| ~~P2~~ | ~~修报告文档矛盾~~ | ✅ 已清洗 |
| ~~P2~~ | ~~stocktake 契约 + 删便捷构造器~~ | ✅ Javadoc 标明；便捷构造器已删 |
| ~~P2~~ | ~~`PrefsJsonQueue` 改 `commit()`~~ | ✅ 同步落盘 |
| P2 | 巨型视图继续 composable 化 | ReplenishmentView / consumer index / WarehouseView |
| P2 | `SessionService` 582 行可再拆 | 非紧急 |

---

## 六、核验方法

- 逐条 `Read` / `Grep` 源码，**不采信** `[x]` 勾选
- 门禁与单测**实跑**（9 门禁 + 30 后端用例）
- 文档声称与代码实现**交叉比对**，标记不一致（§3.1 / §3.2 即由此发现）
- 基线 `496802de`；后续提交需重新评估
