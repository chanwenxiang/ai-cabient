<template>
  <view class="page-root">
    <!-- 会员卡片 -->
    <view class="member-card" :class="'level-' + memberInfo.level">
      <view class="card-header">
        <image class="avatar" :src="userInfo.avatar || '/static/default-avatar.png'" mode="aspectFill" />
        <view class="user-info">
          <text class="nickname">{{ userInfo.nickname || '用户' }}</text>
          <view class="level-badge">
            <text class="level-text">{{ getLevelName(memberInfo.level) }}</text>
          </view>
        </view>
      </view>
      
      <view class="card-body">
        <view class="points-display">
          <text class="points-label">当前积分</text>
          <text class="points-value">{{ memberInfo.points }}</text>
        </view>
        
        <view class="progress-section">
          <view class="progress-bar">
            <view class="progress-fill" :style="{ width: progressWidth }" />
          </view>
          <text class="progress-text">
            还差 {{ pointsToNextLevel }} 积分升级到 {{ getLevelName(memberInfo.level + 1) }}
          </text>
        </view>
      </view>
    </view>

    <!-- 会员权益 -->
    <view class="section">
      <text class="section-title">会员权益</text>
      <view class="benefits-grid">
        <view v-for="benefit in currentBenefits" :key="benefit.id" class="benefit-item">
          <image class="benefit-icon" :src="benefit.icon" mode="aspectFit" />
          <text class="benefit-name">{{ benefit.name }}</text>
          <text class="benefit-desc">{{ benefit.desc }}</text>
        </view>
      </view>
    </view>

    <!-- 等级说明 -->
    <view class="section">
      <text class="section-title">等级说明</text>
      <view class="level-list">
        <view v-for="level in levelList" :key="level.level" 
              class="level-item" 
              :class="{ 'active': memberInfo.level === level.level }">
          <view class="level-left">
            <text class="level-name">{{ level.name }}</text>
            <text class="level-range">{{ level.minPoints }}-{{ level.maxPoints }}积分</text>
          </view>
          <view class="level-benefits">
            <text v-for="b in level.benefits" :key="b" class="benefit-tag">{{ b }}</text>
          </view>
        </view>
      </view>
    </view>

    <!-- 积分明细入口 -->
    <view class="section">
      <view class="action-item" @click="goToPointsHistory">
        <text class="action-label">积分明细</text>
        <text class="action-arrow">›</text>
      </view>
      <view class="action-item" @click="goToPointsExchange">
        <text class="action-label">积分兑换</text>
        <text class="action-arrow">›</text>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue';
import { consumerApi } from '@/utils/consumer-api';

const userInfo = ref({
  avatar: '',
  nickname: ''
});

const memberInfo = ref({
  level: 1,
  points: 0,
  totalSpent: 0
});

const levelConfig = [
  { level: 1, name: '普通会员', minPoints: 0, maxPoints: 999, benefits: ['基础折扣'] },
  { level: 2, name: '银卡会员', minPoints: 1000, maxPoints: 4999, benefits: ['95折', '优先客服'] },
  { level: 3, name: '金卡会员', minPoints: 5000, maxPoints: 9999, benefits: ['9折', '免配送', '专属活动'] },
  { level: 4, name: '钻石会员', minPoints: 10000, maxPoints: 99999, benefits: ['85折', '免配送', '专属客服', '生日礼'] }
];

const currentBenefits = computed(() => {
  const level = levelConfig.find(l => l.level === memberInfo.value.level);
  return [
    { id: 1, name: '会员折扣', desc: 最高折, icon: '/static/icons/discount.png' },
    { id: 2, name: '积分特权', desc: '购物双倍积分', icon: '/static/icons/points.png' },
    { id: 3, name: '专属活动', desc: '会员专享活动', icon: '/static/icons/activity.png' },
    { id: 4, name: '优先客服', desc: '专属客服通道', icon: '/static/icons/service.png' }
  ];
});

const progressWidth = computed(() => {
  const currentLevel = levelConfig.find(l => l.level === memberInfo.value.level);
  const nextLevel = levelConfig.find(l => l.level === memberInfo.value.level + 1);
  if (!currentLevel || !nextLevel) return '100%';
  
  const current = memberInfo.value.points - currentLevel.minPoints;
  const total = nextLevel.minPoints - currentLevel.minPoints;
  return ${Math.min(100, (current / total) * 100)}%;
});

const pointsToNextLevel = computed(() => {
  const nextLevel = levelConfig.find(l => l.level === memberInfo.value.level + 1);
  if (!nextLevel) return 0;
  return nextLevel.minPoints - memberInfo.value.points;
});

const levelList = computed(() => levelConfig);

function getLevelName(level: number): string {
  const config = levelConfig.find(l => l.level === level);
  return config?.name || '普通会员';
}

function getDiscount(level: number): number {
  const discounts: Record<number, number> = { 1: 9.8, 2: 9.5, 3: 9, 4: 8.5 };
  return discounts[level] || 9.8;
}

function goToPointsHistory() {
  uni.navigateTo({ url: '/pages/member/points-history' });
}

function goToPointsExchange() {
  uni.navigateTo({ url: '/pages/member/points-exchange' });
}

async function loadMemberInfo() {
  try {
    const res = await consumerApi.get('/api/v2/member/profile');
    const data = res.data?.data ?? {};
    memberInfo.value = {
      level: data.level || 1,
      points: data.points || 0,
      totalSpent: data.totalSpent || 0
    };
    userInfo.value = {
      avatar: data.avatar || '',
      nickname: data.nickname || ''
    };
  } catch (error) {
    console.error('加载会员信息失败', error);
  }
}

onMounted(() => loadMemberInfo());
</script>

<style scoped>
.page-root {
  min-height: 100vh;
  background: linear-gradient(180deg, #f5f5f5 0%, #fff 100%);
}

.member-card {
  margin: 24rpx;
  padding: 32rpx;
  border-radius: 24rpx;
  background: linear-gradient(135deg, #ffd700 0%, #ffaa00 100%);
  box-shadow: 0 8rpx 32rpx rgba(255, 170, 0, 0.3);
}

.member-card.level-2 {
  background: linear-gradient(135deg, #c0c0c0 0%, #a0a0a0 100%);
}

.member-card.level-3 {
  background: linear-gradient(135deg, #ffd700 0%, #ffaa00 100%);
}

.member-card.level-4 {
  background: linear-gradient(135deg, #b19cd9 0%, #9b59b6 100%);
}

.card-header {
  display: flex;
  align-items: center;
  margin-bottom: 32rpx;
}

.avatar {
  width: 96rpx;
  height: 96rpx;
  border-radius: 50%;
  border: 4rpx solid rgba(255, 255, 255, 0.8);
}

.user-info {
  margin-left: 24rpx;
  color: #fff;
}

.nickname {
  font-size: 32rpx;
  font-weight: bold;
  display: block;
  margin-bottom: 8rpx;
}

.level-badge {
  display: inline-block;
  background: rgba(255, 255, 255, 0.3);
  padding: 4rpx 16rpx;
  border-radius: 20rpx;
}

.level-text {
  font-size: 24rpx;
}

.card-body {
  color: #fff;
}

.points-display {
  text-align: center;
  margin-bottom: 24rpx;
}

.points-label {
  font-size: 24rpx;
  opacity: 0.8;
  display: block;
  margin-bottom: 8rpx;
}

.points-value {
  font-size: 64rpx;
  font-weight: bold;
}

.progress-section {
  padding-top: 16rpx;
}

.progress-bar {
  height: 12rpx;
  background: rgba(255, 255, 255, 0.3);
  border-radius: 6rpx;
  overflow: hidden;
}

.progress-fill {
  height: 100%;
  background: #fff;
  border-radius: 6rpx;
  transition: width 0.3s ease;
}

.progress-text {
  font-size: 22rpx;
  opacity: 0.8;
  display: block;
  margin-top: 8rpx;
  text-align: center;
}

.section {
  background: #fff;
  margin: 24rpx;
  padding: 32rpx;
  border-radius: 16rpx;
}

.section-title {
  font-size: 32rpx;
  font-weight: bold;
  color: #333;
  display: block;
  margin-bottom: 24rpx;
}

.benefits-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16rpx;
}

.benefit-item {
  text-align: center;
}

.benefit-icon {
  width: 64rpx;
  height: 64rpx;
  margin-bottom: 8rpx;
}

.benefit-name {
  font-size: 24rpx;
  color: #333;
  display: block;
  margin-bottom: 4rpx;
}

.benefit-desc {
  font-size: 20rpx;
  color: #999;
  display: block;
}

.level-list {
  padding: 0;
}

.level-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx;
  border-radius: 12rpx;
  margin-bottom: 16rpx;
  background: #f5f5f5;
}

.level-item.active {
  background: #fff7e6;
  border: 2rpx solid #ffd700;
}

.level-left {
  display: flex;
  flex-direction: column;
}

.level-name {
  font-size: 28rpx;
  color: #333;
  font-weight: bold;
}

.level-range {
  font-size: 22rpx;
  color: #999;
  margin-top: 4rpx;
}

.level-benefits {
  display: flex;
  gap: 8rpx;
}

.benefit-tag {
  font-size: 20rpx;
  color: #667eea;
  background: #f0f5ff;
  padding: 4rpx 12rpx;
  border-radius: 4rpx;
}

.action-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 24rpx 0;
  border-bottom: 1rpx solid #f0f0f0;
}

.action-item:last-child {
  border-bottom: none;
}

.action-label {
  font-size: 28rpx;
  color: #333;
}

.action-arrow {
  font-size: 32rpx;
  color: #ccc;
}
</style>
