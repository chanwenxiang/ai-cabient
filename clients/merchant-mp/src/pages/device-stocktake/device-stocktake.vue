<script setup lang="ts">
/**
 * 柜机盘点（开门柜）：扫码/选择柜机 → 盘点开门（先扫码选中柜机=人在柜边再开，关门即完成
 * 不结算）→ 逐商品实盘覆盖式提交（台账→实盘差值自动加/减，落批次台账）。
 * 非将邑柜不支持在线盘点（货道实盘链路已随「开门柜去掉货道」退场）。
 * 凭证照片按商户 ops-policy 强制（共用 stocktake-evidence 上传），可选附带定位（防伪）。
 *
 * 2026-11-10 版式重做（用户「这个页面重新设计一下」）：
 *   旧版是四张等高白卡纵排——「选择柜机 / 盘点开门 / 盘点凭证 / 商品实盘」，每张只有一行
 *   控件，右侧大片空白；说明长句与操作按钮同权，视线没有落点。
 *   新版按「现场盘点」的真实动线重排：
 *     ① 柜机身份收成一条（名称+编号+柜型，右侧「重新扫码」），没选柜时整块变成扫码入口；
 *     ② 开门与拍照合并成并排操作块（同一屏一次点完），说明压成一行小字；
 *     ③ 商品行把「台账数」和「差值」摆在数量旁边，步进攻克小数、输入框承接大好；
 *     ④ 提交改成吸底条，长列表不用滚到底部；条上直接显示待调账项数。
 *   业务链路（开门口径/凭证强制/空值不按 0/只提交差异行）一字未改。
 */
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
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
  /** 提交前从商品清单带过来的名称：结果区直接展示品名，不要甩一串 skuId 给商户看 */
  skuName?: string;
  quantityBefore: number;
  quantityAfter: number;
  delta: number;
}

const { me, meFromServer } = useMerchantMe();
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

/**
 * 单行差值：null = 未填写或非法（不参与调账，也绝不按 0 处理）。
 * 与 submitDoorStocktake 的过滤口径必须一致，否则吸底条上的「待调 N 项」会骗人。
 */
function diffOf(g: StocktakeGoodsRow): number | null {
  const raw = String(goodsInputs.value[g.skuId] ?? '').trim();
  if (raw === '') return null;
  const n = Number(raw);
  if (!Number.isInteger(n) || n < 0) return null;
  return n - (g.quantity ?? 0);
}

/** 行内徽章直接读这张表：模板里不用反复调函数、也不用写 as 断言 */
const diffMap = computed<Record<string, number | null>>(() => {
  const m: Record<string, number | null> = {};
  for (const g of goods.value) m[g.skuId] = diffOf(g);
  return m;
});

/** 真正「没填」= 输入框为空；非法值不算没填，交给提交时的校验去点名 */
function isBlank(g: StocktakeGoodsRow): boolean {
  return String(goodsInputs.value[g.skuId] ?? '').trim() === '';
}

/** 吸底条：待处理行数 = 会动账的行 + 非法非空输入行（R3-B-R3-3：非法值也是「待处理」，
 *  否则吸底条禁用+「与台账一致」把非法输入说成没差异，提交校验永不可达） */
const pendingCount = computed(
  () =>
    goods.value.filter((g) => {
      const d = diffMap.value[g.skuId];
      if (d != null && d !== 0) return true;
      const raw = String(goodsInputs.value[g.skuId] ?? '').trim();
      return raw !== '' && d == null;
    }).length
);

const showDock = computed(() => !!deviceId.value && isDoorCabinet.value && goods.value.length > 0);

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

/** pages.json 开了 enablePullDownRefresh，没有 handler 时下拉只会一直转圈（旧版就是）。 */
onPullDownRefresh(() => {
  if (!deviceId.value) {
    uni.stopPullDownRefresh();
    return;
  }
  void loadDevice(false, true).finally(() => uni.stopPullDownRefresh());
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

/**
 * @param autoOpen 扫码进来的：判完柜型直接开门（门禁式）
 * @param keepResult 重载时保住「本次调账结果」——提交成功后要刷新台账，
 *   旧实现无条件清空 doorResult，结果框永远来不及显示（死代码）。
 */
async function loadDevice(autoOpen = false, keepResult = false) {
  if (!deviceId.value) return;
  loading.value = true;
  if (!keepResult) doorResult.value = null;
  // R2-B4：凭证是「本次盘点」的证据，换柜/重盘必须清 —— 全仓只有上传成功一处赋值，
  // 否则上一柜（或上一次盘点）的照片会静默满足「必须拍照」（后端只判 URL 非空）。
  evidenceUrl.value = '';
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

// ---- 实盘步进 ----
/**
 * 点 −/+ 视为「已给出一个明确实盘数」：被清空的行从 0 起算，
 * 而不是悄悄回填台账数（那样会显示「一致」却和用户看到的不符）。
 */
function adjustQty(g: StocktakeGoodsRow, delta: number) {
  const raw = String(goodsInputs.value[g.skuId] ?? '').trim();
  const cur = raw === '' ? 0 : Number(raw);
  const base = Number.isFinite(cur) ? Math.trunc(cur) : 0;
  goodsInputs.value[g.skuId] = String(Math.max(0, base + delta));
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
  // 先抓品名：刷新台账后 goods 会被替换，结果区还要按品类展示 before→after
  const nameOf = new Map(goods.value.map((g) => [g.skuId, g.skuName || g.skuId]));
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
      skuName: nameOf.get(String(r.skuId ?? '')) || String(r.skuId ?? ''),
      quantityBefore: Number(r.quantityBefore ?? 0),
      quantityAfter: Number(r.quantityAfter ?? 0),
      delta: Number(r.delta ?? 0)
    }));
    showSuccess('盘点已提交，账面已按实盘调整');
    // keepResult=true：否则这里把刚拿到的调账结果清空，商户永远看不到本次改了哪几项
    await loadDevice(false, true);
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
    <view class="page-body" :class="{ 'page-body-dock': showDock }">
      <view v-if="!canStocktake" class="card"
        ><text class="err">{{
          meFromServer ? '当前账号无盘点权限' : '网络异常，暂时无法确认权限，请重试'
        }}</text></view
      >
      <template v-else>
        <!-- ① 柜机身份：已选柜只占一条；未选柜就是整块扫码入口 -->
        <view class="card">
          <view v-if="!deviceId" class="scan-entry">
            <view class="scan-mark" aria-hidden="true"><text class="scan-mark-txt">扫</text></view>
            <text class="scan-title">扫描柜机二维码开始盘点</text>
            <text class="scan-hint">扫码后自动开门；盘点不涉及结算，开门与关门全程留痕</text>
            <app-button label="扫码识别柜机" @click="scanDevice" />
          </view>
          <view v-else class="cabinet-head">
            <image
              class="cabinet-thumb"
              src="/static/device-default.png"
              mode="aspectFill"
              aria-hidden="true"
            />
            <view class="cabinet-id">
              <!-- 名称与「重新扫码」同一行，编号/柜型独占整卡宽度：
                   否则右侧按钮会把状态文字挤成「…按商品 / 盘点」这种断字换行 -->
              <view class="cabinet-name-row">
                <text class="cabinet-name">{{ deviceName || deviceId }}</text>
                <app-button
                  variant="text"
                  size="sm"
                  :block="false"
                  label="重新扫码"
                  @click="scanDevice"
                />
              </view>
              <!-- 无名称时 deviceName 已兜底成编号，再单列一行编号就成了同一串重复两遍 -->
              <text v-if="deviceName && deviceName !== deviceId" class="cabinet-code">{{
                deviceId
              }}</text>
              <text class="cabinet-state" :class="isDoorCabinet ? 'is-ok' : 'is-warn'">
                {{
                  isDoorCabinet
                    ? '开门柜 · 按商品盘点'
                    : '不支持在线盘点 · 仅将邑开门柜可按商品盘点'
                }}
              </text>
            </view>
          </view>
        </view>

        <template v-if="deviceId && isDoorCabinet">
          <!-- ② 现场两件事：开门 + 留证，并排一次点完 -->
          <view class="op-grid">
            <view class="op-tile">
              <text class="op-label">盘点开门</text>
              <app-button :loading="opening" label="开门" @click="openDoor" />
            </view>
            <view class="op-tile" :class="{ 'is-done': !!evidenceUrl }">
              <text class="op-label">盘点凭证</text>
              <app-button
                variant="outline"
                :loading="uploadingEvidence"
                :label="evidenceUrl ? '已拍·重拍' : '拍现场照'"
                @click="chooseEvidence"
              />
            </view>
          </view>
          <text class="op-hint"
            >扫柜机码即自动开门，此按钮为手动兜底（带确认）；凭证需拍到柜内商品。</text
          >

          <!-- ③ 实盘清单：台账与差值就在数量旁边 -->
          <view class="card">
            <view class="list-head">
              <text class="section">商品实盘</text>
              <text class="list-stat">{{ goods.length }} 项 · 待调 {{ pendingCount }}</text>
            </view>
            <text v-if="loading" class="meta">{{ UI_COPY.loading }}</text>

            <!-- 本次调账结果：提交后刷新台账也要保住这块（此前被 loadDevice 清空成死代码） -->
            <view v-if="doorResult?.length" class="result-box">
              <view class="result-head">
                <text class="result-title">本次调账已完成</text>
                <text class="result-count">{{ doorResult.length }} 项</text>
              </view>
              <view v-for="r in doorResult" :key="r.skuId" class="result-row">
                <text class="result-name">{{ r.skuName || r.skuId }}</text>
                <text class="result-flow">{{ r.quantityBefore }} → {{ r.quantityAfter }}</text>
                <text class="result-delta" :class="r.delta > 0 ? 'is-up' : 'is-down'">
                  {{ r.delta > 0 ? '+' : '' }}{{ r.delta }}
                </text>
              </view>
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
                <view class="goods-facts">
                  <text class="book">台账 {{ g.quantity ?? 0 }}</text>
                  <text v-if="isBlank(g)" class="delta is-blank">未填</text>
                  <text v-else-if="diffMap[g.skuId] === 0" class="delta is-same">一致</text>
                  <text
                    v-else-if="diffMap[g.skuId] != null"
                    class="delta"
                    :class="(diffMap[g.skuId] ?? 0) > 0 ? 'is-up' : 'is-down'"
                  >
                    差 {{ (diffMap[g.skuId] ?? 0) > 0 ? '+' : '' }}{{ diffMap[g.skuId] }}
                  </text>
                </view>
              </view>
              <view class="stepper">
                <text
                  class="step-btn"
                  role="button"
                  aria-label="减少实盘数量"
                  hover-class="step-btn-hover"
                  @click="adjustQty(g, -1)"
                  >−</text
                >
                <input
                  v-model="goodsInputs[g.skuId]"
                  class="step-input"
                  type="number"
                  placeholder="—"
                />
                <text
                  class="step-btn"
                  role="button"
                  aria-label="增加实盘数量"
                  hover-class="step-btn-hover"
                  @click="adjustQty(g, 1)"
                  >+</text
                >
              </view>
            </view>
            <text v-if="!loading && !goods.length" class="meta">暂无在架商品</text>
          </view>
        </template>

        <!-- 不支持在线盘点：说明已并入上方柜机条的状态行，这里不再重复一张红字卡 -->
      </template>
    </view>

    <!-- ④ 吸底提交：长清单不用滚到底，条上先告诉你要动几项 -->
    <view v-if="showDock" class="dock">
      <view class="dock-stat">
        <text v-if="pendingCount" class="dock-num">{{ pendingCount }}</text>
        <text class="dock-label">{{ pendingCount ? ' 项待调账' : '与台账一致' }}</text>
      </view>
      <app-button
        class="dock-btn"
        :block="false"
        :disabled="!pendingCount"
        :loading="submittingDoor"
        label="提交实盘调账"
        @click="submitDoorStocktake"
      />
    </view>
  </view>
</template>

<style scoped>
.page-body {
  padding: 24rpx var(--page-gutter, 24rpx) calc(48rpx + env(safe-area-inset-bottom));
}
/* 吸底条占位：否则最后一行商品被压在提交条下面 */
.page-body-dock {
  padding-bottom: calc(200rpx + env(safe-area-inset-bottom));
}
.meta {
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
}
.err {
  color: var(--danger, #b91c1c);
  font-size: var(--font-size-md, 28rpx);
}

/* —— ① 扫码入口 / 柜机身份 —— */
.scan-entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 12rpx;
  padding: 16rpx 0 8rpx;
}
.scan-mark {
  width: 104rpx;
  height: 104rpx;
  border-radius: 28rpx;
  /* 纯 --brand-soft 在白色卡片上几乎看不出块形，用更深一档的 mist 才有「入口」的分量 */
  background: var(--brand-mist, #ccfbf1);
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 4rpx;
}
.scan-mark-txt {
  font-size: 44rpx;
  font-weight: 700;
  color: var(--brand, #0d9488);
}
.scan-title {
  font-size: var(--font-size-lg, 32rpx);
  font-weight: 650;
  color: var(--color-text-primary, #0f172a);
}
.scan-hint {
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
  text-align: center;
  line-height: 1.5;
  margin-bottom: 12rpx;
}
.cabinet-head {
  display: flex;
  align-items: center;
  gap: 20rpx;
}
.cabinet-thumb {
  width: 88rpx;
  height: 88rpx;
  border-radius: 20rpx;
  flex-shrink: 0;
  background: var(--surface-muted, #f4f7f5);
}
.cabinet-id {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 4rpx;
}
.cabinet-name-row {
  display: flex;
  align-items: center;
  gap: 8rpx;
}
.cabinet-name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-lg, 32rpx);
  font-weight: 700;
  color: var(--color-text-primary, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.cabinet-code {
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
}
.cabinet-state {
  font-size: var(--font-size-sm, 24rpx);
  font-weight: 600;
  line-height: 1.45;
}
.cabinet-state.is-ok {
  color: var(--brand, #0d9488);
}
.cabinet-state.is-warn {
  color: var(--warning, #b45309);
}

/* —— ② 开门 / 凭证 并排 —— */
.op-grid {
  display: flex;
  gap: 16rpx;
  margin-bottom: 12rpx;
}
.op-tile {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 12rpx;
  padding: 20rpx;
  border-radius: 24rpx;
  background: var(--card-bg, #fff);
  box-shadow: 0 2rpx 12rpx rgba(15, 23, 42, 0.04);
}
/* 凭证已拍：左侧一道品牌色细边，扫一眼就知道「这步做完了」 */
.op-tile.is-done {
  box-shadow:
    inset 3rpx 0 0 var(--brand, #0d9488),
    0 2rpx 12rpx rgba(15, 23, 42, 0.04);
}
.op-label {
  font-size: var(--font-size-sm, 24rpx);
  font-weight: 650;
  color: var(--color-text-secondary, #64748b);
}
.op-hint {
  display: block;
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
  line-height: 1.5;
  padding: 0 4rpx;
  margin-bottom: 20rpx;
}

/* —— ③ 实盘清单 —— */
.list-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12rpx;
  margin-bottom: 4rpx;
}
.list-stat {
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
}
.goods-row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 18rpx 0;
  border-bottom: 1rpx solid var(--color-border-subtle, #f3f4f6);
}
.goods-row:last-child {
  border-bottom: none;
}
.goods-img {
  width: 80rpx;
  height: 80rpx;
  border-radius: 18rpx;
  flex-shrink: 0;
  background: var(--surface-muted, #f4f7f5);
}
.goods-img-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--brand-soft, #f0fdfa);
  color: var(--brand, #0d9488);
  font-size: 36rpx;
  font-weight: 600;
}
.goods-main {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 6rpx;
}
.goods-name {
  font-size: var(--font-size-md, 28rpx);
  font-weight: 600;
  color: var(--color-text-primary, #0f172a);
  line-height: 1.35;
}
.goods-facts {
  display: flex;
  align-items: center;
  gap: 10rpx;
  flex-wrap: wrap;
}
.book {
  font-size: var(--font-size-xs, 22rpx);
  color: var(--color-text-secondary, #64748b);
}
.delta {
  font-size: var(--font-size-xs, 22rpx);
  font-weight: 700;
  padding: 2rpx 10rpx;
  border-radius: 8rpx;
}
.delta.is-up {
  color: var(--brand, #0d9488);
  background: var(--brand-soft, #f0fdfa);
}
.delta.is-down {
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}
.delta.is-same {
  color: var(--color-text-muted, #94a3b8);
  background: var(--surface-muted, #f4f7f5);
}
.delta.is-blank {
  color: var(--warning, #b45309);
  background: var(--warning-soft, #fff7ed);
}

/* 数量步进：视觉 56rpx，热区靠 ::after 撑到 92rpx（门禁口径 88rpx，同 ReplenishLinesSection） */
.stepper {
  display: flex;
  align-items: center;
  flex-shrink: 0;
  padding: 4rpx;
  border-radius: var(--radius-pill, 999px);
  background: var(--brand-soft, #f0fdfa);
}
.step-btn {
  position: relative;
  width: 56rpx;
  height: 56rpx;
  line-height: 56rpx;
  text-align: center;
  border-radius: 50%;
  background: var(--card-bg, #fff);
  color: var(--brand, #0d9488);
  font-size: var(--font-size-lg, 32rpx);
  font-weight: 700;
}
.step-btn::after {
  content: '';
  position: absolute;
  inset: -18rpx;
}
.step-btn-hover {
  background: var(--brand-mist, #ccfbf1);
}
.step-input {
  width: 88rpx;
  height: 56rpx;
  min-height: 56rpx;
  text-align: center;
  font-size: var(--font-size-lg, 32rpx);
  font-weight: 700;
  color: var(--color-text-primary, #0f172a);
}

/* —— 本次调账结果 —— */
.result-box {
  display: flex;
  flex-direction: column;
  gap: 10rpx;
  margin: 16rpx 0 8rpx;
  padding: 20rpx;
  border-radius: 18rpx;
  background: var(--brand-soft, #f0fdfa);
}
.result-head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}
.result-title {
  font-size: var(--font-size-body, 26rpx);
  font-weight: 700;
  color: var(--brand-deep, #115e59);
}
.result-count {
  font-size: var(--font-size-xs, 22rpx);
  color: var(--brand-deep, #115e59);
}
.result-row {
  display: flex;
  align-items: center;
  gap: 12rpx;
}
.result-name {
  flex: 1;
  min-width: 0;
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-primary, #0f172a);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.result-flow {
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
}
.result-delta {
  font-size: var(--font-size-sm, 24rpx);
  font-weight: 700;
  min-width: 56rpx;
  text-align: right;
}
.result-delta.is-up {
  color: var(--brand, #0d9488);
}
.result-delta.is-down {
  color: var(--warning, #b45309);
}

/* —— ④ 吸底提交条 —— */
.dock {
  position: fixed;
  left: 0;
  right: 0;
  bottom: 0;
  z-index: 20;
  display: flex;
  align-items: center;
  gap: 20rpx;
  padding: 16rpx var(--page-gutter, 24rpx) calc(16rpx + env(safe-area-inset-bottom));
  background: var(--card-bg, #fff);
  border-top: 1rpx solid var(--color-border-subtle, #f3f4f6);
  box-shadow: 0 -4rpx 20rpx rgba(15, 23, 42, 0.06);
}
.dock-stat {
  flex: 1;
  min-width: 0;
  display: flex;
  align-items: baseline;
}
.dock-num {
  font-size: var(--font-size-h3, 40rpx);
  font-weight: 700;
  color: var(--warning, #b45309);
  line-height: 1;
}
.dock-label {
  font-size: var(--font-size-sm, 24rpx);
  color: var(--color-text-secondary, #64748b);
}
.dock-btn {
  flex: 0 0 auto;
  /* 尺寸写在本类上：app-button 的 88rpx 在组件样式里，页面这层看不见 ⇒ 触控门禁会把它
     算成「量不出热区的可点类」（实测 285→286）。这里显式重复一次，判据可证。 */
  min-width: 88rpx;
  min-height: 88rpx;
}
</style>
