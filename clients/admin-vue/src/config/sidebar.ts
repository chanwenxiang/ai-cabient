import type { Component } from 'vue';
import {
  Box, Briefcase, Coin, Collection, DataAnalysis, DataBoard, Document, Goods, House, Key, Lock, Menu, Monitor, Money, Notebook, OfficeBuilding, Operation, Setting, Tools, Upload, User, UserFilled, View, Wallet, Warning
} from '@element-plus/icons-vue';
import { NAV_ITEMS, type NavItem } from '@/config/menu';

export interface SidebarGroup {
  key: string;
  label: string;
  icon: Component;
  items: { path: string; title: string; icon: Component }[];
}

const PATH_ICONS: Record<string, Component> = {
  '/analytics': DataAnalysis,
  '/reports': DataBoard,
  '/finance': Money,
  '/devices': Monitor,
  '/sessions': Key,
  '/upload-queue': Upload,
  '/orders': Document,
  '/skus': Goods,
  '/recognition-demo': View,
  '/disputes': Warning,
  '/exceptions': Warning,
  '/replenishment': Box,
  '/merchants': OfficeBuilding,
  '/reconciliation': Coin,
  '/warehouse': House,
  '/recharges': Wallet,
  '/users': Wallet,
  '/risk': Lock,
  '/operators': User,
  '/roles': UserFilled,
  '/menus': Menu,
  '/dicts': Collection,
  '/system-configs': Tools,
  '/promotions': DataAnalysis,
  '/coupons': Goods,
  '/feedback': Warning,
  '/announcements': Document,
  '/audit': Notebook,
  '/profile': UserFilled
};

function itemsForGroup(group: string, canAccess?: (item: NavItem) => boolean) {
  return NAV_ITEMS
    .filter((item) => item.group === group)
    .filter((item) => (canAccess ? canAccess(item) : true))
    .map((item) => ({
      path: item.path,
      title: item.title,
      icon: PATH_ICONS[item.path] ?? Document
    }));
}

/** 静态全量侧栏（兼容旧引用） */
export const SIDEBAR_GROUPS: SidebarGroup[] = [
  { key: 'biz', label: '业务', icon: Briefcase, items: itemsForGroup('业务') },
  { key: 'ops', label: '运营', icon: Operation, items: itemsForGroup('运营') },
  { key: 'sys', label: '系统', icon: Setting, items: itemsForGroup('系统').filter((i) => i.path !== '/profile') }
];

/** 按权限过滤后的侧栏（若依：菜单随角色权限裁剪） */
export function buildSidebarGroups(canAccess: (item: NavItem) => boolean): SidebarGroup[] {
  return [
    { key: 'biz', label: '业务', icon: Briefcase, items: itemsForGroup('业务', canAccess) },
    { key: 'ops', label: '运营', icon: Operation, items: itemsForGroup('运营', canAccess) },
    {
      key: 'sys',
      label: '系统',
      icon: Setting,
      items: itemsForGroup('系统', canAccess).filter((i) => i.path !== '/profile')
    }
  ].filter((g) => g.items.length > 0);
}

export function sidebarGroupKeyForPath(path: string): string | null {
  const normalized = path.startsWith('/devices/') ? '/devices' : path;
  const item = NAV_ITEMS.find((entry) => entry.path === normalized);
  if (!item) return null;
  if (item.group === '业务') return 'biz';
  if (item.group === '运营') return 'ops';
  if (item.group === '系统') return 'sys';
  return null;
}
