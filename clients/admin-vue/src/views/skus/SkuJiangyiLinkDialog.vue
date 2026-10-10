<script setup lang="ts">
import { ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { api } from '@/api/client';
import { AdminEndpoints } from '@/api/endpoints';
import { errorMessage } from '@/utils/error-message';

/**
 * 将邑商品库挂接弹窗（CB-023 二期，范围 B）：
 * 我方 SKU ↔ 将邑商品（商品维度关联，识别 classId 映射之外的另一半）。
 * 能力：搜索将邑商品库挂接 / 我方商品新增到将邑 / 学习后按条码回填 textName / 解挂。
 * jiangyi_product_id 全局唯一（一个将邑商品至多挂一个我方 SKU）；barCode 不一致拒绝挂接。
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
}

const visible = ref(false);
const skuId = ref('');
const skuBarcode = ref('');
const skuName = ref('');
const links = ref<JiangyiLink[]>([]);
const loading = ref(false);

const searchKeyword = ref('');
const searchResults = ref<StdSkuView[]>([]);
const searching = ref(false);
const binding = ref(false);
const pulling = ref(false);

const createForm = ref({
  specs: '',
  brandName: '',
  purchasePrice: '',
  salePrice: '',
  mainImg: '',
  color: undefined as number | undefined,
  category: 'bottle',
  catId: undefined as number | undefined
});
const creating = ref(false);

const syncStatusMeta: Record<string, { label: string; type: 'success' | 'warning' | 'info' }> = {
  BOUND: { label: '已挂接', type: 'success' },
  CREATED: { label: '将邑新建', type: 'warning' },
  RETIRED: { label: '已解挂', type: 'info' }
};

function open(row: { skuId: string; barcode?: string | null; skuName?: string | null }) {
  skuId.value = row.skuId;
  skuBarcode.value = row.barcode || '';
  skuName.value = row.skuName || '';
  visible.value = true;
  void load();
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
    if (searchKeyword.value.trim()) query.set('name', searchKeyword.value.trim());
    if (skuBarcode.value.trim()) query.set('barCode', skuBarcode.value.trim());
    searchResults.value = await api.request<StdSkuView[]>(
      AdminEndpoints.jiangyiStdSkus(query),
      'GET'
    );
  } catch (e) {
    ElMessage.error(errorMessage(e, '搜索将邑商品库失败'));
  } finally {
    searching.value = false;
  }
}

async function bind(item: StdSkuView) {
  try {
    await ElMessageBox.confirm(
      `将 SKU「${skuName.value}」挂接到将邑商品「${item.name}」？若两边条码不一致将被拒绝。`,
      '挂接确认',
      { type: 'warning', confirmButtonText: '挂接', cancelButtonText: '取消' }
    );
  } catch {
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
    await Promise.all([load(), search()]);
  } catch (e) {
    ElMessage.error(errorMessage(e, '挂接失败'));
  } finally {
    binding.value = false;
  }
}

async function createInJiangyi() {
  if (!createForm.value.catId) {
    ElMessage.warning('请填写将邑分类 ID（category 接口可查）');
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
  <el-dialog v-model="visible" :title="`将邑商品库挂接 — ${skuName}（${skuId}）`" width="760px">
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
              textName（classes 键）：{{ link.jiangyiTextName || '未回填' }}
              <template v-if="link.barCode">｜条码：{{ link.barCode }}</template>
            </div>
          </div>
          <div v-if="link.syncStatus !== 'RETIRED'" class="jy-link-card-actions">
            <el-button link type="primary" size="small" :loading="pulling" @click="pullTextName">
              回填 textName
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
        <div v-if="searchResults.length === 0" class="jy-link-empty">
          输入名称后搜索（默认按我方条码）
        </div>
        <div v-for="item in searchResults" :key="item.id" class="jy-link-card">
          <div class="jy-link-card-main">
            <div class="jy-link-card-title">
              <span class="jy-link-mono">#{{ item.id }}</span>
              <el-tag v-if="item.linked" size="small" type="info">已挂接</el-tag>
            </div>
            <div class="jy-link-card-line">{{ item.name }}</div>
            <div class="jy-link-card-sub">
              条码：{{ item.barCode || '—' }}｜规格：{{ item.specs || '—' }}
            </div>
          </div>
          <div class="jy-link-card-actions">
            <el-button
              v-if="!item.linked"
              link
              type="primary"
              size="small"
              :loading="binding"
              @click="bind(item)"
            >
              挂接
            </el-button>
          </div>
        </div>
      </div>

      <div class="jy-link-section">
        <div class="jy-link-label">或：将我方商品新增到将邑商品库</div>
        <div class="jy-link-row">
          <el-input
            v-model="createForm.specs"
            placeholder="规格（默认 标准）"
            style="width: 120px"
          />
          <el-input
            v-model="createForm.brandName"
            placeholder="品牌（默认 通用）"
            style="width: 120px"
          />
          <el-input v-model="createForm.purchasePrice" placeholder="进货价" style="width: 100px" />
          <el-input v-model="createForm.salePrice" placeholder="售价" style="width: 100px" />
          <el-input-number
            v-model="createForm.catId"
            placeholder="分类 ID"
            :min="1"
            :controls="false"
            style="width: 100px"
          />
          <el-select v-model="createForm.category" style="width: 100px">
            <el-option label="bottle" value="bottle" />
            <el-option label="box" value="box" />
            <el-option label="bag" value="bag" />
            <el-option label="bowl" value="bowl" />
            <el-option label="egg" value="egg" />
          </el-select>
          <el-button type="primary" :loading="creating" @click="createInJiangyi">新增</el-button>
        </div>
        <div class="jy-link-hint">
          分类 ID 可通过将邑分类接口查询；识别形态类别 bottle/box/bag/bowl/egg。
        </div>
      </div>
    </div>
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
</style>
