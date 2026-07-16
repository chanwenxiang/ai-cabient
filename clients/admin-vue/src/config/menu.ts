export interface NavItem {
  path: string;
  title: string;
  group: string;
  keywords?: string[];
  /** 若依风格菜单权限码（C 级）；缺省则登录可见 */
  perm?: string;
}

export const NAV_ITEMS: NavItem[] = [
  { path: '/dashboard', title: '运营工作台', group: '概览', perm: 'ops:dashboard:view', keywords: ['工作台', 'dashboard', '异常', '预警'] },
  { path: '/analytics', title: '数据分析', group: '概览', perm: 'ops:analytics:view', keywords: ['图表', '趋势', '营收', 'analytics', '看板'] },
  { path: '/reports', title: '设备报表', group: '概览', perm: 'ops:report:device', keywords: ['报表', 'report', '设备营收'] },
  { path: '/finance', title: '财务毛利', group: '概览', perm: 'ops:finance:view', keywords: ['财务', '毛利', '成本', 'finance'] },
  { path: '/devices', title: '设备管理', group: '业务', perm: 'ops:device:list', keywords: ['柜机', 'device'] },
  { path: '/sessions', title: '开门记录', group: '业务', perm: 'ops:session:list', keywords: ['会话', 'session'] },
  { path: '/upload-queue', title: '录像上传', group: '业务', perm: 'ops:session:upload', keywords: ['上传', '视频', 'upload'] },
  { path: '/orders', title: '订单管理', group: '业务', perm: 'ops:order:list', keywords: ['order'] },
  { path: '/skus', title: '商品与识别', group: '业务', perm: 'ops:sku:list', keywords: ['sku', '商品', '定价', 'yolo', '识别', 'vision'] },
  { path: '/recognition-demo', title: '识别 Demo', group: '业务', perm: 'ops:sku:list', keywords: ['识别', 'demo', '上传', 'yolo', '测试'] },
  { path: '/disputes', title: '争议审核', group: '业务', perm: 'ops:dispute', keywords: ['争议', 'dispute'] },
  { path: '/exceptions', title: '异常中心', group: '业务', perm: 'ops:exception:list', keywords: ['异常', '超时', '资金', 'exception'] },
  { path: '/replenishment', title: '补货', group: '运营', perm: 'ops:replenishment:list', keywords: ['补货', '要货'] },
  { path: '/merchants', title: '商户分账', group: '运营', perm: 'ops:merchant:list', keywords: ['商户', '分账', '开关'] },
  { path: '/reconciliation', title: '对账', group: '运营', perm: 'ops:reconciliation:list', keywords: ['对账', 'recon'] },
  { path: '/warehouse', title: '仓库', group: '运营', perm: 'ops:warehouse:list', keywords: ['库存', '出库'] },
  { path: '/recharges', title: '充值管理', group: '运营', perm: 'ops:recharge:list', keywords: ['充值', '余额'] },
  { path: '/users', title: '灰度用户', group: '运营', perm: 'ops:user:list', keywords: ['用户', '测试余额', '实名'] },
  { path: '/risk', title: '风控', group: '运营', perm: 'ops:risk:list', keywords: ['黑名单', '风险'] },
  { path: '/promotions', title: '营销活动', group: '运营', perm: 'ops:promotion:list', keywords: ['营销', '活动', 'promotion', '满减', '折扣'] },
  { path: '/coupons', title: '优惠券', group: '运营', perm: 'ops:coupon:list', keywords: ['优惠券', 'coupon', '发券'] },
  { path: '/feedback', title: '用户反馈', group: '运营', perm: 'ops:feedback', keywords: ['反馈', '投诉', '建议', 'feedback'] },
  // 系统管理（对齐若依：账号 → 角色 → 菜单 → 字典/参数/公告/日志）
  { path: '/operators', title: '运营账号', group: '系统', perm: 'ops:rbac:assign', keywords: ['运营账号', '用户授权', '角色分配', '商户范围', 'operator'] },
  { path: '/roles', title: '角色管理', group: '系统', perm: 'ops:rbac:role', keywords: ['角色', '权限字符', '分配权限', 'rbac', 'role'] },
  { path: '/menus', title: '菜单管理', group: '系统', perm: 'ops:rbac:menu', keywords: ['菜单', '目录', '按钮', 'M', 'C', 'F', '权限树'] },
  { path: '/dicts', title: '字典管理', group: '系统', perm: 'ops:dict:list', keywords: ['字典', 'dict', '枚举'] },
  { path: '/system-configs', title: '参数配置', group: '系统', perm: 'ops:config:list', keywords: ['参数', '配置', 'config', 'settings'] },
  { path: '/announcements', title: '通知公告', group: '系统', perm: 'ops:announcement:list', keywords: ['公告', '通知', 'announce'] },
  { path: '/audit', title: '审计日志', group: '系统', perm: 'ops:audit:list', keywords: ['审计', '日志', 'audit'] },
  { path: '/oper-logs', title: '操作日志', group: '系统', perm: 'ops:operlog', keywords: ['日志', '操作', '轨迹', 'oper'] },
  { path: '/profile', title: '个人中心', group: '系统', keywords: ['账号', 'profile', '我'] }
];

export function findNavByPath(path: string) {
  if (path.startsWith('/devices/') && path !== '/devices') {
    return { path, title: '设备详情', group: '业务', parentTitle: '设备管理', parentPath: '/devices', perm: 'ops:device:list' };
  }
  return NAV_ITEMS.find((n) => n.path === path);
}

export function searchNavItems(query: string, canAccess?: (item: NavItem) => boolean): NavItem[] {
  const q = query.trim().toLowerCase();
  const base = canAccess ? NAV_ITEMS.filter(canAccess) : NAV_ITEMS;
  if (!q) return base;
  return base.filter((item) => {
    const hay = [item.title, item.group, item.path, ...(item.keywords || [])].join(' ').toLowerCase();
    return hay.includes(q);
  });
}
