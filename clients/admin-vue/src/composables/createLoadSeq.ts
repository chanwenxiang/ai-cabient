/**
 * 列表/筛选项快速连点时，丢弃过期响应，避免旧页盖住新页。
 * 用法：const loadSeq = createLoadSeq(); const seq = loadSeq.begin(); … if (!loadSeq.isCurrent(seq)) return;
 */
export function createLoadSeq() {
  let seq = 0;
  return {
    begin(): number {
      seq += 1;
      return seq;
    },
    isCurrent(token: number): boolean {
      return token === seq;
    }
  };
}
