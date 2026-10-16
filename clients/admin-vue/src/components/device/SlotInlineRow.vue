<script setup lang="ts">
/**
 * 在售商品卡片内的货道行内编辑行（2026-10-16 改版：陈列网格下线，卡片即编辑入口）。
 * 收起态=只读摘要行；展开态=父组件持有的草稿表单（商品绑定/目层/启停）。
 * 盘点仍走详情页既有弹窗（实盘/调账逻辑集中在 useDeviceSlotActions，不在此重复）。
 */
import type { DeviceSlot, SkuCatalog } from '@aicabinet/shared-types';

export interface SlotDraft {
  assignedSkuId: string;
  parLevel: number;
  minLevel: number;
  maxLevel: number;
  enabled: boolean;
}

const props = defineProps<{
  /** 该行对应的货道 */
  row: DeviceSlot;
  skus: SkuCatalog[];
  /** null=收起；展开时为父组件持有的草稿 */
  draft: SlotDraft | null;
  saving: boolean;
  canEdit: boolean;
  canStocktake: boolean;
}>();

const emit = defineEmits<{
  toggle: [];
  save: [];
  stocktake: [];
  /** 草稿字段变更（父组件合并进 slotDrafts；子组件不直接改 prop） */
  update: [patch: Partial<SlotDraft>];
}>();

/** 受控字段统一出口：所有编辑控件都走这里回传 patch，避免 vue/no-mutating-props */
function set<K extends keyof SlotDraft>(key: K, value: SlotDraft[K]) {
  emit('update', { [key]: value } as Partial<SlotDraft>);
}

// props 在模板中以解构名使用；此处显式引用避免「声明未用」误报
void props;
</script>

<template>
  <div class="slot-line">
    <div class="slot-line-head">
      <span class="slot-code">{{ row.slotCode }}</span>
      <span class="slot-qty">{{ row.bookQty }}/{{ row.maxLevel || row.parLevel }}</span>
      <el-tag v-if="!row.enabled" size="small" type="info" effect="plain">已停用</el-tag>
      <span v-else-if="row.stockoutWarn" class="slot-badge-warn">缺货</span>
      <span v-if="row.hasDiscrepancy" class="slot-badge-diff">账实差 {{ row.qtyDiff }}</span>
      <span class="slot-line-actions">
        <el-button v-if="canStocktake" link size="small" @click="emit('stocktake')">盘点</el-button>
        <el-button v-if="canEdit" link type="primary" size="small" @click="emit('toggle')">
          {{ draft ? '收起' : '编辑' }}
        </el-button>
      </span>
    </div>
    <div v-if="draft" class="slot-line-editor">
      <el-form label-width="auto" size="small" @submit.prevent>
        <el-form-item label="商品">
          <el-select
            :model-value="draft.assignedSkuId"
            filterable
            clearable
            placeholder="选择商品"
            style="width: 100%"
            @update:model-value="(v: string) => set('assignedSkuId', v ?? '')"
          >
            <el-option
              v-for="s in skus"
              :key="s.skuId"
              :label="`${s.skuName} (${s.skuId})`"
              :value="s.skuId"
            />
          </el-select>
        </el-form-item>
        <div class="slot-line-nums">
          <el-form-item label="目标陈列">
            <el-input-number
              :model-value="draft.parLevel"
              :min="0"
              style="width: 100%"
              @update:model-value="(v: number | undefined) => set('parLevel', v ?? 0)"
            />
          </el-form-item>
          <el-form-item label="最低库存">
            <el-input-number
              :model-value="draft.minLevel"
              :min="0"
              style="width: 100%"
              @update:model-value="(v: number | undefined) => set('minLevel', v ?? 0)"
            />
          </el-form-item>
          <el-form-item label="最大容量">
            <el-input-number
              :model-value="draft.maxLevel"
              :min="0"
              style="width: 100%"
              @update:model-value="(v: number | undefined) => set('maxLevel', v ?? 0)"
            />
          </el-form-item>
          <el-form-item label="启用">
            <el-switch
              :model-value="draft.enabled"
              @update:model-value="(v: string | number | boolean) => set('enabled', Boolean(v))"
            />
          </el-form-item>
        </div>
        <div class="slot-line-btns">
          <el-button size="small" @click="emit('toggle')">取消</el-button>
          <el-button
            v-hasPermi="['ops:device:edit']"
            type="primary"
            size="small"
            :loading="saving"
            @click="emit('save')"
            >保存</el-button
          >
        </div>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.slot-line {
  width: 100%;
  border-top: 1px solid var(--el-border-color-lighter);
  padding: 6px 0 2px;
}
.slot-line:first-of-type {
  border-top: none;
}
.slot-line-head {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 24px;
  font-size: var(--admin-font-size-sm);
}
.slot-code {
  font-weight: 600;
  color: var(--el-text-color-regular);
}
.slot-qty {
  color: var(--el-text-color-secondary);
  font-variant-numeric: tabular-nums;
}
.slot-badge-warn {
  color: var(--el-color-danger);
  font-weight: 600;
}
.slot-badge-diff {
  color: #c2410c;
}
.slot-line-actions {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
}
.slot-line-editor {
  margin-top: 4px;
  padding: 8px;
  border-radius: 6px;
  background: var(--el-fill-color-lighter);
}
.slot-line-editor :deep(.el-form-item) {
  margin-bottom: 8px;
}
.slot-line-nums {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 0 12px;
}
.slot-line-btns {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}
</style>
