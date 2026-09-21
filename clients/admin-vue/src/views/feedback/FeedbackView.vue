<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">用户反馈</span>
            <span class="hint">回复为运营备注，不推送用户；用户可在「我的反馈」自行查看</span>
          </div>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="状态">
        <el-select
          v-model="status"
          clearable
          placeholder="全部"
          style="width: 140px"
          @change="search"
        >
          <el-option
            v-for="item in dictOptions('feedback_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="feedbackId"
          selectable
          :actions="showActionColumn ? feedbackActions : undefined"
          :action-width="140"
          empty-text="暂无反馈"
          sort-field-label="反馈编号"
          :csv="csvOptions"
          @action="onFeedbackAction"
        >
          <el-table-column prop="feedbackId" label="反馈编号" width="100" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.feedbackId }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="类型"
            width="110"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              {{ dictLabel('feedback_type', row.feedbackType) || '反馈' }}
            </template>
          </el-table-column>
          <el-table-column label="内容" min-width="220" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="row.content || ''">{{
                row.content || '无'
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="用户" width="100" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.userId"
                type="button"
                class="link-cell"
                @click="goPath('/users', { keyword: String(row.userId) })"
              >
                {{ row.userId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="设备" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <button
                v-if="row.deviceId"
                type="button"
                class="link-cell"
                @click="goPath(`/devices/${encodeURIComponent(row.deviceId)}`)"
              >
                {{ row.deviceId }}
              </button>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column
            label="评分"
            width="72"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.rating ?? '无' }}</template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('feedback_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column
            align="center"
            label="时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>
  </el-card>

  <el-dialog v-model="replyDialog" title="回复反馈" destroy-on-close>
    <p class="reply-content">{{ current?.content }}</p>
    <el-input
      v-model="replyText"
      type="textarea"
      :rows="4"
      maxlength="2000"
      show-word-limit
      placeholder="回复内容"
    />
    <template #footer>
      <el-button @click="replyDialog = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submitReply">提交回复</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { ChatDotRound, Delete } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const canReply = computed(() => auth.hasPerm('ops:feedback:reply'));
const canDelete = computed(
  () => auth.hasPerm('ops:feedback') || auth.hasPerm('ops:feedback:reply')
);

const saving = ref(false);
const status = ref('');
const replyDialog = ref(false);
const replyText = ref('');
const current = ref<Row | null>(null);

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<Row>({
  rowKey: (r) => r.feedbackId,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: async (params) => {
    const q = new URLSearchParams({
      page: String(params.page), // 0 起（useCrudTable 已换算）
      size: String(params.size)
    });
    if (status.value) q.set('status', status.value);
    return api.request<{ items: Row[]; total: number }>(`/api/v2/ops/feedback?${q}`, 'GET');
  },
  // 反馈编号本地排序（替代原 useIdColumnSort 表头排序，改由壳内「按反馈编号 升/降序」切换）
  sort: { prop: 'feedbackId', mode: 'local' }
});

// 导出统一并入 CrudTable 工具条（选中优先导出、文件命名由组件内置）
const csvOptions: CrudCsvOptions = {
  filePrefix: '用户反馈',
  exportPerm: 'ops:feedback:export',
  headers: ['ID', '类型', '内容', '用户', '设备', '评分', '状态', '时间'],
  toRows: (rows) =>
    rows.map((row) => [
      row.feedbackId,
      dictLabel('feedback_type', row.feedbackType),
      row.content,
      row.userId,
      row.deviceId,
      row.rating,
      dictLabel('feedback_status', row.status),
      formatDateTime(row.createdAt)
    ])
};

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (status.value) query.status = status.value;
  router.replace({ query });
}

function applyRouteQuery() {
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== status.value) {
    status.value = qStatus;
    return true;
  }
  return false;
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function reset() {
  status.value = '';
  syncRouteQuery();
  void crud.search();
}

function openReply(row: Row) {
  current.value = row;
  replyText.value = '';
  replyDialog.value = true;
}

function feedbackActions(row: Row): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (row.status === 'PENDING') {
    acts.push({
      key: 'reply',
      label: '回复',
      icon: ChatDotRound,
      type: 'primary',
      perm: 'ops:feedback:reply'
    });
  }
  acts.push({
    key: 'delete',
    label: '删除',
    icon: Delete,
    type: 'danger',
    perm: ['ops:feedback', 'ops:feedback:reply']
  });
  return acts;
}

/** 当前页无可操作项时隐藏操作列（与行操作配置同口径：权限 + 状态） */
const showActionColumn = computed(
  () => canDelete.value || (canReply.value && crud.items.some((row) => row.status === 'PENDING'))
);

function onFeedbackAction({ key, row }: { key: string; row: Row }) {
  if (key === 'reply') openReply(row);
  else if (key === 'delete') void removeFeedback(row);
}

async function removeFeedback(row: Row) {
  try {
    await ElMessageBox.confirm(`确认删除反馈 #${row.feedbackId}？`, '删除反馈', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/feedback/${row.feedbackId}`, 'DELETE');
    ElMessage.success('已删除');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function submitReply() {
  if (!current.value) return;
  if (!replyText.value.trim()) return ElMessage.warning('请填写回复内容');
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/feedback/${current.value.feedbackId}/reply`, 'POST', {
      reply: replyText.value.trim()
    });
    replyDialog.value = false;
    ElMessage.success('已保存回复（仅运营备注，未推送用户）');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '回复失败');
  } finally {
    saving.value = false;
  }
}

// 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由这里显式首查
onMounted(() => {
  applyRouteQuery();
  void crud.load();
});

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () => route.query.status,
  () => {
    void reloadFromRouteQuery();
  }
);

onActivated(() => {
  void reloadFromRouteQuery();
});
</script>

<style scoped>
.page-card-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 12px;
  flex-wrap: wrap;
}
.page-card-head__meta {
  min-width: 0;
}
.page-card-head__title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.title {
  font-weight: 600;
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
  line-height: 1.4;
}
.link-cell {
  appearance: none;
  border: 0;
  padding: 0;
  margin: 0;
  background: transparent;
  color: var(--el-color-primary);
  cursor: pointer;
  font: inherit;
}
.link-cell:hover {
  text-decoration: underline;
}
.muted {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.reply-content {
  margin: 0 0 12px;
  color: var(--layout-muted);
  line-height: 1.5;
}
</style>
