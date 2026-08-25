/**
 * 微信小程序运行时通常没有 URLSearchParams，统一用手写 query。
 */
export function toQuery(
  params: Record<string, string | number | boolean | null | undefined>
): string {
  const parts: string[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (value == null || value === '') continue;
    parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
  }
  return parts.join('&');
}

/** 追加可选参数；全部为空时返回空串（调用方自行决定是否加 `?`）。 */
export function withQuery(
  path: string,
  params: Record<string, string | number | boolean | null | undefined>
): string {
  const qs = toQuery(params);
  if (!qs) return path;
  return path.includes('?') ? `${path}&${qs}` : `${path}?${qs}`;
}

/** 解析 `a=1&b=2`（可带前导 `?`/`#`）；不依赖 URLSearchParams。 */
export function parseQuery(query: string): Record<string, string> {
  const out: Record<string, string> = {};
  const raw = String(query || '').replace(/^[?#]/, '');
  if (!raw) return out;
  for (const part of raw.split('&')) {
    if (!part) continue;
    const eq = part.indexOf('=');
    try {
      const k = decodeURIComponent((eq >= 0 ? part.slice(0, eq) : part).replace(/\+/g, ' '));
      if (!k) continue;
      const v = eq >= 0 ? decodeURIComponent(part.slice(eq + 1).replace(/\+/g, ' ')) : '';
      out[k] = v;
    } catch {
      /* skip malformed segment */
    }
  }
  return out;
}

export function queryGet(query: string, name: string): string {
  return parseQuery(query)[name] || '';
}
