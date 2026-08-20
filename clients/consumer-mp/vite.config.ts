import path from 'path';
import { existsSync, readFileSync, writeFileSync } from 'fs';
import { fileURLToPath } from 'url';
import { defineConfig, type Plugin } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/** uni `dev` sometimes omits manifest lazyCodeLoading — patch app.json after emit. */
function ensureLazyCodeLoading(): Plugin {
  const patch = () => {
    for (const kind of ['dev', 'build'] as const) {
      const file = path.resolve(__dirname, `dist/${kind}/mp-weixin/app.json`);
      if (!existsSync(file)) continue;
      try {
        const json = JSON.parse(readFileSync(file, 'utf8'));
        if (json.lazyCodeLoading === 'requiredComponents') continue;
        json.lazyCodeLoading = 'requiredComponents';
        writeFileSync(file, `${JSON.stringify(json, null, 2)}\n`);
      } catch {
        /* ignore partial writes during watch */
      }
    }
  };
  return {
    name: 'ensure-lazy-code-loading',
    closeBundle() {
      patch();
      setTimeout(patch, 300);
    }
  };
}

export default defineConfig({
  plugins: [uni(), ensureLazyCodeLoading()],
  server: {
    host: '127.0.0.1',
    port: 3002,
    proxy: {
      '/api': {
        target: 'http://localhost',
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (request) => request.removeHeader('origin'));
        }
      },
      '/admin': {
        target: 'http://localhost',
        changeOrigin: true
      }
    }
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@aicabinet/shared-dict': path.resolve(__dirname, '../../packages/shared-dict/src/index.ts'),
      '@aicabinet/shared-types': path.resolve(
        __dirname,
        '../../packages/shared-types/src/index.ts'
      ),
      '@aicabinet/shared-uni': path.resolve(__dirname, '../../packages/shared-uni/src')
    }
  }
});
