<template>
  <!-- 拣货/发运：用 el-dialog 替代 MessageBox，避免自动化偶发点不到确认钮 -->
  <el-dialog
    :model-value="outboundConfirm.visible"
    :title="outboundConfirm.title"
    append-to-body
    destroy-on-close
    :close-on-click-modal="false"
    data-testid="outbound-confirm-dialog"
    @update:model-value="emit('update:visible', $event)"
    @closed="emit('closed')"
  >
    <p class="outbound-confirm-body">
      <span class="outbound-confirm-id" data-testid="outbound-confirm-id"
        >出库单 {{ outboundConfirm.outboundId }}</span
      >
      <br />
      {{ outboundConfirm.message }}
    </p>
    <template #footer>
      <el-button data-testid="outbound-confirm-cancel" @click="emit('cancel')"
        >取消</el-button
      >
      <el-button
        type="primary"
        :loading="outboundConfirm.saving"
        data-testid="outbound-confirm-ok"
        @click="emit('submit')"
        >确定</el-button
      >
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
defineProps<{
  outboundConfirm: {
    visible: boolean;
    saving: boolean;
    title: string;
    message: string;
    outboundId: number | string | null;
  };
}>();

const emit = defineEmits<{
  'update:visible': [value: boolean];
  cancel: [];
  submit: [];
  closed: [];
}>();
</script>

<style scoped>
.outbound-confirm-body {
  margin: 0;
  color: var(--layout-text);
  line-height: 1.6;
  font-size: var(--admin-font-size-menu);
}
.outbound-confirm-id {
  display: inline-block;
  margin-bottom: 6px;
  font-weight: 700;
  font-size: var(--admin-font-size-title);
  color: var(--app-primary, #0f766e);
}
</style>
