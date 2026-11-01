<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">营销活动</span>
            <span class="hint"
              >v1 支持「商品立减 / 商品折扣 / 一口价」：指定参与商品、投放范围与时间窗；
              命中活动价的订单不再叠加优惠券；启用前必须配置轮播横幅</span
            >
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button
            v-if="crud.selectedKeys.length"
            v-hasPermi="['ops:promotion:stop']"
            type="warning"
            @click="batchDisable"
          >
            批量停用 ({{ crud.selectedKeys.length }})
          </el-button>
          <el-button v-hasPermi="['ops:promotion:create']" type="primary" @click="openCreate"
            >新建活动</el-button
          >
        </div>
      </div>
    </template>

    <el-form inline class="filter-bar filter-bar--compact" @submit.prevent="search">
      <el-form-item label="关键词">
        <el-input
          v-model="keyword"
          clearable
          placeholder="名称 / ID"
          style="width: 180px"
          @keyup.enter="search"
          @clear="search"
        />
      </el-form-item>
      <el-form-item label="状态">
        <el-select
          v-model="statusFilter"
          clearable
          placeholder="全部"
          style="width: 120px"
          @change="search"
        >
          <el-option :label="displayLabel('enable_status', 'ACTIVE')" value="ACTIVE" />
          <el-option :label="displayLabel('enable_status', 'INACTIVE')" value="INACTIVE" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
      </el-form-item>
    </el-form>

    <div class="table-scroll">
      <div class="table-scroll-inner">
        <CrudTable
          :table="crud"
          row-key="activityId"
          selectable
          :actions="rowActions"
          :action-width="150"
          actions-testid="promotion"
          empty-text="暂无活动"
          sort-field-label="活动编号"
          :csv="csvOptions"
          @action="onAction"
        >
          <el-table-column prop="activityId" label="活动编号" width="80" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-id">{{ row.activityId }}</span>
            </template>
          </el-table-column>
          <el-table-column label="活动" min-width="160" class-name="col-text">
            <template #default="{ row }">{{ row.activityName || '无' }}</template>
          </el-table-column>
          <el-table-column
            label="类型"
            width="120"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag size="small" effect="plain">{{
                displayLabel('promotion_type', row.activityType, '未知类型')
              }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="活动价" min-width="130" class-name="col-text">
            <template #default="{ row }">{{ priceLabel(row) }}</template>
          </el-table-column>
          <el-table-column
            label="参与商品"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ skuCountLabel(row) }}</template>
          </el-table-column>
          <el-table-column
            align="center"
            label="时间"
            min-width="200"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <span class="cell-datetime"
                >{{ formatTime(row.startTime) }} ~ {{ formatTime(row.endTime) }}</span
              >
            </template>
          </el-table-column>
          <el-table-column label="预算" width="110" align="center" class-name="col-money">
            <template #default="{ row }">{{ yuan(row.budgetCents) }}</template>
          </el-table-column>
          <el-table-column label="已使用" width="110" align="center" class-name="col-money">
            <template #default="{ row }">{{ yuan(row.usedCents) }}</template>
          </el-table-column>
          <el-table-column label="剩余预算" width="120" align="center" class-name="col-money">
            <template #default="{ row }">
              <div class="budget-remain">
                <span>{{
                  yuan(Math.max(0, Number(row.budgetCents || 0) - Number(row.usedCents || 0)))
                }}</span>
                <el-tag
                  v-if="isBudgetExhausted(row)"
                  type="warning"
                  size="small"
                  effect="plain"
                  class="budget-full-tag"
                  >预算已满</el-tag
                >
              </div>
            </template>
          </el-table-column>
          <el-table-column
            label="每人限次"
            width="90"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">{{ row.userLimit || '不限' }}</template>
          </el-table-column>
          <el-table-column label="投放范围" min-width="120" class-name="col-text">
            <template #default="{ row }">
              <span class="cell-ellipsis" :title="deviceScopeLabel(row) || ''">{{
                deviceScopeLabel(row)
              }}</span>
            </template>
          </el-table-column>
          <el-table-column
            label="状态"
            width="88"
            align="center"
            class-name="col-status"
            label-class-name="col-status"
          >
            <template #default="{ row }">
              <el-tag :type="isEnabled(row.status) ? 'success' : 'info'" size="small">
                {{ statusLabel(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
        </CrudTable>
      </div>
    </div>

    <el-dialog
      v-model="showDialog"
      :title="editingId ? '编辑活动' : '新建活动'"
      destroy-on-close
      append-to-body
      align-center
      :close-on-click-modal="false"
      class="dialog-wide promo-dialog"
    >
      <el-form :model="form" label-width="auto">
        <!-- CB-028 弹窗规范：dialog-wide + 字段两列（长文本/长下拉独占整行） -->
        <el-row :gutter="16">
          <el-col :span="24">
            <el-form-item label="活动名称" required
              ><el-input v-model="form.activityName" maxlength="80"
            /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="活动类型" required>
              <el-select v-model="form.activityType" style="width: 100%" teleported>
                <el-option
                  v-for="item in activityTypeOptions"
                  :key="item.value"
                  :label="item.label"
                  :value="item.value"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="参与商品" required>
              <el-select
                v-model="form.skuIds"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                placeholder="选择参与活动的商品"
                style="width: 100%"
              >
                <el-option
                  v-for="s in skuOptions"
                  :key="s.skuId"
                  :label="skuLabel(s)"
                  :value="s.skuId"
                />
              </el-select>
              <div class="field-hint">只对选中 SKU 生效；活动价优于当前单价时才计价</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item :label="priceFieldLabel" required>
              <el-input-number
                v-if="form.activityType === 'SKU_REDUCE'"
                v-model="form.reduceYuan"
                :min="0.01"
                :step="0.5"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
              <el-input-number
                v-else-if="form.activityType === 'SKU_DISCOUNT'"
                v-model="form.discountZhe"
                :min="0.1"
                :max="9.9"
                :step="0.5"
                :precision="1"
                controls-position="right"
                style="width: 100%"
              />
              <el-input-number
                v-else
                v-model="form.fixedPriceYuan"
                :min="0.01"
                :step="0.5"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
              <div class="field-hint">{{ priceFieldHint }}</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="每人限购(单)">
              <el-input-number
                v-model="form.userLimit"
                :min="0"
                :max="100"
                controls-position="right"
                style="width: 100%"
              />
              <div class="field-hint">0 = 不限</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="活动总单数">
              <el-input-number
                v-model="form.totalOrderLimit"
                :min="0"
                :max="100000"
                controls-position="right"
                style="width: 100%"
              />
              <div class="field-hint">0 = 不限</div>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="预算(元)">
              <el-input-number
                v-model="form.budgetYuan"
                :min="0"
                :step="1"
                :precision="2"
                controls-position="right"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="开始时间" required
              ><el-date-picker
                v-model="form.startTime"
                type="datetime"
                teleported
                placement="bottom-start"
                :z-index="5000"
                :popper-options="{ strategy: 'fixed' }"
                style="width: 100%"
            /></el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="结束时间" required
              ><el-date-picker
                v-model="form.endTime"
                type="datetime"
                teleported
                placement="bottom-start"
                :z-index="5000"
                :popper-options="{ strategy: 'fixed' }"
                style="width: 100%"
            /></el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="投放范围">
              <div class="scope-field">
                <el-radio-group v-model="form.deviceScope">
                  <el-radio value="ALL">全部柜机</el-radio>
                  <el-radio value="SPECIFIC">指定柜机</el-radio>
                  <el-radio value="MERCHANT">指定商户</el-radio>
                </el-radio-group>
                <div class="field-hint">指定商户 = 该商户名下所有柜机可参与活动</div>
              </div>
            </el-form-item>
          </el-col>
          <el-col v-if="form.deviceScope === 'SPECIFIC'" :span="24">
            <el-form-item label="选择柜机">
              <el-select
                v-model="form.deviceIds"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                placeholder="选择柜机"
                style="width: 100%"
              >
                <el-option
                  v-for="d in deviceOptions"
                  :key="d.deviceId"
                  :label="d.deviceName || d.deviceId"
                  :value="d.deviceId"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="form.deviceScope === 'MERCHANT'" :span="24">
            <el-form-item label="选择商户">
              <el-select
                v-model="form.merchantIds"
                multiple
                filterable
                collapse-tags
                collapse-tags-tooltip
                placeholder="选择商户"
                style="width: 100%"
              >
                <el-option
                  v-for="m in merchantOptions"
                  :key="m.merchantId"
                  :label="m.merchantName || m.merchantId"
                  :value="m.merchantId"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="24">
            <el-form-item label="描述"
              ><el-input v-model="form.description" type="textarea" maxlength="200"
            /></el-form-item>
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="showDialog = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="placementVisible"
      :title="`展示位配置 · ${placementActivityName}`"
      destroy-on-close
      append-to-body
      align-center
      :close-on-click-modal="false"
      class="dialog-wide"
    >
      <el-form label-width="auto">
        <div class="form-group-title">轮播横幅（必选）</div>
        <el-row :gutter="16">
          <el-col :span="16">
            <el-form-item label="横幅图片" required>
              <el-select
                v-model="placementForm.bannerAssetId"
                filterable
                placeholder="选择图片素材"
                style="width: 100%"
              >
                <el-option
                  v-for="a in imageAssets"
                  :key="a.assetId"
                  :label="assetLabel(a)"
                  :value="a.assetId"
                />
              </el-select>
              <div class="field-hint">{{ PLACEMENT_RULES.BANNER.hint }}</div>
            </el-form-item>
          </el-col>
          <el-col :span="8">
            <el-form-item label="上传">
              <el-button :loading="uploading === 'BANNER'" @click="pickFile('BANNER')"
                >上传新图</el-button
              >
              <input
                ref="bannerFileInput"
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                hidden
                @change="onFilePicked"
              />
            </el-form-item>
          </el-col>
          <el-col v-if="bannerPreview" :span="24">
            <el-image
              :src="bannerPreview"
              fit="contain"
              class="placement-preview"
              :preview-src-list="[bannerPreview]"
            />
          </el-col>
        </el-row>

        <div class="form-group-title">弹窗（开门前 / 关门后 二选一，可不配）</div>
        <el-row :gutter="16">
          <el-col :span="8">
            <el-form-item label="弹窗位置">
              <el-radio-group v-model="placementForm.popupPosition">
                <el-radio value="NONE">不弹窗</el-radio>
                <el-radio value="POPUP_BEFORE_OPEN">开门前</el-radio>
                <el-radio value="POPUP_AFTER_CLOSE">关门后</el-radio>
              </el-radio-group>
            </el-form-item>
          </el-col>
          <el-col v-if="placementForm.popupPosition !== 'NONE'" :span="10">
            <el-form-item label="弹窗图片" required>
              <el-select
                v-model="placementForm.popupAssetId"
                filterable
                placeholder="选择图片素材"
                style="width: 100%"
              >
                <el-option
                  v-for="a in imageAssets"
                  :key="a.assetId"
                  :label="assetLabel(a)"
                  :value="a.assetId"
                />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col v-if="placementForm.popupPosition !== 'NONE'" :span="6">
            <el-form-item label="上传">
              <el-button :loading="uploading === 'POPUP'" @click="pickFile('POPUP')"
                >上传新图</el-button
              >
              <input
                ref="popupFileInput"
                type="file"
                accept="image/jpeg,image/png,image/webp,image/gif"
                hidden
                @change="onFilePicked"
              />
            </el-form-item>
          </el-col>
          <el-col v-if="placementForm.popupPosition !== 'NONE'" :span="24">
            <div class="field-hint placement-hint">{{ PLACEMENT_RULES.POPUP.hint }}</div>
          </el-col>
          <el-col v-if="popupPreview" :span="24">
            <el-image
              :src="popupPreview"
              fit="contain"
              class="placement-preview"
              :preview-src-list="[popupPreview]"
            />
          </el-col>
        </el-row>
      </el-form>
      <template #footer>
        <el-button @click="placementVisible = false">取消</el-button>
        <el-button type="primary" :loading="placementSaving" @click="savePlacements"
          >保存展示位</el-button
        >
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { EditPen, Picture, SwitchButton } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { dictOptions, displayLabel } from '@aicabinet/shared-dict';
import { api, authFetch } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import {
  centsToYuanFixed,
  centsToYuanNumber,
  discountBpsToZhe,
  discountZheText,
  yuanTextZero as yuan,
  yuanToCents,
  zheToDiscountBps
} from '@/utils/display';
import CrudTable, { type CrudCsvOptions, type CrudRowAction } from '@/components/CrudTable.vue';
import { useCrudTable } from '@/composables/useCrudTable';
import { errorMessage } from '@/utils/error-message';
import type { OpenApiPromotionActivityDto } from '@aicabinet/shared-types';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
import { validateAdAssetFile } from '@/utils/upload-validate';
import {
  PLACEMENT_RULES,
  checkImageSize,
  type PlacementPosition
} from '@/utils/promotion-placement';

type PromotionRow = OpenApiPromotionActivityDto & {
  reduceCents?: number | null;
  discountBps?: number | null;
  fixedPriceCents?: number | null;
};

interface SkuOption {
  skuId: string;
  skuName?: string;
  priceCents?: number;
  status?: string;
}

interface MerchantOption {
  merchantId: string;
  merchantName?: string;
}

interface ImageAsset {
  assetId: number;
  title: string;
  assetType?: string;
  status?: string;
  previewUrl?: string;
  imageWidth?: number | null;
  imageHeight?: number | null;
}

interface PlacementDto {
  placementId: number;
  activityId: number;
  position: string;
  assetId: number;
  imageWidth?: number | null;
  imageHeight?: number | null;
  previewUrl?: string;
}

const SKU_TYPES = new Set(['SKU_REDUCE', 'SKU_DISCOUNT', 'SKU_FIXED_PRICE']);

const route = useRoute();
const router = useRouter();
const saving = ref(false);
const keyword = ref('');
const statusFilter = ref('');
const showDialog = ref(false);
const editingId = ref<number | null>(null);
const deviceOptions = ref<{ deviceId: string; deviceName?: string }[]>([]);
const merchantOptions = ref<MerchantOption[]>([]);
const skuOptions = ref<SkuOption[]>([]);

// 列表状态机统一交给 CrudTable：分页 / 排序 / 多选 / 竞态 / 空态 全部内建
const crud = useCrudTable<PromotionRow>({
  rowKey: (r) => r.activityId ?? 0,
  // 首查前需先应用路由查询参数（applyRouteQuery），故关闭 autoLoad 由 onMounted 显式首查
  autoLoad: false,
  fetchPage: (params) => {
    const q = new URLSearchParams({ page: String(params.page), size: String(params.size) });
    if (keyword.value.trim()) q.set('q', keyword.value.trim());
    if (statusFilter.value) q.set('status', statusFilter.value);
    return api.request<{ items: PromotionRow[]; total: number }>(
      AdminEndpoints.promotionsList(q),
      'GET'
    );
  },
  sort: { prop: 'activityId', mode: 'local' }
});

const activityTypeOptions = computed(() => {
  const options = dictOptions('promotion_type').filter((o) => SKU_TYPES.has(o.value));
  // 编辑历史券类活动时保留原类型，避免下拉显示裸码（v1 不新建这些类型）
  const current = form.value.activityType;
  if (current && !SKU_TYPES.has(current) && !options.some((o) => o.value === current)) {
    options.push({ value: current, label: displayLabel('promotion_type', current, current) });
  }
  return options;
});

const priceFieldLabel = computed(() =>
  form.value.activityType === 'SKU_REDUCE'
    ? '立减金额(元)'
    : form.value.activityType === 'SKU_DISCOUNT'
      ? '折扣(折)'
      : '一口价(元)'
);

const priceFieldHint = computed(() => {
  switch (form.value.activityType) {
    case 'SKU_REDUCE':
      return '在原价基础上直接减，减后最低 1 分';
    case 'SKU_DISCOUNT':
      return '如 8.5 表示 8.5 折（原价 15% 优惠）';
    case 'SKU_FIXED_PRICE':
      return '固定活动价，不低于 0.01 元；高于原价时不生效';
    default:
      return 'v1 仅对商品立减 / 商品折扣 / 一口价生效';
  }
});

const emptyForm = () => ({
  activityName: '',
  activityType: 'SKU_REDUCE',
  startTime: '' as string | Date,
  endTime: '' as string | Date,
  budgetYuan: 0,
  userLimit: 1,
  totalOrderLimit: 0,
  deviceScope: 'ALL',
  deviceIds: [] as string[],
  merchantIds: [] as string[],
  skuIds: [] as string[],
  reduceYuan: 1,
  discountZhe: 8.5,
  fixedPriceYuan: 0.01,
  description: ''
});
const form = ref(emptyForm());

const CSV_HEADERS = [
  '活动名称',
  '类型',
  '开始时间',
  '结束时间',
  '立减(元)',
  '折扣(折)',
  '一口价(元)',
  '参与商品ID(逗号分隔)',
  '预算(元)',
  '每人限购',
  '描述',
  '状态'
];

interface RuleConfigShape {
  deviceIds: string[];
  merchantIds: string[];
  skuIds: string[];
  totalOrderLimit: number;
}

function parseRuleConfig(ruleConfig?: string | Record<string, unknown> | null): RuleConfigShape {
  const empty: RuleConfigShape = { deviceIds: [], merchantIds: [], skuIds: [], totalOrderLimit: 0 };
  if (!ruleConfig) return empty;
  try {
    const obj =
      typeof ruleConfig === 'string'
        ? (JSON.parse(ruleConfig) as Record<string, unknown>)
        : ruleConfig;
    const list = (v: unknown): string[] =>
      Array.isArray(v) ? v.map(String).filter((x) => x.trim() !== '') : [];
    const total = Number(obj.totalOrderLimit);
    return {
      deviceIds: list(obj.deviceIds),
      merchantIds: list(obj.merchantIds),
      skuIds: list(obj.skuIds),
      totalOrderLimit: Number.isFinite(total) && total > 0 ? total : 0
    };
  } catch {
    return empty;
  }
}

function formatTime(t: string) {
  // Instant(UTC) → 北京时间（此前截断慢 8 小时，且与编辑框 new Date() 回显不一致）
  return formatDateTimeMinute(t);
}

function isEnabled(status?: string) {
  return status === 'ACTIVE';
}

/** 配置仍启用，但预算已占满：派生态（对齐投放侧「启用≠还能花」） */
function isBudgetExhausted(row: { status?: string; budgetCents?: number; usedCents?: number }) {
  if (!isEnabled(row.status)) return false;
  const budget = Number(row.budgetCents || 0);
  if (budget <= 0) return false;
  return Number(row.usedCents || 0) >= budget;
}
function statusLabel(status?: string) {
  return displayLabel('enable_status', status, '暂无');
}

function deviceScopeLabel(row: { deviceScope?: string; ruleConfig?: string }) {
  if (!row.deviceScope || row.deviceScope === 'ALL') return '全部柜机';
  const rule = parseRuleConfig(row.ruleConfig);
  if (row.deviceScope === 'SPECIFIC') {
    return rule.deviceIds.length ? `${rule.deviceIds.length} 台定向` : '指定柜机';
  }
  if (row.deviceScope === 'MERCHANT') {
    return rule.merchantIds.length ? `${rule.merchantIds.length} 个商户` : '指定商户';
  }
  return row.deviceScope;
}

function skuCountLabel(row: PromotionRow) {
  if (!SKU_TYPES.has(String(row.activityType || ''))) return '—';
  const n = parseRuleConfig(row.ruleConfig).skuIds.length;
  return n ? `${n} 个` : '未选';
}

function priceLabel(row: PromotionRow) {
  switch (row.activityType) {
    case 'SKU_REDUCE':
      return row.reduceCents ? `立减 ${yuan(row.reduceCents)}` : '未配置';
    case 'SKU_DISCOUNT':
      return row.discountBps ? discountZheText(row.discountBps) : '未配置';
    case 'SKU_FIXED_PRICE':
      return row.fixedPriceCents ? `一口价 ${yuan(row.fixedPriceCents)}` : '未配置';
    default:
      return '—';
  }
}

function skuLabel(s: SkuOption) {
  const price = typeof s.priceCents === 'number' ? `（${yuan(s.priceCents)}）` : '';
  return `${s.skuName || s.skuId}${price}`;
}

async function loadDevices() {
  try {
    deviceOptions.value =
      (await api.request<{ deviceId: string; deviceName?: string }[]>(
        AdminEndpoints.devicesRef,
        'GET'
      )) || [];
  } catch {
    deviceOptions.value = [];
  }
}

async function loadMerchants() {
  try {
    const data = await api.request<{ items?: MerchantOption[] }>(
      AdminEndpoints.merchantsCatalog,
      'GET'
    );
    merchantOptions.value = data.items || [];
  } catch {
    merchantOptions.value = [];
  }
}

async function loadSkus() {
  try {
    const data = await api.request<SkuOption[] | { items?: SkuOption[] }>(
      AdminEndpoints.skusCatalogPage,
      'GET'
    );
    const list = Array.isArray(data) ? data : data.items || [];
    skuOptions.value = list.filter((s) => !s.status || s.status === 'ACTIVE');
  } catch {
    skuOptions.value = [];
  }
}

function rowActions(row: PromotionRow): CrudRowAction[] {
  const acts: CrudRowAction[] = [];
  if (!isEnabled(row.status) && row.status !== 'ENDED') {
    acts.push({
      key: 'edit',
      label: '编辑',
      icon: EditPen,
      type: 'primary',
      perm: 'ops:promotion:edit'
    });
    acts.push({
      key: 'placements',
      label: '展示位',
      icon: Picture,
      type: 'primary',
      perm: 'ops:promotion:edit'
    });
  }
  if (row.status !== 'ENDED') {
    if (isEnabled(row.status)) {
      acts.push({
        key: 'toggle',
        label: displayLabel('enable_status', 'INACTIVE'),
        icon: SwitchButton,
        type: 'warning',
        perm: 'ops:promotion:stop'
      });
    } else {
      acts.push({
        key: 'toggle',
        label: displayLabel('enable_status', 'ACTIVE'),
        icon: SwitchButton,
        type: 'success',
        perm: 'ops:promotion:launch'
      });
    }
  }
  return acts;
}

function onAction({ key, row }: { key: string; row: PromotionRow }) {
  if (key === 'edit') openEdit(row);
  else if (key === 'placements') void openPlacements(row);
  else if (key === 'toggle') void onToggleStatus(row);
}

function openCreate() {
  editingId.value = null;
  form.value = emptyForm();
  showDialog.value = true;
}

function openEdit(row: PromotionRow) {
  editingId.value = row.activityId ?? null;
  const rule = parseRuleConfig(row.ruleConfig);
  form.value = {
    activityName: row.activityName || '',
    activityType: row.activityType || 'SKU_REDUCE',
    startTime: row.startTime ? new Date(row.startTime) : '',
    endTime: row.endTime ? new Date(row.endTime) : '',
    budgetYuan: centsToYuanNumber(row.budgetCents),
    userLimit: row.userLimit ?? 1,
    totalOrderLimit: rule.totalOrderLimit,
    deviceScope:
      row.deviceScope === 'SPECIFIC' || row.deviceScope === 'MERCHANT' ? row.deviceScope : 'ALL',
    deviceIds: rule.deviceIds,
    merchantIds: rule.merchantIds,
    skuIds: rule.skuIds,
    reduceYuan: row.reduceCents ? centsToYuanNumber(row.reduceCents) : 1,
    discountZhe: row.discountBps ? discountBpsToZhe(row.discountBps) : 8.5,
    fixedPriceYuan: row.fixedPriceCents ? centsToYuanNumber(row.fixedPriceCents) : 0.01,
    description: row.description || ''
  };
  showDialog.value = true;
}

function toCents(yuanValue: number) {
  return Math.max(0, Math.round(Number(yuanValue || 0) * 100));
}

async function onSubmit() {
  const f = form.value;
  if (!f.activityName?.trim()) return ElMessage.warning('请填写活动名称');
  if (!f.startTime || !f.endTime) return ElMessage.warning('请选择活动时间');
  const start = new Date(f.startTime);
  const end = new Date(f.endTime);
  if (Number.isNaN(start.getTime()) || Number.isNaN(end.getTime()))
    return ElMessage.warning('活动时间无效');
  if (end <= start) return ElMessage.warning('结束时间需晚于开始时间');
  if (!f.skuIds.length) return ElMessage.warning('请选择参与活动的商品');
  if (f.deviceScope === 'SPECIFIC' && !f.deviceIds.length) {
    return ElMessage.warning('指定柜机时请至少选择一台柜机');
  }
  if (f.deviceScope === 'MERCHANT' && !f.merchantIds.length) {
    return ElMessage.warning('指定商户时请至少选择一个商户');
  }
  let reduceCents: number | null = null;
  let discountBps: number | null = null;
  let fixedPriceCents: number | null = null;
  if (f.activityType === 'SKU_REDUCE') {
    reduceCents = toCents(f.reduceYuan);
    if (reduceCents < 1) return ElMessage.warning('立减金额需大于 0');
  } else if (f.activityType === 'SKU_DISCOUNT') {
    discountBps = zheToDiscountBps(f.discountZhe);
    if (discountBps <= 0 || discountBps >= 10000) return ElMessage.warning('折扣需在 0-10 折之间');
  } else if (f.activityType === 'SKU_FIXED_PRICE') {
    fixedPriceCents = toCents(f.fixedPriceYuan);
    if (fixedPriceCents < 1) return ElMessage.warning('一口价需大于 0（不允许 0 元购）');
  }
  const body = {
    activityName: f.activityName.trim(),
    activityType: f.activityType,
    startTime: start.toISOString(),
    endTime: end.toISOString(),
    budgetCents: yuanToCents(f.budgetYuan) ?? 0,
    userLimit: f.userLimit,
    deviceScope: f.deviceScope,
    ruleConfig: JSON.stringify({
      deviceIds: f.deviceScope === 'SPECIFIC' ? f.deviceIds : [],
      merchantIds: f.deviceScope === 'MERCHANT' ? f.merchantIds : [],
      skuIds: f.skuIds,
      totalOrderLimit: f.totalOrderLimit || 0
    }),
    description: f.description,
    reduceCents,
    discountBps,
    fixedPriceCents
  };
  saving.value = true;
  try {
    if (editingId.value) {
      await api.request(AdminEndpoints.promotion(editingId.value), 'PUT', body);
      ElMessage.success('已更新');
      showDialog.value = false;
    } else {
      const created = await api.request<PromotionRow>(AdminEndpoints.promotions, 'POST', body);
      ElMessage.success('创建成功，请继续配置展示位');
      showDialog.value = false;
      if (created?.activityId) {
        await openPlacements(created);
      }
    }
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存失败'));
  } finally {
    saving.value = false;
  }
}

async function onToggleStatus(row: PromotionRow) {
  const enable = !isEnabled(row.status);
  const action = displayLabel('enable_status', enable ? 'ACTIVE' : 'INACTIVE');
  try {
    await ElMessageBox.confirm(`确认${action}活动「${row.activityName}」？`, '活动状态', {
      type: 'warning'
    });
    if (enable) {
      await api.request(AdminEndpoints.promotionLaunch(row.activityId ?? 0), 'POST');
    } else {
      await api.request(AdminEndpoints.promotionStop(row.activityId ?? 0), 'POST');
    }
    ElMessage.success(`已${action}`);
    await crud.load();
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(e instanceof Error ? e.message : `${action}失败`);
    }
  }
}

async function batchDisable() {
  const targets = crud.items.filter(
    (r) => r.activityId != null && crud.selectedKeys.includes(r.activityId) && isEnabled(r.status)
  );
  if (!targets.length) return ElMessage.warning('请勾选已启用的活动');
  try {
    await ElMessageBox.confirm(`确认停用选中的 ${targets.length} 个活动？`, '批量停用');
    for (const row of targets) {
      await api.request(AdminEndpoints.promotionStop(row.activityId ?? 0), 'POST');
    }
    ElMessage.success(`已停用 ${targets.length} 个活动`);
    await crud.load();
  } catch (e: unknown) {
    if (e !== 'cancel' && e !== 'close') {
      ElMessage.error(errorMessage(e, '批量停用失败'));
    }
  }
}

// ── 展示位（CB-082）─────────────────────────────────────────────
const placementVisible = ref(false);
const placementActivityId = ref<number | null>(null);
const placementActivityName = ref('');
const placementSaving = ref(false);
const imageAssets = ref<ImageAsset[]>([]);
const placementForm = ref<{
  bannerAssetId: number | null;
  popupPosition: 'NONE' | 'POPUP_BEFORE_OPEN' | 'POPUP_AFTER_CLOSE';
  popupAssetId: number | null;
}>({ bannerAssetId: null, popupPosition: 'NONE', popupAssetId: null });
const uploading = ref<'BANNER' | 'POPUP' | ''>('');
const uploadTarget = ref<'BANNER' | 'POPUP'>('BANNER');
const bannerFileInput = ref<HTMLInputElement | null>(null);
const popupFileInput = ref<HTMLInputElement | null>(null);

function assetById(id: number | null | undefined): ImageAsset | undefined {
  return id ? imageAssets.value.find((a) => a.assetId === id) : undefined;
}

const bannerPreview = computed(() => assetById(placementForm.value.bannerAssetId)?.previewUrl);
const popupPreview = computed(() => assetById(placementForm.value.popupAssetId)?.previewUrl);

function assetLabel(a: ImageAsset) {
  const size = a.imageWidth && a.imageHeight ? ` · ${a.imageWidth}×${a.imageHeight}` : '';
  return `${a.title}${size}`;
}

async function loadImageAssets() {
  try {
    const q = new URLSearchParams({ page: '0', size: '500' });
    const data = await api.request<{ items?: ImageAsset[] }>(AdminEndpoints.adAssetsList(q), 'GET');
    imageAssets.value = (data.items || []).filter(
      (a) => a.assetType === 'IMAGE' && a.status === 'ACTIVE'
    );
  } catch {
    imageAssets.value = [];
  }
}

async function openPlacements(row: PromotionRow) {
  if (!row.activityId) return;
  const inlineRow = { activityId: row.activityId, activityName: row.activityName || '' };
  placementActivityId.value = inlineRow.activityId;
  placementActivityName.value = inlineRow.activityName || `活动 ${row.activityId}`;
  placementForm.value = { bannerAssetId: null, popupPosition: 'NONE', popupAssetId: null };
  placementVisible.value = true;
  await loadImageAssets();
  try {
    const items = await api.request<PlacementDto[]>(
      AdminEndpoints.promotionPlacements(inlineRow.activityId),
      'GET'
    );
    for (const p of items || []) {
      if (p.position === 'BANNER') placementForm.value.bannerAssetId = p.assetId;
      else if (p.position === 'POPUP_BEFORE_OPEN' || p.position === 'POPUP_AFTER_CLOSE') {
        placementForm.value.popupPosition = p.position;
        placementForm.value.popupAssetId = p.assetId;
      }
    }
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载展示位失败'));
  }
}

function pickFile(target: 'BANNER' | 'POPUP') {
  uploadTarget.value = target;
  (target === 'BANNER' ? bannerFileInput : popupFileInput).value?.click();
}

async function onFilePicked(event: Event) {
  const input = event.target as HTMLInputElement;
  const file = input.files?.[0];
  if (!file) return;
  const check = validateAdAssetFile(file, 'IMAGE');
  if (!check.ok) {
    ElMessage.warning(check.message);
    input.value = '';
    return;
  }
  uploading.value = uploadTarget.value;
  try {
    const base =
      (import.meta.env.VITE_API_BASE || '').replace(/\/$/, '') || globalThis.location.origin;
    const fd = new FormData();
    fd.append('file', file);
    fd.append('title', file.name.replace(/\.[^.]+$/, ''));
    fd.append('assetType', 'IMAGE');
    const res = await authFetch(`${base}${AdminEndpoints.adAssets}`, { method: 'POST', body: fd });
    const json = await res.json().catch(() => ({}));
    if (!res.ok || json.code !== 0) {
      throw new Error(json.message || `上传失败 (${res.status})`);
    }
    const asset = json.data as ImageAsset;
    await loadImageAssets();
    if (uploadTarget.value === 'BANNER') placementForm.value.bannerAssetId = asset.assetId;
    else placementForm.value.popupAssetId = asset.assetId;
    ElMessage.success('上传成功');
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : '上传失败');
  } finally {
    uploading.value = '';
    input.value = '';
  }
}

function assertSize(position: PlacementPosition, assetId: number | null) {
  const asset = assetById(assetId);
  if (!asset) return '请选择图片';
  return checkImageSize(position, asset.imageWidth, asset.imageHeight);
}

async function savePlacements() {
  if (!placementActivityId.value) return;
  const f = placementForm.value;
  if (!f.bannerAssetId) return ElMessage.warning('请选择轮播横幅图片');
  const bannerErr = assertSize('BANNER', f.bannerAssetId);
  if (bannerErr) return ElMessage.warning(`轮播横幅：${bannerErr}`);
  const items: { position: string; assetId: number }[] = [
    { position: 'BANNER', assetId: f.bannerAssetId }
  ];
  if (f.popupPosition !== 'NONE') {
    if (!f.popupAssetId) return ElMessage.warning('请选择弹窗图片');
    const popupErr = assertSize(f.popupPosition, f.popupAssetId);
    if (popupErr) return ElMessage.warning(`弹窗：${popupErr}`);
    items.push({ position: f.popupPosition, assetId: f.popupAssetId });
  }
  placementSaving.value = true;
  try {
    await api.request(AdminEndpoints.promotionPlacements(placementActivityId.value), 'PUT', items);
    ElMessage.success('展示位已保存');
    placementVisible.value = false;
    await crud.load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '保存展示位失败'));
  } finally {
    placementSaving.value = false;
  }
}

// ── CSV ────────────────────────────────────────────────────────
const csvOptions: CrudCsvOptions = {
  filePrefix: '营销活动',
  exportPerm: 'ops:promotion:export',
  importPerm: 'ops:promotion:import',
  headers: CSV_HEADERS,
  toRows: (rows) =>
    rows.map((row: PromotionRow) => [
      row.activityName || '',
      displayLabel('promotion_type', row.activityType, '未知类型'),
      formatTime(row.startTime || ''),
      formatTime(row.endTime || ''),
      row.reduceCents ? centsToYuanFixed(row.reduceCents) : '',
      row.discountBps ? discountBpsToZhe(row.discountBps).toFixed(1) : '',
      row.fixedPriceCents ? centsToYuanFixed(row.fixedPriceCents) : '',
      parseRuleConfig(row.ruleConfig).skuIds.join(','),
      yuan(row.budgetCents || 0),
      row.userLimit ?? 0,
      row.description || '',
      statusLabel(row.status)
    ]),
  templateSample: [
    '示例商品立减',
    displayLabel('promotion_type', 'SKU_REDUCE'),
    '2026-07-16 00:00',
    '2026-08-16 23:59',
    '1.00',
    '',
    '',
    'SKU-0001,SKU-0002',
    '1000',
    '1',
    '示例描述',
    displayLabel('enable_status', 'INACTIVE')
  ],
  onImportRows: async (rows) => {
    let ok = 0;
    for (const row of rows) {
      const name = row['活动名称'] || row.activityName;
      if (!name) continue;
      const type = normalizeImportType(row['类型'] || row.activityType);
      if (!SKU_TYPES.has(type)) {
        throw new Error(`活动「${name}」类型无效：v1 仅支持商品立减 / 商品折扣 / 一口价`);
      }
      const start = parseImportTime(row['开始时间'] || row.startTime);
      const end = parseImportTime(row['结束时间'] || row.endTime);
      if (!start || !end || end <= start) {
        throw new Error(`活动「${name}」时间无效`);
      }
      const skuIds = String(row['参与商品ID(逗号分隔)'] || '')
        .split(/[,，\s]+/)
        .map((s) => s.trim())
        .filter(Boolean);
      if (!skuIds.length) {
        throw new Error(`活动「${name}」缺少参与商品ID`);
      }
      const reduceCents = yuanToCents(row['立减(元)']) ?? null;
      const discountZhe = Number(row['折扣(折)']);
      const discountBps =
        Number.isFinite(discountZhe) && discountZhe > 0 ? Math.round(discountZhe * 1000) : null;
      const fixedPriceCents = yuanToCents(row['一口价(元)']) ?? null;
      const created = await api.request<PromotionRow>(AdminEndpoints.promotions, 'POST', {
        activityName: name,
        activityType: type,
        startTime: start.toISOString(),
        endTime: end.toISOString(),
        budgetCents: yuanToCents(row['预算(元)'] || row.budgetYuan) ?? 0,
        userLimit: (() => {
          const n = Number(row['每人限购'] || row.userLimit);
          return Number.isFinite(n) && n > 0 ? n : 1;
        })(),
        deviceScope: 'ALL',
        ruleConfig: JSON.stringify({ deviceIds: [], merchantIds: [], skuIds, totalOrderLimit: 0 }),
        description: row['描述'] || row.description || '',
        reduceCents: type === 'SKU_REDUCE' ? reduceCents : null,
        discountBps: type === 'SKU_DISCOUNT' ? discountBps : null,
        fixedPriceCents: type === 'SKU_FIXED_PRICE' ? fixedPriceCents : null
      });
      if (wantsEnabled(row['状态'] || row.status) && created?.activityId) {
        await api.request(AdminEndpoints.promotionLaunch(created.activityId), 'POST');
      }
      ok++;
    }
    await crud.load();
    return ok;
  }
};

function normalizeImportType(raw: string | undefined) {
  const value = (raw || '').trim();
  if (!value) return 'SKU_REDUCE';
  if (SKU_TYPES.has(value)) return value;
  const byLabel = dictOptions('promotion_type').find((o) => o.label === value || o.value === value);
  return byLabel?.value || value;
}

function parseImportTime(raw: string): Date | null {
  if (!raw) return null;
  const normalized = raw.includes('T') ? raw : raw.replace(' ', 'T');
  const d = new Date(normalized.length === 16 ? `${normalized}:00` : normalized);
  return Number.isNaN(d.getTime()) ? null : d;
}

function wantsEnabled(statusRaw: string) {
  const s = (statusRaw || '').trim();
  return s === displayLabel('enable_status', 'ACTIVE') || s.toUpperCase() === 'ACTIVE';
}

function syncRouteQuery() {
  const query: Record<string, string> = {};
  if (keyword.value.trim()) query.keyword = keyword.value.trim();
  if (statusFilter.value) query.status = statusFilter.value;
  router.replace({ query });
}

function search() {
  syncRouteQuery();
  void crud.search();
}

function resetFilters() {
  keyword.value = '';
  statusFilter.value = '';
  syncRouteQuery();
  void crud.search();
}

function applyRouteQuery() {
  let changed = false;
  const qKeyword = typeof route.query.keyword === 'string' ? route.query.keyword : '';
  if (qKeyword !== keyword.value) {
    keyword.value = qKeyword;
    changed = true;
  }
  const qStatus = typeof route.query.status === 'string' ? route.query.status : '';
  if (qStatus !== statusFilter.value) {
    statusFilter.value = qStatus;
    changed = true;
  }
  return changed;
}

async function reloadFromRouteQuery() {
  if (!applyRouteQuery()) return;
  await crud.search();
}

watch(
  () => [route.query.keyword, route.query.status] as const,
  () => {
    void reloadFromRouteQuery();
  }
);

// 首查前需先应用路由查询参数，故保留显式首查（crud 已配 autoLoad: false）
onMounted(() => {
  applyRouteQuery();
  void loadDevices();
  void loadMerchants();
  void loadSkus();
  void crud.load();
});
// 首次 activated 紧随 onMounted（选项已拉过），跳过防双请求
let activatedOnce = false;
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true;
    return;
  }
  void reloadFromRouteQuery();
  // keep-alive 缓存页恢复时重拉选项：别处新建的柜机/商户/商品切回本页可立即选到
  void loadDevices();
  void loadMerchants();
  void loadSkus();
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
.muted {
  color: var(--layout-muted);
  font-size: var(--admin-font-size-table);
}
.budget-remain {
  display: inline-flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  line-height: 1.2;
}
.budget-full-tag {
  margin: 0;
}
.field-hint {
  font-size: var(--admin-font-size-sm);
  color: var(--el-text-color-secondary);
  line-height: 1.5;
  margin-top: 4px;
}
.form-group-title {
  margin: 8px 0 12px;
  padding-top: 8px;
  border-top: 1px solid var(--el-border-color-lighter);
  font-weight: 600;
  color: var(--el-text-color-secondary);
  font-size: var(--admin-font-size-sm);
}
.form-group-title:first-child {
  margin-top: 0;
  padding-top: 0;
  border-top: none;
}
.placement-preview {
  width: 240px;
  height: 150px;
  margin-bottom: 12px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 4px;
}
.placement-preview :deep(img) {
  width: 100%;
  height: 100%;
  object-fit: contain;
}
.scope-field {
  width: 100%;
}
.placement-hint {
  margin: -4px 0 8px;
}
:global(.promo-dialog .el-dialog__body) {
  overflow: visible;
}
</style>
