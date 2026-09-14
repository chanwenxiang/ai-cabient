import type { Ref } from 'vue';
import type { DeviceSlot, MerchantSkuPricing } from '@aicabinet/shared-types';
import { API_BASE_URL } from '@/config/api';

type Line = import('@aicabinet/shared-types').OpenApiReplenishmentTaskLineDto;

/** 演示 SKU 本地兜底图；正式商品图由后台上传 */
export const LOCAL_SKU_THUMBS: Record<string, string> = {
  'SKU-DEMO-001': '/static/sku/cola.jpg',
  'SKU-SODA-001': '/static/sku/sprite.jpg',
  'SKU-WATER-001': '/static/sku/water.jpg',
  'SKU-SNACK-001': '/static/sku/chips.jpg',
  'SKU-MILK-001': '/static/sku/milk.jpg',
  'SKU-NOODLE-001': '/static/sku/noodle.jpg'
};

export function absoluteImageUrl(url?: string | null): string {
  const value = String(url || '').trim();
  if (!value) return '';
  if (/^https?:\/\//i.test(value) || value.startsWith('//')) return value;
  const base = (API_BASE_URL || '').replace(/\/$/, '');
  return `${base}${value.startsWith('/') ? value : '/' + value}`;
}

export function isPullOffType(type?: string) {
  const code = String(type || 'RESTOCK').toUpperCase();
  return code === 'PULL_OFF' || code === 'REMOVE' || code === 'PULL';
}

/**
 * 补货详情展示：SKU 名/图、货道余量、行摘要。
 */
export function useReplenishmentDisplay(opts: {
  skus: Ref<MerchantSkuPricing[]>;
  slotCaps: Ref<Record<string, { maxLevel: number; bookQty: number }>>;
  deviceSlotsList: Ref<DeviceSlot[]>;
}) {
  function skuName(id: string) {
    const s = opts.skus.value.find((item) => item.skuId === id);
    return s?.skuName || id;
  }

  function skuThumb(id: string) {
    const s = opts.skus.value.find((item) => item.skuId === id);
    return absoluteImageUrl(s?.imageUrl) || LOCAL_SKU_THUMBS[id] || '';
  }

  function productGlyph(id: string) {
    const name = String(skuName(id) || '').trim();
    return name ? name.slice(0, 1) : '货';
  }

  function slotOptionsFor(line: Line) {
    return opts.deviceSlotsList.value
      .filter((s) => s.enabled !== false)
      .filter((s) => !s.assignedSkuId || s.assignedSkuId === line.skuId)
      .map((s) => {
        const slotCode = String(s.slotCode || '').toUpperCase();
        const maxLevel = Number(s.maxLevel) || 0;
        const bookQty = Number(s.bookQty) || 0;
        const room = maxLevel > 0 ? Math.max(0, maxLevel - bookQty) : 99;
        return { slotCode, room, label: s.assignedSkuName || slotCode };
      })
      .filter((s) => !!s.slotCode)
      .sort((a, b) => b.room - a.room || a.slotCode.localeCompare(b.slotCode));
  }

  function slotHeadroom(line: Line): number {
    const code = String(line.slotId || '').toUpperCase();
    if (!code) {
      const rooms = slotOptionsFor(line)
        .map((o) => o.room)
        .filter((n) => n > 0);
      return rooms.length ? Math.max(...rooms) : 0;
    }
    const cap = opts.slotCaps.value[code];
    if (!cap || cap.maxLevel <= 0) return 99;
    return Math.max(0, cap.maxLevel - cap.bookQty);
  }

  function slotHint(line: Line): string {
    if (isPullOffType(line.lineType)) return '';
    const code = String(line.slotId || '').toUpperCase();
    const cap = opts.slotCaps.value[code];
    if (!cap || cap.maxLevel <= 0) return '';
    const room = slotHeadroom(line);
    if (room <= 0) return `货道已满（${cap.bookQty}/${cap.maxLevel}），请将数量调为 0 或换货道`;
    if ((Number(line.quantity) || 0) > room)
      return `超出容量：最多再补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
    return `还可补 ${room}（已有 ${cap.bookQty}/${cap.maxLevel}）`;
  }

  function formatLineSummary(rows: Line[]): string {
    if (!rows.length) return '暂无明细行';
    const qty = rows.reduce((s, l) => s + Math.max(0, Number(l.quantity) || 0), 0);
    const pull = rows.filter((l) => isPullOffType(l.lineType)).length;
    const restock = rows.length - pull;
    const noExpiry = rows.filter((l) => !String(l.expiryDate || '').trim()).length;
    const noSlot = rows.filter(
      (l) => !String(l.slotId || '').trim() && !isPullOffType(l.lineType)
    ).length;
    const parts = [`${rows.length} 行`, `共 ${qty} 件`];
    if (restock > 0) parts.push(`补货 ${restock}`);
    if (pull > 0) parts.push(`下架 ${pull}`);
    if (noSlot > 0) parts.push(`${noSlot} 行待选货道`);
    if (noExpiry > 0) parts.push(`${noExpiry} 行缺效期`);
    return parts.join(' · ');
  }

  function stockDeltaText(line: Line): string {
    const code = String(line.slotId || '').toUpperCase();
    if (!code) return '';
    const cap = opts.slotCaps.value[code];
    if (!cap) return '';
    const qty = Math.max(0, Number(line.quantity) || 0);
    if (isPullOffType(line.lineType)) {
      const after = Math.max(0, cap.bookQty - qty);
      const capacityHint = cap.maxLevel > 0 ? ` / 容量 ${cap.maxLevel}` : '';
      return `账面 ${cap.bookQty} → 下架后 ${after}${capacityHint}`;
    }
    const after = cap.bookQty + qty;
    const capacityHint = cap.maxLevel > 0 ? ` / 容量 ${cap.maxLevel}` : '';
    return `账面 ${cap.bookQty} → 补后 ${after}${capacityHint}`;
  }

  function lineTypeLabel(type?: string) {
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
    slotOptionsFor,
    slotHeadroom,
    slotHint,
    formatLineSummary,
    stockDeltaText,
    isPullOffType,
    lineTypeLabel,
    lineStatusLabel,
    displayTaskNotes,
    taskActionLabel
  };
}
