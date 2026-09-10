import type { App } from 'vue';
import { ElLoading } from 'element-plus';

/**
 * 仅注册程序化指令；模板组件由 unplugin-vue-components + ElementPlusResolver 按需引入，
 * 避免把整表 El* 打进入口 chunk。
 */
export function installElementPlus(app: App) {
  app.directive('loading', ElLoading.directive);
}
