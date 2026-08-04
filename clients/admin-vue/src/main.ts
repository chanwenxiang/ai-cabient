import { createApp } from 'vue';
import { createPinia } from 'pinia';
import 'element-plus/dist/index.css';
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
