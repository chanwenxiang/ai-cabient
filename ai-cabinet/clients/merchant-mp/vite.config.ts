import path from 'path';
import { fileURLToPath } from 'url';
import { defineConfig } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [uni()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      '@aicabinet/shared-dict': path.resolve(__dirname, '../../packages/shared-dict/src/index.ts'),
      '@aicabinet/shared-types': path.resolve(__dirname, '../../packages/shared-types/src/index.ts'),
      '@aicabinet/shared-uni': path.resolve(__dirname, '../../packages/shared-uni/src')
    }
  }
});
