<template>
  <el-card class="page-card" shadow="never">
    <template #header>
      <div class="card-head">
        <span class="title">风控</span>
        <div class="actions">
          <el-button
            v-if="canBlacklist && tab === 'blacklist'"
            type="primary"
            @click="openAdd"
          >加入黑名单</el-button>
          <el-button @click="onExport">导出</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="reloadCurrent">刷新</el-button>
        </div>
      </div>
    </template>
    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="风险事件" name="events">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="events" stripe border>
          <el-table-column prop="eventId" label="事件ID" min-width="120" />
          <el-table-column prop="userId" label="用户" width="100" />
          <el-table-column label="类型" min-width="140">
            <template #default="{ row }">{{ dictLabel('risk_event_type', row.eventType) }}</template>
          </el-table-column>
          <el-table-column label="级别" width="100">
            <template #default="{ row }">
              <el-tag :type="dictTagType(row.severity)" size="small">
                {{ dictLabel('exception_severity', row.severity) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <template #empty><el-empty description="暂无风险事件" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
      <el-tab-pane label="黑名单" name="blacklist">
        <div class="table-scroll">
          <div class="table-scroll-inner" style="min-width: 900px">
            <el-table v-loading="loading" :data="blacklist" stripe border>
          <el-table-column prop="userId" label="用户ID" width="120" />
          <el-table-column prop="reason" label="原因" min-width="200" show-overflow-tooltip />
          <el-table-column label="加入时间" width="180">
            <template #default="{ row }">{{ formatDateTime(row.createdAt) }}</template>
          </el-table-column>
          <el-table-column v-if="canBlacklist" label="操作" width="88" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="[{ key: 'remove', label: '移出', icon: Delete, type: 'danger' }]"
                @action="() => removeBlacklist(row)"
              />
            </template>
          </el-table-column>
          <template #empty><el-empty description="暂无黑名单" /></template>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="addDialog" title="加入黑名单" width="440px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="用户 ID" required>
          <el-input-number v-model="addForm.userId" :min="1" :precision="0" controls-position="right" style="width: 100%" />
        </el-form-item>
        <el-form-item label="原因" required>
          <el-input v-model="addForm.reason" type="textarea" maxlength="200" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveBlacklist">确认</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { Delete, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useAuthStore } from '@/stores/auth';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const auth = useAuthStore();
const canBlacklist = computed(() => auth.hasPerm('ops:risk:blacklist'));

const loading = ref(false);
const saving = ref(false);
const tab = ref('events');
const events = ref<Row[]>([]);
const blacklist = ref<Row[]>([]);
const loaded = ref(new Set<string>(['events']));
const addDialog = ref(false);
const addForm = reactive({ userId: 1, reason: '' });

const { onExport: exportEvents } = useListCsv({
  filePrefix: '风险事件',
  headers: ['事件ID', '用户', '类型', '级别', '时间'],
  toRows: () =>
    events.value.map((row) => [
      row.eventId,
      row.userId,
      dictLabel('risk_event_type', row.eventType),
      dictLabel('exception_severity', row.severity),
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportBlacklist } = useListCsv({
  filePrefix: '黑名单',
  headers: ['用户ID', '原因', '加入时间'],
  toRows: () =>
    blacklist.value.map((row) => [row.userId, row.reason || '', formatDateTime(row.createdAt)])
});

function onExport() {
  if (tab.value === 'blacklist') exportBlacklist();
  else exportEvents();
}

async function loadEvents() {
  loading.value = true;
  try {
    const ev = await api.request<{ items?: Row[] } | Row[]>(
      '/api/v2/ops/admin/risk/events?page=0&size=50',
      'GET'
    );
    events.value = Array.isArray(ev) ? ev : ev?.items || [];
    loaded.value.add('events');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '风险事件加载失败');
  } finally {
    loading.value = false;
  }
}

async function loadBlacklist() {
  if (!canBlacklist.value) {
    blacklist.value = [];
    ElMessage.warning('当前账号无黑名单权限');
    return;
  }
  loading.value = true;
  try {
    blacklist.value = await api.request<Row[]>('/api/v2/ops/admin/risk/blacklist', 'GET');
    loaded.value.add('blacklist');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '黑名单加载失败');
  } finally {
    loading.value = false;
  }
}

function onTabChange(name: string | number) {
  const key = String(name);
  if (!loaded.value.has(key)) {
    if (key === 'blacklist') loadBlacklist();
    else loadEvents();
  }
}

function reloadCurrent() {
  loaded.value.delete(tab.value);
  if (tab.value === 'blacklist') loadBlacklist();
  else loadEvents();
}

function openAdd() {
  Object.assign(addForm, { userId: 1, reason: '' });
  addDialog.value = true;
}

async function saveBlacklist() {
  if (!addForm.userId || !addForm.reason.trim()) {
    return ElMessage.warning('请填写用户 ID 和原因');
  }
  saving.value = true;
  try {
    await api.request('/api/v2/ops/admin/risk/blacklist', 'POST', {
      userId: addForm.userId,
      reason: addForm.reason.trim()
    });
    addDialog.value = false;
    ElMessage.success('已加入黑名单');
    loaded.value.delete('blacklist');
    await loadBlacklist();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    saving.value = false;
  }
}

async function removeBlacklist(row: Row) {
  try {
    await ElMessageBox.confirm(`确认将用户 ${row.userId} 移出黑名单？`, '移出黑名单', {
      type: 'warning'
    });
    await api.request(`/api/v2/ops/admin/risk/blacklist/${row.userId}`, 'DELETE');
    ElMessage.success('已移出');
    await loadBlacklist();
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '操作失败');
    }
  }
}

onMounted(loadEvents);
</script>

<style scoped>
.card-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.title { font-weight: 600; }
.actions { display: flex; gap: 8px; }
</style>
