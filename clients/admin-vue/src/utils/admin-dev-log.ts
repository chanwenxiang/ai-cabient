/** 仅开发态输出的控制台告警，避免生产包残留 console.warn（A-P2-007）。 */
export function adminDevWarn(...args: unknown[]) {
  if (import.meta.env.DEV && typeof console !== 'undefined' && console.warn) {
    console.warn(...args);
  }
}

export function adminDevError(...args: unknown[]) {
  if (import.meta.env.DEV && typeof console !== 'undefined' && console.error) {
    console.error(...args);
  }
}
