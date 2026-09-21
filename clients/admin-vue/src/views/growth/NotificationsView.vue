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
          <el-button
            v-hasPermi="['ops:notify:list']"
            type="danger"
            plain
            :disabled="!crud.hasSelection"
            :loading="batchDeleting"
            @click="batchRemove"
            >删除选中</el-button
          >
          <el-button v-hasPermi="['ops:notify:list']" type="primary" @click="openSend"
            >发送站内信</el-button
          >
        </div>
      </div>
    </template>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="id"
          selectable
          :actions="rowActions"
          :action-width="140"
          :actions-testid="'notify'"
          empty-text="暂无消息记录"
          sort-field-label="ID"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="id" label="ID" width="80" class-name="col-text" />
          <el-table-column
            label="时间"
            width="150"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column
            label="受众"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="row.audience === 'CONSUMER' ? 'primary' : 'warning'">{{
                audienceLabel(row.audience)
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column
            prop="title"
            label="标题"
            min-width="150"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column label="内容" min-width="240" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="rewriteBizNosInText(row.body) || ''">{{
                rewriteBizNosInText(row.body)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column label="业务" width="100" class-name="col-text">
            <template #default="{ row }">
              <span
                class="cell-ellipsis"
                :title="dictLabel('notification_biz_type', row.bizType) || ''"
                >{{ dictLabel('notification_biz_type', row.bizType) }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="关联单号" width="150" class-name="col-text">
            <template #default="{ row }">{{ displayBizNo(row.bizId, '无') }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog v-model="sendVisible" title="发送站内信" destroy-on-close>
      <el-form label-width="auto">
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

    <el-dialog v-model="editVisible" title="编辑站内信" destroy-on-close>
      <el-form label-width="auto">
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
import { reactive, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Delete, Edit } from '@element-plus/icons-vue';
import { dictLabel } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { displayBizNo, rewriteBizNosInText } from '@aicabinet/shared-uni/format';

type NotificationRow = {
  id: number;
  title: string;
  body: string;
  audience?: string;
  bizType?: string;
  bizId?: string;
  createdAt: string;
};

const sending = ref(false);
const saving = ref(false);
const batchDeleting = ref(false);

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<NotificationRow>({
  rowKey: (r) => r.id,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    return api.request<{ items: NotificationRow[]; total: number }>(
      AdminEndpoints.growthNotificationsList(q)
    );
  },
  sort: { prop: 'id', mode: 'local' }
});

const csvOptions: CrudCsvOptions = {
  filePrefix: '消息记录',
  exportPerm: 'ops:notify:list',
  headers: ['ID', '时间', '受众', '标题', '内容', '业务', '关联单号'],
  toRows: (rows) =>
    rows.map((row) => [
      row.id,
      formatTime(row.createdAt),
      audienceLabel(row.audience),
      row.title || '',
      rewriteBizNosInText(row.body || ''),
      dictLabel('notification_biz_type', row.bizType),
      displayBizNo(row.bizId, '无')
    ])
};

function rowActions(_row: NotificationRow): CrudRowAction[] {
  return [
    { key: 'edit', label: '编辑', icon: Edit, type: 'primary', perm: 'ops:notify:list' },
    { key: 'delete', label: '删除', icon: Delete, type: 'danger', perm: 'ops:notify:list' }
  ];
}

function onAction({ key, row }: { key: string; row: NotificationRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') void removeRow(row);
}

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

function audienceLabel(audience?: string) {
  return audience === 'CONSUMER' ? '消费者' : audience === 'MERCHANT' ? '商户' : audience || '未知';
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
    await api.request(AdminEndpoints.growthNotificationsSend, 'POST', {
      audience: sendForm.audience,
      userId: sendForm.audience === 'CONSUMER' ? Number(sendForm.userId) : null,
      merchantId: sendForm.audience === 'MERCHANT' ? sendForm.merchantId.trim() : null,
      title: sendForm.title.trim(),
      body: sendForm.body.trim()
    });
    ElMessage.success('已发送');
    sendVisible.value = false;
    await crud.load();
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
    await api.request(AdminEndpoints.growthNotification(editForm.id), 'PUT', {
      title: editForm.title.trim(),
      body: editForm.body.trim()
    });
    ElMessage.success('已更新');
    editVisible.value = false;
    await crud.load();
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
    await api.request(AdminEndpoints.growthNotification(row.id), 'DELETE');
    ElMessage.success('已删除');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

async function batchRemove() {
  const ids = crud.selectedKeys.map(Number).filter((n) => Number.isFinite(n) && n > 0);
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
      AdminEndpoints.growthNotificationsBatchDelete,
      'POST',
      { ids }
    );
    ElMessage.success(`已删除 ${res?.deleted ?? ids.length} 条`);
    await crud.load();
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
