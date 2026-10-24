/**
 * 有界 FIFO 缓存（2026-10-23 审计残留修复：dispute-evidence 本地预览缓存原先只进不出）。
 * 语义：同键重写视为刷新位置；超上限按最早写入淘汰。独立纯类，生产与测试调同一份。
 */
export class BoundedFifoCache {
  private readonly map = new Map<string, string>();

  constructor(private readonly max: number) {
    if (max < 1) throw new Error('max 必须 >= 1');
  }

  get(key: string): string | undefined {
    return this.map.get(key);
  }

  set(key: string, value: string) {
    if (this.map.has(key)) this.map.delete(key);
    this.map.set(key, value);
    while (this.map.size > this.max) {
      const oldest = this.map.keys().next().value as string | undefined;
      if (oldest === undefined) break;
      this.map.delete(oldest);
    }
  }
}
