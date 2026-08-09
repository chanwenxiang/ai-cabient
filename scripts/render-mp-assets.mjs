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

/** 商品演示图：从管理端 public/sku-demo 复制，保证三端同一套真实商品照片 */
function syncSkuImages() {
  const srcDir = path.join(root, 'clients/admin-vue/public/sku-demo');
  const targets = [
    'clients/consumer-mp/src/static/sku',
    'clients/merchant-mp/src/static/sku'
  ];
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
    const iconPage = await browser.newPage({ viewport: { width: 144, height: 144 } });
    for (const [name, glyph] of Object.entries(ICONS)) {
      for (const [suffix, active] of [
        ['', false],
        ['-active', true]
      ]) {
        const file = path.join(root, `clients/consumer-mp/src/static/tab/${name}${suffix}.png`);
        await iconPage.setContent(
          `<div style="width:144px;height:144px">${ICON_TEMPLATE(glyph, active)}</div>`
        );
        await iconPage.screenshot({ path: file, omitBackground: true });
      }
    }
    // 商户端复用 home/mine；devices/alerts 专属
    for (const [name, glyph] of Object.entries(ICONS)) {
      for (const [suffix, active] of [
        ['', false],
        ['-active', true]
      ]) {
        const file = path.join(root, `clients/merchant-mp/src/static/tab/${name}${suffix}.png`);
        await iconPage.setContent(
          `<div style="width:144px;height:144px">${ICON_TEMPLATE(glyph, active)}</div>`
        );
        await iconPage.screenshot({ path: file, omitBackground: true });
      }
    }
    await iconPage.close();

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
