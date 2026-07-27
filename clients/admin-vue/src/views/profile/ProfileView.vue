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
          <el-button :icon="Refresh" :loading="loading" @click="reload">刷新资料</el-button>
        </div>
      </div>
    </template>

    <div class="profile-head">
      <el-avatar :size="64" class="avatar">{{ initial }}</el-avatar>
      <div class="name-cell">
        <strong class="display-name">{{ auth.displayName }}</strong>
        <small>{{ auth.phone || '-' }}</small>
        <small class="cell-id">ID {{ auth.userId || '-' }}</small>
      </div>
    </div>

    <el-descriptions :column="1" border class="profile-desc">
      <el-descriptions-item label="角色">{{ auth.roleText || '-' }}</el-descriptions-item>
      <el-descriptions-item label="数据范围">{{ auth.dataScopeText }}</el-descriptions-item>
      <el-descriptions-item label="权限数">
        {{ auth.profile?.permissionCount ?? permissions.length }}
      </el-descriptions-item>
      <el-descriptions-item label="主题">
        <el-tag size="small" :type="settings.theme === 'dark' ? 'info' : 'success'" effect="plain">
          {{ settings.theme === 'dark' ? '深色' : '浅色' }}
        </el-tag>
      </el-descriptions-item>
      <el-descriptions-item label="字号">{{ fontLabel }}</el-descriptions-item>
      <el-descriptions-item label="操作列">{{ actionLabel }}</el-descriptions-item>
    </el-descriptions>

    <div v-if="permissions.length" class="perm-block">
      <div class="perm-head">
        <h4>权限码（节选）</h4>
        <span class="meta">共 {{ permissions.length }} 项</span>
      </div>
      <div class="perm-tags">
        <el-tag v-for="p in permissions.slice(0, 30)" :key="p" size="small" effect="plain" class="perm-tag">
          {{ p }}
        </el-tag>
      </div>
      <p v-if="permissions.length > 30" class="meta">…仅展示前 30 项</p>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { useAuthStore } from '@/stores/auth';
import { useSettingsStore } from '@/stores/settings';

const auth = useAuthStore();
const settings = useSettingsStore();
const loading = ref(false);

const permissions = computed(() => auth.permissions);
const initial = computed(() => (auth.displayName || '运').slice(0, 1));
const fontLabel = computed(() => ({ sm: '小', md: '中', lg: '大' })[settings.fontSize]);
const actionLabel = computed(() => (settings.tableActionMode === 'label' ? '图标+文字' : '图标'));

async function reload() {
  loading.value = true;
  try {
    await auth.loadProfile();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

onMounted(() => reload());
onActivated(() => {
  void reload();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta { min-width: 0; }
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.4; }
.page-card-head__actions { display: flex; gap: 8px; }

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
.name-cell { display: grid; gap: 2px; line-height: 1.35; }
.display-name { font-size: 18px; font-weight: 650; }
.name-cell small {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
.cell-id { font-family: var(--app-font-mono); font-size: 11px !important; }

.profile-desc { margin-top: 20px; max-width: 560px; }

.perm-block { margin-top: 24px; }
.perm-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 10px;
}
.perm-head h4 { margin: 0; font-size: 14px; }
.perm-tags { display: flex; flex-wrap: wrap; gap: 6px; }
.perm-tag { font-family: var(--app-font-mono); }
.meta { color: var(--el-text-color-secondary); margin: 8px 0 0; font-size: 12px; }
</style>
