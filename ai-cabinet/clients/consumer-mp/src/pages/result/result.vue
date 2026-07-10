<template>
  <view>
    <view v-if="loading" class="card"><text class="meta">加载中…</text></view>
    <view v-else-if="error" class="card"><text class="err">{{ error }}</text></view>
    <view v-else-if="order">
      <view class="success-header">
        <text class="success-icon">✓</text>
        <text class="success-title">{{ order.totalAmountCents > 0 ? '购物完成' : '感谢使用' }}</text>
        <text class="success-status">{{ statusLabel }}</text>
      </view>

      <view class="card amount-card">
        <text class="amount-label">实付金额</text>
        <text class="amount">{{ fmtMoney(order.totalAmountCents) }}</text>
        <text v-if="order.totalAmountCents <= 0" class="zero-hint">本次未取走商品，未产生扣款</text>
      </view>

      <view class="card">
        <text class="section-title">商品明细</text>
        <view v-if="order.lines?.length">
          <view v-for="(line, i) in order.lines" :key="i" class="line">
            <text class="line-name">{{ line.skuName || line.skuId }} × {{ line.quantity }}</text>
            <text class="line-amt">{{ fmtMoney(line.lineAmountCents) }}</text>
          </view>
        </view>
        <text v-else class="empty-lines">未识别到取走商品</text>
      </view>

      <view class="footer-actions">
        <button class="action-btn" hover-class="btn-hover" @click="continueShop">继续在本柜购物</button>
        <button class="ghost-btn" hover-class="btn-hover" @click="goOrders">查看订单</button>
        <button v-if="sessionId && !disputeFiled" class="ghost-btn warn" hover-class="btn-hover" @click="openDispute">
          账单有疑问
        </button>
        <text v-else-if="disputeFiled" class="dispute-done">申诉已提交，请等待处理</text>
        <button class="ghost-btn subtle" hover-class="btn-hover" @click="goReport">柜机故障报修</button>
        <button class="ghost-btn subtle" hover-class="btn-hover" @click="goHome">回首页</button>
      </view>
    </view>

    <view v-if="showDispute" class="dispute-mask" @click="closeDispute">
      <view class="dispute-panel" @click.stop>
        <text class="dispute-title">账单申诉</text>
        <text class="dispute-sub">请描述您认为有误的地方，运营将在 24 小时内处理</text>
        <textarea
          v-model="disputeReason"
          class="dispute-input"
          maxlength="200"
          placeholder="例如：我没有拿这个商品 / 数量不对"
        />
        <button class="action-btn" hover-class="btn-hover" :loading="disputeLoading" @click="submitDispute">
          {{ disputeLoading ? '提交中…' : '提交申诉' }}
        </button>
        <text class="dispute-cancel" @click="closeDispute">取消</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onLoad } from '@dcloudio/uni-app';
import { ref } from 'vue';
import { consumerApi } from '@/utils/consumer-api';
import { fmtMoney, orderStatusLabel } from '@aicabinet/shared-uni/format';
import type { OrderDetailDto } from '@aicabinet/shared-types';

const loading = ref(true);
const error = ref('');
const order = ref<OrderDetailDto | null>(null);
const statusLabel = ref('');
let sessionId = '';
const deviceId = ref('');
const showDispute = ref(false);
const disputeReason = ref('');
const disputeLoading = ref(false);
const disputeFiled = ref(false);

onLoad((opts) => {
  sessionId = (opts?.sessionId as string) || '';
  const orderId = (opts?.orderId as string) || '';
  if (orderId) loadByOrderId(orderId);
  else if (sessionId) loadBySession(sessionId);
  else {
    error.value = '缺少会话或订单信息';
    loading.value = false;
  }
});

async function loadBySession(sid: string) {
  try {
    const sess = await consumerApi.getSession(sid);
    deviceId.value = sess.deviceId || '';
    order.value = await consumerApi.getSessionOrder(sid);
    statusLabel.value = orderStatusLabel(order.value?.status);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function loadByOrderId(oid: string) {
  try {
    order.value = await consumerApi.getOrder(oid);
    statusLabel.value = orderStatusLabel(order.value?.status);
    sessionId = order.value?.sessionId || sessionId;
    deviceId.value = order.value?.deviceId || deviceId.value;
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

function openDispute() {
  disputeReason.value = '';
  showDispute.value = true;
}

function closeDispute() {
  showDispute.value = false;
}

async function submitDispute() {
  const reason = disputeReason.value.trim();
  if (!sessionId) {
    uni.showToast({ title: '缺少会话信息', icon: 'none' });
    return;
  }
  if (reason.length < 4) {
    uni.showToast({ title: '请至少填写 4 个字', icon: 'none' });
    return;
  }
  disputeLoading.value = true;
  try {
    await consumerApi.fileDispute({
      sessionId,
      reason,
      category: 'USER_APPEAL',
      priority: 'NORMAL'
    });
    disputeFiled.value = true;
    showDispute.value = false;
    uni.showToast({ title: '申诉已提交', icon: 'success' });
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '提交失败', icon: 'none' });
  } finally {
    disputeLoading.value = false;
  }
}

function continueShop() {
  const id = deviceId.value || order.value?.deviceId;
  if (id) {
    uni.setStorageSync('reopen_device_id', id);
  }
  uni.switchTab({ url: '/pages/index/index' });
}

function goOrders() {
  uni.switchTab({ url: '/pages/orders/orders' });
}

function goReport() {
  const id = deviceId.value || order.value?.deviceId || '';
  uni.navigateTo({
    url: `/pages/report/report?deviceId=${encodeURIComponent(id)}`
  });
}

function goHome() {
  uni.switchTab({ url: '/pages/index/index' });
}
</script>

<style scoped>
.success-header { background: linear-gradient(135deg, #059669, #14b8a6); padding: 48rpx; text-align: center; color: #fff; }
.success-icon { width: 80rpx; height: 80rpx; border-radius: 50%; background: rgba(255,255,255,0.25); display: inline-flex; align-items: center; justify-content: center; font-size: 40rpx; font-weight: 700; }
.success-title { font-size: 36rpx; font-weight: 700; display: block; margin-top: 16rpx; }
.success-status { font-size: 26rpx; opacity: 0.9; display: block; margin-top: 4rpx; }
.amount-card { text-align: center; margin-top: -20rpx; position: relative; z-index: 1; }
.amount-label { font-size: 24rpx; color: #64748b; display: block; }
.amount { font-size: 56rpx; font-weight: 800; color: #059669; display: block; margin-top: 4rpx; }
.zero-hint { font-size: 24rpx; color: #888; display: block; margin-top: 12rpx; }
.section-title { font-weight: 600; display: block; margin-bottom: 12rpx; }
.line { display: flex; justify-content: space-between; padding: 12rpx 0; border-bottom: 1px solid #f1f5f9; }
.line-name { color: #1e293b; }
.line-amt { color: #07c160; font-weight: 600; }
.empty-lines { font-size: 26rpx; color: #888; }
.footer-actions { padding: 24rpx; display: flex; flex-direction: column; gap: 16rpx; }
.action-btn {
  margin: 0;
  height: 88rpx;
  line-height: 88rpx;
  background: #07c160;
  color: #fff;
  border-radius: 12rpx;
  font-size: 32rpx;
}
.action-btn::after { border: none; }
.ghost-btn {
  margin: 0;
  height: 88rpx;
  line-height: 88rpx;
  background: #fff;
  color: #576b95;
  border-radius: 12rpx;
  font-size: 30rpx;
}
.ghost-btn::after { border: none; }
.ghost-btn.warn { color: #d48806; border: 1rpx solid #ffd591; }
.ghost-btn.subtle { color: #999; font-size: 28rpx; }
.dispute-done { text-align: center; font-size: 26rpx; color: #07c160; padding: 8rpx 0; }
.btn-hover { opacity: 0.85; }
.err { color: #fa5151; }

.dispute-mask {
  position: fixed;
  inset: 0;
  background: rgba(0, 0, 0, 0.45);
  z-index: 300;
  display: flex;
  align-items: flex-end;
}
.dispute-panel {
  width: 100%;
  background: #fff;
  border-radius: 24rpx 24rpx 0 0;
  padding: 32rpx 32rpx calc(32rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.dispute-title { font-size: 34rpx; font-weight: 700; display: block; text-align: center; }
.dispute-sub { font-size: 26rpx; color: #888; display: block; text-align: center; margin: 12rpx 0 24rpx; }
.dispute-input {
  width: 100%;
  min-height: 180rpx;
  background: #f7f7f7;
  border-radius: 12rpx;
  padding: 20rpx;
  font-size: 28rpx;
  box-sizing: border-box;
  margin-bottom: 20rpx;
}
.dispute-cancel {
  display: block;
  text-align: center;
  color: #888;
  font-size: 28rpx;
  margin-top: 16rpx;
  padding: 12rpx;
}
</style>
