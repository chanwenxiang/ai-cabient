<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';
import HelpTip from '@/components/HelpTip.vue';
import { useAuthStore } from '@/stores/auth';
import {
  yuanToCents,
  jiangyiStateText,
  jiangyiNextActionText,
  jiangyiCategoryText
} from '@aicabinet/shared-uni/format';

interface ProductState {
  barCode?: string;
  state?: string;
  productId?: number | string;
  name?: string;
  nextAction?: string;
  rejectCause?: string;
  jiangyiState?: string;
  jiangyiProductId?: string;
  jiangyiName?: string;
}
interface Category {
  categoryId: number;
  name: string;
}
interface Policy {
  shotCount: number;
  shotHints: string[];
}
/** 我方 SKU 搜索项（只取搜索需要的三个字段） */
interface SkuOption {
  skuId: string;
  skuName?: string;
  barcode?: string;
}

const queryBarCode = ref('');
const stateLoading = ref(false);
const state = ref<ProductState | null>(null);

/**
 * 按商品名搜我方 SKU（2026-11-10 用户反馈：查审核状态必须记 69 码太反人类）。
 * 选中即带出条码并自动查询；手动输入 69 码的入口保留（未进我方商品库的条码仍可直接查）。
 */
const pickedSkuId = ref('');
const skuOptions = ref<SkuOption[]>([]);
const skuSearching = ref(false);
let skuSearchSeq = 0;

async function searchSkus(keyword: string) {
  const kw = keyword.trim();
  const seq = ++skuSearchSeq;
  if (!kw) {
    skuOptions.value = [];
    return;
  }
  skuSearching.value = true;
  try {
    const q = new URLSearchParams({ page: '0', size: '20', q: kw });
    const res = await api.request<{ items: SkuOption[]; total: number }>(
      AdminEndpoints.skusList(q)
    );
    if (seq !== skuSearchSeq) return;
    // 无条码的行带不出 69 码，筛选掉免得选了空转
    skuOptions.value = (res.items || []).filter((s) => s.barcode);
  } catch (e) {
    if (seq !== skuSearchSeq) return;
    skuOptions.value = [];
    ElMessage.error(errorMessage(e, '搜索商品失败'));
  } finally {
    if (seq === skuSearchSeq) skuSearching.value = false;
  }
}

async function onPickSku(skuId: string) {
  const sku = skuOptions.value.find((s) => s.skuId === skuId);
  if (!sku?.barcode) return;
  queryBarCode.value = sku.barcode;
  await loadState();
}

const auth = useAuthStore();
/** 建档 / 批量刷新是写操作（后端 ops:sku:edit）；本页本身按 ops:sku:list 进得来 */
const canEdit = computed(() => auth.hasPerm('ops:sku:edit'));
const NEED_EDIT_HINT = '当前账号无商品编辑权限';

const createForm = reactive({
  barCode: '',
  name: '',
  salePriceYuan: '',
  specs: '',
  brandName: '',
  categoryId: undefined as number | undefined,
  category: 'bottle'
});
const createLoading = ref(false);
const categories = ref<Category[]>([]);
const policy = ref<Policy | null>(null);

const refreshText = ref('');
const refreshLoading = ref(false);

async function loadState() {
  const code = queryBarCode.value.trim();
  if (!code) {
    ElMessage.warning('请输入条码');
    return;
  }
  stateLoading.value = true;
  try {
    state.value = await api.request<ProductState>(AdminEndpoints.jiangyiProductState(code), 'GET');
  } catch (e) {
    state.value = null;
    ElMessage.error(errorMessage(e, '查询状态失败'));
  } finally {
    stateLoading.value = false;
  }
}

async function doCreate() {
  if (!createForm.barCode.trim() || !createForm.name.trim()) {
    ElMessage.warning('条码与名称必填');
    return;
  }
  const cents = yuanToCents(createForm.salePriceYuan);
  if (cents == null || cents <= 0) {
    ElMessage.warning('零售价须大于 0（元）');
    return;
  }
  createLoading.value = true;
  try {
    const res = await api.request<ProductState>(AdminEndpoints.jiangyiProductCreate, 'POST', {
      barCode: createForm.barCode.trim(),
      name: createForm.name.trim(),
      salePriceCents: cents,
      specs: createForm.specs.trim() || undefined,
      brandName: createForm.brandName.trim() || undefined,
      categoryId: createForm.categoryId,
      category: createForm.category
    });
    state.value = res;
    queryBarCode.value = createForm.barCode.trim();
    ElMessage.success('建档成功，状态见右侧');
  } catch (e) {
    ElMessage.error(errorMessage(e, '建档失败'));
  } finally {
    createLoading.value = false;
  }
}

async function doRefresh() {
  const codes = refreshText.value
    .split(/[\s,，;；]+/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (!codes.length) {
    ElMessage.warning('请输入至少一个条码');
    return;
  }
  refreshLoading.value = true;
  try {
    const n = await api.request<number>(AdminEndpoints.jiangyiProductStateRefresh, 'POST', {
      barCodes: codes
    });
    ElMessage.success(`已刷新 ${n} 条状态`);
    if (queryBarCode.value.trim()) await loadState();
  } catch (e) {
    ElMessage.error(errorMessage(e, '刷新失败'));
  } finally {
    refreshLoading.value = false;
  }
}

onMounted(async () => {
  try {
    categories.value = await api.request<Category[]>(
      AdminEndpoints.jiangyiProductCategories,
      'GET'
    );
    policy.value = await api.request<Policy>(AdminEndpoints.jiangyiCapturePolicy, 'GET');
  } catch {
    /* 只读参考，失败不阻断 */
  }
});
</script>

<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">将邑商品</span>
            <span class="hint"
              >建档 → 手机采集（商户小程序）→ 审核 →
              补货上架；本页负责建档、查状态、批量刷新与看驳回原因</span
            >
            <HelpTip
              content="拍照采集必须在商户小程序「商品采集」完成（现场实物拍摄）；按厂商提示逐张拍、背景统一干净、平视居中；本页只做建档/查状态/批量刷新"
              aria-label="采集与建档口径"
            />
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button @click="loadState">刷新状态</el-button>
        </div>
      </div>
    </template>

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="12">
        <el-card header="查厂商状态（按商品名或 69 码）">
          <el-select
            v-model="pickedSkuId"
            filterable
            remote
            clearable
            reserve-keyword
            :remote-method="searchSkus"
            :loading="skuSearching"
            placeholder="按商品名称搜我方商品（免记 69 码），如：冰红茶"
            style="width: 100%; margin-bottom: 8px"
            @change="onPickSku"
          >
            <el-option
              v-for="s in skuOptions"
              :key="s.skuId"
              :value="s.skuId"
              :label="`${s.skuName || s.skuId}（${s.barcode}）`"
            />
          </el-select>
          <el-input
            v-model="queryBarCode"
            placeholder="或直接输入 69 条码"
            clearable
            @keyup.enter="loadState"
          >
            <template #append>
              <el-button :loading="stateLoading" @click="loadState">查询</el-button>
            </template>
          </el-input>
          <el-descriptions v-if="state" :column="1" border style="margin-top: 12px">
            <el-descriptions-item label="条码">{{
              state.barCode || queryBarCode
            }}</el-descriptions-item>
            <el-descriptions-item label="名称">{{
              state.jiangyiName || state.name || '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="厂商商品 ID">{{
              state.jiangyiProductId || state.productId || '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag>{{
                jiangyiStateText(state.jiangyiState || state.state) ||
                state.jiangyiState ||
                state.state ||
                '—'
              }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="下一步">{{
              jiangyiNextActionText(state.nextAction)
            }}</el-descriptions-item>
            <el-descriptions-item label="驳回原因">
              <span :class="{ 'reject-cause': !!state.rejectCause }">{{
                state.rejectCause || '无'
              }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="搜商品名或输入 69 码后查询" :image-size="60" />
        </el-card>

        <el-card header="批量刷新状态（无副作用）" style="margin-top: 16px">
          <el-input
            v-model="refreshText"
            type="textarea"
            :rows="3"
            placeholder="多个条码用逗号 / 空格 / 换行分隔（单次 ≤100）"
          />
          <el-button
            type="primary"
            :loading="refreshLoading"
            style="margin-top: 8px"
            @click="canEdit ? doRefresh() : ElMessage.info(NEED_EDIT_HINT)"
            >刷新</el-button
          >
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card header="新条码建档">
          <el-form label-width="90px">
            <el-form-item label="条码" required
              ><el-input v-model="createForm.barCode" placeholder="69 条码"
            /></el-form-item>
            <el-form-item label="名称" required
              ><el-input v-model="createForm.name" placeholder="商品名称"
            /></el-form-item>
            <el-form-item label="零售价(元)" required
              ><el-input v-model="createForm.salePriceYuan" placeholder="如 3.5"
            /></el-form-item>
            <el-form-item label="规格"
              ><el-input v-model="createForm.specs" placeholder="如 330ml"
            /></el-form-item>
            <el-form-item label="品牌"><el-input v-model="createForm.brandName" /></el-form-item>
            <el-form-item label="类目">
              <el-select v-model="createForm.categoryId" placeholder="选择类目" clearable>
                <el-option
                  v-for="c in categories"
                  :key="c.categoryId"
                  :label="c.name"
                  :value="c.categoryId"
                />
              </el-select>
            </el-form-item>
            <el-form-item label="形态">
              <el-select v-model="createForm.category">
                <el-option
                  v-for="k in ['bottle', 'box', 'bag', 'bowl', 'egg']"
                  :key="k"
                  :label="jiangyiCategoryText(k)"
                  :value="k"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button
                type="primary"
                :loading="createLoading"
                @click="canEdit ? doCreate() : ElMessage.info(NEED_EDIT_HINT)"
                >建档</el-button
              >
            </el-form-item>
          </el-form>
          <div v-if="policy" class="rules-hint">
            手机采集需 {{ policy.shotCount }} 张：{{ policy.shotHints.join('、') }}
          </div>
        </el-card>
      </el-col>
    </el-row>
  </el-card>
</template>

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
.page-card-head__title .title {
  font-size: 16px;
  font-weight: 600;
}
.page-card-head__title .hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.rules-hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
  margin-top: 8px;
}
.reject-cause {
  color: var(--el-color-danger);
}
</style>
