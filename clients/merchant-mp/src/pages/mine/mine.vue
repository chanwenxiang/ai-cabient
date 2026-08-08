<template>
  <view class="page">
    <view class="profile-header">
      <view class="avatar">{{ avatarText }}</view>
      <view class="profile-info">
        <text class="hello">{{ meName }}</text>
        <text class="sub">{{ merchantNames }}</text>
        <text v-if="phone" class="phone">{{ phone }}</text>
      </view>
    </view>

    <view v-if="fieldNav.length" class="section-label">现场作业</view>
    <view v-if="fieldNav.length" class="menu-list">
      <view
        v-for="item in fieldNav"
        :key="item.key"
        class="menu-cell"
        :class="{ highlight: item.key === 'replenishment' }"
        @click="goNav(item)"
      >
        <text class="menu-icon">{{ item.icon }}</text>
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view v-if="teamNav.length" class="section-label">团队与设置</view>
    <view v-if="teamNav.length" class="menu-list">
      <view v-for="item in teamNav" :key="item.key" class="menu-cell" @click="goNav(item)">
        <text class="menu-icon">{{ item.icon }}</text>
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="section-label">平台公告</view>
    <view class="menu-list">
      <view class="menu-cell" @click="goAnnouncements">
        <text class="menu-icon">告</text>
        <view class="menu-text">
          <text class="menu-title">通知公告</text>
          <text class="menu-desc">运营发布的维护、活动与规则通知</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view v-if="canAlerts" class="section-label">消息提醒</view>
    <view v-if="canAlerts" class="menu-list notify-card">
      <view class="notify-head">
        <view class="menu-text">
          <text class="menu-title">微信订阅提醒</text>
          <text class="menu-desc">{{ notifyDesc }}</text>
        </view>
        <button
          class="bind-btn"
          size="mini"
          :loading="notifyBusy"
          :disabled="!subscribeReady"
          @click="onBindWx"
        >
          {{ wxBound ? '重新绑定' : '开启提醒' }}
        </button>
      </view>
      <view v-if="!subscribeReady" class="notify-warn"
        >未配置订阅消息模板（VITE_WX_SUBSCRIBE_TMPL_IDS），当前仅可保存偏好，无法向微信申请推送授权。</view
      >
      <view class="notify-types">
        <label v-for="t in alertTypeOptions" :key="t.value" class="notify-type">
          <switch
            :checked="enabledTypes.includes(t.value)"
            color="#0f766e"
            @change="(e) => onToggleType(t.value, !!e.detail.value)"
          />
          <text>{{ t.label }}</text>
        </label>
      </view>
      <button class="save-btn" :loading="notifyBusy" @click="onSaveSubscribe">保存提醒偏好</button>
    </view>

    <view v-if="bizNav.length" class="section-label">经营工具</view>
    <view v-if="bizNav.length" class="menu-list">
      <view v-for="item in bizNav" :key="item.key" class="menu-cell" @click="goNav(item)">
        <text class="menu-icon">{{ item.icon }}</text>
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="menu-list">
      <view class="menu-cell danger-cell" @click="onLogout">
        <text class="menu-icon">退</text>
        <view class="menu-text">
          <text class="menu-title danger">退出登录</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { clearSession, merchantApi } from '@/utils/merchant-api';
import {
  hasSubscribeTemplates,
  MERCHANT_ALERT_TYPES,
  requestMerchantSubscribe,
  wxLoginCode
} from '@/utils/notify';
import { canAccessNav, useMerchantMe } from '@/composables/useMerchantMe';
import {
  MERCHANT_BIZ_NAV,
  MERCHANT_FIELD_NAV,
  MERCHANT_TEAM_NAV,
  type MerchantNavItem
} from '@/config/merchant-nav';
import type { MerchantMe } from '@aicabinet/shared-types';
import { formatMerchantNames } from '@/utils/merchant-display';

const { me, refresh: refreshMe } = useMerchantMe();
const meName = ref('');
const merchantNames = ref('');
const phone = ref('');
const avatarText = computed(() => (meName.value || '商').slice(0, 1));
const notifyBusy = ref(false);
const wxBound = ref(false);
const enabledTypes = ref<string[]>([]);
const alertTypeOptions = MERCHANT_ALERT_TYPES;
const subscribeReady = hasSubscribeTemplates();
const notifyDesc = computed(() => {
  if (!subscribeReady) return '未配置订阅模板，偏好可保存但无法申请微信推送授权';
  if (wxBound.value) return '已绑定微信，可接收待办推送';
  return '绑定微信后可接收待办推送';
});

const fieldNav = computed(() => MERCHANT_FIELD_NAV.filter((i) => canAccessNav(me.value, i)));
const bizNav = computed(() => MERCHANT_BIZ_NAV.filter((i) => canAccessNav(me.value, i)));
const teamNav = computed(() => MERCHANT_TEAM_NAV.filter((i) => canAccessNav(me.value, i)));
const canAlerts = computed(() => fieldNav.value.some((i) => i.key === 'alerts'));

function goNav(item: MerchantNavItem) {
  if (item.tab) {
    uni.switchTab({ url: item.url });
    return;
  }
  uni.navigateTo({ url: item.url });
}

function goAnnouncements() {
  uni.navigateTo({ url: '/pages/announcements/announcements' });
}

async function loadNotifyPrefs() {
  if (!canAlerts.value) return;
  try {
    const prefs = await merchantApi.notifyPrefs();
    wxBound.value = !!prefs.wxBound;
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
  } catch {
    /* ignore — page still usable */
  }
}

onShow(async () => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
  }
  const profile = me.value || ((uni.getStorageSync('merchant_me') || {}) as MerchantMe);
  meName.value = profile.displayName || profile.phoneNumber || '商户';
  merchantNames.value = formatMerchantNames(profile.merchants, '未绑定');
  phone.value = profile.phoneNumber || '';
  await loadNotifyPrefs();
});

function onToggleType(type: string, on: boolean) {
  const set = new Set(enabledTypes.value);
  if (on) set.add(type);
  else set.delete(type);
  enabledTypes.value = [...set];
}

async function onBindWx() {
  if (!subscribeReady) {
    uni.showToast({ title: '未配置订阅模板，无法开启推送', icon: 'none' });
    return;
  }
  notifyBusy.value = true;
  try {
    const sub = await requestMerchantSubscribe();
    if (sub === 'failed') {
      uni.showToast({ title: '微信授权未完成，仍可继续绑定账号', icon: 'none' });
    }
    const code = await wxLoginCode();
    const prefs = await merchantApi.notifyWxBind(code);
    wxBound.value = !!prefs.wxBound;
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    uni.showToast({ title: '已绑定微信提醒', icon: 'success' });
  } catch (e) {
    uni.showToast({
      title: e instanceof Error ? e.message : '绑定失败',
      icon: 'none'
    });
  } finally {
    notifyBusy.value = false;
  }
}

async function onSaveSubscribe() {
  notifyBusy.value = true;
  try {
    if (subscribeReady) {
      const sub = await requestMerchantSubscribe();
      if (sub === 'failed') {
        uni.showToast({ title: '微信授权未完成，偏好仍会保存', icon: 'none' });
      }
    }
    const prefs = await merchantApi.notifySubscribe(enabledTypes.value);
    enabledTypes.value = [...(prefs.enabledAlertTypes || [])];
    uni.showToast({
      title: subscribeReady ? '提醒偏好已保存' : '偏好已保存（未配置推送模板）',
      icon: 'success'
    });
  } catch (e) {
    uni.showToast({
      title: e instanceof Error ? e.message : '保存失败',
      icon: 'none'
    });
  } finally {
    notifyBusy.value = false;
  }
}

function onLogout() {
  uni.showModal({
    title: '退出登录',
    content: '确定退出当前账户吗？',
    confirmText: '退出',
    success(res) {
      if (!res.confirm) return;
      clearSession();
      uni.reLaunch({ url: '/pages/login/login' });
    }
  });
}
</script>

<style scoped>
.page {
  min-height: 100%;
  padding-bottom: calc(40rpx + env(safe-area-inset-bottom));
  background: linear-gradient(180deg, #ecfdf5 0, #f0fdfa 280rpx, #f0fdfa 100%);
}
.profile-header {
  margin: 20rpx 24rpx 0;
  padding: 40rpx 32rpx;
  border-radius: 28rpx;
  display: flex;
  align-items: center;
  gap: 24rpx;
  background: linear-gradient(145deg, #134e4a, #0f766e 60%, #14b8a6);
  box-shadow: 0 16rpx 40rpx rgba(15, 118, 110, 0.2);
  color: #fff;
}
.avatar {
  width: 100rpx;
  height: 100rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  border: 2rpx solid rgba(255, 255, 255, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 44rpx;
  font-weight: 700;
}
.profile-info {
  flex: 1;
  min-width: 0;
}
.hello {
  font-size: 36rpx;
  font-weight: 700;
  display: block;
}
.sub {
  font-size: 26rpx;
  opacity: 0.9;
  display: block;
  margin-top: 4rpx;
}
.phone {
  font-size: 24rpx;
  opacity: 0.75;
  display: block;
  margin-top: 4rpx;
}
.section-label {
  margin: 28rpx 32rpx 10rpx;
  font-size: 22rpx;
  color: #94a3b8;
  letter-spacing: 1rpx;
}
.menu-list {
  margin: 0 24rpx;
}
.menu-cell {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx 24rpx;
  margin-bottom: 12rpx;
  display: flex;
  align-items: center;
  gap: 20rpx;
  border: 1rpx solid #e2e8f0;
  box-shadow: 0 6rpx 18rpx rgba(15, 118, 110, 0.05);
}
.menu-cell.highlight {
  border-color: #99f6e4;
  background: linear-gradient(90deg, #fff, #f0fdfa);
}
.menu-icon {
  width: 72rpx;
  height: 72rpx;
  border-radius: 18rpx;
  background: #f0fdfa;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34rpx;
  color: #0f766e;
  font-weight: 700;
}
.menu-text {
  flex: 1;
  min-width: 0;
}
.menu-title {
  font-size: 30rpx;
  font-weight: 600;
  display: block;
  color: #1e293b;
}
.menu-desc {
  font-size: 24rpx;
  color: #94a3b8;
  display: block;
  margin-top: 4rpx;
}
.menu-arrow {
  color: #cbd5e1;
  font-size: 36rpx;
}
.notify-card {
  background: #fff;
  border-radius: 20rpx;
  padding: 24rpx;
  margin: 0 24rpx 12rpx;
}
.notify-head {
  display: flex;
  align-items: flex-start;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.bind-btn {
  flex-shrink: 0;
  background: #ecfdf5;
  color: #0f766e;
  border: none;
  font-size: 22rpx;
}
.notify-warn {
  margin-bottom: 16rpx;
  padding: 12rpx 16rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: #0f766e;
  font-size: 22rpx;
  line-height: 1.4;
}
.notify-types {
  display: grid;
  gap: 12rpx;
}
.notify-type {
  display: flex;
  align-items: center;
  gap: 16rpx;
  font-size: 26rpx;
  color: #334155;
}
.save-btn {
  margin-top: 20rpx;
  background: #0f766e;
  color: #fff;
  border: none;
  border-radius: 12rpx;
  font-size: 28rpx;
}
.danger {
  color: #ef4444;
}
.danger-cell {
  background: #fffafa;
}
.danger-cell .menu-icon {
  background: #fff1f0;
}
</style>
