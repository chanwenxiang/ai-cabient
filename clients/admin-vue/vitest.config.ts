import { defineConfig } from 'vitest/config';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
      // 与 vite.config.ts 保持一致：单测直接测 shared-dict 原函数，不经过响应式包装
      '@aicabinet/shared-dict-core': path.resolve(
        __dirname,
        '../../packages/shared-dict/src/index.ts'
      )
    }
  },
  test: {
    environment: 'node',
    include: ['src/**/*.{test,spec}.ts'],
    reporters: ['default']
  }
});
