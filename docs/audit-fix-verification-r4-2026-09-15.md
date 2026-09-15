# 整改质量核验 · 第四轮（R4）

- **核验时间**：2026-09-15
- **核验基线**：`b6dba8d6` → **`956d9194`**（新增 1 个提交）
- **提交**：`956d9194` fix(openapi): stocktake 契约改用 @Schema 并重生成类型
- **核验方式**：读源码 + **实跑 CI 等价的 OpenAPI 重生成比对** + 强制 clean 编译

---

## 〇、结论：R3 的 P1 已**完全闭环**，并附实测证据

| 维度 | 结论 |
|------|------|
| R3-P1-001（手改生成物 + 中文变 `?` + CI 必红） | ✅ **闭环**，且经**实跑验证** CI 不再红 |
| R3-P2-002（文档措辞） | ✅ 已修正 |
| R3-P2-001（`PrefsJsonQueue` 主线程） | ✅ 已确认调用链在后台路径，并加 Javadoc 约束 |
| 唯一开放项 | `manifest.json:15` `appid` 仍为空 |

---

## 一、修复内容核验

### 1.1 落点改对了：Javadoc → `@Schema`

`services/common/common-core/src/main/java/com/aicabinet/common/dto/StocktakeAdjustRequest.java`（源码中文编码正常）：

```java
@Schema(description = "库存盘点调整请求")
public record StocktakeAdjustRequest(
        @NotBlank @Schema(description = "设备 ID", requiredMode = Schema.RequiredMode.REQUIRED) String deviceId,
        ...
        @Schema(description = "客户端看到的库存版本。已有库存行时必填：缺失返回 400，版本冲突返回 409。新建库存行时可省略。")
        Long expectedVersion
) {}
```

Javadoc 仍保留（给开发者看），另加 `@Schema`（给 springdoc 看）—— 这是本项目唯一正确的双轨写法。

### 1.2 依赖引入正确且版本无冲突

`services/common/common-core/pom.xml` 新增：

```xml
<!-- springdoc 只认 @Schema；Javadoc 不会进入 /v3/api-docs -->
<dependency>
    <groupId>io.swagger.core.v3</groupId>
    <artifactId>swagger-annotations-jakarta</artifactId>
    <version>2.2.36</version>
</dependency>
```

- 版本 **2.2.36 与 springdoc 2.8.13 传递带来的 `swagger-core-jakarta:2.2.36` 完全一致**（`services/trade-service/target/dep-list.txt:108-109`）→ 无 Maven 版本仲裁风险。
- 注释把「为什么不能用 javadoc」写进了 pom，后人不会再踩。

### 1.3 生成物已恢复且中文正常

`packages/shared-types/src/generated/openapi.ts:11359-11377`：

```ts
StocktakeAdjustRequest: {
    /** @description 设备 ID */
    deviceId: string;
    ...
    /**
     * Format: int64
     * @description 客户端看到的库存版本。已有库存行时必填：缺失返回 400，版本冲突返回 409。新建库存行时可省略。
     */
    expectedVersion?: number;
};
```

全文件 `?????` 匹配数 = **0**；`?` 乱码行已彻底消失。

---

## 二、🔬 关键实测：模拟 CI 的重生成比对（本轮最重要证据）

R3 的 P1 判定依赖推演，本轮**直接实跑**了 CI 的同一条链路：

```
OPENAPI_CHECK_REGEN=1 OPENAPI_FILE=.tmp/live-openapi.json node scripts/check-openapi-types.mjs
```

| 观测项 | 结果 |
|---|---|
| 脚本退出码 | **EXIT=0** |
| 脚本结论 | **`[check-openapi-types] OK`** |
| 重生成来源 | `.tmp/live-openapi.json`（529 paths，含 `@Schema` 生成的描述） |
| `git diff --exit-code -- packages/shared-types/src/generated/` | **diffExit=0（零差异）** |
| 乱码残留 | `openapi.ts` 中 `?????` = **0** |

→ 说明已提交的 `openapi.ts` 及 7 个别名文件（order/replenishment/member-coupon/notify-marketing/merchant-finance/merchant-ops/admin-models）**均可从 spec 逐字节复现**。CI 的 `Verify OpenAPI-generated types are up-to-date`（`ci.yml:134-170`）**会通过**，R3 担心的红灯已消除。

> 备注：为防污染工作区，跑之前已备份生成物；实跑后 `git status` 确认工作区干净（仅核验方临时文件）。

---

## 三、编译验证

```
mvn -pl services/common/common-core clean compile
```

`Compiling 384 source files … BUILD SUCCESS`（54.9s）—— 新增 `@Schema` 注解与依赖在 clean 场景下编译通过。

---

## 四、R3 其余项跟进情况

| 编号 | 项 | 状态 | 证据 |
|---|---|---|---|
| R3-P2-002 | r2 报告 §3.3 措辞偏乐观 | ✅ 已修正 | `audit-fix-verification-r2-2026-09-15.md` §3.3 改为「⚠️ 待重做（R3 已跟进）」，并明确「曾误把手写中文写进 DO NOT EDIT 的 openapi.ts（编码成 `?`），会挂 CI regen-diff」「正确落点：`@Schema` → `pnpm gen:api-types`」 |
| R3-P2-002 附 | 经验表措辞 | ✅ 已修正 | `lessons-learned.md:100` #93 → 「**`@Schema` 注解**（springdoc 不读 Javadoc）+ Javadoc 标明必填；禁无 version 便捷构造器；**禁止手改** `generated/openapi.ts`」 |
| R3-P2-001 | `PrefsJsonQueue` `@Synchronized` + `commit()` 主线程风险 | ✅ **已确认并标注** | 调用链：`MqttDeviceClient.publish`（心跳池/MQTT 失败路径）、`CabinetController` 上传失败进离线队列；均非 Activity UI。`mutate` Javadoc 已注明勿在主线程大批量调用 |
| R2-5 / R3-5 | 两端 `manifest.json:15` `appid` 为空 | ⏳ **仍开放** | `consumer-mp:15`、`merchant-mp:15` 仍为 `""` —— 真机/发布唯一硬阻塞 |

---

## 五、可选优化跟进

| 编号 | 严重度 | 状态 | 说明 |
|---|---|---|---|
| R4-P2-001 | P2 | ✅ **已落地** | 根 `pom.xml` 增加 `swagger-annotations.version=2.2.36` + DM；`common-core` 去掉硬编码版本。升级 springdoc 时核对该属性 |
| R4-P2-002 | P2 | ✅ **已确认** | 见上表 R3-P2-001；当前入口在后台路径，风险可接受 |

---

## 六、签发意见

**R3 唯一 P1 已修复到位，且落点、依赖、生成物、文档四处全部正确**——从「改生成物」改为「改源头 + 重生成」，这正是应有的修法。经实跑 CI 等价命令确认零差异，**不再阻断流水线**。

剩余唯一硬阻塞：
- 🟢 **`manifest.json:15` 填 `appid`** —— 上线/真机前必须由持有密钥方填入

---

*核验人：WorkBuddy ｜ 方法：源码通读 + CI 等价命令实跑（regen-diff）+ clean 编译*
*跟进：R4-P2-001/002 与 Prefs 调用线程确认已在后续提交落地*
