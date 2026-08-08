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
    icon: '补'
  },
  {
    key: 'devices',
    title: '柜机管理',
    desc: '在线状态 · 货道库存',
    url: '/pages/devices/devices',
    tab: true,
    perm: 'merchant:devices:list',
    pack: 'field',
    icon: '柜'
  },
  {
    key: 'alerts',
    title: '待办事项',
    desc: '缺货 · 临期 · 离线 · 争议',
    url: '/pages/alerts/alerts',
    tab: true,
    perm: 'merchant:alerts:view',
    pack: 'field',
    icon: '待'
  }
];

export const MERCHANT_BIZ_NAV: MerchantNavItem[] = [
  {
    key: 'pricing',
    title: '点位定价',
    desc: '按柜机调整 SKU 售价',
    url: '/pages/pricing/pricing',
    perm: 'merchant:pricing:view',
    pack: 'biz',
    icon: '价'
  },
  {
    key: 'settlements',
    title: '结算对账',
    desc: '日结与对账单导出',
    url: '/pages/settlements/settlements',
    perm: 'merchant:settlements:view',
    pack: 'biz',
    icon: '账'
  },
  {
    key: 'wallet',
    title: '商户钱包',
    desc: '可提现余额与自主提现',
    url: '/pages/wallet/wallet',
    perm: 'merchant:wallet:view',
    pack: 'biz',
    icon: '财'
  },
  {
    key: 'splits',
    title: '分账明细',
    desc: '分账状态与失败原因',
    url: '/pages/splits/splits',
    perm: 'merchant:splits:list',
    pack: 'biz',
    icon: '分'
  },
  {
    key: 'line-wallet',
    title: '线长钱包',
    desc: '线长余额与自主提现（非商户分账）',
    url: '/pages/line-wallet/line-wallet',
    perm: 'merchant:line-wallet:view',
    pack: 'biz',
    icon: '线'
  },
  {
    key: 'orders',
    title: '柜机订单',
    desc: '本商户柜机成交与争议单',
    url: '/pages/orders/orders',
    perm: 'merchant:orders:list',
    pack: 'biz',
    icon: '单'
  },
  {
    key: 'disputes',
    title: '争议处理',
    desc: '消费者账单申诉',
    url: '/pages/disputes/disputes',
    perm: 'merchant:disputes:list',
    pack: 'biz',
    icon: '议'
  },
  {
    key: 'business',
    title: '经营分析',
    desc: '营收、毛利与商品表现',
    url: '/pages/business/business',
    perm: ['merchant:reports:view', 'merchant:analytics:view'],
    pack: 'biz',
    icon: '绩'
  }
];

export const MERCHANT_TEAM_NAV: MerchantNavItem[] = [
  {
    key: 'team',
    title: '团队成员',
    desc: '查看与邀请商户账号',
    url: '/pages/team/team',
    perm: 'merchant:users:list',
    pack: 'team',
    icon: '队'
  }
];
