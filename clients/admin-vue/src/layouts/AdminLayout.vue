<template>
  <el-container class="layout-main">
    <el-aside :width="sidebarCollapsed ? '64px' : '220px'" class="sidebar">
      <button
        type="button"
        class="brand"
        :class="{ collapsed: sidebarCollapsed }"
        :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
        @click="toggleSidebar"
      >
        <el-icon class="brand-toggle"><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
        <span class="brand-text" :class="{ hidden: sidebarCollapsed }">AI开门柜 OPS</span>
        <span class="brand-mini" :class="{ hidden: !sidebarCollapsed }">柜</span>
      </button>
      <el-scrollbar class="sidebar-scroll">
        <el-menu
          ref="menuRef"
          :key="menuRenderKey"
          :default-active="active"
          :default-openeds="openedMenus"
          router
          :collapse="sidebarCollapsed"
          :collapse-transition="false"
          :background-color="sidebarBg"
          :text-color="sidebarText"
          active-text-color="#fff"
          :popper-class="'sidebar-menu-popper'"
          @open="onSubMenuOpen"
          @close="onSubMenuClose"
        >
          <SidebarMenuTree :nodes="sidebarTree" />
        </el-menu>
      </el-scrollbar>
    </el-aside>

    <el-container class="layout-content" direction="vertical">
      <el-header class="topbar">
        <div class="topbar-left">
          <el-button
            text
            :title="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
            :aria-label="sidebarCollapsed ? '展开侧边栏' : '收起侧边栏'"
            @click="toggleSidebar"
          >
            <el-icon><Expand v-if="sidebarCollapsed" /><Fold v-else /></el-icon>
          </el-button>
          <div class="title-block">
            <AppBreadcrumb />
          </div>
        </div>
        <div class="topbar-right">
          <GlobalSearch />
          <OpsApprovalInbox />
          <el-dropdown trigger="click" @command="onSettingCommand">
            <el-button text title="外观设置" aria-label="外观设置">
              <el-icon><Brush /></el-icon>
            </el-button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="theme">{{
                  settings.theme === 'dark' ? '切换浅色' : '切换深色'
                }}</el-dropdown-item>
                <el-dropdown-item divided disabled>字号</el-dropdown-item>
                <el-dropdown-item command="font-sm">字号：小</el-dropdown-item>
                <el-dropdown-item command="font-md">字号：中</el-dropdown-item>
                <el-dropdown-item command="font-lg">字号：大</el-dropdown-item>
                <el-dropdown-item divided disabled>操作列</el-dropdown-item>
                <el-dropdown-item command="action-icon">操作列：图标</el-dropdown-item>
                <el-dropdown-item command="action-label">操作列：图标+文字</el-dropdown-item>
                <el-dropdown-item divided disabled>主题色</el-dropdown-item>
                <el-dropdown-item
                  v-for="c in PRIMARY_OPTIONS"
                  :key="c.id"
                  :command="'color-' + c.id"
                >
                  <span class="color-dot" :style="{ background: c.color }" /> {{ c.label }}
                </el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
          <el-dropdown trigger="click" @command="onUserCommand">
            <button type="button" class="user-trigger" aria-label="用户菜单">
              <el-avatar :size="32" class="user-avatar">{{ userInitial }}</el-avatar>
              <div class="user-text">
                <span class="user-name">{{ auth.displayName }}</span>
                <span class="user-detail">{{ auth.phone || '暂无' }} · {{ auth.roleText }}</span>
                <span class="user-scope">{{ auth.dataScopeText }}</span>
              </div>
            </button>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>

      <div class="tags-view" @click="hideTagMenu" @contextmenu.prevent="onTagsContextMenu">
        <div ref="tagsScrollRef" class="tags-scroll">
          <span
            v-for="tag in tags"
            :key="tag.path"
            class="tag-wrap"
            :data-path="tag.path"
            :data-title="tag.title"
          >
            <el-tag
              :type="tag.path === route.path ? 'primary' : 'info'"
              closable
              class="tag-item"
              role="link"
              tabindex="0"
              :aria-current="tag.path === route.path ? 'page' : undefined"
              :aria-label="`打开 ${tag.title}`"
              @click="router.push(tag.path)"
              @keydown.enter.prevent="router.push(tag.path)"
              @keydown.space.prevent="router.push(tag.path)"
              @close.prevent="closeTag(tag.path)"
              >{{ tag.title }}</el-tag
            >
          </span>
        </div>
        <div v-if="tags.length > 1" class="tags-actions">
          <el-button text size="small" @click.stop="closeOtherTags">关闭其他</el-button>
        </div>
      </div>

      <Teleport to="body">
        <ul
          v-if="tagMenu.visible"
          class="tag-context-menu"
          :style="{ left: tagMenu.x + 'px', top: tagMenu.y + 'px' }"
          @click.stop
          @contextmenu.prevent
        >
          <li @click="runTagAction('close')">关闭</li>
          <li @click="runTagAction('others')">关闭其他</li>
          <li @click="runTagAction('left')">关闭左侧</li>
          <li @click="runTagAction('right')">关闭右侧</li>
          <li class="danger" @click="confirmCloseAllTags">关闭全部</li>
        </ul>
      </Teleport>

      <el-main id="main-content" class="layout-main-scroll" tabindex="-1">
        <router-view v-slot="{ Component, route: viewRoute }">
          <!-- 字典启停后 bump epoch，重建其他缓存页；字典管理页本身不重建以免丢选中 -->
          <keep-alive :max="12">
            <component
              :is="Component"
              :key="
                viewRoute.name === 'dicts'
                  ? String(viewRoute.path)
                  : `${viewRoute.path}#d${dictRuntimeEpoch}`
              "
            />
          </keep-alive>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessageBox, type MenuInstance } from 'element-plus';
import { Fold, Expand, Brush } from '@element-plus/icons-vue';
import { buildSidebarTree, sidebarOpenKeysForPath } from '@/config/sidebar';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import { dictRuntimeEpoch } from '@/stores/dict-runtime';
import { PRIMARY_OPTIONS, useSettingsStore } from '@/stores/settings';
import { observeTableScrollFit, stopTableScrollFit } from '@/utils/table-scroll-fit';
import AppBreadcrumb from '@/components/AppBreadcrumb.vue';
import GlobalSearch from '@/components/GlobalSearch.vue';
import OpsApprovalInbox from '@/components/OpsApprovalInbox.vue';
import SidebarMenuTree from '@/components/SidebarMenuTree.vue';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { firstAccessiblePath, goPath } = useNavAccess();
const settings = useSettingsStore();
const MAX_TAGS = 12;
const tags = ref<{ path: string; title: string }[]>([]);
const tagsScrollRef = ref<HTMLElement | null>(null);
const menuRef = ref<MenuInstance>();
const openedMenus = ref<string[]>([]);
const menuEpoch = ref(0);
/** Ignore @close while remounting — destroy fires close and would wipe openeds. */
const ignoreMenuClose = ref(false);
const OPENED_MENUS_KEY = 'admin_vue_sidebar_openeds';
const tagMenu = ref({ visible: false, x: 0, y: 0, path: '' });
const compactViewport = ref(false);
/** 窄屏自动收起时，用户临时展开不写 localStorage */
const userExpandedInCompact = ref(false);

const sidebarCollapsed = computed(() => {
  if (compactViewport.value && !userExpandedInCompact.value) return true;
  return settings.sidebarCollapsed;
});

/** Remount only for route/collapse — EP reads default-openeds on mount. Do not remount on @open. */
const menuRenderKey = computed(() => `m${menuEpoch.value}-${sidebarCollapsed.value ? 'c' : 'e'}`);

function toggleSidebar() {
  if (compactViewport.value) {
    userExpandedInCompact.value = !userExpandedInCompact.value;
    return;
  }
  settings.toggleSidebarCollapsed();
}

const sidebarTree = computed(() => buildSidebarTree((item) => auth.canAccessNav(item)));
const active = computed(() => (route.path.startsWith('/devices') ? '/devices' : route.path));
const userInitial = computed(() => (auth.displayName || '运').slice(0, 1));
const sidebarBg = computed(() => (settings.theme === 'dark' ? '#111827' : '#1e293b'));
const sidebarText = computed(() => '#cbd5e1');

function collectDirKeys(nodes: ReturnType<typeof buildSidebarTree>): Set<string> {
  const keys = new Set<string>();
  const walk = (list: typeof nodes) => {
    for (const node of list) {
      if (node.children?.length) {
        keys.add(node.key);
        walk(node.children);
      }
    }
  };
  walk(nodes);
  return keys;
}

function readOpenedMenus(): string[] {
  try {
    const raw = localStorage.getItem(OPENED_MENUS_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed.filter((key) => typeof key === 'string') : [];
  } catch {
    return [];
  }
}

function persistOpenedMenus(keys: string[]) {
  localStorage.setItem(OPENED_MENUS_KEY, JSON.stringify(keys));
}

function remountMenu(nextOpeneds: string[]) {
  ignoreMenuClose.value = true;
  openedMenus.value = nextOpeneds;
  menuEpoch.value += 1;
  nextTick(() => {
    ignoreMenuClose.value = false;
  });
}

function syncOpenedMenusForRoute(path: string, collapsed: boolean) {
  if (collapsed) {
    if (openedMenus.value.length) {
      remountMenu([]);
    }
    return;
  }
  const routeKeys = sidebarOpenKeysForPath(path);
  let next: string[];
  if (routeKeys.length) {
    next = routeKeys;
    persistOpenedMenus(next);
  } else {
    const valid = collectDirKeys(sidebarTree.value);
    next = readOpenedMenus().filter((key) => valid.has(key));
  }
  const prev = openedMenus.value.join('\0');
  const joined = next.join('\0');
  if (prev === joined) return;
  remountMenu(next);
}

function collectOpenedWithParent(key: string, parent: string): Set<string> {
  const next = new Set<string>();
  for (const opened of openedMenus.value) {
    if (opened === parent || opened === key || opened.startsWith(`${key}:`)) {
      next.add(opened);
    } else if (!opened.startsWith(`${parent}:`)) {
      next.add(opened);
    }
  }
  next.add(parent);
  next.add(key);
  return next;
}

function collectOpenedRoot(key: string): Set<string> {
  const next = new Set<string>();
  for (const opened of openedMenus.value) {
    if (opened === key || opened.startsWith(`${key}:`)) next.add(opened);
  }
  next.add(key);
  return next;
}

function closeRemovedSubMenus(prevKeys: Set<string>, next: Set<string>) {
  const menu = menuRef.value;
  if (!menu) return;
  for (const keyToClose of prevKeys) {
    if (!next.has(keyToClose)) menu.close(keyToClose);
  }
}

function onSubMenuOpen(key: string) {
  // 同级唯一展开（勿用 EP unique-opened：会禁止「一级+二级」同时展开）
  const parent = key.includes(':') ? key.slice(0, key.lastIndexOf(':')) : null;
  const next = parent ? collectOpenedWithParent(key, parent) : collectOpenedRoot(key);

  const prevKeys = new Set(openedMenus.value);
  const list = [...next];
  openedMenus.value = list;
  persistOpenedMenus(list);
  // Close removed keys via EP API — remounting here races with @close and snaps back to route keys
  closeRemovedSubMenus(prevKeys, next);
}

function onSubMenuClose(key: string) {
  if (ignoreMenuClose.value) return;
  if (!openedMenus.value.includes(key)) return;
  openedMenus.value = openedMenus.value.filter(
    (item) => item !== key && !item.startsWith(`${key}:`)
  );
  persistOpenedMenus(openedMenus.value);
}

watch(
  () => route.path,
  (path) => syncOpenedMenusForRoute(path, sidebarCollapsed.value),
  { immediate: true }
);

watch(sidebarCollapsed, (collapsed) => syncOpenedMenusForRoute(route.path, collapsed));

watch(
  () => route.path,
  (path) => {
    if (path === '/login') return;
    const t = (route.meta.title as string) || path;
    if (!tags.value.find((x) => x.path === path)) {
      tags.value.push({ path, title: t });
    }
    // 超出上限时关闭最旧的非当前标签，避免标签栏无限堆积
    while (tags.value.length > MAX_TAGS) {
      const dropIdx = tags.value.findIndex((x) => x.path !== path);
      if (dropIdx < 0) break;
      tags.value.splice(dropIdx, 1);
    }
    scrollActiveTagIntoView();
  },
  { immediate: true }
);

function scrollActiveTagIntoView() {
  nextTick(() => {
    const root = tagsScrollRef.value;
    if (!root) return;
    const escaped =
      typeof CSS !== 'undefined' && CSS.escape
        ? CSS.escape(route.path)
        : route.path.replaceAll('"', '\\"');
    const active = root.querySelector(`.tag-wrap[data-path="${escaped}"]`) as HTMLElement | null;
    if (!active) return;
    // 只用标签条横向滚动；禁止 scrollIntoView，避免带动右侧主内容区纵向跳动
    const rootRect = root.getBoundingClientRect();
    const activeRect = active.getBoundingClientRect();
    if (activeRect.left < rootRect.left) {
      root.scrollLeft -= rootRect.left - activeRect.left + 8;
    } else if (activeRect.right > rootRect.right) {
      root.scrollLeft += activeRect.right - rootRect.right + 8;
    }
  });
}

function navigateAfterClose() {
  if (tags.value.some((t) => t.path === route.path)) return;
  const fallback = tags.value[tags.value.length - 1];
  if (fallback) {
    router.push(fallback.path);
    return;
  }
  goPath(firstAccessiblePath());
}

function closeTag(path: string) {
  tags.value = tags.value.filter((t) => t.path !== path);
  hideTagMenu();
  navigateAfterClose();
}

function closeOtherTags() {
  const current = route.path;
  tags.value = tags.value.filter((t) => t.path === current);
  hideTagMenu();
  if (!tags.value.length) {
    goPath(firstAccessiblePath());
  }
}

function openTagMenu(e: MouseEvent, tag: { path: string; title: string }) {
  const menuW = 160;
  const menuH = 220;
  const x = Math.min(e.clientX, globalThis.innerWidth - menuW - 8);
  const y = Math.min(e.clientY, globalThis.innerHeight - menuH - 8);
  tagMenu.value = { visible: true, x: Math.max(8, x), y: Math.max(8, y), path: tag.path };
}

function onTagsContextMenu(e: MouseEvent) {
  const el = (e.target as HTMLElement | null)?.closest?.('.tag-wrap') as HTMLElement | null;
  if (!el?.dataset.path) return;
  openTagMenu(e, { path: el.dataset.path, title: el.dataset.title || el.dataset.path });
}

function hideTagMenu() {
  tagMenu.value.visible = false;
}

function runTagAction(action: 'close' | 'others' | 'left' | 'right' | 'all') {
  const target = tagMenu.value.path;
  const idx = tags.value.findIndex((t) => t.path === target);
  if (idx < 0) {
    hideTagMenu();
    return;
  }
  if (action === 'close') {
    closeTag(target);
    return;
  }
  if (action === 'all') {
    tags.value = [];
    hideTagMenu();
    goPath(firstAccessiblePath());
    return;
  }
  if (action === 'others') {
    tags.value = tags.value.filter((t) => t.path === target);
  } else if (action === 'left') {
    tags.value = tags.value.filter((_, i) => i >= idx);
  } else if (action === 'right') {
    tags.value = tags.value.filter((_, i) => i <= idx);
  }
  hideTagMenu();
  navigateAfterClose();
  if (!tags.value.some((t) => t.path === route.path) && tags.value.some((t) => t.path === target)) {
    router.push(target);
  }
}

async function confirmCloseAllTags() {
  try {
    await ElMessageBox.confirm('确定关闭全部页签吗？', '关闭全部', {
      type: 'warning',
      confirmButtonText: '关闭全部',
      cancelButtonText: '取消'
    });
  } catch {
    hideTagMenu();
    return;
  }
  runTagAction('all');
}

async function onUserCommand(cmd: string) {
  if (cmd === 'profile') router.push('/profile');
  if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定退出登录吗？', '退出登录', {
        type: 'warning',
        confirmButtonText: '退出',
        cancelButtonText: '取消'
      });
    } catch {
      return;
    }
    await auth.logout();
    router.push('/login');
  }
}

function onSettingCommand(cmd: string) {
  if (cmd === 'theme') settings.toggleTheme();
  if (cmd === 'font-sm') settings.setFontSize('sm');
  if (cmd === 'font-md') settings.setFontSize('md');
  if (cmd === 'font-lg') settings.setFontSize('lg');
  if (cmd === 'action-icon') settings.setTableActionMode('icon');
  if (cmd === 'action-label') settings.setTableActionMode('label');
  if (cmd.startsWith('color-')) settings.setPrimaryColor(cmd.replace('color-', ''));
}

function syncSidebarWithViewport() {
  const compact = globalThis.innerWidth <= 1200;
  if (!compact) {
    userExpandedInCompact.value = false;
  }
  compactViewport.value = compact;
}

function onWindowFocus() {
  auth.refreshPermissions().catch(() => {});
}

onMounted(() => {
  settings.init();
  syncSidebarWithViewport();
  auth.refreshPermissions().catch(() => {});
  observeTableScrollFit(document.getElementById('main-content') as HTMLElement);
  globalThis.addEventListener('click', hideTagMenu);
  globalThis.addEventListener('scroll', hideTagMenu, true);
  globalThis.addEventListener('resize', syncSidebarWithViewport);
  globalThis.addEventListener('focus', onWindowFocus);
});

onUnmounted(() => {
  stopTableScrollFit();
  globalThis.removeEventListener('click', hideTagMenu);
  globalThis.removeEventListener('scroll', hideTagMenu, true);
  globalThis.removeEventListener('resize', syncSidebarWithViewport);
  globalThis.removeEventListener('focus', onWindowFocus);
});
</script>

<style scoped>
.layout-main {
  width: 100%;
  height: 100vh;
  height: 100dvh;
  max-height: 100vh;
  max-height: 100dvh;
  overflow: hidden;
  background: var(--layout-bg);
}
.sidebar {
  background: var(--layout-sidebar);
  height: 100vh;
  height: 100dvh;
  transition: width 0.15s ease;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
  /* 侧栏内部变化不向外传导布局/滚动锚定 */
  contain: layout style;
  overscroll-behavior: contain;
}
.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  color: #f8fafc;
  font-weight: 700;
  padding: 14px 16px;
  font-size: 0.95rem;
  white-space: nowrap;
  overflow: hidden;
  cursor: pointer;
  user-select: none;
  flex-shrink: 0;
  border: none;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  background: transparent;
  text-align: left;
  font-family: inherit;
}
.brand:hover {
  background: rgba(255, 255, 255, 0.06);
}
.brand.collapsed {
  justify-content: center;
  padding: 14px 0;
}
.brand-toggle {
  font-size: 18px;
  color: #94a3b8;
  flex-shrink: 0;
}
.brand-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
}
.brand-mini {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  border-radius: 8px;
  background: var(--app-primary);
  font-size: 14px;
}
.brand-text.hidden,
.brand-mini.hidden {
  display: none;
}
.sidebar-scroll {
  flex: 1;
  min-height: 0;
  overscroll-behavior: contain;
}
:deep(.sidebar-scroll .el-scrollbar__wrap) {
  overscroll-behavior: contain;
}

.layout-content {
  flex: 1;
  width: auto;
  min-width: 0;
  height: 100vh;
  height: 100dvh;
  max-height: 100vh;
  max-height: 100dvh;
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.topbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 12px 0 8px;
  background: var(--layout-topbar);
  border-bottom: 1px solid var(--layout-border);
  height: var(--header-height);
  min-height: var(--header-height);
  flex-shrink: 0;
  overflow: hidden;
}
.topbar-left {
  display: flex;
  align-items: center;
  gap: 4px;
  min-width: 0;
  flex: 1 1 auto;
  overflow: hidden;
}
.topbar-left > .el-button {
  position: relative;
  z-index: 2;
  flex-shrink: 0;
}
.title-block {
  min-width: 0;
  flex: 1 1 auto;
  display: flex;
  align-items: center;
  overflow: hidden;
}
.topbar-right {
  display: flex;
  align-items: center;
  gap: 6px;
  flex: 0 1 auto;
  min-width: 0;
  overflow: hidden;
}
.user-trigger {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 4px 8px;
  border-radius: 8px;
  min-width: 0;
  flex-shrink: 1;
  border: none;
  background: transparent;
  font: inherit;
  color: inherit;
  text-align: left;
}
.user-trigger:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px var(--el-color-primary);
}
.user-trigger:hover {
  background: var(--layout-hover);
}
.user-avatar {
  background: var(--app-primary);
  color: #fff;
  flex-shrink: 0;
}
.user-text {
  line-height: 1.3;
  min-width: 0;
  max-width: clamp(72px, 12vw, 180px);
}
.user-name {
  display: block;
  font-size: 13px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.user-detail {
  display: block;
  font-size: 11px;
  color: var(--layout-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.user-scope {
  display: block;
  font-size: 10px;
  color: var(--layout-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  opacity: 0.9;
}
@media (max-width: 900px) {
  .title-block {
    flex: 1 1 120px;
    min-width: 88px;
  }
  .topbar-right {
    flex: 0 0 auto;
    overflow: visible;
  }
  .user-text {
    display: none;
  }
  .user-trigger {
    padding: 4px;
    flex-shrink: 0;
  }
}
.tags-view {
  display: flex;
  align-items: center;
  gap: 8px;
  /* 固定高度：避免横向滚动条显隐把主内容区顶上/顶下 */
  height: 40px;
  min-height: 40px;
  max-height: 40px;
  padding: 0 12px 0 16px;
  background: var(--layout-topbar);
  border-bottom: 1px solid var(--layout-border);
  flex-shrink: 0;
  box-sizing: border-box;
  overflow: hidden;
}
.tags-scroll {
  display: flex;
  flex-wrap: nowrap;
  align-items: center;
  gap: 6px;
  min-width: 0;
  flex: 1 1 auto;
  height: 100%;
  overflow-x: auto;
  overflow-y: hidden;
  scrollbar-gutter: stable;
  overscroll-behavior-x: contain;
}
.tags-actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding-left: 4px;
  border-left: 1px solid var(--layout-border);
}
@media (max-width: 640px) {
  .tags-actions {
    display: none;
  }
}
.tag-item {
  flex-shrink: 0;
  cursor: pointer;
}
.tag-wrap {
  display: inline-flex;
  flex-shrink: 0;
}
.layout-main-scroll {
  flex: 1;
  width: 100%;
  min-width: 0;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: var(--admin-space-md, 12px);
  padding: var(--admin-space-md, 12px) var(--admin-space-lg, 16px);
  box-sizing: border-box;
  /* 页面级滚动：内容超宽时由页面横向滚动，而非表格内部滚动 */
  overflow: auto;
  /* 切页时滚动条显隐不再挤动内容宽度 */
  scrollbar-gutter: stable;
  /* 侧栏展开/滚动条变化时，禁止浏览器滚动锚定带动主区上下跳 */
  overflow-anchor: none;
  background: var(--layout-bg);
  color: var(--layout-text);
  overscroll-behavior: contain;
}

/* 页面根节点横向铺满主区；允许超宽内容撑开触发页面级横向滚动 */
.layout-main-scroll > * {
  width: 100%;
  max-width: none;
  min-width: 0;
  box-sizing: border-box;
}
/* 客流坪效：不许撑开主区，否则白卡片整页横滑、左右边「缺一截」 */
.layout-main-scroll > .footfall-page,
.layout-main-scroll > .page-fill > .footfall-page {
  max-width: 100% !important;
}
.color-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
  margin-right: 6px;
}
:deep(.el-menu--collapse) {
  width: 64px;
}
:deep(.el-sub-menu__title) {
  cursor: pointer;
  user-select: none;
}
:deep(.el-sub-menu__icon-arrow) {
  transition: transform 0.2s ease;
}
:deep(.el-sub-menu.is-opened > .el-sub-menu__title .el-sub-menu__icon-arrow) {
  transform: rotate(180deg);
}
:deep(.el-sub-menu__title:hover),
:deep(.el-menu-item:hover) {
  background-color: rgba(255, 255, 255, 0.06) !important;
}
:deep(.el-sub-menu .el-menu-item) {
  min-height: 44px;
}
:deep(.el-sub-menu .el-sub-menu > .el-sub-menu__title) {
  padding-left: 40px !important;
}
:deep(.el-sub-menu .el-sub-menu .el-menu-item) {
  padding-left: 56px !important;
}
:deep(.el-sub-menu__title),
:deep(.el-menu-item) {
  height: 44px;
}
:deep(.sidebar-scroll .el-scrollbar__view) {
  padding-bottom: 8px;
}
</style>

<style>
.sidebar-menu-popper.el-popper {
  border: none;
  box-shadow: 0 8px 24px rgba(15, 23, 42, 0.18);
}
.sidebar-menu-popper .el-menu {
  min-width: 180px;
}
.sidebar-menu-popper .el-sub-menu .el-menu-item {
  min-height: 40px;
  padding-left: 28px !important;
}
.sidebar-menu-popper .el-sub-menu__title {
  font-weight: 600;
}
.tag-context-menu {
  position: fixed;
  z-index: 5000;
  margin: 0;
  padding: 6px 0;
  list-style: none;
  min-width: 148px;
  border-radius: 8px;
  border: 1px solid var(--layout-border);
  background: var(--layout-card);
  box-shadow: 0 10px 28px rgba(0, 0, 0, 0.28);
  color: var(--layout-text);
}
.tag-context-menu li {
  padding: 8px 14px;
  font-size: 13px;
  cursor: pointer;
}
.tag-context-menu li:hover {
  background: var(--layout-hover);
}
.tag-context-menu li.danger {
  color: #f87171;
}
</style>
