/** 生产构建默认关闭测试工具；开发环境默认开启。可用 VITE_ENABLE_TEST_TOOLS=true/false 覆盖。 */
export const ENABLE_TEST_TOOLS =
  import.meta.env.VITE_ENABLE_TEST_TOOLS === 'true' ||
  (import.meta.env.VITE_ENABLE_TEST_TOOLS !== 'false' && import.meta.env.DEV);
