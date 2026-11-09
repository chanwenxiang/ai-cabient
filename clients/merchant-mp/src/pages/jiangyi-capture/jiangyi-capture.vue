<template>
  <view>
    <app-nav-bar title="商品采集" home-url="/pages/home/home" />
    <view class="page-body">
      <view class="card">
        <text class="label">商品条码</text>
        <view class="row">
          <input v-model="barCode" class="input" placeholder="扫描或输入条码" @blur="loadState" />
          <button size="mini" class="mini" @click="scan">扫码</button>
        </view>
        <view class="row">
          <button size="mini" class="mini" :disabled="!barCode" @click="loadState">查状态</button>
        </view>
        <view v-if="state" class="meta">
          <text>当前状态：{{ stateLabel }}</text>
          <text v-if="state.nextAction">下一步：{{ state.nextAction }}</text>
          <text v-if="state.rejectCause" class="err">驳回原因：{{ state.rejectCause }}</text>
        </view>
        <text v-if="error" class="err">{{ error }}</text>
      </view>

      <view v-if="policy" class="card">
        <text class="label">拍摄须知（厂商审核按此判）</text>
        <view class="rules">
          <text class="rule"
            >· 按下方角度提示逐张拍，共 {{ policy.shotCount }} 张（正放 4 面 + 倒置 4 面）</text
          >
          <text class="rule">· 商品直立居中、尽量占满画面（正放/倒置都要竖直摆）</text>
          <text class="rule">· 镜头与商品同高、正面平视，不要俯拍或仰拍</text>
          <text class="rule">· 背景统一干净（同一张白纸/纯色墙面，不要桌面杂物、键盘、包装）</text>
          <text class="rule">· 光线均匀、避免阴影与强反光</text>
          <text class="rule">· 避免强反光、遮挡和模糊；一张图只拍一个商品</text>
          <text class="rule">· 必须实物现场拍摄，不能用网图或商品宣传图</text>
        </view>
        <text class="label">按顺序拍摄 {{ policy.shotCount }} 张</text>
        <view v-for="(hint, index) in policy.shotHints" :key="index" class="shot">
          <text class="shot-hint">{{ index + 1 }}. {{ hint }}</text>
          <image v-if="photos[index]" :src="photos[index]" class="thumb" mode="aspectFill" />
          <text v-else class="shot-empty">未拍摄</text>
          <button size="mini" class="mini" @click="take(index)">
            {{ photos[index] ? '重拍' : '拍照' }}
          </button>
        </view>
        <button class="submit" :disabled="!canSubmit || submitting" @click="submit">
          {{ submitting ? '提交中…' : '提交审核' }}
        </button>
        <text class="tip">照片必须是实物现场拍摄，不能用网图或宣传图</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue';
import { onLoad } from '@dcloudio/uni-app';
import {
  fetchJiangyiCapturePolicy,
  fetchJiangyiCaptureState,
  submitJiangyiPhoneCapture,
  uploadJiangyiCapturePhoto,
  type JiangyiCapturePolicy,
  type JiangyiCaptureState
} from '@/utils/merchant-api';

const barCode = ref('');
const state = ref<JiangyiCaptureState | null>(null);
const policy = ref<JiangyiCapturePolicy | null>(null);
const photos = ref<string[]>([]);
const error = ref('');
const submitting = ref(false);
const picking = ref(false);

const stateLabel = computed(() => state.value?.jiangyiState || '未知');
const canSubmit = computed(
  () =>
    !!policy.value &&
    photos.value.length === policy.value.shotCount &&
    photos.value.every((path) => !!path)
);

onLoad(async (query) => {
  const fromQuery = (query as Record<string, string> | undefined)?.barCode;
  if (fromQuery) {
    barCode.value = fromQuery;
  }
  try {
    policy.value = await fetchJiangyiCapturePolicy();
    photos.value = new Array(policy.value.shotCount).fill('');
    if (barCode.value) {
      await loadState();
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '读取拍摄要求失败';
  }
});

async function loadState() {
  if (!barCode.value) {
    return;
  }
  error.value = '';
  try {
    state.value = await fetchJiangyiCaptureState(barCode.value.trim());
  } catch (e) {
    state.value = null;
    error.value = e instanceof Error ? e.message : '查询商品状态失败';
  }
}

function scan() {
  uni.scanCode({
    success(res) {
      barCode.value = res.result || '';
      loadState();
    }
  });
}

function take(index: number) {
  if (picking.value) {
    return;
  }
  picking.value = true;
  uni.chooseImage({
    count: 1,
    sizeType: ['compressed'],
    sourceType: ['camera', 'album'],
    success(res) {
      const path = res.tempFilePaths?.[0];
      if (path) {
        photos.value[index] = path;
      }
    },
    complete() {
      picking.value = false;
    }
  });
}

async function submit() {
  if (!barCode.value || !canSubmit.value) {
    return;
  }
  submitting.value = true;
  error.value = '';
  try {
    const imageUrls: string[] = [];
    for (const path of photos.value) {
      imageUrls.push(await uploadJiangyiCapturePhoto(barCode.value.trim(), path));
    }
    state.value = await submitJiangyiPhoneCapture(barCode.value.trim(), imageUrls);
    uni.showToast({ title: '已提交，等待将邑审核', icon: 'none' });
  } catch (e) {
    error.value = e instanceof Error ? e.message : '提交失败';
  } finally {
    submitting.value = false;
  }
}
</script>

<style scoped>
.page-body {
  padding: 24rpx;
}
.card {
  background: #ffffff;
  border-radius: 16rpx;
  padding: 24rpx;
  margin-bottom: 24rpx;
}
.label {
  display: block;
  font-size: 28rpx;
  color: #333333;
  margin-bottom: 16rpx;
}
.rules {
  margin-bottom: 24rpx;
  padding: 16rpx 20rpx;
  background: #fff8e6;
  border-radius: 8rpx;
}
.rule {
  display: block;
  font-size: 24rpx;
  line-height: 36rpx;
  color: #8a6d3b;
}
.row {
  display: flex;
  align-items: center;
  gap: 16rpx;
  margin-bottom: 16rpx;
}
.input {
  flex: 1;
  border: 1rpx solid #dddddd;
  border-radius: 8rpx;
  padding: 12rpx 16rpx;
  font-size: 28rpx;
}
/* 热区按三端统一判据钉死（check:mp-touch-target-ratchet：短边 88rpx，两边都要能算出来） */
.mini {
  margin: 0;
  min-width: 168rpx;
  height: 88rpx;
  min-height: 88rpx;
  line-height: 88rpx;
  box-sizing: border-box;
  padding: 0 20rpx;
  font-size: 26rpx;
}
.meta {
  display: flex;
  flex-direction: column;
  gap: 8rpx;
  font-size: 26rpx;
  color: #555555;
}
.shot {
  display: flex;
  align-items: center;
  gap: 16rpx;
  padding: 16rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}
.shot-hint {
  flex: 1;
  font-size: 26rpx;
  color: #333333;
}
.shot-empty {
  font-size: 24rpx;
  color: #999999;
}
.thumb {
  width: 96rpx;
  height: 96rpx;
  border-radius: 8rpx;
  background: #f5f5f5;
}
.submit {
  margin-top: 24rpx;
  width: 100%;
  height: 88rpx;
  min-height: 88rpx;
  line-height: 88rpx;
  box-sizing: border-box;
  background: #07c160;
  color: #ffffff;
  font-size: 30rpx;
}
.tip {
  display: block;
  margin-top: 16rpx;
  font-size: 24rpx;
  color: #999999;
}
.err {
  color: #d93026;
  font-size: 26rpx;
}
</style>
