/**
 * 渲染小程序视觉资产（tab 图标 / 登录背景 / 商品演示图）。
 *
 * 用法：node scripts/render-mp-assets.mjs
 * 依赖：系统 Chrome（Playwright channel=chrome）；产物为 PNG，覆盖以下文件：
 *   clients/consumer-mp/src/static/tab/*.png
 *   clients/merchant-mp/src/static/tab/*.png
 *   clients/{consumer,merchant}-mp/src/static/login-bg.png
 *   clients/consumer-mp/src/static/sku/*.png
 */
import { chromium } from 'playwright';
import { readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

const GRAY = '#94a3b8';
const TEAL = '#0d9488';

const ICON_TEMPLATE = (glyph, color) =>
  `<svg xmlns="http://www.w3.org/2000/svg" width="81" height="81" viewBox="0 0 24 24" fill="none" stroke="${color}" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">${glyph}</svg>`;

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

/** 登录背景：品牌渐变 + 柔光 + 细网格（750x1334，与当前文件名兼容）。 */
function loginBackgroundSvg() {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="750" height="1334" viewBox="0 0 750 1334">
  <defs>
    <linearGradient id="bg" x1="0" y1="0" x2="0.4" y2="1">
      <stop offset="0" stop-color="#0f766e"/>
      <stop offset="0.55" stop-color="#0d9488"/>
      <stop offset="1" stop-color="#064e3b"/>
    </linearGradient>
    <radialGradient id="glowA" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#5eead4" stop-opacity="0.5"/>
      <stop offset="1" stop-color="#5eead4" stop-opacity="0"/>
    </radialGradient>
    <radialGradient id="glowB" cx="0.5" cy="0.5" r="0.5">
      <stop offset="0" stop-color="#ffffff" stop-opacity="0.18"/>
      <stop offset="1" stop-color="#ffffff" stop-opacity="0"/>
    </radialGradient>
    <pattern id="grid" width="48" height="48" patternUnits="userSpaceOnUse">
      <path d="M48 0H0v48" fill="none" stroke="#ffffff" stroke-opacity="0.05" stroke-width="1"/>
    </pattern>
  </defs>
  <rect width="750" height="1334" fill="url(#bg)"/>
  <rect width="750" height="1334" fill="url(#grid)"/>
  <circle cx="120" cy="180" r="230" fill="url(#glowA)"/>
  <circle cx="640" cy="420" r="300" fill="url(#glowA)"/>
  <circle cx="360" cy="980" r="320" fill="url(#glowB)"/>
  <circle cx="70" cy="1120" r="180" fill="url(#glowA)" opacity="0.7"/>
</svg>`;
}

/** 商品演示图：浅色卡片 + 简洁产品剪影（400x400）。 */
function skuSvg({ bg, shape, label }) {
  return `<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400" viewBox="0 0 400 400">
  <defs>
    <linearGradient id="card" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0" stop-color="${bg}"/>
      <stop offset="1" stop-color="${bg}" stop-opacity="0.72"/>
    </linearGradient>
  </defs>
  <rect x="22" y="22" width="356" height="356" rx="42" fill="url(#card)"/>
  <rect x="22" y="22" width="356" height="356" rx="42" fill="none" stroke="#ffffff" stroke-opacity="0.7" stroke-width="3"/>
  <g transform="translate(200,178)">${shape}</g>
  <text x="200" y="312" text-anchor="middle" font-family="sans-serif" font-size="30" font-weight="600" fill="#ffffff" opacity="0.92">${label}</text>
</svg>`;
}

const SKU_SHAPES = {
  chips:
    '<path d="M-62 34 6 -56a30 30 0 0 1 42 -8l70 78a30 30 0 0 1 -12 42L-20 88a30 30 0 0 1 -42 -54Z" fill="#fbbf24" stroke="#f59e0b" stroke-width="6"/>',
  cola: '<path d="M-28 -64h56l10 40a38 38 0 0 1 -76 0Z" fill="#ef4444" stroke="#dc2626" stroke-width="6"/><rect x="-34" y="-22" width="68" height="86" rx="12" fill="#7f1d1d"/><path d="M-20 -6h40M-20 12h40M-20 30h40" stroke="#ffffff" stroke-width="6" stroke-linecap="round"/>',
  milk: '<path d="M-26 -70h52l8 24 8 74a20 20 0 0 1 -20 20H-22a20 20 0 0 1 -20 -20l8 -74Z" fill="#ffffff" stroke="#cbd5e1" stroke-width="6"/><path d="M-16 -44h32" stroke="#0d9488" stroke-width="8" stroke-linecap="round"/>',
  noodle:
    '<rect x="-62" y="-34" width="124" height="92" rx="22" fill="#f97316" stroke="#ea580c" stroke-width="6"/><path d="M-40 -12c12 -14 24 14 36 0M-4 -12c12 -14 24 14 36 0" stroke="#fde68a" stroke-width="7" stroke-linecap="round"/>',
  sprite:
    '<path d="M-30 -66h60l8 40a36 36 0 0 1 -76 0Z" fill="#a7f3d0" stroke="#34d399" stroke-width="6"/><rect x="-36" y="-24" width="72" height="92" rx="14" fill="#ecfdf5" stroke="#a7f3d0" stroke-width="4"/><path d="M-20 -6h40M-20 12h40M-20 30h40" stroke="#34d399" stroke-width="6" stroke-linecap="round"/>',
  water:
    '<path d="M0 -52c22 22 34 38 34 58a34 34 0 0 1 -68 0c0 -20 12 -36 34 -58Z" fill="#bae6fd" stroke="#38bdf8" stroke-width="6"/><path d="M-14 10a14 14 0 0 0 14 14" stroke="#ffffff" stroke-width="6" stroke-linecap="round"/>'
};

const SKU_LABELS = {
  chips: '薯片',
  cola: '可乐',
  milk: '牛奶',
  noodle: '泡面',
  sprite: '雪碧',
  water: '矿泉水'
};

async function main() {
  const browser = await chromium.launch({ channel: 'chrome', headless: true });
  try {
    const iconPage = await browser.newPage({ viewport: { width: 81, height: 81 } });
    for (const [name, glyph] of Object.entries(ICONS)) {
      for (const [suffix, color] of [
        ['', GRAY],
        ['-active', TEAL]
      ]) {
        const file = path.join(root, `clients/consumer-mp/src/static/tab/${name}${suffix}.png`);
        await iconPage.setContent(
          `<div style="width:81px;height:81px">${ICON_TEMPLATE(glyph, color)}</div>`
        );
        await iconPage.screenshot({ path: file, omitBackground: true });
      }
    }
    // 商户端复用 home/mine；devices/alerts 专属
    for (const [name, glyph] of Object.entries(ICONS)) {
      for (const [suffix, color] of [
        ['', GRAY],
        ['-active', TEAL]
      ]) {
        const file = path.join(root, `clients/merchant-mp/src/static/tab/${name}${suffix}.png`);
        await iconPage.setContent(
          `<div style="width:81px;height:81px">${ICON_TEMPLATE(glyph, color)}</div>`
        );
        await iconPage.screenshot({ path: file, omitBackground: true });
      }
    }
    await iconPage.close();

    const bgPage = await browser.newPage({ viewport: { width: 750, height: 1334 } });
    await bgPage.setContent(loginBackgroundSvg());
    const bgConsumer = path.join(root, 'clients/consumer-mp/src/static/login-bg.png');
    const bgMerchant = path.join(root, 'clients/merchant-mp/src/static/login-bg.png');
    await bgPage.screenshot({ path: bgConsumer });
    writeFileSync(bgMerchant, readFileSync(bgConsumer));
    await bgPage.close();

    const skuPage = await browser.newPage({ viewport: { width: 400, height: 400 } });
    for (const [name, shape] of Object.entries(SKU_SHAPES)) {
      const file = path.join(root, `clients/consumer-mp/src/static/sku/${name}.png`);
      const bg =
        name === 'water' || name === 'sprite' ? '#0ea5e9' : name === 'milk' ? '#64748b' : '#f59e0b';
      await skuPage.setContent(skuSvg({ bg, shape, label: SKU_LABELS[name] }));
      await skuPage.screenshot({ path: file });
    }
    await skuPage.close();

    console.log('render-mp-assets ok');
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
