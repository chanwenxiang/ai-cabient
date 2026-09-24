/**
 * 设备生命周期状态机门闩（可单测）。
 * 与后端 DeviceAssetService 对齐；DeviceDetailView 必须复用，禁止再手写一套（debt-tracker D6）。
 */

export type DeviceLifecycleAction =
  | 'BIND'
  | 'UNBIND'
  | 'DEPLOY'
  | 'UNDEPLOY'
  | 'RETURN'
  | 'RETIRE'
  | 'INBOUND';

export function normalizeLifecycleStatus(status?: string | null): string {
  return String(status || '')
    .trim()
    .toUpperCase();
}

export function hasBoundMerchantId(merchantId?: string | null): boolean {
  return !!String(merchantId || '').trim();
}

/** 状态未加载时全部不可点 */
export function canLifecycleAction(
  action: string,
  lifecycleStatus?: string | null,
  merchantId?: string | null
): boolean {
  const status = normalizeLifecycleStatus(lifecycleStatus);
  if (!status) return false;
  const hasMerchant = hasBoundMerchantId(merchantId);
  switch (action) {
    case 'BIND':
      return status === 'INBOUND' || status === 'IDLE';
    case 'UNBIND':
      return status === 'DEPLOYED' || (status === 'IDLE' && hasMerchant);
    case 'DEPLOY':
      return (status === 'INBOUND' || status === 'IDLE') && hasMerchant;
    case 'UNDEPLOY':
      return status === 'DEPLOYED';
    case 'RETURN':
      return status === 'INBOUND' || status === 'IDLE' || status === 'DEPLOYED';
    case 'RETIRE':
      return status !== 'RETIRED';
    case 'INBOUND':
      return status === 'INBOUND' || status === 'IDLE' || status === 'RETURNING';
    default:
      return false;
  }
}

export function lifecycleDisabledReason(
  action: string,
  lifecycleStatus?: string | null,
  merchantId?: string | null
): string {
  const status = normalizeLifecycleStatus(lifecycleStatus);
  if (!status) return '设备状态加载中';
  if (canLifecycleAction(action, status, merchantId)) return '';
  if (status === 'RETIRED') return '已退役，不可再操作生命周期';
  switch (action) {
    case 'BIND':
      if (status === 'DEPLOYED') return '已投放，请先解绑再换商户';
      return '当前状态不可绑定商户';
    case 'UNBIND':
      return hasBoundMerchantId(merchantId) ? '当前状态不可解绑' : '当前未绑定商户';
    case 'DEPLOY':
      if (status === 'DEPLOYED') return '已是投放状态';
      if (!hasBoundMerchantId(merchantId)) return '请先绑定商户再投放';
      return '当前状态不可投放';
    case 'UNDEPLOY':
      return '仅投放中的柜可撤回未投放';
    case 'RETURN':
      return '当前状态不可返厂';
    case 'RETIRE':
      return '当前状态不可退役';
    case 'INBOUND':
      if (status === 'DEPLOYED') return '投放中请先撤回或解绑后再入库';
      return '当前状态不可入库';
    default:
      return '当前不可操作';
  }
}
