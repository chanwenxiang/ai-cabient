import { ENABLE_TEST_TOOLS } from '@/config/feature-flags';

export interface NavItem {
  path: string;
  title: string;
  /** 一级分组（与菜单管理 ops:nav:* 目录一致） */
  group: string;
  keywords?: string[];
  /** 若依风格菜单权限码（C 级）；缺省则登录可见 */
  perm?: string;
}

/** 侧栏 / 菜单管理共用的一级分类顺序 */
export const NAV_GROUPS = [
  '概览',
  '交易履约',
  '设备商品',
  '履约仓储',
  '财务商户',
  '增长风控',
  '系统'
] as const;

const BASE_NAV: NavItem[] = [
  {
    path: '/dashboard',
    title: '运营工作台',
    group: '概览',
    perm: 'ops:dashboard:view',
    keywords: ['工作台', 'dashboard', '异常', '预警']
  },
  {
    path: '/big-screen',
    title: '运营大屏',
    group: '概览',
    perm: 'ops:bigscreen:view',
    keywords: ['大屏', '监控', 'big screen', '驾驶舱']
  },
  {
    path: '/analytics',
    title: '数据分析',
    group: '概览',
    perm: 'ops:analytics:view',
    keywords: ['图表', '趋势', '营收', 'analytics', '看板']
  },
  {
    path: '/footfall',
    title: '客流坪效',
    group: '概览',
    perm: 'ops:analytics:footfall:view',
    keywords: ['客流', '坪效', '热区', '转化', 'footfall', '热力']
  },
  {
    path: '/reports',
    title: '设备报表',
    group: '概览',
    perm: 'ops:report:device',
    keywords: ['报表', 'report', '设备营收']
  },
  {
    path: '/finance',
    title: '财务毛利',
    group: '概览',
    perm: 'ops:finance:view',
    keywords: ['财务', '毛利', '成本', 'finance']
  },
  {
    path: '/sales-reports',
    title: '销售报表',
    group: '概览',
    perm: 'ops:sales-report:list',
    keywords: ['销售', '商品报表', '货柜报表', '商户报表']
  },
  {
    path: '/stock-health',
    title: '库存健康',
    group: '概览',
    perm: 'ops:stock-health:list',
    keywords: ['缺货', '断货', '临期', '库存健康', 'stock']
  },
  {
    path: '/user-analysis',
    title: '用户分析',
    group: '概览',
    perm: 'ops:user-analysis:view',
    keywords: ['用户', '复购', '活跃', '沉睡', 'user-analysis']
  },

  {
    path: '/orders',
    title: '订单管理',
    group: '交易履约',
    perm: 'ops:order:list',
    keywords: ['order']
  },
  {
    path: '/sessions',
    title: '开门记录',
    group: '交易履约',
    perm: 'ops:session:list',
    keywords: ['会话', 'session']
  },
  {
    path: '/disputes',
    title: '争议审核',
    group: '交易履约',
    perm: 'ops:dispute',
    keywords: ['争议', 'dispute']
  },
  {
    path: '/exceptions',
    title: '异常中心',
    group: '交易履约',
    perm: 'ops:exception:list',
    keywords: ['异常', '超时', 'exception']
  },

  {
    path: '/device-ops',
    title: '设备运维',
    group: '设备商品',
    perm: 'ops:device-ops:list',
    keywords: ['运维', '离线', '锁机', 'device-ops']
  },
  {
    path: '/devices',
    title: '设备管理',
    group: '设备商品',
    perm: 'ops:device:list',
    keywords: ['柜机', 'device']
  },
  {
    path: '/device-map',
    title: '投放地图',
    group: '设备商品',
    perm: 'ops:device-map:view',
    keywords: ['地图', '点位', '坐标', '投放']
  },
  {
    path: '/device-kpi',
    title: '设备可用性',
    group: '设备商品',
    perm: 'ops:device-kpi:view',
    keywords: ['设备', '可用性', 'KPI', '锁机', '恢复', '离线']
  },
  {
    path: '/repair-tickets',
    title: '维修工单',
    group: '设备商品',
    perm: 'ops:repair:list',
    keywords: ['维修', '工单', 'repair']
  },
  {
    path: '/skus',
    title: '商品管理',
    group: '设备商品',
    perm: 'ops:sku:list',
    keywords: ['sku', '商品', '定价', '条码', '品牌']
  },
  {
    path: '/sku-review',
    title: '选品诊断',
    group: '设备商品',
    perm: 'ops:sku-review:list',
    keywords: ['选品', '淘汰', '滞销', '动销', 'sku-review']
  },
  {
    path: '/sku-vision',
    title: '识别入驻',
    group: '设备商品',
    perm: 'ops:sku:list',
    keywords: ['sku', '识别', '入驻', 'yolo', 'vision', '类名']
  },
  {
    path: '/vision-mappings',
    title: '识别映射',
    group: '设备商品',
    perm: 'ops:vision:list',
    keywords: ['yolo', '映射', 'vision', 'deepseek', '重力']
  },
  {
    path: '/upload-queue',
    title: '录像上传',
    group: '设备商品',
    perm: 'ops:session:upload',
    keywords: ['上传', '视频', 'upload']
  },

  {
    path: '/replenishment',
    title: '补货调度',
    group: '履约仓储',
    perm: 'ops:replenishment:list',
    keywords: ['补货', '要货', '开门', '路线']
  },
  {
    path: '/warehouse',
    title: '仓库',
    group: '履约仓储',
    perm: 'ops:warehouse:list',
    keywords: ['库存', '出库']
  },
  {
    path: '/ota',
    title: '固件版本',
    group: '履约仓储',
    perm: 'ops:ota:list',
    keywords: ['ota', '固件', '升级', '版本']
  },
  {
    path: '/sla',
    title: '服务时限监控',
    group: '履约仓储',
    perm: 'ops:sla',
    keywords: ['sla', '服务时限', '开门成功率', '识别耗时', '在线率']
  },

  {
    path: '/fund-bills',
    title: '资金账单',
    group: '财务商户',
    perm: 'ops:fund:list',
    keywords: ['资金', '账单', '账务', 'ledger']
  },
  {
    path: '/merchants',
    title: '商户与分账',
    group: '财务商户',
    perm: 'ops:merchant:list',
    keywords: ['商户', '分账', '开关', '货道']
  },
  {
    path: '/line-managers',
    title: '线长钱包',
    group: '财务商户',
    perm: 'ops:line-manager:list',
    keywords: ['线长', '提现', '佣金', '钱包']
  },
  {
    path: '/merchant-withdraw',
    title: '商户提现',
    group: '财务商户',
    perm: 'ops:merchant-withdraw:list',
    keywords: ['商户提现', '钱包', '打款', '结算']
  },
  {
    path: '/reconciliation',
    title: '对账',
    group: '财务商户',
    perm: 'ops:reconciliation:list',
    keywords: ['对账', 'recon']
  },
  {
    path: '/consistency',
    title: '数据一致性',
    group: '财务商户',
    perm: 'ops:consistency:list',
    keywords: ['一致性', '巡检', 'consistency', '库存', '金额']
  },
  {
    path: '/recharges',
    title: '充值管理',
    group: '财务商户',
    perm: 'ops:recharge:list',
    keywords: ['充值', '余额']
  },
  {
    path: '/balance-refunds',
    title: '余额退款',
    group: '财务商户',
    perm: 'ops:balance-refund:list',
    keywords: ['余额退款', '退余额', '原路退']
  },
  {
    path: '/users',
    title: '用户余额',
    group: '财务商户',
    perm: 'ops:user:list',
    keywords: ['用户', '余额', '实名', '灰度']
  },

  {
    path: '/phone-verify',
    title: '手机验证',
    group: '增长风控',
    perm: 'ops:phone-verify:list',
    keywords: ['手机验证', '验证流水']
  },
  {
    path: '/risk',
    title: '风控',
    group: '增长风控',
    perm: 'ops:risk:list',
    keywords: ['黑名单', '风险']
  },
  {
    path: '/promotions',
    title: '营销活动',
    group: '增长风控',
    perm: 'ops:promotion:list',
    keywords: ['营销', '活动', 'promotion', '满减', '折扣']
  },
  {
    path: '/coupons',
    title: '优惠券',
    group: '增长风控',
    perm: 'ops:coupon:list',
    keywords: ['优惠券', 'coupon', '发券']
  },
  {
    path: '/ad-assets',
    title: '素材库',
    group: '增长风控',
    perm: 'ops:ad:list',
    keywords: ['素材', '广告', '媒体', '上传']
  },
  {
    path: '/ad-campaigns',
    title: '投放计划',
    group: '增长风控',
    perm: 'ops:ad:campaign:list',
    keywords: ['投放', '广告', 'campaign', '轮播']
  },
  {
    path: '/points-redeem',
    title: '积分兑换管理',
    group: '增长风控',
    perm: 'ops:points:list',
    keywords: ['积分', '兑换', 'points', 'redeem']
  },
  {
    path: '/member-levels',
    title: '会员等级规则',
    group: '增长风控',
    perm: 'ops:member-level:list',
    keywords: ['会员', '等级', '积分倍率', 'member-level']
  },
  {
    path: '/marketing-roi',
    title: '活动效果分析',
    group: '增长风控',
    perm: 'ops:marketing-roi:view',
    keywords: ['活动', 'ROI', '营销', '核销', 'marketing-roi']
  },
  {
    path: '/replenishment-staff',
    title: '补货员效率',
    group: '履约仓储',
    perm: 'ops:replenishment:list',
    keywords: ['补货员', '效率', '任务', '准时率', 'replenishment-staff']
  },
  {
    path: '/notifications',
    title: '消息记录',
    group: '增长风控',
    perm: 'ops:notify:list',
    keywords: ['消息', '通知', 'notification']
  },
  {
    path: '/feedback',
    title: '用户反馈',
    group: '增长风控',
    perm: 'ops:feedback',
    keywords: ['反馈', '投诉', '建议', 'feedback']
  },

  {
    path: '/operators',
    title: '运营账号',
    group: '系统',
    perm: 'ops:rbac:assign',
    keywords: ['运营账号', '用户授权', '角色分配', '商户范围', 'operator']
  },
  {
    path: '/roles',
    title: '角色管理',
    group: '系统',
    perm: 'ops:rbac:role',
    keywords: ['角色', '权限字符', '分配权限', 'rbac', 'role']
  },
  {
    path: '/menus',
    title: '菜单管理',
    group: '系统',
    perm: 'ops:rbac:menu',
    keywords: ['菜单', '目录', '按钮', 'M', 'C', 'F', '权限树']
  },
  {
    path: '/dicts',
    title: '字典管理',
    group: '系统',
    perm: 'ops:dict:list',
    keywords: ['字典', 'dict', '枚举']
  },
  {
    path: '/system-configs',
    title: '参数配置',
    group: '系统',
    perm: 'ops:config:list',
    keywords: ['参数', '配置', 'config', 'settings']
  },
  {
    path: '/alert-rules',
    title: '告警规则',
    group: '系统',
    perm: 'ops:config:list',
    keywords: ['告警', '规则', 'SLA', '阈值', '卡点']
  },
  {
    path: '/scheduled-tasks',
    title: '定时任务',
    group: '系统',
    perm: 'ops:task:list',
    keywords: ['定时', '任务', '调度', 'job', 'task']
  },
  {
    path: '/org-sites',
    title: '组织与点位',
    group: '系统',
    perm: 'ops:org:list',
    keywords: ['组织', '点位', '场地', '合同', '布机', '撤机', 'org']
  },
  {
    path: '/announcements',
    title: '通知公告',
    group: '系统',
    perm: 'ops:announcement:list',
    keywords: ['公告', '通知', 'announce']
  },
  {
    path: '/audit',
    title: '审计日志',
    group: '系统',
    perm: 'ops:audit:list',
    keywords: ['审计', '日志', 'audit']
  },
  { path: '/profile', title: '个人中心', group: '系统', keywords: ['账号', 'profile', '我'] }
];

const DEMO_NAV: NavItem = {
  path: '/recognition-demo',
  title: '识别演示',
  group: '设备商品',
  perm: 'ops:recognition-demo:view',
  keywords: ['识别', 'demo', '上传', 'yolo', '测试']
};

function buildNav(): NavItem[] {
  if (!ENABLE_TEST_TOOLS) return BASE_NAV;
  const items = [...BASE_NAV];
  const idx = items.findIndex((n) => n.path === '/sku-vision');
  items.splice(idx >= 0 ? idx + 1 : items.length, 0, DEMO_NAV);
  return items;
}

export const NAV_ITEMS: NavItem[] = buildNav();

export function findNavByPath(path: string) {
  if (path.startsWith('/devices/') && path !== '/devices') {
    return {
      path,
      title: '设备详情',
      group: '设备商品',
      parentTitle: '设备管理',
      parentPath: '/devices',
      perm: 'ops:device:list'
    };
  }
  return NAV_ITEMS.find((n) => n.path === path);
}

export function searchNavItems(query: string, canAccess?: (item: NavItem) => boolean): NavItem[] {
  const q = query.trim().toLowerCase();
  const base = canAccess ? NAV_ITEMS.filter(canAccess) : NAV_ITEMS;
  if (!q) return base;
  return base.filter((item) => {
    const hay = [item.title, item.group, item.path, ...(item.keywords || [])]
      .filter(Boolean)
      .join(' ')
      .toLowerCase();
    return hay.includes(q);
  });
}
