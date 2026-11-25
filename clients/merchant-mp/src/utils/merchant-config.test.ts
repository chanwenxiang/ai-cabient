import { computed } from 'vue';
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

  it('下发订阅模板 ID 后可读取；缺省为空串', async () => {
    const empty = await fresh();
    expect(empty.merchantSubscribeTemplateId()).toBe('');
    merchantPublicConfig.mockResolvedValue({
      wechatSubscribeTemplateId: ' tpl-merchant '
    });
    const m = await fresh();
    await m.loadMerchantFlags();
    expect(m.merchantSubscribeTemplateId()).toBe('tpl-merchant');
  });

  it('wechatSubscribeEnabled 按值判定，缺省关', async () => {
    const empty = await fresh();
    expect(empty.merchantSubscribeEnabled()).toBe(false);
    merchantPublicConfig.mockResolvedValue({ wechatSubscribeEnabled: 'true' });
    const m = await fresh();
    await m.loadMerchantFlags();
    expect(m.merchantSubscribeEnabled()).toBe(true);
  });

  it('提现下限从公开配置读取；缺失或非法时返回 null，0 表示不限制', async () => {
    const empty = await fresh();
    expect(empty.merchantWithdrawMinCents()).toBeNull();
    expect(empty.lineWithdrawMinCents()).toBeNull();

    merchantPublicConfig.mockResolvedValue({
      merchantWithdrawMinCents: ' 10 ',
      lineWithdrawMinCents: '0'
    });
    const configured = await fresh();
    await configured.loadMerchantFlags();
    expect(configured.merchantWithdrawMinCents()).toBe(10);
    expect(configured.lineWithdrawMinCents()).toBe(0);

    merchantPublicConfig.mockResolvedValue({
      merchantWithdrawMinCents: 'abc',
      lineWithdrawMinCents: '-1'
    });
    const invalid = await fresh();
    await invalid.loadMerchantFlags();
    expect(invalid.merchantWithdrawMinCents()).toBeNull();
    expect(invalid.lineWithdrawMinCents()).toBeNull();
  });

  it('异步配置与 refresh 会触发提现下限 computed 更新', async () => {
    let resolveConfig!: (value: Record<string, string>) => void;
    merchantPublicConfig.mockReturnValue(
      new Promise((resolve) => {
        resolveConfig = resolve;
      })
    );
    const m = await fresh();
    const min = computed(() => m.merchantWithdrawMinCents());
    expect(min.value).toBeNull();
    const loading = m.loadMerchantFlags();
    resolveConfig({ merchantWithdrawMinCents: '100' });
    await loading;
    expect(min.value).toBe(100);

    merchantPublicConfig.mockResolvedValue({ merchantWithdrawMinCents: '10' });
    await m.loadMerchantFlags({ refresh: true });
    expect(min.value).toBe(10);
  });

  it('refresh=true 会重新请求公开配置', async () => {
    merchantPublicConfig
      .mockResolvedValueOnce({ wechatSubscribeTemplateId: '' })
      .mockResolvedValueOnce({ wechatSubscribeTemplateId: 'tpl-2' });
    const m = await fresh();
    await m.loadMerchantFlags();
    expect(m.merchantSubscribeTemplateId()).toBe('');
    await m.loadMerchantFlags({ refresh: true });
    expect(merchantPublicConfig).toHaveBeenCalledTimes(2);
    expect(m.merchantSubscribeTemplateId()).toBe('tpl-2');
  });
});
