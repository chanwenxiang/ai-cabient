import { describe, expect, it } from 'vitest';
import { checkImageSize } from './promotion-placement';

describe('checkImageSize / 活动展示位尺寸', () => {
  it('750×300 通过轮播横幅校验', () => {
    expect(checkImageSize('BANNER', 750, 300)).toBeNull();
  });

  it('正方形不适合轮播横幅（宽高比过小）', () => {
    expect(checkImageSize('BANNER', 750, 750)).toContain('宽高比');
  });

  it('750×1000 通过弹窗校验', () => {
    expect(checkImageSize('POPUP_BEFORE_OPEN', 750, 1000)).toBeNull();
  });

  it('横幅图不能当弹窗（方向反了）', () => {
    expect(checkImageSize('POPUP_AFTER_CLOSE', 750, 300)).not.toBeNull();
  });

  it('缺少尺寸时要求重新上传', () => {
    expect(checkImageSize('BANNER', null, null)).toContain('重新上传');
  });

  it('超宽被拒绝', () => {
    expect(checkImageSize('BANNER', 4000, 1000)).not.toBeNull();
  });
});
