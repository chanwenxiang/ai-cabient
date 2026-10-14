import { createApp } from 'vue';
import { createPinia } from 'pinia';
import { normalizeAdminIndexHtmlHash } from './utils/admin-hash-history';
// Element Plus 基础变量（含 :root 浅色变量），组件样式由 unplugin-vue-components 按需引入
import 'element-plus/theme-chalk/base.css';
// 程序化 API 组件（非模板使用）需要手动引入样式
import 'element-plus/es/components/message/style/css';
import 'element-plus/es/components/message-box/style/css';
import 'element-plus/es/components/loading/style/css';
import App from './App.vue';
import router from './router';
import './styles/main.css';
import { useSettingsStore } from './stores/settings';
import { installElementPlus } from './plugins/element-plus';
import { installMessageBoxDefaults } from './plugins/message-box-defaults';
import { installLogoutMessageGuard } from './plugins/logout-message-guard';
import { setupHasPermi } from './directives/hasPermi';
import { installTableCellNativeTitle } from './utils/table-cell-native-title';
import { installAuthTabSync } from './utils/auth-tab-sync';
import { installUnsavedRouteGuard } from './utils/unsaved-guard';

normalizeAdminIndexHtmlHash();
installMessageBoxDefaults();
installLogoutMessageGuard();
// 多标签页登出广播（JWT 在 sessionStorage，storage 事件跨页收不到，见 auth-tab-sync）
installAuthTabSync();
// 未保存离开守卫：路由级确认 + beforeunload（页面用 registerUnsavedGuard 自报脏状态）
installUnsavedRouteGuard(router);

const pinia = createPinia();
const app = createApp(App).use(pinia).use(router);
installElementPlus(app);
setupHasPermi(app);
installTableCellNativeTitle();

useSettingsStore(pinia).init();

// 全局兜底（2026-10-14 审计补齐）：错误边界（PageErrorBoundary）之外的异常
// 在此落 console，不再无声消失；保持不弹窗——渲染期连续异常弹窗会成骚扰。
app.config.errorHandler = (err, _instance, info) => {
  console.error('[admin] 全局异常：', err, '·', info);
};

app.mount('#app');
