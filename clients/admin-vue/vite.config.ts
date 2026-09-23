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
    // 生产包剔除 console.log/debug/info；warn/error 仅允许 DEV 或显式安全告警（见 admin-dev-log）
    esbuild: {
      pure: mode === 'production' ? ['console.log', 'console.debug', 'console.info'] : []
    },
    build: {
      outDir: OUT_DIR,
      emptyOutDir: true,
      chunkSizeWarningLimit: 1000,
      // 浏览器基线（本仓无 browserslist ⇒ 走 Vite 默认 build.target='modules'：
      // 原生 ESM + 动态 import + import.meta 的浏览器）。其中 modulepreload 原生支持始于
      // Chrome 66 / Edge 79 / Safari 11.3 / Firefox 115（2023-07），
      // 故基线内唯一缺它的是已过保的 Firefox 78–114。
      // 该 polyfill 在支持的浏览器里**第一行就 early-return**（relList.supports('modulepreload')），
      // 对现代浏览器纯属解析开销（实测 710B 常驻入口）。关掉后仅影响 Firefox 78–114 的
      // 「预取」优化：`__vitePreload` 注入的 <link rel=modulepreload> 被忽略，动态 import
      // 仍按需拉取（只是少一段提前量），**功能不受影响**。
      modulePreload: { polyfill: false },
      rollupOptions: {
        output: {
          manualChunks(id) {
            if (!id.includes('node_modules')) return;
            // 避免把 CSS 硬塞进 JS chunk
            if (id.endsWith('.css')) return;
            const norm = id.replace(/\\/g, '/');
            if (norm.includes('/leaflet') || norm.includes('/leaflet.markercluster')) {
              return 'leaflet';
            }
            // echarts + zrender：重型图表库（已按需注册，见 src/utils/echarts.ts，压缩后仍 ~513KB）。
            // 必须独立成 vendor chunk —— 它是「第三方库」，不是「业务页面」。混在 route 类里
            // 会让 150KB 的业务页面预算对它失焦：既误报它，又让它 513KB 的体积把真正需要
            // 盯的业务膨胀掩盖在「largest route」之下。独立后由 ADMIN_BUDGET_ECHARTS_KB 单管。
            if (norm.includes('/echarts/') || norm.includes('/zrender/')) {
              return 'echarts-vendor';
            }
            // vue 与 element-plus 必须同 chunk：拆开会形成双向 import，生产 TDZ 白屏
            // （Circular chunk: element-plus → vue-vendor → element-plus）
            if (
              norm.includes('/element-plus/') ||
              norm.includes('/@element-plus/') ||
              norm.includes('/vue/') ||
              norm.includes('/vue-router/') ||
              norm.includes('/pinia/') ||
              norm.includes('/@vue/') ||
              norm.includes('/@vueuse/') ||
              norm.includes('/nprogress/')
            ) {
              return 'ui-vendor';
            }
          }
        }
      }
    }
  };
});
