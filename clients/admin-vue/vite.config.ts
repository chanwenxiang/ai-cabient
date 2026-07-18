import { defineConfig, loadEnv } from 'vite';
import vue from '@vitejs/plugin-vue';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT_DIR = path.resolve(__dirname, '../../services/trade-service/src/main/resources/static/admin');

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, __dirname, '');
  return {
    plugins: [vue()],
    base: './',
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
        '@aicabinet/shared-dict': path.resolve(__dirname, '../../packages/shared-dict/src/index.ts'),
        '@aicabinet/shared-types': path.resolve(__dirname, '../../packages/shared-types/src/index.ts'),
        '@aicabinet/shared-api': path.resolve(__dirname, '../../packages/shared-api/src/index.ts'),
        '@aicabinet/shared-uni': path.resolve(__dirname, '../../packages/shared-uni/src'),
        '@aicabinet/shared-uni/format': path.resolve(__dirname, '../../packages/shared-uni/src/format.ts')
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
