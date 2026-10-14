<template>
  <div v-loading="loading" class="pricing-pane">
    <el-alert
      class="pricing-hint"
      type="info"
      :closable="false"
      show-icon
      title="单柜定价只影响本柜：覆盖价启用后，顾客在本柜下单按覆盖价结算（时段/清仓折扣仍会在其上叠加）；停用则恢复跟随商品库基准价。可改区间与限价规则与商户端一致。"
    />
    <el-table :data="rows" size="small" :row-key="(r: DevicePricingRow) => r.skuId">
      <el-table-column label="商品" min-width="180">
        <template #default="{ row }">
          <div class="sku-cell">
            <el-image
              v-if="row.imageUrl"
              :src="row.imageUrl"
              fit="cover"
              class="sku-img"
              :preview-src-list="[row.imageUrl]"
              preview-teleported
            />
            <div class="sku-meta">
              <span class="sku-name">{{ row.skuName }}</span>
              <span class="sku-id">{{ row.skuId }}</span>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="基准价" width="90" align="right" class-name="col-money">
        <template #default="{ row }">{{ yuan(row.basePriceCents) }}</template>
      </el-table-column>
      <el-table-column label="覆盖价" width="150" class-name="col-money">
        <template #default="{ row }">
          <el-input-number
            v-if="canEdit"
            :model-value="row.overridePriceCents ?? undefined"
            :min="row.minPriceCents"
            :max="row.maxPriceCents"
            :precision="0"
            :controls="false"
            size="small"
            class="price-input"
            :class="{ 'below-cost': isBelowCost(row) }"
            placeholder="跟随基准"
            :disabled="saving[row.skuId] === 'price'"
            @change="(v: number | null) => savePrice(row, v ?? null)"
          />
          <span v-else>{{
            row.overridePriceCents != null ? yuan(row.overridePriceCents) : '—'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="启用" width="80" align="center" class-name="col-text">
        <template #default="{ row }">
          <el-switch
            v-if="canEdit"
            :model-value="row.overrideEnabled && row.overridePriceCents != null"
            :disabled="row.overridePriceCents == null || saving[row.skuId] === 'toggle'"
            @change="() => toggleEnabled(row)"
          />
          <span v-else>{{
            row.overridePriceCents != null ? (row.overrideEnabled ? '启用' : '停用') : '—'
          }}</span>
        </template>
      </el-table-column>
      <el-table-column label="当前成交价" width="110" align="right" class-name="col-money">
        <template #default="{ row }">
          <span :class="{ 'price-active': row.overridePriceCents != null && row.overrideEnabled }">
            {{ yuan(row.effectivePriceCents) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="可改区间" width="130" align="center" class-name="col-money">
        <template #default="{ row }"
          >{{ yuan(row.minPriceCents) }} ~ {{ yuan(row.maxPriceCents) }}</template
        >
      </el-table-column>
      <el-table-column
        label="库存"
        width="70"
        align="right"
        prop="sellableQty"
        class-name="col-text"
      />
      <el-table-column
        v-if="canEdit"
        label="操作"
        width="110"
        align="center"
        class-name="col-action"
      >
        <template #default="{ row }">
          <el-button
            v-if="row.overridePriceCents != null"
            link
            type="primary"
            size="small"
            :loading="saving[row.skuId] === 'reset'"
            @click="resetToBase(row)"
            >恢复基准</el-button
          >
          <span v-else class="muted">跟随基准</span>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-if="!loading && !rows.length" description="该柜暂无在售商品" :image-size="64" />
  </div>
</template>

<script setup lang="ts">
/**
 * 单柜定价卡（V350，CB-037）：行内编辑覆盖价/启停/恢复基准，不走弹窗。
 * 低于进货成本仅黄色提示不阻断（拍板：清仓/引流打折放行，知情即可）。
 */
import { toRef } from 'vue';
import { useDevicePricing, type DevicePricingRow } from '@/composables/device/useDevicePricing';

const props = defineProps<{
  deviceId: string;
  canEdit: boolean;
  /** 父组件在 tab 激活时置 true 触发懒加载 */
  active: boolean;
}>();

const {
  rows,
  loading,
  hydrated,
  saving,
  canEdit,
  load,
  ensureLoaded,
  savePrice,
  toggleEnabled,
  resetToBase
} = useDevicePricing({
  deviceId: props.deviceId,
  canEdit: toRef(props, 'canEdit')
});

if (props.active) ensureLoaded();

function onActive(active: boolean) {
  if (active) ensureLoaded();
}

defineExpose({ onActive, reload: load });

function yuan(cents: number): string {
  return `¥${(cents / 100).toFixed(2)}`;
}

function isBelowCost(row: DevicePricingRow): boolean {
  return (
    row.purchaseCostCents != null &&
    row.purchaseCostCents > 0 &&
    row.overridePriceCents != null &&
    row.overridePriceCents < row.purchaseCostCents
  );
}
</script>

<style scoped>
.pricing-pane {
  min-height: 160px;
}
.pricing-hint {
  margin-bottom: 12px;
}
.sku-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.sku-img {
  width: 36px;
  height: 36px;
  border-radius: 4px;
  flex: none;
}
.sku-meta {
  display: flex;
  flex-direction: column;
  line-height: 1.3;
}
.sku-name {
  font-size: 13px;
}
.sku-id {
  font-size: 11px;
  color: var(--el-text-color-secondary);
}
.price-input {
  width: 110px;
}
.price-input.below-cost :deep(.el-input__inner) {
  color: var(--el-color-warning);
}
.price-active {
  font-weight: 600;
  color: var(--el-color-primary);
}
.muted {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}
</style>
