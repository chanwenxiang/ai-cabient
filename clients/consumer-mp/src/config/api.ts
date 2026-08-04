/** H5 开发环境使用同源代理；小程序开发连接本机，生产构建强制使用显式 HTTPS 域名。 */
const developmentDefault = import.meta.env.DEV && typeof window !== 'undefined' ? '' : 'http://localhost:8080';
export const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || developmentDefault).replace(/\/$/, '');
