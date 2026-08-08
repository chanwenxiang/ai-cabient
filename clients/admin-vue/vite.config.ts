import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import Components from 'unplugin-vue-components/vite';
import { ElementPlusResolver } from 'unplugin-vue-components/resolvers';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(
  __dirname,
  '../../services/trade-service/src/main/resources/static/admin'
);

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '');
  return {
    plugins: [
      vue(),
      // Element Plus 按需引入：模板组件及其样式自动导入，配合 base.css 与程序化组件样式
      Components({
        resolvers: [ElementPlusResolver()],
        dts: false
      })
    ],
    base: '/admin/',
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
        // 业务代码走响应式包装；dict-runtime / 包装内部走 core，避免循环依赖
        '@aicabinet/shared-dict': path.resolve(__dirname, 'src/utils/shared-dict-reactive.ts'),
        '@aicabinet/shared-dict-core': path.resolve(
          __dirname,
          '../../packages/shared-dict/src/index.ts'
        ),
        '@aicabinet/shared-rbac': path.resolve(
          __dirname,
          '../../packages/shared-rbac/src/index.ts'
        ),
        '@aicabinet/shared-types': path.resolve(
          __dirname,
          '../../packages/shared-types/src/index.ts'
        ),
        '@aicabinet/shared-api': path.resolve(__dirname, '../../packages/shared-api/src/index.ts'),
        '@aicabinet/shared-uni': path.resolve(__dirname, '../../packages/shared-uni/src'),
        '@aicabinet/shared-uni/format': path.resolve(
          __dirname,
          '../../packages/shared-uni/src/format.ts'
        )
      }
    },
    server: {
      host: '127.0.0.1',
      port: 3000,
      proxy: {
        '/api': {
          target: env.VITE_DEV_PROXY || 'http://localhost:8080',
          changeOrigin: true,
          headers: { Origin: env.VITE_DEV_ORIGIN || 'http://localhost' }
        }
      }
    },
    build: {
      outDir: OUT_DIR,
      emptyOutDir: true,
      chunkSizeWarningLimit: 600
    }
  };
});
