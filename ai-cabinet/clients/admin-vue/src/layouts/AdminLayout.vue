<template>
  <el-container class="layout-main">
    <el-aside :width="collapsed ? '64px' : '220px'" class="sidebar">
      <div class="brand">AI开门柜 OPS</div>
      <el-menu
        :default-active="active"
        router
        :collapse="collapsed"
        :background-color="sidebarBg"
        :text-color="sidebarText"
        active-text-color="#fff"
      >
        <el-menu-item index="/dashboard"><el-icon><Odometer /></el-icon><span>运营工作台</span></el-menu-item>
        <el-menu-item-group title="业务">
          <el-menu-item index="/devices"><el-icon><Monitor /></el-icon><span>设备管理</span></el-menu-item>
          <el-menu-item index="/sessions"><el-icon><Key /></el-icon><span>开门记录</span></el-menu-item>
          <el-menu-item index="/upload-queue"><el-icon><Upload /></el-icon><span>录像上传</span></el-menu-item>
          <el-menu-item index="/orders"><el-icon><Document /></el-icon><span>订单管理</span></el-menu-item>
          <el-menu-item index="/skus"><el-icon><Goods /></el-icon><span>商品管理</span></el-menu-item>
          <el-menu-item index="/disputes"><el-icon><Warning /></el-icon><span>争议审核</span></el-menu-item>
        </el-menu-item-group>
        <el-menu-item-group title="运营">
          <el-menu-item index="/replenishment"><el-icon><Box /></el-icon><span>补货</span></el-menu-item>
          <el-menu-item index="/merchants"><el-icon><OfficeBuilding /></el-icon><span>商户分账</span></el-menu-item>
          <el-menu-item index="/reconciliation"><el-icon><Coin /></el-icon><span>对账</span></el-menu-item>
          <el-menu-item index="/warehouse"><el-icon><House /></el-icon><span>仓库</span></el-menu-item>
          <el-menu-item index="/recharges"><el-icon><Wallet /></el-icon><span>充值管理</span></el-menu-item>
          <el-menu-item index="/vision-mappings"><el-icon><View /></el-icon><span>识别配置</span></el-menu-item>
          <el-menu-item index="/risk"><el-icon><Lock /></el-icon><span>风控</span></el-menu-item>
        </el-menu-item-group>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="topbar">
        <div class="topbar-left">
          <el-button text @click="collapsed = !collapsed"><el-icon><Fold /></el-icon></el-button>
          <div class="title-block">
            <AppBreadcrumb />
            <h2>{{ title }}</h2>
          </div>
        </div>
        <div class="topbar-right">
          <GlobalSearch />
          <el-dropdown trigger="click" @command="onSettingCommand">
            <el-button text title="外观设置">
              <el-icon><Brush /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="theme">{{ settings.theme === 'dark' ? '切换浅色' : '切换深色' }}</el-dropdown-item>
                <el-dropdown-item divided disabled>字号</el-dropdown-item>
                <el-dropdown-item command="font-sm">字号：小</el-dropdown-item>
                <el-dropdown-item command="font-md">字号：中</el-dropdown-item>
                <el-dropdown-item command="font-lg">字号：大</el-dropdown-item>
                <el-dropdown-item divided disabled>主题色</el-dropdown-item>
                <el-dropdown-item v-for="c in PRIMARY_OPTIONS" :key="c.id" :command="'color-' + c.id">
                  <span class="color-dot" :style="{ background: c.color }" /> {{ c.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="onUserCommand">
            <div class="user-trigger">
              <el-avatar :size="32" class="user-avatar">{{ userInitial }}</el-avatar>
              <div class="user-text">
                <span class="user-name">{{ auth.displayName }}</span>
                <span class="user-detail">{{ auth.phone }} · {{ auth.roleText }}</span>
              </div>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <div v-if="tags.length" class="tags-view">
        <el-tag
          v-for="tag in tags"
          :key="tag.path"
          :type="tag.path === route.path ? 'primary' : 'info'"
          closable
          class="tag-item"
          @click="router.push(tag.path)"
          @close="closeTag(tag.path)"
        >{{ tag.title }}</el-tag>
      </div>
      <el-main><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import {
  Monitor, Document, Warning, OfficeBuilding, Fold, Odometer, Key, Upload, Goods, Box, Coin, House, Wallet, View, Lock, Brush
} from '@element-plus/icons-vue';
import { useAuthStore } from '@/stores/auth';
import { PRIMARY_OPTIONS, useSettingsStore } from '@/stores/settings';
import AppBreadcrumb from '@/components/AppBreadcrumb.vue';
import GlobalSearch from '@/components/GlobalSearch.vue';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const settings = useSettingsStore();
const collapsed = ref(false);
const tags = ref<{ path: string; title: string }[]>([]);

const active = computed(() => (route.path.startsWith('/devices') ? '/devices' : route.path));
const title = computed(() => (route.meta.title as string) || '运营后台');
const userInitial = computed(() => (auth.displayName || '运').slice(0, 1));
const sidebarBg = computed(() => (settings.theme === 'dark' ? '#111827' : '#1e293b'));
const sidebarText = computed(() => '#cbd5e1');

watch(
  () => route.path,
  (path) => {
    if (path === '/login') return;
    const t = (route.meta.title as string) || path;
    if (!tags.value.find((x) => x.path === path)) tags.value.push({ path, title: t });
  },
  { immediate: true }
);

function closeTag(path: string) {
  tags.value = tags.value.filter((t) => t.path !== path);
}

function onUserCommand(cmd: string) {
  if (cmd === 'profile') router.push('/profile');
  if (cmd === 'logout') {
    auth.logout();
    router.push('/login');
  }
}

function onSettingCommand(cmd: string) {
  if (cmd === 'theme') settings.toggleTheme();
  if (cmd === 'font-sm') settings.setFontSize('sm');
  if (cmd === 'font-md') settings.setFontSize('md');
  if (cmd === 'font-lg') settings.setFontSize('lg');
  if (cmd.startsWith('color-')) settings.setPrimaryColor(cmd.replace('color-', ''));
}

onMounted(() => {
  settings.init();
  auth.loadProfile();
});
</script>

<style scoped>
.sidebar { background: var(--layout-sidebar); min-height: 100vh; transition: width 0.2s; }
.brand { color: #f8fafc; font-weight: 700; padding: 16px; font-size: 0.95rem; white-space: nowrap; overflow: hidden; }
.topbar {
  display: flex; align-items: center; justify-content: space-between;
  background: var(--layout-topbar); border-bottom: 1px solid var(--layout-border);
  height: var(--header-height);
}
.topbar-left { display: flex; align-items: center; gap: 8px; min-width: 0; flex: 1; }
.title-block { min-width: 0; }
.title-block h2 { margin: 0; font-size: 1.05rem; font-weight: 600; }
.topbar-right { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.user-trigger { display: flex; align-items: center; gap: 8px; cursor: pointer; padding: 4px 8px; border-radius: 8px; }
.user-trigger:hover { background: var(--layout-hover); }
.user-avatar { background: var(--app-primary); color: #fff; flex-shrink: 0; }
.user-text { line-height: 1.3; max-width: 180px; }
.user-name { display: block; font-size: 13px; font-weight: 600; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.user-detail { display: block; font-size: 11px; color: var(--layout-muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tags-view {
  display: flex; flex-wrap: nowrap; overflow-x: auto; gap: 8px;
  padding: 8px 16px; background: var(--layout-topbar); border-bottom: 1px solid var(--layout-border);
}
.tag-item { flex-shrink: 0; cursor: pointer; }
.color-dot { display: inline-block; width: 10px; height: 10px; border-radius: 50%; margin-right: 6px; }
:deep(.el-menu-item-group__title) { color: #64748b; padding-left: 20px; }
</style>
