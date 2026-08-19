#!/usr/bin/env node
/**
 * 智谱视觉模型：验收截图识图点评
 *
 * 用法:
 *   node scripts/zhipu-vision-review.mjs <image-path> [prompt]
 *
 * 环境变量:
 *   ZHIPU_API_KEY       必填（https://bigmodel.cn 创建）
 *   ZHIPU_VISION_MODEL  可选，默认 glm-4v-flash（免费）；也可用 glm-4.5v / glm-4v-plus
 *
 * 示例:
 *   $env:ZHIPU_API_KEY="你的key"
 *   node scripts/zhipu-vision-review.mjs .tmp/ui-home.png "检查首页是否一屏展示、底栏是否突兀"
 */
import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

const API_URL = 'https://open.bigmodel.cn/api/paas/v4/chat/completions';
const DEFAULT_MODEL = process.env.ZHIPU_VISION_MODEL || 'glm-4v-flash';

const DEFAULT_PROMPT = `你是微信小程序 / H5 验收助手。请基于截图做结构化评审，用中文简短回答：

1. 页面是什么（首页/我的/登录/商户工作台等）
2. 一眼可见的问题（布局过高需滚动、底栏突兀、白块、对比度、间距过大、文字溢出等）
3. 对照常见小程序体验：哪些需要改、优先级（高/中/低）
4. 给出 3 条以内可执行的改法（具体到组件/区域，不要空话）

只评界面，不猜测业务逻辑。`;

function usageAndExit(code = 1) {
  console.error(`Usage: node scripts/zhipu-vision-review.mjs <image-path> [prompt]
Env: ZHIPU_API_KEY (required), ZHIPU_VISION_MODEL (optional, default ${DEFAULT_MODEL})`);
  process.exit(code);
}

function mimeOf(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  if (ext === '.jpg' || ext === '.jpeg') return 'image/jpeg';
  if (ext === '.webp') return 'image/webp';
  if (ext === '.gif') return 'image/gif';
  return 'image/png';
}

async function main() {
  const imagePath = process.argv[2];
  const prompt = process.argv.slice(3).join(' ').trim() || DEFAULT_PROMPT;
  const apiKey = process.env.ZHIPU_API_KEY || process.env.BIGMODEL_API_KEY;

  if (!imagePath) usageAndExit(1);
  if (!apiKey) {
    console.error('Missing ZHIPU_API_KEY (or BIGMODEL_API_KEY). Get one at https://bigmodel.cn');
    process.exit(2);
  }

  const abs = path.resolve(imagePath);
  if (!fs.existsSync(abs)) {
    console.error(`Image not found: ${abs}`);
    process.exit(3);
  }

  const buf = fs.readFileSync(abs);
  if (buf.length > 8 * 1024 * 1024) {
    console.error('Image too large (>8MB). Compress or crop first.');
    process.exit(4);
  }

  const dataUrl = `data:${mimeOf(abs)};base64,${buf.toString('base64')}`;
  const body = {
    model: DEFAULT_MODEL,
    messages: [
      {
        role: 'user',
        content: [
          { type: 'image_url', image_url: { url: dataUrl } },
          { type: 'text', text: prompt }
        ]
      }
    ],
    temperature: 0.2
  };

  const res = await fetch(API_URL, {
    method: 'POST',
    headers: {
      Authorization: `Bearer ${apiKey}`,
      'Content-Type': 'application/json'
    },
    body: JSON.stringify(body)
  });

  const raw = await res.text();
  let json;
  try {
    json = JSON.parse(raw);
  } catch {
    console.error(`HTTP ${res.status}: non-JSON response`);
    console.error(raw.slice(0, 800));
    process.exit(5);
  }

  if (!res.ok) {
    console.error(`HTTP ${res.status}:`, json.error?.message || json.message || raw.slice(0, 800));
    process.exit(5);
  }

  const text =
    json.choices?.[0]?.message?.content || json.choices?.[0]?.message?.reasoning_content || '';
  if (!text) {
    console.error('Empty model response:', JSON.stringify(json).slice(0, 800));
    process.exit(6);
  }

  process.stdout.write(String(text).trim() + '\n');
}

main().catch((err) => {
  console.error(err instanceof Error ? err.message : err);
  process.exit(1);
});
