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
    expect(m.productDetailEnabled()).toBe(false);
    // 广告位尤其要守住：误判成「开」会让首页在没有投放时也挂出占位图
    expect(m.adBannerEnabled()).toBe(false);
    // 腾讯广告位同理：拿不到配置 ⇒ 开关按「关」，且 ID 视为空（两者共同决定「可渲染」）
    expect(m.wxAdEnabled()).toBe(false);
    expect(m.wxAdUnitId()).toBe('');
  });

  it('seed 后按值判定：true / "true" / "1" 为开，其余（含 "false" / 空串 / 任意串）为关', async () => {
    const on = await fresh();
    on.seedConsumerFlags({
      couponEntryEnabled: 'true',
      orderSearchEnabled: '1',
      productDetailEnabled: 'true',
      adBannerEnabled: 'true'
    });
    expect(on.couponEntryEnabled()).toBe(true);
    expect(on.orderSearchEnabled()).toBe(true);
    expect(on.productDetailEnabled()).toBe(true);
    expect(on.adBannerEnabled()).toBe(true);

    // 越界输入（JSON 布尔）也应判为开：`enabled()` 收 unknown，容忍直接下发布尔值。
    // 契约上服务端是 String.valueOf(...) ⇒ 恒字符串；这里**刻意越过类型边界**验证容错，
    // 免得将来换成 JSON 布尔时开关静默失效。
    const onBool = await fresh();
    onBool.seedConsumerFlags({ couponEntryEnabled: true } as unknown as Record<string, string>);
    expect(onBool.couponEntryEnabled()).toBe(true);

    for (const raw of ['false', '', 'yes', 'TRUE', '0']) {
      const m = await fresh();
      m.seedConsumerFlags({
        couponEntryEnabled: raw,
        productDetailEnabled: raw,
        adBannerEnabled: raw
      });
      expect(m.couponEntryEnabled(), `raw=${JSON.stringify(raw)} 必须判为关`).toBe(false);
      expect(m.productDetailEnabled(), `raw=${JSON.stringify(raw)} 必须判为关`).toBe(false);
      expect(m.adBannerEnabled(), `raw=${JSON.stringify(raw)} 必须判为关`).toBe(false);
    }
  });

  it('🔴 各开关互相独立 —— 广告位不得读成别的键（复制粘贴最容易改错键名）', async () => {
    const m = await fresh();
    m.seedConsumerFlags({
      adBannerEnabled: 'true',
      orderSearchEnabled: 'false',
      couponEntryEnabled: 'false',
      productDetailEnabled: 'false'
    });
    expect(m.adBannerEnabled()).toBe(true);
    expect(m.orderSearchEnabled()).toBe(false);
    expect(m.couponEntryEnabled()).toBe(false);
    expect(m.productDetailEnabled()).toBe(false);
  });

  it('🔴 seed(null/undefined)（网络失败）不得覆盖已有缓存 —— 一次失败不能把开关永久钉死在关', async () => {
    const m = await fresh();
    m.seedConsumerFlags({
      couponEntryEnabled: 'true',
      productDetailEnabled: 'true',
      adBannerEnabled: 'true'
    });
    m.seedConsumerFlags(null);
    m.seedConsumerFlags(undefined);
    expect(m.couponEntryEnabled()).toBe(true);
    expect(m.productDetailEnabled()).toBe(true);
    expect(m.adBannerEnabled()).toBe(true);
  });

  it('loadConsumerFlags 请求失败 ⇒ 缓存空表、开关全关（fail-closed，且不抛穿）', async () => {
    consumerPublicConfig.mockRejectedValue(new Error('network down'));
    const m = await fresh();
    await expect(m.loadConsumerFlags()).resolves.toEqual({});
    expect(m.couponEntryEnabled()).toBe(false);
    expect(m.orderSearchEnabled()).toBe(false);
    expect(m.productDetailEnabled()).toBe(false);
    expect(m.adBannerEnabled()).toBe(false);
  });

  it('loadConsumerFlags 成功 ⇒ 下发键进缓存，开关随值打开', async () => {
    consumerPublicConfig.mockResolvedValue({
      orderSearchEnabled: 'true',
      couponEntryEnabled: '1',
      productDetailEnabled: '1',
      adBannerEnabled: '1'
    });
    const m = await fresh();
    await m.loadConsumerFlags();
    expect(m.orderSearchEnabled()).toBe(true);
    expect(m.couponEntryEnabled()).toBe(true);
    expect(m.productDetailEnabled()).toBe(true);
    expect(m.adBannerEnabled()).toBe(true);
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

/**
 * 腾讯流量主广告位（`consumer.wx_ad.*`，2026-09-20 第二十八轮）。
 *
 * 我们是**流量主**（收腾讯分成），广告由微信广告平台投放；这两条守住它的两道闸：
 * 开关默认关、广告单元 ID 空 ⇒ 不渲染。
 */
describe('腾讯流量主广告位读取器', () => {
  it('fail-closed：缓存未热 ⇒ 开关关、ID 空', async () => {
    const m = await fresh();
    expect(m.wxAdEnabled()).toBe(false);
    expect(m.wxAdUnitId()).toBe('');
  });

  it('ID 只做 trim，**不做布尔化** —— "0" / "false" 这类值必须原样返回', async () => {
    const m = await fresh();
    m.seedConsumerFlags({ wxAdUnitId: '  adunit-xyz  ' });
    expect(m.wxAdUnitId()).toBe('adunit-xyz');

    // 🔴 这条是刻意的：unit id 是**值**不是开关。若图省事复用 enabled() 那套判定，
    // 含 "0"/"false" 的 ID 会被判成「假」而返回空串 ⇒ 广告静默不渲染（且很难查）。
    const zero = await fresh();
    zero.seedConsumerFlags({ wxAdUnitId: '0' });
    expect(zero.wxAdUnitId()).toBe('0');

    const falsy = await fresh();
    falsy.seedConsumerFlags({ wxAdUnitId: 'false' });
    expect(falsy.wxAdUnitId()).toBe('false');
  });

  it('ID 非字符串 ⇒ 收敛成空串，不把 "null"/"123" 喂给广告组件', async () => {
    const nul = await fresh();
    nul.seedConsumerFlags({ wxAdUnitId: null as unknown as string });
    expect(nul.wxAdUnitId()).toBe('');

    const num = await fresh();
    num.seedConsumerFlags({ wxAdUnitId: 123 as unknown as string });
    expect(num.wxAdUnitId()).toBe('');
  });

  it('🔴 开关与 ID 互相独立：开关开但 ID 为空 ⇒ 仍是「不可渲染」', async () => {
    const m = await fresh();
    m.seedConsumerFlags({ wxAdEnabled: 'true' });
    expect(m.wxAdEnabled()).toBe(true);
    expect(m.wxAdUnitId()).toBe('');
  });

  it('🔴 wxAdEnabled 不得读成 adBannerEnabled（同族键名最易复制错）', async () => {
    const m = await fresh();
    m.seedConsumerFlags({ adBannerEnabled: 'true', wxAdEnabled: 'false' });
    expect(m.wxAdEnabled()).toBe(false);
    expect(m.adBannerEnabled()).toBe(true);
  });
});

/**
 * 结算页支付方式选择（`consumer.pay_channel_select.enabled`，F6，2026-09-21）。
 *
 * 这个键决定「结算那一刻要不要问用户用哪个渠道扣钱」，默认必须是**关**：
 * 关掉时订单详情的「去支付」不得多发任何请求、也不得弹出选择。
 */
describe('结算页渠道选择开关读取器', () => {
  it('fail-closed：缓存未热 ⇒ 关（拿不到配置不把新交互放出来）', async () => {
    const m = await fresh();
    expect(m.payChannelSelectEnabled()).toBe(false);
  });

  it('值判定与其它开关一致：true / "1" 为开，"false" / 空串为关', async () => {
    const on = await fresh();
    on.seedConsumerFlags({ payChannelSelectEnabled: 'true' });
    expect(on.payChannelSelectEnabled()).toBe(true);

    const one = await fresh();
    one.seedConsumerFlags({ payChannelSelectEnabled: '1' });
    expect(one.payChannelSelectEnabled()).toBe(true);

    for (const raw of ['false', '', 'yes']) {
      const m = await fresh();
      m.seedConsumerFlags({ payChannelSelectEnabled: raw });
      expect(m.payChannelSelectEnabled(), `raw=${JSON.stringify(raw)} 必须判为关`).toBe(false);
    }
  });

  it('🔴 不得读成 productDetailEnabled（同族 consumer.* 键名最易复制错）', async () => {
    const m = await fresh();
    m.seedConsumerFlags({ productDetailEnabled: 'true', payChannelSelectEnabled: 'false' });
    expect(m.payChannelSelectEnabled()).toBe(false);
    expect(m.productDetailEnabled()).toBe(true);
  });
});
