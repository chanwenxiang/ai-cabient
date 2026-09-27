# 整轮回归 FINDINGS（2026-09-27）

> 前置：`559ed362` docs(uat) S3 收口已提交（ahead origin/dev）。  
> 策略：`cleanup -KeepPlatform -RestoreBalanceCents 50000` → `mp-seed-gate` → `e2e-full-flow-milk -SkipCleanup`（避免默认 Wipe 拆掉刚 Resolve 的柜/仓）。

## 闸门

| 项 | 结果 |
|---|---|
| KeepPlatform cleanup | OK；柜 `166813762350`、仓/供/SKU 保留；余额 50000 |
| mp-seed-gate | `pass=True`（清数后 `consumer_orders=0` 预期 FAIL 行，不挡 pass） |

## milk 主链（首跑 ~14.7min，exit 1）

| Id | 结果 | 说明 |
|---|---|---|
| 1-procurement | PASS | PO=5 RECEIVED |
| 2-replenishment | PASS | task=17 outbound=7 |
| 3-three-end | PASS | 34/34（joint 均 DISPUTED→内部关门兜底） |
| S-02-fund-safety | PASS | 含 trade 宕机恢复 |
| 4-shopping | PASS | BALANCE -350 |
| 5-partial-refund | PASS | |
| **6-reconciliation** | **FAIL→根因见下** | 首跑 `batches=0`（假红） |
| 6-splits | PASS | count=3 |
| 6-flow-numeric | PASS | |
| S-01-gray-check | PASS | 非阻断；见灰项 |
| S-05-api-tests | PASS | 13/13 |

首跑摘要：`PASS=10 FAIL=1`（仅对账断言）。

## 6-reconciliation 假红（已软写脚本）

| 现象 | 根因 | 必须怎么做 |
|---|---|---|
| milk 报 `batches=0`，库内已有 `recon_id=3` | `GET /reconciliation` 的 `data` 是**数组**；`Invoke-E2eApi` 解包后单元素被 PS 拆成对象，`$recon.items` 恒空 | **必须** `@($apiResult)` 再数 Count；禁对裸数组读 `.items`；清数后先 `POST .../reconciliation/run` |

复验：`milk -SkipCleanup -SkipLayerA -FromStep finance` → `6-reconciliation PASS batches=1`，`PASS=5 FAIL=0`。

## Gray CheckOnly（非阻断，dev）

- vision recognizer / mock_enabled：灰度真金前已知挡项  
- `infra.device-service`：脚本探测连不上（同轮 `TC-INFRA-003 :18081` PASS）  
- open exceptions=3 / disputes=2：主链 DISPUTED 残留  
- `gray.ops_user_list` balance 空字段：脚本字段映射，非余额丢

## 结论

- **业务主链可回归绿**：采购→补货→三端→资金安全→购物→部分退→分账/流水→API。  
- **唯一硬失败为脚本假红**，已修 `e2e-full-flow-milk.ps1`（未另开 commit，随下次提交）。  
- 未 push。
