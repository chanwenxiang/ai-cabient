<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

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

const queryBarCode = ref('');
const stateLoading = ref(false);
const state = ref<ProductState | null>(null);

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

const STATE_LABEL: Record<string, string> = {
  NEW: '未建档',
  READY: '可补货',
  NEED_PHONE: '待手机采集',
  REJECTED: '已驳回',
  REVIEWING: '手机图审核中',
  CABINET_PENDING: '可补货/待柜内采集',
  CABINET_REVIEWING: '柜内图审核中'
};

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
  const yuan = Number(createForm.salePriceYuan);
  if (!Number.isFinite(yuan) || yuan <= 0) {
    ElMessage.warning('零售价须大于 0（元）');
    return;
  }
  createLoading.value = true;
  try {
    const res = await api.request<ProductState>(AdminEndpoints.jiangyiProductCreate, 'POST', {
      barCode: createForm.barCode.trim(),
      name: createForm.name.trim(),
      salePriceCents: Math.round(yuan * 100),
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
  <div class="jy-products">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="拍照采集请在商户小程序「商品采集」完成（§7.3 要求现场实物拍摄）；本页负责建档、查状态与批量刷新。"
    />

    <el-row :gutter="16" style="margin-top: 16px">
      <el-col :span="12">
        <el-card header="按条码查厂商状态">
          <el-input
            v-model="queryBarCode"
            placeholder="输入 69 条码"
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
                STATE_LABEL[state.jiangyiState || state.state || ''] ||
                state.jiangyiState ||
                state.state ||
                '—'
              }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="下一步">{{
              state.nextAction || '—'
            }}</el-descriptions-item>
            <el-descriptions-item label="驳回原因">
              <span :class="{ 'reject-cause': !!state.rejectCause }">{{
                state.rejectCause || '无'
              }}</span>
            </el-descriptions-item>
          </el-descriptions>
          <el-empty v-else description="输入条码后查询" :image-size="60" />
        </el-card>

        <el-card header="批量刷新状态（§7.10，无副作用）" style="margin-top: 16px">
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
            @click="doRefresh"
            >刷新</el-button
          >
        </el-card>
      </el-col>

      <el-col :span="12">
        <el-card header="新条码建档（§7.6）">
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
                  :label="k"
                  :value="k"
                />
              </el-select>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="createLoading" @click="doCreate">建档</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            v-if="policy"
            type="warning"
            :closable="false"
            :title="`手机采集需 ${policy.shotCount} 张：${policy.shotHints.join('、')}`"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<style scoped>
.jy-products {
  padding: 16px;
}
.reject-cause {
  color: var(--el-color-danger);
}
</style>
