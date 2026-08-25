/**
 * 渲染小程序视觉资产（tab 图标 / 商品演示图）。
 *
 * 用法：node scripts/render-mp-assets.mjs
 * 依赖：系统 Chrome（Playwright channel=chrome）；产物为 PNG，覆盖以下文件：
 *   clients/consumer-mp/src/static/tab/*.png
 *   clients/merchant-mp/src/static/tab/*.png
 * 商品图（clients/{consumer,merchant}-mp/src/static/sku/*.jpg）直接复制自
 * 管理端 clients/admin-vue/public/sku-demo/*.jpg，保证三端同图。
 */
import { chromium } from 'playwright';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import fs from 'node:fs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const GRAY = '#94a3b8';
const TEAL = '#0d9488';

// Reset page margins so the capture matches the SVG canvas exactly
// (the default 8px body margin used to shift every icon toward the top-left).
const RESET_CSS = 'html,body{margin:0;padding:0;overflow:hidden}';

// Render an SVG to a PNG of the exact requested size via a clipped screenshot.
async function renderSvgToPng(page, svgHtml, file, size) {
  await page.setContent(
    `<!doctype html><html><head><style>${RESET_CSS}</style></head><body>${svgHtml}</body></html>`
  );
  await page.screenshot({
    path: file,
    omitBackground: true,
    clip: { x: 0, y: 0, width: size, height: size }
  });
}

/** 精致 tab 图标：144x144（2x），圆角渐变底 + 白色线性图标。 */
const ICON_TEMPLATE = (glyph, active) =>
  active
    ? `<svg xmlns="http://www.w3.org/2000/svg" width="144" height="144" viewBox="0 0 48 48">
  <defs>
    <linearGradient id="g" x1="0" y1="0" x2="1" y2="1">
      <stop offset="0" stop-color="#0f766e"/>
      <stop offset="1" stop-color="#14b8a6"/>
    </linearGradient>
  </defs>
  <rect x="4" y="4" width="40" height="40" rx="13" fill="url(#g)"/>
  <g transform="translate(12,12) scale(1)" fill="none" stroke="#ffffff" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">${glyph}</g>
</svg>`
    : `<svg xmlns="http://www.w3.org/2000/svg" width="144" height="144" viewBox="0 0 48 48">
  <rect x="4" y="4" width="40" height="40" rx="13" fill="#eef2f1"/>
  <g transform="translate(12,12) scale(1)" fill="none" stroke="${GRAY}" stroke-width="2.1" stroke-linecap="round" stroke-linejoin="round">${glyph}</g>
</svg>`;

const ICONS = {
  home: '<path d="M3 10.5 12 3l9 7.5"/><path d="M5 9.5V21h14V9.5"/><path d="M10 21v-5h4v5"/>',
  orders:
    '<path d="M7 3h10a2 2 0 0 1 2 2v16l-2-1.5-2 1.5-2-1.5-2 1.5-2-1.5L7 21V5a2 2 0 0 1 2-2Z"/><path d="M9 8h6M9 12h6"/>',
  mine: '<circle cx="12" cy="9" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/>',
  devices:
    '<rect x="3" y="3" width="18" height="18" rx="2.5"/><path d="M12 3v18"/><path d="M8 9.5h8M8 14.5h8"/>',
  alerts:
    '<path d="M12 4a6 6 0 0 1 6 6v4l2 3H4l2-3v-4a6 6 0 0 1 6-6Z"/><path d="M10 20a2 2 0 0 0 4 0"/>'
};

/** 菜单/快捷项线性图标（Feather 风格，24 视图框；页面以 <image> 展示） */
const MENU_ICONS = {
  orders:
    '<path d="M6 3h12a1 1 0 0 1 1 1v17l-3-2-3 2-3-2-3 2V4a1 1 0 0 1 1-1Z"/><path d="M9 8h6M9 12h6"/>',
  coupons:
    '<path d="M20 8a3 3 0 0 1-3-3H7a3 3 0 0 1-3 3 3 3 0 0 1 0 8 3 3 0 0 1 3 3h10a3 3 0 0 1 3-3 3 3 0 0 1 0-8Z"/><path d="M12 5v14"/>',
  member: '<circle cx="12" cy="8" r="3.5"/><path d="M5 20a7 7 0 0 1 14 0"/>',
  recharge:
    '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M16 12h5v4h-5a2 2 0 0 1 0-4Z"/>',
  shopping:
    '<circle cx="9" cy="20" r="1.3"/><circle cx="17" cy="20" r="1.3"/><path d="M3 4h2l2.3 11h10.2L20 8H6"/>',
  hot: '<path d="M3 17l6-6 4 4 8-8"/><path d="M15 7h6v6"/>',
  balance:
    '<line x1="12" y1="2" x2="12" y2="22"/><path d="M17 6H9.5a3.5 3.5 0 0 0 0 7h5a3.5 3.5 0 0 1 0 7H6"/>',
  notice:
    '<path d="M18 8a6 6 0 0 0-12 0c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.7 21a2 2 0 0 1-3.4 0"/>',
  help: '<circle cx="12" cy="12" r="9"/><path d="M9.4 9a2.6 2.6 0 0 1 5.2 0c0 1.7-2.6 2.1-2.6 3.6"/><path d="M12 17h.01"/>',
  repair:
    '<path d="M14.7 6.3a4 4 0 0 0-5.4 5.4L4 17l3 3 5.3-5.3a4 4 0 0 0 5.4-5.4l-2.6 2.6-2.8-2.8Z"/>',
  feedback: '<path d="M21 11.5a8 8 0 0 1-8 8H4l2-3a8 8 0 1 1 15-5Z"/>',
  agreement:
    '<path d="M14 3H7a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V8Z"/><path d="M14 3v5h5"/><path d="M9 13h6M9 17h4"/>',
  privacy: '<path d="M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6Z"/>',
  refund: '<path d="M3 12a9 9 0 1 0 3-6.7"/><path d="M3 4v5h5"/>',
  billing: '<rect x="2" y="5" width="20" height="14" rx="2"/><path d="M2 10h20"/>',
  wechat:
    '<path d="M21 15a6 6 0 0 1-6 6H5l-2 2V10a6 6 0 0 1 6-6h6a6 6 0 0 1 6 6Z"/><path d="M8 12h.01M13 12h.01"/>',
  alipay: '<path d="M13 2 4 14h6l-1 8 9-12h-6Z"/>',
  mock: '<circle cx="12" cy="12" r="9"/><path d="M10 8.5l6 3.5-6 3.5Z"/>',
  phone: '<rect x="6" y="2" width="12" height="20" rx="3"/><path d="M11 18h2"/>',
  logout:
    '<path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"/><path d="M16 17l5-5-5-5"/><path d="M21 12H9"/>',
  replenish: '<path d="M21 8l-9-5-9 5v8l9 5 9-5Z"/><path d="M3 8l9 5 9-5"/><path d="M12 13v8"/>',
  cabinet:
    '<rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/>',
  pending: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/>',
  pricing:
    '<path d="M20.6 13.4 13.4 20.6a2 2 0 0 1-2.8 0L3 13V3h10l7.6 7.6a2 2 0 0 1 0 2.8Z"/><circle cx="7.5" cy="7.5" r="1.1"/>',
  settlements:
    '<rect x="3" y="6" width="18" height="13" rx="2"/><path d="M16 12h5v4h-5a2 2 0 0 1 0-4Z"/>',
  wallet:
    '<rect x="2" y="6" width="20" height="12" rx="2"/><circle cx="12" cy="12" r="2.3"/><path d="M6 12h.01M18 12h.01"/>',
  splits:
    '<circle cx="6" cy="6" r="3"/><circle cx="18" cy="6" r="3"/><circle cx="12" cy="18" r="3"/><path d="M8.5 7.5 11 16M15.5 7.5 13 16"/>',
  'line-wallet':
    '<path d="M10 13a5 5 0 0 0 7.5.5l3-3a5 5 0 0 0-7-7l-1.7 1.7"/><path d="M14 11a5 5 0 0 0-7.5-.5l-3 3a5 5 0 0 0 7 7l1.7-1.7"/>',
  disputes:
    '<path d="M12 3l7 3v6c0 4-3 7-7 9-4-2-7-5-7-9V6Z"/><path d="M12 8v4"/><path d="M12 16h.01"/>',
  business: '<path d="M18 20V10M12 20V4M6 20v-6"/>',
  team: '<path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/>',
  gift: '<rect x="3" y="8" width="18" height="4" rx="1"/><path d="M12 8v13"/><path d="M12 8H7a2.5 2.5 0 0 1 0-5c2 0 4 2.5 5 5Zm0 0h5a2.5 2.5 0 0 0 0-5c-2 0-4 2.5-5 5Z"/>',
  'check-circle': '<circle cx="12" cy="12" r="9"/><path d="m8.5 12 2.5 2.5 4.5-5"/>',
  warning: '<path d="M12 3 2 20h20Z"/><path d="M12 10v4"/><path d="M12 17h.01"/>'
};

/** 需要特殊配色的图标（如退出登录用警示红） */
const MENU_ICON_COLORS = {
  logout: '#ef4444'
};

const MENU_ICON_TEMPLATE = (
  path,
  color
) => `<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 24 24">
  <g transform="translate(2.4,2.4) scale(0.8)" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${path}</g>
</svg>`;

/** 商品演示图：从管理端 public/sku-demo 复制，保证三端同一套真实商品照片 */
function syncSkuImages() {
  const srcDir = path.join(root, 'clients/admin-vue/public/sku-demo');
  const targets = ['clients/consumer-mp/src/static/sku', 'clients/merchant-mp/src/static/sku'];
  for (const rel of targets) {
    const outDir = path.join(root, rel);
    fs.mkdirSync(outDir, { recursive: true });
  }
  for (const file of fs.readdirSync(srcDir)) {
    if (!/\.(jpe?g|png|webp)$/i.test(file)) continue;
    for (const rel of targets) {
      fs.copyFileSync(path.join(srcDir, file), path.join(root, rel, file));
    }
  }
}

async function main() {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const tabPage = await browser.newPage({ viewport: { width: 144, height: 144 } });
    for (const [name, glyph] of Object.entries(ICONS)) {
      for (const [suffix, active] of [
        ['', false],
        ['-active', true]
      ]) {
        const file = path.join(root, `clients/consumer-mp/src/static/tab/${name}${suffix}.png`);
        await renderSvgToPng(tabPage, ICON_TEMPLATE(glyph, active), file, 144);
      }
    }
    // 商户端复用 home/mine；devices/alerts 专属
    for (const [name, glyph] of Object.entries(ICONS)) {
      for (const [suffix, active] of [
        ['', false],
        ['-active', true]
      ]) {
        const file = path.join(root, `clients/merchant-mp/src/static/tab/${name}${suffix}.png`);
        await renderSvgToPng(tabPage, ICON_TEMPLATE(glyph, active), file, 144);
      }
    }
    // 菜单/快捷项线性图标（两端共用同一套）
    const menuPage = await browser.newPage({ viewport: { width: 96, height: 96 } });
    for (const [name, d] of Object.entries(MENU_ICONS)) {
      const color = MENU_ICON_COLORS[name] || TEAL;
      for (const rel of [
        'clients/consumer-mp/src/static/menu',
        'clients/merchant-mp/src/static/menu'
      ]) {
        const file = path.join(root, `${rel}/${name}.png`);
        await renderSvgToPng(menuPage, MENU_ICON_TEMPLATE(d, color), file, 96);
      }
    }
    await tabPage.close();
    await menuPage.close();

    syncSkuImages();

    console.log('render-mp-assets ok（tab 图标已渲染，商品图已同步）');
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
