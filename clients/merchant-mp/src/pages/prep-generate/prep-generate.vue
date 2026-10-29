<template>
  <view class="page">
    <app-nav-bar title="生成备货单" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="panel">
        <text class="head-hint">
          按每台柜「补满」口径预生成：建议量 = 上限 − 实际 − 已领未上柜。勾选柜机确认后，
          每台柜生成一张补货任务（从本人负责的分仓出库，无需审批）。
        </text>

        <view v-if="loading" class="empty-inline">{{ loadingLabel('预览') }}</view>
        <view v-else-if="!groups.length" class="empty-inline">
          各柜机都已补满，暂无需要备货的商品
        </view>

        <view v-for="group in groups" :key="group.deviceId" class="card">
          <view class="group-head" role="button" @click="toggleGroup(group)">
            <view class="check" :class="{ on: group.checked }">{{ group.checked ? '✓' : '' }}</view>
            <view class="group-copy">
              <text class="group-name">{{ group.deviceName }}</text>
              <text class="group-meta"
                >{{ group.items.length }} 种商品 · 合计 {{ group.totalText }}</text
              >
            </view>
          </view>
          <view v-for="item in group.items" :key="item.skuId" class="line-row">
            <view class="line-copy">
              <text class="sku-name">{{ item.skuName }}</text>
              <text class="sku-meta">
                当前 {{ item.currentQty }}{{ item.unit }} · 上限 {{ item.capacity || '—' }}
                <text v-if="item.inTransitQty > 0"> · 未上柜 {{ item.inTransitQty }}</text>
              </text>
            </view>
            <text class="qty-val">备 {{ item.qtyText }}</text>
          </view>
        </view>

        <app-button
          v-if="groups.length"
          :disabled="submitting || !checkedCount || !canRequest"
          :loading="submitting"
          :label="submitLabel"
          @click="submit"
        />
        <text v-if="groups.length" class="submit-hint">
          提交后任务出现在「补货任务」列表，首页备货清单同步更新
        </text>
        <text v-if="groups.length && !canRequest" class="err"
          >当前账号无补货发起权限，仅可查看预览</text
        >
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
/**
 * 手动生成备货单（A 方案，2026-10-29）：不依赖阈值、可提前生成。
 * 预览 = GET /merchant/replenishment/prep-preview（逐柜 fillToPar 建议，按柜分组）；
 * 确认 = 逐柜 POST /merchant/replenishment/self-initiate（复用自主补货链路：
 * 出库扣账 + 补货任务 + clientRequestNo 幂等），不加备货单实体。
 * 与首页「备货清单」（进行中任务出库行的事后反推）互补：本页是出发前的事前规划。
 */
import { computed, ref } from 'vue';
import { onLoad, onPullDownRefresh } from '@dcloudio/uni-app';
import { showError, showSuccess, showLongError } from '@/utils/notify';
import { loadingLabel } from '@aicabinet/shared-uni/ui-copy';
import AppButton from '@aicabinet/shared-uni/components/app-button.vue';
import AppNavBar from '@aicabinet/shared-uni/components/app-nav-bar.vue';
import { hasPerm, isMerchantLoggedIn, merchantApi } from '@/utils/merchant-api';
import { useMerchantMe } from '@/composables/useMerchantMe';
import { newSelfReplenishRequestNo } from '@/utils/self-replenish-submit';
import type { OpenApiMerchantPrepPreviewGroupDto } from '@aicabinet/shared-types';

/** 页内视图行：勾选态 + 数值量 + 件装换算文案（口径同 CB-061「X 件 + Y 支」） */
interface PrepGroupView {
  deviceId: string;
  deviceName: string;
  checked: boolean;
  items: {
    skuId: string;
    skuName: string;
    unit: string;
    /** 建议补货量（支）——提交 body 的 qty 直接取它 */
    qty: number;
    qtyText: string;
    currentQty: number;
    capacity: number;
    inTransitQty: number;
  }[];
  totalText: string;
}

const { me } = useMerchantMe();
const loading = ref(false);
const submitting = ref(false);
const groups = ref<PrepGroupView[]>([]);
const canRequest = computed(() => hasPerm(me.value, 'merchant:replenishment:request'));
const checkedCount = computed(() => groups.value.filter((g) => g.checked).length);
const submitLabel = computed(() =>
  submitting.value ? '生成中…' : `确认生成（${checkedCount.value} 台柜）`
);

onLoad(() => {
  if (!isMerchantLoggedIn()) {
    uni.reLaunch({ url: '/pages/login/login' });
    return;
  }
  void loadPreview();
});

onPullDownRefresh(() => loadPreview().finally(() => uni.stopPullDownRefresh()));

/** 件装换算：「X 件 + Y 支」，整件或拆零为 0 时省略对应段（与首页 prepPackText 同规则） */
function packText(qty: number, packQty: number, unit: string): string {
  const pack = Math.max(1, packQty || 1);
  const cases = Math.floor(qty / pack);
  const remain = qty % pack;
  const parts: string[] = [];
  if (cases > 0) parts.push(`${cases} 件`);
  if (remain > 0) parts.push(`${remain} ${unit}`);
  return parts.length ? parts.join(' + ') : `0 ${unit}`;
}

function toView(rows: OpenApiMerchantPrepPreviewGroupDto[]): PrepGroupView[] {
  return rows.map((group) => {
    const items = (group.items ?? []).map((item) => {
      const unit = String(item.unit || '支');
      const qty = Number(item.suggestQty) || 0;
      return {
        skuId: String(item.skuId),
        skuName: String(item.skuName || item.skuId),
        unit,
        qty,
        qtyText: packText(qty, Number(item.packQty) || 1, unit),
        currentQty: Number(item.currentQty) || 0,
        capacity: Number(item.capacity) || 0,
        inTransitQty: Number(item.inTransitQty) || 0
      };
    });
    const totalUnits = items.reduce((sum, item) => sum + item.qty, 0);
    const firstPack = Number(group.items?.[0]?.packQty) || 1;
    return {
      deviceId: String(group.deviceId),
      deviceName: String(group.deviceName || group.deviceId),
      checked: true,
      items,
      totalText: packText(totalUnits, firstPack, '支')
    };
  });
}

async function loadPreview() {
  loading.value = true;
  try {
    const rows = await merchantApi.replenishmentPrepPreview();
    groups.value = toView(rows || []);
  } catch (e) {
    groups.value = [];
    showError(e instanceof Error ? e.message : '预览加载失败');
  } finally {
    loading.value = false;
  }
}

function toggleGroup(group: PrepGroupView) {
  group.checked = !group.checked;
}

/**
 * 逐柜确认建任务（顺序提交；单柜失败不中断其余柜）。
 * 幂等：每柜一次一键（V401 契约）——传输类错误保留该柜键，重试复用同键幂等返回原任务。
 */
async function submit() {
  if (submitting.value) return;
  const targets = groups.value.filter((g) => g.checked);
  if (!targets.length) {
    showError('请至少勾选一台柜机');
    return;
  }
  submitting.value = true;
  // 每柜独立幂等键：设备间互不重放；传输失败的柜保留键供重试
  const requestNos = new Map<string, string>();
  const okTasks: string[] = [];
  const failures: string[] = [];
  for (const group of targets) {
    try {
      const body = {
        deviceId: group.deviceId,
        lines: group.items.map((item) => ({ skuId: item.skuId, qty: item.qty })),
        clientRequestNo: requestNos.get(group.deviceId) || newSelfReplenishRequestNo()
      };
      requestNos.set(group.deviceId, body.clientRequestNo);
      const task = await merchantApi.selfInitiateReplenishment(body);
      okTasks.push(`${group.deviceName}（任务 ${task.taskId}）`);
      requestNos.delete(group.deviceId);
    } catch (e) {
      failures.push(`${group.deviceName}：${e instanceof Error ? e.message : '提交失败'}`);
    }
  }
  submitting.value = false;
  if (okTasks.length && !failures.length) {
    showSuccess(`已生成 ${okTasks.length} 台柜的备货任务`);
    setTimeout(
      () => uni.navigateBack({ fail: () => uni.switchTab({ url: '/pages/home/home' }) }),
      900
    );
    return;
  }
  if (okTasks.length) {
    showLongError(
      `已生成：${okTasks.join('、')}。失败：${failures.join('；')}`,
      '部分柜机生成失败'
    );
    return;
  }
  showLongError(failures.join('；') || '生成失败，请稍后重试', '生成失败');
}
</script>

<style scoped src="./prep-generate.page.css"></style>
