<script setup lang="ts">
/**
 * 柜机盘点（开门柜）：扫码/选择柜机 → 盘点开门（先扫码选中柜机=人在柜边再开，关门即完成
 * 不结算）→ 逐商品实盘覆盖式提交（台账→实盘差值自动加/减，落批次台账）。
 * 非将邑柜不支持在线盘点（货道实盘链路已随「开门柜去掉货道」退场）。
 * 凭证照片按商户 ops-policy 强制（共用 stocktake-evidence 上传），可选附带定位（防伪）。
 */
import { onLoad } from '@dcloudio/uni-app';
import { computed, ref } from 'vue';
import { showError, showSuccess } from '@/utils/notify';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { absoluteImageUrl } from '@/composables/useReplenishmentDisplay';
import { scanCabinetDeviceId } from '@/utils/scan-cabinet';
import { UI_COPY } from '@aicabinet/shared-uni/ui-copy';
import { assertLocalImageSize } from '@aicabinet/shared-uni/upload-limits';
import AppNavBar from '@aicabinet/shared-uni/components/app-nav-bar.vue';

interface StocktakeGoodsRow {
  skuId: string;
  skuName?: string | null;
  quantity?: number | null;
  imageUrl?: string | null;
}

interface DoorStocktakeLineResult {
  skuId: string;
  quantityBefore: number;
  quantityAfter: number;
  delta: number;
}

const { me } = useMerchantMe();
const deviceId = ref('');
const deviceName = ref('');
const loading = ref(false);
const opening = ref(false);

// ---- 开门柜（商品实盘，2026-10-28） ----
const isDoorCabinet = ref(false);
const goods = ref<StocktakeGoodsRow[]>([]);
const goodsInputs = ref<Record<string, string>>({});
const submittingDoor = ref(false);
const doorResult = ref<DoorStocktakeLineResult[] | null>(null);

const evidenceUrl = ref('');
const uploadingEvidence = ref(false);

const canStocktake = computed(() => hasPerm(me.value, 'merchant:slots:edit'));

onLoad((opts) => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  const id = opts?.deviceId ? decodeURIComponent(String(opts.deviceId)) : '';
  if (id) {
    deviceId.value = id;
    void loadDevice();
  }
});

// AU-138：弃用页内正则（EG 改版前旧口径，剥前缀+贪婪抓数字段），改走 shared-uni 权威解析
async function scanDevice() {
  const id = await scanCabinetDeviceId();
  if (!id) {
    return; // 失败/取消/无效编号已在封装内提示或降级手输
  }
  deviceId.value = id;
  // 门禁式：扫码成功即开门（仅开门柜；开门不结算无资金影响，全程留痕）
  void loadDevice(true);
}

async function loadDevice(autoOpen = false) {
  if (!deviceId.value) return;
  loading.value = true;
  doorResult.value = null;
  try {
    // 判柜型：将邑绑定柜=开门柜（与柜机详情页同判据 jiangyiBound）
    let bound = false;
    try {
      const st = await merchantApi.jiangyiGatherStatus(deviceId.value);
      bound = !!st?.bound;
    } catch {
      bound = false; // 无 detail 权限/非将邑：按弹簧柜流程走
    }
    isDoorCabinet.value = bound;
    if (!bound) {
      // 货道实盘随「开门柜去掉货道」退场：非将邑柜没有可用的盘点链路
      goods.value = [];
      showError('该柜不支持在线盘点：仅将邑开门柜支持按商品盘点');
      return;
    }
    const detail = await merchantApi.deviceDetail(deviceId.value);
    deviceName.value = detail.device?.deviceName || deviceId.value;
    const rows: StocktakeGoodsRow[] = (detail.skuInventory ?? [])
      .filter((g) => !!g.skuId)
      .map((g) => ({
        skuId: String(g.skuId),
        skuName: g.skuName,
        quantity: g.quantity,
        // 图片地址必须绝对化，否则小程序端加载不到、只渲染空白方块（与补货页同源 helper）
        imageUrl: absoluteImageUrl(g.imageUrl) || null
      }));
    goods.value = rows;
    // 实盘预填台账数：覆盖式语义，没动到的行=无差异，提交时自动过滤
    const inputs: Record<string, string> = {};
    rows.forEach((g) => {
      inputs[g.skuId] = String(g.quantity ?? 0);
    });
    goodsInputs.value = inputs;
    if (!rows.length) {
      showError('该柜台账暂无在架商品');
    } else if (autoOpen) {
      void executeOpenDoor();
    }
  } catch (e) {
    showError(e instanceof Error ? e.message : '加载柜机信息失败');
  } finally {
    loading.value = false;
  }
}

// ---- 盘点开门（仅开门柜） ----
/** 门禁式：扫码触发，直接开门不弹确认（开门不结算无资金影响，全程留痕）。 */
async function executeOpenDoor() {
  if (opening.value) return;
  opening.value = true;
  try {
    await merchantApi.openStocktakeDoor(deviceId.value);
    uni.showToast({ title: '门已开，请清点商品', icon: 'none' });
  } catch (e) {
    showError(e instanceof Error ? e.message : '盘点开门失败');
  } finally {
    opening.value = false;
  }
}

/** 手动开门按钮：保留确认弹窗防误开。 */
function openDoor() {
  if (opening.value) return;
  uni.showModal({
    title: '盘点开门',
    content: '将远程打开柜门进行盘点，请确认已在柜机旁。开门与关门全程留痕。继续？',
    success: (res) => {
      if (res.confirm) void executeOpenDoor();
    }
  });
}

// ---- 凭证照（两分支共用） ----
function chooseEvidence() {
  if (uploadingEvidence.value) return; // 在途门闩：连点拍照只发一次（第四轮审计，对齐补货证据口径）
  uni.chooseImage({
    count: 1,
    sourceType: ['camera'],
    success: (res) => {
      const path = res.tempFilePaths?.[0];
      if (path) void uploadEvidence(path);
    }
  });
}

async function uploadEvidence(path: string) {
  if (uploadingEvidence.value) return;
  uploadingEvidence.value = true;
  try {
    // 5MB 预检（第四轮审计）：与补货证据同口径，避免大图直撞后端报原始错误
    await assertLocalImageSize(path);
    const att = await merchantApi.uploadDeviceStocktakeEvidenceFile(deviceId.value, path);
    evidenceUrl.value = att.url || '';
    showSuccess('凭证已上传');
  } catch (e) {
    showError(e instanceof Error ? e.message : '凭证上传失败');
  } finally {
    uploadingEvidence.value = false;
  }
}

function fetchLocation(): Promise<{ latitude: number; longitude: number } | null> {
  return new Promise((resolve) => {
    uni.getLocation({
      type: 'gcj02',
      success: (res) => resolve({ latitude: res.latitude, longitude: res.longitude }),
      fail: () => resolve(null)
    });
  });
}

// ---- 开门柜：批量提交实盘 ----
async function submitDoorStocktake() {
  if (submittingDoor.value) return;
  /**
   * 🔴 2026-11-10 审计 P1-2'：输入框被清空时 `Number('') === 0`，原实现把它当成
   * 「实盘 0 且有差异」提交；后端 `InventoryLotService.stocktakeAdjust` 是覆盖式绝对值语义
   * （`delta = counted - current`），等于把该 SKU 库存直接清零。
   * 现按「未填写 ≠ 填 0」处理：空值行跳过；非整数/负数阻断并指名到行。
   */
  const invalid: string[] = [];
  const changed: { skuId: string; countedQuantity: number }[] = [];
  let blankRows = 0;
  for (const g of goods.value) {
    const raw = String(goodsInputs.value[g.skuId] ?? '').trim();
    if (raw === '') {
      blankRows += 1;
      continue;
    }
    const n = Number(raw);
    if (!Number.isInteger(n) || n < 0) {
      invalid.push(`${g.skuName || g.skuId}「${raw}」`);
      continue;
    }
    const book = g.quantity ?? 0;
    if (n !== book) changed.push({ skuId: g.skuId, countedQuantity: n });
  }
  if (invalid.length) {
    showError(
      `实盘数量须为不小于 0 的整数：${invalid.slice(0, 3).join('、')}${
        invalid.length > 3 ? ` 等 ${invalid.length} 行` : ''
      }`
    );
    return;
  }
  if (!changed.length) {
    showError(
      blankRows
        ? `有 ${blankRows} 行未填写实盘数量（未填写不会按 0 处理），其余与台账一致，无需调账`
        : '实盘与台账全部一致，无需调账'
    );
    return;
  }
  const diffText = changed
    .map((l) => {
      const book = goods.value.find((g) => g.skuId === l.skuId)?.quantity ?? 0;
      const d = l.countedQuantity - book;
      return `${d > 0 ? '+' : ''}${d}`;
    })
    .join('、');
  const blankHint = blankRows ? `；另有 ${blankRows} 行未填写，本次不调账` : '';
  uni.showModal({
    title: `提交盘点（${changed.length} 行）`,
    content: `系统将按实盘调账，差值：${diffText}${blankHint}${
      evidenceUrl.value ? '（含凭证）' : ''
    }。确认提交？`,
    success: (res) => {
      if (res.confirm) void doSubmitDoor(changed);
    }
  });
}

async function doSubmitDoor(lines: { skuId: string; countedQuantity: number }[]) {
  submittingDoor.value = true;
  try {
    const location = await fetchLocation();
    const result = await merchantApi.submitDoorStocktake(deviceId.value, {
      lines,
      photoEvidenceUrl: evidenceUrl.value || undefined,
      latitude: location?.latitude,
      longitude: location?.longitude
    });
    doorResult.value = (result.lines ?? []).map((r) => ({
      skuId: String(r.skuId ?? ''),
      quantityBefore: Number(r.quantityBefore ?? 0),
      quantityAfter: Number(r.quantityAfter ?? 0),
      delta: Number(r.delta ?? 0)
    }));
    showSuccess('盘点已提交，账面已按实盘调整');
    await loadDevice();
  } catch (e) {
    showError(e instanceof Error ? e.message : '盘点提交失败');
  } finally {
    submittingDoor.value = false;
  }
}
</script>

<template>
  <view>
    <app-nav-bar title="柜机盘点" home-url="/pages/home/home" />
    <view class="page-body">
      <view v-if="!canStocktake" class="card"><text class="err">当前账号无盘点权限</text></view>
      <template v-else>
        <view class="card">
          <text class="section">选择柜机</text>
          <view v-if="!deviceId" class="scan-empty">
            <text class="meta">扫描柜机二维码开始盘点</text>
            <app-button label="扫码识别柜机" @click="scanDevice" />
          </view>
          <view v-else class="scan-done">
            <text class="title">柜机 {{ deviceName || deviceId }}</text>
            <text class="meta">{{
              isDoorCabinet ? '开门柜：已按门禁式开门，清点后按商品提交实盘' : '该柜不支持在线盘点'
            }}</text>
            <app-button
              variant="secondary"
              compact
              :block="false"
              label="重新扫码"
              @click="scanDevice"
            />
          </view>
        </view>

        <template v-if="deviceId">
          <!-- 开门柜：盘点开门 + 逐商品实盘 -->
          <template v-if="isDoorCabinet">
            <view class="card">
              <text class="section">盘点开门</text>
              <view class="door-row">
                <app-button
                  compact
                  :block="false"
                  :loading="opening"
                  label="盘点开门"
                  @click="openDoor"
                />
                <text class="meta">门禁式：扫柜机码即自动开门；此按钮为手动兜底（带确认）</text>
              </view>
            </view>

            <view class="card">
              <view class="row">
                <text class="section">盘点凭证</text>
              </view>
              <view class="evidence-row">
                <app-button
                  variant="secondary"
                  compact
                  :block="false"
                  :label="evidenceUrl ? '已上传（重拍替换）' : '拍现场照'"
                  @click="chooseEvidence"
                />
                <text v-if="evidenceUrl" class="meta">凭证已随本次盘点提交</text>
              </view>
            </view>

            <view class="card">
              <view class="row">
                <text class="section">商品实盘</text>
                <text v-if="loading" class="meta">{{ UI_COPY.loading }}</text>
              </view>
              <view v-for="g in goods" :key="g.skuId" class="goods-row">
                <image
                  v-if="g.imageUrl"
                  :src="g.imageUrl"
                  mode="aspectFill"
                  class="goods-img"
                  @error="g.imageUrl = null"
                />
                <view v-else class="goods-img goods-img-fallback">
                  <text>{{ (g.skuName || g.skuId).slice(0, 1) }}</text>
                </view>
                <view class="goods-main">
                  <text class="goods-name">{{ g.skuName || g.skuId }}</text>
                  <text class="meta">台账 {{ g.quantity ?? 0 }}</text>
                </view>
                <input
                  v-model="goodsInputs[g.skuId]"
                  class="input stock-input"
                  type="number"
                  placeholder="实盘"
                />
              </view>
              <text v-if="!loading && !goods.length" class="meta">暂无在架商品</text>
              <view v-if="goods.length" class="submit-row">
                <app-button
                  :block="false"
                  :loading="submittingDoor"
                  label="提交实盘调账"
                  @click="submitDoorStocktake"
                />
                <text class="meta">只提交与台账有差异的商品；调账批次可追溯</text>
              </view>
              <view v-if="doorResult?.length" class="result-box">
                <text class="section">本次调账结果</text>
                <text v-for="r in doorResult" :key="r.skuId" class="meta">
                  {{ r.skuId }}：{{ r.quantityBefore }} → {{ r.quantityAfter }}（{{
                    r.delta > 0 ? '+' : ''
                  }}{{ r.delta }}）
                </text>
              </view>
            </view>
          </template>

          <view v-else-if="!loading" class="card">
            <text class="err">该柜不支持在线盘点：仅将邑开门柜支持按商品盘点</text>
          </view>
        </template>
      </template>
    </view>
  </view>
</template>

<style scoped>
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
}
.card {
  background: #fff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.section {
  font-size: 30rpx;
  font-weight: 600;
}
.row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.meta {
  font-size: 24rpx;
  color: #909399;
}
.title {
  font-size: 32rpx;
  font-weight: 700;
  display: block;
  margin-bottom: 8rpx;
}
.err {
  color: #d03050;
}
.scan-empty {
  display: flex;
  flex-direction: column;
  gap: 16rpx;
  margin-top: 12rpx;
}
.scan-done {
  margin-top: 12rpx;
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  align-items: flex-start;
}
.evidence-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-top: 12rpx;
}
.door-row {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-top: 12rpx;
  align-items: flex-start;
}
.goods-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.goods-img {
  width: 96rpx;
  height: 96rpx;
  border-radius: 12rpx;
  flex-shrink: 0;
  background: #f5f6f8;
}
.goods-img-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  font-size: 36rpx;
}
.goods-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.goods-name {
  font-weight: 600;
  font-size: 28rpx;
}
.submit-row {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  margin-top: 20rpx;
  align-items: flex-start;
}
.result-box {
  display: flex;
  flex-direction: column;
  gap: 6rpx;
  margin-top: 20rpx;
  padding: 16rpx;
  background: #f7f8fa;
  border-radius: 12rpx;
}
.stock-input {
  width: 120rpx;
  border: 1rpx solid #dcdfe6;
  border-radius: 8rpx;
  padding: 8rpx 12rpx;
  font-size: 26rpx;
}

/* —— 统一到 shared-uni 的组件度量（本页覆盖，勿各页再散写） —— */
.card {
  border-radius: 24rpx;
  padding: 28rpx;
  margin-bottom: 20rpx;
  box-shadow: 0 2rpx 12rpx rgba(15, 23, 42, 0.04);
}
.section {
  font-size: 30rpx;
  font-weight: 600;
  color: var(--color-text-primary, #0f172a);
}
.goods-img,
.goods-img-fallback {
  width: 96rpx;
  height: 96rpx;
  border-radius: 20rpx;
}
.goods-img-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-soft, #f0fdfa);
  color: var(--brand, #0d9488);
  font-size: 40rpx;
  font-weight: 600;
}
.stock-input {
  width: 132rpx;
  height: 76rpx;
  text-align: center;
  font-size: 30rpx;
  font-weight: 600;
  background: #f8fafc;
  border-radius: 16rpx;
}
</style>
