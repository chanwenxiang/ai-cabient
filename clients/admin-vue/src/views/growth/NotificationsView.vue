<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">消息记录</span>
            <span class="hint">站内信发送记录；可手动向消费者 / 商户发信</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:notify:list']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            v-hasPermi="['ops:notify:list']"
            type="danger"
            plain
            :disabled="!selectedKeys.length"
            :loading="batchDeleting"
            @click="batchRemove"
            >删除选中</el-button
          >
          <el-button v-hasPermi="['ops:notify:list']" type="primary" @click="openSend"
            >发送站内信</el-button
          >
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="displayList"
          stripe
          border
          row-key="id"
          empty-text=" "
          class="report-table"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
        >
          <template #empty
            ><el-empty v-if="listHydrated && !loading" description="暂无消息记录"
          /></template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="id"
            label="ID"
            width="80"
            align="center"
            class-name="col-text"
            sortable="custom"
          />
          <el-table-column label="时间" width="150" align="center">
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column label="受众" width="90" align="center">
            <template #default="{ row }">
              <el-tag :type="row.audience === 'CONSUMER' ? 'primary' : 'warning'">{{
                audienceLabel(row.audience)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="title" label="标题" min-width="150" align="center" />
          <el-table-column label="内容" min-width="240" show-overflow-tooltip>
            <template #default="{ row }">{{ rewriteBizNosInText(row.body) }}</template>
          </el-table-column>
          <el-table-column label="业务" width="100" align="center" show-overflow-tooltip>
            <template #default="{ row }">{{
              dictLabel('notification_biz_type', row.bizType)
            }}</template>
          </el-table-column>
          <el-table-column label="关联单号" width="150" align="center" class-name="col-text">
            <template #default="{ row }">{{ displayBizNo(row.bizId, '无') }}</template>
          </el-table-column>
          <el-table-column
            label="操作"
            width="140"
            align="center"
            class-name="col-action"
            fixed="right"
          >
            <template #default="{ row }">
              <el-button v-hasPermi="['ops:notify:list']" link type="primary" @click="openEdit(row)"
                >编辑</el-button
              >
              <el-button v-hasPermi="['ops:notify:list']" link type="danger" @click="removeRow(row)"
                >删除</el-button
              >
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

    <el-dialog v-model="sendVisible" title="发送站内信" width="520px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="受众" required>
          <el-radio-group v-model="sendForm.audience">
            <el-radio value="CONSUMER">消费者</el-radio>
            <el-radio value="MERCHANT">商户</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="sendForm.audience === 'CONSUMER'" label="用户ID" required>
          <el-input v-model="sendForm.userId" placeholder="如 10001" />
        </el-form-item>
        <el-form-item v-else label="商户编号" required>
          <el-input v-model="sendForm.merchantId" placeholder="如 MCH-DEFAULT" />
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="sendForm.title" maxlength="80" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="sendForm.body"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="sendVisible = false">取消</el-button>
        <el-button type="primary" :loading="sending" @click="doSend">发送</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑站内信" width="520px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="标题" required>
          <el-input v-model="editForm.title" maxlength="80" show-word-limit />
        </el-form-item>
        <el-form-item label="内容" required>
          <el-input
            v-model="editForm.body"
            type="textarea"
            :rows="4"
            maxlength="500"
            show-word-limit
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="doSaveEdit">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Refresh } from '@element-plus/icons-vue';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { displayBizNo, rewriteBizNosInText } from '@aicabinet/shared-uni/format';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';

type NotificationRow = {
  id: number;
  title: string;
  body: string;
  audience?: string;
  bizType?: string;
  bizId?: string;
  createdAt: string;
};

const loading = ref(false);
const listHydrated = ref(false);
const sending = ref(false);
const saving = ref(false);
const batchDeleting = ref(false);
const page = ref(1);
const size = ref(20);
const total = ref(0);
const list = ref<NotificationRow[]>([]);
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort<NotificationRow>('id');
const displayList = computed(() => sortById(list.value));

const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection, selectedKeys } =
  useTableSelection<NotificationRow>((r) => r.id);

const { onExport } = useListCsv({
  filePrefix: '消息记录',
  headers: ['ID', '时间', '受众', '标题', '内容', '业务', '关联单号'],
  toRows: () =>
    pickSelected(displayList.value).map((row) => [
      row.id,
      formatTime(row.createdAt),
      audienceLabel(row.audience),
      row.title || '',
      rewriteBizNosInText(row.body || ''),
      dictLabel('notification_biz_type', row.bizType),
      displayBizNo(row.bizId, '无')
    ])
});

const sendVisible = ref(false);
const sendForm = reactive({
  audience: 'CONSUMER',
  userId: '',
  merchantId: '',
  title: '',
  body: ''
});
const editVisible = ref(false);
const editForm = reactive({ id: 0, title: '', body: '' });

onMounted(load);

function audienceLabel(audience?: string) {
  return audience === 'CONSUMER' ? '消费者' : audience === 'MERCHANT' ? '商户' : audience || '未知';
}

async function load() {
  loading.value = true;
  clearSelection();
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    const data = await api.request<{ items: NotificationRow[]; total: number }>(
      `/api/v2/ops/admin/growth/notifications?${q}`
    );
    list.value = data.items || [];
    total.value = Number(data.total) || 0;
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

function openSend() {
  sendForm.audience = 'CONSUMER';
  sendForm.userId = '';
  sendForm.merchantId = '';
  sendForm.title = '';
  sendForm.body = '';
  sendVisible.value = true;
}

function openEdit(row: NotificationRow) {
  editForm.id = row.id;
  editForm.title = row.title || '';
  editForm.body = row.body || '';
  editVisible.value = true;
}

async function doSend() {
  if (!sendForm.title.trim() || !sendForm.body.trim()) {
    ElMessage.warning('请填写标题与内容');
    return;
  }
  if (sendForm.audience === 'CONSUMER' && !sendForm.userId.trim()) {
    ElMessage.warning('请填写用户ID');
    return;
  }
  if (sendForm.audience === 'MERCHANT' && !sendForm.merchantId.trim()) {
    ElMessage.warning('请填写商户编号');
    return;
  }
  sending.value = true;
  try {
    await api.request('/api/v2/ops/admin/growth/notifications/send', 'POST', {
      audience: sendForm.audience,
      userId: sendForm.audience === 'CONSUMER' ? Number(sendForm.userId) : null,
      merchantId: sendForm.audience === 'MERCHANT' ? sendForm.merchantId.trim() : null,
      title: sendForm.title.trim(),
      body: sendForm.body.trim()
    });
    ElMessage.success('已发送');
    sendVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '发送失败');
  } finally {
    sending.value = false;
  }
}

async function doSaveEdit() {
  if (!editForm.title.trim() || !editForm.body.trim()) {
    ElMessage.warning('请填写标题与内容');
    return;
  }
  saving.value = true;
  try {
    await api.request(`/api/v2/ops/admin/growth/notifications/${editForm.id}`, 'PUT', {
      title: editForm.title.trim(),
      body: editForm.body.trim()
    });
    ElMessage.success('已更新');
    editVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeRow(row: NotificationRow) {
  try {
    await ElMessageBox.confirm(`确认删除消息 #${row.id}？`, '删除消息', { type: 'warning' });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/growth/notifications/${row.id}`, 'DELETE');
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function batchRemove() {
  const ids = selectedKeys.value.map(Number).filter((n) => Number.isFinite(n) && n > 0);
  if (!ids.length) return;
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${ids.length} 条消息？`, '批量删除', {
      type: 'warning'
    });
  } catch {
    return;
  }
  batchDeleting.value = true;
  try {
    const res = await api.request<{ deleted: number }>(
      '/api/v2/ops/admin/growth/notifications/batch-delete',
      'POST',
      { ids }
    );
    ElMessage.success(`已删除 ${res?.deleted ?? ids.length} 条`);
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '批量删除失败');
  } finally {
    batchDeleting.value = false;
  }
}

function formatTime(t?: string) {
  if (!t) return '暂无';
  const d = new Date(t);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(
    d.getMinutes()
  )}`;
}
</script>
