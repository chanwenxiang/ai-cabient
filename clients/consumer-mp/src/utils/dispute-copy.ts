import type { DisputeTicketDto } from '@aicabinet/shared-types';
import { fmtMoney, localizeDisputeReason } from '@aicabinet/shared-uni/format';

export interface ConsumerDisputeReviewCopy {
  icon: string;
  title: string;
  detail: string;
  tone: 'wait' | 'warn' | 'success';
}

function isServiceUnavailable(reason: string) {
  return /识别服务暂时不可用|识别服务暂不可用|vision|service.?unavailable/i.test(reason);
}

function isInternalStagingReason(reason: string) {
  return /非生产|重力信号|仅有重力|重力回填|模拟\/兜底|模拟识别|gravity-fill|gravity-mismatch|mock-v/i.test(
    reason
  );
}

function isRecognitionSucceeded(reason: string) {
  return /置信度|识别结果需人工|未识别到商品|识别到：|confidence|manual review/i.test(reason);
}

const GENERIC_MANUAL_REVIEW = '商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。';

function isTerminalStatus(status?: string | null) {
  const s = (status || '').toUpperCase();
  return s === 'RESOLVED' || s === 'CLOSED';
}

/** 已结案：按金额生成结论，避免仍展示 OPEN 态「暂未扣款」 */
function resolvedConsumerDetail(
  ticket: Pick<DisputeTicketDto, 'reason' | 'billedAmountCents' | 'refundedAmountCents'>
): string {
  const billed = Number(ticket.billedAmountCents ?? 0);
  const refunded = Number(ticket.refundedAmountCents ?? 0);
  if (refunded > 0 && billed <= 0) {
    return `已结案：已免单退款 ${fmtMoney(refunded)}`;
  }
  if (refunded > 0 && billed > 0) {
    return `已结案：扣款 ${fmtMoney(billed)}，已退 ${fmtMoney(refunded)}`;
  }
  if (billed > 0) {
    return `已结案：最终扣款 ${fmtMoney(billed)}`;
  }
  if (refunded > 0) {
    return `已结案：已退款 ${fmtMoney(refunded)}`;
  }
  const reason = localizeDisputeReason(ticket.reason);
  if (reason && !/暂未扣款|审核完成后会生成账单/i.test(reason)) {
    return reason;
  }
  return '已结案：未产生扣款，可在订单中查看账单。';
}

/** 将后端/测试 reason 转成消费者可读中文，避免露出内部/英文话术 */
export function localizeConsumerDisputeReason(reason?: string | null): string {
  return localizeDisputeReason(reason);
}

/** 消费者端：争议/人工审核卡片文案（与后端 ticket.reason 对齐） */
export function consumerDisputeReviewCopy(
  ticket?: Pick<
    DisputeTicketDto,
    'reason' | 'status' | 'billedAmountCents' | 'refundedAmountCents'
  > | null
): ConsumerDisputeReviewCopy {
  const raw = (ticket?.reason || '').trim();
  const reason = localizeDisputeReason(raw);

  if (isTerminalStatus(ticket?.status)) {
    return {
      icon: '✓',
      title: '人工审核已完成',
      detail: resolvedConsumerDetail(ticket || {}),
      tone: 'success'
    };
  }
  if (isServiceUnavailable(raw)) {
    return {
      icon: '!',
      title: '识别服务暂不可用',
      detail: reason || '识别服务暂时不可用，本次暂未扣款。审核完成后会生成账单。',
      tone: 'warn'
    };
  }
  // OPEN 态统一「待确认」叙事，避免「识别完成 ✓」与「审核中」打架
  if (isRecognitionSucceeded(raw) || isInternalStagingReason(raw)) {
    return {
      icon: '!',
      title: '账单待人工确认',
      detail: reason || '识别结果需人工确认，本次暂未扣款。审核完成后会生成账单。',
      tone: 'wait'
    };
  }
  return {
    icon: '!',
    title: '账单审核中',
    detail: reason || GENERIC_MANUAL_REVIEW,
    tone: 'wait'
  };
}

/** 仅退款结案时展示退款渠道；纯扣款结案不展示 */
export function shouldShowConsumerRefundChannel(
  ticket?: Pick<DisputeTicketDto, 'status' | 'refundedAmountCents'> | null
): boolean {
  if (!ticket) return false;
  if (!isTerminalStatus(ticket.status)) return false;
  return Number(ticket.refundedAmountCents ?? 0) > 0;
}

/** 消费者提交申诉/退款失败时的友好文案（覆盖后端 409 等冲突提示） */
export function consumerAppealErrorMessage(error: unknown, fallback = '提交失败'): string {
  let raw = '';
  if (error instanceof Error) {
    raw = error.message;
  } else if (typeof error === 'string') {
    raw = error;
  }
  const msg = (raw || '').trim();
  if (!msg) return fallback;
  if (/本单已结案|不可再申诉|申诉通道已关闭|通道已关闭|关联争议已结案|无法再次退款/i.test(msg)) {
    return '本单已结案，不可再申诉';
  }
  if (/该会话已有申诉|已有申诉工单|已有进行中的申诉/i.test(msg)) {
    return '本单已有申诉，请等待审核结果';
  }
  if (/未开启自助退款|仅可申诉/i.test(msg)) {
    return msg;
  }
  if (/[\u4e00-\u9fff]/.test(msg)) return msg;
  return fallback;
}
