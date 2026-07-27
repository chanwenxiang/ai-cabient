/** Same-origin path only; reject protocol-relative and external URLs. */
export function safeRedirectPath(raw: unknown, fallback = '/dashboard'): string {
  if (typeof raw !== 'string') return fallback;
  const path = raw.trim();
  if (!path.startsWith('/') || path.startsWith('//') || path.includes('://')) {
    return fallback;
  }
  return path;
}
