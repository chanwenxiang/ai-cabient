# 小程序上线前：隐私指引整改与发布清单（consumer-mp / merchant-mp）

> 依据：`docs/production-launch-checklist.md` P1「微信小程序完成主体认证、隐私协议、类目、域名白名单和审核发布」。
> 取证日期：2026-10-21

## 〇、两个 AppID 各自独立、内容不同

《用户隐私保护指引》按 AppID 配置，consumer-mp（`wx6dacefb3671de630`）与 merchant-mp（`wxf7de3625991a6431`）是独立账号，**各自填写、各自提交审核、互不影响**。两程序实际调用的接口不同，填的内容也不同（见第二节）：consumer 含手机号、不含位置；merchant 含位置、手机号走表单不勾接口项；两者均不含昵称头像。

## 一、被拒根因（consumer-mp，AppID `wx6dacefb3671de630`）

微信通知：小程序「前海易购科创智能设备开门柜」《用户隐私保护指引》审核不通过，原因【收集你的昵称、头像接口】说明内容不符合接口使用场景。

**根因：指引里声明了「昵称、头像」接口（`wx.getUserProfile` / `scope.userInfo`），但源码全仓未调用该接口。**

证据（均来自源码，非自报）：
- 全仓 grep `getUserProfile|getUserInfo|chooseAvatar|scope.userInfo` 无命中（含 `dist/` 构建产物）。
- consumer-mp 昵称 = 用户在「我的」页**手动输入**保存（`src/pages/mine/mine.vue:495` `onEditNickname` → `consumerApi.updateNickname`）；头像 = 昵称首字母占位（`mine.vue:480` `avatarText`）。
- 代码内《隐私政策》正文（`src/pages/policy/detail.vue:124-128`）也**未声明**昵称/头像，与实际一致——问题只在微信控制台指引多填了这项。
- merchant-mp 同理（`src/pages/mine/mine.vue:309` `avatarText` 为首字母占位），无 `getUserProfile` 调用。

→ 审核结论成立：声明了接口却没有对应真实调用 / 合理用途说明。

## 二、立即修复（在微信公众平台控制台操作，代码无需改动）

对 **consumer-mp** 该 AppID：
1. 微信公众平台 → 开发管理 → 用户隐私保护指引（或 设置 → 服务内容声明）→ 找到「收集你的昵称、头像」条目，**删除该条目**（本程序不调用 `getUserProfile`）。
2. 保留并补全真正使用的接口「接口用途说明」，务必写到具体业务场景、避免空话（审核会逐条核对）：
   - **手机号（getPhoneNumber）**：用于微信手机号一键登录/注册并绑定账号，支撑开门购物、结算与售后联系；仅在登录页点击「微信手机号快捷登录」按钮时调用。
   - **摄像头 / 相册（chooseImage）**：用于订单争议/异常时上传现场照片作为证据；仅用户主动提交时调用（`src/utils/dispute-evidence.ts:30` `safeChooseImage`）。
   - **扫码（scanCode）**：用于扫描柜机二维码完成开门、设备绑定与补货；仅对应业务页调用（`src/pages/index/index.vue:1485` `safeScanCode`）。
3. 保存 → 重新提交审核。

对 **merchant-mp**（AppID `wxf7de3625991a6431）自查：
- 检查指引是否同样误含「昵称、头像」条目（源码未调用 `getUserProfile`），若有一并删除。
- **位置（getLocation）** 已正确声明（`src/manifest.json:31` `requiredPrivateInfos:["getLocation"]` + 补货签到校验逻辑 `useReplenishmentFulfillment.ts`），保留并补全用途说明：「用于补货/盘点签到时校验补给人员是否到达柜机所在位置（需在约 N 米范围内），防止虚假签到；仅在签到/盘点页调用。」
- **摄像头 / 相册（chooseImage）**：用于补货、盘点时拍摄柜内/仓内照片上传作凭证（`device-stocktake.vue:72`、`warehouse-stocktake.vue:498`）。
- **扫码（scanCode）**：用于扫描柜机二维码完成设备绑定、补货与盘点（`useReplenishmentScan.ts:121`）。
- 手机号：merchant 走表单输入（`login.vue`），非 `getPhoneNumber` 接口，无需声明该接口；但隐私政策正文应列「登录手机号」。

## 三、两个小程序发布前还需完成

| # | 事项 | 证据 / 说明 | 状态 |
|---|------|-------------|------|
| 1 | 主体认证（企业） | 深圳前海易购科创智能设备有限公司，企业号 | 需确认 |
| 2 | 用户隐私保护指引过审 | consumer 本次被拒，已给修复方案（删昵称头像 + 补真实接口说明）；merchant 自查同项 | ❌ 待修 |
| 3 | 应用内《隐私政策》与指引口径一致 | consumer `detail.vue` 已较完整；merchant `privacy.vue` 偏简，未列摄像头/位置/扫码，建议补全以免与指引冲突 | ⚠️ merchant 待补 |
| 4 | 服务类目 | 需选对类目（智能柜 / 自助零售 / 经营工具类），否则 `getPhoneNumber`、摄像头等接口会被类目拦截 | 待确认 |
| 5 | request 合法域名白名单 | 生产 API、OSS/CDN 域名须加入 mp 控制台；当前 `manifest.json` `urlCheck:false` 仅开发态 | 待配置 |
| 6 | 隐私授权弹窗 | 两 MP 均已含 `privacy-consent-modal`（合规前置） | ✅ |
| 7 | 灰度 / 全量发布提交审核 | 指引过审后发布 | 待指引过审 |

## 四、关键提醒

- 《用户隐私保护指引》文本在**微信公众平台控制台**配置，不在仓库代码；代码内 `privacy` 页仅作应用内展示，二者口径必须一致（以实际调用接口为准）。
- 不要为了过审而补「昵称头像」的真实调用——本产品账户体系基于手机号 + OpenID，无需微信昵称/头像，**删条目即可**。
- 两 MP 实际采集面（须与指引一一对应）：
  - consumer：微信 OpenID、手机号、柜内录像（摄像头）、扫码。
  - merchant：手机号（表单）、位置（getLocation）、摄像头/相册、扫码。

## 五、控制台「使用用户信息类型」勾选对照（2026-10-21 核对）

微信「使用用户信息类型」勾选项须与真实调用**一一对应**，多勾=无关内容、漏勾=声明缺失，两种都可能被驳回。

### consumer-mp（`wx6dacefb3671de630`，被拒的这个）

| 勾选项 | 是否勾 | 依据 |
|---|---|---|
| 手机号 | ✅ 勾 | `getPhoneNumber` 一键登录（`src/pages/login/login.vue:41`）|
| 摄像头 | ✅ 勾 | `chooseImage` 默认含 camera 拍照路径（`safe-uni-call.ts:93`，未限制 sourceType）|
| 选中的照片或视频信息 | ✅ 勾 | `chooseImage` 从相册选图作争议证据（`src/utils/dispute-evidence.ts:30`）|
| 相册（仅写入）权限 | ❌ 不勾 | 全仓无 `saveImageToPhotosAlbum`，该权限对应「保存到相册」，本程序不用 |
| 用户信息（微信昵称…）| ❌ 不勾（保持）| 全仓无 `getUserProfile`；**这正是本次被拒项** |
| 位置 / 地址 / 麦克风 / 剪贴板 / 蓝牙 / 传感器等 | ❌ 不勾 | 无对应调用 |

> 截图里当前勾的是「摄像头 + 手机号 + 相册（仅写入）」：**把「相册（仅写入）」取消、改勾「选中的照片或视频信息」**即可，其余保持。
> 说明：`setClipboardData` 属写入剪贴板，不触发「剪贴板（读取）」声明；`scanCode`、`wx.login`（OpenID）不在本表用户信息类型内，无需勾选。

### merchant-mp（`wxf7de3625991a6431`）

| 勾选项 | 是否勾 | 依据 |
|---|---|---|
| 位置信息 | ✅ 勾 | `getLocation` 补货签到（`src/manifest.json:31` + `useReplenishmentFulfillment.ts`）|
| 摄像头 | ✅ 勾 | `chooseImage` camera（`device-stocktake.vue:72`）|
| 选中的照片或视频信息 | ✅ 勾 | `chooseImage` 相册（`warehouse-stocktake.vue:498` 等）|
| 手机号 | ❌ 不勾 | 登录为表单输入，未调 `getPhoneNumber`（多勾=无关内容）|
| 相册（仅写入）权限 | ❌ 不勾 | 无 `saveImageToPhotosAlbum` |
| 用户信息（微信昵称…）| ❌ 不勾 | 无 `getUserProfile` |

## 六、可直接粘贴的「用途说明」（consumer-mp）

> 审核看的是「用途」字段内容是否**具体、必要、与真实调用一致**。「登录绑定」「争议取证」这类两三个字的表述太笼统，容易二次驳回。建议按下表填写（写明：什么场景 + 为什么必需 + 何时触发）。

| 信息类型 | 建议用途说明（可直接粘贴） |
|---|---|
| 手机号 | 用于微信手机号一键登录并绑定购物账户，以便完成开门购物、订单结算与售后联系；仅在您于登录页点击手机号授权按钮时获取。 |
| 摄像头 | 用于您在订单争议或异常申诉时拍摄现场照片作为凭证；仅在您主动提交凭证时访问摄像头。 |
| 选中的照片或视频信息 | 用于您在订单争议或异常申诉时从相册选择照片上传作为凭证；仅在您主动选择照片时获取。 |

### merchant-mp 用途说明（可直接粘贴）

| 信息类型 | 用途说明 |
|---|---|
| 位置信息 | 用于补货签到与柜机盘点时采集您当前所在位置，校验您是否到达柜机所在位置（需在柜机配置的签到范围内）并记录盘点地点，以防止远程虚假签到、保障补货与库存数据真实；仅在您点击签到或提交盘点时获取。 |
| 摄像头 | 用于补货、盘点时拍摄柜内或仓内的现场照片并上传作为操作凭证；仅在您主动拍照上传时访问摄像头。 |
| 选中的照片或视频信息 | 用于补货、盘点及处理订单争议时从相册选择照片上传作为凭证；仅在您主动选择照片时获取。 |

依据：位置＝`useReplenishmentFulfillment.ts:126` 签到距离校验 + `device-stocktake.vue:131` 盘点定位；摄像头/照片＝`device-stocktake.vue:72`、`warehouse-stocktake.vue:498`、`useReplenishmentDetail.ts:224`。

### 模板条款 6.2 填空

「如开发者使用你的信息超出本指引目的或合理范围……再次以 ___ 方式告知并征得你的明示同意」→ 填 **弹窗**（或「弹窗提示」）。

依据：小程序端变更指引后微信会再次弹出隐私授权框；H5 端有隐私弹窗组件（`packages/shared-uni/src/privacy-consent.ts`）。不要填「公告」「短信」等无对应机制的表述。

## 七、两个小程序发布前剩余门禁（除隐私指引内容外）

### 7.1 微信平台侧（每个 AppID 各做一次）

- [ ] 隐私保护指引内容过审（进行中，见第二、六节）
- [x] **隐私授权接入（2026-10-21 已实现）**：取证更正——官方指南 §6 明确，未注册监听时微信**自动弹官方默认弹窗**兜底（同意→接口继续 / 拒绝→报错），并非「不接就被拦」；真正的拦截发生在「指引未声明对应信息类型」（errno 112 `api scope is not declared`）或提审误勾「未采集隐私」（`appid privacy api banned`）。已按官方推荐姿势接入：
  - 首屏主动提示：`wx.getPrivacySetting().needAuthorization` → 复用 `privacy-consent-modal`；mp 端同意按钮=官方 `open-type="agreePrivacyAuthorization"`，点击即由基础库向微信同步（未注册 onNeedPrivacyAuthorization，无需 resolve/buttonId，漏网场景官方弹窗兜底）。
  - consumer 一键登录按钮改官方耦合写法：`open-type="getPhoneNumber|agreePrivacyAuthorization"`（一次点击=同意隐私协议+授权手机号）。
  - 涉及文件：`packages/shared-uni/src/privacy-consent.ts`（新增 `isWeixinMpRuntime`/`shouldShowMpPrivacyConsent`）、`use-privacy-consent.ts`（门禁双端感知）、`components/privacy-consent-modal.vue`（mp 官方同意按钮）、`clients/consumer-mp/src/pages/login/login.vue`；merchant 两页复用同一 composable 自动生效。
  - 🔴 **上一版「H5 行为不变」的判断是错的，且随 4173228b 推到了 origin/dev**（CI run 933 e2e-h5 红才暴露）。根因：`isWeixinMpRuntime()` 首版只判 `globalThis.wx` 是否存在，而 **uni-app 的 H5 运行时本来就把 uni API 别名挂在 `globalThis.wx` 上**（本地 3002 dev server 实测：`typeof wx='object'`，键含 `$emit`/`addInterceptor`/`canvasGetImageData`…，**无** `getPrivacySetting`）⇒ H5 被判成 mp ⇒ `use-privacy-consent.ts` 走 mp 分支、`shouldShowMpPrivacyConsent()` 因缺 API 恒 false ⇒ 首屏隐私窗**永不渲染**；而 `request.ts:233` 的 H5 门禁照旧 reject ⇒ 未同意用户**没有任何同意入口**，全站请求死在客户端（consumer + merchant 两个 H5 的同款页面都中）。已改判据（`privacy-consent.ts:51-66`）：有 DOM(`globalThis.window`) 直接 false，其余还要求 `wx.getPrivacySetting` 是函数——两条排除彼此独立，误判方向从「H5 当 mp＝死锁」变成「老基础库不弹＝官方弹窗兜底」。mp 端行为逐项对齐：现代基础库仍判 true（不变）；<2.30.4 无该 API 时改判 false，但落到的 H5 分支因 mp 无 `window` 仍不弹，**结论与改前一致**。回归测试：`clients/consumer-mp/src/utils/privacy-consent-mp.test.ts` 新增「uni-app H5 的 wx 别名对象→false」「有 DOM 即使带 getPrivacySetting→false」两条（7 条全绿）。
  - 浏览器级取证（本地 `uni` dev server :3002，`localStorage.clear()` 后）：修前弹窗节点不存在且 `/api/v2/auth/captcha` **无任何网络请求**；修后 `[data-testid=privacy-consent-dialog]` 渲染、点「同意并继续」（无 `open-type`，走 v-else 本地分支）写入 `aicabinet_privacy_consent_v1=1`、切短信 Tab 后 `GET /api/v2/auth/captcha → 200` 真实发出。
  - 为什么单测/类型检查没拦住：首版测试的前提「无 wx（H5/admin）」是**臆断**——它只在 node/jsdom 里手工置 `g.wx = undefined`，从未按 uni-app H5 的真实全局对象建模；`vue-tsc` 只校验类型。判据类改动必须在**目标运行时**跑一次（本次靠 CI 的 Playwright H5 UAT 挣到）。
  - 误诊成本记录：UAT 抛的「图形验证码连续 6 次未取到 2xx（多为网关 auth_ratelimit 429 限流）」把方向指向 nginx，而 CI 的 e2e job **根本没有 nginx**（`VITE_DEV_PROXY` 直连 8080）。已把该文案改成按观测分流（429 命中次数 vs 根本没收到响应），并在 `dismissPrivacyConsent` 里把「弹窗缺席」改成必须校验本地同意标记，否则直接失败（[clients/consumer-mp/tests/consumer-h5-uat.mjs:265](clients/consumer-mp/tests/consumer-h5-uat.mjs)）。

  - 验证：两端 vitest 全绿（consumer 143 / merchant 175）+ 双端 `vue-tsc --noEmit` 通过 + prettier 通过；✅ 同日两端 `build:mp-weixin:dev` 全链实跑通过（validate→uni build→sync→patch→inject，命令**前置真 appid**——shell 残留假值 `wx5a5bc…` 仍会被 inject 读走，必须显式覆盖）。产物级取证：consumer `pages/login/login.wxml` 含 `getPhoneNumber|agreePrivacyAuthorization`、两端 `privacy-consent-modal.wxml` 含官方同意按钮、两端 `vendor.js` 含 `getPrivacySetting`；两端 dev `project.config.json` appid 已纠正为真值（merchant dev 原残留假值）。
  - 🔴 构建前置：`.env.production.local`（gitignored，`.gitignore:39`）须含 `VITE_API_BASE_URL=https://cabinet.easygovm.com`（生产入口，A→120.25.246.179 + certbot，见 `docs/PROJECT_KNOWLEDGE.md:694`）——两端该文件已建；缺它 validate-miniapp-env 直接 exit 1。
- [ ] **服务类目**：微信**没有**「智能柜/自助零售」类目。官方社区对「无人零售柜（零食、饮料）」的建议是 **生活服务 > 百货/超市/便利店**（前提：不涉及食品的在线交易及配送）。卖酒需另加「商家自营-酒类」（食品经营许可证含酒类）；卖保健品另加对应类目。类目选错会导致 `getPhoneNumber`、摄像头、`getLocation` 被类目权限拦截。
- [ ] **ICP 备案（必须）**：2023-09-01 起未备案的微信小程序不能上架；**每个小程序单独备案**，不能一个覆盖两个 AppID。前置＝先过微信认证（企业 ¥300/年）。流程：填信息 → 平台初审(1-2 工作日) → 工信部短信核验(12381，24h 内) → 通管局审核(1-20 工作日) → 备案成功；广东主体需打印资料签字盖章上传。
- [ ] **request 合法域名**另需**网站 ICP 备案**（域名级），与小程序备案是两件事。
- [ ] **request 合法域名白名单**：生产 API、OSS/CDN 域名须加进 mp 控制台；当前 `manifest.json` `urlCheck:false` 仅开发态，不能当生产配置。
- [ ] **微信认证 / 主体认证**（企业号，¥300/年）—— 确认已办理。
- [ ] **应用内《隐私政策》与指引口径一致**：consumer `detail.vue` 已完整；merchant `privacy.vue` 偏简，建议补摄像头 / 位置 / 扫码用途。
- [ ] 提交审核 → 发布。

### 7.2 真正「营业」的后端门禁（非小程序本身，但绕不开）

见 `docs/production-launch-checklist.md` P0：真实支付商户号（关 mock 支付）、真实柜机开门/门锁/心跳协议、将邑端侧视觉识别、日终对账与资金账本、RBAC 等。小程序过了审核只是「能下载」，不能真营业。
