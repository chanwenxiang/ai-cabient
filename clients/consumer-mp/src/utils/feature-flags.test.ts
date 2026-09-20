import { beforeEach, describe, expect, it, vi } from 'vitest';

/**
 * C 端扩展功能开关读取器（`@/utils/feature-flags`）的行为判据。
 *
 * 🔴 这一组用例存在的理由：`check-feature-flags.mjs` 的 R2「有没有消费者」**只扫
 * services/**\/src/main/java**，前端消费根本不在它的扫描范围里。也就是说「后端下发了开关、
 * 前端从没读过那个键」这种**半截可配置**，门禁抓不到（2026-09-20 实测踩到：couponEntryEnabled
 * 已下发而 index.vue 零消费）。前端消费的判据只能落在这里。
 *
 * 覆盖三件事：① fail-closed（拿不到配置 = 关）；② 值判定的边界；③ 失败不得覆盖已有缓存。
 */
const { consumerPublicConfig } = vi.hoisted(() => ({ consumerPublicConfig: vi.fn() }));

vi.mock('./consumer-api', () => ({
  consumerApi: { consumerPublicConfig }
}));

/** 重新加载模块，避免模块级 cache 在用例之间串味。 */
async function fresh() {
  vi.resetModules();
  return await import('./feature-flags');
}

beforeEach(() => {
  consumerPublicConfig.mockReset();
});

describe('扩展功能开关读取器', () => {
  it('缓存未热时一律按「关」——不因为拿不到配置就把新功能放出来', async () => {
    const m = await fresh();
    expect(m.orderSearchEnabled()).toBe(false);
    expect(m.couponEntryEnabled()).toBe(false);
  });

  it('seed 后按值判定：true / "true" / "1" 为开，其余（含 "false" / 空串 / 任意串）为关', async () => {
    const on = await fresh();
    on.seedConsumerFlags({ couponEntryEnabled: 'true', orderSearchEnabled: '1' });
    expect(on.couponEntryEnabled()).toBe(true);
    expect(on.orderSearchEnabled()).toBe(true);

    // 越界输入（JSON 布尔）也应判为开：`enabled()` 收 unknown，容忍直接下发布尔值。
    // 契约上服务端是 String.valueOf(...) ⇒ 恒字符串；这里**刻意越过类型边界**验证容错，
    // 免得将来换成 JSON 布尔时开关静默失效。
    const onBool = await fresh();
    onBool.seedConsumerFlags({ couponEntryEnabled: true } as unknown as Record<string, string>);
    expect(onBool.couponEntryEnabled()).toBe(true);

    for (const raw of ['false', '', 'yes', 'TRUE', '0']) {
      const m = await fresh();
      m.seedConsumerFlags({ couponEntryEnabled: raw });
      expect(m.couponEntryEnabled(), `raw=${JSON.stringify(raw)} 必须判为关`).toBe(false);
    }
  });

  it('🔴 seed(null/undefined)（网络失败）不得覆盖已有缓存 —— 一次失败不能把开关永久钉死在关', async () => {
    const m = await fresh();
    m.seedConsumerFlags({ couponEntryEnabled: 'true' });
    m.seedConsumerFlags(null);
    m.seedConsumerFlags(undefined);
    expect(m.couponEntryEnabled()).toBe(true);
  });

  it('loadConsumerFlags 请求失败 ⇒ 缓存空表、开关全关（fail-closed，且不抛穿）', async () => {
    consumerPublicConfig.mockRejectedValue(new Error('network down'));
    const m = await fresh();
    await expect(m.loadConsumerFlags()).resolves.toEqual({});
    expect(m.couponEntryEnabled()).toBe(false);
    expect(m.orderSearchEnabled()).toBe(false);
  });

  it('loadConsumerFlags 成功 ⇒ 下发键进缓存，开关随值打开', async () => {
    consumerPublicConfig.mockResolvedValue({
      orderSearchEnabled: 'true',
      couponEntryEnabled: '1'
    });
    const m = await fresh();
    await m.loadConsumerFlags();
    expect(m.orderSearchEnabled()).toBe(true);
    expect(m.couponEntryEnabled()).toBe(true);
  });

  it('loadConsumerFlags 并发去重：同时调用只发一次请求，且都拿到同一份配置', async () => {
    consumerPublicConfig.mockResolvedValue({ couponEntryEnabled: 'true' });
    const m = await fresh();
    const [a, b] = await Promise.all([m.loadConsumerFlags(), m.loadConsumerFlags()]);
    expect(consumerPublicConfig).toHaveBeenCalledTimes(1);
    expect(a).toEqual(b);
  });

  it('loadConsumerFlags 成功后再次调用不再发请求（进程内一次）', async () => {
    consumerPublicConfig.mockResolvedValue({ couponEntryEnabled: 'true' });
    const m = await fresh();
    await m.loadConsumerFlags();
    await m.loadConsumerFlags();
    expect(consumerPublicConfig).toHaveBeenCalledTimes(1);
  });
});
