/**
 * 运营后台入口：Vite 打包后合并为单文件，onclick 依赖 window 上的全局函数。
 */
import { initTheme } from './theme.js';
initTheme();
import './styles.css';
import './app.js';
import './ops-modules.js';
