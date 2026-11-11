# 四端审计 R14 报告（2026-11-11）

> **基线**：HEAD `d60d0b91`（R13-MA-174 修复 + 订单商品图 + 运营号段支付三连 + 手写 types 之后）。
> **方法**：4 路并行只读审计（路 1 = MA-174 修复终验+手写 types；路 2 = 运营号段支付三连钱路深审；路 3 = 订单商品图全链；路 4 = 盲区 fresh-eyes + R13 遗留复核 + 门禁巡检）+ 主审对 5 条关键发现亲验（G1/A1/A2/D1/F2 全部坐实）。
> **已知勿报**：通宵终报 ≈95 条 + R11×25 + R12×6 + R13 MA-174；工作树在制文件（placement-event-queue / consumer-api / CouponsView BOM）属并行会话。

---

## 一、总账：新发现 **11 条 = P2×3 + P3×8**；P0/P1 为零

| 路 | P2 | P3 | 面 |
|---|---|---|---|
| 路 1（MA-174 终验） | A1 / A2（MA-174 残余第 7、8 处） | — | R13 的 57 处台账漏计 2 站 |
| 路 2（运营号段支付三连） | — | D1 / D2 / D3 | 三提交方向正确无资金风险；缺口在钉测/注释/既有告警噪声 |
| 路 3（订单商品图全链） | — | F1 / F2 / F3 / F4 | 新功能主体正确；箭头 CSS 冲突 + 列表逐单取图 |
| 路 4（盲区+遗留） | **G1（安全·限流绕过）** | G2 | `/o/{deviceId}` 匿名限流可被 XFF 首跳伪造绕过 |

---

## 二、P2（3 条）

### G1 [P2·安全] `/o/{deviceId}` 匿名限流可被 X-Forwarded-For 首跳伪造绕过——L04 防柜机编号探测闸失效
- **位置**：`CabinetOpenLandingController.java:91-105`（resolveClientIp）+ `:51-52`（限流入口）。
- **证据（主审三点亲验）**：① `resolveClientIp` 取 XFF **第一跳**（`substring(0, comma)`），X-Real-IP 仅回退；② 本仓全部网关 nginx 用 `$proxy_add_x_forwarded_for`（**追加**而非覆写，nginx-full.conf ×6 + compose 同款），且恒定覆写 `X-Real-IP $remote_addr`（可信）；③ 后端无 XFF 归一 Filter、未配 `server.forward-headers-strategy`（全仓零命中），该 Controller 是全后端唯一 XFF 消费者。
- **触发**：攻击者每次带随机 `X-Forwarded-For: <假IP>` → nginx 追加成「假IP, 真IP」→ 代码取第一跳=假IP → 限流键落在自选值 → 轮换假IP 即无限速，L04「防 NOT_FOUND 差异探测柜机编号」闸被整体架空。注释「不可伪造多段绕过」只防了多段注入变体，没防单段伪造+追加型拓扑。
- **修法**：信任序倒置——`X-Real-IP` 优先（nginx 恒覆写不可伪造），XFF 仅作直连回退；口径写进门禁/注释。单文件 ~8 行。
- **置信度**：高。

### A1 [P2] MenuManageView 权限编辑 `path` 清不掉——MA-174 残余第 7 处（R13 台账漏计）
- **位置**：`MenuManageView.vue:530/:541/:582`（三处 `path: ... || null`；F 类型强制发 null = **必然触发**）+ 后端 `OpsRbacService:259` `p.setPath(blankToNull(...))` + save 无显式清列。
- **触发**：①清空「路由」输入 → null 被 updateById 跳过，旧路由永留；②按钮型权限（F）残留旧路由列。
- **修法**：后端照 MA-169 模板补 wrapper；前端 `|| null` 可保留（键恒发，null=清空信号）。
- **置信度**：高（主审亲验）。

### A2 [P2] PhoneVerifyView 验证记录 `merchantId`/`userId` 清不掉——MA-174 残余第 8 处（R13 完全未提及）
- **位置**：`PhoneVerifyView.vue:245-252`（`merchantId: form.merchantId || null`、`userId: ... : null`）+ 后端 `CompetitiveGapService:707-709` `log.setMerchantId(body.merchantId())` + `updateById`（该文件无任何 lambdaUpdate）。
- **触发**：编辑验证记录清空「商户编号」→ null 被跳过 ⇒ 旧商户绑定永留。注：该处有 `findByIdForUpdate` 行锁，无并发面，纯 M01 清列缺陷。
- **修法**：merchantId/userId 两列补显式清列 wrapper。
- **置信度**：高（主审亲验）。

---

## 三、P3（8 条）

| # | 一句话（均主审亲验或证据核对） |
|---|---|
| D1 | 2535dcd2 三处号段豁免移除**零钉测**（三连中唯一无测试的提交；号段豁免回归不可测）——修法：补 3 个运营 id 单测 |
| D2 | UserValidationService:56/:170 两处 javadoc 仍写「运营账号跳过」，与已删豁免的代码相反（同批其它注释都改了，此两处漏网） |
| D3 | 渠道回落余额分支 CHARGE_PENDING 痕迹行不收口（OrderPaymentService:299-301）→ 60 分钟 HIGH 告警噪声——**既有 H41 形态非新引入**，运营号段收紧把运营单带入该人群；生产 e2e-h5/真部署告警量待观察 |
| F1 | 在线客服按钮箭头不渲染：`app-link-chevron` 的 ::after 图形被同元素 scoped `.support--contact::after{border:none}` 覆盖（merchant devices.vue:610 注释自证过此约束）；修法=文案包一层宿主挂箭头 |
| F2 | 订单列表逐单取图 = 每页 +N 次 sku_catalog 点查（单内批量、跨单未批量；仓内有 `findBySkuIds`/`findByOrderIds` 现成先例）——可选优化，非熔断 |
| F3 | OpenAPI 生成类型 Merchant/Admin 变体缺 coverImageUrl（=lineSkuIds 存量缺位模式，非本次引入；运行时 payload 实际带）——商户端将来做图时一并查根因 |
| F4 | update-prompt.ts:10 注释悬空引用已删除的 haptic 设置（顺手改） |
| G2 | DeviceDataFeeBillService markPaid/voidBill 读后写无行锁/乐观锁——并发双操作可产生「VOID 但 paidAt 残留」（人工留痕通道无资金移动；`findByIdForUpdate` 先例现成，随批修或登记豁免） |

---

## 四、MA-174 修复终验（98979068）—— ✅ 成立，但清单未穷尽

| 站点 | 前端 | 后端 | 判定 |
|---|---|---|---|
| ScheduledTaskView scheduleDesc/remark | `?? null` :485-486 | wrapper :122-132 | ✅ |
| ApprovalConfigView remark | `?? null` :555 | wrapper :495-500 | ✅ |
| ProfileView email/avatarUrl | `?? null` :464-465 | 双 wrapper :928-937 | ✅ |
| WarehouseMonthlyCloseSheetsView lineRemark | `?? null` :781 | wrapper :244-249 | ✅ |
| AdCampaignsView linkUrl | `?? null` :493 | wrapper :160-165 | ✅ |
| CouponsView activityId | `|| null` **保留=有意豁免** | `writeNullableColumns` 显式写列（R2-E2 先例） | ✅ 豁免成立 |
| **MenuManageView path** | 仍 `|| null` ×3 | 无 wrapper | ❌ **漏计 → R14-A1** |
| **PhoneVerifyView merchantId/userId** | `|| null` | 无 wrapper（CompetitiveGapService 零 lambdaUpdate） | ❌ **漏计 → R14-A2** |

「5+5」与「6 处」的差额=CouponsView 豁免（成立）；**真正的漏计是 A1/A2 两站**（R13 的 57 处台账未包含）。附带：OrgSitesView 合同起止日期（R13 列 P3 候选）的豁免理由不成立——el-date-picker clearable 清空后同样清不掉，定级建议升 P3 缺陷。

---

## 五、其它路结论（主体正确）

- **运营号段支付三连**（857fcbb8/260299cc/2535dcd2）：七条资金主链运营与消费者逐字共用同一代码，豁免残留全扫 **0 资金路径残留**；预冻结/扣款/退款守恒有钉测；无 mock/demo 误伤（演示运营号现在消费式开门 412 需充值=刻意收紧）；支付分无契约不触碰（生产 501 fail-closed 已知）。
- **订单商品图全链**：OrderReadModel 为 common-core 纯 DTO + OrderViewAssembler 批量 selectBatchIds 装配（非 XML）；三端 additive 视图带字段；haptic 全链零残留；barehex 基线 1076→1075 实跑恰等。
- **工作树在制识别**：CouponsView 仅去 BOM；placement-event-queue/consumer-api 无未提交 diff（并行会话已提交 f520064a）。

---

## 六、R13 遗留复核 + 门禁巡检

- **R13 无未修 P2/P1 残留**：MA-174 全部 6 站点（5 修 + 1 豁免）HEAD 实读确认；ISSUES.md MA-174 行已记 fixed。
- **门禁实跑 11 次全绿**：seq-success-path-guard(self-test+全量) / mybatis-null-clear / flyway-seed-separation(449 V) / migration-safety / xxl-job-wiring(38/38/38/38, 看护 43) / trybegin(44) / seed(44↔44) / mp-defect-classes(276 文件) / merchant-me-gated-load(36 页) / admin-artifact-pairing / audit-gates-wiring(121 接线)。
- **定时任务四方对账**：registry 44 / KEYS 38 / 种子行 44 / watchedKeys 43 = 38+5 常驻，看护自观察 1 不入——四方对齐无缺口（R6-E6 后 E2 缺口亦已由 5 键收编闭合）。

---

*报告落盘：2026-11-11。主审亲验 5 条关键发现全部坐实；其余 P3 与两路证据一致性核对。修复批建议顺序：G1（安全，~8 行）→ A1/A2（M01 清列）→ D1（补 3 钉测）→ F1/G2/D2/D3/F4 随批；F2/F3 登记观察。*
