<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">用户反馈</span>
        <div class="actions">
          <el-button @click="onExport">导出</el-button>
          <el-select v-model="status" clearable placeholder="全部状态" style="width: 140px" @change="load">
            <el-option
              v-for="item in dictOptions('feedback_status')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 1080px">
        <el-table v-loading="loading" :data="list" stripe border>
      <el-table-column prop="feedbackId" label="ID" width="80" />
      <el-table-column label="类型" width="100">
        <template #default="{ row }">{{ dictLabel('feedback_type', row.feedbackType) }}</template>
      </el-table-column>
      <el-table-column prop="content" label="内容" min-width="260" show-overflow-tooltip />
      <el-table-column prop="userId" label="用户" width="90" />
      <el-table-column prop="deviceId" label="设备" width="120" show-overflow-tooltip />
      <el-table-column prop="rating" label="评分" width="70" />
      <el-table-column label="状态" width="100">
        <template #default="{ row }">
          <el-tag :type="dictTagType(row.status)" size="small">
            {{ dictLabel('feedback_status', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="时间" width="170">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column v-if="canReply" label="操作" width="88" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions
            v-if="row.status === 'PENDING'"
            :actions="[{ key: 'reply', label: '回复', icon: ChatDotRound, type: 'primary' }]"
            @action="() => openReply(row)"
          />
          <span v-else class="muted">已处理</span>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无反馈" /></template>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="replyDialog" title="回复反馈" width="480px" destroy-on-close>
      <p class="reply-content">{{ current?.content }}</p>
      <el-input v-model="replyText" type="textarea" :rows="4" maxlength="2000" show-word-limit placeholder="回复内容" />
      <template #footer>
        <el-button @click="replyDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitReply">提交回复</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ChatDotRound, Refresh } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const auth = useAuthStore();
const canReply = computed(() => auth.hasPerm('ops:feedback:reply'));

const loading = ref(false);
const saving = ref(false);
const status = ref('');
const list = ref<Row[]>([]);
const replyDialog = ref(false);
const replyText = ref('');
const current = ref<Row | null>(null);

const { onExport } = useListCsv({
  filePrefix: '用户反馈',
  headers: ['ID', '类型', '内容', '用户', '设备', '评分', '状态', '时间'],
  toRows: () =>
    list.value.map((row) => [
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

async function load() {
  loading.value = true;
  try {
    const q = status.value ? `?status=${encodeURIComponent(status.value)}` : '';
    list.value = await api.request<Row[]>(`/api/v2/ops/feedback${q}`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

function openReply(row: Row) {
  current.value = row;
  replyText.value = '';
  replyDialog.value = true;
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
    ElMessage.success('已回复');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '回复失败');
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; align-items: center; }
.muted { color: var(--layout-muted); font-size: 13px; }
.reply-content { margin: 0 0 12px; color: var(--layout-muted); line-height: 1.5; }
</style>
