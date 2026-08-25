/**
 * CSPRNG 短随机串：客户端提现请求号等（非密钥派生）。
 * 优先 Web Crypto；极旧运行时无 crypto 时用时间戳+计数兜底（避免 Math.random）。
 */
let fallbackSeq = 0;

export function secureRandomToken(byteLen = 8): string {
  const buf = new Uint8Array(byteLen);
  const cryptoObj = globalThis.crypto;
  if (cryptoObj && typeof cryptoObj.getRandomValues === 'function') {
    cryptoObj.getRandomValues(buf);
  } else {
    fallbackSeq = (fallbackSeq + 1) >>> 0;
    const t = Date.now();
    for (let i = 0; i < byteLen; i++) {
      buf[i] = (t + fallbackSeq * (i + 1) * 31) & 0xff;
    }
  }
  return Array.from(buf, (b) => b.toString(16).padStart(2, '0')).join('');
}
