import type { Component } from 'vue';
import {
  Box, Briefcase, Coin, Collection, Cpu, DataAnalysis, DataBoard, Document, Goods, House, Key, Lock, Menu, Monitor, Money, Notebook, Odometer, OfficeBuilding, Operation, Setting, Timer, Tools, Upload, User, UserFilled, View, Wallet, Warning
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
  '/devices': Monitor,
  '/sessions': Key,
  '/upload-queue': Upload,
  '/orders': Document,
  '/skus': Goods,
  '/recognition-demo': View,
  '/vision-mappings': View,
  '/ota': Cpu,
  '/sla': Timer,
  '/disputes': Warning,
  '/exceptions': Warning,
  '/replenishment': Box,
  '/merchants': OfficeBuilding,
  '/reconciliation': Coin,
  '/warehouse': House,
  '/recharges': Wallet,
  '/users': User,
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
  { key: 'biz', label: '业务', icon: Briefcase },
  { key: 'ops', label: '运营', icon: Operation },
  { key: 'sys', label: '系统', icon: Setting }
];

const GROUP_KEY: Record<string, string> = {
  概览: 'overview',
  业务: 'biz',
  运营: 'ops',
  系统: 'sys'
};

const SECTION_ICONS: Record<string, Component> = {
  交易履约: Document,
  设备与商品: Monitor,
  履约仓储: Box,
  财务商户: Coin,
  增长风控: Lock,
  权限管理: Key,
  系统配置: Tools
};

function toLeaf(item: NavItem): SidebarNode {
  return {
    key: item.path,
    label: item.title,
    path: item.path,
    icon: PATH_ICONS[item.path] ?? Document
  };
}

function slugSection(section: string): string {
  const map: Record<string, string> = {
    交易履约: 'trade',
    设备与商品: 'device-sku',
    履约仓储: 'fulfillment',
    财务商户: 'finance-merchant',
    增长风控: 'growth',
    权限管理: 'rbac',
    系统配置: 'config'
  };
  return map[section] || section;
}

/** 按权限过滤后的树型侧栏（一级分组 → 二级分区 → 叶子） */
export function buildSidebarTree(canAccess: (item: NavItem) => boolean): SidebarNode[] {
  const accessible = NAV_ITEMS.filter((item) => {
    if (item.path === '/profile') return false;
    return canAccess(item);
  });

  return GROUP_META.flatMap((group): SidebarNode[] => {
    const groupItems = accessible.filter((item) => GROUP_KEY[item.group] === group.key);
    if (!groupItems.length) return [];

    const sectionOrder: string[] = [];
    const bySection = new Map<string, NavItem[]>();
    const direct: NavItem[] = [];

    for (const item of groupItems) {
      if (!item.section) {
        direct.push(item);
        continue;
      }
      if (!bySection.has(item.section)) {
        bySection.set(item.section, []);
        sectionOrder.push(item.section);
      }
      bySection.get(item.section)!.push(item);
    }

    const children: SidebarNode[] = [
      ...direct.map(toLeaf),
      ...sectionOrder.map((section) => {
        const items = bySection.get(section) || [];
        return {
          key: `${group.key}:${slugSection(section)}`,
          label: section,
          icon: SECTION_ICONS[section] ?? Document,
          children: items.map(toLeaf)
        };
      })
    ];

    // 概览无二级分区：叶子直接挂在一级下
    if (group.key === 'overview' || (direct.length && !sectionOrder.length)) {
      return [{
        key: group.key,
        label: group.label,
        icon: group.icon,
        children: groupItems.map(toLeaf)
      }];
    }

    return [{
      key: group.key,
      label: group.label,
      icon: group.icon,
      children
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
      .flatMap((child) => (child.children?.length ? child.children : [child]))
      .filter((leaf) => leaf.path)
      .map((leaf) => ({ path: leaf.path!, title: leaf.label, icon: leaf.icon }))
  }));
}

/** 返回当前路径需要展开的全部祖先 key（一级 + 二级） */
export function sidebarOpenKeysForPath(path: string): string[] {
  const normalized = path.startsWith('/devices/') ? '/devices' : path;
  const item = NAV_ITEMS.find((entry) => entry.path === normalized);
  if (!item) return [];
  const groupKey = GROUP_KEY[item.group];
  if (!groupKey) return [];
  if (!item.section || item.group === '概览') return [groupKey];
  return [groupKey, `${groupKey}:${slugSection(item.section)}`];
}

/** @deprecated 请用 sidebarOpenKeysForPath */
export function sidebarGroupKeyForPath(path: string): string | null {
  return sidebarOpenKeysForPath(path)[0] ?? null;
}

export const SIDEBAR_GROUPS = buildSidebarGroups(() => true);
