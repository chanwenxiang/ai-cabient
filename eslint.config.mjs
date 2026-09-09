import prettier from 'eslint-config-prettier';
import vue from 'eslint-plugin-vue';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import local from './tools/eslint-plugin-local/index.mjs';

/**
 * 前端统一 ESLint（flat config）：
 * - TypeScript 推荐规则 + Vue 基础规则（essential）
 * - prettier 作为格式来源（由 `pnpm format` 统一排版）
 * - 类型层面交给 vue-tsc / tsc，ESLint 不再重复检查 no-undef
 * - 订单域禁止硬编码 order_status 中文（走 shared-dict）
 */
export default tseslint.config(
  {
    ignores: [
      '**/.cursor/**',
      '**/node_modules/**',
      '**/dist/**',
      '**/bin/**',
      'services/trade-service/src/main/resources/static/**',
      '**/output/**',
      '**/target/**',
      '**/package-lock.json',
      'pnpm-lock.yaml',
      'packages/shared-types/src/generated/**'
    ]
  },
  ...tseslint.configs.recommended,
  ...vue.configs['flat/essential'],
  prettier,
  {
    files: ['**/*.{ts,vue}'],
    languageOptions: {
      parserOptions: {
        parser: tseslint.parser,
        extraFileExtensions: ['.vue']
      }
    },
    rules: {
      'no-undef': 'off',
      '@typescript-eslint/no-explicit-any': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }
      ],
      'no-empty': ['error', { allowEmptyCatch: true }],
      'vue/multi-word-component-names': 'off',
      'vue/no-v-html': 'off',
      'vue/require-default-prop': 'off',
      'vue/attributes-order': 'off'
    }
  },
  {
    // 状态文案 + any 收紧：订单 / 公告 / 异常 / 余额退款 / 小程序订单
    files: [
      'clients/admin-vue/src/views/orders/**/*.{vue,ts}',
      'clients/admin-vue/src/views/announcements/**/*.{vue,ts}',
      'clients/admin-vue/src/views/exceptions/**/*.{vue,ts}',
      'clients/admin-vue/src/views/finance/BalanceRefundView.vue',
      'clients/admin-vue/src/api/client.ts',
      'clients/*/src/pages/orders/**/*.{vue,ts}',
      'clients/*/src/pages/order-detail/**/*.{vue,ts}'
    ],
    plugins: { local },
    rules: {
      'local/no-hardcoded-status-label': 'error',
      '@typescript-eslint/no-explicit-any': 'warn'
    }
  },
  {
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: { ...globals.node }
    }
  },
  {
    // Playwright UAT 脚本：允许 `ok ? pass++ : fail++` 等计数写法
    files: ['clients/**/tests/**/*.mjs'],
    languageOptions: {
      globals: { ...globals.node }
    },
    rules: {
      '@typescript-eslint/no-unused-expressions': 'off',
      'no-unused-expressions': 'off',
      '@typescript-eslint/no-unused-vars': [
        'warn',
        { argsIgnorePattern: '^_', varsIgnorePattern: '^_' }
      ]
    }
  },
  {
    // 少量遗留 CJS/Node 脚本：允许 require
    files: ['**/*.cjs', 'scripts/**/*.js'],
    rules: {
      '@typescript-eslint/no-require-imports': 'off'
    }
  }
);
