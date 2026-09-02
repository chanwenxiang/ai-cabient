import { createSSRApp } from 'vue';
import { normalizeH5HashToHistory } from '@aicabinet/shared-uni/h5-hash-history';
import App from './App.vue';

normalizeH5HashToHistory();

export function createApp() {
  const app = createSSRApp(App);
  return { app };
}
