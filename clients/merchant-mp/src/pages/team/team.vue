<template>
  <view class="page">
    <app-nav-bar title="团队成员" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="canInvite" class="invite-card" role="button" @click="openInvite">
        <view class="invite-plus" aria-hidden="true">+</view>
        <view class="invite-copy">
          <text class="invite-title">邀请成员</text>
          <text class="invite-desc">用手机号开通账号，同事即可登录协同</text>
        </view>
        <view class="invite-go app-link-chevron" aria-hidden="true">去邀请</view>
      </view>

      <view v-if="loading && !list.length" class="card state">{{ UI_COPY.loading }}</view>
      <error-state v-else-if="error && !list.length" :title="error" @retry="load" />
      <empty-state
        v-else-if="!list.length"
        icon="/static/menu/team.png"
        title="暂无团队成员"
        hint="可邀请同事登录商户端协同补货与经营"
      />
      <view v-else class="list">
        <text class="list-count">共 {{ list.length }} 人</text>
        <view
          v-for="u in list"
          role="button"
          :key="u.userId"
          class="card row"
          :class="{ inactive: u.status === 'INACTIVE' }"
          @click="openManage(u)"
        >
          <view class="avatar">{{ (u.displayName || u.phoneNumber || '员').slice(0, 1) }}</view>
          <view class="meta">
            <view class="name-row">
              <text class="name">{{
                teamMemberTitle(u.displayName, u.phoneNumber, u.userId)
              }}</text>
              <text class="role-tag">{{ teamRoleLabel(u.roleKey, u.roleName) }}</text>
            </view>
            <text class="sub">{{ u.phoneNumber || '无手机号' }}</text>
            <view class="status-row">
              <text class="status-tag" :class="{ off: u.status === 'INACTIVE' }">{{
                teamStatusLabel(u.status)
              }}</text>
              <text v-if="u.status === 'INACTIVE'" class="inactive">点击可重新启用</text>
            </view>
          </view>
          <text v-if="u.self" class="self-tag">我</text>
          <view v-else-if="canManage" class="more app-link-chevron">管理</view>
        </view>
      </view>

      <AppSheet :visible="inviteVisible" aria-label="邀请成员" @close="inviteVisible = false">
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
            >{{ teamRoleLabel(r.roleKey, r.roleName) }}</text
          >
        </view>
        <view class="dialog-actions">
          <button class="btn ghost" @click="inviteVisible = false">取消</button>
          <button class="btn" :loading="saving" @click="onInvite">确认邀请</button>
        </view>
      </AppSheet>

      <AppSheet
        :visible="!!(manageVisible && manageUser)"
        aria-label="成员管理"
        @close="manageVisible = false"
      >
        <text class="dialog-title">{{
          teamMemberTitle(manageUser?.displayName, manageUser?.phoneNumber, manageUser?.userId)
        }}</text>
        <text class="hint"
          >{{ manageUser?.phoneNumber || '无手机号' }} ·
          {{ teamRoleLabel(manageUser?.roleKey, manageUser?.roleName) }}</text
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
              >{{ teamRoleLabel(r.roleKey, r.roleName) }}</text
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

        <view v-if="canDisable && manageUser && !manageUser.self" class="section">
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
      </AppSheet>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { onPullDownRefresh, onShow } from '@dcloudio/uni-app';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import AppSheet from '@/components/AppSheet.vue';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import type { MerchantTeamRoleDto, MerchantUserDto } from '@aicabinet/shared-types';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { teamMemberTitle, teamRoleLabel, teamStatusLabel } from '@/utils/team-display';

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
  if (!isMerchantLoggedIn()) {
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
        if (rs?.length) {
          roles.value = rs.map((r) => ({
            ...r,
            roleName: teamRoleLabel(r.roleKey, r.roleName)
          }));
        }
      } catch {
        /* keep defaults */
      }
    }
  } catch (e) {
    if (!isMerchantLoggedIn()) return;
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

<style scoped src="./team.page.css"></style>
