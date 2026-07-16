<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">对账</span>
        <div class="actions">
          <el-button v-if="canRun" type="primary" @click="runDialog = true">执行对账</el-button>
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar">
      <el-form-item label="渠道">
        <el-select v-model="channel" clearable style="width: 140px" @change="load">
          <el-option
            v-for="item in dictOptions('pay_channel')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="statusFilter" clearable style="width: 140px" @change="load">
          <el-option
            v-for="item in dictOptions('reconciliation_status')"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="load">查询</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner" style="min-width: 900px">
        <el-table v-loading="loading" :data="items" stripe border>
      <el-table-column prop="reconId" label="对账ID" min-width="120" />
      <el-table-column prop="reconDate" label="日期" width="120" />
      <el-table-column label="渠道" width="100">
        <template #default="{ row }">{{ dictLabel('pay_channel', row.channel) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="120">
        <template #default="{ row }">
          <el-tag :type="dictTagType(row.status)" size="small">
            {{ dictLabel('reconciliation_status', row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="差异笔数" width="100">
        <template #default="{ row }">{{ row.mismatchCount ?? 0 }}</template>
      </el-table-column>
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="88" class-name="col-action" align="center">
        <template #default="{ row }">
          <TableActions
            :actions="[{ key: 'detail', label: '详情', icon: View, type: 'primary' }]"
            @action="() => openDetail(row)"
          />
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无对账记录" /></template>
        </el-table>
      </div>
    </div>

    <el-dialog v-model="runDialog" title="执行对账" width="420px" destroy-on-close>
      <el-form label-width="80px">
        <el-form-item label="日期" required>
          <input v-model="runForm.date" class="native-date" type="date" />
        </el-form-item>
        <el-form-item label="渠道">
          <el-select v-model="runForm.channel" style="width: 100%">
            <el-option
              v-for="item in dictOptions('pay_channel')"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="runDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="runRecon">执行</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailOpen" title="对账详情" size="520px">
      <template v-if="detail">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="对账ID">{{ detail.summary?.reconId }}</el-descriptions-item>
          <el-descriptions-item label="日期">{{ detail.summary?.reconDate }}</el-descriptions-item>
          <el-descriptions-item label="渠道">
            {{ dictLabel('pay_channel', detail.summary?.channel) }}
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            {{ dictLabel('reconciliation_status', detail.summary?.status) }}
          </el-descriptions-item>
          <el-descriptions-item label="差异笔数">
            {{ detail.summary?.mismatchCount ?? 0 }}
          </el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.lines || []" stripe style="margin-top: 16px" max-height="360">
          <el-table-column prop="platformTradeNo" label="平台流水" min-width="140" show-overflow-tooltip />
          <el-table-column prop="merchantOrderNo" label="商户单号" min-width="120" show-overflow-tooltip />
          <el-table-column label="金额" width="100">
            <template #default="{ row }">¥{{ ((row.amountCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="匹配" width="80">
            <template #default="{ row }">{{ row.matched ? '是' : '否' }}</template>
          </el-table-column>
          <template #empty><el-empty description="无明细行" :image-size="48" /></template>
        </el-table>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { Refresh, View } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictOptions, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const auth = useAuthStore();
const canRun = computed(() => auth.hasPerm('ops:reconciliation:run'));

const loading = ref(false);
const saving = ref(false);
const channel = ref('');
const statusFilter = ref('');
const items = ref<Row[]>([]);
const runDialog = ref(false);
const detailOpen = ref(false);
const detail = ref<Row | null>(null);
const runForm = reactive({ date: '', channel: 'WECHAT' });

const { onExport } = useListCsv({
  filePrefix: '对账',
  headers: ['对账ID', '日期', '渠道', '状态', '差异笔数', '创建时间'],
  toRows: () =>
    items.value.map((row) => [
      row.reconId,
      row.reconDate || '',
      dictLabel('pay_channel', row.channel),
      dictLabel('reconciliation_status', row.status),
      row.mismatchCount ?? 0,
      formatDateTime(row.createdAt)
    ])
});

function localDate() {
  const now = new Date();
  return new Date(now.getTime() - now.getTimezoneOffset() * 60000).toISOString().slice(0, 10);
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams();
    if (channel.value) q.set('channel', channel.value);
    const path = q.toString()
      ? `/api/v2/ops/admin/reconciliation?${q}`
      : '/api/v2/ops/admin/reconciliation?page=0&size=50';
    items.value = (await api.request<Row[]>(path, 'GET')).filter((row) =>
      statusFilter.value ? row.status === statusFilter.value : true
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    loading.value = false;
  }
}

async function runRecon() {
  if (!runForm.date) {
    runForm.date = localDate();
  }
  saving.value = true;
  try {
    const q = new URLSearchParams({
      date: runForm.date,
      channel: runForm.channel || 'WECHAT'
    });
    await api.request(`/api/v2/ops/admin/reconciliation/run?${q}`, 'POST');
    runDialog.value = false;
    ElMessage.success('对账已执行');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '执行失败');
  } finally {
    saving.value = false;
  }
}

async function openDetail(row: Row) {
  try {
    detail.value = await api.request<Row>(`/api/v2/ops/admin/reconciliation/${row.reconId}`, 'GET');
    detailOpen.value = true;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '详情加载失败');
  }
}

onMounted(() => {
  runForm.date = localDate();
  if (typeof route.query.status === 'string') statusFilter.value = route.query.status;
  load();
});
onActivated(() => {
  if (typeof route.query.status === 'string' && route.query.status !== statusFilter.value) {
    statusFilter.value = route.query.status;
    load();
  }
});
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; }
.filter-bar { margin-bottom: 8px; }
.native-date {
  width: 100%; height: 32px; padding: 0 10px; border: 1px solid var(--layout-border);
  border-radius: 4px; color: var(--layout-text); background: var(--layout-card); box-sizing: border-box;
}
</style>
