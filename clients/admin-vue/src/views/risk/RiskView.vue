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
          <!-- 后端全量导出保留页头；部分选中时的前端 CSV 回退逻辑不变 -->
          <el-button v-hasPermi="['ops:risk:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
        </div>
      </div>
    </template>

    <el-tabs v-model="tab" @tab-change="onTabChange">
      <el-tab-pane label="风险事件" name="events">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable :table="eventsCrud" row-key="eventId" selectable empty-text="暂无风险事件">
              <el-table-column label="事件" min-width="140" class-name="col-text">
                <template #default="{ row }">
                  <div class="id-cell">
                    <strong>{{ displayLabel('risk_event_type', row.eventType, '未知') }}</strong>
                    <small>{{ row.eventId }}</small>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="用户" width="100" class-name="col-text">
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
                min-width="110"
                class-name="col-text"
                label-class-name="col-text"
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
              <el-table-column label="详情" min-width="160" class-name="col-text">
                <template #default="{ row }">{{ formatRiskEventDetail(row.detail) }}</template>
              </el-table-column>
              <el-table-column
                label="级别"
                width="100"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <el-tag :type="dictTagType(row.severity)" size="small">
                    {{ dictLabel('risk_severity', row.severity) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                label="处置"
                width="120"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <el-tag size="small" :type="dispositionTag(row.dispositionStatus)">
                    {{ dispositionLabel(row.dispositionStatus) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column
                label="处置备注"
                min-width="120"
                class-name="col-text"
                label-class-name="col-text"
              >
                <template #default="{ row }">{{ row.dispositionNote || '暂无' }}</template>
              </el-table-column>
              <el-table-column
                align="center"
                label="处置时间"
                width="150"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span v-if="row.dispositionAt" class="cell-datetime">{{
                    formatDateTime(row.dispositionAt)
                  }}</span>
                  <span v-else class="muted">暂无</span>
                </template>
              </el-table-column>
              <el-table-column
                align="center"
                label="时间"
                width="168"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </el-tab-pane>

      <el-tab-pane label="黑名单" name="blacklist">
        <div class="table-scroll">
          <div class="table-scroll-inner">
            <CrudTable
              :table="blacklistCrud"
              row-key="userId"
              selectable
              empty-text="暂无黑名单"
              :actions="canBlacklist ? blacklistActions : undefined"
              :action-width="88"
              actions-testid="risk-blacklist"
              @action="onBlacklistAction"
            >
              <el-table-column label="用户" width="120" class-name="col-text">
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
              <el-table-column label="原因" min-width="180" class-name="col-text">
                <template #default="{ row }">{{ row.reason || '无' }}</template>
              </el-table-column>
              <el-table-column
                label="来源"
                width="100"
                align="center"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">{{ row.source || '暂无' }}</template>
              </el-table-column>
              <el-table-column label="到期" width="150" class-name="col-text">
                <template #default="{ row }">
                  <span v-if="row.expiresAt" class="cell-datetime">{{
                    formatDateTime(row.expiresAt)
                  }}</span>
                  <span v-else class="muted">永久</span>
                </template>
              </el-table-column>
              <el-table-column
                align="center"
                label="加入时间"
                width="168"
                class-name="col-status"
                label-class-name="col-status"
              >
                <template #default="{ row }">
                  <span class="cell-datetime">{{ formatDateTime(row.createdAt) }}</span>
                </template>
              </el-table-column>
            </CrudTable>
          </div>
        </div>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="addDialog" title="加入黑名单" destroy-on-close>
      <!-- H08：userId 不预填 1，必填校验走 el-form rules -->
      <el-form ref="addFormRef" :model="addForm" :rules="addRules" label-width="auto">
        <el-form-item label="用户 ID" prop="userId">
          <el-input-number
            v-model="addForm.userId"
            :min="1"
            :precision="0"
            controls-position="right"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="原因" prop="reason">
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
import { Delete } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus';
import { api, downloadAuthFile } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { useListCsv } from '@/composables/useListCsv';
import { useNavAccess } from '@/composables/useNavAccess';
import { useAuthStore } from '@/stores/auth';
import { csvFileName } from '@/utils/csv';
import type { OpenApiRiskEventDto, OpenApiUserBlacklistDto, PageResult } from '@aicabinet/shared-types';
import {
  dictLabel,
  dictTagType,
  displayLabel,
  formatRiskEventDetail
} from '@aicabinet/shared-dict';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { errorMessage, isUserDismiss } from '@/utils/error-message';

function dispositionLabel(s?: string) {
  const m: Record<string, string> = {
    OPEN: '待处置',
    AUTO_CLEARED: '自动结清',
    ACKED: '已确认'
  };
  return (s && m[s]) || (s ? '未知' : '待处置');
}
function dispositionTag(s?: string) {
  if (s === 'AUTO_CLEARED') return 'success';
  if (s === 'ACKED') return 'info';
  return 'warning';
}

type RiskEventRow = OpenApiRiskEventDto;
type BlacklistRow = OpenApiUserBlacklistDto;
const route = useRoute();
const { router, goPath } = useNavAccess();
const auth = useAuthStore();
const canBlacklist = computed(() => auth.hasPerm('ops:risk:blacklist'));

const saving = ref(false);
const tab = ref('events');
const loaded = ref(new Set<string>(['events']));
const addDialog = ref(false);
// H08：不再预填 userId=1（误触会把无关用户拉黑）；必填校验交给 el-form rules
const addFormRef = ref<FormInstance>();
const addForm = reactive<{ userId?: number; reason: string }>({ userId: undefined, reason: '' });
const addRules: FormRules = {
  userId: [{ required: true, message: '请填写用户 ID', trigger: 'blur' }],
  reason: [{ required: true, message: '请填写原因', trigger: 'blur' }]
};

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / 刷新 全部内建
const eventsCrud = useCrudTable<RiskEventRow>({
  rowKey: (r) => r.eventId ?? '',
  errorMessage: '风险事件加载失败',
  // 首查依赖路由 query.tab 的解析结果，故关闭自动加载，onMounted 里显式首查当前 Tab
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    return api.request<PageResult<RiskEventRow> | RiskEventRow[]>(
      AdminEndpoints.riskEventsList(q),
      'GET'
    );
  }
});

const blacklistCrud = useCrudTable<BlacklistRow>({
  rowKey: (r) => r.userId ?? '',
  errorMessage: '黑名单加载失败',
  // 首查延迟到首次切到黑名单 Tab（loaded 懒加载约定），同样关闭自动加载
  autoLoad: false,
  fetchPage: async (params) => {
    if (!canBlacklist.value) {
      // 无权限：不请求，置空并提示（与迁移前行为一致，不记入 loaded，切回仍会重试提示）
      ElMessage.warning('当前账号无黑名单权限');
      return { items: [], total: 0 };
    }
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    const data = await api.request<PageResult<BlacklistRow> | BlacklistRow[]>(
      AdminEndpoints.riskBlacklistList(q),
      'GET'
    );
    loaded.value.add('blacklist');
    return data;
  }
});

const exportButtonLabel = computed(() =>
  tab.value === 'blacklist' ? blacklistCrud.exportButtonLabel : eventsCrud.exportButtonLabel
);

// 页头「导出」按钮的部分选中回退：勾选了部分行时走前端 CSV，否则走后端全量导出
const { onExport: exportEvents } = useListCsv({
  filePrefix: '风险事件',
  headers: ['事件ID', '用户', '类型', '级别', '时间'],
  toRows: () =>
    eventsCrud
      .pickSelected(eventsCrud.items)
      .map((row) => [
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
    blacklistCrud
      .pickSelected(blacklistCrud.items)
      .map((row) => [row.userId, row.reason || '', formatDateTime(row.createdAt)])
});

async function onExport() {
  if (tab.value === 'blacklist') {
    const selected = blacklistCrud.pickSelected(blacklistCrud.items);
    if (selected.length && selected.length < blacklistCrud.items.length) {
      exportBlacklist();
      return;
    }
    try {
      await downloadAuthFile(AdminEndpoints.riskBlacklistExport, csvFileName('黑名单'));
      ElMessage.success('已导出');
    } catch (e) {
      ElMessage.error(e instanceof Error ? e.message : '导出失败');
    }
    return;
  }
  const selected = eventsCrud.pickSelected(eventsCrud.items);
  if (selected.length && selected.length < eventsCrud.items.length) {
    exportEvents();
    return;
  }
  try {
    await downloadAuthFile(AdminEndpoints.riskEventsExport, csvFileName('风险事件'));
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

function onTabChange(name: string | number) {
  const key = String(name);
  syncRouteQuery();
  if (!loaded.value.has(key)) {
    if (key === 'blacklist') void blacklistCrud.load();
    else void eventsCrud.load();
  }
}

function blacklistActions(_row: BlacklistRow): CrudRowAction[] {
  return [{ key: 'remove', label: '移出', icon: Delete, type: 'danger' }];
}

function onBlacklistAction({ key, row }: { key: string; row: BlacklistRow }) {
  if (key === 'remove') void removeBlacklist(row);
}

function openAdd() {
  Object.assign(addForm, { userId: undefined, reason: '' });
  addDialog.value = true;
}

async function saveBlacklist() {
  if (addFormRef.value) {
    const valid = await addFormRef.value.validate().then(
      () => true,
      () => false
    );
    if (!valid) return;
  }
  if (!addForm.userId || !addForm.reason.trim()) {
    return ElMessage.warning('请填写用户 ID 和原因');
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.riskBlacklist, 'POST', {
      userId: addForm.userId,
      reason: addForm.reason.trim()
    });
    addDialog.value = false;
    ElMessage.success('已加入黑名单');
    loaded.value.delete('blacklist');
    await blacklistCrud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '操作失败');
  } finally {
    saving.value = false;
  }
}

async function removeBlacklist(row: BlacklistRow) {
  if (row.userId == null) {
    ElMessage.error('缺少用户 ID');
    return;
  }
  try {
    await ElMessageBox.confirm(`确认将用户 ${row.userId} 移出黑名单？`, '移出黑名单', {
      type: 'warning'
    });
    await api.request(AdminEndpoints.riskBlacklistUser(row.userId), 'DELETE');
    ElMessage.success('已移出');
    await blacklistCrud.load();
  } catch (e: unknown) {
    if (!isUserDismiss(e)) ElMessage.error(errorMessage(e, '操作失败'));
  }
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  if (tab.value === 'blacklist') await blacklistCrud.load();
  else await eventsCrud.load();
}

watch(
  () => route.query.tab,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  // 两个 crud 均已 autoLoad:false：首查依赖路由 query.tab 解析结果，这里显式首查当前 Tab
  if (tab.value === 'blacklist') void blacklistCrud.load();
  else void eventsCrud.load();
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
  font-size: var(--admin-font-size-title);
}
.hint {
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
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
