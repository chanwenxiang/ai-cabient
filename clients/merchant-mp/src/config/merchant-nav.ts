/** 商户小程序固定导航（主流：前端写死 + 权限/功能包裁剪，不读运营菜单树） */
export type MerchantPack = 'field' | 'biz' | 'team';

export interface MerchantNavItem {
  key: string;
  title: string;
  desc?: string;
  /** tab 页或分包路径 */
  url: string;
  tab?: boolean;
  perm: string | string[];
  pack: MerchantPack;
  icon?: string;
}

export const MERCHANT_FIELD_NAV: MerchantNavItem[] = [
  {
    key: 'replenishment',
    title: '补货任务',
    desc: '扫码到柜 · 签到 · 核对上架',
    url: '/pages/replenishment/replenishment',
    perm: 'merchant:replenishment:view',
    pack: 'field',
    icon: 'replenish'
  },
  {
    key: 'devices',
    title: '柜机管理',
    desc: '在线状态 · 货道库存',
    url: '/pages/devices/devices',
    tab: true,
    perm: 'merchant:devices:list',
    pack: 'field',
    icon: 'cabinet'
  },
  {
    key: 'alerts',
    title: '待办事项',
    desc: '缺货 · 临期 · 离线 · 争议',
    url: '/pages/alerts/alerts',
    tab: true,
    perm: 'merchant:alerts:view',
    pack: 'field',
    icon: 'pending'
  }
];

export const MERCHANT_BIZ_NAV: MerchantNavItem[] = [
  {
    key: 'ops-config',
    title: '补货配置',
    desc: '拍照 · 阈值 · 补货单',
    url: '/pages/ops-config/ops-config',
    perm: 'merchant:replenishment:view',
    pack: 'field',
    icon: 'replenish'
  },
  {
    key: 'messages',
    title: '消息中心',
    desc: '补货任务 · 结算到账 · 系统通知',
    url: '/pages/messages/messages',
    perm: 'merchant:portal:access',
    pack: 'biz',
    icon: 'notice'
  },
  {
    key: 'pricing',
    title: '点位定价',
    desc: '按柜机调整 SKU 售价',
    url: '/pages/pricing/pricing',
    perm: 'merchant:pricing:view',
    pack: 'biz',
    icon: 'pricing'
  },
  {
    key: 'settlements',
    title: '结算对账',
    desc: '日结与对账单导出',
    url: '/pages/settlements/settlements',
    perm: 'merchant:settlements:view',
    pack: 'biz',
    icon: 'settlements'
  },
  {
    key: 'wallet',
    title: '商户钱包',
    desc: '可提现余额与自主提现',
    url: '/pages/wallet/wallet',
    perm: 'merchant:wallet:view',
    pack: 'biz',
    icon: 'wallet'
  },
  {
    key: 'splits',
    title: '分账明细',
    desc: '分账状态与失败原因',
    url: '/pages/splits/splits',
    perm: 'merchant:splits:list',
    pack: 'biz',
    icon: 'splits'
  },
  {
    key: 'line-wallet',
    title: '线长钱包',
    desc: '线长余额与自主提现（非商户分账）',
    url: '/pages/line-wallet/line-wallet',
    perm: 'merchant:line-wallet:view',
    pack: 'biz',
    icon: 'line-wallet'
  },
  {
    key: 'orders',
    title: '柜机订单',
    desc: '本商户柜机成交与争议单',
    url: '/pages/orders/orders',
    perm: 'merchant:orders:list',
    pack: 'biz',
    icon: 'orders'
  },
  {
    key: 'disputes',
    title: '争议处理',
    desc: '消费者账单申诉',
    url: '/pages/disputes/disputes',
    perm: 'merchant:disputes:list',
    pack: 'biz',
    icon: 'disputes'
  },
  {
    key: 'business',
    title: '经营分析',
    desc: '营收、毛利与商品表现',
    url: '/pages/business/business',
    // 页面主体为经营分析（analytics/*），入口权限与页面实际接口对齐，避免"能进但内容全 403"
    perm: 'merchant:analytics:view',
    pack: 'biz',
    icon: 'business'
  }
];

export const MERCHANT_DOCS_NAV: MerchantNavItem[] = [
  {
    key: 'tax',
    title: '税号',
    desc: '月结开票用的公司名称和税号',
    url: '/pages/tax/tax',
    perm: ['merchant:profile:edit', 'merchant:analytics:view'],
    pack: 'biz',
    icon: 'settlements'
  },
  {
    key: 'cabinet-reports',
    title: '销售报表',
    desc: '按货柜查商品与毛利明细、导出',
    url: '/pages/cabinet-reports/cabinet-reports',
    perm: ['merchant:analytics:view', 'merchant:reports:view'],
    pack: 'biz',
    icon: 'cabinet'
  },
  {
    key: 'notify',
    title: '微信提醒',
    desc: '订阅柜机离线、缺货与订单推送',
    url: '/pages/notify/notify',
    perm: 'merchant:alerts:view',
    pack: 'field',
    icon: 'notice'
  }
];

/** 原工作台「更多功能」；搬到「我的」，权限条目仍以 MERCHANT_BIZ_NAV 为准 */
const MORE_FROM_BIZ_KEYS = ['pricing', 'settlements', 'disputes', 'business'] as const;

export const MERCHANT_MORE_NAV: MerchantNavItem[] = [
  {
    key: 'purchase',
    title: '采购入库',
    desc: '货进本人负责的分仓，不是要货',
    url: '/pages/purchase/purchase',
    perm: 'merchant:replenishment:view',
    pack: 'field',
    icon: 'replenish'
  },
  {
    key: 'request',
    title: '要货申请',
    desc: '柜缺货时向仓库喊一声，不是日常补货',
    url: '/pages/request/request',
    perm: 'merchant:replenishment:view',
    pack: 'field',
    icon: 'replenish'
  },
  ...MORE_FROM_BIZ_KEYS.map((key) => MERCHANT_BIZ_NAV.find((i) => i.key === key)).filter(
    (item): item is MerchantNavItem => Boolean(item)
  )
];

export const MERCHANT_MORE_NAV_KEYS = new Set(MERCHANT_MORE_NAV.map((i) => i.key));

export const MERCHANT_TEAM_NAV: MerchantNavItem[] = [
  {
    key: 'team',
    title: '团队成员',
    desc: '查看与邀请商户账号',
    url: '/pages/team/team',
    perm: 'merchant:users:list',
    pack: 'team',
    icon: 'team'
  }
];
