import { createRouter, createWebHistory } from 'vue-router';
import { isLoggedIn } from '@/api/client';
import { findNavByPath } from '@/config/menu';
import { ENABLE_TEST_TOOLS } from '@/config/feature-flags';
import { useAuthStore } from '@/stores/auth';
import { useBrandStore } from '@/stores/brand';
import { safeRedirectPath } from '@/utils/safe-redirect';

const bizChildren: any[] = [
  {
    path: 'dashboard',
    name: 'dashboard',
    component: () => import('@/views/dashboard/DashboardView.vue'),
    meta: { title: '运营工作台', group: '概览' }
  },
  {
    path: 'big-screen',
    name: 'big-screen',
    component: () => import('@/views/dashboard/BigScreenView.vue'),
    meta: { title: '运营大屏', group: '概览' }
  },
  {
    path: 'analytics',
    name: 'analytics',
    component: () => import('@/views/analytics/AnalyticsView.vue'),
    meta: { title: '数据分析', group: '概览' }
  },
  {
    path: 'footfall',
    name: 'footfall',
    component: () => import('@/views/analytics/FootfallView.vue'),
    meta: { title: '客流坪效', group: '概览' }
  },
  {
    path: 'reports',
    name: 'reports',
    component: () => import('@/views/reports/DeviceReportView.vue'),
    meta: { title: '设备报表', group: '概览' }
  },
  {
    path: 'finance',
    name: 'finance',
    component: () => import('@/views/finance/FinanceView.vue'),
    meta: { title: '财务毛利', group: '概览' }
  },
  {
    path: 'fund-bills',
    name: 'fund-bills',
    component: () => import('@/views/finance/FundBillView.vue'),
    meta: { title: '资金账单', group: '财务商户' }
  },
  {
    path: 'sales-reports',
    name: 'sales-reports',
    component: () => import('@/views/reports/SalesReportsView.vue'),
    meta: { title: '销售报表', group: '概览' }
  },
  {
    path: 'stock-health',
    name: 'stock-health',
    component: () => import('@/views/reports/StockHealthView.vue'),
    meta: { title: '库存健康', group: '概览' }
  },
  {
    path: 'devices',
    name: 'devices',
    component: () => import('@/views/devices/DeviceListView.vue'),
    meta: { title: '设备管理', group: '设备商品' }
  },
  {
    path: 'device-map',
    name: 'device-map',
    component: () => import('@/views/devices/DeviceMapView.vue'),
    meta: { title: '投放地图', group: '设备商品' }
  },
  {
    path: 'device-kpi',
    name: 'device-kpi',
    component: () => import('@/views/devices/DeviceKpiView.vue'),
    meta: { title: '设备可用性', group: '设备商品' }
  },
  {
    path: 'repair-tickets',
    name: 'repair-tickets',
    component: () => import('@/views/devices/RepairTicketsView.vue'),
    meta: { title: '维修工单', group: '设备商品' }
  },
  {
    path: 'device-ops',
    name: 'device-ops',
    component: () => import('@/views/devices/DeviceOpsMonitorView.vue'),
    meta: { title: '设备运维', group: '设备商品' }
  },
  {
    path: 'devices/:id',
    name: 'device-detail',
    component: () => import('@/views/devices/DeviceDetailView.vue'),
    meta: { title: '设备详情', group: '设备商品', parentTitle: '设备管理', parentPath: '/devices' }
  },
  {
    path: 'sessions',
    name: 'sessions',
    component: () => import('@/views/sessions/SessionListView.vue'),
    meta: { title: '开门记录', group: '交易履约' }
  },
  { path: 'videos', redirect: '/upload-queue' },
  { path: 'uploads', redirect: '/upload-queue' },
  {
    path: 'upload-queue',
    name: 'upload-queue',
    component: () => import('@/views/upload/UploadQueueView.vue'),
    meta: { title: '录像上传', group: '设备商品' }
  },
  {
    path: 'orders',
    name: 'orders',
    component: () => import('@/views/orders/OrderListView.vue'),
    meta: { title: '订单管理', group: '交易履约' }
  },
  {
    path: 'skus',
    name: 'skus',
    component: () => import('@/views/skus/SkuListView.vue'),
    meta: { title: '商品管理', group: '设备商品' }
  },
  {
    path: 'sku-vision',
    name: 'sku-vision',
    component: () => import('@/views/skus/SkuVisionEnrollView.vue'),
    meta: { title: '识别入驻', group: '设备商品' }
  },
  {
    path: 'disputes',
    name: 'disputes',
    component: () => import('@/views/disputes/DisputeListView.vue'),
    meta: { title: '争议审核', group: '交易履约' }
  },
  {
    path: 'exceptions',
    name: 'exceptions',
    component: () => import('@/views/exceptions/ExceptionListView.vue'),
    meta: { title: '异常中心', group: '交易履约' }
  },
  {
    path: 'replenishment',
    name: 'replenishment',
    component: () => import('@/views/replenishment/ReplenishmentView.vue'),
    meta: { title: '补货调度', group: '履约仓储' }
  },
  {
    path: 'merchants',
    name: 'merchants',
    component: () => import('@/views/merchants/MerchantSplitsView.vue'),
    meta: { title: '商户与分账', group: '财务商户' }
  },
  {
    path: 'line-managers',
    name: 'line-managers',
    component: () => import('@/views/finance/LineManagerView.vue'),
    meta: { title: '线长钱包', group: '财务商户' }
  },
  {
    path: 'merchant-withdraw',
    name: 'merchant-withdraw',
    component: () => import('@/views/finance/MerchantWithdrawView.vue'),
    meta: { title: '商户提现', group: '财务商户' }
  },
  {
    path: 'reconciliation',
    name: 'reconciliation',
    component: () => import('@/views/reconciliation/ReconciliationView.vue'),
    meta: { title: '对账', group: '财务商户' }
  },
  {
    path: 'consistency',
    name: 'consistency',
    component: () => import('@/views/consistency/ConsistencyView.vue'),
    meta: { title: '数据一致性', group: '财务商户' }
  },
  {
    path: 'warehouse',
    name: 'warehouse',
    component: () => import('@/views/warehouse/WarehouseView.vue'),
    meta: { title: '仓库', group: '履约仓储' }
  },
  {
    path: 'recharges',
    name: 'recharges',
    component: () => import('@/views/recharges/RechargeListView.vue'),
    meta: { title: '充值管理', group: '财务商户' }
  },
  {
    path: 'balance-refunds',
    name: 'balance-refunds',
    component: () => import('@/views/finance/BalanceRefundView.vue'),
    meta: { title: '余额退款', group: '财务商户' }
  },
  {
    path: 'invoices',
    name: 'invoices',
    component: () => import('@/views/finance/InvoiceListView.vue'),
    meta: { title: '开票申请', group: '财务商户' }
  },
  {
    path: 'merchant-onboarding',
    name: 'merchant-onboarding',
    component: () => import('@/views/merchants/MerchantOnboardingView.vue'),
    meta: { title: '进件工作台', group: '财务商户' }
  },
  {
    path: 'users',
    name: 'users',
    component: () => import('@/views/users/UserListView.vue'),
    meta: { title: '用户余额', group: '财务商户' }
  },
  {
    path: 'phone-verify',
    name: 'phone-verify',
    component: () => import('@/views/users/PhoneVerifyView.vue'),
    meta: { title: '手机验证', group: '增长风控' }
  },
  {
    path: 'vision-mappings',
    name: 'vision-mappings',
    component: () => import('@/views/vision/VisionMappingView.vue'),
    meta: { title: '识别映射', group: '设备商品' }
  },
  {
    path: 'ota',
    name: 'ota',
    component: () => import('@/views/ota/OtaView.vue'),
    meta: { title: '固件版本', group: '履约仓储' }
  },
  {
    path: 'sla',
    name: 'sla',
    component: () => import('@/views/sla/SlaView.vue'),
    meta: { title: '服务时限监控', group: '履约仓储' }
  },
  {
    path: 'risk',
    name: 'risk',
    component: () => import('@/views/risk/RiskView.vue'),
    meta: { title: '风控', group: '增长风控' }
  },
  {
    path: 'operators',
    name: 'operators',
    component: () => import('@/views/system/OperatorManageView.vue'),
    meta: { title: '运营账号', group: '系统' }
  },
  {
    path: 'roles',
    name: 'roles',
    component: () => import('@/views/system/RoleManageView.vue'),
    meta: { title: '角色管理', group: '系统' }
  },
  {
    path: 'departments',
    name: 'departments',
    component: () => import('@/views/system/DepartmentManageView.vue'),
    meta: { title: '部门管理', group: '系统' }
  },
  {
    path: 'approvals',
    name: 'approvals',
    component: () => import('@/views/system/ApprovalConfigView.vue'),
    meta: { title: '审批流配置', group: '系统' }
  },
  {
    path: 'menus',
    name: 'menus',
    component: () => import('@/views/system/MenuManageView.vue'),
    meta: { title: '菜单管理', group: '系统' }
  },
  { path: 'rbac', redirect: '/roles' },
  {
    path: 'dicts',
    name: 'dicts',
    component: () => import('@/views/system/DictManageView.vue'),
    meta: { title: '字典管理', group: '系统' }
  },
  {
    path: 'system-configs',
    name: 'system-configs',
    component: () => import('@/views/system/SystemConfigView.vue'),
    meta: { title: '参数配置', group: '系统' }
  },
  {
    path: 'alert-rules',
    name: 'alert-rules',
    component: () => import('@/views/system/AlertRuleView.vue'),
    meta: { title: '告警规则', group: '系统' }
  },
  {
    path: 'scheduled-tasks',
    name: 'scheduled-tasks',
    component: () => import('@/views/system/ScheduledTaskView.vue'),
    meta: { title: '定时任务', group: '系统' }
  },
  {
    path: 'org-sites',
    name: 'org-sites',
    component: () => import('@/views/system/OrgSitesView.vue'),
    meta: { title: '组织与点位', group: '系统' }
  },
  {
    path: 'announcements',
    name: 'announcements',
    component: () => import('@/views/announcements/AnnouncementsView.vue'),
    meta: { title: '通知公告', group: '系统' }
  },
  {
    path: 'audit',
    name: 'audit',
    component: () => import('@/views/system/AuditLogView.vue'),
    meta: { title: '审计日志', group: '系统' }
  },
  {
    path: 'devops',
    name: 'devops',
    component: () => import('@/views/system/DevOpsHubView.vue'),
    meta: { title: 'DevOps 中心', group: '系统' }
  },
  { path: 'oper-logs', redirect: '/audit' },
  {
    path: 'promotions',
    name: 'promotions',
    component: () => import('@/views/promotions/PromotionsView.vue'),
    meta: { title: '营销活动', group: '增长风控' }
  },
  {
    path: 'coupons',
    name: 'coupons',
    component: () => import('@/views/promotions/CouponsView.vue'),
    meta: { title: '优惠券', group: '增长风控' }
  },
  {
    path: 'ad-assets',
    name: 'ad-assets',
    component: () => import('@/views/growth/AdAssetsView.vue'),
    meta: { title: '素材库', group: '增长风控' }
  },
  {
    path: 'ad-campaigns',
    name: 'ad-campaigns',
    component: () => import('@/views/growth/AdCampaignsView.vue'),
    meta: { title: '投放计划', group: '增长风控' }
  },
  {
    path: 'points-redeem',
    name: 'points-redeem',
    component: () => import('@/views/growth/PointsRedeemView.vue'),
    meta: { title: '积分兑换管理', group: '增长风控' }
  },
  {
    path: 'member-levels',
    name: 'member-levels',
    component: () => import('@/views/growth/MemberLevelsView.vue'),
    meta: { title: '会员等级规则', group: '增长风控' }
  },
  {
    path: 'marketing-roi',
    name: 'marketing-roi',
    component: () => import('@/views/growth/MarketingRoiView.vue'),
    meta: { title: '活动效果分析', group: '增长风控' }
  },
  {
    path: 'replenishment-staff',
    name: 'replenishment-staff',
    component: () => import('@/views/growth/ReplenishmentStaffView.vue'),
    meta: { title: '补货员效率', group: '履约仓储' }
  },
  {
    path: 'sku-review',
    name: 'sku-review',
    component: () => import('@/views/growth/SkuReviewView.vue'),
    meta: { title: '选品诊断', group: '设备商品' }
  },
  {
    path: 'user-analysis',
    name: 'user-analysis',
    component: () => import('@/views/growth/UserAnalysisView.vue'),
    meta: { title: '用户分析', group: '概览' }
  },
  {
    path: 'notifications',
    name: 'notifications',
    component: () => import('@/views/growth/NotificationsView.vue'),
    meta: { title: '消息记录', group: '增长风控' }
  },
  {
    path: 'feedback',
    name: 'feedback',
    component: () => import('@/views/feedback/FeedbackView.vue'),
    meta: { title: '用户反馈', group: '增长风控' }
  },
  {
    path: 'profile',
    name: 'profile',
    component: () => import('@/views/profile/ProfileView.vue'),
    meta: { title: '个人中心', group: '系统' }
  },
  {
    path: 'forbidden',
    name: 'forbidden',
    component: () => import('@/views/error/ForbiddenView.vue'),
    meta: { title: '无权访问', group: '系统' }
  }
];

if (ENABLE_TEST_TOOLS) {
  const skusIdx = bizChildren.findIndex((r) => r.path === 'skus');
  bizChildren.splice(skusIdx + 1, 0, {
    path: 'recognition-demo',
    name: 'recognition-demo',
    component: () => import('@/views/vision/RecognitionDemoView.vue'),
    meta: { title: '识别演示', group: '业务' }
  });
}

const router = createRouter({
  history: createWebHistory('/admin/'),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { public: true }
    },
    {
      path: '/print',
      name: 'print',
      component: () => import('@/views/print/PrintView.vue'),
      meta: { title: '打印单据' }
    },
    {
      path: '/',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/dashboard',
      children: [
        ...bizChildren,
        {
          path: ':pathMatch(.*)*',
          name: 'not-found',
          component: () => import('@/views/error/NotFoundView.vue'),
          meta: { title: '页面不存在', group: '系统' }
        }
      ]
    }
  ]
});

router.beforeEach(async (to) => {
  if (to.name === 'login' && isLoggedIn()) {
    return { path: safeRedirectPath(to.query.redirect) };
  }
  if (to.meta.public) return true;
  if (!isLoggedIn()) return { name: 'login', query: { redirect: to.fullPath } };

  // 错误页本身不做权限拦截，避免循环跳转
  if (to.name === 'forbidden' || to.name === 'not-found') return true;

  const auth = useAuthStore();
  if (!auth.permissions.length) {
    await auth.restore();
  }
  const nav = findNavByPath(to.path);
  if (nav?.perm && !auth.canAccessNav(nav)) {
    return {
      name: 'forbidden',
      query: {
        from: to.fullPath,
        title: String(to.meta.title || nav.title || '')
      },
      replace: true
    };
  }
  return true;
});

// 动态页面标题：每个路由的 meta.title 会拼到浏览器标签页上
router.afterEach((to) => {
  const brand = useBrandStore();
  const base = brand.documentBaseTitle || 'AI开门柜 · 运营管理系统';
  const pageTitle = to.meta.title as string | undefined;
  document.title = pageTitle ? `${pageTitle} · ${base}` : base;
});

export default router;
