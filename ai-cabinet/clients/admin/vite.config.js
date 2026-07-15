import { defineConfig, loadEnv } from 'vite';
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
    root: __dirname,
    base: './',
    envPrefix: 'VITE_',
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_DEV_PROXY || 'http://localhost:8080',
          changeOrigin: true
        }
      }
    },
    build: {
      outDir: OUT_DIR,
      emptyOutDir: true,
      sourcemap: mode !== 'production',
      rollupOptions: {
        input: path.resolve(__dirname, 'index.html')
      }
    }
  };
});
