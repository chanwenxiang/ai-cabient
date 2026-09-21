<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">手机验证流水</span>
            <span class="hint">用户手机号验证审计，服务客诉与风控</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button type="primary" @click="openCreate">登记验证</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact">
      <el-form-item label="手机号">
        <el-input v-model="phone" clearable placeholder="模糊搜索" style="width: 160px" />
      </el-form-item>
      <el-form-item label="渠道">
        <el-select v-model="channel" clearable placeholder="全部" style="width: 140px">
          <el-option
            v-for="item in dictOptions('verify_channel')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="logId"
          selectable
          :actions="rowActions"
          :action-width="140"
          actions-testid="phone-verify"
          empty-text="暂无验证记录"
          sort-field-label="记录ID"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column
            prop="logId"
            label="记录ID"
            width="100"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            prop="phone"
            label="手机号"
            width="140"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          />
          <el-table-column
            prop="userId"
            label="用户ID"
            width="120"
            class-name="col-text"
            label-class-name="col-text"
          />
          <el-table-column
            prop="channel"
            label="渠道"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ dictLabel('verify_channel', row.channel) }}</template>
          </el-table-column>
          <el-table-column
            prop="merchantId"
            label="商户"
            min-width="160"
            class-name="col-text"
            label-class-name="col-text"
          >
            <template #default="{ row }">{{ merchantCell(row) }}</template>
          </el-table-column>
          <el-table-column
            label="验证时间"
            width="170"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ formatVerifiedAt(row.verifiedAt) }}</template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog v-model="dlg" :title="editingId ? '编辑手机验证' : '登记手机验证'" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="手机号" required>
          <el-input v-model="form.phone" />
        </el-form-item>
        <el-form-item label="用户ID">
          <el-input v-model="form.userId" />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="form.channel" style="width: 100%">
            <el-option
              v-for="item in dictOptions('verify_channel')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商户编号">
          <el-input v-model="form.merchantId" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dlg = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue';
import { Delete, Edit } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';

interface PhoneVerifyRow {
  logId: number;
  phone?: string;
  userId?: number | null;
  channel?: string;
  merchantId?: string | null;
  merchantName?: string | null;
  verifiedAt?: string;
}

const saving = ref(false);
const phone = ref('');
const channel = ref('');

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 / 刷新 全部内建
const crud = useCrudTable<PhoneVerifyRow>({
  rowKey: (r) => r.logId,
  fetchPage: (params) => {
    const q = new URLSearchParams({
      page: String(params.page),
      size: String(params.size)
    });
    if (phone.value) q.set('phone', phone.value);
    if (channel.value) q.set('channel', channel.value);
    return api.request<{ items: PhoneVerifyRow[]; total: number }>(
      AdminEndpoints.phoneVerifyLogsList(q),
      'GET'
    );
  },
  // 记录ID 本地排序：默认升序，方向由壳内「升/降序」按钮切换
  sort: { prop: 'logId', mode: 'local' }
});

// 导出移入 CrudTable 内建工具条；勾选行时仅导出选中（原 pickSelected 语义）
const csvOptions: CrudCsvOptions = {
  filePrefix: '手机验证流水',
  exportPerm: 'ops:phone-verify:list',
  headers: ['记录ID', '手机号', '用户ID', '渠道', '商户', '验证时间'],
  toRows: (rows) =>
    rows.map((row) => [
      row.logId,
      row.phone || '',
      row.userId ?? '',
      dictLabel('verify_channel', row.channel),
      merchantCell(row),
      formatVerifiedAt(row.verifiedAt)
    ])
};

function rowActions(_row: PhoneVerifyRow): CrudRowAction[] {
  return [
    { key: 'edit', label: '编辑', icon: Edit, type: 'primary' },
    { key: 'delete', label: '删除', icon: Delete, type: 'danger' }
  ];
}

function onAction({ key, row }: { key: string; row: PhoneVerifyRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') void removeRow(row);
}

const dlg = ref(false);
const editingId = ref<number | null>(null);
const form = reactive({ phone: '', userId: '', channel: 'SMS', merchantId: '' });

function merchantCell(row: PhoneVerifyRow) {
  if (!row.merchantId) return '—';
  return row.merchantName ? `${row.merchantName}（${row.merchantId}）` : row.merchantId;
}

function formatVerifiedAt(value?: string) {
  return String(value || '')
    .replace('T', ' ')
    .slice(0, 19);
}

function search() {
  void crud.search();
}

function openCreate() {
  editingId.value = null;
  form.phone = '';
  form.userId = '';
  form.channel = 'SMS';
  form.merchantId = '';
  dlg.value = true;
}

function openEdit(row: PhoneVerifyRow) {
  editingId.value = row.logId;
  form.phone = row.phone || '';
  form.userId = row.userId == null ? '' : String(row.userId);
  form.channel = row.channel || 'SMS';
  form.merchantId = row.merchantId || '';
  dlg.value = true;
}

async function save() {
  saving.value = true;
  try {
    const body = {
      phone: form.phone,
      userId: form.userId ? Number(form.userId) : null,
      channel: form.channel,
      merchantId: form.merchantId || null
    };
    if (editingId.value) {
      await api.request(AdminEndpoints.phoneVerifyLog(editingId.value), 'PUT', body);
      ElMessage.success('已更新');
    } else {
      await api.request(AdminEndpoints.phoneVerifyLogs, 'POST', body);
      ElMessage.success('已登记');
    }
    dlg.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeRow(row: PhoneVerifyRow) {
  try {
    await ElMessageBox.confirm(`确认删除验证记录 #${row.logId}？`, '删除记录', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(AdminEndpoints.phoneVerifyLog(row.logId), 'DELETE');
    ElMessage.success('已删除');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}
</script>
