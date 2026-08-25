import prettier from 'eslint-config-prettier';
import vue from 'eslint-plugin-vue';
import globals from 'globals';
import tseslint from 'typescript-eslint';

/**
 * 前端统一 ESLint（flat config）：
 * - TypeScript 推荐规则 + Vue 基础规则（essential）
 * - prettier 作为格式来源（由 `pnpm format` 统一排版）
 * - 类型层面交给 vue-tsc / tsc，ESLint 不再重复检查 no-undef
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
      'pnpm-lock.yaml'
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
    files: ['scripts/**/*.mjs'],
    languageOptions: {
      globals: { ...globals.node }
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
