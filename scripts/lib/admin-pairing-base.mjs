/**
 * MA-293：check-admin-artifact-pairing 的「范围基点决策」纯函数。
 *
 * 两处 fail-open 的根因（R23 实锤，CI run 43698709479 日志 09:20:40
 * `[check-admin-artifact-pairing] OK：无待推提交`）：
 *   ① merge-base 失败 ⇒ SKIP exit 0（CI 干净检出/首次推送时直接假绿）；
 *   ② `mb == head` ⇒ OK——CI push 检出把 origin/dev 指向 HEAD ⇒ 恒真，门禁恒绿。
 *
 * 修法：CI push 事件显式注入 `github.event.before`（env ADMIN_PAIR_CI_BEFORE），
 * 它是权威基点 ⇒ 走 `before..HEAD` 直连范围，merge-base 语义整体绕开；
 * 引用不存在（cat-file 失败）⇒ 红，不 SKIP。本地/PR 未注入时维持旧语义
 * （PR 事件没有 before，检出的是 merge commit，两者都不适用——维持现状并在
 * 台账 MA-293 注明 PR 语义仍待一次真 run 点验）。
 */

/**
 * @param {object} p
 * @param {string} p.ciBefore  env ADMIN_PAIR_CI_BEFORE（CI push 的 github.event.before，可为空）
 * @param {boolean} p.beforeRefExists  cat-file -e `${ciBefore}^{commit}` 是否成功（ciBefore 非空时才调用）
 * @param {boolean} p.mergeBaseOk  merge-base(BASE, HEAD) 是否成功
 * @param {string} p.mergeBaseOut  merge-base 输出（ok 时）
 * @param {string} p.head  rev-parse HEAD
 * @returns {{action:'fail'|'skip'|'ok-empty'|'proceed', rangeBase?:string, reason?:string}}
 */
export function pairingRangePlan({ ciBefore, beforeRefExists, mergeBaseOk, mergeBaseOut, head }) {
  const before = (ciBefore || '').trim();
  if (before) {
    if (!beforeRefExists) {
      return {
        action: 'fail',
        reason: `CI 提供了 ADMIN_PAIR_CI_BEFORE=${before}，但该引用在检出里不存在——范围无法判定，不得判绿`
      };
    }
    if (before === head) {
      return { action: 'ok-empty', reason: 'CI before == HEAD（空推送）' };
    }
    return { action: 'proceed', rangeBase: before };
  }
  if (!mergeBaseOk) {
    return {
      action: 'skip',
      reason: '取不到 merge-base（本地/PR 无 CI before，维持旧 SKIP 语义）'
    };
  }
  if (mergeBaseOut === head) {
    return { action: 'ok-empty', reason: '无待推提交' };
  }
  return { action: 'proceed', rangeBase: mergeBaseOut };
}
