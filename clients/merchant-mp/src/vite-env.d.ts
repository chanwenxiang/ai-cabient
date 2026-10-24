/// <reference types="vite/client" />

/** 构建期注入：读 package.json version（vite.config.ts define），防「我的」页版本号忘改。 */
declare const __APP_VERSION__: string;

declare module '*.vue' {
  import type { DefineComponent } from 'vue';
  const component: DefineComponent<object, object, unknown>;
  export default component;
}
