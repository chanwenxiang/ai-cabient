import path from 'path';
import { fileURLToPath } from 'url';
import { defineConfig } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [uni()],
    server: {
      host: '127.0.0.1',
    port: 3001,
    proxy: {
      '/api': {
        // Prefer gateway (:80); fall back docs note trade direct :18080 on win-ports full stack
        target: process.env.VITE_DEV_PROXY || 'http://localhost',
        changeOrigin: true,
        configure(proxy) { proxy.on('proxyReq', (request) => request.removeHeader('origin')); }
      }
    }
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@aicabinet/shared-dict': path.resolve(__dirname, '../../packages/shared-dict/src/index.ts'),
      '@aicabinet/shared-rbac': path.resolve(__dirname, '../../packages/shared-rbac/src/index.ts'),
      '@aicabinet/shared-types': path.resolve(__dirname, '../../packages/shared-types/src/index.ts'),
      '@aicabinet/shared-uni': path.resolve(__dirname, '../../packages/shared-uni/src')
    }
  }
});
