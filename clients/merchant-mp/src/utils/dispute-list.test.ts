import { describe, expect, it } from 'vitest';
import {
  DISPUTES_FOCUS_SCAN_MAX_PAGES,
  DISPUTES_PAGE_SIZE,
  playablePlaybackUrl
} from './dispute-list';

describe('dispute-list · M7', () => {
  it('PAGE_SIZE 上限 50', () => {
    expect(DISPUTES_PAGE_SIZE).toBe(50);
    expect(DISPUTES_PAGE_SIZE).toBeLessThan(100);
    expect(DISPUTES_FOCUS_SCAN_MAX_PAGES).toBeGreaterThan(0);
  });

  it('只接受可播放协议', () => {
    expect(playablePlaybackUrl('https://cdn.example/a.mp4')).toBe('https://cdn.example/a.mp4');
    expect(playablePlaybackUrl('http://cdn.example/a.mp4')).toBe('http://cdn.example/a.mp4');
    expect(playablePlaybackUrl('blob:https://x/1')).toMatch(/^blob:/);
    expect(playablePlaybackUrl('minio://cabinet-videos/sim/a.mp4')).toBe('');
    expect(playablePlaybackUrl('')).toBe('');
    expect(playablePlaybackUrl(null)).toBe('');
  });
});
