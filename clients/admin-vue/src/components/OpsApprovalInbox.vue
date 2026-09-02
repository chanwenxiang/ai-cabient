<template>
  <el-popover
    v-if="visible"
    v-model:visible="popoverVisible"
    placement="bottom-end"
    :width="380"
    trigger="click"
    popper-class="ops-inbox-popper"
    @show="onOpen"
  >
    <template #reference>
      <el-badge :value="badgeCount" :hidden="badgeCount <= 0" :max="99" class="inbox-badge">
        <el-button text title="审批待办" aria-label="审批待办">
          <el-icon><Bell /></el-icon>
        </el-button>
      </el-badge>
    </template>

    <div class="inbox-panel">
      <div class="inbox-head">
        <span class="inbox-title">待办与消息</span>
        <el-button text size="small" :loading="loading" @click="loadInbox">刷新</el-button>
      </div>

      <section v-if="tasks.length" class="inbox-section">
        <div class="section-label">待审批 ({{ pendingTaskCount }})</div>
        <ul class="inbox-list">
          <li v-for="task in tasks" :key="'t-' + task.taskId" class="inbox-item-wrap">
            <button
              type="button"
              class="inbox-item"
              :class="{ unread: !task.readAt }"
              @click="openTask(task)"
            >
              <div class="item-title">{{ task.title || approvalBizLabel(task.bizType) }}</div>
              <div class="item-meta">
                {{ task.nodeName }} · {{ formatDateTime(task.createdAt) }}
              </div>
            </button>
            <!-- 无业务页权限时仍可在待办内办结（如财务批采购却进不了仓库页） -->
            <div v-if="canInlineReview(task)" class="inbox-actions">
              <el-button
                size="small"
                type="primary"
                :loading="reviewingTaskId === task.taskId"
                @click.stop="inlineReview(task, true)"
                >通过</el-button
              >
              <el-button
                size="small"
                type="danger"
                plain
                :loading="reviewingTaskId === task.taskId"
                @click.stop="inlineReview(task, false)"
                >驳回</el-button
              >
            </div>
          </li>
        </ul>
      </section>

      <section v-if="messages.length" class="inbox-section">
        <div class="section-label">站内消息 ({{ unreadMessageCount }} 未读)</div>
        <ul class="inbox-list">
          <li v-for="msg in messages" :key="'m-' + msg.id" class="inbox-item-wrap">
            <button
              type="button"
              class="inbox-item"
              :class="{ unread: !msg.read }"
              @click="openMessage(msg)"
            >
              <div class="item-title">{{ displayMessageTitle(msg.title) }}</div>
              <div class="item-meta">{{ msg.body }} · {{ formatDateTime(msg.createdAt) }}</div>
            </button>
          </li>
        </ul>
      </section>

      <section v-if="history.length" class="inbox-section">
        <div class="section-label">审批历史 ({{ history.length }})</div>
        <ul class="inbox-list">
          <li v-for="item in history" :key="'h-' + item.taskId" class="inbox-item-wrap">
            <button type="button" class="inbox-item history" @click="openHistory(item)">
              <div class="item-title">
                <span class="status-chip" :class="historyChipClass(item)">{{ historyChipLabel(item) }}</span>
                {{ item.title || approvalBizLabel(item.bizType) }}
              </div>
              <div class="item-meta">
                {{ item.progressText }} · {{ formatDateTime(item.actedAt) }}
              </div>
            </button>
          </li>
        </ul>
      </section>

      <el-empty
        v-if="!loading && !tasks.length && !messages.length && !history.length"
        description="暂无待办"
      />
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Bell } from '@element-plus/icons-vue';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { displayLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import {
  emitPurchaseOrderReviewed,
  onPurchaseOrderReviewed,
  showPurchaseReviewToast,
  type PurchaseOrderPatch
} from '@/utils/purchase-order-sync';

type ApprovalTask = {
  taskId: number;
  bizType: string;
  bizId: string;
  title: string;
  nodeName: string;
  actionPath?: string;
  createdAt?: string;
  readAt?: string | null;
};

type InboxMessage = {
  id: number;
  title: string;
  body: string;
  bizType?: string;
  bizId?: string;
  read?: boolean;
  createdAt?: string;
};

/** 本人已处理节点 + 整单流程进度。 */
type ApprovalHistoryItem = {
  taskId: number;
  instanceId?: number;
  bizType: string;
  bizId: string;
  title: string;
  myNodeName?: string;
  myStatus?: string;
  instanceStatus?: string;
  currentNodeName?: string | null;
  progressText?: string;
  actionPath?: string;
  actedAt?: string;
};

type InboxDto = {
  pendingTaskCount: number;
  unreadMessageCount: number;
  pendingTasks: ApprovalTask[];
  recentMessages: InboxMessage[];
  historyItems?: ApprovalHistoryItem[];
};

const POLL_MS = 60_000;
const router = useRouter();
const auth = useAuthStore();

const loading = ref(false);
const popoverVisible = ref(false);
const reviewingTaskId = ref<number | null>(null);
const pendingTaskCount = ref(0);
const unreadMessageCount = ref(0);
const tasks = ref<ApprovalTask[]>([]);
const messages = ref<InboxMessage[]>([]);
const history = ref<ApprovalHistoryItem[]>([]);
let pollTimer: ReturnType<typeof setInterval> | undefined;
let offPurchaseReviewed: (() => void) | undefined;

const visible = computed(
  () => auth.hasPerm('ops:approval:list') || auth.hasPerm('ops:replenishment:list')
);

/** 角标只计未读（未点开过的待办 + 未读站内信），避免「已看过仍挂红点」。 */
const unreadTaskCount = computed(() => tasks.value.filter((t) => !t.readAt).length);
const badgeCount = computed(() => unreadTaskCount.value + unreadMessageCount.value);

async function loadInbox() {
  if (!visible.value) return;
  loading.value = true;
  try {
    const data = await api.request<InboxDto>('/api/v2/ops/admin/approvals/inbox?limit=15', 'GET');
    pendingTaskCount.value = Number(data?.pendingTaskCount ?? 0);
    unreadMessageCount.value = Number(data?.unreadMessageCount ?? 0);
    tasks.value = data?.pendingTasks ?? [];
    messages.value = data?.recentMessages ?? [];
    history.value = data?.historyItems ?? [];
  } catch {
    // ignore transient errors; badge keeps last value
  } finally {
    loading.value = false;
  }
}

/**
 * 打开面板即视为已读：红点消失；「待审批 (N)」仍表示未办结任务数。
 */
async function onOpen() {
  await loadInbox();
  await markVisibleAsRead();
}

/** 将面板内未读待办/消息批量标记已读。 */
async function markVisibleAsRead() {
  const jobs: Promise<void>[] = [];
  for (const task of tasks.value) {
    if (task.readAt) continue;
    jobs.push(
      (async () => {
        try {
          await api.request(`/api/v2/ops/admin/approvals/tasks/${task.taskId}/read`, 'POST');
          task.readAt = new Date().toISOString();
        } catch {
          /* 忽略单条失败，仍继续 */
        }
      })()
    );
  }
  for (const msg of messages.value) {
    if (msg.read) continue;
    jobs.push(
      (async () => {
        try {
          await api.request(`/api/v2/ops/admin/approvals/messages/${msg.id}/read`, 'POST');
          msg.read = true;
        } catch {
          /* 忽略单条失败，仍继续 */
        }
      })()
    );
  }
  if (!jobs.length) return;
  await Promise.all(jobs);
  unreadMessageCount.value = 0;
}

function approvalBizLabel(bizType?: string) {
  return displayLabel('approval_biz_type', bizType, '审批事项');
}

function resolvePath(item: { actionPath?: string; bizType?: string; bizId?: string }): string {
  if (item.actionPath) return item.actionPath;
  if (item.bizType === 'MERCHANT_REPLEN_REQUEST') {
    return item.bizId
      ? `/replenishment?tab=requests&requestId=${encodeURIComponent(item.bizId)}`
      : '/replenishment?tab=requests';
  }
  if (item.bizType === 'PURCHASE_ORDER') {
    return item.bizId
      ? `/warehouse?tab=purchase&orderId=${encodeURIComponent(item.bizId)}`
      : '/warehouse?tab=purchase';
  }
  if (item.bizType === 'MERCHANT_WITHDRAW' || item.bizType === 'MERCHANT_WALLET_ADJUST') {
    return '/merchant-withdraw';
  }
  if (item.bizType === 'LINE_WITHDRAW') return '/line-managers?tab=withdraws';
  if (item.bizType === 'BALANCE_REFUND') return '/balance-refunds';
  if (item.bizType === 'MERCHANT_ONBOARD') {
    return item.bizId
      ? `/merchant-onboarding?onboardingId=${encodeURIComponent(item.bizId)}`
      : '/merchant-onboarding';
  }
  return '/replenishment?tab=requests';
}

/**
 * 历史数据可能仍为「待审批：」前缀，展示时统一成「审批提醒：」以免与上方待办混淆。
 * @param {string | undefined} title
 */
function displayMessageTitle(title?: string): string {
  if (!title) return '';
  return title.startsWith('待审批：') ? `审批提醒：${title.slice('待审批：'.length)}` : title;
}

/**
 * @param {ApprovalHistoryItem} item
 */
function historyChipLabel(item: ApprovalHistoryItem): string {
  if (item.myStatus === 'REJECTED') return '已驳回';
  if (item.myStatus === 'APPROVED') return '已通过';
  return '已处理';
}

/**
 * @param {ApprovalHistoryItem} item
 */
function historyChipClass(item: ApprovalHistoryItem): string {
  if (item.myStatus === 'REJECTED') return 'is-rejected';
  if (item.instanceStatus === 'PENDING') return 'is-ongoing';
  return 'is-done';
}

/**
 * 待办可内联审批的业务：避免「有节点权限、无菜单权限」时点进无权页。
 */
function canInlineReview(task: ApprovalTask): boolean {
  if (!task.bizId) return false;
  if (task.bizType === 'PURCHASE_ORDER') return true;
  return false;
}

/** 无对应业务菜单权限时，点标题不要跳无权页。 */
function canNavigateTask(task: ApprovalTask): boolean {
  if (task.bizType === 'PURCHASE_ORDER') return auth.hasPerm('ops:warehouse:list');
  return true;
}

function purchaseConfirmSubject(task: ApprovalTask): string {
  const title = String(task.title || '').trim();
  if (title) {
    if (/采购单/.test(title)) return title;
    return title;
  }
  const id = String(task.bizId || '').trim();
  return id ? `采购单 ${id}` : '该采购单';
}

async function inlineReview(task: ApprovalTask, approve: boolean) {
  if (task.bizType !== 'PURCHASE_ORDER' || !task.bizId) return;
  popoverVisible.value = false;
  await nextTick();
  const subject = purchaseConfirmSubject(task);
  try {
    await ElMessageBox.confirm(
      approve
        ? `确认通过 ${subject}（${task.nodeName || '当前节点'}）？`
        : `确认驳回 ${subject}？`,
      approve ? '审批通过' : '审批驳回',
      { type: approve ? 'info' : 'warning', appendTo: 'body' }
    );
  } catch {
    return;
  }
  reviewingTaskId.value = task.taskId;
  try {
    const updated = await api.request<PurchaseOrderPatch>(
      `/api/v2/ops/admin/purchase-orders/${task.bizId}/review`,
      'POST',
      {
        approve,
        remark: approve ? '审批通过' : '审批驳回'
      }
    );
    emitPurchaseOrderReviewed(updated || { purchaseOrderId: task.bizId });
    showPurchaseReviewToast(updated, approve);
    await loadInbox();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '审批失败');
  } finally {
    reviewingTaskId.value = null;
  }
}

async function openTask(task: ApprovalTask) {
  try {
    await api.request(`/api/v2/ops/admin/approvals/tasks/${task.taskId}/read`, 'POST');
    // 仅标记已读样式；待审批数量仍按 PENDING 任务计，不因点开而减少
    task.readAt = new Date().toISOString();
  } catch {
    // still navigate when allowed
  }
  if (!canNavigateTask(task)) {
    ElMessage.info('当前账号无业务页权限，请使用下方「通过 / 驳回」完成审批');
    return;
  }
  router.push(resolvePath(task));
}

async function openMessage(msg: InboxMessage) {
  try {
    await api.request(`/api/v2/ops/admin/approvals/messages/${msg.id}/read`, 'POST');
    if (!msg.read) unreadMessageCount.value = Math.max(0, unreadMessageCount.value - 1);
    msg.read = true;
  } catch {
    // still navigate
  }
  router.push(resolvePath(msg));
}

/**
 * @param {ApprovalHistoryItem} item
 */
function openHistory(item: ApprovalHistoryItem) {
  router.push(resolvePath(item));
}

function onWindowFocus() {
  void loadInbox();
}

onMounted(() => {
  void loadInbox();
  pollTimer = setInterval(() => void loadInbox(), POLL_MS);
  window.addEventListener('focus', onWindowFocus);
  offPurchaseReviewed = onPurchaseOrderReviewed(() => {
    void loadInbox();
  });
});

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer);
  window.removeEventListener('focus', onWindowFocus);
  offPurchaseReviewed?.();
});
</script>

<style scoped>
.inbox-badge :deep(.el-badge__content) {
  top: 6px;
  right: 10px;
}
.inbox-panel {
  max-height: 420px;
  overflow: auto;
}
.inbox-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}
.inbox-title {
  font-weight: 600;
  font-size: 14px;
}
.inbox-section + .inbox-section {
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px solid var(--layout-border, #e2e8f0);
}
.section-label {
  font-size: 12px;
  color: var(--layout-muted, #64748b);
  margin-bottom: 6px;
}
.inbox-list {
  list-style: none;
  margin: 0;
  padding: 0;
}
.inbox-item-wrap {
  margin: 0;
}
.inbox-actions {
  display: flex;
  gap: 8px;
  padding: 0 10px 8px;
}
.inbox-item {
  display: block;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: inherit;
  font: inherit;
  text-align: left;
  cursor: pointer;
}
.inbox-item:hover {
  background: var(--layout-hover, rgba(148, 163, 184, 0.12));
}
.inbox-item.unread .item-title {
  font-weight: 600;
}
.inbox-item.history .item-title {
  font-weight: 500;
}
.item-title {
  font-size: 13px;
  line-height: 1.35;
  word-break: break-word;
}
.item-meta {
  margin-top: 2px;
  font-size: 11px;
  color: var(--layout-muted, #64748b);
  line-height: 1.35;
  word-break: break-word;
}
.status-chip {
  display: inline-block;
  margin-right: 6px;
  padding: 0 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  line-height: 18px;
  vertical-align: 1px;
}
.status-chip.is-done {
  color: #15803d;
  background: rgba(34, 197, 94, 0.12);
}
.status-chip.is-ongoing {
  color: #b45309;
  background: rgba(245, 158, 11, 0.14);
}
.status-chip.is-rejected {
  color: #b91c1c;
  background: rgba(239, 68, 68, 0.12);
}
</style>
