import { describe, expect, it } from 'vitest';
import { buildSidebarTree, sidebarOpenKeysForPath } from '@/config/sidebar';

describe('warehouse sidebar navigation', () => {
  it('keeps the warehouse group and all page links in the left sidebar', () => {
    const tree = buildSidebarTree(() => true);
    const warehouse = tree.find((group) => group.key === 'warehouse');

    expect(warehouse?.label).toBe('仓储');
    expect(warehouse?.children?.map((leaf) => leaf.path)).toEqual(
      expect.arrayContaining([
        '/warehouse/master',
        '/warehouse/procurement',
        '/warehouse/claims',
        '/warehouse/expiry-alerts',
        '/warehouse/monthly-close-sheets',
        '/warehouse/stock',
        '/warehouse/reconciliation',
        '/warehouse/fulfillment'
      ])
    );
  });

  it('opens the warehouse group for warehouse routes', () => {
    expect(sidebarOpenKeysForPath('/warehouse/master')).toEqual(['warehouse']);
    expect(sidebarOpenKeysForPath('/warehouse/procurement')).toEqual(['warehouse']);
    expect(sidebarOpenKeysForPath('/dashboard')).toEqual(['overview']);
  });
});
