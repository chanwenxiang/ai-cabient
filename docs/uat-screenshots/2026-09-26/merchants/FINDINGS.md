# 商户与分账 · FINDINGS · 2026-09-26

> [`BUTTONS.md`](./BUTTONS.md) · [`MERCHANTS_FULL_BROWSER_UAT.md`](../../../uat/MERCHANTS_FULL_BROWSER_UAT.md)

## 结论

**PASS · FAIL 0 · BLOCK 0**

四 Tab 齐全。组织树可新建/编辑/挂载且均取消；商户列表共 **2** ↔ API；分账明细共 **24** ↔ API，行「确认完结」二次确认后取消。功能包开关为即时写，本轮只验可见未拨动。深链 `?tab=splits` 落地正确。结束视口 **1366×768**。

## 数据对照

| 面 | UI | API |
|----|-----|-----|
| 组织树 | 2 节点 | merchants 树 |
| 商户列表 | 共 **2** | total=2（MCH-DEFAULT / MCH-OTHER） |
| 关键词无匹配 | 共 0 | — |
| 分账明细 | 共 **24** | revenue-splits total=24 |
| 深链 splits | Tab 分账明细 · 共 24 | — |

样例分账：`LEDGER_ONLY` · gross 350¢ · platform 35¢ · merchant 315¢

## 本轮缺陷

无 FAIL。无需改码。

## 口径说明（非 FAIL）

| # | 说明 |
|---|------|
| 1 | 余额支付默认**仅记账**入商户钱包；有微信交易号才可「提交」真实分账。 |
| 2 | **功能包开关无二次确认**，拨动即写库；UAT/回归禁止为「点一下」而拨开关。 |
| 3 | 运营配置「保存」为硬写；本轮只验页可见。 |
| 4 | 「确认完结」为 prompt+确认，取消不落库；批量确认须先勾选仅记账行。 |
| 5 | 深链：`?tab=org|merchants|ops-config|splits`（及 splits 的 status/merchantId）。 |
| 6 | 上级商户可见下级货柜；子商户按自身抽成结算（非自动再抽成）— 组织树 alert。 |

## 证据

`mch-01`…`mch-16` · `mch-ux-*` · `mch-99-end` · `mch-api-probe.json`
|
