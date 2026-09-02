import { defineStore } from 'pinia';
import { ref, watch } from 'vue';

export type ThemeMode = 'light' | 'dark';
export type FontSize = 'sm' | 'md' | 'lg';
export type TableActionMode = 'icon' | 'label';

const THEME_KEY = 'admin_vue_theme';
const FONT_KEY = 'admin_vue_font_size';
const PRIMARY_KEY = 'admin_vue_primary';
const SIDEBAR_COLLAPSED_KEY = 'admin_vue_sidebar_collapsed';
const SIDEBAR_USER_SET_KEY = 'admin_vue_sidebar_user_set';
const TABLE_ACTION_KEY = 'admin_vue_table_action';

function readSidebarCollapsed(): boolean {
  const saved = localStorage.getItem(SIDEBAR_COLLAPSED_KEY);
  const userSet = localStorage.getItem(SIDEBAR_USER_SET_KEY) === '1';
  // 未手动切换过侧栏时一律默认展开（IMP-003）
  if (!userSet) return false;
  if (saved === '1') return true;
  if (saved === '0') return false;
  return false;
}

function readTableActionMode(): TableActionMode {
  const saved = localStorage.getItem(TABLE_ACTION_KEY);
  if (saved === 'icon' || saved === 'label') return saved;
  return 'label';
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

function mixHex(hex: string, target: string, weight: number): string {
  const parse = (h: string) => {
    const n = h.replace('#', '');
    return [
      Number.parseInt(n.slice(0, 2), 16),
      Number.parseInt(n.slice(2, 4), 16),
      Number.parseInt(n.slice(4, 6), 16)
    ];
  };
  const [r1, g1, b1] = parse(hex);
  const [r2, g2, b2] = parse(target);
  const w = Math.min(1, Math.max(0, weight));
  const to = (v: number) => v.toString(16).padStart(2, '0');
  return `#${to(Math.round(r1 + (r2 - r1) * w))}${to(Math.round(g1 + (g2 - g1) * w))}${to(Math.round(b1 + (b2 - b1) * w))}`;
}

function applyDom(theme: ThemeMode, fontSize: FontSize, primaryId: string) {
  const root = document.documentElement;
  root.dataset.theme = theme;
  root.dataset.fontSize = fontSize;
  const color = PRIMARY_COLORS[primaryId] || PRIMARY_COLORS.teal;
  root.style.setProperty('--app-primary', color);
  root.style.setProperty('--el-color-primary', color);
  root.style.setProperty('--el-color-primary-light-3', mixHex(color, '#ffffff', 0.3));
  root.style.setProperty('--el-color-primary-light-5', mixHex(color, '#ffffff', 0.5));
  root.style.setProperty('--el-color-primary-light-7', mixHex(color, '#ffffff', 0.7));
  root.style.setProperty('--el-color-primary-light-8', mixHex(color, '#ffffff', 0.8));
  root.style.setProperty('--el-color-primary-light-9', mixHex(color, '#ffffff', 0.9));
  root.style.setProperty('--el-color-primary-dark-2', mixHex(color, '#000000', 0.2));
}

export const useSettingsStore = defineStore('settings', () => {
  const theme = ref<ThemeMode>((localStorage.getItem(THEME_KEY) as ThemeMode) || 'light');
  const fontSize = ref<FontSize>((localStorage.getItem(FONT_KEY) as FontSize) || 'md');
  const primaryColor = ref(localStorage.getItem(PRIMARY_KEY) || 'teal');
  const sidebarCollapsed = ref(readSidebarCollapsed());
  const tableActionMode = ref<TableActionMode>(readTableActionMode());

  function persist() {
    localStorage.setItem(THEME_KEY, theme.value);
    localStorage.setItem(FONT_KEY, fontSize.value);
    localStorage.setItem(PRIMARY_KEY, primaryColor.value);
    localStorage.setItem(SIDEBAR_COLLAPSED_KEY, sidebarCollapsed.value ? '1' : '0');
    localStorage.setItem(TABLE_ACTION_KEY, tableActionMode.value);
    applyDom(theme.value, fontSize.value, primaryColor.value);
  }

  function toggleSidebarCollapsed() {
    localStorage.setItem(SIDEBAR_USER_SET_KEY, '1');
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
    theme,
    fontSize,
    primaryColor,
    sidebarCollapsed,
    tableActionMode,
    toggleTheme,
    setFontSize,
    setPrimaryColor,
    setTableActionMode,
    toggleSidebarCollapsed,
    setSidebarCollapsed,
    init,
    PRIMARY_COLORS
  };
});
