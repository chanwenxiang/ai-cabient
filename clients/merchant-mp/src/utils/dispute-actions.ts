/**
 * 争议认领 / 结案 / 回复写路径门闩与导航（debt-tracker M7c）。
 * 纯函数：禁止依赖 uni / merchantApi；确认框与 API 调用仍在 disputes.vue。
 */
import type { MerchantDisputeResolutionType } from '@/utils/money-ui-contracts';

export function canStartDisputeClaim(input: {
  ticketId?: string | null;
  claiming: boolean;
}): boolean {
  return Boolean(input.ticketId) && !input.claiming;
}

export function canStartDisputeResolve(input: {
  ticketId?: string | null;
  resolving: boolean;
}): boolean {
  return Boolean(input.ticketId) && !input.resolving;
}

/** 认领成功后把票字段合入详情行。 */
export function mergeClaimedDisputeDetail<T extends object>(detail: T, ticket: Partial<T>): T {
  return { ...detail, ...ticket };
}

export function disputeResolveTypeLabels(
  display: (namespace: string, code: string) => string
): Record<MerchantDisputeResolutionType, string> {
  return {
    KEEP: display('dispute_resolution', 'KEEP'),
    WAIVE: display('dispute_resolution', 'WAIVE'),
    CONFIRM: display('dispute_resolution', 'CONFIRM')
  };
}

/** 回复弹窗固定文案（promptText 入参）。 */
export const DISPUTE_REPLY_PROMPT = {
  title: '回复争议',
  hint: '回复内容将同步给消费者与运营',
  placeholder: '填写商户回复内容',
  required: true,
  requiredMessage: '请填写回复内容',
  maxLength: 200,
  testId: 'dispute-reply-prompt'
} as const;

export const DISPUTE_REPLY_DENIED_MESSAGE = '无回复权限';

export function disputeActionErrorMessage(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback;
}

export function disputeOrderDetailUrl(orderId: string): string {
  return `/pages/order-detail/order-detail?orderId=${encodeURIComponent(orderId)}`;
}

export function disputeDeviceDetailUrl(deviceId: string): string {
  return `/pages/device-detail/device-detail?id=${encodeURIComponent(deviceId)}`;
}
