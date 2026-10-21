<script setup lang="ts">
import { computed, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

/**
 * 将邑商品库挂接弹窗（CB-023 二期，范围 B）：
 * 我方 SKU ↔ 将邑商品（商品维度关联，识别 classId 映射之外的另一半）。
 * 能力：搜索将邑商品库挂接 / 我方商品新增到将邑 / 学习后按条码回填 textName / 解挂。
 * jiangyi_product_id 全局唯一（一个将邑商品至多挂一个我方 SKU）；barCode 不一致仅提示不阻断
 * （69 码是罐身固有属性，同款不同包装版本条码可不同）。
 */

interface JiangyiLink {
  id: number;
  skuId: string;
  jiangyiProductId: string;
  jiangyiName: string | null;
  jiangyiTextName: string | null;
  barCode: string | null;
  syncStatus: 'BOUND' | 'CREATED' | 'RETIRED';
  syncedAt: string | null;
}

interface StdSkuView {
  id: number;
  name: string;
  barCode: string | null;
  specs: string | null;
  brandName: string | null;
  category: string | null;
  mainImg: string | null;
  stdSkuCode: string | null;
  linked: boolean;
  barCodeSource: string | null;
  matchLevel: 'EXACT_BARCODE' | 'SAME_SPEC' | 'RELATED' | 'SUSPECT' | 'UNKNOWN';
  mismatchReasons: string[];
}

interface StdSkuSearchResult {
  items: StdSkuView[];
  total: number;
  hidden: number;
}

const visible = ref(false);
const skuId = ref('');
const skuBarcode = ref('');
const skuName = ref('');
const skuSpec = ref('');
const skuImageUrl = ref('');
const links = ref<JiangyiLink[]>([]);
const loading = ref(false);

const searchKeyword = ref('');
const searchResults = ref<StdSkuView[]>([]);
const searching = ref(false);
const searchAttempted = ref(false);
const scored = ref(false);
const includeAll = ref(false);
const searchTotal = ref(0);
const hiddenCount = ref(0);
const binding = ref(false);
const pulling = ref(false);

// 挂接前并排核对（我方 SKU vs 将邑商品）；无条码一致时不勾选不允许提交
const confirmItem = ref<StdSkuView | null>(null);
const confirmVisible = ref(false);
const confirmedSame = ref(false);

const hasExactBarcodeMatch = computed(() =>
  searchResults.value.some((item) => item.matchLevel === 'EXACT_BARCODE')
);
const suspectCount = computed(() => {
  if (includeAll.value) {
    return searchResults.value.filter((item) => item.matchLevel === 'SUSPECT').length;
  }
  return hiddenCount.value;
});
const confirmBarcodeMismatch = computed(() => {
  const item = confirmItem.value;
  return Boolean(
    item && skuBarcode.value.trim() && item.barCode && skuBarcode.value.trim() !== item.barCode
  );
});
const needsManualConfirm = computed(
  () => Boolean(confirmItem.value) && confirmItem.value?.matchLevel !== 'EXACT_BARCODE'
);

const createForm = ref({
  specs: '',
  brandName: '',
  purchasePrice: '',
  salePrice: '',
  mainImg: '',
  color: undefined as number | undefined,
  category: '',
  catId: undefined as number | undefined
});
const creating = ref(false);

// 将邑分类（CB-023 §4.1.4）：创建时 catId 必填，拉到列表就用下拉替代手填编号
interface JiangyiCategory {
  id: number;
  name: string;
}
const categories = ref<JiangyiCategory[]>([]);

const syncStatusMeta: Record<string, { label: string; type: 'success' | 'warning' | 'info' }> = {
  BOUND: { label: '已挂接', type: 'success' },
  CREATED: { label: '将邑新建', type: 'warning' },
  RETIRED: { label: '已解挂', type: 'info' }
};

type TagType = 'success' | 'warning' | 'info' | 'primary' | 'danger';

const matchMetaMap: Record<StdSkuView['matchLevel'], { label: string; type: TagType }> = {
  EXACT_BARCODE: { label: '条码一致', type: 'success' },
  SAME_SPEC: { label: '同规格', type: 'primary' },
  RELATED: { label: '相关', type: 'info' },
  SUSPECT: { label: '明显不符', type: 'danger' },
  UNKNOWN: { label: '未比对', type: 'info' }
};

function matchMeta(level: StdSkuView['matchLevel']) {
  return matchMetaMap[level] || matchMetaMap.UNKNOWN;
}

function sourceMeta(
  source: string | null,
  barCode?: string | null
): { label: string; type: TagType } {
  if (source === 'app') {
    return { label: '扫码条码', type: 'success' };
  }
  if (source === 'manual') {
    return { label: '手填条码', type: 'warning' };
  }
  if (source) {
    return { label: source, type: 'info' };
  }
  return { label: barCode ? '来源未知' : '无条码', type: 'info' };
}

function open(row: {
  skuId: string;
  barcode?: string | null;
  skuName?: string | null;
  spec?: string | null;
  brand?: string | null;
  imageUrl?: string | null;
}) {
  skuId.value = row.skuId;
  skuBarcode.value = row.barcode || '';
  skuName.value = row.skuName || '';
  skuSpec.value = row.spec || '';
  skuImageUrl.value = row.imageUrl || '';
  searchKeyword.value = (row.brand || '').trim();
  searchResults.value = [];
  searchTotal.value = 0;
  hiddenCount.value = 0;
  searchAttempted.value = false;
  scored.value = false;
  includeAll.value = false;
  confirmItem.value = null;
  confirmVisible.value = false;
  confirmedSame.value = false;
  // 主图默认取我方商品图（将邑侧必填；后端也会按 SKU 主图兜底，这里预填可核对/可改）
  createForm.value.mainImg = row.imageUrl || '';
  // 不能沿用上一个 SKU 选的包装类型：罐装/瓶装误选会写进将邑商品
  createForm.value.category = '';
  visible.value = true;
  void load();
  void loadCategories();
  // 有品牌就先用品牌搜一轮：名称搜全称往往一条都匹配不到，品牌能直接看到同款候选
  if (searchKeyword.value) {
    void search();
  }
}

async function loadCategories() {
  try {
    categories.value = await api.request<JiangyiCategory[]>(
      AdminEndpoints.jiangyiProductCategories,
      'GET'
    );
  } catch {
    // 拉取失败降级为手填编号（模板按 categories 为空切换），不阻断弹窗
    categories.value = [];
  }
}

async function load() {
  loading.value = true;
  try {
    links.value = await api.request<JiangyiLink[]>(
      AdminEndpoints.skuJiangyiLink(skuId.value),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '加载挂接信息失败'));
  } finally {
    loading.value = false;
  }
}

async function search() {
  searching.value = true;
  try {
    const query = new URLSearchParams();
    const name = searchKeyword.value.trim();
    // 名称优先：将邑 name+barCode 是 AND，叠加我方条码会把「库里有、条码不同」的商品全部滤掉
    if (name) {
      query.set('name', name);
    } else if (skuBarcode.value.trim()) {
      query.set('barCode', skuBarcode.value.trim());
    }
    if (skuId.value) {
      query.set('skuId', skuId.value);
    }
    if (includeAll.value) {
      query.set('includeAll', 'true');
    }
    // 旧 jar 仍返回裸数组（nginx 挂新前端、trade-service 未重建的过渡期），归一到新结构
    const response = await api.request<StdSkuSearchResult | StdSkuView[]>(
      AdminEndpoints.jiangyiStdSkus(query),
      'GET'
    );
    const rawItems = Array.isArray(response) ? response : response.items || [];
    searchResults.value = rawItems.map((item) => ({
      ...item,
      barCodeSource: item.barCodeSource ?? null,
      matchLevel: item.matchLevel ?? 'UNKNOWN',
      mismatchReasons: item.mismatchReasons ?? []
    }));
    scored.value = !Array.isArray(response);
    searchTotal.value = Array.isArray(response)
      ? searchResults.value.length
      : (response.total ?? searchResults.value.length);
    hiddenCount.value = Array.isArray(response) ? 0 : (response.hidden ?? 0);
    searchAttempted.value = true;
  } catch (e) {
    ElMessage.error(errorMessage(e, '搜索将邑商品库失败'));
  } finally {
    searching.value = false;
  }
}

function toggleIncludeAll() {
  includeAll.value = !includeAll.value;
  void search();
}

function openBindConfirm(item: StdSkuView) {
  confirmItem.value = item;
  confirmedSame.value = false;
  confirmVisible.value = true;
}

async function confirmBind() {
  const item = confirmItem.value;
  if (!item) {
    return;
  }
  binding.value = true;
  try {
    await api.request(AdminEndpoints.skuJiangyiLink(skuId.value), 'PUT', {
      jiangyiProductId: item.id,
      jiangyiName: item.name,
      barCode: item.barCode || undefined
    });
    ElMessage.success('已挂接');
    confirmVisible.value = false;
    await Promise.all([load(), search()]);
  } catch (e) {
    ElMessage.error(errorMessage(e, '挂接失败'));
  } finally {
    binding.value = false;
  }
}

async function createInJiangyi() {
  if (!createForm.value.catId) {
    ElMessage.warning('请选择将邑分类（列表加载失败时可手填编号）');
    return;
  }
  if (!createForm.value.category) {
    ElMessage.warning('请选择识别形态（将邑要求必填）');
    return;
  }
  try {
    await ElMessageBox.confirm(
      '将在将邑商品库新建该商品（名称/条码取自我方 SKU），成功后自动挂接。确认？',
      '新增到将邑确认',
      { type: 'warning', confirmButtonText: '新增', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  creating.value = true;
  try {
    await api.request(AdminEndpoints.skuJiangyiLinkCreate(skuId.value), 'POST', {
      name: skuName.value,
      specs: createForm.value.specs.trim() || '标准',
      catId: createForm.value.catId,
      brandName: createForm.value.brandName.trim() || '通用',
      purchasePrice: createForm.value.purchasePrice.trim() || '0',
      salePrice: createForm.value.salePrice.trim() || '0',
      mainImg: createForm.value.mainImg.trim() || '',
      color: createForm.value.color,
      category: createForm.value.category,
      barCode: skuBarcode.value.trim() || undefined
    });
    ElMessage.success('已在将邑新建商品并挂接');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '新增到将邑失败'));
  } finally {
    creating.value = false;
  }
}

async function pullTextName() {
  pulling.value = true;
  try {
    const n = await api.request<number>(
      AdminEndpoints.skuJiangyiLinkPullTextName(skuId.value),
      'POST'
    );
    ElMessage.success(`已按条码回填 ${n} 行 textName（学习完成后 classes 键）`);
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '回填 textName 失败'));
  } finally {
    pulling.value = false;
  }
}

async function unbind(link: JiangyiLink) {
  try {
    await ElMessageBox.confirm(
      '解挂后识别映射不再推荐此关联（记录留痕可复活）。确认解挂？',
      '解挂确认',
      { type: 'warning', confirmButtonText: '解挂', cancelButtonText: '取消' }
    );
  } catch {
    return;
  }
  try {
    await api.request(`${AdminEndpoints.skuJiangyiLink(skuId.value)}/${link.id}`, 'DELETE');
    ElMessage.success('已解挂');
    await load();
  } catch (e) {
    ElMessage.error(errorMessage(e, '解挂失败'));
  }
}

defineExpose({ open });
</script>

<template>
  <el-dialog
    v-model="visible"
    :title="`将邑商品库挂接 — ${skuName}（编号 ${skuId}）`"
    class="dialog-wide"
  >
    <div v-loading="loading">
      <div class="jy-link-section">
        <div class="jy-link-label">当前挂接</div>
        <div v-if="links.length === 0" class="jy-link-empty">未挂接任何将邑商品</div>
        <div v-for="link in links" :key="link.id" class="jy-link-card">
          <div class="jy-link-card-main">
            <div class="jy-link-card-title">
              <span class="jy-link-mono">{{ link.jiangyiProductId }}</span>
              <el-tag size="small" :type="syncStatusMeta[link.syncStatus]?.type || 'info'">
                {{ syncStatusMeta[link.syncStatus]?.label || link.syncStatus }}
              </el-tag>
            </div>
            <div class="jy-link-card-line">{{ link.jiangyiName || '—' }}</div>
            <div class="jy-link-card-sub">
              识别名称：{{ link.jiangyiTextName || '未回填' }}
              <template v-if="link.barCode">｜条码：{{ link.barCode }}</template>
            </div>
          </div>
          <div v-if="link.syncStatus !== 'RETIRED'" class="jy-link-card-actions">
            <el-button link type="primary" size="small" :loading="pulling" @click="pullTextName">
              回填识别名称
            </el-button>
            <el-button link type="danger" size="small" @click="unbind(link)">解挂</el-button>
          </div>
        </div>
      </div>

      <div class="jy-link-section">
        <div class="jy-link-label">搜索将邑商品库挂接</div>
        <div class="jy-link-row">
          <el-input
            v-model="searchKeyword"
            placeholder="商品名称（可空）"
            clearable
            style="flex: 1"
            @keyup.enter="search"
          />
          <el-button type="primary" :loading="searching" @click="search">搜索</el-button>
        </div>
        <div v-if="searchAttempted" class="jy-link-summary">
          共 {{ searchTotal }} 条<template v-if="suspectCount > 0"
            >，{{
              includeAll ? `其中 ${suspectCount} 条明显不符` : `已隐藏 ${suspectCount} 条明显不符`
            }}</template
          >
          <el-button
            v-if="suspectCount > 0"
            link
            type="primary"
            size="small"
            :loading="searching"
            @click="toggleIncludeAll"
          >
            {{ includeAll ? '收起不符项' : `显示全部 ${searchTotal} 条` }}
          </el-button>
        </div>
        <el-alert
          v-if="scored && searchAttempted && searchResults.length > 0 && !hasExactBarcodeMatch"
          type="warning"
          :closable="false"
          show-icon
          class="jy-link-alert"
          :title="
            skuBarcode.trim()
              ? `将邑库中没有与我方条码 ${skuBarcode} 完全一致的商品，请按图片/名称/规格核对后再挂接`
              : '我方 SKU 未登记条码，无法按条码校验，请按图片/名称/规格核对后再挂接'
          "
        />
        <div v-if="searchResults.length === 0" class="jy-link-empty">
          {{
            searchAttempted
              ? '没有匹配的将邑商品（可换关键词或显示全部）'
              : '输入名称搜索；名称留空时按我方条码搜索'
          }}
        </div>
        <div
          v-for="item in searchResults"
          :key="item.id"
          class="jy-link-card"
          :class="{ 'jy-link-card-suspect': item.matchLevel === 'SUSPECT' }"
        >
          <el-image v-if="item.mainImg" :src="item.mainImg" fit="cover" class="jy-link-thumb">
            <template #error><span class="jy-link-thumb-empty-text">无图</span></template>
          </el-image>
          <div v-else class="jy-link-thumb jy-link-thumb-empty">无图</div>
          <div class="jy-link-card-main">
            <div class="jy-link-card-title">
              <span class="jy-link-mono">编号 {{ item.id }}</span>
              <el-tag size="small" :type="matchMeta(item.matchLevel).type">
                {{ matchMeta(item.matchLevel).label }}
              </el-tag>
              <el-tag v-if="item.linked" size="small" type="info">已挂接</el-tag>
              <el-tag size="small" :type="sourceMeta(item.barCodeSource, item.barCode).type">
                {{ sourceMeta(item.barCodeSource, item.barCode).label }}
              </el-tag>
            </div>
            <div class="jy-link-card-line">{{ item.name }}</div>
            <div class="jy-link-card-sub">
              条码：{{ item.barCode || '—' }}｜规格：{{ item.specs || '—' }}
            </div>
            <div v-if="item.mismatchReasons.length" class="jy-link-card-warn">
              {{ item.mismatchReasons.join('；') }}
            </div>
          </div>
          <div class="jy-link-card-actions">
            <el-button
              v-if="!item.linked"
              link
              type="primary"
              size="small"
              @click="openBindConfirm(item)"
            >
              挂接
            </el-button>
          </div>
        </div>
      </div>

      <div class="jy-link-section">
        <div class="jy-link-label">或：将我方商品新增到将邑商品库</div>
        <el-form label-width="auto">
          <el-row :gutter="16">
            <el-col :span="12">
              <el-form-item label="规格">
                <el-input v-model="createForm.specs" placeholder="不填按「标准」提交" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="品牌">
                <el-input v-model="createForm.brandName" placeholder="不填按「通用」提交" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="进货价(元)">
                <el-input v-model="createForm.purchasePrice" placeholder="如 1.50" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="售价(元)">
                <el-input v-model="createForm.salePrice" placeholder="如 3.00" />
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="分类">
                <el-select
                  v-if="categories.length > 0"
                  v-model="createForm.catId"
                  placeholder="选择将邑分类"
                  style="width: 100%"
                >
                  <el-option
                    v-for="cat in categories"
                    :key="cat.id"
                    :label="`${cat.name}（${cat.id}）`"
                    :value="cat.id"
                  />
                </el-select>
                <el-input-number
                  v-else
                  v-model="createForm.catId"
                  :min="1"
                  :controls="false"
                  placeholder="将邑分类编号"
                  style="width: 100%"
                />
                <div class="jy-link-hint">将邑侧商品归类，不影响端侧识别</div>
              </el-form-item>
            </el-col>
            <el-col :span="12">
              <el-form-item label="识别形态">
                <el-select
                  v-model="createForm.category"
                  clearable
                  placeholder="请选择包装类型"
                  style="width: 100%"
                >
                  <el-option label="瓶装" value="bottle" />
                  <el-option label="盒装" value="box" />
                  <el-option label="袋装" value="bag" />
                  <el-option label="碗装" value="bowl" />
                  <el-option label="蛋类" value="egg" />
                </el-select>
                <div class="jy-link-hint">
                  用于将邑侧商品归类，不影响端侧识别。将邑仅支持这五种，无「罐装」——罐装饮品请选「瓶装」
                </div>
              </el-form-item>
            </el-col>
            <el-col :span="24">
              <el-form-item label="商品主图">
                <div class="jy-link-img-row">
                  <el-input
                    v-model="createForm.mainImg"
                    placeholder="默认取我方商品主图，可改为公网可访问的图片地址"
                    clearable
                  />
                  <img
                    v-if="createForm.mainImg.trim()"
                    :src="createForm.mainImg.trim()"
                    class="jy-link-img-preview"
                    alt="主图预览"
                  />
                </div>
                <div class="jy-link-hint">将邑要求必填；留空时后端自动回退我方商品主图</div>
              </el-form-item>
            </el-col>
          </el-row>
        </el-form>
        <el-button type="primary" :loading="creating" @click="createInJiangyi"
          >新增到将邑商品库</el-button
        >
      </div>
    </div>
  </el-dialog>

  <el-dialog v-model="confirmVisible" title="核对并挂接" width="720px" append-to-body>
    <div v-if="confirmItem" class="jy-compare">
      <div class="jy-compare-col">
        <div class="jy-compare-title">我方 SKU</div>
        <div class="jy-compare-img-box">
          <el-image v-if="skuImageUrl" :src="skuImageUrl" fit="cover" class="jy-compare-img">
            <template #error><span class="jy-link-thumb-empty-text">无图</span></template>
          </el-image>
          <div v-else class="jy-link-thumb-empty">无图</div>
        </div>
        <div class="jy-compare-name">{{ skuName || '—' }}</div>
        <div class="jy-compare-row">规格：{{ skuSpec || '—' }}</div>
        <div class="jy-compare-row" :class="{ 'jy-compare-diff': confirmBarcodeMismatch }">
          条码：{{ skuBarcode || '—' }}
        </div>
      </div>
      <div class="jy-compare-col">
        <div class="jy-compare-title">将邑商品（编号 {{ confirmItem.id }}）</div>
        <div class="jy-compare-img-box">
          <el-image
            v-if="confirmItem.mainImg"
            :src="confirmItem.mainImg"
            fit="cover"
            class="jy-compare-img"
          >
            <template #error><span class="jy-link-thumb-empty-text">无图</span></template>
          </el-image>
          <div v-else class="jy-link-thumb-empty">无图</div>
        </div>
        <div class="jy-compare-name">{{ confirmItem.name }}</div>
        <div class="jy-compare-row">规格：{{ confirmItem.specs || '—' }}</div>
        <div class="jy-compare-row" :class="{ 'jy-compare-diff': confirmBarcodeMismatch }">
          条码：{{ confirmItem.barCode || '—' }}
          <el-tag
            size="small"
            :type="sourceMeta(confirmItem.barCodeSource, confirmItem.barCode).type"
          >
            {{ sourceMeta(confirmItem.barCodeSource, confirmItem.barCode).label }}
          </el-tag>
        </div>
      </div>
    </div>
    <el-alert
      v-if="needsManualConfirm"
      type="warning"
      :closable="false"
      show-icon
      class="jy-link-alert"
      title="双方条码不一致或无法校验。69 码随包装版本不同，条码不同不阻断挂接，但请确认图片/名称/规格指向同一实物。"
    />
    <ul v-if="confirmItem && confirmItem.mismatchReasons.length" class="jy-compare-reasons">
      <li v-for="reason in confirmItem.mismatchReasons" :key="reason">{{ reason }}</li>
    </ul>
    <el-checkbox v-if="needsManualConfirm" v-model="confirmedSame" class="jy-compare-check">
      已核对为同一实物
    </el-checkbox>
    <template #footer>
      <el-button @click="confirmVisible = false">取消</el-button>
      <el-button
        type="primary"
        :loading="binding"
        :disabled="needsManualConfirm && !confirmedSame"
        @click="confirmBind"
      >
        挂接
      </el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.jy-link-section {
  margin-bottom: 16px;
}
.jy-link-label {
  font-size: 13px;
  color: var(--color-text-secondary);
  margin-bottom: 6px;
}
.jy-link-row {
  display: flex;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  flex-wrap: wrap;
}
.jy-link-mono {
  font-family: var(--font-mono);
  font-size: 12px;
}
.jy-link-empty {
  font-size: 12px;
  color: var(--color-text-tertiary);
  padding: 8px 0;
}
.jy-link-summary {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-bottom: 6px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.jy-link-alert {
  margin-bottom: 8px;
}
.jy-link-thumb {
  width: 56px;
  height: 56px;
  border-radius: 4px;
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}
.jy-link-thumb-empty {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  color: var(--color-text-tertiary);
  background: var(--el-fill-color-lighter, #f5f7fa);
}
.jy-link-thumb-empty-text {
  font-size: 11px;
  color: var(--color-text-tertiary);
}
.jy-link-card-suspect {
  border-color: var(--el-color-danger);
  background: color-mix(in srgb, var(--el-color-danger) 4%, transparent);
}
.jy-link-card-warn {
  font-size: 12px;
  color: var(--el-color-danger);
  margin-top: 2px;
}
.jy-link-card {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  margin-bottom: 8px;
}
.jy-link-card-main {
  min-width: 0;
  flex: 1;
}
.jy-link-card-title {
  display: flex;
  align-items: center;
  gap: 8px;
}
.jy-link-card-line {
  font-size: 13px;
  margin-top: 2px;
}
.jy-link-card-sub {
  font-size: 12px;
  color: var(--color-text-tertiary);
  margin-top: 2px;
}
.jy-link-card-actions {
  flex-shrink: 0;
  white-space: nowrap;
}
.jy-link-hint {
  font-size: 12px;
  color: var(--color-text-tertiary);
}
.jy-link-img-row {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
}
.jy-link-img-preview {
  width: 40px;
  height: 40px;
  object-fit: cover;
  border-radius: 4px;
  border: 1px solid var(--color-border);
  flex-shrink: 0;
}
.jy-compare {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}
.jy-compare-col {
  min-width: 0;
}
.jy-compare-title {
  font-size: 13px;
  font-weight: 600;
  margin-bottom: 8px;
}
.jy-compare-img-box {
  width: 100%;
  height: 180px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  overflow: hidden;
  display: flex;
  align-items: center;
  justify-content: center;
}
.jy-compare-img {
  width: 100%;
  height: 100%;
}
.jy-compare-name {
  font-size: 14px;
  margin: 8px 0 4px;
  word-break: break-all;
}
.jy-compare-row {
  font-size: 12px;
  color: var(--color-text-secondary);
  margin-top: 4px;
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}
.jy-compare-diff {
  color: var(--el-color-danger);
  font-weight: 600;
}
.jy-compare-reasons {
  margin: 4px 0 0 18px;
  padding: 0;
  font-size: 12px;
  color: var(--el-color-danger);
}
.jy-compare-check {
  margin-top: 10px;
}
</style>
