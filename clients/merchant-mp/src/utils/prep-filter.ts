/**
 * 备货两页（备货单 / 手动生成）共用的柜机筛选：按柜名或编码模糊匹配，大小写不敏感。
 * 空关键字＝不过滤（返回 true），调用方直接 filter 即可。
 */
export function matchDeviceKeyword(
  keyword: string,
  device: { deviceName?: string | null; deviceId?: string | null }
): boolean {
  const kw = String(keyword ?? '')
    .trim()
    .toLowerCase();
  if (!kw) return true;
  const name = String(device.deviceName ?? '').toLowerCase();
  const id = String(device.deviceId ?? '').toLowerCase();
  return name.includes(kw) || id.includes(kw);
}
