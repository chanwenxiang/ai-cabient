<template>
  <view class="page">
    <view class="profile-header" :style="headerPadStyle">
      <view class="profile-main">
        <view class="avatar">{{ avatarText }}</view>
        <view class="profile-info">
          <text class="hello">{{ meName }}</text>
          <text class="sub">{{ merchantNames }}</text>
          <text v-if="phone" class="phone">{{ phone }}</text>
        </view>
        <text v-if="canEditProfile" class="edit-btn" @click="openProfileEdit">编辑资料</text>
      </view>
    </view>

    <view v-if="profileEditVisible" class="mask" @click="profileEditVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">编辑资料</text>
        <text class="hint">维护联系电话与告警联系人，用于异常通知与现场联系</text>
        <input
          class="input"
          type="number"
          maxlength="11"
          placeholder="联系电话"
          :value="profileForm.contactPhone"
          @input="profileForm.contactPhone = eventInput($event)"
        />
        <input
          class="input"
          placeholder="告警联系人"
          :value="profileForm.alertContactName"
          @input="profileForm.alertContactName = eventInput($event)"
        />
        <input
          class="input"
          type="number"
          maxlength="11"
          placeholder="告警电话"
          :value="profileForm.alertContactPhone"
          @input="profileForm.alertContactPhone = eventInput($event)"
        />
        <view class="dialog-actions">
          <button class="btn ghost" @click="profileEditVisible = false">取消</button>
          <button class="btn" :loading="profileSaving" @click="saveProfileEdit">保存</button>
        </view>
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
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
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
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
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
        <image class="menu-icon" :src="menuIcon('notice')" mode="aspectFit" />
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
        >未配置订阅消息模板，当前仅可保存偏好，无法向微信申请推送授权。</view
      >
      <view class="notify-types">
        <view v-for="t in alertTypeOptions" :key="t.value" class="notify-type">
          <switch
            :checked="enabledTypes.includes(t.value)"
            color="#0f766e"
            :aria-label="t.label"
            @change="(e) => onToggleType(t.value, switchEnabled(e))"
          />
          <text>{{ t.label }}</text>
        </view>
      </view>
      <button class="save-btn" :loading="notifyBusy" @click="onSaveSubscribe">保存提醒偏好</button>
    </view>

    <view v-if="bizNav.length" class="section-label">经营工具</view>
    <view v-if="bizNav.length" class="menu-list">
      <view v-for="item in bizNav" :key="item.key" class="menu-cell" @click="goNav(item)">
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <text class="menu-arrow">›</text>
      </view>
    </view>

    <view class="menu-list">
      <view class="menu-cell danger-cell" @click="onLogout">
        <image class="menu-icon" :src="menuIcon('logout')" mode="aspectFit" />
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
import { getStatusBarPadPx } from '@aicabinet/shared-uni/status-bar';
import {
  clearSession,
  hasPerm,
  merchantApi,
  type MerchantProfileUpdate
} from '@/utils/merchant-api';
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
import { menuIcon } from '@/utils/menu-icon';

const headerPadStyle = {
  borderTop: getStatusBarPadPx() + 'px solid var(--brand-deep, #134e4a)'
};

const { me, refresh: refreshMe } = useMerchantMe();
const meName = ref('');
const merchantNames = ref('');
const phone = ref('');
const canEditProfile = computed(() => hasPerm(me.value, 'merchant:profile:edit'));
const profileEditVisible = ref(false);
const profileSaving = ref(false);
const profileForm = ref<MerchantProfileUpdate>({});
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

function openProfileEdit() {
  profileForm.value = { contactPhone: '', alertContactName: '', alertContactPhone: '' };
  profileEditVisible.value = true;
}

async function saveProfileEdit() {
  profileSaving.value = true;
  try {
    await merchantApi.updateMerchantProfile({
      contactPhone: profileForm.value.contactPhone || undefined,
      alertContactName: profileForm.value.alertContactName || undefined,
      alertContactPhone: profileForm.value.alertContactPhone || undefined
    });
    uni.showToast({ title: '已保存', icon: 'success' });
    profileEditVisible.value = false;
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '保存失败', icon: 'none' });
  } finally {
    profileSaving.value = false;
  }
}

function eventInput(e: unknown) {
  const ev = e as { detail?: { value?: unknown }; target?: { value?: unknown } };
  return String(ev?.detail?.value ?? ev?.target?.value ?? '');
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

function switchEnabled(e: unknown) {
  const ev = e as { detail?: { value?: boolean } };
  return !!ev?.detail?.value;
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
.edit-btn {
  align-self: flex-start;
  padding: 10rpx 22rpx;
  border-radius: 999rpx;
  background: #ecfdf5;
  color: var(--brand, #0f766e);
  font-size: 24rpx;
  font-weight: 600;
}
.mask {
  position: fixed;
  inset: 0;
  z-index: 30;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
}
.dialog {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 32rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
.dialog-title {
  display: block;
  font-size: 32rpx;
  font-weight: 700;
  color: var(--brand-deep, #134e4a);
}
.hint {
  display: block;
  margin: 8rpx 0 20rpx;
  font-size: 24rpx;
  color: #64748b;
}
.input {
  display: block;
  width: 100%;
  height: 80rpx;
  min-height: 80rpx;
  line-height: 80rpx;
  box-sizing: border-box;
  background: #f8fafc;
  border: 1rpx solid #e2e8f0;
  border-radius: 14rpx;
  padding: 0 20rpx;
  margin-bottom: 16rpx;
  font-size: 28rpx;
  color: #0f172a;
}
.dialog-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 8rpx;
}
.btn {
  flex: 1;
  background: var(--brand, #0f766e);
  color: #fff;
  border: none;
  border-radius: 999rpx;
  font-size: 28rpx;
  min-height: 80rpx;
  line-height: 1.2;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.btn.ghost {
  background: #f1f5f9;
  color: #475569;
}

.page {
  min-height: 100%;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  background: #ffffff;
}
.profile-header {
  margin: 0;
  padding: 0;
  border-radius: 0;
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
  box-sizing: border-box;
  background: linear-gradient(
    145deg,
    var(--brand-deep, #134e4a),
    var(--brand, #0f766e) 60%,
    var(--brand, #0f766e)
  );
  box-shadow: none;
  color: #fff;
}
.profile-main {
  display: flex;
  align-items: center;
  gap: 14rpx;
  padding: 12rpx 24rpx 24rpx;
}
.avatar {
  width: 80rpx;
  height: 80rpx;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.22);
  border: 2rpx solid rgba(255, 255, 255, 0.35);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 34rpx;
  font-weight: 700;
}
.profile-info {
  flex: 1;
  min-width: 0;
}
.hello {
  font-size: 30rpx;
  font-weight: 700;
  display: block;
}
.sub {
  font-size: 22rpx;
  opacity: 0.9;
  display: block;
  margin-top: 2rpx;
}
.phone {
  font-size: 22rpx;
  opacity: 0.75;
  display: block;
  margin-top: 2rpx;
}
.section-label {
  margin: 14rpx 28rpx 6rpx;
  font-size: 22rpx;
  color: #94a3b8;
  letter-spacing: 1rpx;
}
.menu-list {
  margin: 0 24rpx;
  background: #fff;
  border-radius: 14rpx;
  overflow: hidden;
}
.menu-cell {
  background: transparent;
  border-radius: 0;
  padding: 20rpx 22rpx;
  margin-bottom: 0;
  display: flex;
  align-items: center;
  gap: 14rpx;
  border: none;
  border-bottom: 1rpx solid #f1f5f9;
  box-shadow: none;
  min-height: 84rpx;
  box-sizing: border-box;
}
.menu-cell:last-child {
  border-bottom: none;
}
.menu-cell.highlight {
  border-color: transparent;
  border-bottom: 1rpx solid #f1f5f9;
  background: #f8fffc;
}
.menu-icon {
  width: 40rpx;
  height: 40rpx;
  flex-shrink: 0;
}
.menu-text {
  flex: 1;
  min-width: 0;
}
.menu-title {
  font-size: 28rpx;
  font-weight: 500;
  display: block;
  color: #0f172a;
  line-height: 1.3;
}
.menu-desc {
  font-size: 22rpx;
  color: #94a3b8;
  display: block;
  margin-top: 2rpx;
  line-height: 1.3;
}
.menu-arrow {
  color: #cbd5e1;
  font-size: 28rpx;
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
  color: var(--brand, #0f766e);
  border: none;
  font-size: 22rpx;
}
.notify-warn {
  margin-bottom: 16rpx;
  padding: 12rpx 16rpx;
  border-radius: 12rpx;
  background: #ecfdf5;
  color: var(--brand, #0f766e);
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
  background: var(--brand, #0f766e);
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
