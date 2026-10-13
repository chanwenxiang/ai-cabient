#!/usr/bin/env node
/**
 * C-admin 实测前置：判定「当前本地栈跑的到底是哪一版代码」。
 *
 * 存在理由：镜像标签不足以证明行为（本机 trade-service 镜像由主仓 dev 工作树构建，
 * 不含审计分支的入口校验修复）。这里用**行为指纹**把话说死：同一份畸形上报，
 * 审计分支必 400（值域校验在 findById 之前，见 VisionResultIngestService.ingest:73-76），
 * 未含修复的构建则落到会话查询 → 404。指纹不同 ⇒ 后续所有流程结论的归属版本必须如实标注。
 *
 * 用法：node scripts/devops/probe-audit-code-fingerprint.mjs [--base http://127.0.0.1:18080]
 */
const BASE = (
  process.argv.indexOf('--base') >= 0
    ? process.argv[process.argv.indexOf('--base') + 1]
    : 'http://127.0.0.1:18080'
).replace(/\/$/, '');
const KEY = process.env.INTERNAL_KEY || 'dev-internal-key-change-me';
const SESSION = 'AUDIT-FP-NONEXISTENT';

function payload(items, overall) {
  return {
    sessionId: SESSION,
    deviceId: '100000000001',
    taskId: 'AUDIT-FP-TASK',
    traceId: null,
    items,
    overallConfidence: overall,
    needReview: false,
    modelVersion: 'fp-1',
    detectedClasses: [],
    provider: 'QUECTEL',
    occurredAt: new Date().toISOString()
  };
}

import http from 'node:http';
import { URL } from 'node:url';

/**
 * 走 node:http 而非 fetch：Node 24 的 undici 连接池在 Windows 上退出时会触发
 * libuv `UV_HANDLE_CLOSING` 断言（脚本输出完整却拿到 126 退出码），门禁会把它读成失败。
 * 显式 Connection: close + agent:false 让 socket 在响应内关闭，退出干净。
 */
function post(body) {
  return new Promise((resolve, reject) => {
    const url = new URL(`${BASE}/internal/v1/vision/edge-results`);
    const payloadText = JSON.stringify(body);
    const req = http.request(
      {
        host: url.hostname,
        port: url.port,
        path: url.pathname,
        method: 'POST',
        agent: false,
        headers: {
          'Content-Type': 'application/json',
          'Content-Length': Buffer.byteLength(payloadText),
          'X-Internal-Api-Key': KEY,
          Connection: 'close'
        }
      },
      (res) => {
        let text = '';
        res.setEncoding('utf8');
        res.on('data', (chunk) => (text += chunk));
        res.on('end', () => {
          let message = '';
          try {
            message = JSON.parse(text)?.message || '';
          } catch {
            /* 非 JSON 保留原文 */
          }
          resolve({ status: res.statusCode, message: message.slice(0, 120) });
        });
      }
    );
    req.on('error', reject);
    req.end(payloadText);
  });
}

const cases = [
  {
    name: 'line-confidence-99',
    body: payload([{ skuId: 'SKU-FP', quantity: 1, confidence: 99.0 }], 0.9),
    auditExpected: 400,
    unpatchedExpected: 404
  },
  {
    name: 'overall-confidence-99',
    body: payload([{ skuId: 'SKU-FP', quantity: 1, confidence: 0.9 }], 99.0),
    auditExpected: 400,
    unpatchedExpected: 404
  },
  {
    name: 'quantity-above-entry-cap',
    body: payload([{ skuId: 'SKU-FP', quantity: 2_000_000_000, confidence: 0.9 }], 0.9),
    auditExpected: 400,
    unpatchedExpected: 404
  }
];

const out = [];
for (const c of cases) {
  const r = await post(c.body);
  const verdict =
    r.status === c.auditExpected
      ? 'AUDIT-CODE'
      : r.status === c.unpatchedExpected
        ? 'UNPATCHED'
        : 'OTHER';
  out.push({ case: c.name, ...r, verdict });
  console.log(`${c.name}: HTTP=${r.status} verdict=${verdict} message=${r.message}`);
}
const anyAudit = out.some((o) => o.verdict === 'AUDIT-CODE');
console.log(
  `\n结论：被测栈 ${anyAudit ? '包含审计分支入口校验（AU-039/038 批九代码在跑）' : '不含批九入口校验 = dev 构建（实测结论须标注为 dev 版本行为）'}`
);
console.log(JSON.stringify({ base: BASE, at: new Date().toISOString(), cases: out }, null, 2));
process.exit(0);
