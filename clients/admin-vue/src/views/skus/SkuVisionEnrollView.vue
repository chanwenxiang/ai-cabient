<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">识别入驻</span>
            <span class="hint">类名映射 → 测试 → 生产白名单；商品主数据请在「商品管理」维护</span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button v-hasPermi="['ops:sku:export']" @click="onExport">{{
            exportButtonLabel
          }}</el-button>
          <el-button
            v-hasPermi="['ops:sku:import']"
            @click="
              onDownloadTemplate([
                'SKU-DEMO-001',
                '示例商品',
                '3.50',
                '',
                '饮料',
                'demo_sku',
                '映射中',
                '上架',
                '92%',
                '50%'
              ])
            "
            >导入模板</el-button
          >
          <el-button v-hasPermi="['ops:sku:import']" :loading="importing" @click="triggerImport"
            >导入</el-button
          >
          <input
            ref="importInput"
            type="file"
            accept=".csv,text/csv"
            class="hidden-input"
            @change="onImportFile"
          />
          <el-button v-if="canAccessSkus" @click="goSkus">商品管理</el-button>
          <el-button v-if="canEnroll" type="primary" @click="openEnroll()">入驻配置</el-button>
          <el-button :icon="Refresh" :loading="loading" @click="load">刷新</el-button>
        </div>
      </div>
    </template>

    <div class="list-lead">
      <div class="enroll-chips" role="group" aria-label="识别入驻状态筛选">
        <button
          type="button"
          class="enroll-chip"
          :class="{ active: !enrollmentFilter }"
          @click="filterByEnrollment('')"
        >
          全部
        </button>
        <button
          v-for="(step, idx) in enrollmentSteps"
          :key="step.status"
          type="button"
          class="enroll-chip"
          :class="{ active: enrollmentFilter === step.status }"
          :title="`${step.label}${step.description ? ' · ' + step.description : ''}`"
          @click="filterByEnrollment(step.status)"
        >
          <span class="enroll-chip__idx">{{ idx + 1 }}</span>
          {{ shortEnrollmentLabel(step.label) }}
        </button>
        <el-button
          class="help-toggle"
          text
          type="primary"
          size="small"
          @click="helpOpen = !helpOpen"
        >
          {{ helpOpen ? '收起说明' : '入驻说明' }}
        </el-button>
      </div>
      <el-collapse-transition>
        <div v-show="helpOpen" class="sku-help">
          <el-alert
            type="info"
            :closable="false"
            show-icon
            class="risk-alert"
            title="识别入驻说明（算法无关）"
            :description="pipelineHint"
          />
          <el-alert
            type="warning"
            :closable="false"
            show-icon
            class="risk-alert"
            title="风险商品提示"
            description="体积过小或过薄的商品易被遮挡/误识别，可能导致少扣或多扣。上架前请确认识别映射与阈值，并优先安排人工抽检。"
          />
        </div>
      </el-collapse-transition>
    </div>

    <el-tabs v-model="saleTab" class="status-tabs" @tab-change="onSaleTab">
      <el-tab-pane label="在售商品" name="ACTIVE" />
      <el-tab-pane label="所有商品" name="ALL" />
    </el-tabs>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="编号 / 名称 / 条码 / 类名"
          style="width: 240px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="识别状态">
        <el-select
          v-model="enrollmentFilter"
          clearable
          placeholder="全部"
          style="width: 130px"
          @change="search"
        >
          <el-option
            v-for="s in enrollmentStatuses"
            :key="s"
            :label="enrollmentLabel(s)"
            :value="s"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-button
          v-hasPermi="['ops:sku:edit']"
          type="danger"
          plain
          :disabled="!selectedKeys.length"
          :loading="batchDelisting"
          @click="batchDelist"
          >批量下架</el-button
        >
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <el-table
          v-loading="loading"
          :data="displayList"
          stripe
          border
          row-key="skuId"
          class="report-table sku-table"
          :default-sort="idDefaultSort"
          @sort-change="onIdSortChange"
          @selection-change="onSelectionChange"
          empty-text=" "
        >
          <template #empty>
            <el-empty v-if="listHydrated && !loading" :description="skuEmptyText" />
          </template>
          <el-table-column type="selection" width="48" align="center" />
          <el-table-column
            prop="skuCode"
            label="编号"
            width="100"
            align="center"
            class-name="col-text"
            sortable="custom"
          >
            <template #default="{ row }">
              <span class="cell-id">{{ row.skuCode ?? '暂无' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="主图" width="72" align="center">
            <template #default="{ row }">
              <img
                v-if="row.imageUrl"
                :src="row.imageUrl"
                alt=""
                class="sku-thumb"
                loading="lazy"
                referrerpolicy="no-referrer"
                @error="onThumbError"
              />
              <div v-else class="sku-thumb sku-thumb--empty">无图</div>
            </template>
          </el-table-column>
          <el-table-column label="商品" min-width="160" align="center" class-name="col-text">
            <template #default="{ row }">
              <button
                type="button"
                class="link-cell"
                @click="canEnroll ? openEnroll(row) : ElMessage.info('当前账号无商品编辑权限')"
              >
                {{ row.skuName || '无' }}
              </button>
            </template>
          </el-table-column>
          <el-table-column label="基准价" width="96" align="center" class-name="col-money">
            <template #default="{ row }">¥{{ ((row.priceCents || 0) / 100).toFixed(2) }}</template>
          </el-table-column>
          <el-table-column label="成本" width="96" align="center" class-name="col-money">
            <template #default="{ row }">
              {{
                row.purchaseCostCents != null
                  ? `¥${(row.purchaseCostCents / 100).toFixed(2)}`
                  : '无'
              }}
            </template>
          </el-table-column>
          <el-table-column
            prop="category"
            label="类目"
            min-width="100"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">{{ categoryLabel(row.category) }}</template>
          </el-table-column>
          <el-table-column
            label="端侧类名"
            min-width="130"
            align="center"
            class-name="col-text"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              <span v-if="row.yoloClassName" class="cell-id">{{ row.yoloClassName }}</span>
              <span v-else class="muted">无</span>
            </template>
          </el-table-column>
          <el-table-column label="识别状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="enrollmentTagType(row.visionEnrollmentStatus)">
                {{ enrollmentLabel(row.visionEnrollmentStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="映射/模型" min-width="150" align="center">
            <template #default="{ row }">
              <div class="pipe-cell">
                <el-tag size="small" :type="rowMeta(row)?.mappingEffective ? 'success' : 'info'">
                  {{ rowMeta(row)?.mappingEffective ? '结算白名单' : '未进白名单' }}
                </el-tag>
                <el-tag size="small" type="warning" effect="plain">端侧质量门禁</el-tag>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="商品状态" width="96" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="row.status === 'ACTIVE' ? 'success' : 'info'">
                {{ skuStatusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="扣款阈值" width="96" align="center">
            <template #default="{ row }">{{ formatConfidence(row.minChargeConfidence) }}</template>
          </el-table-column>
          <el-table-column label="检测阈值" width="96" align="center">
            <template #default="{ row }">{{
              formatConfidence(row.detectionMinConfidence ?? 0.5)
            }}</template>
          </el-table-column>
          <el-table-column label="操作" width="200" class-name="col-action" align="center">
            <template #default="{ row }">
              <TableActions
                :actions="skuActions(row)"
                :max-primary="2"
                @action="(key) => onSkuAction(key, row)"
              />
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
      layout="total, sizes, prev, pager, next"
      background
      @current-change="load"
      @size-change="onSizeChange"
    />

    <el-dialog
      v-model="enrollDialog"
      :title="enrollForm.existing ? '编辑识别入驻' : '识别入驻配置'"
      width="640px"
    >
      <el-form label-width="108px">
        <el-form-item v-if="!enrollForm.existing" label="关联商品" required>
          <el-select
            v-model="enrollForm.skuId"
            filterable
            placeholder="选择商品"
            style="width: 100%"
            @change="onPickSku"
          >
            <el-option
              v-for="s in catalogOptions"
              :key="s.skuId"
              :label="s.skuName"
              :value="s.skuId"
            />
          </el-select>
          <div class="field-hint">没有商品？请先到「商品管理」新建主数据。</div>
        </el-form-item>
        <el-form-item v-else label="数字编号">
          <el-input
            :model-value="
              enrollForm.skuCode != null ? String(enrollForm.skuCode) : enrollForm.skuId
            "
            disabled
          />
        </el-form-item>
        <el-form-item label="商品名称" required>
          <el-input
            v-model="enrollForm.skuName"
            :disabled="!enrollForm.existing"
            @blur="suggestClassNameIfEmpty"
          />
        </el-form-item>
        <el-form-item label="基准价(分)" required>
          <el-input-number
            v-model="enrollForm.priceCents"
            :min="1"
            :step="10"
            :disabled="!enrollForm.existing"
          />
        </el-form-item>
        <el-form-item label="端侧类名" required>
          <div class="inline-field">
            <el-input
              v-model="enrollForm.yoloClassName"
              placeholder="例如 cola_330ml（英文类名，算法无关）"
            />
            <el-button :loading="suggestingClass" @click="suggestClassName(true)"
              >规则建议</el-button
            >
          </div>
        </el-form-item>
        <el-form-item label="主图">
          <div class="sku-image-field">
            <el-upload
              v-hasPermi="['ops:sku:edit']"
              :show-file-list="false"
              accept="image/jpeg,image/png,image/webp,image/gif,.jpg,.jpeg,.png,.webp,.gif"
              :http-request="onImageUpload"
              :disabled="imageUploading"
            >
              <el-button :loading="imageUploading" type="primary" plain>上传图片</el-button>
            </el-upload>
            <el-button
              v-if="enrollForm.imageUrl"
              link
              type="danger"
              @click="enrollForm.imageUrl = ''"
              >清除</el-button
            >
            <div class="field-hint">支持 jpg/png/webp/gif，单张不超过 5MB</div>
            <img
              v-if="enrollForm.imageUrl.trim()"
              :src="enrollForm.imageUrl.trim()"
              alt="主图预览"
              class="sku-preview"
              referrerpolicy="no-referrer"
            />
          </div>
        </el-form-item>
        <el-form-item label="智能建议">
          <div class="inline-field">
            <input
              ref="classImageInput"
              type="file"
              accept="image/*"
              class="hidden-input"
              @change="onClassImagePick"
            />
            <el-button :loading="suggestingImage" @click="triggerClassImage"
              >上传主图建议类名</el-button
            >
            <span v-if="classSuggestHint" class="field-hint">{{ classSuggestHint }}</span>
          </div>
        </el-form-item>
        <el-form-item label="识别状态">
          <el-select v-model="enrollForm.visionEnrollmentStatus" style="width: 100%">
            <el-option
              v-for="s in enrollmentStatuses"
              :key="s"
              :label="enrollmentLabel(s)"
              :value="s"
            />
          </el-select>
          <div class="field-hint">建议走「保存 → 识别测试 → 推进状态」；勿跳过抽检直接生产。</div>
        </el-form-item>
        <el-form-item label="参考图 URL">
          <el-input
            v-model="enrollForm.referenceImageUrls"
            type="textarea"
            :rows="2"
            placeholder="可选，逗号或换行分隔；用于采集闭环留档（训练管线 stub）"
          />
        </el-form-item>
        <el-form-item label="检测阈值">
          <el-slider v-model="enrollForm.detectionPercent" :min="10" :max="100" show-input />
        </el-form-item>
        <el-form-item label="扣款阈值">
          <el-slider v-model="enrollForm.chargePercent" :min="50" :max="100" show-input />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="enrollDialog = false">取消</el-button>
        <el-button v-if="canEnroll" type="primary" :loading="saving" @click="saveEnroll"
          >保存入驻</el-button
        >
      </template>
    </el-dialog>

    <el-dialog v-model="testDialog" :title="`识别测试 · ${testForm.skuName}`" width="560px">
      <el-alert
        type="info"
        :closable="false"
        show-icon
        class="risk-alert"
        title="抽检说明"
        description="预览可用联调/演示数据。转生产只进入结算白名单；端侧若回传 mock、fallback 或低于扣款阈值，仍会进争议，不会静默扣款。"
      />
      <el-form label-width="96px">
        <el-form-item label="设备 ID">
          <el-input v-model="testForm.deviceId" placeholder="例如 CAB-001" />
        </el-form-item>
        <el-form-item label="测试图片">
          <input ref="testImageInput" type="file" accept="image/*" @change="onTestImagePick" />
        </el-form-item>
      </el-form>
      <el-alert
        v-if="testPreview?.hint"
        :title="testPreview.hint"
        :type="testPreview.needReview ? 'warning' : 'success'"
        show-icon
      />
      <el-table
        v-if="testPreview?.items?.length"
        :data="testPreview.items"
        size="small"
        stripe
        class="test-table"
      >
        <el-table-column prop="skuName" label="商品" align="center" />
        <el-table-column prop="quantity" label="数量" width="72" align="center" />
        <el-table-column label="置信度" width="88" align="center">
          <template #default="{ row }">{{ Math.round((row.confidence || 0) * 100) }}%</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="testDialog = false">关闭</el-button>
        <el-button
          v-if="
            canEnroll &&
            testPreview &&
            testForm.status !== 'TESTED' &&
            testForm.status !== 'PRODUCTION'
          "
          type="success"
          plain
          :loading="advancing"
          @click="markTestedFromPreview"
          >标记已测试</el-button
        >
        <el-button type="primary" :loading="testing" :disabled="!testImageFile" @click="runTest"
          >预览识别</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Refresh, Upload, CircleCheck, ArrowRight } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox, type UploadRequestOptions } from 'element-plus';
import { dictLabel, dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api, authFetch } from '@/api/client';
import TableActions, { type TableAction } from '@/components/TableActions.vue';
import PagePager from '@/components/PagePager.vue';
import { useDictOptions } from '@/composables/useDictOptions';
import { useListCsv } from '@/composables/useListCsv';
import { useTableSelection } from '@/composables/useTableSelection';
import { useAuthStore } from '@/stores/auth';
import { useIdColumnSort } from '@/composables/useIdColumnSort';
import { findNavByPath } from '@/config/menu';
import { dictRuntimeEpoch } from '@/stores/dict-runtime';
import type {
  DevRecognitionPreviewDto,
  FileAttachmentDto,
  SkuCatalog,
  SkuVisionEnrollmentPipeline,
  SkuVisionEnrollmentRow,
  UpsertSkuRequest,
  UpsertSkuVisionEnrollmentRequest
} from '@aicabinet/shared-types';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const { idDefaultSort, onIdSortChange, sortById } = useIdColumnSort('skuCode');
/** 入驻/转生产需同时具备商品编辑与识别映射编辑 */
const canEnroll = computed(() => auth.hasPerm('ops:sku:edit') && auth.hasPerm('ops:vision:edit'));
const canVisionEdit = computed(() => auth.hasPerm('ops:vision:edit'));
const canAccessSkus = computed(() => {
  const nav = findNavByPath('/skus');
  return !nav?.perm || auth.hasPerm(nav.perm);
});
function goSkus() {
  router.push('/skus');
}
const loading = ref(false);
const listHydrated = ref(false);
const saving = ref(false);
const advancing = ref(false);
const testing = ref(false);
const suggestingClass = ref(false);
const suggestingImage = ref(false);
const imageUploading = ref(false);
const batchDelisting = ref(false);
const items = ref<SkuCatalog[]>([]);
const total = ref(0);
const catalogOptions = ref<SkuCatalog[]>([]);
const rowBySku = ref<Record<string, SkuVisionEnrollmentRow>>({});
const pipelineHint = ref(
  '流程：草稿/映射中 → 识别测试 → 转生产。转生产只表示进入结算白名单；可对接任意端侧识别算法。端侧若回传 mock/fallback 或低于扣款阈值，仍会进争议审单，不会静默扣款。'
);
const enrollmentStepDesc: Record<string, string> = {
  DRAFT: '录入商品基本信息',
  MAPPING: '绑定类名与阈值',
  TESTED: '完成识别抽检',
  PRODUCTION: '结算白名单（端侧质量门禁仍生效）'
};
/** 接口可能返回全量步骤；展示时按 ACTIVE 字典项过滤（禁用项不进芯片/下拉） */
const pipelineStepsFromApi = ref<SkuVisionEnrollmentPipeline['steps']>([]);
const enrollmentStatusOptions = useDictOptions('sku_enrollment_status');
const enrollmentStatuses = computed(() => enrollmentStatusOptions.value.map((o) => o.value));
const enrollmentSteps = computed(() => {
  const active = new Set(enrollmentStatuses.value);
  const fromApi = (pipelineStepsFromApi.value || []).filter((s) => active.has(s.status));
  if (fromApi.length) return fromApi;
  return enrollmentStatusOptions.value.map((o) => ({
    status: o.value,
    label: o.label,
    description: enrollmentStepDesc[o.value] || o.label
  }));
});
const keyword = ref('');
const enrollmentFilter = ref('');
const helpOpen = ref(false);
const saleTab = ref('ACTIVE');
const page = ref(1);
const size = ref(20);
const displayList = computed(() => sortById(items.value, (r) => r.skuCode ?? r.skuId));

function queryParams() {
  const q = new URLSearchParams({
    page: String(page.value - 1),
    size: String(size.value)
  });
  if (keyword.value.trim()) q.set('q', keyword.value.trim());
  if (saleTab.value === 'ACTIVE') q.set('status', 'ACTIVE');
  else q.set('status', 'ALL');
  if (enrollmentFilter.value) q.set('enrollment', enrollmentFilter.value);
  return q;
}

function onSizeChange() {
  page.value = 1;
  load();
}

async function loadCatalogOptions() {
  try {
    const data = await api.request<{ items: SkuVisionEnrollmentRow[] }>(
      '/api/v2/ops/admin/sku-vision/rows?status=ALL&page=0&size=500',
      'GET'
    );
    catalogOptions.value = (data.items || []).map((r) => r.sku);
  } catch {
    catalogOptions.value = [];
  }
}
const skuEmptyText = computed(() => {
  if (saleTab.value === 'ACTIVE') {
    if (keyword.value.trim() || enrollmentFilter.value)
      return '在售列表无匹配商品，可清空筛选或切换到「所有商品」';
    return '暂无在售商品，可切换到「所有商品」查看已下架项';
  }
  if (keyword.value.trim() || enrollmentFilter.value) return '无匹配商品';
  return '暂无商品';
});
const { onSelectionChange, pickSelected, exportButtonLabel, clearSelection, selectedKeys } =
  useTableSelection<SkuCatalog>((r) => r.skuId);

function onSaleTab() {
  clearSelection();
  page.value = 1;
  syncRouteQuery();
  load();
}

function toUpsertBody(row: SkuCatalog, status: string): UpsertSkuRequest {
  return {
    skuId: row.skuId,
    skuName: row.skuName || row.skuId,
    priceCents: row.priceCents || 1,
    weightGrams: row.weightGrams,
    visionEnabled: row.visionEnabled ?? true,
    imageUrl: row.imageUrl,
    description: row.description,
    category: row.category,
    barcode: row.barcode,
    status,
    shelfLifeDays: row.shelfLifeDays,
    nearExpiryDays: row.nearExpiryDays,
    blockSaleDaysBeforeExpiry: row.blockSaleDaysBeforeExpiry,
    storageType: row.storageType,
    purchaseCostCents: row.purchaseCostCents,
    nearExpiryPriceCents: row.nearExpiryPriceCents,
    minChargeConfidence: row.minChargeConfidence,
    yoloClassName: row.yoloClassName,
    visionEnrollmentStatus:
      row.visionEnrollmentStatus as UpsertSkuRequest['visionEnrollmentStatus'],
    detectionMinConfidence: row.detectionMinConfidence,
    referenceImageUrlsJson: row.referenceImageUrlsJson
  };
}

async function batchDelist() {
  const targets = items.value.filter(
    (d) => selectedKeys.value.map(String).includes(d.skuId) && d.status === 'ACTIVE'
  );
  if (!targets.length) {
    ElMessage.warning('请勾选在售商品');
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认将 ${targets.length} 个商品下架？下架后不再出现在在售列表。`,
      '批量下架',
      { type: 'warning', confirmButtonText: '确认下架' }
    );
  } catch {
    return;
  }
  batchDelisting.value = true;
  let ok = 0;
  let fail = 0;
  try {
    for (const row of targets) {
      try {
        await api.request(
          `/api/v2/ops/admin/skus/${encodeURIComponent(row.skuId)}`,
          'PUT',
          toUpsertBody(row, 'INACTIVE')
        );
        const idx = items.value.findIndex((x) => x.skuId === row.skuId);
        if (idx >= 0) items.value[idx] = { ...items.value[idx], status: 'INACTIVE' };
        ok += 1;
      } catch {
        fail += 1;
      }
    }
    if (fail === 0) ElMessage.success(`已下架 ${ok} 个商品`);
    else ElMessage.warning(`下架完成：成功 ${ok}，失败 ${fail}`);
    clearSelection();
  } finally {
    batchDelisting.value = false;
  }
}

function enrollmentStatusCode(raw?: string): string {
  dictRuntimeEpoch.value;
  const text = String(raw || '').trim();
  if (!text) return 'MAPPING';
  for (const o of dictOptions('sku_enrollment_status')) {
    if (o.value === text || o.label === text) return o.value;
  }
  // 禁用项仍可能出现在历史导入文案里，回退编译期/全量标签
  const fallback: Record<string, string> = {
    草稿: 'DRAFT',
    映射中: 'MAPPING',
    已测试: 'TESTED',
    生产: 'PRODUCTION',
    DRAFT: 'DRAFT',
    MAPPING: 'MAPPING',
    TESTED: 'TESTED',
    PRODUCTION: 'PRODUCTION'
  };
  return fallback[text] || 'MAPPING';
}
const skuStatusByLabel: Record<string, string> = {
  上架: 'ACTIVE',
  下架: 'INACTIVE',
  停用: 'DISABLED',
  ACTIVE: 'ACTIVE',
  INACTIVE: 'INACTIVE',
  DISABLED: 'DISABLED'
};

function parseConfidence(raw: string | undefined, fallback: number) {
  if (raw == null || !String(raw).trim()) return fallback;
  const n = Number(String(raw).replaceAll('%', '').trim());
  if (Number.isNaN(n)) return fallback;
  return n > 1 ? n / 100 : n;
}

const { importing, importInput, onExport, onDownloadTemplate, triggerImport, onImportFile } =
  useListCsv({
    filePrefix: '商品',
    headers: [
      '商品编号',
      '名称',
      '基准价',
      '成本',
      '类目',
      '识别类名',
      '识别状态',
      '商品状态',
      '扣款阈值',
      '检测阈值'
    ],
    toRows: () =>
      pickSelected(displayList.value).map((row) => [
        row.skuId,
        row.skuName,
        ((row.priceCents || 0) / 100).toFixed(2),
        row.purchaseCostCents != null ? (row.purchaseCostCents / 100).toFixed(2) : '',
        categoryLabel(row.category) === '无' ? '' : categoryLabel(row.category),
        row.yoloClassName || '',
        enrollmentLabel(row.visionEnrollmentStatus),
        skuStatusLabel(row.status),
        formatConfidence(row.minChargeConfidence),
        formatConfidence(row.detectionMinConfidence ?? 0.5)
      ]),
    onImportRows: async (rows) => {
      let ok = 0;
      for (const row of rows) {
        const skuId = (row['商品编号'] || row.skuId || '').trim();
        const skuName = (row['名称'] || row.skuName || '').trim();
        if (!skuId || !skuName) continue;
        const yoloClassName =
          (row['识别类名'] || row.yoloClassName || '').trim() ||
          skuId.toLowerCase().replaceAll(/[^a-z0-9_]+/g, '_');
        const priceCents = Math.round((Number(row['基准价'] || row.priceYuan) || 0) * 100);
        const visionEnrollmentStatus = enrollmentStatusCode(
          row['识别状态'] || row.visionEnrollmentStatus
        );
        const status = skuStatusByLabel[row['商品状态'] || row.status] || 'ACTIVE';
        const costRaw = row['成本'] ?? row.purchaseCostYuan;
        const purchaseCostCents =
          costRaw != null && String(costRaw).trim() !== ''
            ? Math.round((Number(costRaw) || 0) * 100)
            : undefined;
        const body: UpsertSkuVisionEnrollmentRequest = {
          sku: {
            skuId,
            skuName,
            priceCents: priceCents || 350,
            visionEnabled: true,
            status,
            category: normalizeCategoryToCode(row['类目'] || row.category) || undefined,
            purchaseCostCents,
            minChargeConfidence: parseConfidence(row['扣款阈值'] || row.minChargeConfidence, 0.92),
            yoloClassName,
            visionEnrollmentStatus,
            detectionMinConfidence: parseConfidence(
              row['检测阈值'] || row.detectionMinConfidence,
              0.5
            )
          },
          yoloClassName,
          visionEnrollmentStatus,
          detectionMinConfidence: parseConfidence(
            row['检测阈值'] || row.detectionMinConfidence,
            0.5
          ),
          mappingSource: 'EDGE_CLASS'
        };
        await api.request<SkuCatalog>('/api/v2/ops/admin/sku-vision/enroll', 'POST', body);
        ok++;
      }
      clearSelection();
      await load();
      return ok;
    }
  });
const enrollDialog = ref(false);
const testDialog = ref(false);
const testPreview = ref<DevRecognitionPreviewDto | null>(null);
const testImageFile = ref<File | null>(null);
const classImageInput = ref<HTMLInputElement | null>(null);
const testImageInput = ref<HTMLInputElement | null>(null);
const classSuggestHint = ref('');

const enrollForm = reactive({
  existing: false,
  skuId: '',
  skuCode: undefined as number | undefined,
  skuName: '',
  priceCents: 350,
  yoloClassName: '',
  imageUrl: '',
  referenceImageUrls: '',
  visionEnrollmentStatus: 'MAPPING',
  detectionPercent: 50,
  chargePercent: 92
});

const testForm = reactive({
  skuId: '',
  skuName: '',
  status: '' as string,
  deviceId: 'CAB-DEMO-001'
});

function formatConfidence(value?: number) {
  if (value == null || Number.isNaN(value)) return '92%';
  return `${Math.round(value * 100)}%`;
}

function enrollmentLabel(status?: string) {
  return dictLabel('sku_enrollment_status', status || 'DRAFT');
}

function categoryLabel(code?: string | null) {
  if (!code) return '暂无';
  dictRuntimeEpoch.value;
  return displayLabel('category_code', code, '未分类');
}

/** 字典键值入库；兼容历史中文标签 */
function normalizeCategoryToCode(raw?: string | null): string {
  const text = String(raw || '').trim();
  if (!text) return '';
  dictRuntimeEpoch.value;
  for (const o of dictOptions('category_code')) {
    if (o.value === text || o.label === text) return o.value;
  }
  return text;
}

function onThumbError(e: Event) {
  const img = e.target as HTMLImageElement | null;
  if (!img) return;
  const placeholder = document.createElement('div');
  placeholder.className = 'sku-thumb sku-thumb--empty';
  placeholder.textContent = '无图';
  img.replaceWith(placeholder);
}

function skuStatusLabel(status?: string) {
  return displayLabel('sku_status', status, '未知状态');
}

function enrollmentTagType(status?: string) {
  if (status === 'PRODUCTION') return 'success';
  if (status === 'TESTED') return 'warning';
  return 'info';
}

function rowMeta(row: SkuCatalog) {
  return rowBySku.value[row.skuId];
}

function shortEnrollmentLabel(label: string) {
  const s = String(label || '');
  const cut = Math.min(
    s.indexOf('（') >= 0 ? s.indexOf('（') : s.length,
    s.indexOf('(') >= 0 ? s.indexOf('(') : s.length
  );
  const head = s.slice(0, cut).trim();
  return head || label;
}

function filterByEnrollment(status: string) {
  enrollmentFilter.value = !status || enrollmentFilter.value === status ? '' : status;
  search();
}

function urlsToJson(raw: string) {
  const parts = raw
    .split(/[\n,]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  return parts.length ? JSON.stringify(parts) : undefined;
}

function jsonToUrls(raw?: string) {
  if (!raw?.trim()) return '';
  try {
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed.filter(Boolean).join('\n');
  } catch {
    /* plain text */
  }
  return raw;
}

function skuActions(row: SkuCatalog): TableAction[] {
  const acts: TableAction[] = [];
  if (canEnroll.value) {
    acts.push({ key: 'edit', label: '编辑', icon: EditPen, type: 'primary' });
  }
  if (canEnroll.value || canVisionEdit.value) {
    acts.push({ key: 'test', label: '识别测试', icon: Upload, type: 'warning' });
  }
  const meta = rowMeta(row);
  if (canVisionEdit.value && meta?.nextStatus) {
    acts.push({
      key: 'advance',
      label:
        meta.nextStatus === 'PRODUCTION' ? '转生产' : `推进到${enrollmentLabel(meta.nextStatus)}`,
      icon: meta.nextStatus === 'PRODUCTION' ? CircleCheck : ArrowRight,
      type: 'success',
      overflow: true
    });
  } else if (canEnroll.value && row.visionEnrollmentStatus !== 'PRODUCTION') {
    acts.push({
      key: 'production',
      label: '转生产',
      icon: CircleCheck,
      type: 'success',
      overflow: true
    });
  }
  return acts;
}

function onSkuAction(key: string, row: SkuCatalog) {
  if (key === 'edit') openEnroll(row);
  else if (key === 'test') openTest(row);
  else if (key === 'advance') advanceRow(row);
  else if (key === 'production') markProduction(row);
}

function onPickSku(skuId: string) {
  const row = items.value.find((i) => i.skuId === skuId);
  if (!row) return;
  enrollForm.skuCode = row.skuCode;
  enrollForm.skuName = row.skuName;
  enrollForm.priceCents = row.priceCents || 350;
  enrollForm.yoloClassName = row.yoloClassName || '';
  enrollForm.imageUrl = row.imageUrl || '';
  enrollForm.referenceImageUrls = jsonToUrls(row.referenceImageUrlsJson);
  enrollForm.visionEnrollmentStatus = row.visionEnrollmentStatus || 'MAPPING';
  enrollForm.detectionPercent = Math.round((row.detectionMinConfidence ?? 0.5) * 100);
  enrollForm.chargePercent = Math.round((row.minChargeConfidence ?? 0.92) * 100);
}

function openEnroll(row?: SkuCatalog) {
  classSuggestHint.value = '';
  if (row) {
    enrollForm.existing = true;
    enrollForm.skuId = row.skuId;
    enrollForm.skuCode = row.skuCode;
    enrollForm.skuName = row.skuName;
    enrollForm.priceCents = row.priceCents;
    enrollForm.yoloClassName = row.yoloClassName || '';
    enrollForm.imageUrl = row.imageUrl || '';
    enrollForm.referenceImageUrls = jsonToUrls(row.referenceImageUrlsJson);
    enrollForm.visionEnrollmentStatus = row.visionEnrollmentStatus || 'MAPPING';
    enrollForm.detectionPercent = Math.round((row.detectionMinConfidence ?? 0.5) * 100);
    enrollForm.chargePercent = Math.round((row.minChargeConfidence ?? 0.92) * 100);
  } else {
    enrollForm.existing = false;
    enrollForm.skuId = '';
    enrollForm.skuCode = undefined;
    enrollForm.skuName = '';
    enrollForm.priceCents = 350;
    enrollForm.yoloClassName = '';
    enrollForm.imageUrl = '';
    enrollForm.referenceImageUrls = '';
    enrollForm.visionEnrollmentStatus = 'MAPPING';
    enrollForm.detectionPercent = 50;
    enrollForm.chargePercent = 92;
  }
  enrollDialog.value = true;
}

function openTest(row: SkuCatalog) {
  testForm.skuId = row.skuId;
  testForm.skuName = row.skuName;
  testForm.status = row.visionEnrollmentStatus || 'DRAFT';
  testPreview.value = null;
  testImageFile.value = null;
  if (testImageInput.value) testImageInput.value.value = '';
  testDialog.value = true;
}

async function suggestClassNameIfEmpty() {
  await suggestClassName(false);
}

async function suggestClassName(forceReplace: boolean) {
  if (!enrollForm.skuName.trim()) return;
  if (!forceReplace && enrollForm.yoloClassName.trim()) return;
  suggestingClass.value = true;
  try {
    const data = await api.request<{ yoloClassName: string }>(
      `/api/v2/ops/admin/sku-vision/suggest-class-name?skuName=${encodeURIComponent(enrollForm.skuName)}`,
      'GET'
    );
    if (!forceReplace && enrollForm.yoloClassName.trim()) return;
    if (
      forceReplace &&
      enrollForm.yoloClassName.trim() &&
      enrollForm.yoloClassName.trim() !== data.yoloClassName
    ) {
      try {
        await ElMessageBox.confirm(`将识别类名替换为「${data.yoloClassName}」？`, '覆盖类名', {
          confirmButtonText: '替换',
          cancelButtonText: '取消',
          type: 'warning'
        });
      } catch {
        return;
      }
    }
    enrollForm.yoloClassName = data.yoloClassName;
  } catch (e: any) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : '建议失败');
    }
  } finally {
    suggestingClass.value = false;
  }
}

function triggerClassImage() {
  classImageInput.value?.click();
}

async function onClassImagePick(ev: Event) {
  const input = ev.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  suggestingImage.value = true;
  classSuggestHint.value = '';
  try {
    const result = await uploadMultipart<{ yoloClassName?: string; reason?: string }>(
      '/api/v2/ops/admin/sku-vision/suggest-class',
      { skuName: enrollForm.skuName, image: file }
    );
    if (result.yoloClassName) {
      enrollForm.yoloClassName = result.yoloClassName;
      classSuggestHint.value = result.reason
        ? `建议：${result.yoloClassName}（${result.reason}）`
        : `建议：${result.yoloClassName}`;
    } else {
      classSuggestHint.value = result.reason || '未能生成建议';
    }
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '智能建议失败');
  } finally {
    suggestingImage.value = false;
    input.value = '';
  }
}

function onTestImagePick(ev: Event) {
  const input = ev.target as HTMLInputElement;
  testImageFile.value = input.files?.[0] || null;
  testPreview.value = null;
}

async function saveEnroll() {
  if (!enrollForm.skuId.trim()) {
    ElMessage.warning('请先选择关联商品');
    return;
  }
  if (!enrollForm.skuName.trim()) {
    ElMessage.warning('请填写商品名称');
    return;
  }
  if (!enrollForm.yoloClassName.trim()) {
    await suggestClassNameIfEmpty();
  }
  saving.value = true;
  try {
    const referenceImageUrlsJson = urlsToJson(enrollForm.referenceImageUrls);
    const skuId = enrollForm.skuId.trim();
    const existing = items.value.find((i) => i.skuId === skuId);
    const body: UpsertSkuVisionEnrollmentRequest = {
      sku: {
        skuId,
        skuName: enrollForm.skuName.trim(),
        priceCents: enrollForm.priceCents,
        visionEnabled: true,
        imageUrl: enrollForm.imageUrl || undefined,
        // Preserve catalog fields not editable in this dialog (avoid wipe/reactivate)
        status: existing?.status || 'ACTIVE',
        category: existing?.category,
        barcode: existing?.barcode,
        brand: existing?.brand,
        spec: existing?.spec,
        unit: existing?.unit || '件',
        purchaseCostCents: existing?.purchaseCostCents,
        weightGrams: existing?.weightGrams,
        description: existing?.description,
        shelfLifeDays: existing?.shelfLifeDays,
        nearExpiryDays: existing?.nearExpiryDays,
        blockSaleDaysBeforeExpiry: existing?.blockSaleDaysBeforeExpiry,
        storageType: existing?.storageType,
        nearExpiryPriceCents: existing?.nearExpiryPriceCents,
        minChargeConfidence: enrollForm.chargePercent / 100,
        yoloClassName: enrollForm.yoloClassName.trim(),
        visionEnrollmentStatus: enrollForm.visionEnrollmentStatus,
        detectionMinConfidence: enrollForm.detectionPercent / 100,
        referenceImageUrlsJson
      },
      yoloClassName: enrollForm.yoloClassName.trim(),
      visionEnrollmentStatus: enrollForm.visionEnrollmentStatus,
      detectionMinConfidence: enrollForm.detectionPercent / 100,
      referenceImageUrlsJson,
      mappingSource: 'EDGE_CLASS'
    };
    const updated = await api.request<SkuCatalog>(
      '/api/v2/ops/admin/sku-vision/enroll',
      'POST',
      body
    );
    const idx = items.value.findIndex((i) => i.skuId === updated.skuId);
    if (idx >= 0) items.value[idx] = updated;
    else items.value.push(updated);
    items.value.sort((a, b) => (a.skuCode ?? 0) - (b.skuCode ?? 0));
    enrollDialog.value = false;
    ElMessage.success('已保存识别入驻配置');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '保存失败');
  } finally {
    saving.value = false;
  }
}

async function applyRowUpdate(updated: SkuVisionEnrollmentRow) {
  const sku = updated.sku;
  const idx = items.value.findIndex((i) => i.skuId === sku.skuId);
  if (idx >= 0) items.value[idx] = sku;
  rowBySku.value = { ...rowBySku.value, [sku.skuId]: updated };
}

async function advanceRow(row: SkuCatalog) {
  const meta = rowMeta(row);
  const next = meta?.nextStatus;
  if (!next) {
    ElMessage.info('已处于生产状态（结算白名单已生效）');
    return;
  }
  try {
    if (next === 'PRODUCTION') {
      await ElMessageBox.confirm(
        '转生产表示进入结算白名单。端侧可换任意识别算法；mock/fallback/低置信仍进争议，不会静默扣款。确认继续？',
        '转生产确认',
        { type: 'warning', confirmButtonText: '确认转生产' }
      );
    }
    advancing.value = true;
    const updated = await api.request<SkuVisionEnrollmentRow>(
      `/api/v2/ops/admin/sku-vision/${encodeURIComponent(row.skuId)}/advance`,
      'POST'
    );
    await applyRowUpdate(updated);
    ElMessage.success(
      next === 'PRODUCTION'
        ? `${row.skuName} 已进入结算白名单（端侧 mock/低置信仍进争议）`
        : `${row.skuName} 已推进到「${enrollmentLabel(updated.sku.visionEnrollmentStatus)}」`
    );
  } catch (e) {
    if (e === 'cancel') return;
    ElMessage.error(e instanceof Error ? e.message : '推进失败');
  } finally {
    advancing.value = false;
  }
}

async function markProduction(row: SkuCatalog) {
  try {
    await ElMessageBox.confirm(
      '转生产表示进入结算白名单。端侧可换任意识别算法；mock/fallback/低置信仍进争议，不会静默扣款。确认继续？',
      '转生产确认',
      { type: 'warning', confirmButtonText: '确认转生产' }
    );
    const updated = await api.request<SkuCatalog>(
      `/api/v2/ops/admin/sku-vision/${encodeURIComponent(row.skuId)}/status?status=PRODUCTION`,
      'PATCH'
    );
    const idx = items.value.findIndex((i) => i.skuId === row.skuId);
    if (idx >= 0) items.value[idx] = updated;
    ElMessage.success(`${row.skuName} 已进入结算白名单`);
    await load();
  } catch (e) {
    if (e === 'cancel') return;
    ElMessage.error(e instanceof Error ? e.message : '更新失败');
  }
}

async function markTestedFromPreview() {
  if (!testForm.skuId) return;
  const matched = testPreview.value?.items?.some((item) => item.skuId === testForm.skuId);
  if (!matched) {
    ElMessage.warning('预览结果未包含当前商品，请更换图片或检查识别类名后再标记');
    return;
  }
  advancing.value = true;
  try {
    const updated = await api.request<SkuCatalog>(
      `/api/v2/ops/admin/sku-vision/${encodeURIComponent(testForm.skuId)}/status?status=TESTED`,
      'PATCH'
    );
    const idx = items.value.findIndex((i) => i.skuId === updated.skuId);
    if (idx >= 0) items.value[idx] = updated;
    testForm.status = 'TESTED';
    ElMessage.success('已标记为已测试，可继续转生产（进入结算白名单）');
    await load();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '更新失败');
  } finally {
    advancing.value = false;
  }
}

async function runTest() {
  if (!testImageFile.value) {
    ElMessage.warning('请选择测试图片');
    return;
  }
  testing.value = true;
  try {
    testPreview.value = await uploadMultipart<DevRecognitionPreviewDto>(
      '/api/v2/ops/recognition-preview',
      {
        image: testImageFile.value,
        ...(testForm.deviceId.trim() ? { deviceId: testForm.deviceId.trim() } : {})
      }
    );
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '识别失败');
  } finally {
    testing.value = false;
  }
}

async function onImageUpload(options: UploadRequestOptions) {
  const file = options.file as File;
  if (!file) return;
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.warning('单张图片不能超过 5MB');
    options.onError?.(new Error('too large') as never);
    return;
  }
  imageUploading.value = true;
  try {
    const uploaded = await uploadMultipart<FileAttachmentDto>('/api/v2/ops/admin/skus/image', {
      file
    });
    if (!uploaded?.url) {
      throw new Error('上传成功但未返回图片地址');
    }
    enrollForm.imageUrl = uploaded.url;
    ElMessage.success('主图已上传');
    options.onSuccess?.(uploaded as never);
  } catch (e) {
    const msg = e instanceof Error ? e.message : '上传失败';
    ElMessage.error(msg);
    options.onError?.(e as never);
  } finally {
    imageUploading.value = false;
  }
}

async function uploadMultipart<T>(path: string, fields: Record<string, string | File>): Promise<T> {
  const base = (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
  const form = new FormData();
  for (const [key, val] of Object.entries(fields)) {
    form.append(key === 'image' ? 'image' : key, val);
  }
  const res = await authFetch(`${base}${path}`, {
    method: 'POST',
    body: form
  });
  const json = await res.json().catch(() => ({}));
  if (!res.ok || json.code !== 0) {
    throw new Error(json.message || `请求失败 (${res.status})`);
  }
  return json.data as T;
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (enrollmentFilter.value) query.enrollment = enrollmentFilter.value;
  if (saleTab.value && saleTab.value !== 'ACTIVE') query.sale = saleTab.value;
  router.replace({ query });
}

function search() {
  page.value = 1;
  syncRouteQuery();
  load();
}

function resetFilters() {
  keyword.value = '';
  enrollmentFilter.value = '';
  saleTab.value = 'ACTIVE';
  page.value = 1;
  syncRouteQuery();
  load();
}

function applyRouteQuery() {
  let changed = false;
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  const qEnrollment = typeof route.query.enrollment === 'string' ? route.query.enrollment : '';
  if (qEnrollment !== enrollmentFilter.value) {
    enrollmentFilter.value = qEnrollment;
    changed = true;
  }
  const qSale =
    typeof route.query.sale === 'string' && route.query.sale ? route.query.sale : 'ACTIVE';
  if (qSale !== saleTab.value) {
    saleTab.value = qSale;
    changed = true;
  }
  return changed;
}

async function load() {
  loading.value = true;
  try {
    const [rowsRes, pipeline] = await Promise.all([
      api.request<{ items: SkuVisionEnrollmentRow[]; total: number }>(
        `/api/v2/ops/admin/sku-vision/rows?${queryParams()}`,
        'GET'
      ),
      api
        .request<SkuVisionEnrollmentPipeline>('/api/v2/ops/admin/sku-vision/pipeline', 'GET')
        .catch(() => null)
    ]);
    items.value = (rowsRes.items || []).map((r) => r.sku);
    total.value = Number(rowsRes.total) || 0;
    const map: Record<string, SkuVisionEnrollmentRow> = {};
    for (const r of rowsRes.items || []) map[r.sku.skuId] = r;
    rowBySku.value = map;
    if (pipeline) {
      pipelineHint.value = pipeline.modelPipelineHint || pipelineHint.value;
      if (pipeline.steps?.length) pipelineStepsFromApi.value = pipeline.steps;
    }
    clearSelection();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '加载失败');
  } finally {
    listHydrated.value = true;
    loading.value = false;
  }
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  page.value = 1;
  await load();
}

watch(
  () => [route.query.keyword, route.query.enrollment, route.query.sale] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

onMounted(() => {
  applyRouteQuery();
  void loadCatalogOptions();
  load();
});
onActivated(() => {
  void reloadFromRouteQuery();
});
</script>

<style scoped>
.list-lead {
  margin: 0 0 8px;
}
.enroll-chips {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
}
.enroll-chip {
  appearance: none;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 28px;
  padding: 0 10px;
  border: 1px solid var(--el-border-color);
  border-radius: 999px;
  background: var(--el-fill-color-blank);
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1;
  cursor: pointer;
}
.enroll-chip:hover {
  border-color: var(--el-color-primary-light-5);
  color: var(--el-color-primary);
}
.enroll-chip.active {
  border-color: var(--el-color-primary);
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  font-weight: 600;
}
.enroll-chip__idx {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 650;
  color: #fff;
  background: var(--el-color-primary);
}
.help-toggle {
  margin-left: 4px;
}
.sku-help {
  margin-top: 8px;
  display: grid;
  gap: 8px;
}
.risk-alert {
  margin: 0;
}
.status-tabs {
  margin: 0 0 10px;
}
.pipe-cell {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
}
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
.sku-thumb {
  width: 40px;
  height: 40px;
  border-radius: 6px;
  object-fit: cover;
  display: block;
  margin: 0 auto;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
}
.sku-thumb--empty {
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--el-text-color-placeholder);
  font-size: 11px;
  border-style: dashed;
  object-fit: unset;
}
.sku-image-field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  width: 100%;
}
.sku-image-field .el-upload {
  display: inline-flex;
}
.sku-preview {
  margin-top: 4px;
  width: 96px;
  height: 96px;
  border-radius: 8px;
  object-fit: contain;
  display: block;
  background: var(--el-fill-color-light);
  border: 1px solid var(--el-border-color-lighter);
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
  font-weight: 650;
}
.link-cell:hover {
  text-decoration: underline;
}
.muted {
  color: var(--el-text-color-secondary);
}
.sku-table {
  font-size: 14px;
}
.inline-field {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.hidden-input {
  display: none;
}
.field-hint {
  color: var(--layout-muted);
  font-size: 13px;
  margin-top: 4px;
}
.test-table {
  margin-top: 12px;
  font-size: 14px;
}
</style>
