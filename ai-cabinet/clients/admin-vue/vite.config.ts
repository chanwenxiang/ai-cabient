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
        '@aicabinet/shared-api': path.resolve(__dirname, '../../packages/shared-api/src/index.ts')
      }
    },
    server: {
      port: 5173,
      proxy: {
        '/api': { target: env.VITE_DEV_PROXY || 'http://localhost:8080', changeOrigin: true }
      }
    },
    build: {
      outDir: OUT_DIR,
      emptyOutDir: true,
      chunkSizeWarningLimit: 600
    }
  };
});
