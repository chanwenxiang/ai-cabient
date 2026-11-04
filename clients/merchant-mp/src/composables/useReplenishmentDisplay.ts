import type { Ref } from 'vue';
import type { MerchantSkuPricing } from '@aicabinet/shared-types';
import { absoluteImageUrl } from '@aicabinet/shared-uni/product-image';

export { absoluteImageUrl };

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

export function isPullOffType(type?: string | null) {
  const code = String(type || 'RESTOCK').toUpperCase();
  return code === 'PULL_OFF' || code === 'REMOVE' || code === 'PULL';
}

/**
 * 补货详情展示：SKU 名/图、行摘要。
 */
export function useReplenishmentDisplay(opts: { skus: Ref<MerchantSkuPricing[]> }) {
  function skuName(id: string) {
    const s = opts.skus.value.find((item) => item.skuId === id);
    return s?.skuName || id;
  }

  function skuThumb(id: string) {
    // 演示 SKU 本地图已于 2026-10-24 下线；商品图仅取后台上传地址，无图时走 productGlyph 占位
    const s = opts.skus.value.find((item) => item.skuId === id);
    return absoluteImageUrl(s?.imageUrl) || '';
  }

  function productGlyph(id: string) {
    const name = String(skuName(id) || '').trim();
    return name ? name.slice(0, 1) : '货';
  }

  function formatLineSummary(rows: Line[]): string {
    if (!rows.length) return '暂无明细行';
    const qty = rows.reduce((s, l) => s + Math.max(0, Number(l.quantity) || 0), 0);
    const pull = rows.filter((l) => isPullOffType(l.lineType)).length;
    const restock = rows.length - pull;
    const noExpiry = rows.filter((l) => !String(l.expiryDate || '').trim()).length;
    const parts = [`${rows.length} 行`, `共 ${qty} 件`];
    if (restock > 0) parts.push(`补货 ${restock}`);
    if (pull > 0) parts.push(`下架 ${pull}`);
    if (noExpiry > 0) parts.push(`${noExpiry} 行缺效期`);
    return parts.join(' · ');
  }

  function lineTypeLabel(type?: string | null) {
    return isPullOffType(type) ? '下架' : '上架';
  }

  function lineStatusLabel(line: Line) {
    if (line.applied) return isPullOffType(line.lineType) ? '已下架' : '已入柜';
    return isPullOffType(line.lineType) ? '待下架' : '待上架';
  }

  type TaskLike = { status?: string; notes?: string; checkInAt?: string };

  function taskLooksPullOff(task: TaskLike) {
    return /from-expiry|PULL_OFF|下架|临期/i.test(String(task.notes || ''));
  }

  function knownTaskNoteLabel(raw: string): string {
    if (/from-expiry|NEAR_EXPIRY/i.test(raw)) return '临期商品下架';
    if (/PULL_OFF/i.test(raw) && !/[\u4e00-\u9fff]/.test(raw)) return '下架任务';
    return '';
  }

  function stripMachineTaskNoteTokens(raw: string): string {
    return raw
      .replaceAll(/from-expiry:\d+/gi, '')
      .replaceAll(/\bNEAR_EXPIRY\b/gi, '')
      .replaceAll(/\bPULL_OFF\b/gi, '')
      .replaceAll(/\bseq=\d+\b/gi, '')
      .replaceAll(/\bdist=\d+m?\b/gi, '')
      .replaceAll(/[|;,]+/g, ' ')
      .trim();
  }

  function isOpaqueMachineNote(cleaned: string): boolean {
    return !/[\u4e00-\u9fff]/.test(cleaned) && /^[\w:=\-.\s]+$/.test(cleaned);
  }

  /** 机器备注转可读文案；seq=/dist= 等内部字段不展示 */
  function displayTaskNotes(notes?: string): string {
    const raw = String(notes || '').trim();
    if (!raw) return '';
    const known = knownTaskNoteLabel(raw);
    if (known) return known;
    const cleaned = stripMachineTaskNoteTokens(raw);
    if (!cleaned || isOpaqueMachineNote(cleaned)) return '';
    return cleaned;
  }

  function taskActionLabel(task: TaskLike) {
    if (task.status === 'COMPLETED') return '查看完成明细';
    const pull = taskLooksPullOff(task);
    if (task.checkInAt) return pull ? '继续下架' : '继续补货';
    return pull ? '开始下架' : '开始补货';
  }

  return {
    skuName,
    skuThumb,
    productGlyph,
    formatLineSummary,
    isPullOffType,
    lineTypeLabel,
    lineStatusLabel,
    displayTaskNotes,
    taskActionLabel
  };
}
