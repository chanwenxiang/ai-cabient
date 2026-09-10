/**
 * 列表/筛选项快速连点时，丢弃过期响应，避免旧页盖住新页。
 * 用法：const loadSeq = createLoadSeq(); const seq = loadSeq.begin(); … if (!loadSeq.isCurrent(seq)) return;
 * 同页多列表并行加载时传 channel：begin('bills') / isCurrent(seq, 'bills')。
 */
export function createLoadSeq() {
  let seq = 0;
  const named = new Map<string, number>();
  return {
    begin(channel?: string): number {
      if (channel) {
        const next = (named.get(channel) || 0) + 1;
        named.set(channel, next);
        return next;
      }
      seq += 1;
      return seq;
    },
    isCurrent(token: number, channel?: string): boolean {
      if (channel) return named.get(channel) === token;
      return token === seq;
    }
  };
}
