<template>
  <el-popover
    v-if="visible"
    placement="bottom-end"
    :width="360"
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
              <div class="item-title">{{ task.title || task.bizType }}</div>
              <div class="item-meta">
                {{ task.nodeName }} · {{ formatDateTime(task.createdAt) }}
              </div>
            </button>
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

      <el-empty v-if="!loading && !tasks.length && !messages.length" description="暂无待办" />
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { Bell } from '@element-plus/icons-vue';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';

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

type InboxDto = {
  pendingTaskCount: number;
  unreadMessageCount: number;
  pendingTasks: ApprovalTask[];
  recentMessages: InboxMessage[];
};

const POLL_MS = 60_000;
const router = useRouter();
const auth = useAuthStore();

const loading = ref(false);
const pendingTaskCount = ref(0);
const unreadMessageCount = ref(0);
const tasks = ref<ApprovalTask[]>([]);
const messages = ref<InboxMessage[]>([]);
let pollTimer: ReturnType<typeof setInterval> | undefined;

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

function resolvePath(task: ApprovalTask | InboxMessage): string {
  if ('actionPath' in task && task.actionPath) return task.actionPath;
  if (task.bizType === 'MERCHANT_REPLEN_REQUEST') return '/replenishment?tab=requests';
  if (task.bizType === 'PURCHASE_ORDER') return '/warehouse?tab=purchase';
  if (task.bizType === 'MERCHANT_WITHDRAW' || task.bizType === 'MERCHANT_WALLET_ADJUST') {
    return '/merchant-withdraw';
  }
  if (task.bizType === 'LINE_WITHDRAW') return '/line-managers?tab=withdraws';
  if (task.bizType === 'BALANCE_REFUND') return '/balance-refunds';
  if (task.bizType === 'MERCHANT_ONBOARD') return '/merchant-onboarding';
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

async function openTask(task: ApprovalTask) {
  try {
    await api.request(`/api/v2/ops/admin/approvals/tasks/${task.taskId}/read`, 'POST');
    // 仅标记已读样式；待审批数量仍按 PENDING 任务计，不因点开而减少
    task.readAt = new Date().toISOString();
  } catch {
    // still navigate
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

function onWindowFocus() {
  void loadInbox();
}

onMounted(() => {
  void loadInbox();
  pollTimer = setInterval(() => void loadInbox(), POLL_MS);
  window.addEventListener('focus', onWindowFocus);
});

onUnmounted(() => {
  if (pollTimer) clearInterval(pollTimer);
  window.removeEventListener('focus', onWindowFocus);
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
</style>
