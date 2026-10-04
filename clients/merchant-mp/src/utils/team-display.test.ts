import { describe, expect, it } from 'vitest';
import { isInternalRoleKey, teamMemberTitle, teamRoleLabel, teamStatusLabel } from './team-display';

describe('team-display', () => {
  it('never surfaces English role keys', () => {
    expect(teamRoleLabel('merchant', 'merchant')).toBe('商户管理员');
    expect(teamRoleLabel('merchant_store_manager')).toBe('店长');
    expect(teamRoleLabel('merchant_finance')).toBe('财务');
    expect(teamRoleLabel('merchant_staff', 'merchant_staff')).toBe('店员');
    expect(teamRoleLabel('unknown_role_xyz')).toBe('成员');
    expect(isInternalRoleKey('merchant_store_manager')).toBe(true);
  });

  it('keeps Chinese role names from catalog', () => {
    expect(teamRoleLabel('merchant_staff', '店员')).toBe('店员');
  });

  it('maps status and titles in Chinese', () => {
    expect(teamStatusLabel('INACTIVE')).toBe('已停用');
    expect(teamStatusLabel('ACTIVE')).toBe('启用中');
    expect(teamMemberTitle('运营超管', '13900000001')).toBe('运营超管');
    expect(teamMemberTitle('', '13800138001')).toBe('13800138001');
  });
});
