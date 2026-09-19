# P0-12 — `edge/android-app` 补 Robolectric 覆盖（含三处真实缺陷）

日期：2026-09-19　基线 HEAD（改前）：`dbe88a89`（P0-11）

> 本批是 P0-11 的接续：P0-11 把「能编译、能跑 JVM 单测」建起来了，本批把 P0-11 里
> 明确标为**未覆盖**的 `Context` / `SharedPreferences` / `AndroidKeyStore` 路径补上。
>
> ⚠️ **漂移脚本不在这里**：`ab-drift.py` 与 `gate-drift.mjs` 与 P0-11 共用，物理上位于
> `docs/evidence/2026-09-19-p11-android-tests/scripts/`（本批对其做了扩展，数量见下）。

---

## 0. 一句话结论

引入 Robolectric 后**实测**发现：`AndroidKeyStore` 在 Robolectric 下**根本不存在**，
于是 `EdgeRuntimeConfig.putSecret` 会**静默把密钥明文落盘**（回退分支）；
同时本批在**门禁自身**和**我自己的 A/B 判据**上各抓到一处「看起来很严、其实恒真」的缺陷。
最终：**10 个测试文件 / 89 条用例全绿**，A/B 注入漂移 **17/17**，门禁漂移 **10/10**，聚合链 **29 门禁 / 0 失败**。

---

## 1. 关键发现①：Robolectric 下 `AndroidKeyStore` 不可用 ⇒ 密钥静默明文回退

按纪律**先写探针实测、不猜**（`ProbeRobolectricTest`，用后即删）：

| 能力 | Robolectric 下实测 | 结论 |
|---|---|---|
| `SharedPreferences` 真读写 | ✅ 工作 | 可覆盖队列/配置持久化路径 |
| `BuildConfig` | ✅ 工作 | — |
| **`AndroidKeyStore`** | ❌ **`AndroidKeyStore not found`** | `KeystoreCipher.encrypt` 直接抛 `KeyStoreException` |

**这条不是坏消息，而是一个真实的运行时口径**：既然 keystore 取不到，
`EdgeRuntimeConfig.putSecret` 走的是 `getOrDefault(value)` 分支 ⇒ **明文写入 SharedPreferences**。
新增 `SecretStorageFallbackTest`（12 条）把这个**回退口径钉住**（钉的是行为契约，
而不是「keystore 能用」这个不成立的假设）。真机 keystore 行为仍属**未覆盖**（需仪器化测试）。

---

## 2. 关键发现②：门禁自己中了它警告的形态③（注释里的 `@Test`）

`scripts/check-android-tests-wired.mjs` 的规则 1 用 `/@Test\b/` **在整个文件文本里搜**。
我在测试文件里写了一句**注释**，里面含 `@Test` 字样 —— 门禁**照样通过**。
这正是该门禁头注里自己警告的「形态③：判据恒真 / 白名单 / 搜关键词」。

- 修法：新增 `stripKotlinComments()`，**先剥掉 `//` 与 `/* */` 再搜**；规则 1/2/2b/3 全部改用剥注释后的文本。
- 顺带修掉两处**同源缺陷**：规则 2 用 `/testImplementation\s*\(/` 搜未剥注释文本
  （漂移用例把行替换成 `// removed: testImplementation(...)` 时**仍然命中** ⇒ 门禁假绿），
  规则 3 解析 `productFlavors` 同样未剥注释。
- 新增漂移用例 **G9**（往测试文件里加一句含 `@Test` 的注释 ⇒ 门禁必须红）专杀该形态。

---

## 3. 关键发现③：A/B 抓到我自己的判据失效（D10）

首轮 A/B：**15/16**，`D10`（把 `enqueue` 的「挑非关键消息丢弃」改成「恒丢第 0 条」）**注入后仍然绿**。

根因是**判据设计缺陷**（不是实现缺陷）：我的用例先塞满 50 条**非关键**消息、第 51 条才是关键，
此时「第 0 条」**本来就是非关键** ⇒ 朴素实现与正确实现**结果完全相同**，注入无从暴露。

- 修法：**队首放关键消息** ⇒ 只有「挑非关键」才会保住 DOOR 事件，「恒丢第 0 条」必然丢错 ⇒ 判据可红。
- 配套新增 **D15**（把兜底分支 `?: 0` 改成 `?: pending.size - 1`），证明「全是关键消息」那条用例不是空的。

---

## 4. 变更清单

| 文件 | 变更 |
|---|---|
| `app/build.gradle.kts` | `testImplementation("org.robolectric:robolectric:4.12.2")` + `testImplementation("androidx.test:core:1.5.0")`（后者的 `ApplicationProvider` 提供 `Context`）。⚠️ **刻意不加** `unitTests.isIncludeAndroidResources = true`：实测**缺它照样 89 例全绿**，加了属未验证的配置 ⇒ 也不设对应门禁规则（避免在能跑通的配置上假红） |
| `.../config/EdgeRuntimeConfigPrefsTest.kt` | **新增** 17 条（SharedPreferences 真读写 / 覆盖读取） |
| `.../config/SecretStorageFallbackTest.kt` | **新增** 12 条（密钥回退口径，见 §1） |
| `.../queue/PrefsJsonQueueTest.kt` | **新增** 11 条（容量丢弃 / replaceAll / 副本语义） |
| `.../mqtt/OutboundMqttQueueTest.kt` | **新增** 12 条（真跑 `enqueue`/`drain`，含丢弃策略） |
| `.../mqtt/OutboundMqttQueueCriticalTest.kt` | **删 2 条自证用例**（复刻生产表达式的形式③），保留 11 条 |
| `scripts/check-android-tests-wired.mjs` | 见 §2（剥注释 + 规则 2/3 同源修复） |
| `docs/evidence/.../p11.../scripts/ab-drift.py` | 扩到 **17 条**（新增 D8–D15、R2 + 修还原校验名单漏 `F_PREFS`） |
| `docs/evidence/.../p11.../scripts/gate-drift.mjs` | 扩到 **10 条**（新增 G9 形态③专杀、G10 Robolectric 依赖；**修正 G3 的错误期望**） |

> **G3 为什么被修正**：第一版摘掉 `junit` 一行就期望门禁红，实测**仍绿** —— 但那**不是门禁缺陷**，
> 是**用例期望错了**：规则 2 的语义是「`testImplementation` 里至少要有运行依赖」，
> 而非「必须钉死 junit」（robolectric/androidx.test 还在 ⇒ 本就不该红）。
> 若改成「必须钉死 junit」才是错方向 —— 将来迁 JUnit5 会在**能跑通**的配置上**假红**。

---

## 5. 验证结果（全部实跑）

| 项 | 结果 | 判据 |
|---|---|---|
| Robolectric 全量单测 | **89 例、0 失败**（10 个 XML 报告） | 只认 `<testcase>` 元素个数（`Tests run:` 在 `@Nested` 下会写 0） |
| 用例数一致性 | 源码 `@Test` **89** = 报告 `<testcase>` **89** | 先 grep 到 90，查明差的那 1 个是**注释里的字样**（见 §2），无静默跳过 |
| A/B 注入漂移 | **17/17** 符合期望 | `scripts/ab-drift.py`，**基线先跑绿才继续**；受控文件 sha256 逐字节还原 |
| 门禁注入漂移 | **G1–G10 / 10 全过** | `scripts/gate-drift.mjs` |
| 聚合门禁链 | **29 个门禁、失败 0** | `node scripts/run-audit-gates.mjs` |

---

## 6. 复现

```bash
# 单测（同步到临时构建目录再跑，避免污染工作区；计数只认 <testcase>）
bash docs/evidence/2026-09-19-p11-android-tests/scripts/run-android-unit-tests.sh

# A/B 注入漂移（17 条；会先跑基线，基线不绿即中止）
python docs/evidence/2026-09-19-p11-android-tests/scripts/ab-drift.py

# 门禁注入漂移（10 条）
node docs/evidence/2026-09-19-p11-android-tests/scripts/gate-drift.mjs
```

前置（本机）：最小 Android SDK（`platforms/android-34/android.jar` + `build-tools/34.0.0` + `licenses/`）
＋ 本地 `~/.gradle` 里的 gradle 8.9（**仓库无 wrapper，不要写 `./gradlew`**）。
搭建细节见 `.workbuddy/memory/EDGE-ANDROID-GRADLE.md`（该目录不入库）。

---

## 7. 仍未覆盖

- `AndroidKeyStore` 的**真机**行为（Robolectric 不实现 ⇒ 只能仪器化测试）；
- 串口 `ChzhSerialPort` 的真实 I/O、`Context` 相关 Android 组件生命周期；
- `*IT` 级（Robolectric 之外的）集成路径。
