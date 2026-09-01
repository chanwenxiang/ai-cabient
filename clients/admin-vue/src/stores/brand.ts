import { computed, ref } from 'vue';
import { defineStore } from 'pinia';
import { api } from '@/api/client';

export interface OpsBrand {
  title: string;
  subtitle: string;
  sidebarTitle: string;
  logoUrl: string;
}

const DEFAULT_BRAND: OpsBrand = {
  title: 'AI开门柜',
  subtitle: '运营管理系统',
  sidebarTitle: 'AI开门柜运营',
  logoUrl: ''
};

export const useBrandStore = defineStore('brand', () => {
  const brand = ref<OpsBrand>({ ...DEFAULT_BRAND });
  const loaded = ref(false);
  const loading = ref(false);

  const markChar = computed(() => {
    const t = (brand.value.title || DEFAULT_BRAND.title).trim();
    return t ? t.slice(-1) : '柜';
  });

  const documentBaseTitle = computed(
    () => `${brand.value.title || DEFAULT_BRAND.title} · ${brand.value.subtitle || DEFAULT_BRAND.subtitle}`
  );

  async function load() {
    if (loading.value) return;
    loading.value = true;
    try {
      const data = await api.request<{
        title?: string;
        subtitle?: string;
        sidebarTitle?: string;
        logoUrl?: string;
      }>('/api/v2/public/ops-branding', 'GET');
      brand.value = {
        title: (data?.title || '').trim() || DEFAULT_BRAND.title,
        subtitle: (data?.subtitle || '').trim() || DEFAULT_BRAND.subtitle,
        sidebarTitle: (data?.sidebarTitle || '').trim() || DEFAULT_BRAND.sidebarTitle,
        logoUrl: (data?.logoUrl || '').trim()
      };
      document.title = documentBaseTitle.value;
    } catch {
      // 软失败：保持默认文案，避免登录页空白
      brand.value = { ...DEFAULT_BRAND };
    } finally {
      loaded.value = true;
      loading.value = false;
    }
  }

  function applyLocal( partial: Partial<OpsBrand>) {
    brand.value = {
      ...brand.value,
      ...partial,
      title: (partial.title ?? brand.value.title).trim() || DEFAULT_BRAND.title,
      subtitle: (partial.subtitle ?? brand.value.subtitle).trim() || DEFAULT_BRAND.subtitle,
      sidebarTitle:
        (partial.sidebarTitle ?? brand.value.sidebarTitle).trim() || DEFAULT_BRAND.sidebarTitle,
      logoUrl: (partial.logoUrl ?? brand.value.logoUrl).trim()
    };
    document.title = documentBaseTitle.value;
  }

  return {
    brand,
    loaded,
    loading,
    markChar,
    documentBaseTitle,
    load,
    applyLocal
  };
});
