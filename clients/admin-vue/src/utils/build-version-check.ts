import { ElMessageBox } from 'element-plus';

/**
 * 前端版本更新检测（2026-10-14 审计补齐）。
 *
 * 后台是 SPA 且运营常驻一整天：发新版后，开着的标签页仍在跑旧 bundle，
 * 新旧接口字段错位时会「静默出错」。做法：
 * - 窗口聚焦/定时重新拉取当前 URL 的 HTML（cache: no-store）；
 * - 解析其中 vite 入口 `<script type="module" src="...">` 的指纹（生产为 hash 文件名）；
 * - 与加载时记录的入口比对，不同 ⇒ 弹「系统已更新」确认框 → 整页 reload。
 *
 * dev 环境入口固定为 /src/main.ts，指纹不变 ⇒ 不会误报。
 * 拉取失败（断网/网关异常）静默，交给断网横幅提示。
 */
let knownMainScript = '';
let checking = false;
let prompting = false;

function detectMainScript(html: string): string | null {
  const m = html.match(/<script[^>]+type="module"[^>]+src="([^"]+)"/);
  return m ? m[1] : null;
}

function currentMainScript(): string | null {
  const scripts = document.querySelectorAll<HTMLScriptElement>('script[type="module"][src]');
  const last = scripts[scripts.length - 1];
  return last ? last.getAttribute('src') : null;
}

export function startBuildVersionCheck(intervalMs = 5 * 60_000): void {
  knownMainScript = currentMainScript() || '';
  if (!knownMainScript) return;
  const check = async () => {
    if (checking || prompting) return;
    checking = true;
    try {
      const res = await fetch(window.location.href, { cache: 'no-store' });
      if (!res.ok) return;
      const fresh = detectMainScript(await res.text());
      if (!fresh || fresh === knownMainScript) return;
      prompting = true;
      try {
        await ElMessageBox.confirm(
          '检测到系统已发布新版本，请刷新页面以获取最新功能与修复。',
          '系统已更新',
          { confirmButtonText: '立即刷新', cancelButtonText: '稍后', type: 'info' }
        );
        window.location.reload();
      } catch {
        /* 用户选「稍后」：下个聚焦/定时周期再问 */
        prompting = false;
      }
    } catch {
      /* 断网等异常静默 */
    } finally {
      checking = false;
    }
  };
  window.addEventListener('focus', check);
  window.setInterval(check, intervalMs);
}
