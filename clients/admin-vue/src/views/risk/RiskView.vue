<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">风控</span>
            <span class="hint">风险事件与黑名单；按 Tab 切换</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-if="canBlacklist && tab === 'blacklist'" type="primary" @click="openAdd">
            加入黑名单
          </el-button>
          <el-button v-hasPermi="['ops:risk:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            :icon="Refresh"
            :loading="tab === 'blacklist' ? blacklistLoading : eventsLoading"
            @click="reloadCurrent"
            >刷新</el-button
          >
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="风险事件" name="events">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="eventsLoading"
              :data="events"
              stripe
              border
              class="report-table"
              empty-text=" "
              row-key="eventId"
              @selection-change="onEventsSelectionChange"
            >
              <template #empty
                ><el-empty v-if="eventsHydrated && !eventsLoading" description="暂无风险事件"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="事件" min-width="140" align="center" class-name="col-text">
                <template #default="{ row }">
                  <div class="id-cell">
                    <strong>{{
                      dictLabel('risk_event_type', row.eventType) || row.eventType || '未知'
                    }}</strong>
                    <small>{{ row.eventId }}</small>
                  </div>
                </template>
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
              <el-table-column label="级别" width="100" align="center">
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.severity)" size="small">
                    {{ dictLabel('risk_severity', row.severity) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="168" align="center" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
        <PagePager
          :hydrated="eventsHydrated"
          v-model:current-page="eventPage"
          v-model:page-size="eventSize"
          :total="eventTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @current-change="loadEvents"
          @size-change="onEventSizeChange"
        />
      </el-tab-pane>

      <el-tab-pane label="黑名单" name="blacklist">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <el-table
              v-loading="blacklistLoading"
              :data="blacklist"
              stripe
              border
              class="report-table"
              empty-text=" "
              row-key="userId"
              @selection-change="onBlacklistSelectionChange"
            >
              <template #empty
                ><el-empty v-if="blacklistHydrated && !blacklistLoading" description="暂无黑名单"
              /></template>
              <el-table-column type="selection" width="48" align="center" />
              <el-table-column label="用户" width="120" align="center" class-name="col-text">
                <template #default="{ row }">
                  <button
                    type="button"
                    class="link-cell"
                    @click="goPath('/users', { keyword: String(row.userId) })"
                  >
                    {{ row.userId }}
                  </button>
                </template>
              </el-table-column>
              <el-table-column
                label="原因"
                min-width="220"
                align="center"
                class-name="col-text"
                show-overflow-tooltip
              >
                <template #default="{ row }">{{ row.reason || '无' }}</template>
              </el-table-column>
              <el-table-column label="加入时间" width="168" align="center" class-name="col-text">
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
              <el-table-column
                v-if="canBlacklist"
                label="操作"
                width="88"
                class-name="col-action"
                align="center"
              >
                <template #default="{ row }">
                  <TableActions
                    :actions="[{ key: 'remove', label: '移出', icon: Delete, type: 'danger' }]"
                    @action="() => removeBlacklist(row)"
                  />
                </template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="addDialog" title="加入黑名单" width="440px" destroy-on-close>
      <el-form label-width="88px">
        <el-form-item label="用户 ID" required>
          <el-input-number
            v-model="addForm.userId"
            :min="1"
            :precision="0"
            controls-position="right"
            style="width: 100%"
          />
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
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { Delete, Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import TableActions from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import type { PageResult } from '@aicabinet/shared-types';
import { dictLabel, dictTagType } from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';

type Row = Record<string, any>;
const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const canBlacklist = computed(() => auth.hasPerm('ops:risk:blacklist'));

const eventsLoading = ref(false);
const blacklistLoading = ref(false);
const eventsHydrated = ref(false);
const blacklistHydrated = ref(false);
const saving = ref(false);
const tab = ref('events');
const events = ref<Row[]>([]);
const blacklist = ref<Row[]>([]);
const eventPage = ref(1);
const eventSize = ref(20);
const eventTotal = ref(0);
const loaded = ref(new Set<string>(['events']));
const addDialog = ref(false);
const addForm = reactive({ userId: 1, reason: '' });

const {
  onSelectionChange: onEventsSelectionChange,
  pickSelected: pickEvents,
  exportButtonLabel: eventsExportLabel,
  clearSelection: clearEventsSelection
} = useTableSelection<Row>((r) => r.eventId);

const {
  onSelectionChange: onBlacklistSelectionChange,
  pickSelected: pickBlacklist,
  exportButtonLabel: blacklistExportLabel,
  clearSelection: clearBlacklistSelection
} = useTableSelection<Row>((r) => r.userId);

const exportButtonLabel = computed(() =>
  tab.value === 'blacklist' ? blacklistExportLabel.value : eventsExportLabel.value
);

const { onExport: exportEvents } = useListCsv({
  filePrefix: '风险事件',
  headers: ['事件ID', '用户', '类型', '级别', '时间'],
  toRows: () =>
    pickEvents(events.value).map((row) => [
      row.eventId,
      row.userId,
      dictLabel('risk_event_type', row.eventType),
      dictLabel('risk_severity', row.severity),
      formatDateTime(row.createdAt)
    ])
});

const { onExport: exportBlacklist } = useListCsv({
  filePrefix: '黑名单',
  headers: ['用户ID', '原因', '加入时间'],
  toRows: () =>
    pickBlacklist(blacklist.value).map((row) => [
      row.userId,
      row.reason || '',
      formatDateTime(row.createdAt)
    ])
});

async function onExport() {
  if (tab.value === 'blacklist') {
    const selected = pickBlacklist(blacklist.value);
    if (selected.length && selected.length < blacklist.value.length) {
      exportBlacklist();
      return;
    }
    try {
      await downloadAuthFile('/api/v2/ops/admin/risk/blacklist/export', csvFileName('黑名单'));
      ElMessage.success('已导出');
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '导出失败');
    }
    return;
  }
  const selected = pickEvents(events.value);
  if (selected.length && selected.length < events.value.length) {
    exportEvents();
    return;
  }
  try {
    await downloadAuthFile('/api/v2/ops/admin/risk/events/export', csvFileName('风险事件'));
    ElMessage.success('已导出');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '导出失败');
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (tab.value && tab.value !== 'events') query.tab = tab.value;
  router.replace({ query });
}

function applyRouteQuery() {
  const qTab = typeof route.query.tab === 'string' ? route.query.tab : '';
  const next = qTab === 'blacklist' ? 'blacklist' : 'events';
  if (next !== tab.value) {
    tab.value = next;
    return true;
  }
  return false;
}

async function loadEvents() {
  eventsLoading.value = true;
  try {
    const q = new URLSearchParams({
      page: String(Math.max(0, eventPage.value - 1)),
      size: String(eventSize.value)
    });
    const ev = await api.request<PageResult<Row> | Row[]>(
      `/api/v2/ops/admin/risk/events?${q}`,
      'GET'
    );
    if (Array.isArray(ev)) {
      events.value = ev;
      eventTotal.value = ev.length;
    } else {
      events.value = ev?.items || [];
      eventTotal.value = ev?.total ?? events.value.length;
    }
    clearEventsSelection();
    loaded.value.add('events');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '风险事件加载失败');
  } finally {
    eventsHydrated.value = true;
    eventsLoading.value = false;
  }
}

function onEventSizeChange() {
  eventPage.value = 1;
  loadEvents();
}

async function loadBlacklist() {
  if (!canBlacklist.value) {
    blacklist.value = [];
    blacklistHydrated.value = true;
    ElMessage.warning('当前账号无黑名单权限');
    return;
  }
  blacklistLoading.value = true;
  try {
    blacklist.value = await api.request<Row[]>('/api/v2/ops/admin/risk/blacklist', 'GET');
    clearBlacklistSelection();
    loaded.value.add('blacklist');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '黑名单加载失败');
  } finally {
    blacklistHydrated.value = true;
    blacklistLoading.value = false;
  }
}

function onTabChange(name: string | number) {
  const key = String(name);
  syncRouteQuery();
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

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  if (tab.value === 'blacklist') await loadBlacklist();
  else await loadEvents();
}

watch(
  () => route.query.tab,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  if (tab.value === 'blacklist') loadBlacklist();
  else loadEvents();
});
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
  flex-wrap: wrap;
}
.id-cell {
  display: grid;
  gap: 2px;
  line-height: 1.35;
}
.id-cell strong {
  font-weight: 650;
}
.id-cell small {
  color: var(--el-text-color-secondary);
  font-family: inherit;
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
  color: var(--el-text-color-secondary);
}
</style>
