import { createRouter, createWebHashHistory } from 'vue-router';
import { isLoggedIn } from '@/api/client';

const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { public: true } },
    {
      path: '/',
      component: () => import('@/layouts/AdminLayout.vue'),
      redirect: '/dashboard',
      children: [
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/dashboard/DashboardView.vue'), meta: { title: '运营工作台', group: '概览' } },
        { path: 'analytics', name: 'analytics', component: () => import('@/views/analytics/AnalyticsView.vue'), meta: { title: '数据分析', group: '概览' } },
        { path: 'reports', name: 'reports', component: () => import('@/views/reports/DeviceReportView.vue'), meta: { title: '设备报表', group: '运营' } },
        { path: 'finance', name: 'finance', component: () => import('@/views/finance/FinanceView.vue'), meta: { title: '财务毛利', group: '运营' } },
        { path: 'devices', name: 'devices', component: () => import('@/views/devices/DeviceListView.vue'), meta: { title: '设备管理', group: '业务' } },
        { path: 'devices/:id', name: 'device-detail', component: () => import('@/views/devices/DeviceDetailView.vue'), meta: { title: '设备详情', group: '业务', parentTitle: '设备管理', parentPath: '/devices' } },
        { path: 'sessions', name: 'sessions', component: () => import('@/views/sessions/SessionListView.vue'), meta: { title: '开门记录', group: '业务' } },
        { path: 'videos', redirect: '/upload-queue' },
        { path: 'upload-queue', name: 'upload-queue', component: () => import('@/views/upload/UploadQueueView.vue'), meta: { title: '录像上传', group: '业务' } },
        { path: 'orders', name: 'orders', component: () => import('@/views/orders/OrderListView.vue'), meta: { title: '订单管理', group: '业务' } },
        { path: 'skus', name: 'skus', component: () => import('@/views/skus/SkuListView.vue'), meta: { title: '商品与识别', group: '业务' } },
        { path: 'recognition-demo', name: 'recognition-demo', component: () => import('@/views/vision/RecognitionDemoView.vue'), meta: { title: '识别 Demo', group: '业务' } },
        { path: 'disputes', name: 'disputes', component: () => import('@/views/disputes/DisputeListView.vue'), meta: { title: '争议审核', group: '业务' } },
        { path: 'exceptions', name: 'exceptions', component: () => import('@/views/exceptions/ExceptionListView.vue'), meta: { title: '异常中心', group: '业务' } },
        { path: 'replenishment', name: 'replenishment', component: () => import('@/views/replenishment/ReplenishmentView.vue'), meta: { title: '补货', group: '运营' } },
        { path: 'merchants', name: 'merchants', component: () => import('@/views/merchants/MerchantSplitsView.vue'), meta: { title: '商户分账', group: '运营' } },
        { path: 'reconciliation', name: 'reconciliation', component: () => import('@/views/reconciliation/ReconciliationView.vue'), meta: { title: '对账', group: '运营' } },
        { path: 'warehouse', name: 'warehouse', component: () => import('@/views/warehouse/WarehouseView.vue'), meta: { title: '仓库', group: '运营' } },
        { path: 'recharges', name: 'recharges', component: () => import('@/views/recharges/RechargeListView.vue'), meta: { title: '充值管理', group: '运营' } },
        { path: 'users', name: 'users', component: () => import('@/views/users/UserListView.vue'), meta: { title: '灰度用户', group: '运营' } },
        { path: 'vision-mappings', redirect: '/skus' },
        { path: 'risk', name: 'risk', component: () => import('@/views/risk/RiskView.vue'), meta: { title: '风控', group: '运营' } },
        { path: 'dicts', name: 'dicts', component: () => import('@/views/system/DictManageView.vue'), meta: { title: '字典管理', group: '系统' } },
        { path: 'system-configs', name: 'system-configs', component: () => import('@/views/system/SystemConfigView.vue'), meta: { title: '参数配置', group: '系统' } },
        { path: 'rbac', name: 'rbac', component: () => import('@/views/system/RbacView.vue'), meta: { title: '权限管理', group: '系统' } },
        { path: 'audit', name: 'audit', component: () => import('@/views/system/AuditLogView.vue'), meta: { title: '操作日志', group: '系统' } },
        { path: 'promotions', name: 'promotions', component: () => import('@/views/promotions/PromotionsView.vue'), meta: { title: '营销活动', group: '运营' } },
        { path: 'coupons', name: 'coupons', component: () => import('@/views/promotions/CouponsView.vue'), meta: { title: '优惠券管理', group: '运营' } },
        { path: 'announcements', name: 'announcements', component: () => import('@/views/announcements/AnnouncementsView.vue'), meta: { title: '公告管理', group: '系统' } },
        { path: 'feedback', name: 'feedback', component: () => import('@/views/feedback/FeedbackView.vue'), meta: { title: '用户反馈', group: '运营' } },
        { path: 'oper-logs', name: 'oper-logs', component: () => import('@/views/system/OperLogView.vue'), meta: { title: '操作日志', group: '系统' } },        { path: 'profile', name: 'profile', component: () => import('@/views/profile/ProfileView.vue'), meta: { title: '个人中心', group: '系统' } }
      ]
    }
  ]
});

router.beforeEach((to) => {
  if (to.meta.public) return true;
  if (!isLoggedIn()) return { name: 'login', query: { redirect: to.fullPath } };
  return true;
});

export default router;

