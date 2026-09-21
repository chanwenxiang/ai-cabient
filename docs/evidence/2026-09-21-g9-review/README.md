# G9 用户主动解约端点 · 独立复核（复核者：本会话，非实现者）

> 批次：2026-09-21 22:2x ｜ 复核对象：工作区里一份**未提交**的 G9 实现。

## 0. 复核起因与归属声明

按项目纪律「不采信文档状态、任何自报状态都会过期」，做 #60 G9 前先取工作区现状，发现：

- **实现已存在但未提交、不在 `HEAD`、不在当日日志**：

  | 文件 | 状态 |
  |---|---|
  | `services/trade-service/.../api/AccountController.java` | ` M`（+13） |
  | `services/trade-service/.../service/AccountService.java` | ` M`（+19） |
  | `services/trade-service/.../service/PayScoreService.java` | ` M`（+69） |
  | `clients/consumer-mp/src/pages/mine/mine.vue` | ` M`（+61） |
  | `clients/consumer-mp/src/utils/consumer-api.ts` | ` M`（+9） |
  | `services/trade-service/.../test/.../PayScoreContractCancelTest.java` | **未跟踪（新）** |

  磁盘 mtime `20:04–20:21`；`git log` / `.workbuddy/memory/2026-09-21.md`（末尾记「#60 G9 待做」）**均无记录**。
  同时刻另有一并发会话在写 `clients/admin-vue`（22:13 仍在写）。

- ⇒ **本包只做「独立实跑验证」，不改一行实现、不提交**。归属留给用户拍板。

## 1. 判据一：从零编译（先删产物，杜绝陈旧假绿）

`.tmp/run-g9-verify.ps1`（脚本先 `Remove-Item` 掉 `target/surefire-reports` 与 `target/classes`，再 `mvn clean`）：

```
D:\devTools\apache-maven-3.9.11\bin\mvn.cmd -B \
  -pl services/trade-service -am clean test \
  -Dtest=PayScoreContractCancelTest -Dsurefire.failIfNoSpecifiedTests=false
→ EXITCODE=0
→ [INFO] BUILD SUCCESS
→ services/trade-service/target/classes/.../PayScoreService$ContractCancelResult.class  EXISTS
```

⚠️ 第一次跑用错了 `--console=plain`（那是 Gradle 的写法，Maven 不认）⇒ Maven 只打用法帮助、`exit=1`，
而**上一次会话遗留的 3 份 `TEST-*.xml` 还在**（`CompetitiveGapConcurrencyTest` / `CompetitiveGapSalesReportTest` /
`PayScoreContractCancelTest`）——**差点把陈旧产物当成本次结果**。这正是「产物存在 ≠ 本次跑过」。

## 2. 判据二：`<testcase>` 元素个数（不看 `Tests run:` 行）

```
TEST-com.aicabinet.trade.service.PayScoreContractCancelTest.xml  testcase=5  failure=0  error=0
```

报告目录**只有这一个类**（陈旧报告已被脚本删掉）⇒ 5 例全部来自本次运行。
用例覆盖：清双合约 + 偏好回落、幂等（不写库）、非 mock **fail-closed 且零写副作用**、清 `PENDING:` 支付宝协议、偏好非被解约渠道时不动。

## 3. 判据三：契约路径对得上

- 后端：`AccountController.java:22` `@RequestMapping("/api/v2/account")` ＋ `:101` `@PostMapping("/pay-contract/unsign")`
- 前端：`clients/consumer-mp/src/utils/consumer-api.ts` → `'/api/v2/account/pay-contract/unsign'`
- E2E 脚本：`.tmp/e2e-g9.mjs`（打临时容器 `127.0.0.1:19099`，链：签约 → 切 WECHAT 偏好 → 解约 → 再解约会幂等 → 重取账户证明落库）

三者一致。

## 4. 判据四：全量门禁链

```
NODE_OPTIONS="--require=$PWD/.tmp/tools/nopipe.cjs" node scripts/run-audit-gates.mjs
→ 34 个门禁，失败 1
→ 唯一失败：check:line-endings
   - w/crlf  clients/admin-vue/public/__mock-sw.js
```

🔴 失败项**与 G9 无关**：`__mock-sw.js` 是并发会话新建的**未跟踪**临时 Mock SW（文件首行自述「临时验收用…之后删除」）。
G9 改动的 6 个文件全部通过行尾门禁。

## 5. 结论

| 项 | 结论 |
|---|---|
| 能否编译 | ✅ 从零 clean 编译通过 |
| 单测 | ✅ 5/5，`<testcase>` 元素级取证 |
| 契约一致性 | ✅ 后端 / 前端 / E2E 三处一致 |
| 是否踩门禁 | ✅ 未踩（唯一红点属并发会话的临时文件） |
| **真机 / 真实商户号验证** | ❌ **未做、也做不了**（本机无 emulator，见 §6；商户号未申请） |
| **是否由我提交** | ❌ **没有，也不建议**——归属未定（不在日志），且工作区含并发会话成果 |

> **2026-09-21 22:43 补：用户已拍板选 B** —— 本会话**不接手、不提交**该实现，只保留本复核包；
> 收口（`packages/shared-types/src/generated/` 重生成 + 文档 + 提交）由原实现会话负责。
> 本包自此为**只读存档**，不应再有改动。

## 6. 附：本机真机 / emulator 能力实测（同日取证）

| 探测 | 结果 |
|---|---|
| `adb` | 仅微信开发者工具自带 `D:\devTools\微信web开发者工具\bin\adb-win\adb.exe`（1.0.39，2017 版） |
| `adb devices -l` | **空设备列表** |
| Android SDK | `%LOCALAPPDATA%\Temp\aicabinet-android-sdk`（最小装：`platforms/android-34`、`build-tools/34.0.0`、`platform-tools`、`licenses`） |
| `emulator` / `system-images` / `cmdline-tools` | **全部缺失**；全盘 `find /c /d -name emulator.exe` **零命中** |
| AVD | `~/.android/avd` **不存在** |
| 虚拟化（WHPX / Hyper-V） | 查询需提权，**无法确认** |

⇒ **emulator 不可行**（需另下 ~3GB 组件且虚拟化前提未知）；**真机需用户物理接入设备**（本机无从代劳）。
且该端的真实闭环还依赖**真实串口锁具**（`ChzhSerialPort`）与 `trade-service` 可达，emulator 天生缺前者。
