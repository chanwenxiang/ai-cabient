# 消费者小程序代码错误与问题详细清单

## 一、具体代码错误

### 1. 开门流程问题 (index.vue)

#### 问题1: 开门超时无处理
**文件**: clients/consumer-mp/src/pages/index/index.vue
**代码位置**: 第350-400行
**问题代码**:
`javascript
async function startShoppingFlow(id: string, scanChannel?: string | null) {
  // ...
  const sessionResult = await consumerApi.createSession(cabinetId, entryChannel.value)
  // 没有超时处理
}
`
**问题**: createSession API调用没有设置超时，网络慢时用户一直等待
**影响**: 用户界面卡住，无反馈

#### 问题2: 扫码二维码解析无校验
**文件**: index.vue
**代码位置**: 第550-560行
**问题代码**:
`javascript
function onScan() {
  uni.scanCode({
    success: (res) => {
      const parsed = parseCabinetScan(res.result);
      startShoppingFlow(parsed.deviceId, parsed.channel);
      // parsed.deviceId 可能为空或无效
    }
  });
}
`
**问题**: 扫描非柜机二维码时，parsed.deviceId可能为空，但直接调用startShoppingFlow
**影响**: 可能导致无效请求或崩溃

#### 问题3: 开门失败后状态未重置
**文件**: index.vue
**代码位置**: 第360-380行
**问题代码**:
`javascript
if (sessionResult.status !== 'fulfilled') {
  scanned.value = false;
  deviceId.value = '';
  landingError.value = formatError(sessionResult.reason);
  // 没有重置 opening.value = false
  return;
}
`
**问题**: opening.value在finally中重置，但如果在此之前return，可能状态不一致
**影响**: 按钮可能保持禁用状态

#### 问题4: 设备离线检测在开门后
**文件**: index.vue
**代码位置**: 第365-375行
**问题代码**:
`javascript
await refreshDeviceStatus();
if (deviceOffline.value) {
  // 设备离线提示
  return;
}
// 然后才调用 createSession
`
**问题**: 先调用efreshDeviceStatus检测状态，但设备状态可能有延迟
**影响**: 用户等了很长时间才被告知设备离线

---

### 2. 支付/充值流程问题 (recharge.vue + recharge.ts)

#### 问题5: Mock充值无限制
**文件**: clients/consumer-mp/src/pages/recharge/recharge.vue
**代码位置**: 第76-80行
**问题代码**:
`ue
<button v-if="mockEnabled" class="btn-primary" @click="onRecharge">
  {{ loading ? '充值中…' : '模拟到账 ¥' + (selectedAmount / 100).toFixed(0) }}
</button>
`
**问题**: mockEnabled默认为true，生产环境也能看到Mock充值按钮
**影响**: 用户可能误用测试功能

#### 问题6: 充值待处理订单清理不完整
**文件**: clients/consumer-mp/src/utils/recharge.ts
**代码位置**: 第150-180行
**问题代码**:
`javascript
export async function resumePendingRechargeIfAny(): Promise<boolean> {
  const orderId = peekPendingRechargeOrder();
  if (!orderId) return false;
  try {
    await pollRechargePaid(orderId, 8, 1500);
    clearPendingRechargeOrder();
    return true;
  } catch (e) {
    // 超时不清理，保留 pending
    if (/超时|timeout/i.test(msg)) {
      return false; // 不清理
    }
  }
}
`
**问题**: 网络超时后保留pending订单，但下次启动仍会轮询，可能累积多个
**影响**: 用户每次打开都看到错误提示

#### 问题7: 支付宝沙箱返回处理
**文件**: echarge.ts
**代码位置**: 第100-130行
**问题代码**:
`javascript
export function redirectIfAlipayReturn(): boolean {
  // 检测支付宝返回参数
  const fromAlipay = /[?&]out_trade_no=/.test(combined);
  if (!fromAlipay) return false;
  uni.reLaunch({ url });
  return true;
}
`
**问题**: 只在H5环境下处理，小程序环境无法正确处理支付宝返回
**影响**: 支付宝支付后可能无法正确跳转

---

### 3. 登录认证问题 (login.vue)

#### 问题8: 验证码固定为123456
**文件**: clients/consumer-mp/src/pages/login/login.vue
**代码位置**: 后端AuthService配置
**问题**: 本地环境验证码固定为123456，但前端无提示
**影响**: 安全漏洞

#### 问题9: 密码登录无加密
**文件**: login.vue
**代码位置**: 第180-200行
**问题代码**:
`javascript
async function onLogin() {
  // ...
  const data = await consumerPasswordLogin(phoneNum, password.value);
  // 密码明文传输
}
`
**问题**: 密码直接明文传输到后端
**影响**: 中间人攻击可窃取密码

---

### 4. 订单与争议问题 (orders.vue + result.vue)

#### 问题10: 争议提交无图片上传
**文件**: clients/consumer-mp/src/pages/result/result.vue
**代码位置**: 第140-200行
**问题代码**:
`ue
<textarea v-model="disputeReason" placeholder="例如：我没有拿这个商品 / 数量不对" />
<!-- 只有文本输入，无图片上传 -->
`
**问题**: 争议提交只能填写文字，无法上传凭证图片
**影响**: 争议处理效率低，用户证据不足

#### 问题11: 订单筛选不保存状态
**文件**: orders.vue
**代码位置**: 第100-130行
**问题**: 用户切换筛选条件后，返回页面状态丢失
**影响**: 用户体验差

#### 问题12: 订单详情无商品图片
**文件**: order-detail.vue
**代码位置**: 第30-50行
**问题代码**:
`ue
<view class="item-info">
  <text class="item-name">{{ item.skuName }}</text>
  <text class="item-qty">x{{ item.quantity }}</text>
</view>
<!-- 无商品图片显示 -->
`
**问题**: 订单详情页只显示商品名称，无商品图片
**影响**: 用户难以确认商品

---

### 5. Token和会话管理问题 (consumer-api.ts)

#### 问题13: Token刷新竞态条件
**文件**: clients/consumer-mp/src/utils/consumer-api.ts
**代码位置**: 第70-100行
**问题代码**:
`javascript
let refreshInFlight: Promise<boolean> | null = null;

async function refreshTokenSilently(): Promise<boolean> {
  if (refreshInFlight) return refreshInFlight;
  // ...
  refreshInFlight = pending;
  return pending;
}
`
**问题**: 多个请求同时401时，可能触发多次刷新
**影响**: 第一个刷新成功后，其他请求的token已失效

#### 问题14: localStorage存储敏感信息
**文件**: consumer-api.ts
**代码位置**: 第30-40行
**问题代码**:
`javascript
const TOKEN_KEY = 'consumer_token';
function applyTokenSession(data: LoginResponse) {
  uni.setStorageSync(TOKEN_KEY, data.token);
  // Token直接存储在localStorage
}
`
**问题**: Token明文存储在localStorage，可被XSS攻击窃取
**影响**: 安全风险

---

### 6. 会员与积分问题 (member/index.vue)

#### 问题15: 积分过期无提醒
**文件**: clients/consumer-mp/src/pages/member/index.vue
**问题**: 积分有过期时间，但页面无过期提醒
**影响**: 用户积分可能过期而不知

#### 问题16: 等级升级无通知
**文件**: member/index.vue
**问题**: 用户消费达到升级条件后，无升级通知/动画
**影响**: 用户对会员体系感知弱

---

## 二、UI/交互错误

### 1. 文案错误

| 问题 | 文件 | 行号 | 错误文案 | 正确文案 |
|------|------|------|----------|----------|
| UI-001 | mine.vue | 10 | "测试余额" | "余额" |
| UI-002 | recharge.vue | 76 | "模拟到账" | 生产环境应隐藏 |
| UI-003 | open-prep-drawer.vue | 82 | "模拟充值 ¥20 测试余额（兜底）" | 生产环境应隐藏 |

### 2. 按钮问题

| 问题 | 文件 | 说明 |
|------|------|------|
| BTN-001 | index.vue:650 | 开门中界面无取消按钮 |
| BTN-002 | result.vue:100 | "账单有疑问"按钮应更明显 |
| BTN-003 | orders.vue:105 | "柜机有问题？故障报修"链接不够明显 |

### 3. 空状态问题

| 问题 | 文件 | 说明 |
|------|------|------|
| EMPTY-001 | orders.vue | 空订单无引导操作 |
| EMPTY-002 | coupons.vue | 无优惠券时提示不够友好 |
| EMPTY-003 | member/index.vue | 无积分时无引导获取 |

---

## 三、缺失功能详细清单

### 1. 核心购物功能缺失

| 功能 | 竞品对比 | 影响用户 | 实现难度 |
|------|----------|----------|----------|
| **退款申请入口** | 便利蜂/考拉订单详情有"申请退款" | 高 | 中 |
| **商品搜索** | 便利蜂首页有搜索框 | 高 | 低 |
| **订单评价** | 考拉订单详情有评价入口 | 中 | 低 |
| **收藏商品/柜机** | 便利蜂可收藏 | 中 | 中 |
| **一键复购** | 考拉订单详情有复购按钮 | 中 | 低 |

### 2. 用户服务功能缺失

| 功能 | 竞品对比 | 影响用户 | 实现难度 |
|------|----------|----------|----------|
| **在线客服** | 便利蜂有在线聊天入口 | 高 | 高(需接入IM) |
| **FAQ常见问题** | 考拉有FAQ页面 | 中 | 低 |
| **发票申请** | 便利蜂可申请电子发票 | 高 | 中 |
| **账户注销** | 竞品均有注销入口 | 低 | 低 |
| **多账户绑定** | 考拉可绑定微信/支付宝 | 中 | 中 |

### 3. 营销互动功能缺失

| 功能 | 竞品对比 | 影响用户 | 实现难度 |
|------|----------|----------|----------|
| **新人礼包** | 便利蜂新用户有礼包 | 高 | 中 |
| **每日签到** | 考拉有签到送积分 | 中 | 低 |
| **邀请有礼** | 便利蜂有邀请奖励 | 中 | 中 |
| **积分抽奖** | 考拉有积分抽奖 | 低 | 中 |
| **会员日活动** | 便利蜂有固定会员日 | 中 | 中 |

### 4. 消息通知功能缺失

| 功能 | 竞品对比 | 影响用户 | 实现难度 |
|------|----------|----------|----------|
| **订单状态推送** | 便利蜂有支付/退款推送 | 高 | 中(需配置) |
| **消息中心** | 考拉有消息列表页 | 高 | 中 |
| **营销消息推送** | 便利蜂有活动推送 | 中 | 中 |

### 5. 地图与定位功能缺失

| 功能 | 竞品对比 | 影响用户 | 实现难度 |
|------|----------|----------|----------|
| **附近柜机地图** | 便利蜂有地图页 | 高 | 高 |
| **柜机导航** | 便利蜂可导航到柜机 | 中 | 中 |
| **定位推荐** | 便利蜂根据位置推荐柜机 | 中 | 中 |

---

## 四、边界情况处理缺失

| 场景 | 当前处理 | 应有处理 |
|------|----------|----------|
| 网络断开 | 无处理 | 自动重试 + 提示 |
| 小程序切后台 | 状态丢失 | 恢复后自动刷新 |
| 支付超时 | 订单卡住 | 自动取消 + 提示 |
| 设备离线 | 等待后提示 | 实时检测 + 友好提示 |
| Token过期 | 刷新失败后跳转登录 | 无感刷新 |
| 扫码非柜机二维码 | 可能崩溃 | 友好错误提示 |

---

## 五、性能问题

| 问题 | 文件 | 说明 |
|------|------|------|
| PERF-001 | index.vue | 商品列表无分页，一次加载全部 |
| PERF-002 | orders.vue | 订单列表无虚拟滚动，数据多时卡顿 |
| PERF-003 | 多处 | 图片无懒加载 |
| PERF-004 | 多处 | 无请求防抖节流，可能重复请求 |
| PERF-005 | index.vue | 状态轮询间隔固定，无动态调整 |

---

## 六、安全风险

| 风险 | 文件 | 说明 | 风险级别 |
|------|------|------|----------|
| SEC-001 | recharge.vue | Mock充值可无限操作 | 高 |
| SEC-002 | 后端 | 验证码固定123456 | 高 |
| SEC-003 | consumer-api.ts | Token明文存储 | 中 |
| SEC-004 | login.vue | 密码明文传输 | 中 |
| SEC-005 | 多处 | 敏感接口无防刷限制 | 中 |

---

## 七、修复优先级排序

### P0 立即修复
1. 隐藏Mock充值入口（环境变量控制）
2. 修复验证码安全问题（接入短信服务）
3. 添加退款申请入口

### P1 本周修复
1. 完善开门流程错误处理和用户引导
2. 完善支付失败提示
3. 添加订单状态推送
4. 添加在线客服入口

### P2 短期修复
1. 新人礼包功能
2. 签到送积分
3. 商品搜索
4. 附近柜机地图

### P3 中期修复
1. 性能优化（分页、懒加载）
2. 邀请有礼功能
3. 积分抽奖
4. 账户注销功能

---

**文档状态**: 完成
**问题总数**: 50+
**下一步**: 根据优先级逐项修复
