<template>
  <el-card class="page-card profile-card">
    <template #header>个人中心</template>
    <div class="profile-head">
      <el-avatar :size="64" class="avatar">{{ initial }}</el-avatar>
      <div>
        <h3>{{ auth.displayName }}</h3>
        <p class="meta">{{ auth.phone || '-' }}</p>
        <p class="meta">用户 ID：{{ auth.userId }}</p>
      </div>
    </div>
    <el-descriptions :column="1" border style="margin-top:20px">
      <el-descriptions-item label="角色">{{ auth.roleText }}</el-descriptions-item>
      <el-descriptions-item label="权限数">{{ auth.profile?.permissionCount ?? permissions.length }}</el-descriptions-item>
      <el-descriptions-item label="主题">{{ settings.theme === 'dark' ? '深色' : '浅色' }}</el-descriptions-item>
      <el-descriptions-item label="字号">{{ fontLabel }}</el-descriptions-item>
      <el-descriptions-item label="操作列">{{ actionLabel }}</el-descriptions-item>
    </el-descriptions>
    <div class="perm-block" v-if="permissions.length">
      <h4>权限码（节选）</h4>
      <el-tag v-for="p in permissions.slice(0, 30)" :key="p" size="small" class="perm-tag">{{ p }}</el-tag>
      <p v-if="permissions.length > 30" class="meta">…共 {{ permissions.length }} 项</p>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useAuthStore } from '@/stores/auth';
import { useSettingsStore } from '@/stores/settings';

const auth = useAuthStore();
const settings = useSettingsStore();

const permissions = computed(() => auth.permissions);
const initial = computed(() => (auth.displayName || '运').slice(0, 1));
const fontLabel = computed(() => ({ sm: '小', md: '中', lg: '大' })[settings.fontSize]);
const actionLabel = computed(() => (settings.tableActionMode === 'label' ? '图标+文字' : '图标'));

onMounted(() => auth.loadProfile());
</script>

<style scoped>
.profile-head { display: flex; gap: 16px; align-items: center; }
.profile-head h3 { margin: 0 0 4px; }
.avatar { background: var(--app-primary); color: #fff; font-size: 24px; }
.meta { color: var(--layout-muted); margin: 0; font-size: 13px; }
.perm-block { margin-top: 20px; }
.perm-tag { margin: 0 6px 6px 0; }
</style>
