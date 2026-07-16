import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

export type ThemeMode = 'light' | 'dark';
export type FontSize = 'sm' | 'md' | 'lg';
export type TableActionMode = 'icon' | 'label';

const THEME_KEY = 'admin_vue_theme';
const FONT_KEY = 'admin_vue_font_size';
const PRIMARY_KEY = 'admin_vue_primary';
const SIDEBAR_COLLAPSED_KEY = 'admin_vue_sidebar_collapsed';
const TABLE_ACTION_KEY = 'admin_vue_table_action';

function readSidebarCollapsed(): boolean {
  const saved = localStorage.getItem(SIDEBAR_COLLAPSED_KEY);
  if (saved === '1') return true;
  if (saved === '0') return false;
  return false;
}

const PRIMARY_COLORS: Record<string, string> = {
  teal: '#0f766e',
  blue: '#2563eb',
  violet: '#7c3aed',
  orange: '#ea580c'
};

export const PRIMARY_OPTIONS = [
  { id: 'teal', label: '青绿', color: PRIMARY_COLORS.teal },
  { id: 'blue', label: '蓝色', color: PRIMARY_COLORS.blue },
  { id: 'violet', label: '紫色', color: PRIMARY_COLORS.violet },
  { id: 'orange', label: '橙色', color: PRIMARY_COLORS.orange }
] as const;

function applyDom(theme: ThemeMode, fontSize: FontSize, primaryId: string) {
  const root = document.documentElement;
  root.setAttribute('data-theme', theme);
  root.setAttribute('data-font-size', fontSize);
  const color = PRIMARY_COLORS[primaryId] || PRIMARY_COLORS.teal;
  root.style.setProperty('--app-primary', color);
  root.style.setProperty('--el-color-primary', color);
}

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || 'light');
  const fontSize = ref<FontSize>((localStorage.getItem(FONT_KEY) as FontSize) || 'md');
  const primaryColor = ref(localStorage.getItem(PRIMARY_KEY) || 'teal');
  const sidebarCollapsed = ref(readSidebarCollapsed());
  const tableActionMode = ref<TableActionMode>(
    (localStorage.getItem(TABLE_ACTION_KEY) as TableActionMode) === 'label' ? 'label' : 'icon'
  );

  function persist() {
    localStorage.setItem(THEME_KEY, theme.value);
    localStorage.setItem(FONT_KEY, fontSize.value);
    localStorage.setItem(PRIMARY_KEY, primaryColor.value);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, sidebarCollapsed.value ? '1' : '0');
    localStorage.setItem(TABLE_ACTION_KEY, tableActionMode.value);
    applyDom(theme.value, fontSize.value, primaryColor.value);
  }

  function toggleSidebarCollapsed() {
    sidebarCollapsed.value = !sidebarCollapsed.value;
    persist();
  }

  function setSidebarCollapsed(collapsed: boolean) {
    if (sidebarCollapsed.value === collapsed) return;
    sidebarCollapsed.value = collapsed;
    persist();
  }

  function toggleTheme() {
    theme.value = theme.value === 'dark' ? 'light' : 'dark';
    persist();
  }

  function setFontSize(size: FontSize) {
    fontSize.value = size;
    persist();
  }

  function setPrimaryColor(id: string) {
    primaryColor.value = id;
    persist();
  }

  function setTableActionMode(mode: TableActionMode) {
    tableActionMode.value = mode === 'label' ? 'label' : 'icon';
    persist();
  }

  function init() {
    applyDom(theme.value, fontSize.value, primaryColor.value);
  }

  watch([theme, fontSize, primaryColor, sidebarCollapsed, tableActionMode], persist);

  return {
    theme, fontSize, primaryColor, sidebarCollapsed, tableActionMode,
    toggleTheme, setFontSize, setPrimaryColor, setTableActionMode,
    toggleSidebarCollapsed, setSidebarCollapsed, init, PRIMARY_COLORS
  };
});
