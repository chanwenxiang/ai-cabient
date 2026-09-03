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
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:feedback:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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
        <el-table
          v-loading="loading"
          :data="sortedList"
          stripe
          border
          class="report-table"
          row-key="feedbackId"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无反馈"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="feedbackId"
            label="反馈编号"
            width="100"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.feedbackId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="类型" width="110" align="center">
            <template #default="{ row }">
              {{ dictLabel('feedback_type', row.feedbackType) || '反馈' }}
            </template>
          </el-table-column>
          <el-table-column
            label="内容"
            min-width="220"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ row.content || '无' }}</template>
          </el-table-column>
          <el-table-column label="用户" width="100" align="center" class-name="col-text">
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
          <el-table-column
            label="设备"
            min-width="120"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
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
          <el-table-column label="评分" width="72" align="center">
            <template #default="{ row }">{{ row.rating ?? '无' }}</template>
          </el-table-column>
          <el-table-column label="状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.status)" size="small">
                {{ dictLabel('feedback_status', row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="168" align="center" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
            </template>
          </el-table-column>
          <el-table-column
            v-if="showActionColumn"
            label="操作"
            width="140"
            class-name="col-action"
            align="center"
            fixed="right"
          >
            <template #default="{ row }">
              <TableActions
                :actions="feedbackActions(row)"
                @action="(key) => onFeedbackAction(key, row)"
              />
            </template>
          </el-table-column>
        </el-table>
      </div>
    </div>

    <PagePager
      :hydrated="listHydrated"
      v-model:current-page="page"
      v-model:page-size="size"
      :total="total"
      :page-sizes="[10, 20, 50, 100]"
      layout="total, sizes, prev, pager, next, jumper"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />
  </el-card>

  <el-dialog v-model="replyDialog" title="回复反馈" width="480px" destroy-on-close>
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
import { ChatDotRound, Delete, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

type Row = Record<string, any>;
const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('feedbackId');
const canReply = computed(() => auth.hasPerm('ops:feedback:reply'));
const canDelete = computed(
  () => auth.hasPerm('ops:feedback') || auth.hasPerm('ops:feedback:reply')
);

const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const status = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const list = ref<Row[]>([]);
const replyDialog = ref(false);
const replyText = ref('');
const current = ref<Row | null>(null);

const sortedList = computed(() => sortById(list.value));

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection } =
  useTableSelection<Row>((r) => r.feedbackId);

const { onExport } = useListCsv({
  filePrefix: '用户反馈',
  headers: ['ID', '类型', '内容', '用户', '设备', '评分', '状态', '时间'],
  toRows: () =>
    pickSelected(sortedList.value).map((row) => [
      row.feedbackId,
      dictLabel('feedback_type', row.feedbackType),
      row.content,
      row.userId,
      row.deviceId,
      row.rating,
      dictLabel('feedback_status', row.status),
      formatDateTime(row.createdAt)
    ])
});

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

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (status.value) q.set('status', status.value);
    const data = await api.request<{ items: Row[]; total: number }>(
      `/api/v2/ops/feedback?${q}`,
      'GET'
    );
    list.value = data.items || [];
    total.value = Number(data.total) || 0;
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function onSizeChange() {
  page.value = 1;
  load();
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function reset() {
  status.value = '';
  page.value = 1;
  syncRouteQuery();
  load();
}

function openReply(row: Row) {
  current.value = row;
  replyText.value = '';
  replyDialog.value = true;
}

function feedbackActions(row: Row) {
  const acts: {
    key: string;
    label: string;
    icon: any;
    type?: 'primary' | 'success' | 'warning' | 'danger' | 'info';
  }[] = [];
  if (canReply.value && row.status === 'PENDING') {
    acts.push({ key: 'reply', label: '回复', icon: ChatDotRound, type: 'primary' });
  }
  if (canDelete.value) {
    acts.push({ key: 'delete', label: '删除', icon: Delete, type: 'danger' });
  }
  return acts;
}

/** 当前页无可操作项时隐藏操作列 */
const showActionColumn = computed(() => list.value.some((row) => feedbackActions(row).length > 0));

async function onFeedbackAction(key: string, row: Row) {
  if (key === 'reply') openReply(row);
  else if (key === 'delete') await removeFeedback(row);
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
    await load();
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
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '回复失败');
  } finally {
    saving.value = false;
  }
}

onMounted(() => {
  applyRouteQuery();
  load();
});

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
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
  font-size: 15px;
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.4;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.feedback-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.feedback-cell strong {
  font-weight: 650;
}
.feedback-cell small {
  color: var(--el-text-color-secondary);
  font-size: 11px;
}
.feedback-cell .content-line {
  color: var(--el-text-color-regular);
  font-size: 13px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  max-width: 320px;
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
  font-size: 13px;
}
.reply-content {
  margin: 0 0 12px;
  color: var(--layout-muted);
  line-height: 1.5;
}
</style>
