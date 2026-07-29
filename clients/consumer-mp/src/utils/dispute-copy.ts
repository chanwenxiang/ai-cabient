import type { DisputeTicketDto } from '@aicabinet/shared-types';

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

function isMostlyEnglish(text: string) {
  if (!text) return false;
  const letters = (text.match(/[A-Za-z]/g) || []).length;
  const cjk = (text.match(/[\u4e00-\u9fff]/g) || []).length;
  return letters >= 8 && cjk === 0;
}

const GENERIC_MANUAL_REVIEW =
  '商品识别结果需要人工确认，本次暂未扣款。审核完成后会生成账单。';

/** 将后端/测试 reason 转成消费者可读中文，避免露出内部/英文话术 */
export function localizeConsumerDisputeReason(reason?: string | null): string {
  const r = (reason || '').trim();
  if (!r) return '';
  if (/recognition needs manual review/i.test(r) || /no charge yet/i.test(r)) {
    return GENERIC_MANUAL_REVIEW;
  }
  if (/confidence/i.test(r) && /threshold|below|manual/i.test(r)) {
    return '部分商品识别置信度不足，需人工确认后再扣款。';
  }
  if (/timeout|识别超时|STIMEOUT/i.test(r)) {
    return '识别超时，本次暂未扣款，工作人员正在核对账单。';
  }
  if (isInternalStagingReason(r)) {
    return GENERIC_MANUAL_REVIEW;
  }
  if (isMostlyEnglish(r)) {
    return GENERIC_MANUAL_REVIEW;
  }
  return r;
}

/** 消费者端：争议/人工审核卡片文案（与后端 ticket.reason 对齐） */
export function consumerDisputeReviewCopy(
  ticket?: Pick<DisputeTicketDto, 'reason' | 'status'> | null
): ConsumerDisputeReviewCopy {
  const raw = (ticket?.reason || '').trim();
  const reason = localizeConsumerDisputeReason(raw);

  if (ticket?.status === 'RESOLVED') {
    return {
      icon: '✓',
      title: '人工审核已完成',
      detail: reason || '审核结果已生效，可在订单中查看账单。',
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
