import { describe, expect, it } from 'vitest';
import { jiangyiStateText } from '@aicabinet/shared-uni/format';

/**
 * 将邑商品状态文案（2026-11-10）：厂商状态（七态表）映射中文；
 * 表外状态不露裸英文码——UNKNOWN 显示「未知状态」，其它新值显示「未知状态（原始值）」。
 */
describe('jiangyiStateText', () => {
  it('七态表映射中文', () => {
    expect(jiangyiStateText('CABINET_REVIEWING')).toBe('柜内图审核中');
    expect(jiangyiStateText('CABINET_PENDING')).toBe('可补货/待柜内采集');
    expect(jiangyiStateText('READY')).toBe('可补货');
  });

  it('UNKNOWN 显示未知状态，不露英文码', () => {
    expect(jiangyiStateText('UNKNOWN')).toBe('未知状态');
  });

  it('厂商新增状态显示未知状态（原始值）', () => {
    expect(jiangyiStateText('UPLOADING')).toBe('未知状态（UPLOADING）');
    expect(jiangyiStateText(' cabinet_reviewed ')).toBe('未知状态（cabinet_reviewed）');
  });

  it('空值显示破折号', () => {
    expect(jiangyiStateText('')).toBe('—');
    expect(jiangyiStateText(null)).toBe('—');
    expect(jiangyiStateText(undefined)).toBe('—');
  });
});
