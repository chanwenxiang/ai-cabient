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
        { path: 'devices', name: 'devices', component: () => import('@/views/devices/DeviceListView.vue'), meta: { title: '设备管理', group: '业务' } },
        { path: 'devices/:id', name: 'device-detail', component: () => import('@/views/devices/DeviceDetailView.vue'), meta: { title: '设备详情', group: '业务', parentTitle: '设备管理', parentPath: '/devices' } },
        { path: 'sessions', name: 'sessions', component: () => import('@/views/sessions/SessionListView.vue'), meta: { title: '开门记录', group: '业务' } },
        { path: 'upload-queue', name: 'upload-queue', component: () => import('@/views/upload/UploadQueueView.vue'), meta: { title: '录像上传', group: '业务' } },
        { path: 'orders', name: 'orders', component: () => import('@/views/orders/OrderListView.vue'), meta: { title: '订单管理', group: '业务' } },
        { path: 'skus', name: 'skus', component: () => import('@/views/skus/SkuListView.vue'), meta: { title: '商品管理', group: '业务' } },
        { path: 'disputes', name: 'disputes', component: () => import('@/views/disputes/DisputeListView.vue'), meta: { title: '争议审核', group: '业务' } },
        { path: 'replenishment', name: 'replenishment', component: () => import('@/views/replenishment/ReplenishmentView.vue'), meta: { title: '补货', group: '运营' } },
        { path: 'merchants', name: 'merchants', component: () => import('@/views/merchants/MerchantSplitsView.vue'), meta: { title: '商户分账', group: '运营' } },
        { path: 'reconciliation', name: 'reconciliation', component: () => import('@/views/reconciliation/ReconciliationView.vue'), meta: { title: '对账', group: '运营' } },
        { path: 'warehouse', name: 'warehouse', component: () => import('@/views/warehouse/WarehouseView.vue'), meta: { title: '仓库', group: '运营' } },
        { path: 'recharges', name: 'recharges', component: () => import('@/views/recharges/RechargeListView.vue'), meta: { title: '充值管理', group: '运营' } },
        { path: 'vision-mappings', name: 'vision-mappings', component: () => import('@/views/vision/VisionMappingView.vue'), meta: { title: '识别配置', group: '运营' } },
        { path: 'risk', name: 'risk', component: () => import('@/views/risk/RiskView.vue'), meta: { title: '风控', group: '运营' } },
        { path: 'profile', name: 'profile', component: () => import('@/views/profile/ProfileView.vue'), meta: { title: '个人中心', group: '系统' } }
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
