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

/**
 * 宽表格：把 .table-scroll 容器宽度撑到表格自然宽度，
 * 去掉表格内部横向滚动，横向滚动交给页面（整页滑动看数据）。
 * 例外：.page-fill 固定高度页（异常中心/字典）保持内部滚动布局。
 */
function fitWideTables() {
  requestAnimationFrame(() => {
    document.querySelectorAll<HTMLElement>('.table-scroll').forEach((scroll) => {
      const colgroup = scroll.querySelector('colgroup');
      const cols = colgroup ? colgroup.querySelectorAll<HTMLElement>('col') : [];
      let natural = 0;
      cols.forEach((c) => {
        natural += c.offsetWidth || 0;
      });
      if (!natural) {
        const wrapper = scroll.querySelector<HTMLElement>('.el-table__body-wrapper, .el-table__body');
        natural = wrapper ? wrapper.scrollWidth : 0;
      }
      if (!natural) return;
      // 已撑开且列结构未变：保持现状，避免反复测量导致的抖动
      if (scroll.dataset.fitted === '1') {
        const fitW = parseInt(scroll.dataset.fitW || '0', 10);
        if (Math.abs(natural - fitW) < 16) return;
      }
      if (natural > scroll.clientWidth + 4) {
        scroll.style.width = `${natural + 2}px`;
        scroll.style.maxWidth = 'none';
        scroll.dataset.fitted = '1';
        scroll.dataset.fitW = String(natural);
      } else {
        scroll.style.width = '';
        scroll.style.maxWidth = '';
        delete scroll.dataset.fitted;
        delete scroll.dataset.fitW;
      }
    });
  });
}

let fitTimer = 0;
function scheduleFit() {
  if (fitTimer) return;
  fitTimer = window.setTimeout(() => {
    fitTimer = 0;
    fitWideTables();
  }, 120);
}

const fitObserver = new MutationObserver(scheduleFit);
fitObserver.observe(document.body, {
  childList: true,
  subtree: true,
  attributes: true,
  attributeFilter: ['class']
});
window.addEventListener('resize', scheduleFit);
window.addEventListener('resize', () => {
  document.querySelectorAll<HTMLElement>('.table-scroll').forEach((s) => {
    delete s.dataset.fitted;
    delete s.dataset.fitW;
  });
});
