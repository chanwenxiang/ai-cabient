# 上线前三端代码走查报告（2026-11-07）

> 范围：`clients/admin-vue`（运营后台）、`clients/consumer-mp`（消费者小程序）、`clients/merchant-mp`（商户小程序）、`services/trade-service`（交易服务核心）、`packages/shared-types`。
> 方法：**逐文件源码走查**，不采信任何他 AI 文档自报 `[x]`/绿；每个结论都落到 `file:line`。
> 后端单元测试：`mvn -o -pl services/trade-service -am test` 实跑 → **Tests run: 2827，Failures 0，Errors 0，Skipped 0，BUILD SUCCESS**（surefire 自 fork 收尾告警 `exit 1` 非失败；逐类 `*.txt` 汇总 fail=0 err=0）。

## 目录
1. [结论摘要](#1-结论摘要)
2. [F1 调账审批：无实例也返回 200 APPROVED（真实缺陷，建议修）](#2-f1)
3. [F2 调账不驱动打款（刻意语义，非缺陷）](#3-f2)
4. [F3 审批手动发起响应用 Map.of：字段为空即 500（低风险健壮性）](#4-f3)
5. [消费端首页 0 商品卡 — 复核后判定非缺陷](#5-消费端首页0-商品卡--复核后判定非缺陷)
6. [其它已核项（不成立的怀疑 / 已修复）](#6-其它已核项不成立的怀疑--已修复)
7. [未闭环项（需真实环境验证）](#7-未闭环项需真实环境验证)
8. [环境事实（测试计划前置）](#8-环境事实测试计划前置)

---

## 1 · 结论摘要

| 编号 | 端 | 位置 | 性质 | 严重度 | 处置建议 |
|------|----|------|------|--------|----------|
| F1 | trade-service | `MerchantWithdrawService.reviewAdjustment:319-332` | 逻辑缺陷：审批静默无操作仍回 200/APPROVED | **P2**（资金不真实变动，但审计留痕失真 + UI 无法区分） | 按 `completeReview:354-359` 的 409 模式补守卫 |
| F2 | trade-service | `MerchantWithdrawService.reviewAdjustment:312-318` | 刻意语义：调账审批不驱动打款 | 非缺陷（设计文档化） | 保持；如后续要回冲再议 |
| F3 | trade-service | `ApprovalController.startInstance:112` | `Map.of(...)` 遇字段 null → NPE 500 | P3 | `Map.of(k, v==null?"":v)` 或用 LinkedHashMap |
| FG-MA167 | admin-vue | `MerchantWithdrawView:897-903` | 已修：inbox 键名 `tasks` → `pendingTasks` | 已闭环 | — |
| FG-MERCH-BIZ | admin-vue/shared-types | `MerchantSplitsView.vue` / `MerchantDto` / `UpsertMerchantRequest` | 已核：`legalPerson`/`businessLicenseUrl` 已贯通 | 已闭环 | — |
| FG-CONS-HOME | consumer-mp | `pages/index/index.vue` | 非缺陷：柜机购物页需 `deviceId` 才渲染 | 关闭 | — |
| FG-JAN-CAP | merchant-mp | `pages/jiangyi-capture/jiangyi-capture.vue` | 仅见客户端结构，后端接口未端到端验证 | 待验 | 见 §7 |

后端 380 个测试类全绿（2827 例，0 失败）。**本次走查只新增 1 个建议修缺陷（F1）+ 1 个健壮性建议（F3）。**

---

## 2. F1 〈调账审批：无实例也返回 200 APPROVED〉

**位置**：`services/trade-service/src/main/java/com/aicabinet/trade/service/MerchantWithdrawService.java:319-332`

```java
public Map<String, Object> reviewAdjustment(Long operatorId, String bizId, boolean approve, String remark) {
    permissionService.requirePermission(operatorId, PERM_OPS_MERCHANT_WITHDRAW_REVIEW);
    String status;
    if (approve) {
        approvalWorkflowService.completeApproved(operatorId, BIZ_WALLET_ADJUST, bizId, trim(remark));
        status = "APPROVED";          // ← 无条件就算批示过
    } else {
        approvalWorkflowService.completeRejected(operatorId, BIZ_WALLET_ADJUST, bizId, trim(remark));
        status = "REJECTED";
    }
    auditService.appendLog(operatorId, BIZ_WALLET_ADJUST + "_REVIEW", BIZ_WALLET_ADJUST, bizId,
            "结果=" + status + "；备注=" + trim(remark));   // ← 且写进审计
    return Map.of("bizId", bizId, "status", status);        // ← 且回 200
}
```

**问题**：`completeApproved/completeRejected` 在**实例不存在**（审批定义为空/已禁用）或**实例已终结**时是**静默 no-op**（`ApprovalWorkflowService.completeApproved` 直接 return）。此时本方法仍返回 `status=APPROVED`、仍写审计行、仍 HTTP 200。三层后果：

1. **审计留痕失真**：实际 instance 可能根本没有 `APPROVED` 状态，账上却有了「结果=APPROVED」的不可撤回留痕。
2. **UI 无法区分**：`MerchantWithdrawView` 调 `POST  merchantWalletAdjustReview(bizId,{approve,remark})` 后只看 `code===0`，真实结论与静默空操作无法区分 → 用户以为审批过了。
3. **与同文件对照**：提现审核路径 `completeReview:354-359` **已正确**挡：无实例时抛 `409 「审批流未发起…无法通过；如需退回冻结资金请走驳回」`。这条是同类逻辑应有的样子。

**性质**：资金不因这条路径真实变动（调账在发起时已即时生效，审批只做事后留痕），所以是「**留痕/响应正确性**」缺陷，非资金直接错误。但线上审计证据失真，属上线前该修的级别。

**建议修法**（对齐 `completeReview`）：
```java
if (approve && approvalWorkflowService.instanceStatus(BIZ_WALLET_ADJUST, bizId).isEmpty()) {
    throw new ResponseStatusException(HttpStatus.CONFLICT,
        "审批流未发起（审批定义为空或已禁用），无法通过；如需驳回请走驳回");
}
```
非 approve（驳回）路径也建议同样校验实例存在，否则也会「空操作 + 假 REJECTED」。

**测试验证方式**：见 §7 / 测试计划 R7 边界，用真实造数造一个「无审批实例的调账单」点通过/驳回。

---

## 3. F2 〈调账不驱动打款〉

**位置**：`MerchantWithdrawService.java:312-318` 注释，`reviewAdjustment` 只驱动实例状态机。

这是**有意设计**（代码注释已写明）：调账资金在发起时已即时生效，事后审批只留痕；自动反向冲账有透支风险。**非缺陷**，保留。本报告仅登记以免被误判为 bug。

---

## 4. F3 〈审批手动发起响应用 Map.of〉

**位置**：`services/trade-service/src/main/java/com/aicabinet/trade/api/ApprovalController.java:112`

```java
return ApiResponse.ok(Map.of(
        "instanceId", instance.getInstanceId(),
        "bizType",    instance.getBizType(),
        "bizId",      instance.getBizId(),
        "status",     instance.getStatus(),
        "title",      instance.getTitle() == null ? "" : instance.getTitle()));
```

`Map.of` **不接受 null 值**：若 `instance.getStatus()`（或 `getBizType/getBizId`）为 null → `NullPointerException` → HTTP 500 而非干净的 400/业务响应。`startManual`（`ApprovalWorkflowService:155-177`）对 `bizType/bizId/title` 已做空白守卫，故实际触发面主要在 `status` 字段。`title` 已用 ternary 兜了。

**严重度 P3**（正常路径 startManual 会构造出实例，触发概率低）。**建议**：改用 `LinkedHashMap`，或对每个字段 `Objects.requireNonNullElse`。

---

## 5. 消费端首页 0 商品卡 — 复核后判定非缺陷

**现象**：`reLaunch('/pages/index/index')`（不带参）时 `.product-cell` = 0。初看像「首页打不开」。

**复核**：`pages/index/index.vue` 首页是**柜机购物页**，`deviceId` 来自 `parseLaunchOptions`（柜门二维码 deep link / `?deviceId=`）；`v-if="products.length"` 只在 `deviceId` 就绪后才请求 `/api/v2/devices/{id}/products`。**不带 deviceId 就是 landing 态**（`.page-root.is-landing`），0 商品卡是正确行为。

带 `deviceId=EG307002410770` + 消费端登录态实测：`cell-count=6`、`.product-grid=1`、`.shop-nav-title=演示智能柜`、价格 `¥6.37/件…`、商品名 `原味薯片 70g…` —— 全部正常。**判定非缺陷，样例截图 `.tmp/ui-probe-20261107/10-home-device.png`。**

---

## 6. 其它已核项（不成立的怀疑 / 已修复）

| 项 | 位置 | 结论 |
|----|------|------|
| 步进器 `.stepper-btn.minus` 在首页不出现 | `index.vue:199/204-222` | **正常**：购物态需 `sessionActive && state==='SHOPPING' && mockEnabled`，未开门时隐藏 |
| 商家改店弹窗法人/执照字段 | `MerchantSplitsView.vue` | 已贯通：`MerchantDto`/`UpsertMerchantRequest` 均暴露 `legalPerson`/`businessLicenseUrl`；`MerchantService:173-177` 条件写（非 null 才写）保持局部更新语义 |
| 提现资格校验要求法人 | `WithdrawEligibilityService:76-86` | ✅ `legalPerson` 必填；执照 url 更软 |
| 调账审批 tab 恒空 | `MerchantWithdrawView.vue:897-903` | ✅ 已修（MA-167）：`inbox.pendingTasks` 过滤 `bizType===MERCHANT_WALLET_ADJUST && status==='PENDING'` → `adjustTasks` |
| 5 个 CRLF 文件 | `admin-vue/…`、`packages/shared-types/src/index.ts` | ✅ `scripts/check-line-endings.mjs --fix` 已修，磁盘 CRLF=0 |

---

## 7 · 未闭环项（需真实环境验证）

| 项 | 说明 | 验证计划 |
|----|------|----------|
| merchant-mp `jiangyi-capture` | `pages/jiangyi-capture/jiangyi-capture.vue` 是新建页面，只看到客户端结构；`endpoints.ts` 暴露 `jiangyiCapture{Policy,State,Photo,Submit}`。**后端是否有对应接口、是否已接线未确认。** | 测试计划 R3（将邑采集/货架绑定）、R7 收口 |
| F1 真实触发 | 需造「无审批实例的调账单」点通过 | 测试计划 R7 边界 |
| 消费端真实开门结算 | 需消费端登录态 + 柜号；stepp 端到端（真扫码）用设备模拟器 | 测试计划 R1 复跑 / R7 |
| 调账审批发起实例 | UI「发起实例」弹窗真实点击 | 测试计划 R7 |

---

## 8 · 环境事实（测试计划前置）

- **UAT 栈**：`ai-cabinet-trade-service-1`（127.0.0.1:18080 → 8080，health UP）、`jiangyi-gateway-1`（18084）、`gateway-1`（:80，`/api/v2/auth/server-boot` → `{"code":0,"message":"ok"}`）、`postgres-1`（:15433）、`redis-1`、`emqx`、`redpanda`、`minio`、`xxl-job`、`device-simulator`。
- **trade-service 指向 `aicabinet_uat`**（compose override `infra/docker-compose.uat.yml` 注入 `SPRING_DATASOURCE_URL`）；`SPRING_PROFILES_ACTIVE=dev`、`AICABINET_MOCK_ENABLED=true`。
- **演示柜**：`EG307002410770`（`device_info`），绑 `SKU-DEMO-001`（qty=20）/`SKU-SODA-001`（qty=16）。
- **登录态采集**：`.tmp/login-tokens.mjs` 已连通三端，落 `.tmp/tokens.json`：
  - 运营 `13900000001` / `123456`（图形码取自 Redis `aicabinet:captcha:{id}`）→ `token`/userId `100000001`
  - 消费端 `13800138000`（短信码，从 `sms_verification_code` 读）→ userId `10001`
  - 商户端 `13900000002` / `123456`（图形码）→ userId `100000007`
- **微信 `wx-login` 不可用**（`WECHAT_CONSUMER_APP_ID=wx6dacefb3671de630` 为 dev 假值，微信 code2session 失败）⇒ 消费端**必须走短信登录**校验收核；非缺陷，环境事实。
- **微信开发者工具**：`cli.bat` 位于 `D:\devTools\微信web开发者工具\cli.bat`；`auto` ws 端口 9420 已 LISTENING（PID 68160）。项目固定 `dist/dev/mp-weixin`（`build:mp-weixin:dev` 产物）。
- **产品**：`mvn -o -pl services/trade-service -am test` 全绿（2827/0/0/0）；单测**未**覆盖 F1 的真实触发路径（需造数）。

> 说明：SSH ("sp2") 与本会话提及的其它无关工具不在本次范围内；走查仅围绕本仓三端代码。
