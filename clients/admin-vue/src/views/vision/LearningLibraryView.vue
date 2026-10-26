<template>
  <el-card class="page-card report-page" shadow="never">
    <template #header>
      <div class="page-card-head">
        <div class="page-card-head__meta">
          <div class="page-card-head__title">
            <span class="title">商品学习库</span>
            <span class="hint">
              租户级学习资产总览：将邑云端学会的商品与模型，一次学习所有柜机共用；
              但「能不能卖」仍逐柜——类别要在「识别映射」里挂到我方 SKU 并配价与库存
            </span>
          </div>
        </div>
        <div class="page-card-head__actions">
          <el-button :loading="loading" @click="load()">刷新</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="primary"
            :disabled="!cabinets.length"
            @click="openTraining()"
          >
            触发学习
          </el-button>
        </div>
      </div>
    </template>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="采集在柜机、学习在厂商云，本页不驱动摄像头"
    >
      <template #default>
        拍照采集是把商品摆到<b>柜机自己的摄像头</b>前，由将邑商户 App 编排（我方采集编排尚未接入）；
        学习是把该商户已采集的商品在<b>将邑云端</b>重训成租户模型（§4.4.3 请求体里没有柜机）。
        学成只是「云里有这个类别」，要在我方柜子上卖还得到
        <el-link type="primary" @click="goMappings()">识别映射</el-link>
        逐柜挂 SKU（重训会让识别编号整体平移，每次都要复核）。
      </template>
    </el-alert>

    <el-alert
      v-if="library?.vendorError"
      class="vendor-alert"
      type="warning"
      :closable="false"
      show-icon
      title="将邑读面降级：下面的模型与已学商品可能为空"
      :description="library.vendorError"
    />

    <div class="stat-line">
      <span
        >模型 <b>{{ library?.models.length ?? 0 }}</b></span
      >
      <span
        >已学商品 <b>{{ library?.learnedProducts.length ?? 0 }}</b></span
      >
      <span
        >已接入柜机 <b>{{ cabinets.length }}</b></span
      >
      <span
        >可发起学习的 SKU <b>{{ learnableSkuCount }}</b></span
      >
    </div>

    <h4 class="section-title">已学商品（将邑云端）</h4>
    <div class="filter-bar filter-bar--compact">
      <el-input
        v-model="keyword"
        clearable
        placeholder="按厂商名 / 识别名 / 条码 / 我方 SKU 过滤"
        style="width: 320px"
      />
    </div>
    <el-table v-loading="loading" :data="filteredProducts" size="small" border>
      <el-table-column prop="name" label="厂商商品名" min-width="160">
        <template #default="{ row }">{{ row.name || '—' }}</template>
      </el-table-column>
      <el-table-column prop="textName" label="识别名称（classes）" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.textName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="barCode" label="条码" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.barCode || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="我方商品" min-width="170">
        <template #default="{ row }">
          <template v-if="row.skuId">
            <div>{{ row.skuName || row.skuId }}</div>
            <div class="sub mono">{{ row.skuId }}</div>
          </template>
          <el-tag v-else size="small" type="warning">未挂我方商品</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="已上架柜机（ACTIVE 映射）" min-width="240">
        <template #default="{ row }">
          <template v-if="row.coverage.length">
            <el-tag
              v-for="c in row.coverage"
              :key="`${c.deviceId}-${c.classId}`"
              class="coverage-tag"
              size="small"
              :type="c.mappingVerified ? 'success' : 'info'"
            >
              {{ c.deviceName || c.deviceId }} · 编号 {{ c.classId }}
              {{ c.mappingVerified ? '' : '（未复核）' }}
            </el-tag>
          </template>
          <span v-else class="muted">未上架</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-tooltip
            v-if="!row.skuId"
            content="未挂我方商品：先到商品挂接页绑定 SKU 才能上架"
            placement="top"
          >
            <span class="muted">不可上架</span>
          </el-tooltip>
          <el-button
            v-else
            v-hasPermi="['ops:device:edit']"
            link
            type="primary"
            @click="openShelf(row)"
          >
            跨柜上架
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <div v-if="library && !library.learnedProducts.length" class="empty-note">
      将邑侧当前没有已学习商品。先在柜机前采集（厂商商户 App），再触发学习；学习完成以将邑回调为准。
    </div>

    <h4 class="section-title">柜机（映射与采集锁仍是逐柜的）</h4>
    <el-table :data="cabinets" size="small" border>
      <el-table-column label="柜机" min-width="180">
        <template #default="{ row }">
          <div>{{ row.deviceName || row.deviceId }}</div>
          <div class="sub mono">{{ row.deviceId }} · {{ row.identifier || '无 identifier' }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="classesVersion" label="模型 classes 版本" min-width="150">
        <template #default="{ row }">
          <span class="mono">{{ row.classesVersion || '未登记' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="映射复核" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="row.mappingVerified ? 'success' : 'danger'">
            {{ row.mappingVerified ? '已复核' : '未复核' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="activeMappingCount" label="在架类别" width="100" />
      <el-table-column label="采集模式" width="110">
        <template #default="{ row }">
          <el-tag v-if="row.gatherLocked" size="small" type="warning">采集中</el-tag>
          <span v-else class="muted">正常营业</span>
        </template>
      </el-table-column>
      <el-table-column label="最近通信" min-width="160">
        <template #default="{ row }">
          {{ row.lastWsOnlineAt ? formatInstant(row.lastWsOnlineAt) : '从未上线' }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goMappings(row.deviceId)">识别映射</el-button>
          <el-button link type="primary" @click="goDevice(row.deviceId)">设备详情</el-button>
        </template>
      </el-table-column>
    </el-table>

    <h4 class="section-title">模型（§4.4.4，学习完成的产物）</h4>
    <el-table :data="library?.models ?? []" size="small" border>
      <el-table-column prop="modelName" label="模型名" min-width="170">
        <template #default="{ row }">
          <span class="mono">{{ row.modelName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="quantity" label="类别数" width="90" />
      <el-table-column prop="industrialControlModel" label="工控机型" width="100">
        <template #default="{ row }">
          <span class="mono">{{ row.industrialControlModel || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="modelTextUrl" label="classes 地址" min-width="280">
        <template #default="{ row }">
          <span class="mono ellipsis" :title="row.modelTextUrl || ''">
            {{ row.modelTextUrl || '—' }}
          </span>
        </template>
      </el-table-column>
    </el-table>

    <h4 class="section-title">最近学习（只含我方发起的）</h4>
    <el-table :data="library?.recentTrainings ?? []" size="small" border>
      <el-table-column label="发起时间" min-width="160">
        <template #default="{ row }">{{ formatInstant(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="柜机" min-width="150">
        <template #default="{ row }">{{ row.deviceName || row.deviceId || '—' }}</template>
      </el-table-column>
      <el-table-column label="商品" min-width="170">
        <template #default="{ row }">{{ row.skuName || row.skuId || '—' }}</template>
      </el-table-column>
      <el-table-column prop="modelName" label="模型" min-width="140">
        <template #default="{ row }">
          <span class="mono">{{ row.modelName || '—' }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag size="small" :type="trainingStatusType(row.status)">
            {{ trainingStatusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="完成时间" min-width="160">
        <template #default="{ row }">
          {{ row.finishedAt ? formatInstant(row.finishedAt) : '—' }}
        </template>
      </el-table-column>
    </el-table>
    <div class="empty-note">
      在厂商商户 App
      里发起的学习不会出现在这里（回调走厂商自己的地址）；本页发起的学习，回调成功后会自动退出该柜的采集模式。
    </div>

    <el-dialog v-model="trainingVisible" title="批量触发学习" width="600px">
      <el-alert
        class="dialog-alert"
        type="warning"
        :closable="false"
        show-icon
        title="一次提交只发一次厂商学习调用，覆盖勾选的全部商品"
      >
        <template #default>
          将邑学习是<b>集合粒度</b>（§4.4.3 请求体里没有商品参数）——勾几个商品都是同一次重训，
          柜机只决定「进度挂在谁身上」。商品必须已在柜机前采集过、并已挂将邑商品（否则回调无法交叉验证）。
          若柜机已在我方采集模式中，学习回调成功后会<b>自动退出采集模式</b>并恢复营业。
        </template>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item label="柜机" required>
          <el-select
            v-model="trainingForm.deviceId"
            filterable
            placeholder="选择发起学习的柜机"
            style="width: 100%"
          >
            <el-option
              v-for="c in cabinets"
              :key="c.deviceId"
              :label="`${c.deviceName || c.deviceId}（${c.identifier || '无编号'}）`"
              :value="c.deviceId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="商品" required>
          <el-select
            v-model="trainingForm.skuIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="勾选本次要学的我方 SKU（已挂将邑商品）"
            style="width: 100%"
          >
            <el-option
              v-for="s in learnableSkus"
              :key="s.skuId"
              :label="`${s.skuName || s.skuId}（${s.trained ? '已学' : '待学'}）`"
              :value="s.skuId"
            />
          </el-select>
          <div class="form-hint">
            只列出已挂将邑商品的 SKU；有在途学习的商品提交会被拒（等回执后再试）。
          </div>
        </el-form-item>
        <el-form-item label="模型名" required>
          <el-select
            v-if="library?.models.length"
            v-model="trainingForm.modelName"
            filterable
            placeholder="选择要重训的模型名"
            style="width: 100%"
          >
            <el-option
              v-for="m in library?.models ?? []"
              :key="m.id"
              :label="`${m.modelName}（${m.quantity} 类）`"
              :value="m.modelName || ''"
            />
          </el-select>
          <el-input
            v-else
            v-model="trainingForm.modelName"
            placeholder="将邑当前没有学成模型，填写要新建的模型名"
          />
          <div v-if="!library?.models.length" class="form-hint">
            将邑模型列表为空（尚无学成模型）：此处手填的模型名就是首次训练要生成的模型，
            名字要和后续下发的 classes/模型对齐。
          </div>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="trainingVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="trainingSubmitting"
          :disabled="!trainingForm.skuIds.length"
          @click="submitTraining"
        >
          提交学习（{{ trainingForm.skuIds.length }}）
        </el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="shelfVisible" title="跨柜上架" width="720px">
      <el-alert
        class="dialog-alert"
        type="info"
        :closable="false"
        show-icon
        title="只挂柜机当前模型里同名的识别映射"
      >
        <template #default>
          按将邑侧识别名「<b class="mono">{{ shelfProduct?.textName || '—' }}</b
          >」把商品挂到所选柜机
          <b>当前 classes 里同名的那一行</b
          >，并置为在售——不改识别编号、不新建类目（编号由各柜模型决定）。
          未按当前模型复核、当前模型没有这个识别名、或该识别名已挂其它 SKU
          的柜机会被<b>跳过</b>，逐柜给出原因。
        </template>
      </el-alert>
      <el-form label-width="92px">
        <el-form-item label="商品">
          <div v-if="shelfProduct">
            {{ shelfProduct.skuName || shelfProduct.skuId }}
            <span class="sub mono"> · {{ shelfProduct.skuId }}</span>
          </div>
        </el-form-item>
        <el-form-item label="柜机" required>
          <el-select
            v-model="shelfForm.deviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择要上架的柜机"
            style="width: 100%"
          >
            <el-option
              v-for="c in cabinets"
              :key="c.deviceId"
              :label="`${c.deviceName || c.deviceId}（${c.mappingVerified ? '已复核' : '未复核'}）`"
              :value="c.deviceId"
            />
          </el-select>
          <div class="form-hint">
            一次最多 20 台；未复核的柜机提交后会被跳过（先去识别映射页复核）。
          </div>
        </el-form-item>
        <el-form-item label="柜级价">
          <el-input-number
            v-model="shelfForm.priceYuan"
            :min="0.01"
            :max="99999"
            :precision="2"
            :step="0.5"
            :disabled="!canEditPricing"
            placeholder="留空不改价"
            style="width: 220px"
          />
          <div class="form-hint">
            {{
              canEditPricing
                ? '留空＝不动价格（沿用现有柜级价或基准价）；填写则写本柜覆盖价（受 SKU 限价约束）。'
                : '没有改价权限（ops:pricing:edit）：本次只挂接，不调价。'
            }}
          </div>
        </el-form-item>
        <el-form-item label="铺货件数">
          <el-input-number
            v-model="shelfForm.quantity"
            :min="1"
            :max="999"
            :disabled="!canEditReplenishment"
            placeholder="留空不铺货"
            style="width: 220px"
          />
          <div class="form-hint">
            {{
              canEditReplenishment
                ? '只补「账面为 0 且未启用批次账本」的柜；已有库存或批次账本的柜不改动（原因见逐柜结果）。'
                : '没有补货权限（ops:replenishment:edit）：本次只挂接，不铺货。'
            }}
          </div>
        </el-form-item>
      </el-form>
      <template v-if="shelfResult">
        <el-divider content-position="left">逐柜结果</el-divider>
        <el-table :data="shelfResult.devices" size="small" border>
          <el-table-column label="柜机" min-width="150">
            <template #default="{ row }">{{ row.deviceName || row.deviceId }}</template>
          </el-table-column>
          <el-table-column label="结果" width="100">
            <template #default="{ row }">
              <el-tag size="small" :type="shelfStatusType(row.status)">
                {{ shelfStatusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="识别编号" width="90">
            <template #default="{ row }">{{ row.classId ?? '—' }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="260">
            <template #default="{ row }">{{ row.message || '—' }}</template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="closeShelf">{{ shelfResult ? '关闭' : '取消' }}</el-button>
        <el-button
          type="primary"
          :loading="shelfSubmitting"
          :disabled="!shelfForm.deviceIds.length"
          @click="submitShelf"
        >
          上架到 {{ shelfForm.deviceIds.length }} 台柜机
        </el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup lang="ts">
import { computed, onActivated, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { useAuthStore } from '@/stores/auth';
import { errorMessage } from '@/utils/error-message';
import { formatDateTime } from '@aicabinet/shared-uni/format';

/**
 * 商品学习库（租户级）：将邑云端学成的商品/模型是商户资产，一次学习全部柜机共用；
 * 但 classId 是 classes.txt 按识别名排序的行号（H-27），重训即整体平移 ⇒ 映射/库存/价格
 * 仍逐柜。本页只做总览与（可选）学习触发，采集编排不在我方（G-5）。
 */

interface LearningModel {
  id: number;
  modelName: string | null;
  quantity: number;
  industrialControlModel: string | null;
  modelTextUrl: string | null;
  modelUrl: string | null;
}

interface ProductCoverage {
  deviceId: string;
  deviceName: string | null;
  classId: number;
  mappingVerified: boolean;
}

interface LearnedProduct {
  jiangyiProductId: number;
  name: string | null;
  textName: string | null;
  barCode: string | null;
  skuId: string | null;
  skuName: string | null;
  coverage: ProductCoverage[];
}

interface LearnableSku {
  skuId: string;
  skuName: string | null;
  jiangyiProductId: string | null;
  jiangyiName: string | null;
  textName: string | null;
  syncStatus: string;
  trained: boolean;
}

interface LearningCabinet {
  deviceId: string;
  deviceName: string | null;
  identifier: string | null;
  classesVersion: string | null;
  mappingVerified: boolean;
  activeMappingCount: number;
  gatherLocked: boolean;
  onlineStatus: string | null;
  lastWsOnlineAt: string | null;
}

interface TrainingAttempt {
  deviceId: string | null;
  deviceName: string | null;
  skuId: string | null;
  skuName: string | null;
  modelName: string | null;
  status: string;
  createdAt: string | null;
  finishedAt: string | null;
}

interface LearningLibraryView {
  models: LearningModel[];
  learnedProducts: LearnedProduct[];
  learnableSkus: LearnableSku[];
  cabinets: LearningCabinet[];
  recentTrainings: TrainingAttempt[];
  vendorError: string | null;
}

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const library = ref<LearningLibraryView | null>(null);
const loading = ref(false);
const keyword = ref('');

// 上架顺带改价/铺货各走独立权限点（与单柜定价、补货页同一把钥匙）；无权时输入框禁用而非等 403。
const canEditPricing = computed(() => auth.hasPerm('ops:pricing:edit'));
const canEditReplenishment = computed(() => auth.hasPerm('ops:replenishment:edit'));

const cabinets = computed(() => library.value?.cabinets ?? []);
const learnableSkus = computed(() => library.value?.learnableSkus ?? []);
const learnableSkuCount = computed(() => learnableSkus.value.length);
const filteredProducts = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  const rows = library.value?.learnedProducts ?? [];
  if (!kw) return rows;
  return rows.filter((r) =>
    [r.name, r.textName, r.barCode, r.skuId, r.skuName]
      .filter((v): v is string => !!v)
      .some((v) => v.toLowerCase().includes(kw))
  );
});

const trainingVisible = ref(false);
const trainingSubmitting = ref(false);
const trainingForm = ref({ deviceId: '', skuIds: [] as string[], modelName: '' });

interface BatchTrainingResult {
  deviceId: string;
  modelName: string;
  count: number;
  items: { ticketId: number; skuId: string; jiangyiProductId: string; status: string }[];
}

interface ShelfDeviceResult {
  deviceId: string;
  deviceName: string | null;
  status: 'SHELVED' | 'ALREADY' | 'SKIPPED' | 'FAILED';
  classId: number | null;
  priceCents: number | null;
  quantity: number | null;
  message: string | null;
}

interface ShelfResult {
  jiangyiProductId: string;
  skuId: string;
  skuName: string | null;
  textName: string;
  requestedDevices: number;
  devices: ShelfDeviceResult[];
}

const shelfVisible = ref(false);
const shelfSubmitting = ref(false);
const shelfProduct = ref<LearnedProduct | null>(null);
const shelfResult = ref<ShelfResult | null>(null);
const shelfForm = ref<{
  deviceIds: string[];
  priceYuan: number | undefined;
  quantity: number | undefined;
}>({ deviceIds: [], priceYuan: undefined, quantity: undefined });

async function load() {
  loading.value = true;
  try {
    library.value = await api.request<LearningLibraryView>(
      AdminEndpoints.jiangyiLearningLibrary,
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载商品学习库失败'));
  } finally {
    loading.value = false;
  }
}

function openTraining() {
  const fromQuery = typeof route.query.deviceId === 'string' ? route.query.deviceId : '';
  const fallback = cabinets.value[0]?.deviceId || '';
  trainingForm.value = {
    deviceId: cabinets.value.some((c) => c.deviceId === fromQuery) ? fromQuery : fallback,
    skuIds: [],
    modelName: library.value?.models[0]?.modelName || ''
  };
  trainingVisible.value = true;
}

async function submitTraining() {
  const { deviceId, modelName } = trainingForm.value;
  const skuIds = trainingForm.value.skuIds.filter((id) => !!id);
  if (!deviceId) {
    ElMessage.warning('请选择柜机');
    return;
  }
  if (!skuIds.length) {
    ElMessage.warning('请至少勾选一个商品');
    return;
  }
  if (!modelName.trim()) {
    ElMessage.warning('请选择或填写模型名');
    return;
  }
  trainingSubmitting.value = true;
  try {
    const result = await api.request<BatchTrainingResult>(
      AdminEndpoints.jiangyiGatherTrainingBatch,
      'POST',
      { deviceId, skuIds, modelName: modelName.trim() }
    );
    ElMessage.success(
      `已提交 ${result.count} 个商品的学习（一次厂商调用）；回调成功后会记录为完成，若该柜在采集模式中会自动退出`
    );
    trainingVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '触发学习失败'));
  } finally {
    trainingSubmitting.value = false;
  }
}

function goMappings(deviceId?: string) {
  void router.push({ path: '/vision-mappings', query: deviceId ? { deviceId } : {} });
}

function openShelf(row: LearnedProduct) {
  shelfProduct.value = row;
  shelfResult.value = null;
  shelfForm.value = { deviceIds: [], priceYuan: undefined, quantity: undefined };
  shelfVisible.value = true;
}

async function submitShelf() {
  const product = shelfProduct.value;
  if (!product) return;
  const deviceIds = shelfForm.value.deviceIds.filter((id) => !!id);
  if (!deviceIds.length) {
    ElMessage.warning('请至少选择一台柜机');
    return;
  }
  const priceCents =
    typeof shelfForm.value.priceYuan === 'number'
      ? Math.round(shelfForm.value.priceYuan * 100)
      : null;
  const quantity =
    typeof shelfForm.value.quantity === 'number' ? Math.round(shelfForm.value.quantity) : null;
  shelfSubmitting.value = true;
  try {
    shelfResult.value = await api.request<ShelfResult>(
      AdminEndpoints.jiangyiShelfLearnedProduct,
      'POST',
      { jiangyiProductId: String(product.jiangyiProductId), deviceIds, priceCents, quantity }
    );
    const done = shelfResult.value.devices.filter(
      (d) => d.status === 'SHELVED' || d.status === 'ALREADY'
    ).length;
    ElMessage.success(
      `${done} / ${shelfResult.value.devices.length} 台柜机已上架，其余跳过/失败原因见下`
    );
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '跨柜上架失败'));
  } finally {
    shelfSubmitting.value = false;
  }
}

function closeShelf() {
  shelfVisible.value = false;
  shelfResult.value = null;
}

function shelfStatusText(status: ShelfDeviceResult['status']) {
  if (status === 'SHELVED') return '已上架';
  if (status === 'ALREADY') return '已在售';
  if (status === 'SKIPPED') return '跳过';
  return '失败';
}

function shelfStatusType(status: ShelfDeviceResult['status']) {
  if (status === 'SHELVED') return 'success';
  if (status === 'ALREADY') return 'info';
  if (status === 'SKIPPED') return 'warning';
  return 'danger';
}

function goDevice(deviceId: string) {
  void router.push({ path: `/devices/${encodeURIComponent(deviceId)}` });
}

function trainingStatusText(status: string) {
  if (status === 'FINISHED') return '已完成';
  if (status === 'FAILED') return '失败';
  return '等待回调';
}

function trainingStatusType(status: string) {
  if (status === 'FINISHED') return 'success';
  if (status === 'FAILED') return 'danger';
  return 'warning';
}

function formatInstant(t: string | null) {
  return t ? formatDateTime(t) : '—';
}

onMounted(() => {
  void load();
});

let activatedOnce = false;
onActivated(() => {
  if (!activatedOnce) {
    activatedOnce = true;
    return;
  }
  void load();
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
.page-card-head__title .title {
  font-size: 16px;
  font-weight: 600;
}
.page-card-head__title .hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.page-card-head__actions {
  display: flex;
  gap: 8px;
}
.vendor-alert {
  margin-top: 8px;
}
.stat-line {
  display: flex;
  gap: 24px;
  flex-wrap: wrap;
  margin: 12px 0 4px;
  color: var(--el-text-color-secondary);
  font-size: 13px;
}
.stat-line b {
  color: var(--el-text-color-primary);
}
.section-title {
  margin: 20px 0 8px;
  font-size: 14px;
  font-weight: 600;
}
.filter-bar {
  margin-bottom: 8px;
}
.coverage-tag {
  margin: 0 6px 4px 0;
}
.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
}
.sub {
  color: var(--el-text-color-secondary);
}
.muted {
  color: var(--el-text-color-placeholder);
}
.ellipsis {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
}
.empty-note {
  margin-top: 8px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.6;
}
.dialog-alert {
  margin-bottom: 12px;
}
.form-hint {
  width: 100%;
  margin-top: 4px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
  line-height: 1.5;
}
</style>
