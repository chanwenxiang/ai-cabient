/** 深色 / 浅色主题切换 */
const THEME_KEY = 'admin_theme';

function getTheme() {
  return localStorage.getItem(THEME_KEY) || 'dark';
}

function applyTheme(theme) {
  const t = theme === 'light' ? 'light' : 'dark';
  document.documentElement.setAttribute('data-theme', t);
  localStorage.setItem(THEME_KEY, t);
  const btn = document.getElementById('themeToggle');
  if (btn) {
    btn.textContent = t === 'dark' ? '浅色' : '深色';
    btn.title = t === 'dark' ? '切换为浅色主题' : '切换为深色主题';
    btn.setAttribute('aria-label', btn.title);
  }
}

function initTheme() {
  applyTheme(getTheme());
}

function toggleTheme() {
  applyTheme(getTheme() === 'dark' ? 'light' : 'dark');
}

export { initTheme, toggleTheme, getTheme, applyTheme };
