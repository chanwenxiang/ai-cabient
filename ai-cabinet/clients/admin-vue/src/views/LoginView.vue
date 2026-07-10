<template>
  <div class="login-page">
    <el-card class="login-card" shadow="always">
      <h1>AI开门柜</h1>
      <p class="sub">运营管理系统</p>
      <el-form @submit.prevent="onSubmit">
        <el-form-item label="手机号">
          <el-input v-model="phone" maxlength="11" placeholder="11位手机号" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input v-model="password" type="password" show-password placeholder="登录密码" />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" style="width:100%">登录</el-button>
        <p v-if="err" class="err">{{ err }}</p>
      </el-form>
      <p class="hint">演示：13900000001 / 123456</p>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const phone = ref('13900000001');
const password = ref('123456');
const loading = ref(false);
const err = ref('');
const auth = useAuthStore();
const router = useRouter();
const route = useRoute();

async function onSubmit() {
  loading.value = true;
  err.value = '';
  try {
    await auth.login(phone.value.trim(), password.value);
    const redirect = (route.query.redirect as string) || '/devices';
    router.replace(redirect);
  } catch (e) {
    err.value = e instanceof Error ? e.message : '登录失败';
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page { min-height: 100vh; display: flex; align-items: center; justify-content: center; background: linear-gradient(135deg, #0f172a, #1e3a5f); }
.login-card { width: 380px; }
.login-card h1 { margin: 0 0 4px; }
.sub { color: #64748b; margin: 0 0 20px; }
.err { color: #ef4444; margin-top: 12px; }
.hint { color: #94a3b8; font-size: 0.8rem; margin-top: 16px; }
</style>
