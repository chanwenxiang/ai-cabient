import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * 商户端扩展功能开关读取器（`@/utils/merchant-config`）的行为判据。
 *
 * 🔴 与 `consumer-mp/src/utils/feature-flags.test.ts` 是**对称的两份**：`enabled()` 与
 * `loadMerchantFlags()` 在两端是复制体，最容易被单侧改动改漂。`check-feature-flags.mjs`
 * 的 R2「有没有消费者」只扫 Java、**不覆盖前端**，所以「下发了但前端没读」这种半截可配置
 * 只能靠这一组用例兜住。
 */
const { merchantPublicConfig } = vi.hoisted(() => ({ merchantPublicConfig: vi.fn() }));

vi.mock('./merchant-api', () => ({
  merchantApi: { merchantPublicConfig }
}));

/** 重新加载模块，避免模块级 cache 在用例之间串味。 */
async function fresh() {
  vi.resetModules();
  return await import('./merchant-config');
}

beforeEach(() => {
  merchantPublicConfig.mockReset();
});

describe('商户端扩展功能开关读取器', () => {
  it('缓存未热时一律按「关」——不因为拿不到配置就把图表放出来', async () => {
    const m = await fresh();
    expect(m.merchantChartsEnabled()).toBe(false);
  });

  it('按值判定：true / "true" / "1" 为开，其余（含 "false" / 空串 / 任意串）为关', async () => {
    for (const [raw, want] of [
      ['true', true],
      ['1', true],
      [true, true],
      ['false', false],
      ['', false],
      ['yes', false],
      ['TRUE', false],
      ['0', false]
    ] as const) {
      const m = await fresh();
      merchantPublicConfig.mockResolvedValue({ chartsEnabled: raw });
      await m.loadMerchantFlags();
      expect(m.merchantChartsEnabled(), `raw=${JSON.stringify(raw)}`).toBe(want);
    }
  });

  it('loadMerchantFlags 请求失败 ⇒ 缓存空表、图表开关为关（fail-closed，且不抛穿）', async () => {
    merchantPublicConfig.mockRejectedValue(new Error('network down'));
    const m = await fresh();
    await expect(m.loadMerchantFlags()).resolves.toEqual({});
    expect(m.merchantChartsEnabled()).toBe(false);
  });

  it('并发去重：同时调用只发一次请求，且都拿到同一份配置', async () => {
    merchantPublicConfig.mockResolvedValue({ chartsEnabled: 'true' });
    const m = await fresh();
    const [a, b] = await Promise.all([m.loadMerchantFlags(), m.loadMerchantFlags()]);
    expect(merchantPublicConfig).toHaveBeenCalledTimes(1);
    expect(a).toEqual(b);
  });

  it('成功后再次调用不再发请求（进程内一次）', async () => {
    merchantPublicConfig.mockResolvedValue({ chartsEnabled: 'true' });
    const m = await fresh();
    await m.loadMerchantFlags();
    await m.loadMerchantFlags();
    expect(merchantPublicConfig).toHaveBeenCalledTimes(1);
    expect(m.merchantChartsEnabled()).toBe(true);
  });
});
