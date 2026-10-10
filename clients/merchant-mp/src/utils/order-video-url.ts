/**
 * 商户订单购物视频绝对 URL（M12）。
 * 页内禁止再拼 API_BASE + path；小程序 downloadFile 走本函数。
 */
import { API_BASE_URL } from '@/config/api';
import { MerchantEndpoints } from '@/api/endpoints';

export function merchantOrderVideoUrl(orderId: string): string {
  return `${API_BASE_URL.replace(/\/$/, '')}${MerchantEndpoints.orderVideo(orderId)}`;
}

export type MerchantVideoErrorView = {
  title: string;
  desc: string;
  showCopy: boolean;
};

/** 把下载/播放失败收成用户可读文案。404=对象不存在，禁止再引导「复制链接」。 */
export function merchantVideoErrorView(err: unknown): MerchantVideoErrorView {
  const raw = err instanceof Error ? err.message : String(err || '');
  if (/401|未登录|登录已失效/.test(raw)) {
    return { title: '请重新登录', desc: '登录过期后无法拉取录像', showCopy: false };
  }
  if (/404|暂无|不存在|无法读取/.test(raw)) {
    return {
      title: '暂无购物视频',
      desc: '本单没有可播放的录像。超时免单、模拟柜或文件未上传时常见。',
      showCopy: false
    };
  }
  return {
    title: '视频加载失败',
    desc: raw || '录像暂时无法播放',
    showCopy: false
  };
}

/** CB-030：视频来源。EDGE=旧边缘链路（走字节流端点）；JIANGYI=将邑台账（预签名直链）；NONE=无视频。 */
export type OrderVideoSource = 'EDGE' | 'JIANGYI' | 'NONE';

/** CB-030：单个可播放分片（对应后端 OrderVideoPlaylistDto.Clip）。 */
export type OrderVideoClip = {
  serialNum: number;
  total: number;
  /** 该片内摄像头通道序号（1 起）。同一 serialNum 可能有多个通道（协议：上下摄像头同片上报）。 */
  channel: number;
  url: string | null;
  playable: boolean;
  reason: string | null;
};

/** CB-030：订单购物视频清单（对应后端 OrderVideoPlaylistDto）。 */
export type OrderVideoPlaylist = {
  source: OrderVideoSource;
  clips: OrderVideoClip[];
};

/**
 * 从清单挑出真正可播放的片。后端对「该片生成/上传失败」会如实回 playable=false，
 * 这里只用于播放列表，失败片由调用方按需提示，不静默当成功。
 */
export function pickPlayableClips(
  playlist: OrderVideoPlaylist | null | undefined
): OrderVideoClip[] {
  return (playlist?.clips ?? []).filter((c) => c.playable && !!c.url);
}

/**
 * CB-030：分片切换条上的标签。
 *
 * <p>单片单通道（当前主流形态）只显示「第 N 段」；同一片存在多通道时补上通道号，避免出现
 * 两个同名「第 N 段」让人分不清播的是哪一路。不做「上/下」命名——协议未定义哪一路是上摄像头，
 * 臆测会误导复核。</p>
 */
export function clipLabel(clip: OrderVideoClip, all: OrderVideoClip[]): string {
  const base = `第 ${clip.serialNum} 段`;
  const sameShard = all.filter((c) => c.serialNum === clip.serialNum).length;
  return sameShard > 1 ? `${base} · 通道${clip.channel}` : base;
}
