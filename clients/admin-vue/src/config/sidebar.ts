import type { Component } from 'vue';
import {
  Box, Briefcase, Coin, Collection, DataAnalysis, DataBoard, Document, Goods, House, Key, Lock, Monitor, Money, Notebook, OfficeBuilding, Setting, Tools, Upload, UserFilled, View, Wallet, Warning
} from '@element-plus/icons-vue';
import { NAV_ITEMS } from '@/config/menu';

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
  '/vision-mappings': View,
  '/risk': Lock,
  '/dicts': Collection,
  '/system-configs': Tools,
  '/rbac': UserFilled,
  '/promotions': DataAnalysis,
  '/coupons': Goods,
  '/feedback': Warning,
  '/announcements': Document,
  '/oper-logs': Notebook,
  '/audit': Notebook,
  '/profile': UserFilled
};

function itemsForGroup(group: string) {
  return NAV_ITEMS
    .filter((item) => item.group === group)
    .map((item) => ({
      path: item.path,
      title: item.title,
      icon: PATH_ICONS[item.path] ?? Document
    }));
}

export const SIDEBAR_GROUPS: SidebarGroup[] = [
  { key: 'biz', label: '业务', icon: Briefcase, items: itemsForGroup('业务') },
  { key: 'ops', label: '运营', icon: Setting, items: itemsForGroup('运营') },
  { key: 'sys', label: '系统', icon: Setting, items: itemsForGroup('系统').filter((i) => i.path !== '/profile') }
];

export function sidebarGroupKeyForPath(path: string): string | null {
  const normalized = path.startsWith('/devices/') ? '/devices' : path;
  const item = NAV_ITEMS.find((entry) => entry.path === normalized);
  if (!item) return null;
  if (item.group === '业务') return 'biz';
  if (item.group === '运营') return 'ops';
  if (item.group === '系统') return 'sys';
  return null;
}

