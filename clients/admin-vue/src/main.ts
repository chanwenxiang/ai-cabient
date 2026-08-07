import { createApp } from 'vue';
import { createPinia } from 'pinia';
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
import { setupHasPermi } from './directives/hasPermi';

const pinia = createPinia();
const app = createApp(App).use(pinia).use(router);
installElementPlus(app);
setupHasPermi(app);

useSettingsStore(pinia).init();

app.mount('#app');
