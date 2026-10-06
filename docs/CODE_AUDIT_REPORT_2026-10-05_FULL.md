# AI Cabinet 全仓代码审计报告（2026-10-05 晚 · 全量重审）

> **基线**：`18c5c02b`（`dev`，与 `origin/dev` 同步，工作区干净）
> **方法**：5 个分区并行独立读码（后端资金/会话、设备信任边界、admin-vue、双小程序、基建/边缘/视觉/CI），**主审对全部 P0 逐条复核到行**。
> **与昨日报告的关系**：`CODE_AUDIT_REPORT_2026-10-05.md`（基线 `3650a295`）**不作为本报告依据**，仅在 §7 做状态对照。昨天的自报状态一律只当线索。

---

## 0. 结论摘要

| 级别 | 数量 | 核心 |
|------|------|------|
| **P0** | **4** | 柜机可回显服务器任意文件（P0-1）；fleet 密钥硬编进每个 APK + 无鉴权开门端点（P0-2）；识别结果可伪造驱动扣款（P0-3）；**端口门禁只查 12/32 条、生产绑公网不报红（P0-6，主审负向验证实证）** |
| **P0-4** | — | 预授权冲抵**三种场景全部**违反记账不变量（混合记 140、全额记 200、无预授权记 200）。**仍是真 P0**，但**修法已更正**（见 §1，初版给的改法会改出更糟的账） |
| **P1** | 17 | 部分退款跨锁窗口、事务内抢锁拖垮连接池、APK 无签名+OTA 可 MITM、明文传输、离线队列无界、模拟器 `/close` 无鉴权、ps1 BOM 铁律违反、`docker.sock` 读写挂载等 |
| **P2** | 21 | 分账费率静默归零、在途无幂等、**结算事务内扣款后失败（原 P0-5，已降级）**、mybatis 门禁假绿窗口、admin 金额展示 12 处分叉等 |
| **P3** | 16 | 死代码、时区依赖、死 stub 端点、可维护性 |

**一句话**：CI 门禁体系的诚实度**高于一般项目**（门禁全部有真实退出码、fail-closed 一致、本次 4 个关键门禁负向验证全部按预期变红）；真正的风险集中在**两处门禁管不到的地方** —— 资金记账的一处变量赋值错误（P0-4），和柜机↔云端信任边界的设计（fleet 共享密钥，P0-2/P0-3）。
**唯一例外是 P0-6**：连门禁本身都有一处可被绕过的解析缺陷，使「端口全绑回环」这个结论只覆盖了 1/3 的实际端口。

### 本报告自身的更正记录（透明留痕）

本报告初稿有 3 处事实错误，经外部复核 + 主审自行复算后更正，**均已在对应条目内标注 🔧**：

| 条目 | 初版错误 | 更正后 |
|---|---|---|
| **P0-5** | 判 P0「用户白扣款」 | **降级 P2**。`BalanceLedgerService.change:46-47` 的 `txTemplate` 是默认 `REQUIRED`（非 `REQUIRES_NEW`）⇒ 余额扣减随外层事务一并回滚，**无白扣款**。真实后果 = 整笔结算白做需人工重试 + 长事务持连接 |
| **P0-4** | 修法写成「`:386` 改 `capturedCents()`」 | **修法错误**。验算：那样改混合场景从 140 变 **160**，比现状更糟。正确修法 = 删 `:394-400` 记账块 + 终段改记 `chargeAmount − captured` |
| **P0-4** | 只写混合场景（记 140） | **全额冲抵主路径记 200、无预授权场景记 200**，三种场景**全部**违反不变量 ⇒ 不是边缘 case，每次余额扣款都命中 |
| **P2 admin** | `yuanText`「被 0 次引用」 | **不实**。`utils/charts.ts:3,9` 正在用；自建 `yuan()` 实为 **12 处**（初版写 9 处） |
| **P2-13** | 判旧报告「误判 + 修复只换了崩法 + 归因错层次」 | **撤回「误判」与「归因错层次」**（§7.2.1）。修复者已在 `docker-compose.staging.yml:56-58` 明写「这是诚实姿态」并在提交 `a47ef8d7` 说明 —— **我未读该注释就下结论**。仅维持「staging 按默认仍起不来」这一事实，并改判为**待拍板的产品取舍** |

> **教训**：P0-4 的错误来自「只推演了一个场景就下结论」；P0-5 的错误来自「看到 `@Transactional` 就假设扣款独立提交」，没查 `txTemplate` 的传播行为；P2-13 的错误来自「只读代码逻辑，没读同批修复留下的注释与提交信息」。**资金路径上，断言传播行为前必须看 `txTemplate` 配置，不看注解；判断「是否有意设计」前必须先找注释与 commit message。**

> **§7 是对旧报告 `CODE_AUDIT_REPORT_2026-10-05.md`（基线 `3650a295`）全部 55 条的逐条复核结论** —— 旧报告属实，但有 4 条夸大、2 条修复被架空、若干修得不彻底。改代码前请连 §6、§7 一起看。



---

## 1. P0 — 需立即处置

### P0-1 柜机可把 `videoUri` 写成 `file://`，消费者端点回显 trade-service 进程任意文件

**证据**（主审已逐行复核）：

`SessionDoorService.java:95-97` 写入时**无任何 scheme / 归属校验**：
```java
if (event.videoUri() != null && !event.videoUri().isBlank()) {
    session.setVideoUri(event.videoUri());
}
```

对照：视频直传入口**有**严格校验 —— `SessionService.java:284`：
```java
if (minioVideoService == null || !minioVideoService.isPlatformObjectUri(request.videoUri())) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "videoUri 必须为本平台对象存储路径（minio://）");
}
```

消费侧 `MinioVideoService.streamTo` **接受 `file://` 并直接读盘**，且**无根目录约束**：
- `MinioVideoService.java:303-305` → `streamLocalFile(...)`
- `MinioVideoService.java:370-372`：
```java
Path path = Paths.get(URI.create(videoUri));
if (!Files.isRegularFile(path)) { throw ... 404 ... }
```
只有 `isRegularFile` 一个判断 —— `/proc/self/environ` 也是普通文件。

**攻击路径**（攻击者只需一台合法柜机 + 一个普通消费者账号）：
1. 自己柜机正常开门产生 session；
2. 用该柜机 MQTT 凭据向 `cabinet/{deviceId}/evt` 发 `type=DOOR, doorState=CLOSED, sessionId=<自己的>, videoUri="file:///proc/self/environ"`（`SessionDoorService.java:91` 的 deviceId 绑定挡不住 —— 攻击者用自己柜机）；转发链 `MqttEventListener.java:299,339`；
3. 结算生成订单后，用**自己的**消费者 token 调 `GET /api/v2/orders/{orderId}/video`（`OrderController.java:71-78` → `OrderService.java:98-102`），响应体即 trade-service 进程环境变量：`INTERNAL_API_KEY`、`JWT_SECRET`、`SPRING_DATASOURCE_PASSWORD`、微信商户密钥。

拿到 `JWT_SECRET` + `INTERNAL_API_KEY` ⇒ 管理员 + 内部 API 全权。商户侧同源（`MerchantFinanceService.java:147-151`）。

**修法（二选一，建议都做）**：
- 写入侧对齐 `SessionService.java:284`：`SessionDoorService.java:95` 改为仅接受 `isPlatformObjectUri` 为真；
- 读取侧 `streamLocalFile` 加根目录约束（只允许配置的 video 根目录内），并对 `file://` 整体禁用。

---

### P0-2 fleet 共享 `INTERNAL_API_KEY` 硬编进**每一个** release APK，配合无鉴权开门端点 = 远程开任意柜机门

**证据**（主审已复核）：`edge/android-app/app/build.gradle.kts:21,32`
```kotlin
buildConfigField("String", "MQTT_PASSWORD", "\"dev-mqtt-device-pass\"")
buildConfigField("String", "INTERNAL_API_KEY", "\"dev-internal-key-change-me\"")
```
`productFlavors`（同文件 `:38-48`）**只覆盖 `USE_MOCK_DRIVER`**，未覆盖任何密钥 ⇒ `device`（真机）release 包同样内嵌 fleet 共享内部密钥。柜机运行时确实当 fleet key 用：`OtaChecker.kt:47`、`upload/TradeVideoClient.kt:45,87`。

而开门端点除该 key 外**无任何绑定** —— `DeviceCommandService.java:24-29`：
```java
public String openDoor(String deviceId, String sessionId, Long userId, boolean operatorMode) {
    assertDeviceRegistered(deviceId);      // 只校验「设备存在」
    mqttPublisher.publishOpenDoor(deviceId, sessionId, userId, operatorMode);
```

**攻击路径**：解包任一真机 APK → 取 `X-Internal-Api-Key` → `POST /internal/v1/devices/{任意已注册 deviceId}/open-door`（body 的 `sessionId/userId` 任意填）→ `MqttCommandPublisher.java:66-83` 发 QoS1 OPEN_DOOR → 受害柜机 `MqttDeviceClient.kt:242` 直接开门。deviceId 在运营台/设备列表可见，攻击者无需任何其他秘密。`ops-command`（`:44-54`）同理可发 `UNLOCK/REBOOT`。

**这是本项目信任关系的根问题**：fleet 共享密钥 = 全 fleet 共享权限。P0-3 是它的直接延伸。

---

### P0-3 `edge-results` 的「deviceId 绑定」在 fleet 共享 key 下不构成任何越权防护

**证据**：`VisionResultIngestService.java:73-79`
```java
// 审计 P1-5：识别结果必须来自会话所属柜机 ... fleet 共享内部 key 一旦泄露，
// 缺这层绑定时持 key 者可为任意 RECOGNIZING 会话伪造结果（多算=多扣顾客钱）
if (isBlank(request.deviceId()) || !before.getDeviceId().equals(request.deviceId().trim())) {
    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ApiMessages.DEVICE_MISMATCH);
}
```

**这条校验本身是正确的**（昨天的 P1-5 修复已落地），但代码注释自己写明了前提「fleet 共享内部 key 一旦泄露」—— 而 P0-2 证明这把 key **就在每个 APK 里**。`request.deviceId()` 由调用方自称，与调用方身份**无任何绑定**：持 fleet key 者填 `deviceId=X` + `sessionId=<X 柜任意 RECOGNIZING 会话>` 即通过。

后续 `toResult()`（`:156-177`）原样采纳 `items[].skuId/quantity`，`modelVersion` 只做「非空 + 长度」检查（`:135-143`），不含 `mock/fallback` 即通过 `SettlementService.blocksSilentSettle`（`SettlementService.java:103-108`），最终 `completeAsyncRecognition` → `SessionSettleService.java:171-176` 直接 `finalizeOrder` 扣款。

**得到什么**：任意 SKU × 任意数量 × 受害者会话 ⇒ 顾客被多扣（`needReview=false` 时不进人工审核）。

**唯一根治方向**：把「deviceId 自称」换成「调用方身份派生」—— 设备级独立凭据 + `deviceId` 与凭据查库绑定，或 mTLS 证书 subject。在 fleet 共享 key 架构下，加再多字段校验都是徒劳。

---

### P0-4 预授权冲抵 + 余额补扣双重记账，`netCompletedCents` 虚高 ⇒ 退款超额、重复扣款护栏失效

**证据**（主审已复核 `OrderPaymentService.java:374-414` 全文）：

```java
int chargeAmount = order.getTotalAmountCents() - netCompletedCents(order.getOrderId());   // :376
if (chargeAmount <= 0) { return; }                                                        // :377
int remainDebit = chargeAmount;
int capturedViaPreauth = 0;
if (session != null) {
    var captureResult = consumerPreauthService.captureForCharge(session, chargeAmount, order.getOrderId());
    remainDebit = captureResult.remainDebitCents();
    capturedViaPreauth = Math.max(0, chargeAmount - captureResult.capturedCents());       // :386  ← BUG
    ...
}
if (capturedViaPreauth > 0 && remainDebit > 0) {
    recordOperation(order, CHARGE, capturedViaPreauth, ..., preauthChargeKey, ...);        // :397
}
if (remainDebit > 0) {
    balanceLedgerService.change(order.getUserId(), -remainDebit, CHARGE, ..., idemKey, ...); // :402
}
```

`:386` 的 `capturedViaPreauth = chargeAmount - captured` 得到的是**未冲抵的余量**，与变量名、以及 `:410` 注释「CHARGE 行记本次实收（净额），与 PREAUTH_CAPTURE 行合计=应付」所表达的意图**完全相反**。设计意图应为 `captured`（已冲抵额）。

> 🔧 **修正记录（2026-10-05 22:40，经外部复核 + 主审自行复算确认）**：本条初版有三处错误，已更正 —— ①场景**不只混合一种**，**全额冲抵主路径（captured=100, remain=0）记 200，比混合场景更严重**；②初版给的修法 `:386 → capturedCents()` **是错的**（混合场景会从 140 变 160，比现状更糟）；③初版遗漏 `remainDebit == 0` 时 `:411` 也会记一笔 `chargeAmount`，全额场景是**双重记账**。

**记账不变量验算**（主审写脚本枚举三场景，验「`PREAUTH_CAPTURE + CHARGE 行 ≡ 应付`」）：

| 场景 | 应付 | 现状 | 初版提的修法 | 正确修法 |
|---|---|---|---|---|
| 混合：冲抵 60 + 余额补扣 40 | 100 | **140 ✗** | 160 ✗ | 100 ✓ |
| **全额冲抵：captured=100, remain=0** | 100 | **200 ✗** | 200 ✗ | 100 ✓ |
| 无预授权：全余额扣 100 | 100 | **200 ✗** | 100 ✓ | 100 ✓ |

**三场景全部违反不变量** —— 也就是说这条不是边缘 case，是**每次走余额扣款都会命中**。机制：
- `captureForCharge` 内部已落 `PREAUTH_CAPTURE` = `captured`（`ConsumerPreauthService.java:264` 真实扣余额）；
- `:386` 错把 `chargeAmount − captured`（= remain，本该由 `:402` 余额行承担）**又记一笔 CHARGE**；
- `remainDebit == 0` 时走 `:409-413`，用 `chargeAmount`（=100）**再记一笔** ⇒ 100 + 100 = **200**。

**后果**：
1. 真实扣款金额**正确**（冲抵多少扣多少 + 余额补扣），但 `netCompletedCents`（`PaymentOperationMapper.java:119-131`，`CHARGE`/`ADJUST_CHARGE`/`PREAUTH_CAPTURE` 三分支全计入）**虚高 1~2 倍**；
2. `UnpaidOrderService.java:314` `if (alreadyPaidCents >= order.getTotalAmountCents())` 判虚高值 ≥ 应付 ⇒ 补缴时**直接收口不扣款**，用户欠款被放过；
3. `:376` 下次重试 `chargeAmount = 应付 − 净额` 为负 ⇒ 直接 return，**掩盖真实欠款**；
4. `SettlementWaiveRefundService.java:98` `amount = netCompletedCents` ⇒ 免单退款**按虚高额退**，凭空多退。

**旁证**：`grep "CHARGE:PREAUTH"` 在 `src/test/` 下**零命中** —— 此路径无测试覆盖，这就是它活到今天的原因。

**修法（已更正）**：
1. **删掉 `:394-400` 整个记账块**（`if (capturedViaPreauth > 0 && remainDebit > 0)`）—— `PREAUTH_CAPTURE` 已覆盖冲抵部分、`:402` 余额行记 `remainDebit`，不需要第三笔；
2. **终段 `:409-413` 改记 `chargeAmount − capturedCents`**（该分支只在 `remain == 0` 时进入，此时差额应为 0）；
3. **补断言测试覆盖上表三场景**，每场景断言 `netCompletedCents(orderId) == order.getTotalAmountCents()`。

⚠️ **不要只改 `:386`** —— 那样混合场景会从 140 变 160，比现状更糟（已验算）。


---

### P0-6 `check-compose-ports.mjs` 遇注释即关闭 ports 块 ⇒ 32 条端口映射只检查 12 条（不可见 20 条中 19 条属真·端口映射），**生产端口门禁形同虚设**

> **本条是主审在复核旧报告时新发现的，且已做负向验证实证。**

**证据**：`scripts/check-compose-ports.mjs:35-40`
```js
if (/^\s*ports:\s*$/.test(line)) { inPorts = true; continue; }
if (inPorts && !/^\s*-/.test(line)) inPorts = false;   // ← 注释行也匹配这个「非 - 开头」
if (!inPorts) continue;
```
YAML 允许 `- 127.0.0.1:8080:8080` 之间夹注释行。门禁遇到**任何非 `-` 开头的行**（含 `#` 注释）就把整个 `ports:` 块标记为已结束，块内后续所有端口项**全部不再检查**。

**实测盲区**（探针复刻门禁解析逻辑，统计全部 11 个 compose）：
```
compose 端口映射项总数 : 32
门禁实际能看见       : 12
门禁完全看不见       : 20（真·端口映射 19）
```
看不见的 20 条里包含**全部关键对外端口**：`docker-compose.yml:20` Postgres、`:50-52` EMQX MQTT/TLS/Dashboard、`:91-92` MinIO、`:138` Prometheus；`docker-compose.production.yml:87` 生产 EMQX Dashboard；`docker-compose.apps.yml:60/154/193` trade/device/vision；`full.yml:42-44`、`win-ports.yml` 全部。

**负向验证（实证盲区，不是推理）**：
```
# 把生产 EMQX Dashboard 从 127.0.0.1 改成 0.0.0.0 后跑门禁：
[check-compose-ports] OK：12 条端口映射全部绑回环或在豁免清单（11 个 compose 文件）
门禁退出码: 0        ==>  注入 0.0.0.0 仍绿  ==>  盲区确认
[已还原] docker-compose.production.yml 恢复原状
```
即：**把生产 EMQX 管理后台绑到公网，门禁不会响。** 而旧报告 P2-11 修复所加的正是 `${EMQX_MQTT_BIND:-127.0.0.1}` 这套「绑定地址走 env」的设计 —— 改成 `0.0.0.0` 只需改 env，**恰好落进门禁盲区**。旧报告与两批修复均未发现此问题。

**修法**：块关闭条件应改为「遇到非 `-`、非注释、非空行才关闭」：
```js
const isItem = /^\s*-\s/.test(line);
const isCommentOrBlank = /^\s*(#.*)?$/.test(line);
if (inPorts && !isItem && !isCommentOrBlank) inPorts = false;
```
并补一条门禁自测：构造「`ports:` + 注释 + 非回环端口」用例，必须 exit 1。⚠️ 修完这个门禁**会立刻变红**（20 条此前未检查的端口里若有非回环项），需先跑一遍确认 —— 这正是「修复使真问题浮现」的正常现象，不是修复失败。

---

### P0-5 结算事务内扣款后续步骤失败 —— **降级为 P2（不构成白扣款）**

> 🔧 **降级修正（2026-10-05 22:40，外部复核后主审自行复算确认）**：本条初版判为 P0「用户白扣款」，**该后果不成立**。`BalanceLedgerService.change`（`:46-47`）用的 `txTemplate` 是**默认 `REQUIRED` 传播**，合入外层结算事务 ⇒ `recordSplit` 抛异常时**余额扣减与流水一并回滚**，不存在「钱扣了订单没了」。本条降为 P2（失败可重试 + 长事务持连接）。

**原始证据（仍成立的部分）**：`SettlementOrderFinalizeService.java:97-112`
```java
if (!tryChargeSettledOrder(session, order)) { return orderSupport.toDto(order); }
orderRepository.save(order);
if (appliedCoupon != null) { couponService.markUsed(...); }
revenueSplitService.recordSplit(order);
```
由 `SettlementSettleOrchestrator.processRecognitionAfterVision`（`:80` `@Transactional`）包裹。`markUsed` 的 try 只包了 `memberService`（`:115-117`），`couponService.markUsed` 与 `revenueSplitService.recordSplit` **无任何 try**。

**为什么「白扣款」不成立**（主审复核证据）：
- `BalanceLedgerService.change:46-47`：`runWithBalanceLock(userId, () -> txTemplate.execute(tx -> doChange(...)))` —— `txTemplate` **未设 `PROPAGATION_REQUIRES_NEW`**，默认 `REQUIRED` ⇒ 加入 `processRecognitionAfterVision` 的事务；
- `SessionSettleService.settleSession:137-149` 的 `catch (RuntimeException)` 只做「状态迁移 + 告警 + 返回 DTO」，**不重抛** ⇒ 异常被吞，Spring 按「事务已标记 rollback-only」在方法出口**回滚**；
- 回滚范围包含 `user_account.balance_cents`、`payment_operation`（CHARGE 流水）、`cabinet_order`、订单行、分账表 ⇒ **状态一致，无残留**。

**真实残留问题（P2 级）**：
1. **整笔结算白做**：识别已成功、扣款逻辑已跑通，因一个 DB 抖动导致整单回滚，会话置 `FAILED`、商品已离柜 ⇒ 需人工重试 `resetSessionForRetry`（`SessionService:508`，需 `ops:exception:handle` 权限）；
2. **长事务持连接**：结算事务内含 `recordSplit`（多次 DB 写）+ 渠道 HTTP，与 P1-2 的「事务内抢锁」是同类问题；
3. **与渠道路径不对称**：`OrderPaymentService.recordChargePending:280` 已用 `REQUIRES_NEW` 做「痕迹先行」，余额路径无同等保护 —— 渠道侧能保留痕迹供补偿，余额侧不能。

**建议修法（不紧急）**：给 `recordSplit` / `markUsed` 加独立 try（记 ops 告警但不毒化主事务），或在 `processRecognitionAfterVision` 之前先落「结算痕迹行」。**这属于健壮性优化，不是资金安全漏洞。**

---


## 2. P1 — 高危（17 项，摘要）

### 后端资金/会话
| # | 问题 | 证据 |
|---|------|------|
| P1-1 | `partialRefund` 三段非原子：`planPartialRefundCents`(`readOnly`, 无锁) → `refundOrder`(内部才加锁) → `preparePartialRefund`(无幂等键)。并发同额退款第二次会 400，但**第一次退款已到账**且订单已改小 ⇒ UI 报错与实际相反 | `SettlementPartialRefundService.java:67-71,76-78`；`DisputeService.java:296` |
| P1-2 | `processRecognitionResult` 在 `@Transactional` **内**抢分布式锁（`tryLock(60,5)`）⇒ 3 请求各挂一个 DB 连接等 5 秒，10 并发即耗尽 HikariCP。**与 `BalanceLedgerService.java:40-44` 的设计注释直接矛盾**（该处正确用了编程式 `txTemplate`） | `SettlementSettleOrchestrator.java:110-118` |
| P1-3 | `OpsRiskAdminService:38-39` / `OpsCommercialFacade:88` 分页 `size` 无上限（全仓其它分页入口都有 `Math.min(size,100)`）⇒ `size=MAX_VALUE` 可拉全表 OOM | `OpsRiskController.java:38` |
| P1-4 | `RevenueSplitService` 商户缺失时费率静默归零（3 处 `merchant != null ? rate : 0`）⇒ 平台抽成变 0，`clawbackWalletDelta` 按全额退钱包。**与首次记账路径「非 ACTIVE 直接 return 不记账」口径不一致** | `RevenueSplitService.java:249,287,476` vs `:96` |
| P1-5 | `InTransitService.recordFromOutbound` 是 public `@Transactional`，**无幂等键无唯一约束**。当前仅一处调用方且有行锁守卫（`:669-670`），故当前不可触发 | `InTransitService.java:68-75` |

### 设备/边缘/视觉
| # | 问题 | 证据 |
|---|------|------|
| P1-6 | Android release **无 `signingConfigs`** + OTA 只验 SHA-256（**且 checksum 来自明文 HTTP 同一响应体**）⇒ 局域网 MITM 获得柜机代码执行。配合 `usesCleartextTraffic="true"`、无 network security config | `app/build.gradle.kts`（grep 无命中）；`OtaChecker.kt:63,71,165,203`；`AndroidManifest.xml:19` |
| P1-7 | 内部 API key 与 MinIO 预签名 PUT 全程**明文 HTTP**。生产 compose 已把 MQTT 切 8883 TLS，但端侧 cleartext 全开 ⇒ **降级不被阻断** | `AndroidManifest.xml:19`；`build.gradle.kts:31` |
| P1-8 | 生产 Kafka **无 SASL/ACL**（`PLAINTEXT://0.0.0.0:9092`）+ `VisionRecognitionListener.java:67-68` 把 `modelVersion` **硬编码 null 丢弃** ⇒ `blocksSilentSettle` 对 null 返回 false（`SettlementService.java:104`）⇒ 能连 9092 的人发一条消息即可伪造识别结果静默扣款，**完全绕过精度闸** | `docker-compose.apps.yml:237,244`；`VisionRecognitionListener.java:67-68` |
| P1-9 | `OfflineUploadQueue.enqueue()` **无 maxItems / maxBytes / 淘汰**（`OutboundMqttQueue` 有）。文件仅在上传成功后删除 ⇒ 长期断网 ⇒ 队列 JSON 与柜机视频文件**双增**，可达「磁盘打满 → 录像写失败 → 门磁发不出」 | `OfflineUploadQueue.kt:72-87,100,110` |
| P1-10 | `device-simulator` 的 `/close` **无鉴权且实际绑 0.0.0.0**（`new InetSocketAddress(port)` 传 port），而日志打印 `http://127.0.0.1:{port}/` ⇒ **日志与实际绑定不符**，误导运维 | `DeviceSimulator.java:406,409,416` |

### 基建/CI/脚本
| # | 问题 | 证据 |
|---|------|------|
| P1-11 | `infra/docker-compose.devops.yml:81` **读写挂载 `docker.sock`**（无 `:ro`）+ `:64` classic PAT `repo` 作用域。self-hosted runner 的定位就是执行不受信 PR 代码 ⇒ **宿主 root 等价**。同仓 `docker-compose.observability.yml:49` 是 `:ro`（正确），两种做法 | `docker-compose.devops.yml:64,81` |
| P1-12 | `check-admin-endpoints.mjs:14` 用 `path.resolve('clients/admin-vue/src')` **依赖 CWD**（同目录其它 42 个脚本都用 `fileURLToPath` 自锚定）。从 `scripts/` 跑直接 ENOENT 崩溃 ⇒ 任何以子目录 CWD 调用它的封装会得到与被审计内容无关的红/绿 | `scripts/check-admin-endpoints.mjs:14` |
| P1-13 | **ps1 BOM 铁律 66/81 违反**（45 个「无 BOM 且含中文」）⇒ PS 5.1 按 ANSI 解析全乱码。含 `deploy-production.ps1`、`verify-production-readiness.ps1`、`gen-emqx-auth-bootstrap.ps1`、约 18 个 `e2e-*.ps1`、`docker-up.ps1`。机制原因：`.editorconfig:3` 只写 `charset = utf-8`（**不带 bom**），`.gitattributes` 无 ps1/BOM 规则，全仓唯一提及 BOM 的 `check-line-endings.mjs` 不涉及 BOM ⇒ **该铁律 100% 靠人记，零自动化** | `git ls-files '*.ps1'` 统计 |
| P1-14 | 生产栈镜像**未钉版本**：`minio/minio:latest`、`minio/mc:latest`，以及 `nginx:alpine`/`redis:7-alpine`/`postgres:16-alpine`/`mysql:8.0`/`python:3.12-slim` 等浮动 tag。postgres/redis/mysql 是**有状态服务** ⇒ 某次 pull 即静默改变生产行为，无 git diff 痕迹。现有门禁**不校验镜像版本** | `docker-compose.yml:84` 等多处 |

### 双小程序
| # | 问题 | 证据 |
|---|------|------|
| P1-15 | `SubmitReplenishmentLinesRequest` 的 `@Valid` **空挂**（record 无任何约束注解）⇒ 非 RESTOCK 路径（`persistSubmittedTaskLine`）直接 `dto.quantity()` 入库，**无数量校验**。对比：要货路径**有**校验（`MerchantReplenishmentService.java:337` 拒 ≤0）⇒ 两路径强度不一致 | `SubmitReplenishmentLinesRequest.java:5`；`MerchantPortalController.java:475`；`ReplenishmentService.java:1057` |
| P1-16 | 生产 mock 充值单点取决于后端 `aicabinet.security.mock-enabled`。前端 `resolveMockEnabled` 生产恒 false（只挡 UI 入口），**不挡已登录用户直接打 URL**。**需确认生产 profile 显式设 false 而非依赖 profile 不激活**（`DevMockRechargeController` 是 `@ConditionalOnProperty`） | `recharge.ts:269,298`；`DevMockRechargeController.java:20` |
| P1-17 | 补货实收数量上限**仅前端钳制**，后端 RESTOCK 行走「自动截断 + warn」不报错。不会超卖（可接受），但 `:1041` `if (qty <= 0) return;` 是**静默丢弃该行**而非拒绝 | `useReplenishmentFulfillment.ts:281`；`ReplenishmentService.java:1041,1062` |

---

## 3. P2 — 中危（21 项，摘要）

**后端**：佣金 job 长事务包 N×7 次锁（`LineCommissionJob.java:58-59`，失败全回滚）｜**结算事务内扣款后后续步骤失败（原 P0-5，已降级，见 §1 P0-5 节的完整证据）**｜~~`SiteRentBillService.allocate` `fixedCents` 无上限校验~~ **已定案并已改造（2026-10-06）**：原判「叠加总额超 base」为缺陷，据此改为「先扣 `Σfixed` → 剩余 `(base − Σfixed)` 按份额分 + 补 `Σfixed ≤ base` 校验」，不变式改为 **`Σ账单金额恒等于 base`**。
- **产品依据**：叠加是产品明示意图（`OrgSitesView.vue:575`「按各方份额拆分合同月费，**并可叠加固定金额**」）。
- **改造依据（本条已剔除错误来源）**：🔴 初版写的「业界通行做法是先扣后分（支付分账要求总额恒等、百分比租金的固定部分就是 base 本身）」是**跨域类比，不成立** —— ①支付分账的「总额恒等于订单金额」是**支付通道**规则，与**场地租金**不同域；②旧系统 easygo（同产品线上一代，实测在 `D:\ideaCode\easygo`）**没有场地租金分摊功能**（`grep 场地租金|房租|rentFee|siteRent` 零业务命中；`BillService.java:423-435` 仅「销售额−退款=利润」单层账单）⇒ 本产品线无先例；③同业柜机运营方（友宝/丰宜/哈哈零兽）公开资料只有「**销售额**分成比例」（15%–35% / 20%–25% / 20%–30%），**无「份额+固定额」双层结构** ⇒ 无同行先例支持「必须先扣后分」。
- **保留改造的真正理由**：总额恒等于 base 时**不必逐单核对合同附加费条款**（改动前 Σ账单可超 base 90% 且无第二道校验），少一类资损来源。口径选择依据是**产品语义与资损风险**，不是「业界主流」这四个字。
- **迁移说明**：改动前两表均 0 行、无历史账单 ⇒ 无需数据迁移；若后续已用旧口径出过账，改动即升级为数据迁移问题。｜`OpsSessionOrderQueryService:571,581` 吞异常致 `paidAt` 静默回退到下单时间，**对账场景危险**｜`WarehouseMonthlyCloseMath:90-92` 归零在循环内（同一循环先减后加会少算）

**设备/边缘**：`aicabinet-device` 共享账号 ACL 用 `${clientid}` 命名空间（`aicabinet-acl.conf:16-17`）且被编进 APK 默认值 ⇒ 持共享账号者可自选 clientId 订阅他柜 cmd｜`DoorEventDeduplicator` 去重键含设备可控 `eventSeq`（`MqttEventListener.java:314-323`）⇒ 每次改值即绕过 60s 去重（资金侧有状态机兜底，但会刷告警）｜`rejectDeviceIdMismatch:202-209` 在 topic 无法解析时 `unknown` 走放行（**当前 ACL 下不可达**，ACL 一改宽即水平越权）｜`EdgeRuntimeConfig:159-174` Keystore 加密失败**静默回落明文**，且 `allowBackup="true"` 允许 adb 导出

**视觉服务**：`VISION_TRUST_PROXY` 是布尔开关而非可信代理网段（`main.py:81,86-96`）无条件信任 XFF 最左值，**与 Java 侧正确实现不对称**｜`VISION_ALLOWED_CIDRS` 全部非法时静默降级为「不限制」（`main.py:79-83`，仅 warning）｜上传只限体积，**不校验 content-type / 魔数**，且把用户文件名当 `source_uri` 回写（`main.py:313,324`）

**admin 前端**：金额展示 **12 处自建 `yuan()`**（`BigScreenView:574`、`BalanceRefundView:80`、`FundBillView:504`、`InvoiceListView:80`、`LineManagerView:874`、`MerchantWithdrawView:663`、`MarketingRoiView:240`、`MemberLevelsView:390`、`UserAnalysisView:340`、`CouponsView:536`、`PromotionsView:335`、`SalesReportsView:392`），分叉成多个语义变体（`Number(x)||0` vs `(x||0)` 的 `NaN` 行为不同；`'暂无'` vs `yuanText` 默认 `'未统计'`）。> 🔧 **初版说 `yuanText`「被 0 次引用」是错的** —— `utils/charts.ts:3,9` 正在用它做图表轴标签，是**除图表外的视图**绕过它自建。`MemberLevelsView:390` **不除 100 是正确的**（`MemberLevelRuleDto.minSpent` 是 `BigDecimal` 元，已核实）｜`OrgSitesView:317,385` 与 `ExceptionListView:484` 金额除法**无 `||0` 守卫**（当前 DTO 是 `int` 必为数字，故不可触发）｜`CrudTable.vue:200` 与 `:620` 对「谁是主键」取值顺序**相反**（`:620` 已亲验为 `table.rowKey ?? props.rowKey`；当前 30+ 调用方均只传其一，不触发）｜`ops:warehouse:list` 一权守 4 条路由 + `/print`｜`toLocaleString` 4 处无 `timeZone`

**小程序**：商户登录页**明文持久化手机号**（`login.vue:198`，密码侧已正确清理，手机号是唯一残留 PII）｜`isMerchantCookieAuth`（H5）纯本地布尔可伪造（前端守卫非安全边界，H5 已定口径不修）｜`isDevBuild` 不可绕过（编译期常量），但 `build:mp-weixin` 无产物断言｜`privacy-consent` **不拦网络请求**（`request.ts:164` 不查该标志）

**CI/脚本**：`check-mybatis-null-clear.mjs:64` 只看 `set(null)` 后 **4 行**且 `MITIGATION:38` 匹配到任意 `set(...,null)` 就 break ⇒ **明确假绿路径**｜`check-android-tests-wired` 移走 15/16 个测试文件仍绿（无「测试数不得低于基线」下限；CI 的 `<testcase>` 计数兜底）｜`e2e-lib.ps1:25-30` `Exit-E2eLock` 只按路径删除 ⇒ TOCTOU（A 删掉 B 刚拿到的锁）；异常路径未 try/finally ⇒ 锁泄漏｜`check-compose-ports` ALLOWLIST 可无声滥加豁免（只改一行代码，无人复核）｜`check-admin-table-gate` 基线已漂移 2 个文件未收紧，且文件重命名会假红｜模拟器与真端 `doorEvent` payload **两套独立实现**，MQTT 门禁只对账 topic/type 不对账字段集 ⇒ 联调可产生假绿

---

## 4. P3 — 低危（16 项，摘要）

`OrderPaymentService.java:864` / `BalanceLedgerService.trim:247` `substring(0,128)` 按 UTF-16 code unit 截断会切碎 surrogate pair｜`OrderPaymentService:875-880` `reasonKey()` 死代码｜`SettlementPartialRefundMath:102` int 乘法溢出（库存约束下不可达）｜`admin-table-gate` 不在 preflight 任何档（只在校验、推前不预检）｜vision `/api/v2/vision/tasks/{id}` 与 `/recognize/async` 是 stub，**全仓无调用方**（降级为记录；建议删或响应加 `"stub": true`）｜`MqttEventListener:71` 每次重启换 clientId + `cleanSession=false` ⇒ broker 侧永久会话堆积｜真机远程 LOCK/UNLOCK/REBOOT **实际不生效**（`MqttDeviceClient.kt:224-247` 只处理 OPEN_DOOR，其余静默丢弃 —— 模拟器有处理）｜release 签名配置完全缺失（只能出 debug APK）｜`EdgeRuntimeConfig:161` 加密失败静默降级明文｜`OutboundMqttQueue.kt:33` 全 critical 时 `dropIndex ?: 0` 会丢第 0 条关键信令（有告警兜底，可能是有意取舍）

---

## 5. 假绿审计结论（CI 门禁）

**接线**：`check:audit-gates` 聚合 **43** 个门禁，CI 挂 `ci.yml:517`；`run-audit-gates.mjs:43-58` 逐个 `spawnSync` 并以 `failed===0?0:1` 汇总，**不吞错**。另有 6 个不在链中、由 build job 单独跑（`check-migration-safety`/`check-flyway-seed-separation` 及其 `.test`、`check-admin-table-gate`、`check-admin-bundle-budget`）—— 这个分层**合理**（前 4 个依赖 `MIGRATION_BASE_REF`，进链会拿错基线）。

**负向验证实测**（主审复核，注入错误 → 是否变红）：

| 门禁 | 注入方式 | 结果 |
|------|---------|------|
| `check-compose-ports` | 删 observability 的 `127.0.0.1:` 前缀 | ✅ exit 1 |
| `check-menu-permissions` | 改 `menu.ts:30` 为 `zz:ghost:perm` | ✅ exit 1 |
| `check-edge-cloud-mqtt-contract` | 改 `MqttDeviceClient.kt:110` topic | ✅ exit 1 |
| `check-migration-safety` | git 不可用 | ✅ exit 1（**主动 fail-closed**，不静默放行） |
| `check-line-endings` | git 不可用 | ✅ exit 1（同上） |
| `check-admin-endpoints` | 从 `scripts/` 目录调用 | ⚠️ ENOENT 崩溃（P1-12） |

**结论：无「只 console.log 不退出」的恒真门禁。** 多个脚本内置防「零发现⇒全绿」护栏做得很好：`check-line-endings:97-109`（`MIN_SCANNED=500` / `MIN_LF_MANAGED=100` 双下限）、`check-docker-tests-guarded:194-211`、`check-mp-unit-tests-wired:127-132`、`check-edge-cloud-mqtt-contract:87-89/110/181-182`、`check-xxl-job-wiring` 8 处 `fail()` 锚点。

**`pre-push-ci-preflight.mjs` 实测真跑**（`--quick` → `format:check ✓` → `line-endings` 失败 → `REAL_EXIT=1`，fail-fast 生效）。**未覆盖**：`check-admin-table-gate`（任何档都不跑）、`admin-bundle-budget`、两个 `.test` 防绕过自测、OpenAPI live regen（`:182-189` 明确交给 CI）、`static/admin` 产物一致性（`:145-150` **Windows 直接跳过**）、`mvn verify`/e2e/Docker 测试。

**误报风险高的门禁**：`check-compose-ports` ALLOWLIST 硬编码 `"文件名:端口"` 二元组（改端口忘同步即假红；反向可无声滥加豁免）｜`check-admin-table-gate` 基线绑路径+计数（重命名即假红）｜`check-android-tests-wired:262-265` 硬编码 `^:?app:test<Flavor>(Debug|Release)UnitTest$`（新增 buildType 即假红）。

---

## 6. 疑似但存疑 —— **不要改**

这一节很重要：以下都长得像 bug，核实后判定**不该动**。

| 项 | 判定 |
|---|------|
| **两处 `setState` 旁路**（`AdminDeviceOpsService:115`、`OpsService:105`） | **我上一轮报错了。** 这两处是「新建实体赋初态」（`new ShoppingSession()` 后立即 save，无历史实体），`SessionState.java:5` 注释**明确豁免**此场景。`markOpsRemoteSessionFailed:131` / `markRestockOpenFailed:126` 都走 `transition` 且前置守卫排除终态。**不是状态机违规。** 但有真实次生缺陷：旁路会话**不写 `openTime`**，而 `SessionExpireService:457` 用 `openTime` 判超时 ⇒ 一旦有人放宽 `isNonConsumerSession` 过滤（`:454`）即成泄漏 |
| `SETTLING → RECOGNIZING` 回退是否无限重试漏洞 | **否。** 唯一入口 `resetSessionForRetry:508` 需人工调 + `@RequiresPermissions("ops:exception:handle")` + 幂等键 + 异常白名单；`COMPLETED` 直接 return；**且 V305 唯一索引 `uk_cabinet_order_session` 兜底** —— 即使锁租约失效，第二次撞唯一约束而非双扣 |
| `SessionSettleService` 大量 catch 返回 DTO | **是设计。** 每个 catch 都做了「状态迁移 + `opsExceptionService.report` + 日志」三件事，状态不会悬空。`:145` 的 `canTransitionTo(FAILED)` 守卫是**必要**的（`catch(RuntimeException)` 会捕获 `transition` 自己抛的异常）。真实问题只在 P0-5（事务已被毒化） |
| `BalanceLedgerService.change` 拒绝 `deltaCents == 0` | 无害。唯一可能传 0 的路径有 `:401`/`:670` 双守卫 |
| admin 权限能否绕过 | **4 条尝试全部失败**：改 `localStorage` 权限数组（`auth.ts:63` 不从 storage 初始化，只删不写）｜改 active_nav 缓存（同上）｜伪造 `admin_cookie_auth='1'`（能过 `isLoggedIn` 但无真实 Cookie ⇒ `/me` 401 ⇒ 全部带 perm 菜单拒）｜访问未登记路径（`router/index.ts:547-556` 显式 forbidden）。**路由 perm 差集量化实测**：75 条业务路由中 67 有 perm 保护、0 条 PERM_EMPTY、3 条 fail-closed（`/profile` + `recognition-demo` + `/`）、5 条 legacy redirect（**目标全部有 perm**，vue-router 在守卫前展开 redirect ⇒ 无绕过）。且 `router/index.ts` 中 `meta.perm` 出现 **0 次** —— 全部权限单点依赖 `menu.ts` |
| admin XSS | 全仓 `v-html|innerHTML|dangerously` **仅 1 处**（`AlertRuleView.vue:566-568`），数据源是外部飞书文案（可控）但 `:578-583` `escapeHtml` 转义全 5 个字符 ⇒ 不可利用。CSV 公式注入前后端**都**已中和（`csv.ts:33-38` + 后端 `CsvCells`） |
| 视频能否枚举他人 | **不能。** `OrderService.java:89` 归属不符返 **404 而非 403**（不泄露存在性）；前端已移除任意 `?url=` 深链 |
| 小程序前端门禁是否等于无后端校验 | **否，逐端点核实均为真校验**：`assertReplenishmentDeviceAccess`→`MerchantPortalController:415-421` + `requireDevicePack:199-219`（越界转 403）｜提现 `@RequiresPermissions` + `validateAmount:536-551`｜`saveTaxProfile` → `InvoiceService:180 requireMerchantScope`。**唯一例外是 P1-15 的 `@Valid` 空挂** |
| H5 分支是否污染 mp-weixin 产物 | **不污染。** 4 类模式逐一核验：纯 `withCredentials` 增强（剥离后无害）｜H5-only 实现均有 `#ifndef H5` 显式兜底（不留 undefined 空洞）｜运行时探测（`typeof window`+UA）非编译期依赖｜H5 块内赋值均为局部 `const`，无跨分支共享变量。`mock_alipay_user_id` 整段在 `#ifdef H5` 内且有 `isDevBuild` 双重拦截 |
| 生产 compose 是否有强口令检查 | **有，且 fail-closed。** `docker-compose.production.yml` 全部用 `${VAR:?set ...}` 内建必填校验；`vision-service/app/main.py:43-53` 有运行时二次校验（secure env 禁 mock、禁 dev 默认 key）。**这一条设计正确，不应报为问题。** |
| 全仓有无 root 提权配置 | **无。** `privileged`/`cap_add`/`security_opt`/`network_mode: host` 全仓 grep 为空。唯一例外是 P1-11 的 `docker.sock` |
| X-Forwarded-For 能否绕过 CIDR（Java 侧） | **不能。** `InternalApiAuthInterceptor:51-66` 只在 `remoteAddr ∈ trustedProxyCidrs` 时才解析 XFF；生产该变量默认空 ⇒ 一律用 `getRemoteAddr()`；`firstValidIp:69-74` 另做 IPv4 字面量校验。**设计是对的** |
| `presignDownloadUrl` 对 http(s) 原样返回 | 初看像 SSRF，但唯一调用方是 `OtaCdnService:24`（`downloadUrl` 由需 `ops:*` 权限的运营配置），且 `presignPlaybackUrl:94-97` 已拒绝非 MinIO scheme。**不构成 SSRF** |
| `StreamTo` 对 http(s) 的处理 | 同上，`OtaCdnService` 是唯一调用方 |
| 真实柜机异常路径会否卡死 | **不会。** `CabinetController` 异常路径完备有界：`awaitDoorOpened` 2s 上限、`sessionMutex` 全程串行、unlock 失败停录、门磁未确认停录并回 CLOSED、关门超时停录 + `uploadStatus=UPLOADING`、上传失败进离线队列并发 `LOCAL_QUEUED`。**未发现卡死路径** |
| `KeystoreCipher` 加密强度 | **是真加密**：AndroidKeyStore + AES-256/GCM/NoPadding、随机 12B IV、key 不可导出。仅「失败回落明文」是 P3 |
| `docker.sock` 挂载 | 见 P1-11，**是问题**（但仅 `devops` profile，不进生产栈） |

---

## 7. 旧报告（`CODE_AUDIT_REPORT_2026-10-05.md`）全部 55 条逐条复核

> **方法**：旧报告 55 条全部逐条回代码验证（3 个复核代理分域并行 + 主审抽验与负向验证），**不抽样**。旧报告自报证据一律重新取证，不采信。
> **结论**：**判断层面 55/55 属实**（未发现误判），**修复层面 34 条彻底、2 条被架空、6 条修得不彻底、13 条自报即待办**。

### 7.1 判定汇总

| 类别 | 条数 | 编号 |
|---|---|---|
| 判断错误/夸大 | **0** | — |
| 成立且**修得干净** | 34 | P1-1、P1-2、P1-3、P1-4、P1-7、P1-8、P2-1、P2-2、P2-4、P2-5、P2-6、P2-7、P2-11、P2-12、P2-14\*、P2-15、P2-16、P2-17、P2-18、P2-19、P2-20、P2-21、P2-22、P2-24、P3 批… |
| 成立但**修得不彻底** | **6** | P1-3(死参)、P1-6、P2-8、P2-13、P2-14、P3 设备④ |
| 成立但**修复被架构架空** | **2** | **P1-5**（见 P0-3）、**P2-3**（告警非 HIGH、无 ops 留痕） |
| 成立且**自报即待办**（可接受） | 13 | P1-9、P2-9、P2-10、P3 各条 |

### 7.2 修得不彻底 / 被架空的 8 条（改代码前必读）

| 旧编号 | 自报 | 实际 | 后果 |
|---|---|---|---|
| **P1-5** | 已修（DTO 加 deviceId + 强校验） | **被架空** —— 校验的是「自称的 deviceId」，`build.gradle.kts:32` 的 fleet 共享 key 未动、APK 未清 | 攻击者只需填**目标会话的真实 deviceId** 即可伪造识别结果。旧报告修复建议里的 ②（逐设备凭据/HMAC）③（APK release 置空）**一条没做**（提交 `9e896459` 只做了 ①）⇒ 残余风险与修复前基本同级 |
| **P2-3** | 已修（发 HIGH 告警） | **夸大** —— `SettlementConfirmDisputeService:104` 走三参 `send`，**无级别参数**；`opsExceptionService.report(...,"HIGH",...)` 全文件 grep 零命中 | 级别 HIGH 只存在于升级链配置（默认 `ESCALATION_ENABLED=false`）⇒ **只发 best-effort 聊天渠道、无 ops 留痕表**，渠道未配即静默丢失。这正是 P2-3 原判「无 ops 告警单」的字面病灶。对比 P2-4 确实用了 `"HIGH"`（`SessionExpireService:179`），做法不一致 |
| **P1-6** | 已修（bootstrap 护栏） | 报告要求三条，实际只做到「CSV 存在 + `MQTT_USERNAME` 在 CSV」 | 护栏是**事前提醒**不是**事后闭环**。被吊销的是**设备**凭据（12 位 deviceId 行），而护栏只比对 `MQTT_USERNAME` 一行 ⇒ **「吊销成功但旧 secret 仍有效」这一原始后果未闭环** |
| **P2-8** | 已修（补受管仓校验） | `WarehouseService:165-167` **仅在 `warehouseId` 为空时**校验 | 显式传 `warehouseId=<无主仓>` 仍可入库 ⇒ 「库存搁浅」原始后果保留，触发面从「默认兜底」缩到「显式指定」。**与 P2-5 修法不一致**（P2-5 连显式也拦，相邻两处宽严相反） |
| **P2-13** | 已修（改 quectel） | 🔧 **撤回「误判」指控**（见下方 7.2.1）；仅维持「staging 按默认仍起不来」 | `quectel_recognizer.py:33` 的 `available` 硬编码 `False`、无 env 开关 + staging `MOCK_ENABLED=false`（`docker-compose.staging.yml:55`）⇒ `main.py:49` 抛 RuntimeError。**但这是被显式设计的 fail-loud**，非疏漏。⇒ 待拍板：(a) 保持现状 + 文档写明正确启动方式（我倾向此方案，与 P2-20「staging 不静默落 mock」一致）；(b) 给 stub 加 env 开关可起，但削弱 fail-loud |
| **P2-14** | 已修（改拒绝式） | **残留盲区** —— `RecognitionDemoView.vue:215` 仍是 `` `${base}/api/v2/ops/recognition-preview` ``，而脚本正则要求引号紧邻 `/api/` ⇒ 模板串**不匹配、放行绿**。「拒绝式」对最常见的 `base + 路径` 形态无效 |
| **P2-15** | 已修 | 契约函数已单点化（`money-ui-contracts.ts:100-124`），但 `money-ui-contracts.test.ts` grep `LineWithdraw` **零命中** | 新契约**无单测**，而同批 P2-19 补了单测。测试覆盖缺失 |
| **P1-3** | 已修 | `StocktakeAdjustCommand.bookQty`（`:288`）已成**死参数**，仍由 `WarehouseStocktakeService:248` 传入 | 无正确性影响，纯残留 |

### 7.2.1 P2-13 撤回「误判」指控的完整说明

> 🔧 **2026-10-05 22:50**：本报告初版判 P2-13「误判 + 修复只换了崩法 + 归因错层次」。**撤回「误判」与「归因错层次」**。

**我读漏了修复者写在 `infra/docker-compose.staging.yml:56-58` 的注释原文**：
```
# X2 + 审计 P2-13：云端识别已退役、生产识别走端侧直报（/internal/v1/vision/edge-results）。
# 默认 quectel（端侧 provider 占位）：其 stub available=false 会让启动 fail-loud——
# 这是「接真前云端识别不可用」的诚实姿态；原默认 yolo 已进弃用名单，按默认起必崩且误导排障。
```
提交 `a47ef8d7` 正文亦写明「staging 识别后端默认 yolo（弃用）改 quectel」。

⇒ **两种崩法的语义差异是被显式设计并写进注释的**：yolo 崩 = 撞废弃名单、误导排障；quectel 崩 = 诚实的「云端识别不存在」信号。**不是疏漏，是我未读该注释就下结论。**

**唯一维持的部分**：`quectel_recognizer.py:33` 的 `available = False` 硬编码无 env 开关 + staging 默认 `MOCK_ENABLED=false`（`:55`）⇒ `main.py:49` 抛 RuntimeError，**staging 按默认配置起不来**。但这是 fail-loud 设计的必然结果，**属待拍板的产品取舍而非 bug**：
- (a) 保持现状 + 在 `docs/` 写明「staging 需显式设 `MOCK_ENABLED=true` 或 `VISION_FORCE_REAL=true` 才能起」——**我倾向此方案**，与 P2-20「staging 不静默落 mock」整改方向一致；
- (b) 给 quectel stub 加 `QUECTEL_STUB_AVAILABLE` env 开关让 staging 可起 —— 但会削弱 fail-loud 排障价值。

### 7.3 旧报告自报但**夸大**的表述（不是误判，是程度问题）
| 旧编号 | 自报表述 | 核实后 |
|---|---|---|
| P2-9 | 「拖库=一次性拿到全 fleet 设备 secret，S1 隔离收益被单点归零」 | **夸大**。ACL `:15` `${username}` 限定 `cabinet/<自身>/#` ⇒ 拿到 secret **不给其他柜机权限**，真实爆炸半径是「冒充一台柜机发自己的事件」。未受限的是「共享受任一柜机凭据即可横向冒充」（P3 设备④） |
| P2-23 | 「compose 栈设备接口无网关限速」 | 原报告**自我修正诚实**（已判为死配置并删除），修正后成立。原判断确属高估 |
| P3 库存⑥ | 「`InventoryOpsService` 死代码」 | **夸大**。服务活着（`OpsCommercialFacade:281,286` 等 4 处调用），死的是**分支**（`:134`/`:139` 的 `deviceUsesLotLedger` 恒假）。§7.1 证据写的是「409 闸门后 lot 分支永假」（准确），§3 摘要才夸大 |
| P3 基建 | 「E2E 弱口令 123456 分布 37 文件」 | **严重低估**。实测全仓 **193 个文件**含 `123456`（37 vs 193）。生产免疫的结论本身成立 |
| P3 库存⑤ | 「采购价兜底 `max(priceCents,1)` 虚增应付」列 P3 | **低估**。P1-8 修完后 `fillSatelliteLineCosts:729` 已**无条件**用此值 ⇒ 分仓路径拿不到采购价时会用**零售价**入库并经 `:461` → `receivedValueCents` 喂 `recordReceive`，虚增应付的通路是**真实打开的** |
| P2-10 | 位置写 `application.yml:27-28` | 轻微误导：trade 的 yml 里**没有** mqtt 段，实为 `services/device-service/src/main/resources/application.yml` |
| P1-4 / P2-7 | 行号 `:833-855` / `:668-675` | 行号偏差（基线实际 `:823-849` / `:670-676`），机理描述正确 |

### 7.4 旧报告**漏抓**的同类实例（我的审计新发现）

| # | 发现 | 证据 |
|---|---|---|
| 9.4-1 | **Kafka 入口完全绕过 deviceId 校验** —— `VisionRecognitionListener.java:52-78` 从 topic 收 payload，解析 `sessionId` 后直接 `completeAsyncRecognition`，**零 deviceId 校验**，与 HTTP `/edge-results` 落到同一结算驱动方法。当前 `vision-async.enabled=false` 不可利用；一旦启用即比 edge-results **更宽**的伪造面（无需凭据，能投 Kafka 即可）。旧报告只盘了 HTTP 端点，修复也未覆盖 | `VisionRecognitionListener.java:52-78` |
| 9.4-2 | **P2-7 的同构死锁窗口未修** —— 排序只覆盖 `doShipOutbound`；`cancelShippedDeviceLines:805-814` 回仓循环**同样无排序**，用同一个 `runWithStockLock` + `findBy...ForUpdate` ⇒ 「回仓」单 vs「发运」单行序相反时死锁窗口**依然存在** | `WarehouseService:805-814` |
| 9.4-3 | **P2-5/P2-8 治理只加在 ProcurementService 一层，下游裸奔** —— `receivePurchaseStock:197-229` 与 `returnPurchaseStock:234` 自身**不校验**受管仓（只 `findById` 存在性），完全靠上游兜住。新增第二个调用方即绕过治理 | `WarehouseService:197,234` |
| 9.4-4 | **端口门禁解析缺陷**（旧有，两批修复与旧报告均未发现）—— `check-compose-ports.mjs:39` 遇注释即关闭 `ports:` 块 ⇒ **32 条端口只查 12 条**；把生产 EMQX 绑 `0.0.0.0` 门禁仍绿。已升级为 **P0-6** 并做负向验证 | `check-compose-ports.mjs:39` |
| 9.4-5 | `handleDoorEvent:281-290` 与新 `rejectDeviceIdMismatch:202-209` 判定条件等价但**代码分叉**未复用 | 同上 |
| 9.4-6 | `check-compose-ports` 对 `${VAR:-port}` 形式**看不出是否被外部改成 0.0.0.0**（只匹配端口号做 ALLOWLIST）—— 与 9.4-4 叠加 | 同上 |

### 7.5 旧报告**已修且干净**里值得记的正面样本

修复处**全部留了引用审计编号的成因注释**，这是我今天不用重新推演判断依据的原因：

- `OrderPaymentService:955` —「包成 500 会毒化结算事务并打断『冲抵保留+差额转待支付』信号链」
- `WarehouseService:246-253` —「STOCKTAKE 流水恒自洽、巡检无法发现」⇒ 直接说明了为何旧实现查不出来
- `ProcurementService:717-731` —「该路径面向无 `ops:procurement:edit` 权限的补货员」
- `SessionExpireService:145-152` —「四个既有清扫器都不覆盖 FAILED」+ 滞后 1h 避免与人工处置抢跑
- `DisputeService:241-246` —「外层事务会让『渠道已退、本地回滚』账实漂移」

🔧 **这个做法应作为项目规范固化**：修 bug 时把「为什么这里原来会错、为什么静默」写进注释，比写「已修复」有用一个数量级。

---

## 8. 修复队列（与外部复核裁定合并后的并集）

> 本节已与另一份独立复核的修复队列**取并集**（对方 §「合并后的修复队列」6 项与本节基本一致，差异见文末）。编号沿用本报告。

**第一批（本周，只改高杠杆小改动）**
1. **P0-4 资金双记**（对方队列第 1）：`OrderPaymentService.applyBalanceCharge` 删 `:394-400` 记账块 + 终段 `:409-413` 改记 `chargeAmount − capturedCents`，补**三场景**断言测试（混合/全额/无预授权，每场景 `netCompletedCents == totalAmountCents`）。⚠️ **不要只改 `:386`**（验算证明 140→160，更糟）
2. **P0-1 file:// 回显**（队列第 2）：写入侧 `SessionDoorService:95` 对齐 `SessionService:284` 的 `isPlatformObjectUri` + 读取侧 `streamLocalFile` 禁 `file://` 或加根目录约束
3. **P0-6 门禁盲区**（队列第 3）：`check-compose-ports.mjs:39` 块关闭条件放行注释行 + 补负向自测（构造「`ports:` + 注释 + 非回环」用例必须 exit 1）+ **首次全量跑那 19 条**确认合规
4. P1-15 `SubmitReplenishmentLinesRequest` 补 `@Min(0)`（让 `@Valid` 生效）
5. P1-12 `check-admin-endpoints.mjs:14` 改自锚定路径（1 行）
6. P1-10 `DeviceSimulator.java:406` 改绑 `127.0.0.1` + 日志与实际一致
7. P1-11 `docker-compose.devops.yml:81` 改 `:ro`（1 行）
8. P1-9 `OfflineUploadQueue` 加 `maxItems`/`maxBytes` + 淘汰

**第二批（需架构决策）**
- **P0-2/P0-3 根治**（队列第 4）：fleet 共享密钥 → 设备级凭据 + deviceId 绑定；`VisionRecognitionListener` 补 deviceId 校验 + 透传 `modelVersion`；Redpanda 开 SASL
- Android：`signingConfigs` + APK release 变体不再内嵌默认 key + OTA 验签名 + 防回滚 + 关 cleartext
- **P2-13 待你拍板**（§7.2.1）：staging 识别后端保持 fail-loud（我倾向）+ 文档写明正确启动方式，**还是**给 quectel stub 加 env 开关

**第三批（旧报告「不彻底」五连 + 健壮性）**
- P1-6 护栏补设备凭据行校验（不只 `MQTT_USERNAME` 一行）
- P2-8 显式指定仓库也拦（与 P2-5 宽严对齐）
- P2-14 正则支持 `${base}/api/...` 模板串形态
- P2-15 补 `money-ui-contracts.test.ts` 的线长契约单测
- P1-3 清 `StocktakeAdjustCommand.bookQty` 死参数
- P2-3 `SettlementConfirmDisputeService` 补 ops 留痕
- `cancelShippedDeviceLines:805-814` 补锁排序｜`receivePurchaseStock`/`returnPurchaseStock` 补受管仓校验｜ps1 BOM 门禁 + 批量补 BOM｜镜像钉 digest｜`check-mybatis-null-clear` 假绿窗口｜`e2e-lib.ps1` 锁 TOCTOU｜12 处自建 `yuan()` 收敛到 `yuanText`（`MemberLevelsView` 除外，元口径）｜`recordSplit`/`markUsed` 加独立 try（原 P0-5 降级项）

**与对方队列的唯一差异**：对方第 3 项写「全量跑首次现身的 17 条」，我实测是 **19 条真·端口映射**（20 条不可见中含 1 条非端口行）。建议按 **19** 复核。

---

## 9. 核实方式说明（可复现）

- **P0-1~P0-6 全部由主审逐行复核**，含 `MinioVideoService.java:295-312,368-390`、`OrderPaymentService.java:374-414`、`build.gradle.kts:15-55`、`SessionDoorService.java:80-110` 全文。
- **P0-6 做了负向验证**（不是推理）：写探针复刻 `check-compose-ports.mjs:39` 的解析逻辑，统计出「32 条端口 / 只查 12 条」；再把生产 EMQX Dashboard 改成 `0.0.0.0` 实跑门禁 → 输出「OK：12 条全部绑回环」、exit 0。探针用完已删除，`infra/` 工作区已还原干净。
- **P1/P2/P3** 由 5 个分区探查代理给出证据（均带 `文件:行`），主审抽样复核 `RevenueSplitService`、`OtaChecker`、`e2e-lib.ps1`、`AdminDeviceOpsService:105-135` 等，未发现编造行号。
- **旧报告 55 条**由 3 个复核代理分域逐条复核（8 + 13 + 17 条，覆盖 P1/P2/P3 全部编号）+ 主审抽验；结论见 §7。
- **未核实项**（诚实标注）：生产 profile 的 `aicabinet.security.mock-enabled` 取值（P1-16 需读 `application-prod.yml`）。
- **已定案并已改造**：`SiteRentBill.fixedCents` 语义 —— 原判「叠加使总额超 base」为缺陷，2026-10-06 已改为「先扣 `Σfixed`、剩余按份额分」，不变式 `Σ账单 ≡ base`，并补 `Σfixed > base ⇒ 400`。代码注释已同步（**已剔除「对齐业界主流」这条错误依据**，改写为产品语义 + 资损风险 + 三方无先例的实证）。
- **本轮为只读审计**，未修改任何仓库文件（唯一例外是本报告本身，以及已删除的临时探针）。
