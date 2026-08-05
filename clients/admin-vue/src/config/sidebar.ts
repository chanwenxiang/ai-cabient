import type { Component } from 'vue';
import {
  Box, Briefcase, Coin, Collection, Cpu, DataAnalysis, DataBoard, Document, Goods, House, Key, Location, Lock, Menu, Monitor, Money, Notebook, Odometer, OfficeBuilding, Setting, Timer, Tools, Upload, User, UserFilled, View, Wallet, Warning
} from '@element-plus/icons-vue';
import { NAV_ITEMS, type NavItem } from '@/config/menu';

/** 侧栏树节点：目录(dir) 或叶子菜单(leaf) */
export interface SidebarNode {
  key: string;
  label: string;
  icon: Component;
  path?: string;
  children?: SidebarNode[];
}

const PATH_ICONS: Record<string, Component> = {
  '/dashboard': Odometer,
  '/analytics': DataAnalysis,
  '/reports': DataBoard,
  '/finance': Money,
  '/sales-reports': DataBoard,
  '/stock-health': Box,
  '/fund-bills': Coin,
  '/devices': Monitor,
  '/device-map': Location,
  '/repair-tickets': Tools,
  '/device-ops': Tools,
  '/sessions': Key,
  '/upload-queue': Upload,
  '/orders': Document,
  '/skus': Goods,
  '/sku-vision': View,
  '/recognition-demo': View,
  '/vision-mappings': View,
  '/ota': Cpu,
  '/sla': Timer,
  '/disputes': Warning,
  '/exceptions': Warning,
  '/replenishment': Box,
  '/merchants': OfficeBuilding,
  '/line-managers': UserFilled,
  '/merchant-withdraw': Wallet,
  '/reconciliation': Coin,
  '/consistency': Document,
  '/warehouse': House,
  '/recharges': Wallet,
  '/users': User,
  '/phone-verify': UserFilled,
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

const GROUP_META: { key: string; label: string; icon: Component }[] = [
  { key: 'overview', label: '概览', icon: DataBoard },
  { key: 'trade', label: '交易履约', icon: Document },
  { key: 'device', label: '设备商品', icon: Monitor },
  { key: 'fulfill', label: '履约仓储', icon: Box },
  { key: 'finance', label: '财务商户', icon: Coin },
  { key: 'growth', label: '增长风控', icon: Lock },
  { key: 'sys', label: '系统', icon: Setting }
];

const GROUP_KEY: Record<string, string> = {
  概览: 'overview',
  交易履约: 'trade',
  设备商品: 'device',
  履约仓储: 'fulfill',
  财务商户: 'finance',
  增长风控: 'growth',
  系统: 'sys',
  // 旧分组兼容（书签/缓存）
  业务: 'trade',
  运营: 'finance'
};

function toLeaf(item: NavItem): SidebarNode {
  return {
    key: item.path,
    label: item.title,
    path: item.path,
    icon: PATH_ICONS[item.path] ?? Document
  };
}

/** 按权限过滤后的树型侧栏（一级分类 → 叶子，与菜单管理一致） */
export function buildSidebarTree(canAccess: (item: NavItem) => boolean): SidebarNode[] {
  const accessible = NAV_ITEMS.filter((item) => {
    if (item.path === '/profile') return false;
    return canAccess(item);
  });

  return GROUP_META.flatMap((group): SidebarNode[] => {
    const groupItems = accessible.filter((item) => GROUP_KEY[item.group] === group.key);
    if (!groupItems.length) return [];
    return [{
      key: group.key,
      label: group.label,
      icon: group.icon,
      children: groupItems.map(toLeaf)
    }];
  });
}

/** @deprecated 兼容旧引用；请改用 buildSidebarTree */
export function buildSidebarGroups(canAccess: (item: NavItem) => boolean) {
  return buildSidebarTree(canAccess).map((node) => ({
    key: node.key,
    label: node.label,
    icon: node.icon,
    items: (node.children || [])
      .filter((leaf) => leaf.path)
      .map((leaf) => ({ path: leaf.path!, title: leaf.label, icon: leaf.icon }))
  }));
}

/** 返回当前路径需要展开的全部祖先 key */
export function sidebarOpenKeysForPath(path: string): string[] {
  const normalized = path.startsWith('/devices/') ? '/devices' : path;
  const item = NAV_ITEMS.find((entry) => entry.path === normalized);
  if (!item) return [];
  const groupKey = GROUP_KEY[item.group];
  return groupKey ? [groupKey] : [];
}

/** @deprecated 请用 sidebarOpenKeysForPath */
export function sidebarGroupKeyForPath(path: string): string | null {
  return sidebarOpenKeysForPath(path)[0] ?? null;
}

export const SIDEBAR_GROUPS = buildSidebarGroups(() => true);
