# R18 审计：11-03/11-04 新改动真值回归（三端增量轮）

- 执行 AI / 日期：Delta R18 / 2026-11-04
- 代码基线：HEAD `b3ddd7ae`（并发会话期间又推进到 `adaa8919`，本轮只多两笔 docs：台账 + 本报告）
- 环境：本地 docker 全栈（admin `:18080/admin`、trade `:18080`、pg `:15433`）；消费端用微信开发者工具模拟器（`mp-weixin dist/build`，非 H5）
- 账号：超管 `13900000001`、消费端 `13800138000`（userId 10001）、商户端 `13800138001`
- 口径（用户拍板）：**新改动真值回归 + 只读（AUDIT- 造数后清理）+ 开发者工具元素级，只记录不修**

## 0. 本轮与前几轮的区别

R1–R17 已把「页面遍历 / 395 写端点静态审计 / 资金矩阵 / 跨端同数」跑完，照抄只会重复。本轮只打三件事：

1. 11-03～11-04 落地的 UI 统一 Phase 1–6（骨架屏、HelpTip、热区、语义色、深色）与营销批次 S/T/U 的**运行时真值**；
2. 判据必须能变红（每条都做负向对照），且**先自证探针本身**（本轮自证抓出 5 个探针自身假判，见 §5）；
3. 计划里明确记着「无法实证」的两条腿（弹窗关闭热区、附件删除热区）——本轮补上了弹窗那条。

## 1. 结论先行

| 端 | 覆盖面 | 结果 | 证据 |
|----|--------|------|------|
| admin（PC 浏览器） | 78 路由 × 2 视口 = 156 样本 + 10 页深色 | **0 缺陷**（18 处 TABLE_GAP 13px 经核为设计值，见 §4.1） | `.tmp/three-end-delta-20261104/admin-geometry.json` |
| 消费端小程序（模拟器） | 23/23 页元素级 | **0 缺陷**（1 处守卫重定向属设计） | `mp-elements-consumer.json` |
| 消费端专项 | 弹窗热区 + 关闭生效 + 打点入库；异常态与自愈 | **PASS**（此前「无法实证」的一条腿已挣） | `mp-popup-probe.json`、`mp-fault-probe.json` |
| 商户端小程序（模拟器） | — | **BLOCKED**（开发者工具 GUI 未能重启，见 §6） | — |
| 商户端（非 UI） | 8 个读接口真凭证 + 4 项静态门禁 | **PASS 8/8、门禁 4/4 绿** | `l3-merchant-api.json` |
| 活动位 L3 | 效果表 API ↔ 独立 SQL 复算 + 2 条负向 | **PASS**（2 行逐字段一致，负向均 0） | `l3-placement.json` |

新增缺陷 2 条，均 P3：**MA-155**（补货措辞残留「分仓出库」）、**MA-156**（按钮 sm/compact 变体 37px 且不在热区门禁覆盖面内）。

## 2. 基线取证（先证明「测的是哪份代码」）

- 运行栈下发的 admin 入口 chunk 与磁盘产物**完全一致**：`assets/index-B8kGfZsr.js` / `C5DxIUUy.css`（`curl /admin/index.html` vs `resources/static/admin/index.html`）。
- 两端小程序 `dist/build` **新于** 各自 `src` 最新改动（consumer build 11-04 12:39 > src 11-04 10:15；merchant build 11-04 09:36 > src 11-03 22:52）⇒ 结论描述的是当前源码，不是旧产物。
- 路由清单从源码解析：78 条，`routerOnly=[] / menuOnly=[]`（路由与菜单零漂移）。

## 3. 逐端结果

### 3.1 admin：78 路由 × 两视口（1440×900 / 1092×606）

- 0 导航错误、0 控制台报错页、0 接口 4xx/5xx 页。
- 布局判据（横向滚动 / 文本裁剪 / 块重叠 / 白屏 / 图片加载失败 / 骨架残留）全部零命中。
- 深色主题 10 页抽样：亮 `#fff/#303133` → 暗 `#1a2332/#e7ecf3`，success `#059669→#34d399`、danger `#dc2626→#f87171`，KPI 营收色跟随 `--app-primary`（`revenueFollowsPrimary=true`），10/10 `themeSwitched=true`、`darkBad=0`。
- 负向对照（证明判据不是恒绿）：注入「body 撑宽 320px + 可见骨架块」后，`hscroll 0→320`、`skeletonStuck 0→1`，`discriminating=true`。

### 3.2 消费端小程序：23 页元素级

- 23/23 页可达，`navErrors=0`、`emptyPages=0`、`skeletonStuck=0`、`heatSmall=0`。
- 全量首跑记了 2 条 `STYLE_RESET .btn-slot=rgba(0,0,0,0)`，经查是探针把插槽容器当按钮（§5 #2）⇒ **假红**，用修正后的组件作用域判据复跑 4 页为 0 条，故按 0 缺陷计。
- 唯一 `guardRedirect` 是 `pages/login` → `pages/index`（已登录态被守卫弹回），属设计；负向对照「访问不存在的页面必须被识别」成立。
- **加载态硬证**：冻结订单列表请求后 `.sk-card` 出现 5 块（首页块 366×322），`restoreWxMethod` 后同页 `0` 块 ⇒ `discriminating=true`（既证明骨架屏真的会出，也证明「骨架残留」判据不是恒假）。

### 3.3 消费端专项：弹窗热区（补上计划里「无法实证」的一条腿）

`promotion_placement` 现有 1 条 `POPUP_BEFORE_OPEN` 投放，实测链路：

| 断言 | 实测 |
|------|------|
| 投放位下发到 UI | `.promo-popup-close` 命中 1（`.promo-popup-card/.promo-popup-mask/.promo-popup-img` 同时在树） |
| 关闭按钮本体 | 24×24px（=48rpx） |
| 热区扩展 | `::after{top/right/bottom/left:-22rpx}` ⇒ 有效命中区 **≈47px** ≥ 88rpx 折算 45.76px ⇒ `hitOk=true` |
| 点击真的能关 | 点后 `.promo-popup-close` 1→0，且 1.5s 后仍为 0（不是闪一下又弹回） |
| 打点真的入库 | 弹出即写 `ad_play_event`（POPUP_BEFORE_OPEN / IMPRESSION / user 10001 / activity 11）；**探针产生的这 1 行已按 id 删除并断言归零（4→3）** |

结论：`.promo-popup-close` 的「小视觉 + 伪元素大热区」写法在真机模拟器上成立，Phase 4 那条存疑关闭。

### 3.4 消费端专项：异常态与自愈

后端返回 500 时，订单页进 `加载失败` 空态 + **2 个重试入口**（扫码购物 / 重试），无骨架残留；恢复请求后同页 252 个节点、`.order-tag` 125 个正常回来。

### 3.5 商户端：L3 接口 + 静态门禁（UI 线 BLOCKED）

- 8 个读接口全部 `200 / code 0 / 数据非空`，耗时 28–89ms：`me`、`stats`、`devices(3)`、`orders`、`wallet(9 keys)`、`disputes`、`settlements/overview`、`analytics/overview(16 keys)`。
- 静态门禁 4/4 绿：热区棘轮 `0/0`、WXSS 令牌兜底（95 令牌全覆盖）、缺陷类 17 类零命中（269 文件，自检 56 例）、emoji 基线 0。

## 4. 判为「非缺陷」的三处（附判据）

1. **admin 18 处 `TABLE_GAP 13px`**：全部 13px 且跨 9 个路由两视口一致，来源是「表下 12px 间距 token + 1px 边框」，非错位；判据阈值我定得太紧（>12px 即报），已记为判据噪音而非缺陷。
2. **消费端 `pages/login` 守卫重定向**：已登录访问登录页被弹回首页，符合设计；负向对照证明该判据能识别真正的「落点错」。
3. **弹窗关闭不写 CLICK 事件**：`reportPopup` 只在「展示」记 IMPRESSION、在「点 CTA」记 CLICK，关闭本身不单独记事件。属事件模型设计（关闭率由 IMPRESSION − CLICK 推得），不作缺陷。

## 5. 探针自证：本轮抓出的 5 个「判据自身假判」（比缺陷更值得留）

| # | 假判 | 后果 | 修法 |
|---|------|------|------|
| 1 | 小程序 KEY_SELECTORS 按常见命名猜（`.app-button`/`.btn-primary`/`.mine-quick-item`） | 首轮 23 页 `counts` 全 0，看似「页面全空」的**假绿** | 词表改为从 `dist/build` 的 WXML/WXSS 实测提取（`.app-btn`/`.card`/`.order-tag`/`.sk-card`…） |
| 2 | 把 `.btn-slot`（插槽容器）当按钮查背景色 | 透明背景=正常，被报 `STYLE_RESET` | 改为组件作用域查 `app-button` 内 `.app-btn`，且「全部按钮皆透明」才算异常 |
| 3 | 弹窗热区正则只认 `.X::after{inset:-}` | 产物选择器带 `.data-v-xxx` 且用四向长写 ⇒ 误判「无热区扩展」（24px 看着像违规） | 正则认 scoped 后缀 + 四向长写；实测 47px 合规 |
| 4 | 自造 Promise 的 `mockWxMethod` 调 `o.success({statusCode:500})` | 请求悬挂 ⇒ 页面停在骨架屏，差点记成「错态缺失」的重缺陷 | 改用静态 mock 返回值（项目已验证形态）⇒ 正确出「加载失败 + 重试」 |
| 5 | `locator().innerText()` 未给超时 | 无标题页逐页空等 30s，全量跑到 1/4 就超时 | 显式 `{timeout:1500}`，整轮 156 样本降到 6 分钟 |

留档理由：这 5 条里有 4 条会产出**看起来很像缺陷的假红/假绿**，与铁律 4/13「判不是缺陷也要证据」同源。

## 6. 覆盖缺口（如实标注，不当 PASS）

- **商户端小程序元素级：BLOCKED**。我在切换两端时执行了 `cli quit`，之后开发者工具 GUI 无法在当前沙箱重启（启动流程调用的 `reg.exe` 被安全策略拦截，`gui_proc=0`）。已完成的替代证据：商户端 8 接口 L3 + 4 项静态门禁；**不能**替代元素级结论。
- `evidence-del`（申诉附件删除热区）未实证：需要先有带附件的申诉单，`dispute_message` 当前 0 行。修法上它与已验证的 `.promo-popup-close` 同一写法（本体小 + `::after` 负 inset），且 wxss 规则已在产物中核对存在；仍按「未实证」记。
- 深色主题只抽 10 页（改成全量的成本高、且暗色令牌已由 `check:ui-barehex-ratchet` 覆盖）。
- 1092×606 只代表窄桌面，不代表 iPad/平板断点。

## 7. 需用户拍板

1. **MA-156**：`app-button` 的 sm/compact（72rpx）是要「补 `::after` 热区扩展到 88rpx」，还是承认「紧凑按钮是设计选择、门禁白名单化」？两者都合理，但**门禁必须与选择一致**，否则同类会继续在覆盖盲区里增长。
2. **MA-155** 措辞：导航描述改「直接生成领货单与补货任务」，还是保留「分仓出库」（若业务上确实有分仓出库单这一实体，则铁律 80 的措辞口径需要再确认边界）。

## 8. 复跑方式

```bash
# admin（156 样本，约 6 分钟）
PW=$(docker exec ai-cabinet-redis-1 printenv REDIS_PASSWORD)
MSYS_NO_PATHCONV=1 REDIS_HOST=127.0.0.1 REDIS_PASSWORD="$PW" ADMIN_USER=13900000001 ADMIN_PASS=123456 \
  VIEWPORTS=1440x900,1092x606 SETTLE_MS=650 LOAD_WAIT_MS=1800 \
  node .tmp/three-end-delta-20261104/admin-geometry.mjs

# 消费端（需开发者工具已开该工程 + auto 端口）
MSYS_NO_PATHCONV=1 MP_END=consumer AUTO_PORT=9451 node .tmp/three-end-delta-20261104/mp-elements.mjs
node .tmp/three-end-delta-20261104/mp-popup-probe.mjs
node .tmp/three-end-delta-20261104/mp-fault-probe.mjs

# 商户端 / 活动位 L3
node .tmp/three-end-delta-20261104/l3-merchant-api.mjs
node .tmp/three-end-delta-20261104/l3-placement.mjs
```
