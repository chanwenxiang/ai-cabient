import { ref } from 'vue';
import { formatDateTimeMinute } from './format';
import { announcementReadMap, markAnnouncementRead } from './announcement-read';
import type { AnnouncementDto } from '@aicabinet/shared-types';

export interface AnnouncementsListOptions {
  /** 摘要截断长度（字符），两端默认 80 */
  previewMax?: number;
}

/** 公告列表页共享逻辑：加载/未读/预览/优先级/跳详情。 */
export function useAnnouncementsList(
  fetchList: () => Promise<AnnouncementDto[]>,
  options: AnnouncementsListOptions = {}
) {
  const previewMax = options.previewMax ?? 80;
  const loading = ref(true);
  const error = ref('');
  const list = ref<AnnouncementDto[]>([]);
  const readMap = ref<Record<string, number>>({});

  function unread(id?: number) {
    return id != null && readMap.value[String(id)] == null;
  }

  async function load() {
    // 已有列表时静默刷新，避免返回/切页时整页先缩成「加载中」再撑开
    if (!list.value.length) loading.value = true;
    error.value = '';
    try {
      list.value = (await fetchList()) || [];
      readMap.value = announcementReadMap();
    } catch (e) {
      if (!list.value.length) {
        list.value = [];
        error.value = e instanceof Error ? e.message : '加载失败';
      } else {
        error.value = '';
      }
    } finally {
      loading.value = false;
    }
  }

  function goDetail(id?: number) {
    if (!id) return;
    uni.navigateTo({ url: `/pages/announcements/detail?id=${id}` });
  }

  function formatTime(t?: string) {
    return formatDateTimeMinute(t, '');
  }

  function previewText(content?: string) {
    const text = String(content || '')
      .replace(/\s+/g, ' ')
      .trim();
    return text.length > previewMax ? `${text.slice(0, previewMax)}…` : text;
  }

  function priorityLabel(p?: string) {
    if (p === 'URGENT') return '紧急';
    if (p === 'HIGH') return '重要';
    return '';
  }

  function priorityClass(p?: string) {
    if (p === 'URGENT') return 'urgent';
    if (p === 'HIGH') return 'high';
    return '';
  }

  return {
    loading,
    error,
    list,
    unread,
    load,
    goDetail,
    formatTime,
    previewText,
    priorityLabel,
    priorityClass
  };
}

/** 公告详情页共享逻辑：加载/标记已读/优先级。 */
export function useAnnouncementDetail(
  fetchDetail: (id: number) => Promise<AnnouncementDto | null | undefined>
) {
  const loading = ref(true);
  const error = ref('');
  const item = ref<AnnouncementDto | null>(null);
  let announceId = 0;

  async function load(id?: number) {
    if (typeof id === 'number') announceId = id;
    if (!announceId) {
      loading.value = false;
      error.value = '公告不存在';
      return;
    }
    loading.value = !item.value;
    error.value = '';
    try {
      item.value = (await fetchDetail(announceId)) ?? null;
      markAnnouncementRead(item.value?.announceId);
    } catch (e) {
      if (!item.value) {
        item.value = null;
        error.value = e instanceof Error ? e.message : '加载失败';
      }
    } finally {
      loading.value = false;
    }
  }

  function formatTime(t?: string) {
    return formatDateTimeMinute(t, '暂无');
  }

  function priorityLabel(p?: string) {
    if (p === 'URGENT') return '紧急';
    if (p === 'HIGH') return '重要';
    return '';
  }

  function priorityClass(p?: string) {
    if (p === 'URGENT') return 'urgent';
    if (p === 'HIGH') return 'high';
    return '';
  }

  return {
    loading,
    error,
    item,
    load,
    formatTime,
    priorityLabel,
    priorityClass
  };
}
