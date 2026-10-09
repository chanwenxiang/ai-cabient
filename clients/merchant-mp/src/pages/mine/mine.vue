<template>
  <view class="page">
    <app-nav-bar title="我的" hide-back home-url="/pages/home/home" />
    <view
      class="profile-cell"
      :hover-class="canEditProfile ? 'wx-cell-hover' : ''"
      :role="canEditProfile ? 'button' : undefined"
      :aria-label="canEditProfile ? '编辑资料' : undefined"
      @click="onProfileTap"
    >
      <view class="avatar">
        <text class="avatar-text">{{ avatarText }}</text>
      </view>
      <view class="profile-info">
        <text class="hello">{{ meName }}</text>
        <text class="sub">{{ profileSub }}</text>
      </view>
      <view
        v-if="canEditProfile"
        class="menu-arrow app-icon app-icon--chevron"
        aria-hidden="true"
      />
    </view>

    <AppSheet
      :visible="profileEditVisible"
      aria-label="编辑资料"
      @close="profileEditVisible = false"
    >
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
    </AppSheet>

    <view v-if="fieldNav.length" class="section-label">现场作业</view>
    <view v-if="fieldNav.length" class="menu-list">
      <view
        v-for="item in fieldNav"
        role="button"
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
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="moreNav.length" class="section-label">更多功能</view>
    <view v-if="moreNav.length" class="menu-list">
      <view
        v-for="item in moreNav"
        role="button"
        :key="item.key"
        class="menu-cell"
        @click="goNav(item)"
      >
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="teamNav.length" class="section-label">团队与设置</view>
    <view v-if="teamNav.length" class="menu-list">
      <view
        v-for="item in teamNav"
        role="button"
        :key="item.key"
        class="menu-cell"
        @click="goNav(item)"
      >
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="docsNav.length" class="section-label">资料与报表</view>
    <view v-if="docsNav.length" class="menu-list">
      <view
        v-for="item in docsNav"
        role="button"
        :key="item.key"
        class="menu-cell"
        @click="goNav(item)"
      >
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view class="section-label">平台公告</view>
    <view class="menu-list">
      <view role="button" class="menu-cell" @click="goAnnouncements">
        <image class="menu-icon" :src="menuIcon('notice')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">通知公告</text>
          <text class="menu-desc">运营发布的维护、活动与规则通知</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view v-if="bizNav.length" class="section-label">经营工具</view>
    <view v-if="bizNav.length" class="menu-list">
      <view
        v-for="item in bizNav"
        role="button"
        :key="item.key"
        class="menu-cell"
        @click="goNav(item)"
      >
        <image class="menu-icon" :src="menuIcon(item.icon)" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">{{ item.title }}</text>
          <text v-if="item.desc" class="menu-desc">{{ item.desc }}</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <!-- CB-018④：在线客服触点（热线来自 merchant.service_phone，未配置时整组不渲染） -->
    <view v-if="supportPhone || supportEmail" class="section-label">帮助与客服</view>
    <view v-if="supportPhone || supportEmail" class="menu-list">
      <view
        v-if="supportPhone"
        role="button"
        class="menu-cell"
        aria-label="拨打客服热线"
        @click="onContactSupport"
      >
        <image class="menu-icon" :src="menuIcon('notice')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">客服热线</text>
          <text class="menu-desc">{{ supportPhone }}</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
      <view
        v-if="supportEmail"
        role="button"
        class="menu-cell"
        aria-label="复制客服邮箱"
        @click="onCopySupportEmail"
      >
        <image class="menu-icon" :src="menuIcon('notice')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title">客服邮箱</text>
          <text class="menu-desc">{{ supportEmail }}</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>

    <view class="menu-list">
      <view role="button" class="menu-cell danger-cell" @click="onLogout">
        <image class="menu-icon" :src="menuIcon('logout')" mode="aspectFit" />
        <view class="menu-text">
          <text class="menu-title danger">退出登录</text>
        </view>
        <view class="menu-arrow app-icon app-icon--chevron" aria-hidden="true" />
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { onShow } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import AppSheet from '@/components/AppSheet.vue';
import { clearSession, hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import { showError, showSuccess, showConfirm } from '@/utils/notify';
import {
  canAccessNav,
  useMerchantMe,
  seedMerchantMeDisplayCache
} from '@/composables/useMerchantMe';
import type { MerchantMe, OpenApiUpdateMerchantProfileRequest } from '@aicabinet/shared-types';
import {
  MERCHANT_BIZ_NAV,
  MERCHANT_DOCS_NAV,
  MERCHANT_FIELD_NAV,
  MERCHANT_MORE_NAV,
  MERCHANT_MORE_NAV_KEYS,
  MERCHANT_TEAM_NAV,
  type MerchantNavItem
} from '@/config/merchant-nav';
import { formatMerchantNames } from '@/utils/merchant-display';
import {
  loadMerchantFlags,
  merchantServicePhone,
  merchantSupportEmail
} from '@/utils/merchant-config';
import { safeMakePhoneCall } from '@aicabinet/shared-uni/safe-uni-call';
import { menuIcon } from '@/utils/menu-icon';

const { me, refresh: refreshMe } = useMerchantMe();
const meName = ref('');
const merchantNames = ref('');
const phone = ref('');
const canEditProfile = computed(() => hasPerm(me.value, 'merchant:profile:edit'));
const profileEditVisible = ref(false);
const profileSaving = ref(false);
const profileForm = ref<OpenApiUpdateMerchantProfileRequest>({});
const avatarText = computed(() => (meName.value || '商').slice(0, 1));
const profileSub = computed(() => {
  const bits = [merchantNames.value, phone.value].filter((s) => String(s || '').trim());
  return bits.join(' · ');
});

const fieldNav = computed(() => MERCHANT_FIELD_NAV.filter((i) => canAccessNav(me.value, i)));
const moreNav = computed(() => MERCHANT_MORE_NAV.filter((i) => canAccessNav(me.value, i)));
const bizNav = computed(() =>
  MERCHANT_BIZ_NAV.filter((i) => canAccessNav(me.value, i) && !MERCHANT_MORE_NAV_KEYS.has(i.key))
);
const teamNav = computed(() => MERCHANT_TEAM_NAV.filter((i) => canAccessNav(me.value, i)));
const docsNav = computed(() => MERCHANT_DOCS_NAV.filter((i) => canAccessNav(me.value, i)));

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

function onProfileTap() {
  if (!canEditProfile.value) return;
  openProfileEdit();
}

async function saveProfileEdit() {
  profileSaving.value = true;
  try {
    await merchantApi.updateMerchantProfile({
      contactPhone: profileForm.value.contactPhone || undefined,
      alertContactName: profileForm.value.alertContactName || undefined,
      alertContactPhone: profileForm.value.alertContactPhone || undefined
    });
    showSuccess('已保存');
    profileEditVisible.value = false;
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
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

// ===== CB-018④：在线客服触点 =====
const supportPhone = ref('');
const supportEmail = ref('');

/** 电话/邮箱来自公开配置；拨打走 safeMakePhoneCall（H5/非微信环境安全降级）。 */
function loadSupportContacts() {
  void loadMerchantFlags().then(() => {
    supportPhone.value = merchantServicePhone();
    supportEmail.value = merchantSupportEmail();
  });
}

function onContactSupport() {
  if (!supportPhone.value) return;
  safeMakePhoneCall(supportPhone.value);
}

function onCopySupportEmail() {
  if (!supportEmail.value) return;
  uni.setClipboardData({
    data: supportEmail.value,
    success: () => showSuccess('邮箱已复制')
  });
}

onShow(async () => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  seedMerchantMeDisplayCache(me);
  const profile = me.value || ({} as MerchantMe);
  meName.value = profile.displayName || profile.phoneNumber || '商户';
  merchantNames.value = formatMerchantNames(profile.merchants, '未绑定');
  phone.value = profile.phoneNumber || '';
  loadSupportContacts();
});

async function onLogout() {
  const confirmed = await showConfirm({
    title: '退出登录',
    content: '确定退出当前账户吗？',
    confirmText: '退出'
  });
  if (!confirmed) return;
  clearSession();
  uni.reLaunch({ url: '/pages/login/login' });
}
</script>

<style scoped>
.dialog-title {
  display: block;
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--brand-deep, #134e4a);
}
.hint {
  display: block;
  margin: 8rpx 0 20rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
.input {
  display: block;
  width: 100%;
  height: 80rpx;
  min-height: 80rpx;
  line-height: 80rpx;
  box-sizing: border-box;
  background: var(--page-bg, #f8fafc);
  border: 1rpx solid var(--color-border);
  border-radius: var(--radius-control);
  padding: 0 20rpx;
  margin-bottom: 16rpx;
  font-size: var(--font-size-md);
  color: var(--text-primary, #0f172a);
}
.dialog-actions {
  display: flex;
  gap: 16rpx;
  margin-top: 8rpx;
}
.btn {
  flex: 1;
  background: var(--brand, #0f766e);
  color: var(--white);
  border: none;
  border-radius: 16rpx;
  font-size: var(--font-size-md);
  min-height: 80rpx;
  line-height: 1.2;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.btn.ghost {
  background: var(--color-border-subtle, #f1f5f9);
  color: var(--text-muted, #475569);
}

.page {
  min-height: 100%;
  padding-bottom: calc(24rpx + env(safe-area-inset-bottom));
  background: var(--page-bg, #ededed);
}
.profile-cell {
  display: flex;
  align-items: center;
  gap: 24rpx;
  margin: 0 0 16rpx;
  padding: 32rpx 32rpx 36rpx;
  background: #ffffff;
  box-sizing: border-box;
}
.avatar {
  width: 128rpx;
  height: 128rpx;
  border-radius: 50%;
  background: var(--brand, #0f766e);
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}
.avatar-text {
  color: #ffffff;
  font-size: 48rpx;
  font-weight: 600;
  line-height: 1;
}
.profile-info {
  flex: 1;
  min-width: 0;
}
.hello {
  font-size: 34rpx;
  font-weight: 600;
  color: #181818;
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.35;
}
.sub {
  font-size: 26rpx;
  color: #888888;
  display: block;
  margin-top: 8rpx;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  line-height: 1.4;
}
.section-label {
  /*
   * 🔴 上边距（14rpx）是**组间距**的唯一来源：除「退出登录」外，每组菜单前面都有一行
   * section-label，靠它把上一组卡片推开。退出登录组没有 label（见模板），
   * 因此上一组与它之间**一点间距都没有** —— 两张白卡直接黏成一块（2026-10-08 用户报）。
   * 故把组间距改为「上一组卡片 margin-bottom + label margin-top」两段共同承担。
   */
  margin: 10rpx 28rpx 6rpx;
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  letter-spacing: 1rpx;
}
.menu-list {
  /*
   * 🔴 卡片自身必须带下边距，不能只靠 section-label 隔开：
   * ① 「退出登录」组前面没有 label（模板里它是裸的 menu-list）；
   * ② 任何一组被 v-if 裁掉（fieldNav/bizNav 等为空）时，label 也会一起消失，
   *    只剩相邻两张 menu-list 贴在一起。
   * 两条路径都会产生「两张卡片黏成一块」，所以间距放在**卡片**上而不是标签上。
   */
  margin: 0 24rpx 16rpx;
  background: var(--card-bg, #fff);
  border-radius: var(--radius-control);
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
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
  box-shadow: none;
  min-height: 84rpx;
  box-sizing: border-box;
}
.menu-cell:last-child {
  border-bottom: none;
}
.menu-cell.highlight {
  border-color: transparent;
  border-bottom: 1rpx solid var(--color-border-subtle, #f1f5f9);
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
  font-size: var(--font-size-md);
  font-weight: 500;
  display: block;
  color: var(--text-primary, #0f172a);
  line-height: 1.3;
}
.menu-desc {
  font-size: var(--font-size-sm);
  color: var(--text-subtle);
  display: block;
  margin-top: 2rpx;
  line-height: 1.3;
}
.menu-arrow {
  color: var(--text-subtle, #cbd5e1);
  flex-shrink: 0;
  width: 0.55em;
  height: 0.55em;
  font-size: var(--font-size-md);
  margin-left: 8rpx;
}
.danger {
  color: var(--color-danger);
}
.danger-cell {
  background: #fffafa;
}
.danger-cell .menu-icon {
  background: #f9eded;
}
</style>
