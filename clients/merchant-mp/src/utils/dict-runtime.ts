import { loadRuntimeDict as sharedLoadRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
import { ensureSessionFresh, getToken, request } from '@/utils/merchant-api';
import { MerchantEndpoints } from '@/api/endpoints';

/**
 * 运行时字典：本函数是 **App.onLaunch 里唯一「启动即发」的带鉴权请求**，因此也是
 * 「过期 token 的第一个牺牲品」——token 过了 30 分钟有效期后冷启动，这里必先吃一个 401
 * （2026-11-11 实证：网关 `401 81B` 紧跟 `200 91KB`，控制台表现为一条红色 401）。
 *
 * 先 `ensureSessionFresh()`（临期才换票，非临期零成本直连）再发请求：
 * 开发者工具对 4xx 的原生红字无法由 JS 抑制，只能让这次请求从一开始就带上有效票据。
 * 续期失败不阻断——退回 `loadRuntimeDict` 内部的 401 自愈与编译期 DICT 兜底。
 */
export async function loadRuntimeDict() {
  await ensureSessionFresh();
  return sharedLoadRuntimeDict({
    getToken,
    // M9/M3 复审：此前为裸路径字面量，收口到 MerchantEndpoints
    fetchRuntime: () => request(MerchantEndpoints.dictsRuntime, 'GET')
  });
}

export { resetRuntimeDict } from '@aicabinet/shared-uni/dict-runtime';
