import { merchantApi } from './merchant-api';

/**
 * 商户端「扩展功能」开关的进程内缓存（对称于 C 端的 `feature-flags.ts`）。
 *
 * 来源：后端公开配置接口（`MerchantEndpoints.publicMerchantConfig`，`SystemConfigService#merchantPublicConfig`）。
 *
 * 🔴 fail-closed：配置没取到（网络失败 / 字段缺失 / 值不是 true|1）时一律按「关」处理
 * ⇒ 图表不显示，页面与接入前完全一致。绝不因为「拿不到配置」而把新功能放出来。
 */
let cache: Record<string, string> | null = null;
let inflight: Promise<Record<string, string>> | null = null;

function enabled(raw: unknown): boolean {
  return raw === true || raw === 'true' || raw === '1';
}

/** 拉取并缓存公开配置。失败时缓存空表 ⇒ 所有开关按「关」。 */
export async function loadMerchantFlags(opts?: {
  refresh?: boolean;
}): Promise<Record<string, string>> {
  if (opts?.refresh) {
    cache = null;
    inflight = null;
  }
  if (cache) return cache;
  if (inflight) return inflight;
  inflight = merchantApi
    .merchantPublicConfig()
    .then((cfg) => {
      cache = cfg || {};
      return cache;
    })
    .catch(() => {
      cache = {};
      return cache;
    })
    .finally(() => {
      inflight = null;
    });
  return inflight;
}

/** 商户端经营分析图表（`merchant.charts.enabled`）。 */
export function merchantChartsEnabled(): boolean {
  return enabled(cache?.chartsEnabled);
}

/** 商户订阅消息模板 ID（公开配置下发，空串表示未配）。 */
export function merchantSubscribeTemplateId(): string {
  const raw = cache?.wechatSubscribeTemplateId;
  return typeof raw === 'string' ? raw.trim() : '';
}

/** 后端是否已配齐小程序凭据 + 商户订阅模板（能真正下发推送）。 */
export function merchantSubscribeEnabled(): boolean {
  return enabled(cache?.wechatSubscribeEnabled);
}

/** CB-018④：商户端客服热线（`merchant.service_phone`；空串=未配置，前端不渲染入口）。 */
export function merchantServicePhone(): string {
  const raw = cache?.servicePhone;
  return typeof raw === 'string' ? raw.trim() : '';
}

/** CB-018④：运营支持邮箱（`ops.support_email` 下发；空串=未配置）。 */
export function merchantSupportEmail(): string {
  const raw = cache?.supportEmail;
  return typeof raw === 'string' ? raw.trim() : '';
}
