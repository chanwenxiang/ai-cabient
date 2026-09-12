<template>
  <view class="page">
    <app-nav-bar title="团队成员" />
    <view class="page-body">
      <view class="toolbar">
        <button
          v-if="canInvite"
          class="invite-btn"
          size="mini"
          :loading="saving"
          @click="openInvite"
        >
          邀请成员
        </button>
      </view>

      <view v-if="loading && !list.length" class="card state">{{ UI_COPY.loading }}</view>
      <view v-else-if="error && !list.length" class="card state">
        <text class="err">{{ error }}</text>
        <app-button compact variant="ghost" label="重试" @click="load" />
      </view>
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/team.png"
        title="暂无团队成员"
        hint="可邀请同事登录商户端协同补货与经营"
      >
        <app-button v-if="canInvite" label="邀请成员" @click="openInvite" />
      </empty-state>
      <view v-else>
        <view
          v-for="u in list"
          role="button"
          :key="u.userId"
          class="card row"
          @click="openManage(u)"
        >
          <view class="avatar">{{ (u.displayName || u.phoneNumber || '员').slice(0, 1) }}</view>
          <view class="meta">
            <text class="name">{{ u.displayName || u.phoneNumber || '用户 ' + u.userId }}</text>
            <text class="sub"
              >{{ u.phoneNumber || '无手机号' }} · {{ u.roleName || roleLabel(u.roleKey) }}</text
            >
            <text class="sub status-line"
              >{{ u.status === 'INACTIVE' ? '已停用' : '启用中'
              }}{{ u.roleKey ? ` · ${u.roleKey}` : '' }}</text
            >
            <text v-if="u.status === 'INACTIVE'" class="inactive">点击可重新启用</text>
          </view>
          <text v-if="u.self" class="self-tag">我</text>
          <text v-else-if="canManage" class="more">管理</text>
        </view>
      </view>

      <view
        v-if="inviteVisible"
        role="button"
        aria-label="关闭"
        class="mask"
        @click="inviteVisible = false"
      >
        <view role="button" class="dialog" @click.stop>
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
              role="button"
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

      <view
        v-if="manageVisible && manageUser"
        role="button"
        aria-label="关闭"
        class="mask"
        @click="manageVisible = false"
      >
        <view role="button" class="dialog" @click.stop>
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
                role="button"
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
    </view></view
  >
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import type { MerchantMe, MerchantTeamRoleDto, MerchantUserDto } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';

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

function eventInput(e: unknown) {
  const ev = e as { detail?: { value?: unknown }; target?: { value?: unknown } };
  return String(ev?.detail?.value ?? ev?.target?.value ?? '');
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
  if (!list.value.length) loading.value = true;
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
    seedMerchantMeDisplayCache(me);
    if (!list.value.length) {
      list.value = [];
      error.value = e instanceof Error ? e.message : '加载失败';
    }
  } finally {
    loading.value = false;
  }
}

async function onInvite() {
  const phone = form.phoneNumber.trim();
  const password = form.password.trim();
  if (!/^1\d{10}$/.test(phone)) {
    showError('请输入正确手机号');
    return;
  }
  if (password.length < 6) {
    showError('密码至少 6 位');
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
    showSuccess('已邀请');
    inviteVisible.value = false;
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '邀请失败');
  } finally {
    saving.value = false;
  }
}

async function onSaveRole() {
  if (!manageUser.value) return;
  saving.value = true;
  try {
    await merchantApi.updateTeamUser(manageUser.value.userId, { roleKey: manageRoleKey.value });
    showSuccess('已更新角色');
    manageVisible.value = false;
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '更新失败');
  } finally {
    saving.value = false;
  }
}

async function onResetPassword() {
  if (!manageUser.value) return;
  const pwd = resetPassword.value.trim();
  if (pwd.length < 6) {
    showError('密码至少 6 位');
    return;
  }
  saving.value = true;
  try {
    await merchantApi.resetTeamUserPassword(manageUser.value.userId, pwd);
    showSuccess('密码已重置');
    resetPassword.value = '';
  } catch (e) {
    showError(e instanceof Error ? e.message : '重置失败');
  } finally {
    saving.value = false;
  }
}

async function onDisable() {
  if (!manageUser.value) return;
  saving.value = true;
  try {
    await merchantApi.disableTeamUser(manageUser.value.userId);
    showSuccess('已停用');
    manageVisible.value = false;
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '停用失败');
  } finally {
    saving.value = false;
  }
}

async function onEnable() {
  if (!manageUser.value) return;
  saving.value = true;
  try {
    await merchantApi.enableTeamUser(manageUser.value.userId);
    showSuccess('已启用');
    manageVisible.value = false;
    await load();
  } catch (e) {
    showError(e instanceof Error ? e.message : '启用失败');
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
  background: var(--brand);
  color: var(--white);
  border: none;
  border-radius: var(--radius-pill);
  padding: 0 28rpx;
}
.card {
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card);
  padding: 28rpx;
  margin-bottom: 16rpx;
  box-shadow: 0 8rpx 24rpx rgba(15, 118, 110, 0.06);
}
.state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 16rpx;
  color: var(--text-muted);
}
.err {
  color: var(--color-danger);
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
  background: var(--brand-mist);
  color: var(--brand);
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
  font-size: var(--font-size-lg);
  font-weight: 650;
  color: var(--brand-deep);
}
.sub {
  display: block;
  margin-top: 6rpx;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
}
.status-line {
  color: var(--text-subtle);
}
.inactive {
  display: block;
  margin-top: 4rpx;
  font-size: var(--font-size-sm);
  color: var(--color-danger);
}
.self-tag,
.more {
  font-size: var(--font-size-sm);
  color: var(--brand);
  background: var(--brand-soft);
  padding: 6rpx 12rpx;
  border-radius: var(--radius-pill);
  font-weight: 600;
}
.more {
  background: var(--color-border-subtle, #f1f5f9);
  color: var(--text-muted);
  min-width: 88rpx;
  text-align: center;
  box-sizing: border-box;
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
  background: var(--card-bg, #fff);
  border-radius: var(--radius-card) 28rpx 0 0;
  padding: 32rpx 28rpx calc(28rpx + env(safe-area-inset-bottom));
  max-height: 85vh;
  overflow-y: auto;
}
.dialog-title {
  display: block;
  font-size: var(--font-size-xl);
  font-weight: 700;
  color: var(--brand-deep);
  margin-bottom: 8rpx;
}
.hint {
  display: block;
  font-size: var(--font-size-caption);
  color: var(--text-muted);
  margin-bottom: 20rpx;
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
  border-radius: var(--radius-pill);
  background: var(--color-border-subtle, #f1f5f9);
  color: var(--text-muted);
  font-size: var(--font-size-body);
}
.role-chip.active {
  background: var(--brand-mist);
  color: var(--brand);
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
  font-size: var(--font-size-body);
  font-weight: 650;
  color: var(--text-muted, #334155);
  margin-bottom: 12rpx;
}
.btn {
  flex: 1;
  background: var(--brand);
  color: var(--white);
  border: none;
  border-radius: var(--radius-pill);
  font-size: var(--font-size-md);
  min-height: 80rpx;
  line-height: 1.2;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  box-sizing: border-box;
}
.btn.block {
  width: 100%;
  margin-bottom: 12rpx;
  flex: none;
}
.btn.ghost {
  background: var(--color-border-subtle, #f1f5f9);
  color: var(--text-muted, #475569);
}
.btn.danger {
  background: var(--color-danger);
}
.page-body {
  padding: 24rpx 24rpx calc(24rpx + env(safe-area-inset-bottom));
  box-sizing: border-box;
}
</style>
