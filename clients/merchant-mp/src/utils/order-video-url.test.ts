import { describe, expect, it } from 'vitest';
import { MerchantEndpoints } from '@/api/endpoints';
import {
  clipLabel,
  merchantOrderVideoUrl,
  merchantVideoErrorView,
  pickPlayableClips,
  type OrderVideoClip
} from './order-video-url';

describe('merchantOrderVideoUrl · M12', () => {
  it('绝对 URL 含 Endpoints 相对路径且编码 orderId', () => {
    const url = merchantOrderVideoUrl('O/1');
    expect(url).toContain(MerchantEndpoints.orderVideo('O/1'));
    expect(url).toMatch(/^https?:\/\//);
    expect(url).toContain(encodeURIComponent('O/1'));
  });
});

describe('merchantVideoErrorView', () => {
  it('404 映射为暂无录像且不复制链接', () => {
    const view = merchantVideoErrorView(new Error('下载失败 (404)'));
    expect(view.title).toBe('暂无购物视频');
    expect(view.showCopy).toBe(false);
  });

  it('对象不存在文案同样不复制', () => {
    const view = merchantVideoErrorView(new Error('视频文件不存在或无法读取'));
    expect(view.title).toBe('暂无购物视频');
    expect(view.showCopy).toBe(false);
  });
});

describe('pickPlayableClips · CB-030', () => {
  it('只保留可播放且有地址的片，保持后端片序', () => {
    const clips = pickPlayableClips({
      source: 'JIANGYI',
      clips: [
        {
          serialNum: 1,
          total: 3,
          channel: 1,
          url: 'https://signed-a',
          playable: true,
          reason: null
        },
        {
          serialNum: 2,
          total: 3,
          channel: 1,
          url: null,
          playable: false,
          reason: '该片生成或上传失败'
        },
        {
          serialNum: 3,
          total: 3,
          channel: 1,
          url: 'https://signed-c',
          playable: true,
          reason: null
        }
      ]
    });
    expect(clips.map((c) => c.serialNum)).toEqual([1, 3]);
    expect(clips[1].url).toBe('https://signed-c');
  });

  it('清单为空或未加载时返回空数组，不抛错', () => {
    expect(pickPlayableClips(null)).toEqual([]);
    expect(pickPlayableClips({ source: 'NONE', clips: [] })).toEqual([]);
  });

  it('playable 为真但地址缺失时同样不进入播放列表', () => {
    const clips = pickPlayableClips({
      source: 'JIANGYI',
      clips: [{ serialNum: 1, total: 1, channel: 1, url: null, playable: true, reason: null }]
    });
    expect(clips).toEqual([]);
  });
});

describe('clipLabel · CB-030 多通道区分', () => {
  const clip = (serialNum: number, channel: number): OrderVideoClip => ({
    serialNum,
    total: 2,
    channel,
    url: `https://signed-${serialNum}-${channel}`,
    playable: true,
    reason: null
  });

  it('单片单通道只显示片号（当前主流形态，不加噪音）', () => {
    const all = [clip(1, 1), clip(2, 1)];
    expect(clipLabel(all[0], all)).toBe('第 1 段');
    expect(clipLabel(all[1], all)).toBe('第 2 段');
  });

  it('同片多通道时补上通道号，避免出现两个同名「第 N 段」', () => {
    // 协议：上下摄像头地址同片上报 → serialNum 相同、channel 不同
    const all = [clip(1, 1), clip(1, 2)];
    expect(clipLabel(all[0], all)).toBe('第 1 段 · 通道1');
    expect(clipLabel(all[1], all)).toBe('第 1 段 · 通道2');
    expect(new Set(all.map((c) => clipLabel(c, all))).size).toBe(2);
  });
});
