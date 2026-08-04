/** Same-origin path only; reject protocol-relative and external URLs. */
export function safeRedirectPath(raw: unknown, fallback = '/dashboard'): string {
  if (typeof raw !== 'string') return fallback;
  let path = raw.trim();
  // 兼容旧 hash 深链：/dashboard#/merchants → /merchants
  const hashIdx = path.indexOf('#');
  if (hashIdx >= 0) {
    const after = path.slice(hashIdx + 1);
    path = after.startsWith('/') ? after : `/${after}`;
  }
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('://')) {
    return fallback;
  }
  return path;
}
