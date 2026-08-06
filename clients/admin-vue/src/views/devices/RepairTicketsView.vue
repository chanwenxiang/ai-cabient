<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">维修工单</span>
            <span class="hint">轻量工单流转 · 不含配件库存</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:repair:edit']" type="primary" @click="openCreate">新建工单</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="状态">
        <el-select v-model="status" clearable placeholder="全部" style="width: 140px" @change="search">
          <el-option
            v-for="item in statusOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="设备">
        <el-select
          v-model="deviceId"
          clearable
          filterable
          placeholder="选择设备"
          style="width: 200px"
          @change="search"
        >
          <el-option
            v-for="d in deviceOptions"
            :key="d.deviceId"
            :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
            :value="d.deviceId"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="priority" clearable placeholder="全部" style="width: 120px" @change="search">
          <el-option
            v-for="item in priorityOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="故障类型">
        <el-select v-model="faultType" clearable filterable placeholder="全部" style="width: 140px" @change="search">
          <el-option
            v-for="item in faultOptions"
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
      <el-table :data="displayRows"
        :default-sort="idDefaultSort"
        @sort-change="onIdSortChange" v-loading="loading" stripe border class="report-table" empty-text=" ">
        <template #empty><el-empty v-if="listHydrated && !loading" description="暂无维修工单" /></template>
        <el-table-column prop="ticketId" label="工单号" width="90" align="center" sortable="custom" />
        <el-table-column prop="deviceId" label="设备" min-width="130" align="center">
          <template #default="{ row }">
            <el-button
              v-if="canAccessPath('/devices')"
              link
              type="primary"
              @click="goPath(`/devices/${encodeURIComponent(row.deviceId)}`)"
            >
              {{ row.deviceId }}
            </el-button>
            <span v-else>{{ row.deviceId }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="title" label="标题" min-width="150" show-overflow-tooltip align="center" />
        <el-table-column prop="faultType" label="故障类型" width="110" align="center">
          <template #default="{ row }">{{ faultLabel(row.faultType) }}</template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="90" align="center">
          <template #default="{ row }">{{ priorityLabel(row.priority) }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100" align="center">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusLabel(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="负责人" width="110" show-overflow-tooltip align="center">
          <template #default="{ row }">{{ row.assignee || '无' }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="140" show-overflow-tooltip align="center">
          <template #default="{ row }">{{ row.remark || '无' }}</template>
        </el-table-column>
        <el-table-column label="创建人" width="100" align="center">
          <template #default="{ row }">{{ row.createdBy || '无' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.createdAt) || '无' }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.updatedAt) || '无' }}</template>
        </el-table-column>
        <el-table-column label="关闭时间" width="170" align="center">
          <template #default="{ row }">{{ formatDateTime(row.closedAt) || '无' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right" align="center" class-name="col-action">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <template v-if="auth.hasPerm('ops:repair:edit')">
              <el-button v-if="row.status === 'OPEN'" link type="warning" @click="transition(row, 'IN_PROGRESS')">开始处理</el-button>
              <el-button v-if="row.status === 'IN_PROGRESS'" link type="success" @click="transition(row, 'DONE')">完成</el-button>
              <el-button
                v-if="row.status === 'OPEN' || row.status === 'IN_PROGRESS'"
                link
                type="danger"
                @click="transition(row, 'CANCELLED')"
              >取消</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <PagePager :hydrated="listHydrated"
        v-model:current-page="page1"
        v-model:page-size="size"
        layout="total, prev, pager, next"
        :total="total"
        @current-change="load"
        @size-change="search"
      />

    <el-dialog v-model="createVisible" title="新建维修工单" width="560px" destroy-on-close>
      <el-form label-width="90px">
        <el-form-item label="设备" required>
          <el-select v-model="form.deviceId" filterable clearable placeholder="从设备列表选择" style="width: 100%">
            <el-option
              v-for="d in deviceOptions"
              :key="d.deviceId"
              :label="`${d.deviceName || d.deviceId}（${d.deviceId}）`"
              :value="d.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="标题" required>
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="故障类型">
          <el-select v-model="form.faultType" filterable allow-create clearable placeholder="选择或输入" style="width: 100%">
            <el-option
              v-for="item in faultOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="优先级">
          <el-select v-model="form.priority" style="width: 100%">
            <el-option
              v-for="item in priorityOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="负责人">
          <el-input v-model="form.assignee" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="create">创建</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="工单详情" size="480px">
      <div v-loading="!detailHydrated" class="repair-detail-pane">
        <template v-if="detail">
          <el-descriptions :column="1" border>
            <el-descriptions-item label="工单号">{{ detail.ticket.ticketId }}</el-descriptions-item>
            <el-descriptions-item label="设备">{{ detail.ticket.deviceId }}</el-descriptions-item>
            <el-descriptions-item label="标题">{{ detail.ticket.title }}</el-descriptions-item>
            <el-descriptions-item label="故障类型">{{ faultLabel(detail.ticket.faultType) }}</el-descriptions-item>
            <el-descriptions-item label="状态">{{ statusLabel(detail.ticket.status) }}</el-descriptions-item>
            <el-descriptions-item label="优先级">{{ priorityLabel(detail.ticket.priority) }}</el-descriptions-item>
            <el-descriptions-item label="负责人">{{ detail.ticket.assignee || '无' }}</el-descriptions-item>
            <el-descriptions-item label="创建人">{{ detail.ticket.createdBy || '无' }}</el-descriptions-item>
            <el-descriptions-item label="备注">{{ detail.ticket.remark || '无' }}</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ formatDateTime(detail.ticket.createdAt) }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ formatDateTime(detail.ticket.updatedAt) }}</el-descriptions-item>
            <el-descriptions-item label="关闭时间">{{ formatDateTime(detail.ticket.closedAt) }}</el-descriptions-item>
          </el-descriptions>
          <div class="event-title">流转记录</div>
          <el-timeline v-if="detail.events?.length">
            <el-timeline-item v-for="e in detail.events" :key="e.eventId" :timestamp="formatDateTime(e.createdAt)">
              {{ e.action }}：{{ e.fromStatus || '无' }} → {{ e.toStatus }}
              <span v-if="e.remark">（{{ e.remark }}）</span>
            </el-timeline-item>
          </el-timeline>
          <el-empty v-else-if="detailHydrated" description="暂无流转记录" :image-size="48" />
        </template>
        <el-empty v-else-if="detailHydrated" description="详情加载失败" :image-size="64" />
      </div>
    </el-drawer>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import PagePager from '@/components/PagePager.vue';
import { useRoute } from 'vue-router';
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { useAuthStore } from '@/stores/auth';
import { useNavAccess } from '@/composables/useNavAccess';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { dictLabel } from '@aicabinet/shared-dict';
import { useDictOptions } from '@/composables/useDictOptions';

interface Ticket {
  ticketId: number;
  deviceId: string;
  title: string;
  faultType?: string;
  status: string;
  assignee?: string;
  priority?: string;
  remark?: string;
  createdBy?: number;
  createdAt?: string;
  updatedAt?: string;
  closedAt?: string;
}

interface Detail {
  ticket: Ticket;
  events: Array<{
    eventId: number;
    fromStatus?: string;
    toStatus: string;
    action: string;
    remark?: string;
    createdAt?: string;
  }>;
}

interface DeviceOpt {
  deviceId: string;
  deviceName?: string;
}

const auth = useAuthStore();
const route = useRoute();
const { canAccessPath, goPath } = useNavAccess();
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const rows = ref<Ticket[]>([]);
const { defaultSort: idDefaultSort, onSortChange: onIdSortChange, sortById } = useIdColumnSort<Ticket>('ticketId');
const displayRows = computed(() => sortById(rows.value));
const total = ref(0);
const page1 = ref(1);
const size = ref(20);
const status = ref('');
const deviceId = ref(typeof route.query.deviceId === 'string' ? route.query.deviceId : '');
const priority = ref('');
const faultType = ref('');
const deviceOptions = ref<DeviceOpt[]>([]);
const createVisible = ref(false);
const detailVisible = ref(false);
const detailHydrated = ref(false);
const detail = ref<Detail | null>(null);
const form = reactive({
  deviceId: '',
  title: '',
  faultType: '',
  priority: 'NORMAL',
  assignee: '',
  remark: ''
});

const statusOptions = useDictOptions('repair_ticket_status');
const priorityOptions = useDictOptions('dispute_priority');
const faultOptions = useDictOptions('repair_fault_type');

function statusLabel(s?: string) {
  return dictLabel('repair_ticket_status', s) || s || '未知状态';
}
function statusType(s?: string) {
  return ({ OPEN: 'warning', IN_PROGRESS: '', DONE: 'success', CANCELLED: 'info' } as Record<string, string>)[s || ''] || '';
}
function priorityLabel(p?: string) {
  return dictLabel('dispute_priority', p) || p || '未知';
}
function faultLabel(f?: string) {
  return dictLabel('repair_fault_type', f) || f || '未知';
}

async function loadDevices() {
  try {
    const res = await api.request<{ items: DeviceOpt[] } | DeviceOpt[]>(
      '/api/v2/ops/admin/devices?page=0&size=200',
      'GET'
    );
    deviceOptions.value = Array.isArray(res) ? res : res.items || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function load() {
  loading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(page1.value - 1),
      size: String(size.value)
    });
    if (status.value) q.set('status', status.value);
    if (deviceId.value.trim()) q.set('deviceId', deviceId.value.trim());
    if (priority.value) q.set('priority', priority.value);
    const res = await api.request<{ items: Ticket[]; total: number }>(
      `/api/v2/ops/admin/repair-tickets?${q}`,
      'GET'
    );
    let items = res.items || [];
    if (faultType.value) {
      items = items.filter((t) => t.faultType === faultType.value);
    }
    rows.value = items;
    total.value = Number(res.total || 0);
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

function search() {
  page1.value = 1;
  load();
}

function openCreate() {
  form.deviceId = deviceId.value || '';
  form.title = '';
  form.faultType = '';
  form.priority = 'NORMAL';
  form.assignee = '';
  form.remark = '';
  createVisible.value = true;
  if (!deviceOptions.value.length) loadDevices();
}

async function create() {
  if (!form.deviceId || !form.title.trim()) {
    ElMessage.warning('请填写设备与标题');
    return;
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/repair-tickets', 'POST', { ...form });
    ElMessage.success('已创建');
    createVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '创建失败');
  } finally {
    saving.value = false;
  }
}

async function openDetail(row: Ticket) {
  // 切换工单才清空；同单重开保留壳 + hydrated 门控
  if (detail.value?.ticket?.ticketId !== row.ticketId) {
    detail.value = null;
    detailHydrated.value = false;
  }
  detailVisible.value = true;
  try {
    detail.value = await api.request<Detail>(`/api/v2/ops/admin/repair-tickets/${row.ticketId}`, 'GET');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载详情失败');
    if (!detailHydrated.value) detail.value = null;
  } finally {
    detailHydrated.value = true;
  }
}

async function transition(row: Ticket, next: string) {
  try {
    let remark: string | undefined;
    let unlockDevice = false;
    if (next === 'DONE') {
      try {
        await ElMessageBox.confirm(
          `工单 #${row.ticketId}（${row.deviceId}）标为完成。\n若柜机因故障/离线自动锁机，可同时解锁恢复售卖。`,
          '完成维修',
          {
            distinguishCancelAndClose: true,
            confirmButtonText: '完成并解锁',
            cancelButtonText: '仅完成',
            type: 'warning'
          }
        );
        unlockDevice = true;
      } catch (action) {
        if (action === 'close') return;
        unlockDevice = false;
      }
      const prompt = await ElMessageBox.prompt('可选备注', '完成维修', {
        inputPlaceholder: '备注',
        confirmButtonText: '确认',
        cancelButtonText: '取消'
      }).catch(() => null);
      if (prompt === null) return;
      remark = prompt.value || undefined;
    } else {
      const { value } = await ElMessageBox.prompt(`将工单流转为「${statusLabel(next)}」`, '状态流转', {
        inputPlaceholder: '可选备注',
        confirmButtonText: '确认'
      }).catch(() => ({ value: null as string | null }));
      if (value === null) return;
      remark = value || undefined;
    }
    await api.request(`/api/v2/ops/admin/repair-tickets/${row.ticketId}/transition`, 'POST', {
      status: next,
      remark,
      unlockDevice: unlockDevice ? 'true' : 'false'
    });
    ElMessage.success(unlockDevice ? '已完成并解锁' : '已更新');
    await load();
  } catch (e) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '流转失败');
    }
  }
}

onMounted(async () => {
  await loadDevices();
  await load();
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
.page-card-head__title { display: flex; flex-direction: column; gap: 4px; }
.title { font-weight: 600; font-size: 15px; }
.hint { font-size: 12px; color: var(--el-text-color-secondary); }
.page-pager { margin-top: 12px; display: flex; justify-content: flex-end; }
.event-title { margin: 16px 0 8px; font-weight: 600; }
.repair-detail-pane { min-height: 160px; }
</style>
