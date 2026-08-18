<template>
  <view class="page">
    <app-nav-bar title="团队成员" />
    <view class="page-body">
    <view class="toolbar">
      <button v-if="canInvite" class="invite-btn" size="mini" :loading="saving" @click="openInvite">
        邀请成员
      </button>
    </view>

    <view v-if="loading" class="card state">加载中…</view>
    <view v-else-if="error" class="card state">
      <text class="err">{{ error }}</text>
      <button class="retry" size="mini" @click="load">重试</button>
    </view>
    <empty-state
      v-else-if="!list.length"
      icon="/static/menu/team.png"
      title="暂无团队成员"
      hint="可邀请同事登录商户端协同补货与经营"
    >
      <button v-if="canInvite" class="empty-btn" @click="openInvite">邀请成员</button>
    </empty-state>
    <view v-else>
      <view v-for="u in list" :key="u.userId" class="card row" @click="openManage(u)">
        <view class="avatar">{{ (u.displayName || u.phoneNumber || '员').slice(0, 1) }}</view>
        <view class="meta">
          <text class="name">{{ u.displayName || u.phoneNumber || '用户 ' + u.userId }}</text>
          <text class="sub"
            >{{ u.phoneNumber || '无手机号' }} · {{ u.roleName || roleLabel(u.roleKey) }}</text
          >
          <text v-if="u.status === 'INACTIVE'" class="inactive">已停用</text>
        </view>
        <text v-if="u.self" class="self-tag">我</text>
        <text v-else-if="canManage" class="more">管理</text>
      </view>
    </view>

    <view v-if="inviteVisible" class="mask" @click="inviteVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">邀请成员</text>
        <input
          class="input"
          type="number"
          maxlength="11"
          placeholder="手机号"
          :value="form.phoneNumber"
          @input="form.phoneNumber = eventInput($event)"
        />
        <input
          class="input"
          password
          placeholder="初始密码（至少 6 位）"
          :value="form.password"
          @input="form.password = eventInput($event)"
        />
        <input
          class="input"
          placeholder="显示名（选填）"
          :value="form.displayName"
          @input="form.displayName = eventInput($event)"
        />
        <view class="role-row wrap">
          <text
            v-for="r in roles"
            :key="r.roleKey"
            class="role-chip"
            :class="{ active: form.roleKey === r.roleKey }"
            @click="form.roleKey = r.roleKey"
            >{{ r.roleName }}</text
          >
        </view>
        <view class="dialog-actions">
          <button class="btn ghost" @click="inviteVisible = false">取消</button>
          <button class="btn" :loading="saving" @click="onInvite">确认邀请</button>
        </view>
      </view>
    </view>

    <view v-if="manageVisible && manageUser" class="mask" @click="manageVisible = false">
      <view class="dialog" @click.stop>
        <text class="dialog-title">{{ manageUser.displayName || manageUser.phoneNumber }}</text>
        <text class="hint"
          >{{ manageUser.phoneNumber }} ·
          {{ manageUser.roleName || roleLabel(manageUser.roleKey) }}</text
        >

        <view v-if="canEdit" class="section">
          <text class="section-title">角色</text>
          <view class="role-row wrap">
            <text
              v-for="r in roles"
              :key="'m-' + r.roleKey"
              class="role-chip"
              :class="{ active: manageRoleKey === r.roleKey }"
              @click="manageRoleKey = r.roleKey"
              >{{ r.roleName }}</text
            >
          </view>
          <button class="btn block" :loading="saving" @click="onSaveRole">保存角色</button>
        </view>

        <view v-if="canReset" class="section">
          <text class="section-title">重置密码</text>
          <input
            class="input"
            password
            placeholder="新密码（至少 6 位）"
            :value="resetPassword"
            @input="resetPassword = eventInput($event)"
          />
          <button class="btn block" :loading="saving" @click="onResetPassword">确认重置</button>
        </view>

        <view v-if="canDisable && !manageUser.self" class="section">
          <button
            v-if="manageUser.status !== 'INACTIVE'"
            class="btn danger block"
            :loading="saving"
            @click="onDisable"
          >
            停用该成员
          </button>
          <button v-else class="btn block" :loading="saving" @click="onEnable">重新启用</button>
        </view>

        <button class="btn ghost block" @click="manageVisible = false">关闭</button>
      </view>
    </view>
  
    </view></view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import type { MerchantMe, MerchantTeamRoleDto, MerchantUserDto } from '@aicabinet/shared-types';

const { me, refresh: refreshMe } = useMerchantMe();
const canInvite = computed(() => hasPerm(me.value, 'merchant:users:invite'));
const canEdit = computed(() => hasPerm(me.value, 'merchant:users:edit'));
const canDisable = computed(() => hasPerm(me.value, 'merchant:users:disable'));
const canReset = computed(() => hasPerm(me.value, 'merchant:users:reset-password'));
const canManage = computed(() => canEdit.value || canDisable.value || canReset.value);

const loading = ref(true);
const saving = ref(false);
const error = ref('');
const list = ref<MerchantUserDto[]>([]);
const roles = ref<MerchantTeamRoleDto[]>([
  { roleKey: 'merchant', roleName: '商户管理员' },
  { roleKey: 'merchant_store_manager', roleName: '店长' },
  { roleKey: 'merchant_finance', roleName: '财务' },
  { roleKey: 'merchant_replenisher', roleName: '补货员' },
  { roleKey: 'merchant_staff', roleName: '店员' }
]);
const inviteVisible = ref(false);
const manageVisible = ref(false);
const manageUser = ref<MerchantUserDto | null>(null);
const manageRoleKey = ref('merchant_staff');
const resetPassword = ref('');
const form = reactive({
  phoneNumber: '',
  password: '',
  displayName: '',
  roleKey: 'merchant_staff'
});

onShow(() => {
  if (!uni.getStorageSync('merchant_token')) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void load();
});

onPullDownRefresh(async () => {
  try {
    await load();
  } finally {
    uni.stopPullDownRefresh();
  }
});

function eventInput(e: { detail?: { value?: unknown }; target?: { value?: unknown } }) {
  return String(e?.detail?.value ?? '');
}

function roleLabel(roleKey?: string) {
  const hit = roles.value.find((r) => r.roleKey === roleKey);
  if (hit) return hit.roleName;
  if (roleKey === 'merchant_admin' || roleKey === 'merchant') return '商户管理员';
  if (roleKey === 'merchant_staff') return '店员';
  return roleKey || '成员';
}

function openInvite() {
  form.phoneNumber = '';
  form.password = '';
  form.displayName = '';
  form.roleKey = 'merchant_staff';
  inviteVisible.value = true;
}

function openManage(u: MerchantUserDto) {
  if (u.self || !canManage.value) return;
  manageUser.value = u;
  manageRoleKey.value = u.roleKey || 'merchant_staff';
  resetPassword.value = '';
  manageVisible.value = true;
}

async function load() {
  loading.value = true;
  error.value = '';
  try {
    await refreshMe();
    if (!hasPerm(me.value, 'merchant:users:list')) {
      error.value = '无团队成员权限';
      list.value = [];
      return;
    }
    list.value = (await merchantApi.teamUsers()) || [];
    if (canInvite.value || canEdit.value) {
      try {
        const rs = await merchantApi.teamRoles();
        if (rs?.length) roles.value = rs;
      } catch {
        /* keep defaults */
      }
    }
  } catch (e) {
    if (!uni.getStorageSync('merchant_token')) return;
    me.value = (uni.getStorageSync('merchant_me') as MerchantMe) || null;
    list.value = [];
    error.value = e instanceof Error ? e.message : '加载失败';
  } finally {
    loading.value = false;
  }
}

async function onInvite() {
  const phone = form.phoneNumber.trim();
  const password = form.password.trim();
  if (!/^1\d{10}$/.test(phone)) {
    uni.showToast({ title: '请输入正确手机号', icon: 'none' });
    return;
  }
  if (password.length < 6) {
    uni.showToast({ title: '密码至少 6 位', icon: 'none' });
    return;
  }
  saving.value = true;
  try {
    await merchantApi.createTeamUser({
      phoneNumber: phone,
      password,
      displayName: form.displayName.trim() || undefined,
      roleKey: form.roleKey
    });
    uni.showToast({ title: '已邀请', icon: 'success' });
    inviteVisible.value = false;
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '邀请失败', icon: 'none' });
  } finally {
    saving.value = false;
  }
}

async function onSaveRole() {
  if (!manageUser.value) return;
  saving.value = true;
  try {
    await merchantApi.updateTeamUser(manageUser.value.userId, { roleKey: manageRoleKey.value });
    uni.showToast({ title: '已更新角色', icon: 'success' });
    manageVisible.value = false;
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '更新失败', icon: 'none' });
  } finally {
    saving.value = false;
  }
}

async function onResetPassword() {
  if (!manageUser.value) return;
  const pwd = resetPassword.value.trim();
  if (pwd.length < 6) {
    uni.showToast({ title: '密码至少 6 位', icon: 'none' });
    return;
  }
  saving.value = true;
  try {
    await merchantApi.resetTeamUserPassword(manageUser.value.userId, pwd);
    uni.showToast({ title: '密码已重置', icon: 'success' });
    resetPassword.value = '';
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '重置失败', icon: 'none' });
  } finally {
    saving.value = false;
  }
}

async function onDisable() {
  if (!manageUser.value) return;
  saving.value = true;
  try {
    await merchantApi.disableTeamUser(manageUser.value.userId);
    uni.showToast({ title: '已停用', icon: 'success' });
    manageVisible.value = false;
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '停用失败', icon: 'none' });
  } finally {
    saving.value = false;
  }
}

async function onEnable() {
  if (!manageUser.value) return;
  saving.value = true;
  try {
    await merchantApi.enableTeamUser(manageUser.value.userId);
    uni.showToast({ title: '已启用', icon: 'success' });
    manageVisible.value = false;
    await load();
  } catch (e) {
    uni.showToast({ title: e instanceof Error ? e.message : '启用失败', icon: 'none' });
  } finally {
    saving.value = false;
  }
}
</script>

<style scoped>
.page {
  padding: 0;
  min-height: 100vh;
  box-sizing: border-box;
}
.toolbar {
  display: flex;
  justify-content: flex-end;
  margin-bottom: 12rpx;
}
.invite-btn {
  background: #0f766e;
  color: #fff;
  border: none;
  border-radius: 999rpx;
  padding: 0 28rpx;
}
.card {
  background: #fff;
  border-radius: 20rpx;
  padding: 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: #64748b;
}
.err {
  color: #b91c1c;
}
.retry {
  background: #0f766e;
  color: #fff;
  border: none;
}
.row {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.avatar {
  width: 72rpx;
  height: 72rpx;
  border-radius: 50%;
  background: #ccfbf1;
  color: #0f766e;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}
.meta {
  flex: 1;
  min-width: 0;
}
.name {
  display: block;
  font-size: 30rpx;
  font-weight: 650;
  color: #134e4a;
}
.sub {
  display: block;
  margin-top: 6rpx;
  font-size: 24rpx;
  color: #64748b;
}
.inactive {
  display: block;
  margin-top: 4rpx;
  font-size: 22rpx;
  color: #b91c1c;
}
.self-tag,
.more {
  font-size: 22rpx;
  color: #0f766e;
  background: #ecfdf5;
  padding: 6rpx 12rpx;
  border-radius: 999rpx;
  font-weight: 600;
}
.more {
  background: #f1f5f9;
  color: #64748b;
}
.empty-btn {
  margin-top: 16rpx;
  background: #0f766e;
  color: #fff;
  border: none;
  border-radius: 999rpx;
}
.mask {
  position: fixed;
  inset: 0;
  background: rgba(15, 23, 42, 0.45);
  display: flex;
  align-items: flex-end;
  z-index: 20;
}
.dialog {
  width: 100%;
  background: #fff;
  border-radius: 28rpx 28rpx 0 0;
  padding: 32rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  max-height: 85vh;
  overflow-y: auto;
}
.dialog-title {
  display: block;
  font-size: 32rpx;
  font-weight: 700;
  color: #134e4a;
  margin-bottom: 8rpx;
}
.hint {
  display: block;
  font-size: 24rpx;
  color: #64748b;
  margin-bottom: 20rpx;
}
.input {
  background: #f8fafc;
  border-radius: 14rpx;
  padding: 22rpx 20rpx;
  margin-bottom: 16rpx;
  font-size: 28rpx;
}
.role-row {
  display: flex;
  gap: 12rpx;
  margin: 8rpx 0 24rpx;
}
.role-row.wrap {
  flex-wrap: wrap;
}
.role-chip {
  padding: 12rpx 24rpx;
  border-radius: 999rpx;
  background: #f1f5f9;
  color: #64748b;
  font-size: 26rpx;
}
.role-chip.active {
  background: #ccfbf1;
  color: #0f766e;
  font-weight: 650;
}
.dialog-actions {
  display: flex;
  gap: 16rpx;
}
.section {
  margin-bottom: 28rpx;
}
.section-title {
  display: block;
  font-size: 26rpx;
  font-weight: 650;
  color: #334155;
  margin-bottom: 12rpx;
}
.btn {
  flex: 1;
  background: #0f766e;
  color: #fff;
  border: none;
  border-radius: 999rpx;
  font-size: 28rpx;
}
.btn.block {
  width: 100%;
  margin-bottom: 12rpx;
  flex: none;
}
.btn.ghost {
  background: #f1f5f9;
  color: #475569;
}
.btn.danger {
  background: #b91c1c;
}
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
