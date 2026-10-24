<template>
  <view class="page">
    <app-nav-bar title="仓库盘点" home-url="/pages/home/home" />
    <view class="page-body">
      <!-- ── 列表 ── -->
      <view v-if="mode === 'list'" class="panel">
        <view class="card">
          <text class="label">盘点仓</text>
          <text class="warehouse-name">{{ warehouseName || '加载中…' }}</text>
          <text class="hint">仅本人负责的分仓可盘点</text>
        </view>

        <app-underline-tabs :items="statusTabs" :value="statusTab" @change="onStatusTab" />

        <view v-if="listError" class="card">
          <error-state :message="listError" retry-label="重试" @retry="loadList" />
        </view>
        <view v-else-if="listLoading" class="card"><view class="empty-inline">加载中…</view></view>
        <view v-else-if="!stocktakes.length" class="card">
          <view class="empty-inline">暂无盘点单</view>
        </view>
        <view
          v-for="st in stocktakes"
          v-else
          :key="st.stocktakeId"
          role="button"
          class="card st-card"
          @click="openDetail(Number(st.stocktakeId))"
        >
          <view class="row-between">
            <text class="st-no">#{{ st.stocktakeNo }}</text>
            <text class="st-status" :class="lineStateClass(st.status)">{{
              stocktakeStatusLabel(st.status)
            }}</text>
          </view>
          <text class="sku-meta">
            {{ stocktakeModeLabel(st.mode) }} · 账面 {{ st.bookQty }} · 实盘
            {{ st.countedQty ?? '—' }} · 差异 {{ st.diffQty ?? 0 }}（{{ st.diffLineCount ?? 0 }}
            行）
          </text>
          <text class="sku-meta">{{ fmtTime(st.createdAt) }}</text>
        </view>

        <!-- 2026-10-23 审计残留：原先只取 page0/size20，>20 条静默截断；补加载更多。 -->
        <text v-if="listHasMore" role="button" class="load-more" @click="loadMoreStocktakes">
          {{ listLoadingMore ? '加载中…' : `加载更多（已显示 ${stocktakes.length}/${listTotal}）` }}
        </text>

        <app-button
          block
          variant="primary"
          label="发起盘点"
          :disabled="!canRequest"
          @click="mode = 'create'"
        />
        <text v-if="!canRequest" class="err">当前账号无盘点操作权限</text>
      </view>

      <!-- ── 建单 ── -->
      <view v-else-if="mode === 'create'" class="panel">
        <view class="card">
          <text class="label">盘点仓</text>
          <text class="warehouse-name">{{ warehouseName || '加载中…' }}</text>
        </view>
        <view class="card">
          <text class="label">盘点方式</text>
          <view class="mode-row">
            <view
              role="button"
              class="mode-opt"
              :class="{ on: createMode === 'OPEN' }"
              @click="createMode = 'OPEN'"
            >
              <text class="mode-title">明盘</text>
              <text class="mode-desc">显示账面数，录入时对照</text>
            </view>
            <view
              role="button"
              class="mode-opt"
              :class="{ on: createMode === 'BLIND' }"
              @click="createMode = 'BLIND'"
            >
              <text class="mode-title">盲盘</text>
              <text class="mode-desc">不显示账面数，直接实盘</text>
            </view>
          </view>
          <text class="hint block-hint">按库存批次逐批盘点；有货批次才会生成盘点行</text>
        </view>
        <view class="card">
          <text class="label">备注（可选）</text>
          <input
            v-model="createNotes"
            class="input"
            placeholder="如：月末例行盘点"
            maxlength="80"
          />
        </view>
        <app-button
          block
          variant="primary"
          :loading="creating"
          label="创建盘点单"
          @click="submitCreate"
        />
        <app-button block label="返回列表" @click="mode = 'list'" />
      </view>

      <!-- ── 详情 / 录入 ── -->
      <view v-else class="panel">
        <view v-if="detailError" class="card">
          <error-state :message="detailError" retry-label="重试" @retry="loadDetail" />
        </view>
        <template v-else-if="detail">
          <view class="card">
            <view class="row-between">
              <text class="st-no">#{{ detail.stocktakeNo }}</text>
              <text class="st-status" :class="lineStateClass(detail.status)">{{
                stocktakeStatusLabel(detail.status)
              }}</text>
            </view>
            <text class="sku-meta">
              {{ stocktakeModeLabel(detail.mode) }} · 账面 {{ detail.bookQty }} · 实盘
              {{ detail.countedQty ?? '—' }}
            </text>
            <text
              v-if="detail.status === 'COMPLETED' && (detail.diffLineCount ?? 0) > 0"
              class="hint"
            >
              已完成，可「完成并过账」落库存，或逐行核对差异原因后再过账
            </text>
          </view>

          <view class="card">
            <text class="label">盘点明细（{{ detail.lines?.length || 0 }} 行）</text>
            <view v-if="!detail.lines?.length" class="empty-inline">
              该仓暂无有货批次，无需盘点
            </view>
            <view v-for="row in editRows" :key="row.lineId" class="line-block">
              <view class="row-between">
                <view class="line-copy">
                  <text class="sku-name">{{ row.skuName }}</text>
                  <text class="sku-meta">
                    {{ row.skuId }} · 批次 {{ row.batchNo || '—' }} · 账面
                    {{ detail.mode === 'BLIND' && !isDone ? '隐' : row.bookQty }}
                  </text>
                </view>
                <text class="st-status" :class="lineStateClass(row.status)">{{
                  isBlindActive ? '' : diffDirectionLabel(rowDiff(row))
                }}</text>
              </view>
              <view class="entry-row">
                <input
                  v-model="row.countedInput"
                  class="input qty-input"
                  type="number"
                  :disabled="!rowEditable(row)"
                  placeholder="实盘数"
                />
                <picker
                  :range="reasonLabels(row)"
                  :value="reasonIndex(row)"
                  :disabled="!rowEditable(row)"
                  @change="onReasonPick(row, $event)"
                >
                  <view class="picker reason-picker">{{ reasonLabel(row) }}</view>
                </picker>
                <view
                  v-if="rowEditable(row)"
                  role="button"
                  class="save-btn"
                  :class="{ saving: row.saving }"
                  @click="saveLine(row)"
                >
                  保存
                </view>
              </view>
              <input
                v-if="rowEditable(row)"
                v-model="row.notesInput"
                class="input"
                placeholder="备注（可选，如：破损 2 件）"
                maxlength="80"
              />
              <text v-else-if="row.notesInput" class="sku-meta">备注：{{ row.notesInput }}</text>
            </view>
          </view>
          <view class="card">
            <text class="label">现场照片（{{ photos.length }}/5）</text>
            <view
              v-if="!isDone && photos.length < 5"
              role="button"
              class="photo-add-btn"
              @click="choosePhoto"
            >
              ＋ 拍照 / 从相册选图
            </view>
            <view v-if="!photos.length" class="empty-inline">暂无照片</view>
            <view v-else class="photo-grid">
              <image
                v-for="p in photos"
                :key="p.fileId"
                class="photo"
                :src="p.localPath"
                mode="aspectFill"
              />
            </view>
          </view>

          <template v-if="!isDone">
            <app-button
              block
              variant="primary"
              :loading="acting"
              label="完成并过账（差异直接落库存）"
              @click="doCompleteAndAdjust"
            />
            <app-button block :loading="acting" label="仅完成（先核对差异）" @click="doComplete" />
            <app-button v-if="detail.status === 'DRAFT'" block label="取消盘点" @click="doCancel" />
          </template>
          <app-button block label="返回列表" @click="backToList" />
        </template>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
import { hasPerm, merchantApi, isMerchantLoggedIn } from '@/utils/merchant-api';
import { useMerchantMe, seedMerchantMeDisplayCache } from '@/composables/useMerchantMe';
import { formatDateTimeMinute } from '@aicabinet/shared-uni/format';
// 铁律 40 家族：mp 运行时无 URLSearchParams（shared-uni/query.ts 注释自证），
// 修复 2026-10-14 盘点列表在真机恒「加载失败」的 ReferenceError。
import { toQuery } from '@aicabinet/shared-uni/query';
import {
  blindReasonOptions,
  buildUpdateLineBody,
  diffDirectionLabel,
  diffReasonLabel,
  diffReasonOptionsFor,
  isLineEditable,
  stocktakeLineStateClass,
  stocktakeModeLabel,
  stocktakeStatusLabel,
  STOCKTAKE_STATUS_TABS
} from '@/utils/warehouse-stocktake';

type Stocktake = import('@aicabinet/shared-types').OpenApiStocktakeDto;
type StocktakeLine = import('@aicabinet/shared-types').OpenApiStocktakeLineDto;

interface EditRow {
  lineId: number;
  skuId: string;
  skuName: string;
  batchNo: string;
  bookQty: number;
  status: string;
  countedInput: string;
  notesInput: string;
  diffReason: string;
  saving: boolean;
}

interface PhotoView {
  fileId: number;
  localPath: string;
}

const { me, refresh: refreshMe } = useMerchantMe();
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));

const mode = ref<'list' | 'create' | 'detail'>('list');
const warehouseName = ref('');

// ── 列表 ──
const statusTabs = STOCKTAKE_STATUS_TABS;
const statusTab = ref('ALL');
const stocktakes = ref<Stocktake[]>([]);
const listLoading = ref(false);
const listError = ref('');
// 2026-10-23 审计残留：列表翻页（原先只取第一页 20 条）
const listTotal = ref(0);
const listPage = ref(0);
const listHasMore = ref(false);
const listLoadingMore = ref(false);
/** 列表分页序号守卫（2026-10-24 缺陷类门禁 H）：切 tab/下拉刷新使迟到旧页 append 失效 */
let listSeq = 0;
/** 凭证上传在途门闩（2026-10-24 缺陷类门禁 F4）：连点拍照只发一次，对齐 device-stocktake 口径 */
const uploadingPhoto = ref(false);

// ── 建单 ──
const createMode = ref('OPEN');
const createNotes = ref('');
const creating = ref(false);

// ── 详情 ──
const detail = ref<Stocktake | null>(null);
const detailId = ref(0);
const detailError = ref('');
const editRows = ref<EditRow[]>([]);
const photos = ref<PhotoView[]>([]);
const acting = ref(false);
/** 详情请求序号守卫：快速切换盘点单时丢弃过期响应（修复 2026-10-14 竞态：A 单数据配 B 单照片）。 */
let detailSeq = 0;

const isDone = computed(() => {
  const s = detail.value?.status;
  return s === 'COMPLETED' || s === 'ADJUSTED' || s === 'CANCELLED';
});

/** 盲盘录入中：不显示盘盈/盘亏方向、原因选项给全量（不泄露账面方向）。 */
const isBlindActive = computed(() => detail.value?.mode === 'BLIND' && !isDone.value);

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const id = typeof opts?.stocktakeId === 'string' ? Number(opts.stocktakeId) : 0;
  void bootstrap(id);
});

onPullDownRefresh(() => {
  const reload =
    mode.value === 'detail' ? loadDetail() : mode.value === 'list' ? loadList() : Promise.resolve();
  void reload.finally(() => uni.stopPullDownRefresh());
});

async function bootstrap(preferDetailId = 0) {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  try {
    const wh = await merchantApi.satelliteWarehouse();
    warehouseName.value = wh.warehouseName || wh.warehouseId || '';
  } catch {
    warehouseName.value = '';
    showError('未找到您负责的分仓，无法盘点');
    return;
  }
  if (preferDetailId > 0) {
    detailId.value = preferDetailId;
    mode.value = 'detail';
    await loadDetail();
    return;
  }
  await loadList();
}

function onStatusTab(v: string) {
  statusTab.value = v;
  void loadList();
}

async function loadList() {
  // A 类模式：入口无条件复位在途 loadMore + 递增序号作废迟到旧页（对齐 tally-records）
  listSeq += 1;
  listLoadingMore.value = false;
  listLoading.value = true;
  listError.value = '';
  try {
    const q = toQuery({
      page: 0,
      size: 20,
      status: statusTab.value === 'ALL' ? null : statusTab.value
    });
    const page = await merchantApi.warehouseStocktakes(q);
    stocktakes.value = page?.items || [];
    listTotal.value = page?.total ?? stocktakes.value.length;
    listPage.value = 0;
    listHasMore.value = stocktakes.value.length < listTotal.value;
  } catch (e) {
    listError.value = e instanceof Error ? e.message : '盘点单加载失败';
  } finally {
    listLoading.value = false;
  }
}

async function loadMoreStocktakes() {
  if (!listHasMore.value || listLoadingMore.value || listLoading.value) return;
  const seq = ++listSeq;
  listLoadingMore.value = true;
  try {
    const q = toQuery({
      page: listPage.value + 1,
      size: 20,
      status: statusTab.value === 'ALL' ? null : statusTab.value
    });
    const page = await merchantApi.warehouseStocktakes(q);
    if (seq !== listSeq) return;
    const items = page?.items || [];
    stocktakes.value = stocktakes.value.concat(items);
    listTotal.value = page?.total ?? listTotal.value;
    listPage.value += 1;
    listHasMore.value = stocktakes.value.length < listTotal.value;
  } catch (e) {
    if (seq !== listSeq) return;
    showError(e instanceof Error ? e.message : '加载更多失败');
  } finally {
    if (seq === listSeq) listLoadingMore.value = false;
  }
}

async function submitCreate() {
  if (creating.value) return;
  creating.value = true;
  try {
    const st = await merchantApi.warehouseStocktakeCreate({
      mode: createMode.value,
      notes: createNotes.value.trim() || undefined
    });
    showSuccess(`盘点单 #${st.stocktakeNo} 已创建`);
    createNotes.value = '';
    detailId.value = Number(st.stocktakeId);
    mode.value = 'detail';
    await loadDetail();
  } catch (e) {
    showError(e instanceof Error ? e.message : '创建盘点单失败');
  } finally {
    creating.value = false;
  }
}

async function openDetail(stocktakeId: number) {
  detailId.value = stocktakeId;
  mode.value = 'detail';
  await loadDetail();
}

function backToList() {
  mode.value = 'list';
  void loadList();
}

async function loadDetail() {
  const seq = ++detailSeq;
  detailError.value = '';
  try {
    const st = await merchantApi.warehouseStocktake(detailId.value);
    if (seq !== detailSeq) return;
    detail.value = st;
    editRows.value = (st.lines || []).map((l: StocktakeLine) => ({
      lineId: Number(l.lineId),
      skuId: l.skuId || '',
      skuName: l.skuName || l.skuId || '',
      batchNo: l.batchNo || '',
      bookQty: Number(l.bookQty ?? 0),
      status: l.status || 'PENDING',
      countedInput: l.countedQty == null ? '' : String(l.countedQty),
      notesInput: l.notes || '',
      diffReason: l.diffReason || '',
      saving: false
    }));
    await loadPhotos(seq);
  } catch (e) {
    if (seq !== detailSeq) return;
    detailError.value = e instanceof Error ? e.message : '盘点单加载失败';
  }
}

// ── 行编辑 ──
function rowEditable(row: EditRow): boolean {
  return isLineEditable(detail.value?.status, row.status);
}

function rowDiff(row: EditRow): number | null {
  const n = Number(row.countedInput);
  if (!Number.isInteger(n) || n < 0) return null;
  return n - row.bookQty;
}

function reasonOptions(row: EditRow) {
  // 盲盘进行中给全量选项（不按账面方向泄露）；方向选反由服务端 400 兜底。
  if (isBlindActive.value) return blindReasonOptions();
  return diffReasonOptionsFor(rowDiff(row) ?? row.bookQty * -1);
}

function reasonLabels(row: EditRow): string[] {
  return reasonOptions(row).map((o) => o.label);
}

function reasonIndex(row: EditRow): number {
  const idx = reasonOptions(row).findIndex((o) => o.value === row.diffReason);
  return idx >= 0 ? idx : reasonOptions(row).length - 1;
}

function reasonLabel(row: EditRow): string {
  return diffReasonLabel(row.diffReason || null);
}

function onReasonPick(row: EditRow, e: { detail: { value: string } }) {
  const opt = reasonOptions(row)[Number(e.detail.value) || 0];
  if (opt) row.diffReason = opt.value;
}

async function saveLine(row: EditRow) {
  // 修复 2026-10-23：row.saving 此前只有赋值无读取（死变量）＝防重实际不存在，
  // 双击会并发两个 PUT 重复写台账。入口守卫 + 按钮 .saving 态。
  if (row.saving) return;
  const counted = row.countedInput === '' ? null : Number(row.countedInput);
  const built = buildUpdateLineBody(counted, row.notesInput, row.diffReason);
  if (!built.ok) {
    showError(built.error);
    return;
  }
  row.saving = true;
  try {
    const updated = await merchantApi.warehouseStocktakeUpdateLine(
      detailId.value,
      row.lineId,
      built.body
    );
    row.status = updated.status || 'PENDING';
    // 修复 2026-10-14：不再 loadDetail 重建整表——那会把其它行未保存的
    // 实盘数/备注/原因冲掉（现场盘点数据丢失）。就地只更新本行状态。
    showSuccess('已保存');
  } catch (e) {
    showError(e instanceof Error ? e.message : '保存失败');
  } finally {
    row.saving = false;
  }
}

// ── 照片 ──
async function loadPhotos(seq = detailSeq) {
  try {
    const list = await merchantApi.warehouseStocktakeEvidenceList(detailId.value);
    if (seq !== detailSeq) return;
    const views: PhotoView[] = [];
    for (const att of list || []) {
      const localPath = await merchantApi.downloadStocktakeEvidence(
        detailId.value,
        Number(att.fileId)
      );
      if (seq !== detailSeq) return;
      if (localPath) views.push({ fileId: Number(att.fileId), localPath });
    }
    if (seq !== detailSeq) return;
    photos.value = views;
  } catch {
    if (seq !== detailSeq) return;
    photos.value = [];
  }
}

function choosePhoto() {
  if (uploadingPhoto.value) return; // 在途门闩：连点拍照只发一次（2026-10-24 缺陷类门禁 F4）
  uni.chooseImage({
    count: 1,
    sourceType: ['camera', 'album'],
    success: (res) => {
      const path = res.tempFilePaths?.[0];
      if (path) void uploadPhoto(path);
    }
  });
}

async function uploadPhoto(path: string) {
  if (uploadingPhoto.value) return;
  uploadingPhoto.value = true;
  try {
    await merchantApi.uploadStocktakeEvidence(detailId.value, path);
    showSuccess('照片已上传');
    await loadPhotos();
  } catch (e) {
    showError(e instanceof Error ? e.message : '照片上传失败');
  } finally {
    uploadingPhoto.value = false;
  }
}

// ── 动作 ──
function confirmThen(action: () => Promise<void>, title: string, confirmText: string) {
  uni.showModal({
    title,
    content:
      title === '完成并过账' ? '差异将直接调整分仓库存（不可逆）。确认？' : '确认执行该操作？',
    confirmText,
    cancelText: '取消',
    success: (res) => {
      if (res.confirm) void action();
    }
  });
}

function doComplete() {
  confirmThen(
    async () => {
      acting.value = true;
      try {
        detail.value = await merchantApi.warehouseStocktakeComplete(detailId.value);
        showSuccess('盘点已完成');
        await loadDetail();
      } catch (e) {
        showError(e instanceof Error ? e.message : '完成失败');
      } finally {
        acting.value = false;
      }
    },
    '仅完成',
    '确认完成'
  );
}

function doCompleteAndAdjust() {
  confirmThen(
    async () => {
      acting.value = true;
      try {
        detail.value = await merchantApi.warehouseStocktakeCompleteAndAdjust(detailId.value);
        showSuccess('已过账');
        await loadDetail();
      } catch (e) {
        showError(e instanceof Error ? e.message : '过账失败');
      } finally {
        acting.value = false;
      }
    },
    '完成并过账',
    '确认过账'
  );
}

function doCancel() {
  confirmThen(
    async () => {
      acting.value = true;
      try {
        detail.value = await merchantApi.warehouseStocktakeCancel(detailId.value);
        showSuccess('已取消');
        await loadDetail();
      } catch (e) {
        showError(e instanceof Error ? e.message : '取消失败');
      } finally {
        acting.value = false;
      }
    },
    '取消盘点',
    '确认取消'
  );
}

function fmtTime(v: string | undefined): string {
  return formatDateTimeMinute(v);
}

function lineStateClass(status: string | null | undefined) {
  return stocktakeLineStateClass(status);
}
</script>

<style scoped src="./warehouse-stocktake.page.css"></style>
