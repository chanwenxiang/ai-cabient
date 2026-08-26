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
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
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

    <el-table
      v-loading="loading"
      :data="displayItems"
      :default-sort="idDefaultSort"
      @sort-change="onIdSortChange"
      stripe
      border
      class="report-table"
      empty-text=" "
    >
      <template #empty
        ><el-empty v-if="listHydrated && !loading" description="暂无验证记录"
      /></template>
      <el-table-column prop="logId" label="记录ID" width="100" align="center" sortable="custom" />
      <el-table-column prop="phone" label="手机号" width="140" align="center" />
      <el-table-column prop="userId" label="用户ID" width="120" align="center" />
      <el-table-column prop="channel" label="渠道" width="120" align="center">
        <template #default="{ row }">{{ dictLabel('verify_channel', row.channel) }}</template>
      </el-table-column>
      <el-table-column
        prop="merchantId"
        label="商户"
        min-width="160"
        align="center"
        show-overflow-tooltip
      >
        <template #default="{ row }">{{
          row.merchantId
            ? row.merchantName
              ? `${row.merchantName}（${row.merchantId}）`
              : row.merchantId
            : '—'
        }}</template>
      </el-table-column>
      <el-table-column label="验证时间" width="170" align="center">
        <template #default="{ row }">{{
          String(row.verifiedAt || '')
            .replace('T', ' ')
            .slice(0, 19)
        }}</template>
      </el-table-column>
      <el-table-column label="操作" width="140" align="center">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link type="danger" @click="removeRow(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>

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

    <el-dialog
      v-model="dlg"
      :title="editingId ? '编辑手机验证' : '登记手机验证'"
      width="420px"
      destroy-on-close
    >
      <el-form label-width="88px">
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
import { computed, onMounted, reactive, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictLabel, dictOptions } from '@aicabinet/shared-dict';
import { api } from '@/api/client';
import PagePager from '@/components/PagePager.vue';
import { useIdColumnSort } from '@/composables/useIdColumnSort';

const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const phone = ref('');
const channel = ref('');
const page = ref(1);
const size = ref(20);
const total = ref(0);
const items = ref<any[]>([]);
const {
  defaultSort: idDefaultSort,
  onSortChange: onIdSortChange,
  sortById
} = useIdColumnSort('logId');
const displayItems = computed(() => sortById(items.value));
const dlg = ref(false);
const editingId = ref<number | null>(null);
const form = reactive({ phone: '', userId: '', channel: 'SMS', merchantId: '' });

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page.value - 1),
      size: String(size.value)
    });
    if (phone.value) q.set('phone', phone.value);
    if (channel.value) q.set('channel', channel.value);
    const data = await api.request<{ items: any[]; total: number }>(
      `/api/v2/ops/admin/phone-verify/logs?${q}`,
      'GET'
    );
    items.value = data.items || [];
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

function search() {
  page.value = 1;
  load();
}

function openCreate() {
  editingId.value = null;
  form.phone = '';
  form.userId = '';
  form.channel = 'SMS';
  form.merchantId = '';
  dlg.value = true;
}

function openEdit(row: any) {
  editingId.value = row.logId;
  form.phone = row.phone || '';
  form.userId = row.userId != null ? String(row.userId) : '';
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
      await api.request(`/api/v2/ops/admin/phone-verify/logs/${editingId.value}`, 'PUT', body);
      ElMessage.success('已更新');
    } else {
      await api.request('/api/v2/ops/admin/phone-verify/logs', 'POST', body);
      ElMessage.success('已登记');
    }
    dlg.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function removeRow(row: any) {
  try {
    await ElMessageBox.confirm(`确认删除验证记录 #${row.logId}？`, '删除记录', {
      type: 'warning'
    });
  } catch {
    return;
  }
  try {
    await api.request(`/api/v2/ops/admin/phone-verify/logs/${row.logId}`, 'DELETE');
    ElMessage.success('已删除');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '删除失败');
  }
}

onMounted(load);
</script>
