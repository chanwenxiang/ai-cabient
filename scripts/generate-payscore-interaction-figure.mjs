#!/usr/bin/env node
/**
 * 生成微信支付分申请附件《场景交互图》（一整张流程长图）。
 *
 * 官方要求（申请邮件模板 §5）：
 *   「使用支付分的场景交互图：附件提供使用支付分场景的小程序/APP交互页面流程，需包含
 *     业务/商品展示页 → 提交订单页面（含介绍、计费规则、支付方式等）→ 使用支付分开通成功
 *     → 扣款成功 → 订单完成页」
 *   「请将以上各环节流程页合并为一整张流程图片」
 *
 * 本脚本：把原型 HTML 渲染成 PNG 长图（单张，横向 6 屏 + 标题 + 页脚）。
 *
 * 🔴 素材来源：支付分 logo 取自官方《微信支付分合作品牌线上应用规范》PDF 切图
 *    （规范 P6/P14：logo 图形不可重绘、不可改色、不可拉伸变形 ⇒ 必须用官方素材）。
 *
 * 用法：node scripts/generate-payscore-interaction-figure.mjs
 */
import { readFileSync, mkdirSync, existsSync } from 'node:fs';
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const TAG = '[generate-payscore-interaction-figure]';
const TEMPLATE = join(ROOT, 'scripts', 'templates', 'payscore-interaction-figure.html');
const LOGO = join(
  ROOT,
  'clients',
  'consumer-mp',
  'src',
  'static',
  'payscore',
  'payscore-logo@2x.png'
);
const OUT_DIR = join(ROOT, 'docs', 'figures');
const OUT_PNG = join(OUT_DIR, 'payscore-interaction-figure.png');

async function main() {
  if (!existsSync(TEMPLATE)) {
    console.error(`${TAG} FAIL：找不到原型模板 ${relative(ROOT, TEMPLATE)}`);
    process.exit(1);
  }
  if (!existsSync(LOGO)) {
    console.error(`${TAG} FAIL：找不到官方支付分 logo 素材 ${relative(ROOT, LOGO)}`);
    process.exit(1);
  }

  const logoDataUrl = `data:image/png;base64,${readFileSync(LOGO).toString('base64')}`;
  const html = readFileSync(TEMPLATE, 'utf8').replaceAll('__LOGO__', logoDataUrl);
  mkdirSync(OUT_DIR, { recursive: true });

  const browser = await chromium.launch();
  try {
    const page = await browser.newPage({
      viewport: { width: 2160, height: 1200 },
      deviceScaleFactor: 2
    });
    await page.setContent(html, { waitUntil: 'networkidle' });
    await page.waitForTimeout(400);

    const box = await page.evaluate(() => {
      const el = document.querySelector('.canvas');
      const r = el.getBoundingClientRect();
      return { width: Math.ceil(r.width), height: Math.ceil(r.height) };
    });
    await page.setViewportSize({ width: box.width, height: box.height });
    await page.waitForTimeout(200);

    await page.screenshot({ path: OUT_PNG, fullPage: true });
    const bytes = readFileSync(OUT_PNG);
    console.log(
      `${TAG} OK：${relative(ROOT, OUT_PNG)}  ${box.width}x${box.height} @2x  ${(bytes.length / 1024).toFixed(0)} KB`
    );
  } finally {
    await browser.close();
  }
}

main().catch((e) => {
  console.error(`${TAG} FAIL：${e.message}`);
  process.exit(1);
});
