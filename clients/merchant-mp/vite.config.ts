import path from 'path';
import { existsSync, readFileSync, realpathSync, writeFileSync } from 'fs';
import { createRequire } from 'module';
import { fileURLToPath } from 'url';
import { defineConfig, type Plugin } from 'vite';
import uni from '@dcloudio/vite-plugin-uni';
import { UniEcharts } from 'uni-echarts/vite';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

/**
 * `echarts` 的 lib（ESM）里会直接 `import "zrender/..."` / `import "tslib"` ——
 * 这些都是它自己的依赖。pnpm 是**严格结构**：它们只挂在 echarts 自己所在的 `.pnpm` 层
 * （`node_modules/.pnpm/echarts@5.6.0/node_modules/{zrender,tslib}`），项目顶层看不到。
 *
 * 两个坑叠加会让小程序构建直接失败：
 *   `[vite]: Rollup failed to resolve import "zrender/lib/canvas/Painter.js"`（换成 tslib 同理）——
 *   ① Node 与 Rollup **都不 realpath** symlink ⇒ 从 `clients/merchant-mp/node_modules/echarts/lib/...`
 *      逐级向上找，永远看不到 `.pnpm/` 里的兄弟目录；
 *   ② 所以**必须先 `realpathSync` 到 `.pnpm/echarts@x/node_modules/echarts`**，再以该位置解析依赖。
 *
 * 这里按 echarts 的 **dependencies 清单动态生成** alias（而不是逐个硬编码 `zrender`）：
 * 版本升级 / 上游换依赖时无需改代码，硬编码 `.pnpm/zrender@x.y.z/...` 则会**悄悄**失效。
 */
const echartsDepAliases = (() => {
  const echartsPkg = realpathSync(path.join(__dirname, 'node_modules/echarts/package.json'));
  // pnpm 严格结构：依赖就躺在 echarts **同层**的 node_modules 里
  // （`.pnpm/echarts@x/node_modules/{echarts,zrender,tslib}`）。
  const siblingNodeModules = path.dirname(path.dirname(echartsPkg));
  const requireFromEcharts = createRequire(echartsPkg);
  const { dependencies = {} } = JSON.parse(readFileSync(echartsPkg, 'utf8')) as {
    dependencies?: Record<string, string>;
  };
  const aliases: Record<string, string> = {};
  for (const name of Object.keys(dependencies)) {
    const sibling = path.join(siblingNodeModules, name);
    if (existsSync(sibling)) {
      aliases[name] = sibling;
      continue;
    }
    // 扁平结构（npm / yarn）回退到常规解析。⚠️ `tslib` 这类包用 `exports` 封了子路径，
    // 解析 `tslib/package.json` 会得到 ERR_PACKAGE_PATH_NOT_EXPORTED，只能从入口向上找包根。
    try {
      let dir = path.dirname(requireFromEcharts.resolve(name));
      while (!existsSync(path.join(dir, 'package.json')) && dir !== path.dirname(dir)) {
        dir = path.dirname(dir);
      }
      aliases[name] = dir;
    } catch {
      // 解析不到就交给默认解析（例如上游声明了但未安装的可选依赖）。
    }
  }
  return aliases;
})();

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

/**
 * 给 `uni-echarts` 补一个本项目 uni-app 版本缺的 `toValue`。
 *
 * 事实：`uni-echarts/src/shared-core.js` 第 1 行是
 *   `import { computed, getCurrentInstance, inject, onBeforeUnmount, onMounted, provide, shallowRef, toValue, watch, watchEffect } from "vue"`
 * 而本项目 uni-app 是 `3.0.0-4010520240507001`（2024-05），其小程序运行时
 * `@dcloudio/uni-mp-vue/dist/vue.runtime.esm.js` 的**裁剪版没有导出 `toValue`**（实测 grep 计数 = 0），
 * 于是小程序构建直接失败（H5 走标准 vue，不受影响）：
 *   `"toValue" is not exported by ".../@dcloudio/uni-mp-vue/dist/vue.runtime.esm.js"`。
 *
 * 为什么打补丁而不是换路：
 *   - uni-echarts 从 **1.0.0 到 2.5.3 每个版本**都用了 `toValue` ⇒ 没有兼容版可退（已逐版本实测）；
 *   - 升级 uni-app 要跨 2024-05 → 2026-09 两年多的 build，牵动整个 uni 端，风险远大于收益。
 * 补丁与 Vue 3.3 的官方定义**语义等价**：`toValue = (source) => isFunction(source) ? source() : unref(source)`。
 *
 * ⚠️ 将来 uni-app 升到「运行时已导出 toValue」的版本后，**请删掉这个插件**。
 */
function patchUniEchartsToValue(): Plugin {
  const toValueImpl =
    'const __toValue = (source) => (typeof source === "function" ? source() : unref(source));\n';
  return {
    name: 'patch-uni-echarts-to-value',
    enforce: 'pre',
    transform(code, id) {
      if (!/uni-echarts[\\/]/.test(id) || !code.includes('toValue')) return null;
      let removedFromImport = false;
      // ① 从 `import { ... } from "vue"` 里摘掉 toValue，并补上实现要用的 unref。
      const stripped = code.replace(
        /import\s*\{([^}]*)\}\s*from\s*(["'])vue\2/g,
        (whole, names: string) => {
          const list = names
            .split(',')
            .map((s) => s.trim())
            .filter(Boolean);
          const at = list.indexOf('toValue');
          if (at < 0) return whole;
          list.splice(at, 1);
          if (!list.includes('unref')) list.push('unref');
          removedFromImport = true;
          return `import { ${list.join(', ')} } from 'vue'`;
        }
      );
      // ② 没摘掉说明上游改了 import 写法 ⇒ **不改动**，让构建照常报错，
      //    免得这里静默"修好"、日后排查困难。
      if (!removedFromImport) return null;
      return { code: toValueImpl + stripped.replace(/\btoValue\s*\(/g, '__toValue('), map: null };
    }
  };
}

export default defineConfig({
  // ⚠️ `UniEcharts()` 必须排在 `uni()` 之前：npm 上的 `uni-echarts` 是**未编译的 .vue**
  // 发布物，插件内部会为它补 `optimizeDeps.exclude`，否则 Vite 预构建会复制出**第二份 echarts**，
  // 图表在小程序端会静默不渲染（见 https://uni-echarts.xiaohe.ink/guide/getting-started）。
  plugins: [UniEcharts(), patchUniEchartsToValue(), uni(), ensureLazyCodeLoading()],
  server: {
    host: '127.0.0.1',
    port: 3001,
    proxy: {
      '/api': {
        // Prefer gateway (:80); fall back docs note trade direct :18080 on win-ports full stack
        target: process.env.VITE_DEV_PROXY || 'http://localhost',
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (request) => request.removeHeader('origin'));
        }
      },
      '/admin': {
        target: process.env.VITE_DEV_PROXY || 'http://localhost',
        changeOrigin: true
      }
    }
  },
  resolve: {
    alias: {
      // pnpm 严格结构下 echarts 的依赖（zrender / tslib）在顶层不可见（见文件头说明）。
      ...echartsDepAliases,
      '@': path.resolve(__dirname, 'src'),
      '@aicabinet/shared-dict': path.resolve(__dirname, '../../packages/shared-dict/src/index.ts'),
      '@aicabinet/shared-rbac': path.resolve(__dirname, '../../packages/shared-rbac/src/index.ts'),
      '@aicabinet/shared-types': path.resolve(
        __dirname,
        '../../packages/shared-types/src/index.ts'
      ),
      '@aicabinet/shared-uni': path.resolve(__dirname, '../../packages/shared-uni/src')
    }
  }
});
