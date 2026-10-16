# Track E — 将邑对接面审计（2026-10-13）

基线 `9911a2ee`；文档 `将邑对接资料/doc_full_text.txt`（V16.0.0 抽取文本）。
选型事实：**2026-10-09 已拍板模式一·普通对接**（`docs/JIANGYI_SAAS_INTEGRATION_V16.md` §7，「只接第四章，5.4.x 回调均不接」）。

## E-2 定案（原列「最高优先待决」，现已关闭）

§5.4.2–5.4.7（sendOrderAmt / sendOrderRecheck / 支付失败×2 / 模型版本 / 补货 / 在线状态）**不实现属选型一致，非缺陷**：模式一下出单·扣费·结算·争议全在我方，将邑仅是设备与模型供应商，这些回调只是「喂将邑展示账本」。存量文档与本台账 AU-003 的「gap」表述据此更正。

**唯一例外＝5.4.10 confirmActivate**：文档原文「未绑定机器则处于未激活状态」（DOC:153），`JiangyiOnboardingService.java:25` 标「暂不实现」且全仓零调用 ⇒ **新柜上线（扩机/量产）时无激活通道** —— 列 **P1·扩机阻塞**，上线前须与将邑书面确认改由其售后手动激活，或补实现。

## E-1 §4 设备↔我方逐接口结论

13 个接口 12 个一致（含容错文档笔误的 setDomain/getIdentifier 兜底 `JiangyiMerchantClient.java:65`、`JiangyiOnboardingService.java:88/103`；识别结果先合成 CLOSED→归一化→edge-results，映射未命中 fail-closed 转 DISPUTED、金额不采信设备 `DeviceReportController.java:122/134`）。

偏差/缺失：
- **R2（P1，实质影响复核闭环）** WS 拉取视频指令 `sendUploadErrorOrderVideos`（V16 §4.3.2.11）未接，且 `getArtificialCheckByIdentifier` 恒返 false（`DeviceInfoController.java:87`，设备只上传异常单视频）⇒ 正常单视频仅存柜机本地（64GB/保留 1 个月，将邑已确认），**纠纷回拉录像无通道、超期永久灭失**。若对外 SLA 含正常单视频复核 ⇒ 升 P0。
- **R3（P2）** token 端点 404/403/503 走 HTTP 状态码而非文档的 `body.status` 信封（`DeviceTokenController.java:58,63,67`），固件按信封解析的行为未真机验证。
- §4.3 WS 缺 13 条指令：reboot（我方需发，缺失，现场断电兜底，P2）；强制开门/双门/灯带/音量/closeDoor 等属单门柜+端侧自主 ⇒ 不需要或挂起；getDoorState/getDeviceInfo/sendQueryNetwork/sendChangeWifi（配网/信号巡检）缺失 ⇒ 现场人工兜底（P2）。
- §4.2.15–4.2.19 可选上报面（设备信息/门磁/wifi/在线/广告）未实现，设备侧会收 404（P3 噪音）。

## E-3 凭据/配置面

- 七件套机制正确：`JIANGYI_JWT_SECRET` 无默认值 ⇒ 503 fail-closed（`application.yml:26`）；生产实值未出现在任何入库文件 ✓。OSS 五项 ALL GREEN（GATEWAY_DESIGN §7.6 实测）。
- **F-E1（P1）**：生产模板 `infra/.env.example` **无 JIANGYI 段**（仅 `.env.staging.example:40-56` 有）⇒ 生产漏配即整条设备链静默不可用。修法：补段+注释。
- **F-E2（P2）**：当前回调域名=花生壳 `130954bm9ka83.vicp.fun`→192.168.0.26:80（`将邑对接确认记录.txt:21`），部署检查单明示「勿用花生壳」；**切正式域后需逐设备重绑 setDomain**（`JiangyiOnboardingService.java:155` 可重放），流程未文档化（检查单只写首次绑定）。
- 原「生产/staging 共用将邑租户 426」**未能证实**：tenant 426 在仓内与将邑资料全文检索零命中 ⇒ 撤出拍板清单，改记「上线前向将邑索取生产租户凭据」。

## E-4 模拟器可测矩阵（`edge/device-simulator/.../jiangyi/JiangyiDeviceSimulator.java`）

- 可测：token→getDetail→WS 建连(:182-211)、openDoor→锁/门 success→识别上报(:90-113 恒 success)、updateModel→classes 缓存→downloadModelNotify(:138-178)。
- 必须真机（模拟器盲区）：识别真实正确率、**心跳**（模拟器不发，:259 只认 openDoor/updateModel ⇒ 在线态测不真）、lock/door 的 **fail 分支**、uploadOrderError/大模型 doing、OSS 直传+uploadVideoUrl 分片、sendOpenStockDoor 补货回执、新设备激活、工控型号 "88" 校验、自动回锁时序。

## 轨道结论

将邑对接面**无 P0**；两个真实缺口为 R2（正常单视频回拉通道）与 confirmActivate（扩机激活通道），外加生产 env 模板缺段（F-E1）。其余属选型一致或可挂起。
