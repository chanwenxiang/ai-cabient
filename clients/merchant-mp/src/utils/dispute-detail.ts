/**
 * 争议详情合并与权限投影（debt-tracker M7b）。
 * 认领/结案/回复门闩见 `dispute-actions`（M7c）。
 */
import { canReplyMerchantDispute, canResolveMerchantDispute } from '@/utils/money-ui-contracts';

export type DisputeDetailMergeInput = {
  ticketId?: string;
  lastMessage?: string;
  status?: string;
  [key: string]: unknown;
};

export type DisputeDetailApiSlice = {
  ticket?: DisputeDetailMergeInput | null;
  messages?: { body?: string }[] | null;
  canReply?: boolean;
  canResolve?: boolean;
};

/** 列表行 + 详情 API → 详情弹层行（含最新消息）。 */
export function mergeDisputeDetailRow<T extends DisputeDetailMergeInput>(
  item: T,
  api?: DisputeDetailApiSlice | null
): T & { lastMessage?: string } {
  let row: T & { lastMessage?: string } = { ...item };
  if (api?.ticket) row = { ...item, ...api.ticket };
  const lastMsg = api?.messages?.length
    ? api.messages[api.messages.length - 1]?.body
    : row.lastMessage;
  if (lastMsg) row = { ...row, lastMessage: lastMsg };
  return row;
}

export function resolveDisputeDetailPermissions(input: {
  status?: string | null;
  canReplyFromApi?: boolean;
  canResolveFromApi?: boolean;
  hasReplyPerm: boolean;
  hasResolvePerm: boolean;
}): { canReplyDetail: boolean; canResolveDetail: boolean } {
  const canReplyDetail =
    input.canReplyFromApi == null
      ? canReplyMerchantDispute({ status: input.status, hasReplyPerm: input.hasReplyPerm })
      : input.canReplyFromApi && input.hasReplyPerm;
  const canResolveDetail = canResolveMerchantDispute({
    status: input.status,
    hasResolvePerm: input.hasResolvePerm,
    canResolveFromApi: input.canResolveFromApi
  });
  return { canReplyDetail, canResolveDetail };
}
