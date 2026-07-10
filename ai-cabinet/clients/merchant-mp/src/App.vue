<script setup lang="ts">
import { onLaunch } from '@dcloudio/uni-app';

onLaunch(() => {
  if (!uni.getStorageSync('merchant_token')) return;
});
</script>

<style>
page { background: #f0fdfa; font-family: system-ui, -apple-system, sans-serif; }
.card { background: #fff; border-radius: 16px; padding: 20px; margin: 12px; box-shadow: 0 2px 12px rgba(15, 118, 110, 0.08); }
.btn-primary { background: linear-gradient(135deg, #134e4a, #0f766e); color: #fff; border-radius: 12px; padding: 14px; text-align: center; font-weight: 600; font-size: 30rpx; }
.meta { color: #64748b; font-size: 24rpx; }
.slot-grid { display: flex; flex-wrap: wrap; gap: 8px; }
.slot-cell { width: 30%; border: 1px solid #e2e8f0; border-radius: 8px; padding: 8px; font-size: 22rpx; background: #fff; }
</style>
