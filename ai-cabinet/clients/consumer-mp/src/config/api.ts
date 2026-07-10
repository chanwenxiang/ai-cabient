/** 开发环境默认连接本机；生产构建由 validate-miniapp-env.mjs 强制校验 HTTPS 域名。 */
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080').replace(/\/$/, '');
