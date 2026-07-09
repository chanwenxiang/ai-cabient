/**
 * 开门柜二维码解析 — 兼容市面常见的微信 / 支付宝 / 纯文本 / H5 链接格式
 */

const DEVICE_ID_RE = /^[A-Z0-9][A-Z0-9_-]{1,63}$/;

function normalizeDeviceId(raw) {
  if (!raw) return '';
  const id = String(raw).trim().toUpperCase();
  if (DEVICE_ID_RE.test(id)) return id;
  return '';
}

function parseQueryString(query) {
  const out = {};
  if (!query) return out;
  const q = query.startsWith('?') ? query.slice(1) : query;
  q.split(/[&;]/).forEach((pair) => {
    if (!pair) return;
    const idx = pair.indexOf('=');
    if (idx === -1) {
      out[decodeURIComponent(pair)] = '';
      return;
    }
    const k = decodeURIComponent(pair.slice(0, idx));
    const v = decodeURIComponent(pair.slice(idx + 1));
    out[k] = v;
  });
  return out;
}

function pickDeviceIdFromParams(params) {
  if (!params) return '';
  const keys = ['deviceId', 'device_id', 'd', 'cabinetId', 'cabinet_id', 'id', 'sn'];
  for (const k of keys) {
    const v = normalizeDeviceId(params[k]);
    if (v) return v;
  }
  return '';
}

function tryParseJson(text) {
  try {
    const obj = JSON.parse(text);
    if (obj && typeof obj === 'object') {
      return pickDeviceIdFromParams(obj);
    }
  } catch (e) {
    /* not json */
  }
  return '';
}

function extractFromUrl(raw) {
  try {
    const url = new URL(raw);
    const fromQuery = pickDeviceIdFromParams(parseQueryString(url.search));
    if (fromQuery) return fromQuery;

    const parts = url.pathname.split('/').filter(Boolean);
    for (let i = parts.length - 1; i >= 0; i--) {
      const id = normalizeDeviceId(parts[i]);
      if (id) return id;
    }

    if (url.hash && url.hash.includes('=')) {
      const fromHash = pickDeviceIdFromParams(parseQueryString(url.hash.replace(/^#/, '')));
      if (fromHash) return fromHash;
    }
  } catch (e) {
    /* not a url */
  }
  return '';
}

function extractFromAlipayScheme(raw) {
  const lower = raw.toLowerCase();
  if (!lower.includes('alipay')) return { deviceId: '', alipayOnly: false };

  const alipayOnly = /alipays:\/\/|platformapi\/startapp|ds\.alipay\.com/.test(lower);
  let deviceId = '';

  const queryMatch = raw.match(/[?&]query=([^&]+)/i);
  if (queryMatch) {
    deviceId = pickDeviceIdFromParams(parseQueryString(decodeURIComponent(queryMatch[1])));
  }
  if (!deviceId) {
    deviceId = extractFromUrl(raw) || normalizeDeviceId(raw);
  }
  return { deviceId, alipayOnly: alipayOnly && !deviceId };
}

function extractFromWechatScheme(raw) {
  const lower = raw.toLowerCase();
  if (!/(weixin:\/\/|wxp:\/\/|servicewechat\.com)/.test(lower)) {
    return '';
  }
  const queryMatch = raw.match(/[?&]query=([^&]+)/i);
  if (queryMatch) {
    return pickDeviceIdFromParams(parseQueryString(decodeURIComponent(queryMatch[1])));
  }
  return extractFromUrl(raw);
}

function parseCabinetScan(raw) {
  const text = (raw || '').trim();
  if (!text) {
    return { deviceId: '', channel: 'UNKNOWN', autoOpen: false, alipayOnly: false, raw: text };
  }

  let deviceId = normalizeDeviceId(text);
  if (deviceId) {
    return { deviceId, channel: 'PLAIN', autoOpen: true, alipayOnly: false, raw: text };
  }

  deviceId = tryParseJson(text);
  if (deviceId) {
    return { deviceId, channel: 'JSON', autoOpen: true, alipayOnly: false, raw: text };
  }

  const wxId = extractFromWechatScheme(text);
  if (wxId) {
    return { deviceId: wxId, channel: 'WECHAT', autoOpen: true, alipayOnly: false, raw: text };
  }

  const alipay = extractFromAlipayScheme(text);
  if (alipay.alipayOnly && !alipay.deviceId) {
    return { deviceId: '', channel: 'ALIPAY', autoOpen: false, alipayOnly: true, raw: text };
  }
  if (alipay.deviceId) {
    return { deviceId: alipay.deviceId, channel: 'ALIPAY', autoOpen: true, alipayOnly: false, raw: text };
  }

  deviceId = extractFromUrl(text);
  if (deviceId) {
    const channel = /alipay/i.test(text) ? 'ALIPAY' : /weixin|wx/i.test(text) ? 'WECHAT' : 'URL';
    const autoOpen = parseQueryString(text.includes('?') ? text.slice(text.indexOf('?')) : '').autoOpen === '1'
      || parseQueryString(text.includes('?') ? text.slice(text.indexOf('?')) : '').open === '1';
    return { deviceId, channel, autoOpen: autoOpen || true, alipayOnly: false, raw: text };
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

function parseLaunchOptions(options) {
  if (!options) return { deviceId: '', autoOpen: false };
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
    } catch (e) {
      /* ignore */
    }
  }

  return { deviceId: normalizeDeviceId(deviceId), autoOpen };
}

module.exports = {
  parseCabinetScan,
  parseLaunchOptions,
  normalizeDeviceId
};
