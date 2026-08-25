<template>
  <el-card class="page-card report-page profile-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">个人中心</span>
            <span class="hint">账号信息与当前外观偏好</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button type="primary" plain @click="openPasswordDialog">修改密码</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="reload">刷新资料</el-button>
        </div>
      </div>
    </template>

    <div class="profile-head">
      <el-avatar :size="64" class="avatar">{{ initial }}</el-avatar>
      <div class="name-cell">
        <strong class="display-name">{{ profileReady ? auth.displayName : '…' }}</strong>
        <small>{{ profileReady ? auth.phone || '无' : '…' }}</small>
        <small class="cell-id">ID {{ profileReady ? auth.userId || '无' : '…' }}</small>
      </div>
    </div>

    <el-descriptions :column="1" border class="profile-desc">
      <el-descriptions-item label="角色">{{
        profileReady ? auth.roleText : '…'
      }}</el-descriptions-item>
      <el-descriptions-item label="数据范围">{{
        profileReady ? auth.dataScopeText : '…'
      }}</el-descriptions-item>
      <el-descriptions-item label="权限数">
        {{ profileReady ? (auth.profile?.permissionCount ?? permissions.length) : '暂无' }}
      </el-descriptions-item>
      <el-descriptions-item label="主题">
        <el-tag size="small" :type="settings.theme === 'dark' ? 'info' : 'success'" effect="plain">
          {{ settings.theme === 'dark' ? '深色' : '浅色' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="字号">{{ fontLabel }}</el-descriptions-item>
      <el-descriptions-item label="操作列">{{ actionLabel }}</el-descriptions-item>
    </el-descriptions>

    <div class="twofa-card">
      <div class="perm-head">
        <h4>双因子认证（TOTP）</h4>
        <el-tag size="small" :type="twoFactorEnabled ? 'success' : 'info'" effect="plain">
          {{ twoFactorEnabled ? '已启用' : '未启用' }}
        </el-tag>
      </div>
      <p class="meta">登录时除密码外需输入身份验证器动态码，建议所有运营账号启用</p>

      <template v-if="!twoFactorEnabled">
        <el-button
          v-if="!enrollData"
          type="primary"
          plain
          :loading="twoFactorLoading"
          @click="enroll"
          >绑定身份验证器</el-button
        >
        <div v-else class="enroll-box">
          <p class="meta">
            1. 打开身份验证器 App（Google Authenticator / Microsoft Authenticator
            等），扫码或手动输入密钥：
          </p>
          <el-input :model-value="enrollData.secret" readonly class="secret-input">
            <template #append>
              <el-button @click="copyText(enrollData.secret)">复制密钥</el-button>
            </template>
          </el-input>
          <p class="meta break-all">{{ enrollData.otpauthUri }}</p>
          <p class="meta">2. 输入 App 当前显示的 6 位动态码完成绑定：</p>
          <div class="enroll-row">
            <el-input
              v-model="confirmCode"
              maxlength="6"
              placeholder="6 位动态码"
              class="code-input"
              @keyup.enter="confirm"
            />
            <el-button type="primary" :loading="twoFactorLoading" @click="confirm"
              >确认绑定</el-button
            >
          </div>
          <p class="meta warn">
            请立即保存以下 8 个后备码（仅显示一次），丢失后只能联系管理员重置：
          </p>
          <div class="recovery-codes">
            <el-tag v-for="c in enrollData.recoveryCodes" :key="c" size="small">{{ c }}</el-tag>
          </div>
        </div>
      </template>

      <div v-else class="enroll-row">
        <el-input
          v-model="disableCode"
          maxlength="6"
          placeholder="输入当前动态码以关闭"
          class="code-input"
          @keyup.enter="disable"
        />
        <el-button type="danger" plain :loading="twoFactorLoading" @click="disable"
          >关闭双因子认证</el-button
        >
      </div>
    </div>

    <div v-if="permissions.length" class="perm-block">
      <div class="perm-head">
        <h4>权限码（节选）</h4>
        <span class="meta">共 {{ permissions.length }} 项</span>
      </div>
      <div class="perm-tags">
        <el-tag
          v-for="p in permissions.slice(0, 30)"
          :key="p"
          size="small"
          effect="plain"
          class="perm-tag"
        >
          {{ p }}
        </el-tag>
      </div>
      <p v-if="permissions.length > 30" class="meta">…仅展示前 30 项</p>
    </div>

    <el-dialog v-model="pwdVisible" title="修改密码" width="420px" :close-on-click-modal="false">
      <el-form label-position="top" @submit.prevent="submitPassword">
        <el-form-item label="原密码">
          <el-input
            v-model="pwdForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入当前登录密码"
          />
        </el-form-item>
        <el-form-item label="新密码">
          <el-input
            v-model="pwdForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="6-64 位"
          />
        </el-form-item>
        <el-form-item label="确认新密码">
          <el-input
            v-model="pwdForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="再次输入新密码"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="pwdVisible = false">取消</el-button>
        <el-button type="primary" :loading="pwdSaving" @click="submitPassword">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useSettingsStore } from '@/stores/settings';
import type { TwoFactorEnroll, TwoFactorStatus } from '@aicabinet/shared-types';

const auth = useAuthStore();
const settings = useSettingsStore();
const loading = ref(false);
const profileHydrated = ref(!!auth.profile);

const permissions = computed(() => auth.permissions);
/** 有缓存资料则直接展示；首屏未拉完前不闪「无 / 未分配角色」 */
const profileReady = computed(() => !!auth.profile || (profileHydrated.value && !loading.value));
const initial = computed(() => (auth.displayName || '运').slice(0, 1));
const fontLabel = computed(() => ({ sm: '小', md: '中', lg: '大' })[settings.fontSize]);
const actionLabel = computed(() => (settings.tableActionMode === 'label' ? '图标+文字' : '图标'));
const pwdVisible = ref(false);
const pwdSaving = ref(false);
const pwdForm = ref({ oldPassword: '', newPassword: '', confirmPassword: '' });
const twoFactorEnabled = ref(false);
const twoFactorLoading = ref(false);
const enrollData = ref<TwoFactorEnroll | null>(null);
const confirmCode = ref('');
const disableCode = ref('');

onMounted(() => {
  void loadTwoFactorStatus();
});

async function loadTwoFactorStatus() {
  try {
    const s = await api.request<TwoFactorStatus>(
      '/api/v2/ops/admin/rbac/me/two-factor/status',
      'GET'
    );
    twoFactorEnabled.value = !!s?.enabled;
  } catch {
    // 软失败：保持默认未启用展示
  }
}

async function enroll() {
  twoFactorLoading.value = true;
  try {
    enrollData.value = await api.request<TwoFactorEnroll>(
      '/api/v2/ops/admin/rbac/me/two-factor/enroll',
      'GET'
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '绑定失败');
  } finally {
    twoFactorLoading.value = false;
  }
}

async function confirm() {
  const code = confirmCode.value.trim();
  if (!code) {
    ElMessage.warning('请输入动态码');
    return;
  }
  twoFactorLoading.value = true;
  try {
    await api.request('/api/v2/ops/admin/rbac/me/two-factor/confirm', 'POST', { code });
    twoFactorEnabled.value = true;
    enrollData.value = null;
    confirmCode.value = '';
    ElMessage.success('双因子认证已启用');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '启用失败');
  } finally {
    twoFactorLoading.value = false;
  }
}

async function disable() {
  const code = disableCode.value.trim();
  if (!code) {
    ElMessage.warning('请输入动态码');
    return;
  }
  twoFactorLoading.value = true;
  try {
    await api.request('/api/v2/ops/admin/rbac/me/two-factor/disable', 'POST', { code });
    twoFactorEnabled.value = false;
    disableCode.value = '';
    ElMessage.success('已关闭双因子认证');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '关闭失败');
  } finally {
    twoFactorLoading.value = false;
  }
}

function copyText(text: string) {
  navigator.clipboard
    ?.writeText(text)
    .then(() => ElMessage.success('已复制'))
    .catch(() => ElMessage.warning('复制失败'));
}

function openPasswordDialog() {
  pwdForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' };
  pwdVisible.value = true;
}

async function submitPassword() {
  const f = pwdForm.value;
  if (!f.oldPassword) {
    ElMessage.warning('请输入原密码');
    return;
  }
  if (!f.newPassword || f.newPassword.length < 6 || f.newPassword.length > 64) {
    ElMessage.warning('新密码长度需为 6-64 位');
    return;
  }
  if (f.newPassword !== f.confirmPassword) {
    ElMessage.warning('两次输入的新密码不一致');
    return;
  }
  pwdSaving.value = true;
  try {
    await api.request('/api/v2/ops/admin/rbac/me/password', 'PUT', {
      oldPassword: f.oldPassword,
      newPassword: f.newPassword
    });
    ElMessage.success('密码已修改');
    pwdVisible.value = false;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '修改失败');
  } finally {
    pwdSaving.value = false;
  }
}

async function reload() {
  loading.value = true;
  try {
    await auth.loadProfile();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    profileHydrated.value = true;
    loading.value = false;
  }
}

onMounted(() => reload());
onActivated(() => {
  void reload();
});
</script>

<style scoped>
.twofa-card {
  margin-top: 20px;
  padding: 16px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 10px;
  background: var(--el-fill-color-blank);
}
.enroll-box {
  margin-top: 12px;
}
.secret-input {
  max-width: 420px;
  margin: 8px 0;
}
.enroll-row {
  display: flex;
  gap: 10px;
  align-items: center;
  flex-wrap: wrap;
}
.code-input {
  max-width: 260px;
}
.recovery-codes {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}
.warn {
  color: #b45309;
}
.break-all {
  word-break: break-all;
}

.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}

.profile-head {
  display: flex;
  gap: 16px;
  align-items: center;
}
.avatar {
  background: var(--app-primary);
  color: #fff;
  font-size: 24px;
  flex-shrink: 0;
}
.name-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.display-name {
  font-size: 18px;
  font-weight: 650;
}
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.cell-id {
  font-family: inherit;
  font-size: inherit;
}

.profile-desc {
  margin-top: 20px;
  max-width: 560px;
}

.perm-block {
  margin-top: 24px;
}
.perm-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}
.perm-head h4 {
  margin: 0;
  font-size: 14px;
}
.perm-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.perm-tag {
  font-family: var(--app-font-mono);
}
.meta {
  color: var(--el-text-color-secondary);
  margin: 8px 0 0;
  font-size: 12px;
}
</style>
