<template>
  <view class="page-root">
    <view class="header">
      <text class="header-title">积分明细</text>
    </view>

    <!-- 积分统计 -->
    <view class="stats-card">
      <view class="stat-item">
        <text class="stat-value">{{ pointsSummary.totalEarned }}</text>
        <text class="stat-label">累计获得</text>
      </view>
      <view class="stat-divider"></view>
      <view class="stat-item">
        <text class="stat-value">{{ pointsSummary.totalSpent }}</text>
        <text class="stat-label">累计消耗</text>
      </view>
      <view class="stat-divider"></view>
      <view class="stat-item">
        <text class="stat-value highlight">{{ pointsSummary.current }}</text>
        <text class="stat-label">当前积分</text>
      </view>
    </view>

    <!-- 筛选 -->
    <view class="filter-bar">
      <view 
        v-for="tab in tabs" 
        :key="tab.value"
        class="filter-tab"
        :class="{ active: currentTab === tab.value }"
        @click="currentTab = tab.value"
      >
        <text class="tab-text">{{ tab.label }}</text>
      </view>
    </view>

    <!-- 明细列表 -->
    <view class="list-container">
      <view v-for="item in pointsList" :key="item.id" class="points-item">
        <view class="item-left">
          <text class="item-title">{{ item.title }}</text>
          <text class="item-time">{{ item.createdAt }}</text>
        </view>
        <text class="item-points" :class="{ plus: item.type === 'earn', minus: item.type === 'spend' }">
          {{ item.type === 'earn' ? '+' : '-' }}{{ item.points }}
        </text>
      </view>

      <view v-if="pointsList.length === 0" class="empty-state">
        <text class="empty-text">暂无积分记录</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { consumerApi } from '@/utils/consumer-api';

const tabs = [
  { label: '全部', value: 'all' },
  { label: '获得', value: 'earn' },
  { label: '消耗', value: 'spend' }
];

const currentTab = ref('all');
const pointsSummary = ref({
  totalEarned: 0,
  totalSpent: 0,
  current: 0
});

const pointsList = ref<any[]>([]);

onMounted(() => {
  loadSummary();
  loadList();
});

async function loadSummary() {
  try {
    const res = await consumerApi.get('/api/v2/member/points/summary');
    pointsSummary.value = res.data?.data ?? pointsSummary.value;
  } catch (error) {
    console.error('加载积分统计失败', error);
  }
}

async function loadList() {
  try {
    const res = await consumerApi.get('/api/v2/member/points', {
      params: { type: currentTab.value, page: 0, size: 50 }
    });
    pointsList.value = res.data?.data?.items ?? [];
  } catch (error) {
    console.error('加载积分明细失败', error);
  }
}
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: #f5f5f5;
}

.header {
  background: #fff;
  padding: 32rpx;
}

.header-title {
  font-size: 36rpx;
  font-weight: bold;
  color: #333;
}

.stats-card {
  display: flex;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  margin: 24rpx;
  padding: 32rpx;
  border-radius: 16rpx;
  color: #fff;
}

.stat-item {
  flex: 1;
  text-align: center;
}

.stat-value {
  font-size: 40rpx;
  font-weight: bold;
  display: block;
  margin-bottom: 8rpx;
}

.stat-value.highlight {
  color: #ffd700;
}

.stat-label {
  font-size: 24rpx;
  opacity: 0.8;
}

.stat-divider {
  width: 1rpx;
  background: rgba(255, 255, 255, 0.3);
}

.filter-bar {
  display: flex;
  background: #fff;
  padding: 16rpx 24rpx;
  margin-bottom: 24rpx;
}

.filter-tab {
  padding: 16rpx 32rpx;
  margin-right: 16rpx;
}

.filter-tab.active {
  background: #f0f5ff;
  border-radius: 24rpx;
}

.tab-text {
  font-size: 28rpx;
  color: #666;
}

.filter-tab.active .tab-text {
  color: #667eea;
  font-weight: bold;
}

.list-container {
  background: #fff;
  margin: 24rpx;
  border-radius: 16rpx;
  padding: 24rpx;
}

.points-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}

.points-item:last-child {
  border-bottom: none;
}

.item-left {
  display: flex;
  flex-direction: column;
}

.item-title {
  font-size: 28rpx;
  color: #333;
  margin-bottom: 8rpx;
}

.item-time {
  font-size: 24rpx;
  color: #999;
}

.item-points {
  font-size: 32rpx;
  font-weight: bold;
}

.item-points.plus {
  color: #52c41a;
}

.item-points.minus {
  color: #ff4d4f;
}

.empty-state {
  text-align: center;
  padding: 80rpx 0;
}

.empty-text {
  font-size: 28rpx;
  color: #999;
}
</style>
