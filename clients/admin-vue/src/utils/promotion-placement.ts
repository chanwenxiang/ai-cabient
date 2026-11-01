/**
 * 活动展示位（CB-082）图片尺寸口径 —— 与后端 `PromotionPlacementProperties` 默认值保持一致。
 * 前端只做即时提示，最终以服务端校验为准（后端读素材真实像素，不信客户端）。
 */
export type PlacementPosition = 'BANNER' | 'POPUP_BEFORE_OPEN' | 'POPUP_AFTER_CLOSE';

export interface PlacementRule {
  minWidth: number;
  maxWidth: number;
  minHeight: number;
  maxHeight: number;
  minAspect: number;
  maxAspect: number;
  hint: string;
}

export const PLACEMENT_RULES: Record<'BANNER' | 'POPUP', PlacementRule> = {
  BANNER: {
    minWidth: 600,
    maxWidth: 2000,
    minHeight: 200,
    maxHeight: 800,
    minAspect: 1.8,
    maxAspect: 3.5,
    hint: '建议 750×300（宽高比 2.5:1），允许宽 600-2000、高 200-800'
  },
  POPUP: {
    minWidth: 600,
    maxWidth: 1500,
    minHeight: 800,
    maxHeight: 2000,
    minAspect: 0.5,
    maxAspect: 0.9,
    hint: '建议 750×1000（宽高比 3:4），允许宽 600-1500、高 800-2000'
  }
};

export const POPUP_POSITIONS: PlacementPosition[] = ['POPUP_BEFORE_OPEN', 'POPUP_AFTER_CLOSE'];

export function ruleOf(position: PlacementPosition): PlacementRule {
  return position === 'BANNER' ? PLACEMENT_RULES.BANNER : PLACEMENT_RULES.POPUP;
}

export function isPopup(position: string): boolean {
  return position === 'POPUP_BEFORE_OPEN' || position === 'POPUP_AFTER_CLOSE';
}

/** @returns 错误文案；通过返回 null。 */
export function checkImageSize(
  position: PlacementPosition,
  width?: number | null,
  height?: number | null
): string | null {
  if (!width || !height) {
    return '该图片未记录像素尺寸，请重新上传后再选择';
  }
  const rule = ruleOf(position);
  if (
    width < rule.minWidth ||
    width > rule.maxWidth ||
    height < rule.minHeight ||
    height > rule.maxHeight
  ) {
    return `图片尺寸不合适：${width}×${height}px，需 ${rule.hint}`;
  }
  const aspect = width / height;
  if (aspect < rule.minAspect || aspect > rule.maxAspect) {
    return `图片宽高比不合适：${aspect.toFixed(2)}，需 ${rule.hint}`;
  }
  return null;
}
