/**
 * 开门柜二维码解析 — 移植自旧原生小程序实现
 */

const DEVICE_ID_RE = /^[A-Z0-9][A-Z0-9_-]{1,63}$/;

export function normalizeDeviceId(raw?: string | null): string {
  if (!raw) return '';
  const id = String(raw).trim().toUpperCase();
  return DEVICE_ID_RE.test(id) ? id : '';
}

function parseQueryString(query: string): Record<string, string> {
  const out: Record<string, string> = {};
  if (!query) return out;
  const q = query.startsWith('?') ? query.slice(1) : query;
  q.split(/[&;]/).forEach((pair) => {
    if (!pair) return;
    const idx = pair.indexOf('=');
    if (idx === -1) {
      out[decodeURIComponent(pair)] = '';
      return;
    }
    out[decodeURIComponent(pair.slice(0, idx))] = decodeURIComponent(pair.slice(idx + 1));
  });
  return out;
}

function pickDeviceIdFromParams(params: Record<string, string | undefined>): string {
  const keys = ['deviceId', 'device_id', 'd', 'cabinetId', 'cabinet_id', 'id', 'sn'];
  for (const k of keys) {
    const v = normalizeDeviceId(params[k]);
    if (v) return v;
  }
  return '';
}

function tryParseJson(text: string): string {
  try {
    const obj = JSON.parse(text) as Record<string, string>;
    if (obj && typeof obj === 'object') return pickDeviceIdFromParams(obj);
  } catch {
    /* not json */
  }
  return '';
}

function extractFromUrl(raw: string): string {
  try {
    const url = new URL(raw);
    const fromQuery = pickDeviceIdFromParams(parseQueryString(url.search));
    if (fromQuery) return fromQuery;
    const parts = url.pathname.split('/').filter(Boolean);
    for (let i = parts.length - 1; i >= 0; i--) {
      const id = normalizeDeviceId(parts[i]);
      if (id) return id;
    }
    if (url.hash?.includes('=')) {
      const fromHash = pickDeviceIdFromParams(parseQueryString(url.hash.replace(/^#/, '')));
      if (fromHash) return fromHash;
    }
  } catch {
    /* not a url */
  }
  return '';
}

export interface ScanResult {
  deviceId: string;
  channel: string;
  autoOpen: boolean;
  alipayOnly: boolean;
  raw: string;
}

export function parseCabinetScan(raw?: string | null): ScanResult {
  const text = (raw || '').trim();
  if (!text) {
    return { deviceId: '', channel: 'UNKNOWN', autoOpen: false, alipayOnly: false, raw: text };
  }

  let deviceId = normalizeDeviceId(text);
  if (deviceId) return { deviceId, channel: 'PLAIN', autoOpen: true, alipayOnly: false, raw: text };

  deviceId = tryParseJson(text);
  if (deviceId) return { deviceId, channel: 'JSON', autoOpen: true, alipayOnly: false, raw: text };

  const lower = text.toLowerCase();
  if (/(weixin:\/\/|wxp:\/\/|servicewechat\.com)/.test(lower)) {
    const queryMatch = text.match(/[?&]query=([^&]+)/i);
    if (queryMatch) {
      deviceId = pickDeviceIdFromParams(parseQueryString(decodeURIComponent(queryMatch[1])));
    }
    if (!deviceId) deviceId = extractFromUrl(text);
    if (deviceId) return { deviceId, channel: 'WECHAT', autoOpen: true, alipayOnly: false, raw: text };
  }

  const alipayOnly = /alipays:\/\/|platformapi\/startapp|ds\.alipay\.com/.test(lower);
  if (lower.includes('alipay')) {
    let alipayDeviceId = '';
    const queryMatch = text.match(/[?&]query=([^&]+)/i);
    if (queryMatch) {
      alipayDeviceId = pickDeviceIdFromParams(parseQueryString(decodeURIComponent(queryMatch[1])));
    }
    if (!alipayDeviceId) alipayDeviceId = extractFromUrl(text) || normalizeDeviceId(text);
    if (alipayOnly && !alipayDeviceId) {
      return { deviceId: '', channel: 'ALIPAY', autoOpen: false, alipayOnly: true, raw: text };
    }
    if (alipayDeviceId) {
      return { deviceId: alipayDeviceId, channel: 'ALIPAY', autoOpen: true, alipayOnly: false, raw: text };
    }
  }

  deviceId = extractFromUrl(text);
  if (deviceId) {
    const channel = /alipay/i.test(text) ? 'ALIPAY' : /weixin|wx/i.test(text) ? 'WECHAT' : 'URL';
    return { deviceId, channel, autoOpen: true, alipayOnly: false, raw: text };
  }

  const sceneParams = parseQueryString(text.includes('=') ? text : '');
  deviceId = pickDeviceIdFromParams(sceneParams);
  if (deviceId) {
    return {
      deviceId,
      channel: 'SCENE',
      autoOpen: sceneParams.autoOpen === '1' || sceneParams.open === '1',
      alipayOnly: false,
      raw: text
    };
  }

  return { deviceId: '', channel: 'UNKNOWN', autoOpen: false, alipayOnly: false, raw: text };
}

export function parseLaunchOptions(options: Record<string, string | undefined> = {}) {
  let deviceId = options.deviceId || options.d || options.device_id || '';
  let autoOpen = options.autoOpen === '1' || options.open === '1';
  if (!deviceId && options.q) {
    const parsed = parseCabinetScan(decodeURIComponent(options.q));
    deviceId = parsed.deviceId;
    autoOpen = autoOpen || parsed.autoOpen;
  }
  if (!deviceId && options.scene) {
    try {
      const scene = decodeURIComponent(options.scene);
      const parsed = parseCabinetScan(scene);
      deviceId = parsed.deviceId || normalizeDeviceId(scene);
      autoOpen = autoOpen || parsed.autoOpen;
    } catch {
      /* ignore */
    }
  }
  return { deviceId: normalizeDeviceId(deviceId), autoOpen };
}
