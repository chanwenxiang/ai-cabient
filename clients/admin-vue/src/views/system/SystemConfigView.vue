<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">参数配置</span>
            <span class="hint">系统键值配置；支持导入导出</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:config:edit']" type="primary" @click="openCreate"
            >新增</el-button
          >
        </div>
      </div>
    </template>

    <el-card class="brand-card" shadow="never">
      <template #header>
        <div class="brand-card-head">
          <span>品牌外观</span>
          <span class="hint">用于登录页、侧栏与浏览器标题；保存后刷新页面即可生效</span>
        </div>
      </template>
      <el-form label-width="auto" class="brand-form" @submit.prevent="saveBrand">
        <el-form-item label="品牌标志">
          <div class="brand-logo-row">
            <div class="brand-logo-preview">
              <img v-if="brandForm.logoUrl" :src="brandForm.logoUrl" alt="品牌标志" />
              <span v-else>{{ brandMarkPreview }}</span>
            </div>
            <el-upload
              v-if="auth.hasPerm('ops:config:edit')"
              :show-file-list="false"
              accept="image/jpeg,image/png,image/webp,image/gif"
              :http-request="uploadBrandLogo"
              :disabled="brandLogoUploading"
            >
              <el-button :loading="brandLogoUploading">上传标志</el-button>
            </el-upload>
            <el-button
              v-if="brandForm.logoUrl && auth.hasPerm('ops:config:edit')"
              link
              type="danger"
              @click="brandForm.logoUrl = ''"
              >清除</el-button
            >
          </div>
        </el-form-item>
        <el-form-item label="主标题" required>
          <el-input v-model="brandForm.title" maxlength="64" placeholder="例如：AI开门柜" />
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="brandForm.subtitle" maxlength="64" placeholder="例如：运营管理系统" />
        </el-form-item>
        <el-form-item label="侧栏标题">
          <el-input
            v-model="brandForm.sidebarTitle"
            maxlength="64"
            placeholder="例如：AI开门柜运营"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            v-hasPermi="['ops:config:edit']"
            type="primary"
            :loading="brandSaving"
            @click="saveBrand"
            >保存品牌</el-button
          >
        </el-form-item>
      </el-form>
    </el-card>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="配置键 / 值 / 说明"
          style="width: 220px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item v-if="flagGroups.length" label="功能分组">
        <el-select
          v-model="groupFilter"
          clearable
          placeholder="全部分组"
          style="width: 200px"
          @change="onGroupFilterChange"
        >
          <el-option v-for="g in flagGroups" :key="g" :label="g" :value="g" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="configKey"
          selectable
          :actions="showActionColumn ? rowActions : undefined"
          actions-testid="system-config"
          empty-text="暂无参数"
          :csv="csvOptions"
          @action="onRowAction"
        >
          <el-table-column label="配置键" min-width="200" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.configKey }}</span>
              <el-tooltip
                v-if="flagByKey[row.configKey]?.deprecated"
                :content="flagByKey[row.configKey]?.deprecatedNote || '已废弃，请勿使用'"
                placement="top"
              >
                <el-tag type="danger" size="small" class="flag-deprecated-tag">已废弃</el-tag>
              </el-tooltip>
            </template>
          </el-table-column>
          <el-table-column label="功能分组" min-width="130">
            <template #default="{ row }">{{ groupOf(row.configKey) || '—' }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="160" class-name="col-text">
            <template #default="{ row }">{{ row.description || '无说明' }}</template>
          </el-table-column>
          <el-table-column label="配置值" min-width="200" class-name="col-text">
            <template #default="{ row }">{{ row.configValue || '无' }}</template>
          </el-table-column>
          <el-table-column
            align="center"
            label="更新时间"
            width="168"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime">{{ formatDateTime(row.updatedAt) }}</span>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <!-- F1 策略版本：某配置键的变更历史（旧值/新值/操作人），可回滚到任一历史版本 -->
    <el-drawer v-model="historyVisible" :title="`变更历史 · ${historyKey}`" size="680px">
      <el-table v-loading="historyLoading" :data="historyRows" border size="small">
        <el-table-column label="变更时间" width="150" class-name="col-text">
          <template #default="{ row }">
            <span>{{ formatDateTime(row.createdAt) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作人" width="100" class-name="col-text">
          <template #default="{ row }">
            <span>{{ row.operatorName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变更前" class-name="col-text">
          <template #default="{ row }">
            <span>{{ historyValueText(row.oldValue) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="变更后" class-name="col-text">
          <template #default="{ row }">
            <span>{{ historyValueText(row.newValue, true) }}</span>
          </template>
        </el-table-column>
        <el-table-column
          label="操作"
          width="90"
          align="center"
          class-name="col-action"
          fixed="right"
        >
          <template #default="{ row }">
            <el-button
              v-hasPermi="['ops:config:edit']"
              link
              type="primary"
              :disabled="row.oldValue === null"
              :loading="rollingBackId === row.historyId"
              @click="onRollback(row)"
              >回滚</el-button
            >
          </template>
        </el-table-column>
      </el-table>
      <el-empty
        v-if="!historyLoading && !historyRows.length"
        description="暂无变更记录；需先开启「系统配置变更审计」并发生过改动"
      />
    </el-drawer>

    <el-dialog v-model="dialogVisible" :title="creating ? '新增参数' : '编辑参数'" destroy-on-close>
      <el-form label-width="auto">
        <el-form-item label="配置键" required>
          <el-input
            v-model="form.configKey"
            :disabled="!creating"
            placeholder="例如 consumer.service_phone"
          />
        </el-form-item>
        <el-form-item label="配置值" required>
          <el-select
            v-if="valueOptions.length"
            v-model="form.configValue"
            filterable
            allow-create
            default-first-option
            style="width: 100%"
            placeholder="选择或输入"
          >
            <el-option
              v-for="opt in valueOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
          <el-input v-else v-model="form.configValue" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="说明">
          <el-input v-model="form.description" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button v-hasPermi="['ops:config:edit']" type="primary" :loading="saving" @click="save"
          >保存</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { Clock, Delete, EditPen } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import type { UploadRequestOptions } from 'element-plus';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable, type CrudPageParams } from '@/composables/useCrudTable';
import { useAuthStore } from '@/stores/auth';
import { useBrandStore } from '@/stores/brand';
import { formatDateTime } from '@aicabinet/shared-uni/format';
import { sortByPrimaryKey } from '@/utils/sort-by-pk';
import { errorMessage, isUserDismiss } from '@/utils/error-message';
import { validateImageFile } from '@/utils/upload-validate';

const BRAND_KEYS = {
  title: 'ops.brand.title',
  subtitle: 'ops.brand.subtitle',
  sidebarTitle: 'ops.brand.sidebar_title',
  logoUrl: 'ops.brand.logo_url'
} as const;
interface SystemConfigRow {
  configKey: string;
  configValue: string;
  description?: string;
  updatedAt?: string;
}

/** 功能开关注册表（后端 ops/feature-flags.json）——只读元数据，用于分组筛选与类型化控件。 */
interface FeatureFlagOption {
  value: string;
  label: string;
}
interface FeatureFlagMeta {
  key: string;
  group: string;
  type: string;
  default: string;
  description: string;
  unit?: string;
  options?: FeatureFlagOption[];
  deprecated?: boolean;
  deprecatedNote?: string;
}
interface FeatureFlagCatalog {
  version: number;
  groups: string[];
  flags: FeatureFlagMeta[];
}

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const brandStore = useBrandStore();
const saving = ref(false);
const brandSaving = ref(false);
const brandLogoUploading = ref(false);
const keyword = ref('');
/** 全量配置（未做关键词/分组过滤）：品牌表单回显与 valueOfKey 依赖它 */
const items = ref<SystemConfigRow[]>([]);
/** key -> 开关元数据；面板据此分组筛选、按 type 渲染控件、标注废弃项 */
const flagByKey = ref<Record<string, FeatureFlagMeta>>({});
const flagGroups = ref<string[]>([]);
const groupFilter = ref('');
const dialogVisible = ref(false);
const creating = ref(false);
const form = reactive({ configKey: '', configValue: '', description: '' });
const brandForm = reactive({
  title: 'AI开门柜',
  subtitle: '运营管理系统',
  sidebarTitle: 'AI开门柜运营',
  logoUrl: ''
});

const brandMarkPreview = computed(() => {
  const t = brandForm.title.trim();
  return t ? t.slice(-1) : '柜';
});

const ENUM_VALUE_OPTIONS: Record<string, { value: string; label: string }[]> = {
  'settlement.recognition_mode': [
    { value: 'VISION', label: 'VISION — 纯视觉（忽略重力）' },
    { value: 'VISION_GRAVITY', label: 'VISION_GRAVITY — 视觉+重力融合' }
  ],
  'refund.default_policy': [
    { value: 'AUTO_REFUND', label: 'AUTO_REFUND — 自助退款' },
    { value: 'DISPUTE_ONLY', label: 'DISPUTE_ONLY — 仅申诉' }
  ]
};

const valueOptions = computed(() => {
  const key = form.configKey.trim();
  // 注册表是权威清单：枚举候选优先取它，前端不再各维护一份（下面的常量只作兜底）
  const fromCatalog = flagByKey.value[key]?.options;
  if (fromCatalog && fromCatalog.length > 0) {
    return fromCatalog.map((o) => ({ value: o.value, label: `${o.value} — ${o.label}` }));
  }
  return ENUM_VALUE_OPTIONS[key] || [];
});

const filtered = computed(() => {
  const q = keyword.value.trim().toLowerCase();
  const g = groupFilter.value;
  const rows = items.value.filter((row) => {
    if (g && flagByKey.value[row.configKey]?.group !== g) return false;
    if (!q) return true;
    return [row.configKey, row.configValue, row.description].some((x) =>
      String(x || '')
        .toLowerCase()
        .includes(q)
    );
  });
  return sortByPrimaryKey(rows, 'configKey', 'asc');
});

function groupOf(key: string) {
  return flagByKey.value[key]?.group || '';
}

// 列表状态机统一交给 CrudTable：分页 / 多选 / 竞态 / 空态 / CSV / 刷新全部内建。
// 本页无服务端分页：fetchPage 拉全量配置 → 客户端按关键词/功能分组过滤（随查询生效）→ 前端切片成一页。
async function fetchPage(params: CrudPageParams) {
  items.value = await api.request<SystemConfigRow[]>(AdminEndpoints.systemConfigs, 'GET');
  syncBrandFormFromItems();
  const rows = filtered.value;
  const start = params.page * params.size;
  return { items: rows.slice(start, start + params.size), total: rows.length };
}

const crud = useCrudTable<SystemConfigRow>({
  rowKey: (r) => r.configKey,
  fetchPage,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false
});

/** 功能分组为客户端过滤（fetchPage 内生效），切换分组触发重查并回到第一页 */
function onGroupFilterChange() {
  void crud.search();
}

const csvOptions: CrudCsvOptions = {
  filePrefix: '参数配置',
  exportPerm: 'ops:config:export',
  importPerm: 'ops:config:import',
  headers: ['配置键', '配置值', '说明', '更新时间'],
  toRows: (picked) =>
    picked.map((row) => [
      row.configKey,
      row.configValue,
      row.description || '',
      formatDateTime(row.updatedAt)
    ]),
  templateSample: ['demo.config.key', 'value', '说明', ''],
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const configKey = (row['配置键'] || row.configKey || '').trim();
      const configValue = (row['配置值'] || row.configValue || '').trim();
      if (!configKey) continue;
      await api.request(AdminEndpoints.systemConfigs, 'PUT', {
        configKey,
        configValue,
        description: (row['说明'] || row.description || '').trim()
      });
      ok++;
    }
    await crud.load();
    return ok;
  }
};

function rowActions(_row: SystemConfigRow): CrudRowAction[] {
  return [
    { key: 'edit', label: '编辑', icon: EditPen, type: 'primary', perm: 'ops:config:edit' },
    { key: 'delete', label: '删除', icon: Delete, type: 'danger', perm: 'ops:config:delete' },
    // F1 策略版本：能看配置就能看它的变更历史（后端同权限 ops:config:list）
    { key: 'history', label: '历史', icon: Clock, type: 'info', perm: 'ops:config:list' }
  ];
}

const showActionColumn = computed(
  () =>
    auth.hasPerm('ops:config:edit') ||
    auth.hasPerm('ops:config:delete') ||
    auth.hasPerm('ops:config:list')
);

async function onRowAction({ key, row }: { key: string; row: SystemConfigRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'delete') await onDelete(row);
  else if (key === 'history') await openHistory(row);
}

/** F1 策略版本：变更历史抽屉。 */
interface SystemConfigHistoryRow {
  historyId: number;
  configKey: string;
  oldValue: string | null;
  newValue: string | null;
  operatorId: number;
  operatorName: string;
  createdAt: string;
}

const historyVisible = ref(false);
const historyLoading = ref(false);
const historyKey = ref('');
const historyRows = ref<SystemConfigHistoryRow[]>([]);
const rollingBackId = ref<number | null>(null);

async function openHistory(row: SystemConfigRow) {
  historyKey.value = row.configKey;
  historyVisible.value = true;
  historyLoading.value = true;
  historyRows.value = [];
  try {
    historyRows.value = await api.request<SystemConfigHistoryRow[]>(
      AdminEndpoints.systemConfigHistory(row.configKey),
      'GET'
    );
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '加载变更历史失败'));
  } finally {
    historyLoading.value = false;
  }
}

function historyValueText(v: string | null, del = false) {
  if (v === null) return del ? '（已删除）' : '（未配置）';
  return v === '' ? '（空值）' : v;
}

/**
 * 回滚到某版本的**变更前值**（撤销那一次变更）。
 * 该版本若是配置首次创建（oldValue 为 null），后端会拒绝——按钮已禁用，这里再兜一层。
 */
async function onRollback(row: SystemConfigHistoryRow) {
  if (row.oldValue === null) return;
  try {
    await ElMessageBox.confirm(
      `确认把「${historyKey.value}」回滚到该次变更之前的值：${historyValueText(row.oldValue)}？`,
      '回滚配置',
      { type: 'warning' }
    );
  } catch (e: unknown) {
    if (isUserDismiss(e)) return;
    throw e;
  }
  rollingBackId.value = row.historyId;
  try {
    await api.request(AdminEndpoints.systemConfigRollback(historyKey.value), 'POST', {
      historyId: row.historyId
    });
    ElMessage.success('已回滚');
    await crud.load();
    await openHistory({ configKey: historyKey.value } as SystemConfigRow);
  } catch (e: unknown) {
    ElMessage.error(errorMessage(e, '回滚失败'));
  } finally {
    rollingBackId.value = null;
  }
}

async function onDelete(row: SystemConfigRow) {
  try {
    await ElMessageBox.confirm(
      `确认删除参数「${row.configKey}」？系统默认项删除后可能被重新初始化。`,
      '删除参数',
      { type: 'warning' }
    );
    await api.request(AdminEndpoints.systemConfig(row.configKey), 'DELETE');
    ElMessage.success('已删除');
    await crud.load();
  } catch (e: unknown) {
    if (!isUserDismiss(e)) ElMessage.error(errorMessage(e, '删除失败'));
  }
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  router.replace({ query });
}

function applyRouteQuery() {
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    return true;
  }
  return false;
}

/**
 * 拉取功能开关注册表。它只影响「怎么分组展示」，不影响配置读写本身，
 * 所以单独 try —— 清单挂了也不能连累主表；但必须提示，不能静默退化成「没有分组」。
 */
async function loadFeatureFlags() {
  try {
    const catalog = await api.request<FeatureFlagCatalog>(
      AdminEndpoints.systemConfigFeatureFlags,
      'GET'
    );
    flagGroups.value = catalog.groups || [];
    const map: Record<string, FeatureFlagMeta> = {};
    for (const flag of catalog.flags || []) {
      map[flag.key] = flag;
    }
    flagByKey.value = map;
  } catch (e) {
    ElMessage.warning(e instanceof Error ? e.message : '功能开关清单加载失败（仍可编辑参数）');
  }
}

function valueOfKey(key: string, fallback = '') {
  return items.value.find((r) => r.configKey === key)?.configValue ?? fallback;
}

function syncBrandFormFromItems() {
  brandForm.title = valueOfKey(BRAND_KEYS.title, 'AI开门柜') || 'AI开门柜';
  brandForm.subtitle = valueOfKey(BRAND_KEYS.subtitle, '运营管理系统') || '运营管理系统';
  brandForm.sidebarTitle = valueOfKey(BRAND_KEYS.sidebarTitle, 'AI开门柜运营') || 'AI开门柜运营';
  brandForm.logoUrl = valueOfKey(BRAND_KEYS.logoUrl, '');
}

async function upsertBrandKey(configKey: string, configValue: string, description: string) {
  await api.request(AdminEndpoints.systemConfigs, 'PUT', {
    configKey,
    configValue: configValue ?? '',
    description
  });
}

async function saveBrand() {
  const title = brandForm.title.trim();
  if (!title) {
    ElMessage.warning('请填写主标题');
    return;
  }
  brandSaving.value = true;
  try {
    await upsertBrandKey(BRAND_KEYS.title, title, '运营后台品牌标题（登录页主标题）');
    await upsertBrandKey(
      BRAND_KEYS.subtitle,
      brandForm.subtitle.trim() || '运营管理系统',
      '运营后台副标题（登录页副文案）'
    );
    await upsertBrandKey(
      BRAND_KEYS.sidebarTitle,
      brandForm.sidebarTitle.trim() || `${title}运营`,
      '侧栏展开时的品牌文案'
    );
    await upsertBrandKey(
      BRAND_KEYS.logoUrl,
      brandForm.logoUrl.trim(),
      '品牌标志图片地址（留空则用标题末字）'
    );
    brandStore.applyLocal({
      title,
      subtitle: brandForm.subtitle.trim() || '运营管理系统',
      sidebarTitle: brandForm.sidebarTitle.trim() || `${title}运营`,
      logoUrl: brandForm.logoUrl.trim()
    });
    ElMessage.success('品牌已保存');
    await crud.load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    brandSaving.value = false;
  }
}

async function uploadBrandLogo(options: UploadRequestOptions) {
  const file = options.file as File;
  if (!file) return;
  const check = validateImageFile(file);
  if (!check.ok) {
    ElMessage.warning(check.message);
    return;
  }
  brandLogoUploading.value = true;
  try {
    const base =
      (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const formData = new FormData();
    formData.append('file', file);
    const res = await authFetch(`${base}${AdminEndpoints.systemConfigBrandLogo}`, {
      method: 'POST',
      body: formData
    });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `上传失败 (${res.status})`);
    }
    const uploaded = json.data as { url?: string };
    if (!uploaded?.url) throw new Error('上传失败：未返回地址');
    brandForm.logoUrl = uploaded.url;
    options.onSuccess?.(uploaded as never);
    ElMessage.success('标志已上传，请点击保存品牌');
  } catch (e) {
    options.onError?.(e as never);
    ElMessage.error(e instanceof Error ? e.message : '上传失败');
  } finally {
    brandLogoUploading.value = false;
  }
}

/** 关键词/分组为客户端过滤（fetchPage 内生效），查询/回车/清空触发重查 */
function search() {
  syncRouteQuery();
  void crud.search();
}

function reset() {
  keyword.value = '';
  syncRouteQuery();
  void crud.search();
}

function openCreate() {
  creating.value = true;
  form.configKey = '';
  form.configValue = '';
  form.description = '';
  dialogVisible.value = true;
}

function openEdit(row: SystemConfigRow) {
  creating.value = false;
  form.configKey = row.configKey;
  form.configValue = row.configValue;
  form.description = row.description || '';
  dialogVisible.value = true;
}

async function save() {
  if (!form.configKey.trim()) {
    ElMessage.warning('请填写配置键');
    return;
  }
  saving.value = true;
  try {
    await api.request(AdminEndpoints.systemConfigs, 'PUT', {
      configKey: form.configKey.trim(),
      configValue: form.configValue.trim(),
      description: form.description.trim()
    });
    ElMessage.success('已保存');
    dialogVisible.value = false;
    await crud.load();
    if (form.configKey.startsWith('ops.brand.')) {
      await brandStore.load();
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

// 首查前需先应用路由查询参数（applyRouteQuery），故 crud 配 autoLoad: false，由这里显式首查
onMounted(() => {
  applyRouteQuery();
  void crud.load();
  void loadFeatureFlags();
});

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  // 关键词来自路由变更：同步后重查（crud.search 自带回到第一页）
  await crud.search();
}

watch(
  () => route.query.keyword,
  () => {
    void reloadFromRouteQuery();
  }
);

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
.brand-card {
  margin-bottom: 16px;
  border: 1px solid var(--el-border-color-lighter);
}
.brand-card-head {
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-weight: 600;
}
.brand-form {
  max-width: 560px;
}
.brand-logo-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}
.brand-logo-preview {
  width: 56px;
  height: 56px;
  border-radius: 12px;
  display: grid;
  place-items: center;
  overflow: hidden;
  color: #fff;
  font-size: var(--admin-font-size-display-md);
  font-weight: 700;
  background: linear-gradient(145deg, #14b8a6, var(--app-primary, #0f766e));
}
.brand-logo-preview img {
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.flag-deprecated-tag {
  margin-left: 6px;
}
</style>
