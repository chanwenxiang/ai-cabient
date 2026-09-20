#!/usr/bin/env node
/**
 * 生成演示用「购物录像」样例（H.264 / MP4），供 H5 UAT 与演示种子使用。
 *
 * 为什么需要它：
 *   1. 旧的 `testdata/sample-shopping.mp4` 只有 22 KB，覆盖不了「大文件 / 长时播放 / 超时」场景；
 *   2. **模拟器产出的会话录像编码是 mp4v（MPEG-4 Part 2），Chromium 解不了** ——
 *      把它当录像喂给 H5，`<video>` 必然在 `readyState` 处红，那是环境的错，不是产品的错。
 *      所以样例必须是浏览器真能播的编码（avc1）。
 *
 * 为什么不用 ffmpeg：本机与 CI 都没有 ffmpeg（Playwright 自带的那个是裁剪版，
 * `--disable-everything` 只留 VP8 + webm，既没有 H.264 编码器也没有 mp4 封装）。
 * 而 Playwright 的 Chromium 原生支持 `MediaRecorder` + `video/mp4;codecs=avc1`，
 * 于是用**仓库已有依赖**就能产出合规 MP4，无需任何额外安装。
 *
 * 用法：
 *   node scripts/generate-demo-shopping-video.mjs
 *   node scripts/generate-demo-shopping-video.mjs --seconds 60 --out testdata/sample-shopping.mp4
 *   PW_CHANNEL=chromium node scripts/generate-demo-shopping-video.mjs
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const ROOT = path.resolve(__dirname, '..');

function parseArgs(argv) {
  const out = {
    out: path.join(ROOT, 'testdata', 'sample-shopping.mp4'),
    seconds: 30,
    width: 640,
    height: 480,
    fps: 30,
    bitrate: 800000,
    channel: process.env.PW_CHANNEL || 'chrome'
  };
  for (let i = 2; i < argv.length; i += 2) {
    const k = argv[i];
    const v = argv[i + 1];
    if (k === '--out') out.out = path.resolve(v);
    else if (k === '--seconds') out.seconds = Number(v);
    else if (k === '--width') out.width = Number(v);
    else if (k === '--height') out.height = Number(v);
    else if (k === '--fps') out.fps = Number(v);
    else if (k === '--bitrate') out.bitrate = Number(v);
    else if (k === '--channel') out.channel = v;
    else {
      console.error(`未知参数：${k}`);
      process.exit(2);
    }
  }
  return out;
}

const args = parseArgs(process.argv);
const MIME = 'video/mp4;codecs=avc1.42E01E';

const browser = await chromium.launch({ channel: args.channel, headless: true });
const page = await browser.newPage({ viewport: { width: args.width, height: args.height } });

const supported = await page.evaluate((m) => {
  return typeof MediaRecorder !== 'undefined' && MediaRecorder.isTypeSupported(m);
}, MIME);
if (!supported) {
  console.error(
    `当前 Chromium 不支持 ${MIME} 录制（channel=${args.channel}）。` +
      `请换用较新的 Chrome（Chrome 126+ 支持 MP4 录制）：PW_CHANNEL=chrome node scripts/generate-demo-shopping-video.mjs`
  );
  await browser.close();
  process.exit(1);
}

// 画一个「自动售货机购物」合成场景：货架 + 取物 + 时间码 + 轻微噪点。
// 必须有运动与噪点，否则编码器会把画面压到近乎 0 字节，就失去「大文件」的意义。
const b64 = await page.evaluate(
  async ({ width, height, fps, seconds, bitrate, mime }) => {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    document.body.appendChild(canvas);
    const ctx = canvas.getContext('2d');

    const SHELF_TOP = 90;
    const SHELF_H = 110;
    const ITEM_COLORS = ['#d94f4f', '#4f8ad9', '#4fb87a', '#d9a54f', '#8a4fd9', '#4fd0d9'];
    const items = [];
    for (let row = 0; row < 3; row++) {
      for (let col = 0; col < 5; col++) {
        items.push({
          x: 60 + col * 105,
          y: SHELF_TOP + row * SHELF_H + 26,
          w: 44,
          h: 70,
          color: ITEM_COLORS[(row * 5 + col) % ITEM_COLORS.length],
          taken: false,
          label: `S${row + 1}-${col + 1}`
        });
      }
    }

    const draw = (t) => {
      const totalMs = seconds * 1000;
      const p = Math.min(1, t / totalMs);
      // 背景
      const bg = ctx.createLinearGradient(0, 0, 0, height);
      bg.addColorStop(0, '#20242c');
      bg.addColorStop(1, '#12151a');
      ctx.fillStyle = bg;
      ctx.fillRect(0, 0, width, height);

      // 玻璃反光（缓慢移动，提供大面积运动）
      const gx = Math.sin(t / 900) * 120 + width / 2;
      const gl = ctx.createLinearGradient(gx - 160, 0, gx + 160, height);
      gl.addColorStop(0, 'rgba(255,255,255,0)');
      gl.addColorStop(0.5, 'rgba(255,255,255,0.10)');
      gl.addColorStop(1, 'rgba(255,255,255,0)');
      ctx.fillStyle = gl;
      ctx.fillRect(0, 0, width, height);

      // 货架
      for (let row = 0; row < 3; row++) {
        ctx.fillStyle = '#2c323d';
        ctx.fillRect(40, SHELF_TOP + row * SHELF_H + 96, width - 80, 8);
      }

      // 商品（按时间逐个被取走，制造内容变化）
      const takeAt = (i) => 0.25 + (i / items.length) * 0.5;
      items.forEach((it, i) => {
        const taken = p > takeAt(i);
        if (taken) return;
        ctx.fillStyle = it.color;
        ctx.beginPath();
        ctx.roundRect(it.x, it.y, it.w, it.h, 8);
        ctx.fill();
        ctx.fillStyle = 'rgba(255,255,255,0.85)';
        ctx.font = '11px sans-serif';
        ctx.fillText(it.label, it.x + 4, it.y + it.h - 8);
      });

      // 取物「手」（矩形块水平扫过）
      const handX = ((t / 40) % (width + 200)) - 100;
      ctx.fillStyle = 'rgba(230,200,170,0.85)';
      ctx.fillRect(handX, height - 150, 90, 26);

      // 时间码 + 进度条（文字也会占码率）
      ctx.fillStyle = 'rgba(0,0,0,0.55)';
      ctx.fillRect(8, 8, width - 16, 34);
      ctx.fillStyle = '#e8f0ff';
      ctx.font = 'bold 15px monospace';
      const sec = Math.floor(t / 1000);
      ctx.fillText(
        `CAB-001  REC  ${String(Math.floor(sec / 60)).padStart(2, '0')}:${String(sec % 60).padStart(2, '0')}`,
        18,
        31
      );
      ctx.fillStyle = '#2c323d';
      ctx.fillRect(8, height - 16, width - 16, 8);
      ctx.fillStyle = '#4f8ad9';
      ctx.fillRect(8, height - 16, (width - 16) * p, 8);

      // 轻微噪点：避免静态帧被压成 0 字节
      const noiseW = 120;
      const img = ctx.getImageData(0, 0, noiseW, 40);
      for (let i = 0; i < img.data.length; i += 4) {
        const n = (Math.random() - 0.5) * 40;
        img.data[i] += n;
        img.data[i + 1] += n;
        img.data[i + 2] += n;
      }
      ctx.putImageData(img, width - noiseW - 12, height - 70);
    };

    const stream = canvas.captureStream(fps);
    const rec = new MediaRecorder(stream, { mimeType: mime, videoBitsPerSecond: bitrate });
    const chunks = [];
    rec.ondataavailable = (e) => {
      if (e.data && e.data.size) chunks.push(e.data);
    };
    const done = new Promise((res) => (rec.onstop = res));
    rec.start(1000);

    const t0 = performance.now();
    await new Promise((res) => {
      const tick = () => {
        const t = performance.now() - t0;
        draw(t);
        if (t >= seconds * 1000) return res();
        requestAnimationFrame(tick);
      };
      requestAnimationFrame(tick);
    });
    rec.stop();
    await done;
    stream.getTracks().forEach((tr) => tr.stop());

    const blob = new Blob(chunks, { type: 'video/mp4' });
    const buf = await blob.arrayBuffer();
    // 转 base64 回传（几 MB 级别，Playwright 能承受）
    let bin = '';
    const bytes = new Uint8Array(buf);
    const STEP = 0x8000;
    for (let i = 0; i < bytes.length; i += STEP) {
      bin += String.fromCharCode.apply(null, bytes.subarray(i, i + STEP));
    }
    return btoa(bin);
  },
  {
    width: args.width,
    height: args.height,
    fps: args.fps,
    seconds: args.seconds,
    bitrate: args.bitrate,
    mime: MIME
  }
);

await browser.close();

const buf = Buffer.from(b64, 'base64');
fs.mkdirSync(path.dirname(args.out), { recursive: true });
fs.writeFileSync(args.out, buf);

// —— 自检：必须是浏览器可解的 MP4（ftyp + avc1），否则这份产物等于给 H5 埋雷 ——
const hasFtyp = buf.indexOf('ftyp') >= 0;
const hasAvc1 = buf.indexOf('avc1') >= 0;
const hasMp4v = buf.indexOf('mp4v') >= 0;
console.log(
  `wrote ${args.out}\n  bytes=${buf.byteLength} seconds=${args.seconds} fps=${args.fps} bitrate=${args.bitrate}\n  ftyp=${hasFtyp} avc1=${hasAvc1} mp4v=${hasMp4v}`
);
if (!hasFtyp || !hasAvc1 || hasMp4v) {
  console.error('生成的视频不是浏览器可解的 H.264 MP4，请检查 Chromium 版本/录制参数。');
  process.exit(1);
}
