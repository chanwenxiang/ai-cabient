/**
 * 禁止在 UI 中硬编码字典状态中文（应走 dictLabel / displayLabel）。
 * 覆盖 order / announcement / dispute / balance_refund 等高频状态文案。
 */
const STATUS_LABELS = new Map([
  // order_status / recharge overlapping
  ['待支付', "dictLabel/displayLabel('order_status'|'recharge_status', code)"],
  ['处理中', "dictLabel/displayLabel('order_status', code)"],
  ['已支付', "dictLabel/displayLabel('order_status'|'recharge_status', code)"],
  ['已完成', "dictLabel/displayLabel('order_status', code)"],
  ['争议中', "dictLabel/displayLabel('order_status', code)"],
  ['已退款', "dictLabel/displayLabel('order_status'|'recharge_status'|'balance_refund_status', code)"],
  ['部分退款', "dictLabel/displayLabel('order_status', code)"],
  ['处理失败', "dictLabel/displayLabel('order_status', code)"],
  ['已取消', "dictLabel/displayLabel('order_status'|'recharge_status', code)"],
  // announcement_status
  ['草稿', "dictLabel/displayLabel('announcement_status', code)"],
  ['已发布', "dictLabel/displayLabel('announcement_status', code)"],
  ['已归档', "dictLabel/displayLabel('announcement_status', code)"],
  // dispute_status / balance_refund
  ['待审核', "dictLabel/displayLabel('dispute_status'|'balance_refund_status', code)"],
  ['审核中', "dictLabel/displayLabel('dispute_status', code)"],
  ['运营审核中', "dictLabel/displayLabel('dispute_status', code) + ' 或后端 consumerReviewTitle'"],
  ['运营已审核', "后端 consumerReviewTitle / displayLabel('dispute_status', code)"],
  ['已结案', "dictLabel/displayLabel('dispute_status', code)"],
  ['已关闭', "dictLabel/displayLabel('dispute_status'|'recharge_status', code)"],
  ['已驳回', "dictLabel/displayLabel('balance_refund_status', code)"],
  // enable / online / role status（admin 高频漏网）
  ['启用', "dictLabel/displayLabel('enable_status'|'coupon_status'|…, code)"],
  ['停用', "dictLabel/displayLabel('enable_status'|'merchant_status'|…, code)"],
  ['正常', "dictLabel/displayLabel('merchant_status'|'warehouse_status'|…, code)"],
  ['在线', "dictLabel/displayLabel('online_status', 'ONLINE')"],
  ['离线', "dictLabel/displayLabel('online_status', 'OFFLINE')"]
]);

const ALLOWED_CALLEES = new Set(['dictLabel', 'displayLabel', 'dictOptions']);

function isAllowedCall(node) {
  let cur = node?.parent;
  while (cur) {
    if (cur.type === 'CallExpression') {
      const callee = cur.callee;
      if (callee?.type === 'Identifier' && ALLOWED_CALLEES.has(callee.name)) {
        return true;
      }
      if (
        callee?.type === 'MemberExpression' &&
        callee.property?.type === 'Identifier' &&
        ALLOWED_CALLEES.has(callee.property.name)
      ) {
        return true;
      }
      return false;
    }
    if (
      cur.type === 'VariableDeclarator' ||
      cur.type === 'Property' ||
      cur.type === 'AssignmentExpression'
    ) {
      break;
    }
    cur = cur.parent;
  }
  return false;
}

function reportIfBanned(context, node, value) {
  if (typeof value !== 'string' || !STATUS_LABELS.has(value)) return;
  if (isAllowedCall(node)) return;
  context.report({
    node,
    messageId: 'useDict',
    data: { label: value, hint: STATUS_LABELS.get(value) }
  });
}

/** @type {import('eslint').Rule.RuleModule} */
const noHardcodedStatusLabel = {
  meta: {
    type: 'problem',
    docs: {
      description: 'Forbid hardcoded status Chinese labels; use shared-dict helpers'
    },
    schema: [],
    messages: {
      useDict: '禁止硬编码状态「{{label}}」，请使用 {{hint}}'
    }
  },
  create(context) {
    return {
      Literal(node) {
        reportIfBanned(context, node, node.value);
      },
      TemplateElement(node) {
        if (node.parent?.expressions?.length) return;
        reportIfBanned(context, node, node.value?.cooked);
      },
      VLiteral(node) {
        reportIfBanned(context, node, node.value);
      }
    };
  }
};

export default {
  rules: {
    'no-hardcoded-status-label': noHardcodedStatusLabel
  }
};
