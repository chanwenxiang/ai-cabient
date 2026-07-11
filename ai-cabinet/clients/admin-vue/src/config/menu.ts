export interface NavItem {
  path: string;
  title: string;
  group: string;
  keywords?: string[];
}

export const NAV_ITEMS: NavItem[] = [
  { path: '/dashboard', title: '运营工作台', group: '概览', keywords: ['工作台', 'dashboard', '异常', '预警'] },
  { path: '/devices', title: '设备管理', group: '业务', keywords: ['柜机', 'device'] },
  { path: '/sessions', title: '开门记录', group: '业务', keywords: ['会话', 'session'] },
  { path: '/upload-queue', title: '录像上传', group: '业务', keywords: ['上传', '视频', 'upload'] },
  { path: '/orders', title: '订单管理', group: '业务', keywords: ['order'] },
  { path: '/skus', title: '商品管理', group: '业务', keywords: ['sku', '商品', '定价'] },
  { path: '/disputes', title: '争议审核', group: '业务', keywords: ['争议', 'dispute'] },
  { path: '/exceptions', title: '异常中心', group: '业务', keywords: ['异常', '超时', '资金', 'exception'] },
  { path: '/replenishment', title: '补货', group: '运营', keywords: ['补货', '要货'] },
  { path: '/merchants', title: '商户分账', group: '运营', keywords: ['商户', '分账', '开关'] },
  { path: '/reconciliation', title: '对账', group: '运营', keywords: ['对账', 'recon'] },
  { path: '/warehouse', title: '仓库', group: '运营', keywords: ['库存', '出库'] },
  { path: '/recharges', title: '充值管理', group: '运营', keywords: ['充值', '余额'] },
  { path: '/users', title: '灰度用户', group: '运营', keywords: ['用户', '测试余额', '实名'] },
  { path: '/vision-mappings', title: '识别配置', group: '运营', keywords: ['yolo', '识别', 'vision'] },
  { path: '/risk', title: '风控', group: '运营', keywords: ['黑名单', '风险'] },
  { path: '/profile', title: '个人中心', group: '系统', keywords: ['账号', 'profile', '我'] }
];

export function findNavByPath(path: string) {
  if (path.startsWith('/devices/') && path !== '/devices') {
    return { path, title: '设备详情', group: '业务', parentTitle: '设备管理', parentPath: '/devices' };
  }
  return NAV_ITEMS.find((n) => n.path === path);
}

export function searchNavItems(query: string): NavItem[] {
  const q = query.trim().toLowerCase();
  if (!q) return NAV_ITEMS;
  return NAV_ITEMS.filter((item) => {
    const hay = [item.title, item.group, item.path, ...(item.keywords || [])].join(' ').toLowerCase();
    return hay.includes(q);
  });
}
