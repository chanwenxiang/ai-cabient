<template>
  <view class="page-root">
    <app-nav-bar title="仓库盘点" home-url="/pages/home/home" />
    <view class="page-body" :class="{ 'page-body-dock': showDock }">
      <!-- ── 列表 ── -->
      <template v-if="mode === 'list'">
        <!-- ① 分仓身份一条：与柜机盘点同语言（图标+名称+说明），不再「标签/仓名/提示」三行各占一处 -->
        <view v-if="warehouseGate.state === 'error'" class="card">
          <error-state :message="warehouseError" retry-label="重试" @retry="retryWarehouse" />
        </view>
        <view v-else class="card wh-card">
          <view class="wh-mark" aria-hidden="true"><text class="wh-mark-txt">仓</text></view>
          <view class="wh-id">
            <text class="wh-name">{{ warehouseGate.nameText }}</text>
            <text class="wh-hint">仅本人负责的分仓可盘点</text>
          </view>
        </view>

        <!-- ② 状态筛选：5 个页签超屏宽，右侧渐隐告诉用户「还能往左滑」 -->
        <view class="tabs-wrap">
          <app-underline-tabs
            layout="scroll"
            :items="statusTabs"
            :value="statusTab"
            @change="onStatusTab"
          />
        </view>

        <!-- ③ 盘点单列表 -->
        <view v-if="listError" class="card">
          <error-state :message="listError" retry-label="重试" @retry="loadList" />
        </view>
        <view v-else-if="listLoading" class="card">
          <view class="empty-inline">加载中…</view>
        </view>
        <view v-else-if="!stocktakes.length" class="card">
          <view class="empty-inline">
            {{ statusTab === 'ALL' ? '暂无盘点单' : `没有「${tabLabel}」的盘点单` }}
          </view>
        </view>
        <template v-else>
          <view
            v-for="st in stocktakes"
            :key="st.stocktakeId"
            role="button"
            class="card st-card"
            @click="openDetail(Number(st.stocktakeId))"
          >
            <view class="st-top">
              <text class="st-no">{{ st.stocktakeNo }}</text>
              <text class="badge" :class="statusBadgeClass(st.status)">{{
                stocktakeStatusLabel(st.status)
              }}</text>
            </view>
            <!-- MA-032：明盘单创建时 counted 已预填账面（DB 实证全 PENDING、counted=book），
                 所以「草稿」态若照常报「实盘 502 · 差异 0」会被读成「已核平无差异」。
                 数字没撒谎，撒谎的是状态语义 ⇒ 未录入时不报差异。 -->
            <view class="st-facts">
              <text class="chip">{{ stocktakeModeLabel(st.mode) }}</text>
              <text class="chip">账面 {{ st.bookQty }}</text>
              <text v-if="st.status === 'DRAFT'" class="chip chip-muted">实盘 未录入</text>
              <template v-else>
                <text class="chip">实盘 {{ st.countedQty ?? '—' }}</text>
                <text class="chip" :class="(st.diffQty ?? 0) === 0 ? 'chip-ok' : 'chip-warn'">
                  差异 {{ st.diffQty ?? 0 }}
                </text>
              </template>
            </view>
            <view class="st-foot">
              <text class="st-time">{{ fmtTime(st.createdAt) }}</text>
              <text v-if="(st.diffLineCount ?? 0) > 0" class="st-diff-lines"
                >{{ st.diffLineCount }} 行有差异</text
              >
            </view>
          </view>

          <!-- 2026-10-23 审计残留：原先只取 page0/size20，>20 条静默截断；补加载更多。 -->
          <text v-if="listHasMore" role="button" class="load-more" @click="loadMoreStocktakes">
            {{
              listLoadingMore ? '加载中…' : `加载更多（已显示 ${stocktakes.length}/${listTotal}）`
            }}
          </text>
        </template>
      </template>

      <!-- ── 建单 ── -->
      <template v-else-if="mode === 'create'">
        <app-button
          variant="text"
          size="sm"
          :block="false"
          label="返回列表"
          @click="mode = 'list'"
        />
        <!-- 一张卡承载全部建单输入：仓、方式、备注，用分隔线分段而不是三张等高卡 -->
        <view class="card create-card">
          <view class="create-head">
            <text class="create-title">新建盘点单</text>
            <text class="create-sub">{{ warehouseGate.nameText }}</text>
          </view>

          <view class="field">
            <text class="field-label">盘点方式</text>
            <view class="seg">
              <view
                role="button"
                class="seg-item"
                :class="{ on: createMode === 'OPEN' }"
                @click="createMode = 'OPEN'"
              >
                明盘
              </view>
              <view
                role="button"
                class="seg-item"
                :class="{ on: createMode === 'BLIND' }"
                @click="createMode = 'BLIND'"
              >
                盲盘
              </view>
            </view>
            <text class="field-hint">
              {{
                createMode === 'OPEN'
                  ? '明盘：录入时能看到账面数，边对边盘'
                  : '盲盘：不显示账面数，盘完再比对'
              }}
            </text>
          </view>

          <view class="field">
            <text class="field-label">备注（可选）</text>
            <input
              v-model="createNotes"
              class="input"
              placeholder="如：月末例行盘点"
              maxlength="80"
            />
          </view>

          <text class="field-hint">按库存批次逐批盘点；有货批次才会生成盘点行。</text>
        </view>
      </template>

      <!-- ── 详情 / 录入 ── -->
      <template v-else>
        <app-button variant="text" size="sm" :block="false" label="返回列表" @click="backToList" />
        <view v-if="detailError" class="card">
          <error-state :message="detailError" retry-label="重试" @retry="loadDetail" />
        </view>
        <template v-else-if="detail">
          <!-- ① 单据身份：单号+状态，账面/实盘/录入进度一行看完 -->
          <view class="card">
            <view class="st-top">
              <text class="st-no">{{ detail.stocktakeNo }}</text>
              <text class="badge" :class="statusBadgeClass(detail.status)">{{
                stocktakeStatusLabel(detail.status)
              }}</text>
            </view>
            <view class="st-facts">
              <text class="chip">{{ stocktakeModeLabel(detail.mode) }}</text>
              <text class="chip">账面 {{ detail.bookQty }}</text>
              <text class="chip">实盘 {{ detail.countedQty ?? '—' }}</text>
              <text class="chip" :class="diffChipWarn ? 'chip-warn' : 'chip-ok'">
                {{ diffChipLabel }}
              </text>
            </view>
            <view class="progress">
              <view class="progress-bar">
                <view class="progress-fill" :style="{ width: `${enteredPct}%` }" />
              </view>
              <text class="progress-txt">已录入 {{ enteredCount }}/{{ editRows.length }}</text>
            </view>
            <text
              v-if="detail.status === 'COMPLETED' && (detail.diffLineCount ?? 0) > 0"
              class="hint"
            >
              已完成。商户端无过账入口：差异落库存请联系运营在后台「完成并过账」
            </text>
          </view>

          <!-- ② 明细：一行一 SKU，「账面 → 实盘 → 方向」摆在一起，原因只在真有差异时出现 -->
          <view class="card">
            <view class="list-head">
              <text class="section">盘点明细</text>
              <text class="list-stat">{{ editRows.length }} 行</text>
            </view>
            <view v-if="!editRows.length" class="empty-inline">该仓暂无有货批次，无需盘点</view>
            <view v-for="row in editRows" :key="row.lineId" class="line">
              <view class="line-top">
                <text class="line-name">{{ row.skuName }}</text>
                <text class="badge line-badge" :class="rowBadgeClass(row)">{{
                  rowBadgeText(row)
                }}</text>
              </view>
              <text class="line-meta">
                {{ row.skuId }} · 批次 {{ row.batchNo || '—' }} · 账面
                {{ detail.mode === 'BLIND' && !isDone ? '隐' : row.bookQty }}
              </text>
              <view class="line-entry">
                <input
                  v-model="row.countedInput"
                  class="line-input"
                  type="number"
                  :disabled="!rowEditable(row)"
                  placeholder="实盘数"
                />
                <!-- 原因只在真有差异时可填：一致的行走不到这一步，少一个控件少一分误填 -->
                <picker
                  v-if="showReasonRow(row)"
                  :range="reasonLabels(row)"
                  :value="reasonIndex(row)"
                  @change="onReasonPick(row, $event)"
                >
                  <view class="line-reason">{{ reasonLabel(row) }}</view>
                </picker>
                <view
                  v-if="rowEditable(row)"
                  role="button"
                  class="line-save"
                  :class="{ saving: row.saving }"
                  @click="saveLine(row)"
                  >保存</view
                >
                <text v-else class="line-locked">{{ stocktakeLineStatusLabel(row.status) }}</text>
              </view>
              <input
                v-if="showNotesEditor(row)"
                v-model="row.notesInput"
                class="line-notes"
                placeholder="备注（可选，如：破损 2 件）"
                maxlength="80"
              />
              <text v-else-if="row.notesInput" class="line-note-text"
                >备注：{{ row.notesInput }}</text
              >
            </view>
          </view>

          <!-- ③ 凭证：空格子提示还差几张，点图可放大核对 -->
          <view class="card">
            <view class="list-head">
              <text class="section">现场照片</text>
              <text class="list-stat">{{ photos.length }}/5</text>
            </view>
            <view class="photo-grid">
              <image
                v-for="p in photos"
                :key="p.fileId"
                class="photo"
                :src="p.localPath"
                mode="aspectFill"
                role="button"
                aria-label="查看大图"
                @click="previewPhoto(p)"
              />
              <view
                v-if="!isDone && photos.length < 5"
                role="button"
                class="photo-add"
                aria-label="拍照或从相册选图"
                @click="choosePhoto"
              >
                <text class="photo-add-txt">＋</text>
              </view>
            </view>
            <text v-if="!photos.length" class="empty-inline">暂无照片</text>
          </view>

          <view class="tail">
            <app-button
              v-if="detail.status === 'DRAFT'"
              variant="text"
              label="取消盘点"
              @click="doCancel"
            />
          </view>
        </template>
      </template>
    </view>

    <!-- 吸底操作：列表=发起盘点，建单=创建，详情=仅完成/完成并过账 -->
    <view v-if="showDock" class="dock">
      <template v-if="mode === 'list'">
        <view class="dock-stat"
          ><text class="dock-label">{{ dockSummary }}</text></view
        >
        <view class="dock-main">
          <app-button
            :disabled="!canRequest || !warehouseGate.enabled"
            label="发起盘点"
            @click="mode = 'create'"
          />
        </view>
      </template>
      <template v-else-if="mode === 'create'">
        <view class="dock-stat"
          ><text class="dock-label"
            >{{ createMode === 'OPEN' ? '明盘' : '盲盘' }} · 按批次</text
          ></view
        >
        <view class="dock-main">
          <app-button :loading="creating" label="创建盘点单" @click="submitCreate" />
        </view>
      </template>
      <template v-else>
        <view class="dock-side">
          <app-button :loading="acting" variant="outline" label="仅完成" @click="doComplete" />
        </view>
        <view class="dock-main">
          <app-button :loading="acting" label="完成并过账" @click="doCompleteAndAdjust" />
        </view>
      </template>
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
  diffReasonLabel,
  diffReasonOptionsFor,
  isLineEditable,
  lineDiff,
  resolveWarehouseGate,
  stocktakeLineStatusLabel,
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

const { me, meFromServer, refresh: refreshMe } = useMerchantMe();
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));

const mode = ref<'list' | 'create' | 'detail'>('list');
const warehouseName = ref('');
const warehouseError = ref('');
const warehouseGate = computed(() =>
  resolveWarehouseGate(warehouseName.value, warehouseError.value)
);

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
// R3-B-R3-5/R4-A24：载入时留底稿 —— 过账前只回写「与底稿不同」的行；
// 全量回写会把他人（admin）并发保存的实盘盖回本端打开详情时的旧值。
let rowBaseline = new Map<number, string>();

function rowDraftKey(row: EditRow): string {
  return `${row.countedInput}|${row.notesInput}|${row.diffReason}`;
}

const isDone = computed(() => {
  const s = detail.value?.status;
  return s === 'COMPLETED' || s === 'ADJUSTED' || s === 'CANCELLED';
});

/** 盲盘录入中：不显示盘盈/盘亏方向、原因选项给全量（不泄露账面方向）。 */
const isBlindActive = computed(() => detail.value?.mode === 'BLIND' && !isDone.value);

const tabLabel = computed(
  () => statusTabs.find((t) => t.value === statusTab.value)?.label || '全部'
);

/** 吸底条左侧：能盘就报当前筛选下的单据数，不能盘就说清为什么（按钮禁用不能没有理由）。 */
const dockSummary = computed(() => {
  if (!canRequest.value) return '当前账号无盘点权限';
  if (warehouseGate.value.state === 'error') return '未绑定分仓，暂时无法盘点';
  if (warehouseGate.value.state === 'loading') return '正在确认盘点仓…';
  return `${tabLabel.value} · ${listTotal.value} 单`;
});

/** 三种模式各有自己的主操作；详情在终态（已过账/已取消/已完成）不再给操作条。 */
const showDock = computed(() => {
  if (mode.value === 'list' || mode.value === 'create') return true;
  return !!detail.value && !isDone.value;
});

const enteredCount = computed(() => editRows.value.filter((r) => !isRowBlank(r)).length);
const diffRowCount = computed(() => editRows.value.filter((r) => hasDiff(r)).length);
// R3-B-R3-1：盲盘进行中的头部统计不得读 hasDiff（方向即答案）—— 改「已录入」口径
const diffChipLabel = computed(() =>
  isBlindActive.value
    ? `已录入 ${enteredCount.value}/${editRows.value.length} 行`
    : `差异 ${diffRowCount.value} 行`
);
const diffChipWarn = computed(() =>
  isBlindActive.value ? enteredCount.value < editRows.value.length : diffRowCount.value > 0
);
const enteredPct = computed(() => {
  const total = editRows.value.length;
  if (!total) return 0;
  return Math.round((enteredCount.value / total) * 100);
});

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const id = typeof opts?.stocktakeId === 'string' ? Number(opts.stocktakeId) : 0;
  void bootstrap(id);
});

onPullDownRefresh(() => {
  // 分仓未解析成功时，下拉即重试解析（否则下拉成了无反馈的空动作）
  const reload =
    mode.value === 'detail'
      ? loadDetail()
      : mode.value === 'list'
        ? warehouseGate.value.enabled
          ? loadList()
          : retryWarehouse()
        : Promise.resolve();
  void reload.finally(() => uni.stopPullDownRefresh());
});

async function bootstrap(preferDetailId = 0) {
  try {
    await refreshMe();
  } catch {
    if (!isMerchantLoggedIn()) return;
    seedMerchantMeDisplayCache(me);
  }
  // R3-B-R3-4/R4-B-新3：弱网（服务端 me 不可知）≠「无盘点权限」——报网络错误可重试
  if (!meFromServer.value) {
    listError.value = '网络异常，暂时无法确认权限，请重试';
    return;
  }
  if (!(await resolveWarehouse())) return;
  if (preferDetailId > 0) {
    detailId.value = preferDetailId;
    mode.value = 'detail';
    await loadDetail();
    return;
  }
  await loadList();
}

/**
 * 解析本人负责的分仓。失败时把提示留在 warehouseError（页面上常驻错误态 + 重试），
 * 不再用一次性 toast 掩盖——2026-10-28 修复：此前 return 后页面永久停在「加载中…」。
 */
async function resolveWarehouse(): Promise<boolean> {
  try {
    const wh = await merchantApi.satelliteWarehouse();
    warehouseName.value = wh.warehouseName || wh.warehouseId || '';
    warehouseError.value = '';
    return Boolean(warehouseName.value);
  } catch (e) {
    warehouseName.value = '';
    warehouseError.value = e instanceof Error ? e.message : '未找到您负责的分仓，无法盘点';
    return false;
  }
}

async function retryWarehouse() {
  if (await resolveWarehouse()) await loadList();
}

function onStatusTab(v: string) {
  statusTab.value = v;
  void loadList();
}

async function loadList() {
  // 分仓未解析成功时不发列表请求：后端必然 400，还会把错误叠加到错误态上
  if (!warehouseGate.value.enabled) return;
  // A 类模式：入口无条件复位在途 loadMore + 递增序号作废迟到旧页（对齐 tally-records）
  // R2-B5：序号必须**捕获**并在赋值/finally 处判 —— 原实现只递增不判，快速切状态页签时
  // 慢返回的旧页签结果会覆盖新页签（loadMore 有守卫，首页反而没有）。
  const seq = ++listSeq;
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
    if (seq !== listSeq) return;
    stocktakes.value = page?.items || [];
    listTotal.value = page?.total ?? stocktakes.value.length;
    listPage.value = 0;
    listHasMore.value = stocktakes.value.length < listTotal.value;
  } catch (e) {
    if (seq !== listSeq) return;
    listError.value = e instanceof Error ? e.message : '盘点单加载失败';
  } finally {
    if (seq === listSeq) listLoading.value = false;
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
    showSuccess(`盘点单 ${st.stocktakeNo} 已创建`);
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
  // R4-B-新6：换单瞬间先清旧明细/照片 —— seq 只防「过期写入」，不防展示窗口，
  // 不清的话弱网下 B 单头部下先闪现 A 单的行与照片。
  detail.value = null;
  editRows.value = [];
  photos.value = [];
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
    rowBaseline = new Map(editRows.value.map((r) => [r.lineId, rowDraftKey(r)]));
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

/** 复读共享判据：空输入 = 未录入（null），不是「实盘 0」 */
function rowDiff(row: EditRow): number | null {
  return lineDiff(row.countedInput, row.bookQty);
}

function isRowBlank(row: EditRow): boolean {
  return String(row.countedInput ?? '').trim() === '';
}

function hasDiff(row: EditRow): boolean {
  const d = rowDiff(row);
  return d != null && d !== 0;
}

/**
 * 行徽章：盲盘进行中只说「录没录」（方向即答案，不能泄露账面）；
 * 明盘给「盘亏/盘盈 + 数量」。未录入的单说「未录入」——旧实现把空输入算成 0，
 * 空行会显示成「盘亏 = 整批账面」。
 */
function rowBadgeText(row: EditRow): string {
  if (isBlindActive.value) return isRowBlank(row) ? '未录入' : '已录入';
  const d = rowDiff(row);
  if (d == null) return '未录入';
  if (d === 0) return '相符';
  return `${d < 0 ? '盘亏' : '盘盈'} ${Math.abs(d)}`;
}

function rowBadgeClass(row: EditRow): string {
  if (isBlindActive.value) return isRowBlank(row) ? 'is-pending' : 'is-ok';
  const d = rowDiff(row);
  if (d == null) return 'is-pending';
  if (d === 0) return 'is-ok';
  return 'is-warn';
}

/** 单据状态 → 徽章配色：草稿待办(橙)、盘点中(品牌)、已完成(绿)、已调整(蓝)、已取消(灰) */
function statusBadgeClass(status: string | null | undefined): string {
  switch (status) {
    case 'DRAFT':
      return 'is-warn';
    case 'IN_PROGRESS':
      return 'is-live';
    case 'COMPLETED':
      return 'is-ok';
    case 'ADJUSTED':
      return 'is-done';
    default:
      return 'is-pending';
  }
}

/**
 * R3-B-R3-1：原因/备注的显隐不得在盲盘进行中读 hasDiff（差异方向即账面答案）。
 * 盲盘口径 = 已录入的行给原因/备注（reasonOptions 盲盘时已是全量选项、不泄露方向）。
 */
function showReasonRow(row: EditRow): boolean {
  if (isBlindActive.value) return rowEditable(row) && !isRowBlank(row);
  return rowEditable(row) && hasDiff(row);
}

/** 备注只在真有差异时需要：一致的行走不到备注，行高省一半 */
function showNotesEditor(row: EditRow): boolean {
  return showReasonRow(row);
}

/**
 * R3-B-R3-5：过账前把「与底稿不同」的实盘先落库 —— 否则按上次保存值调库存（静默错账）。
 * 只 PUT 改动行（幂等），不回写他人并发保存的行。
 */
async function persistUnsavedLines(): Promise<boolean> {
  const changed = editRows.value.filter(
    (r) => !isRowBlank(r) && rowBaseline.get(r.lineId) !== rowDraftKey(r)
  );
  for (const row of changed) {
    const counted = isRowBlank(row) ? null : Number(row.countedInput); // R3-B-R3-6
    const built = buildUpdateLineBody(counted, row.notesInput, row.diffReason);
    if (!built.ok) {
      showError(built.error);
      return false;
    }
    try {
      const updated = await merchantApi.warehouseStocktakeUpdateLine(
        detailId.value,
        row.lineId,
        built.body
      );
      row.status = updated.status || 'PENDING';
      rowBaseline.set(row.lineId, rowDraftKey(row));
    } catch (e) {
      showError(e instanceof Error ? e.message : '保存实盘失败，请先处理后再过账');
      return false;
    }
  }
  return true;
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
  const counted = isRowBlank(row) ? null : Number(row.countedInput); // R3-B-R3-6：' ' 也是未录入
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

function previewPhoto(p: PhotoView) {
  if (!p.localPath) return;
  uni.previewImage({ urls: photos.value.map((x) => x.localPath), current: p.localPath });
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
        if (!(await persistUnsavedLines())) return;
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
        if (!(await persistUnsavedLines())) return;
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
</script>

<style scoped src="./warehouse-stocktake.page.css"></style>
