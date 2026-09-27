# 进件工作台 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md`](../../../uat/MERCHANT_ONBOARDING_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

本机进件列表共 **0**，空态「暂无进件记录」诚实。页头明确**仅登记、不推送支付渠道**；live-hints 显示三渠道均为测试。新建弹层取消不落库；空保存 toast 正确。渠道/状态筛选项中文无裸码。行级审批因无数据 SKIP。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 默认列表 | 共 **0** · 暂无进件记录 | total=0 |
| channel=WECHAT | 共 0 | total=0 |
| status=SUBMITTED | 共 0 | total=0 |
| 商户编号无匹配 | 共 0 | — |
| live-hints | 仅登记 · 三渠道测试 | registryOnly=true · *PayLive=false |

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | **仅登记**：外部门店号/状态只留痕，不调微信/支付宝进件 OpenAPI（与 mock/registry 模式一致）。 |
| 2 | 批量通过/驳回仅对勾选中 `SUBMITTED`+`PENDING` 行启用；无勾选则 disabled。 |
| 3 | 行「通过/驳回」须有待审行；空表无法点测，属合法 SKIP。 |
| 4 | 深链 `?onboardingId=` 高亮对应行（`is-highlight-row`）；无数据时 SKIP。 |
| 5 | 空表导出可能无文件；按钮仍可点。 |

## 证据

`onb-01`…`onb-15` · `onb-ux-*` · `onb-99-end` · `onb-api-probe.json`
|
