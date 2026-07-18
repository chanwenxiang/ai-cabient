/**
 * H5 开发（浏览器）走 Vite 同源代理，忽略局域网 VITE_API_BASE_URL。
 * 微信开发者工具 / 小程序仍使用 .env 中的本机或局域网地址。
 */
const envBase = (import.meta.env.VITE_API_BASE_URL || '').replace(/\/$/, '');
const isH5DevBrowser =
  import.meta.env.DEV &&
  typeof window !== 'undefined' &&
  typeof navigator !== 'undefined' &&
  !/miniProgram|miniprogram/i.test(navigator.userAgent);

export const API_BASE_URL = (isH5DevBrowser ? '' : envBase || 'http://localhost:8080').replace(
  /\/$/,
  ''
);
