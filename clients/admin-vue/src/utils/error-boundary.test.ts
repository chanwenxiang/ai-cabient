import { describe, expect, it } from 'vitest';
import { isFatalRenderInfo } from './error-boundary';

describe('isFatalRenderInfo（R4-A19）', () => {
  it('dev 构建的可读文案：setup/render 致命，事件/watcher/生命周期非致命', () => {
    expect(isFatalRenderInfo('setup function')).toBe(true);
    expect(isFatalRenderInfo('render function')).toBe(true);
    expect(isFatalRenderInfo('native event handler')).toBe(false);
    expect(isFatalRenderInfo('component event handler')).toBe(false);
    expect(isFatalRenderInfo('watcher callback')).toBe(false);
    expect(isFatalRenderInfo('lifecycle hook')).toBe(false);
  });

  it('生产构建的 #runtime-N URL：0=setup、1=render function 致命，其余非致命', () => {
    expect(isFatalRenderInfo('https://vuejs.org/error-reference/#runtime-0')).toBe(true);
    expect(isFatalRenderInfo('https://vuejs.org/error-reference/#runtime-1')).toBe(true);
    expect(isFatalRenderInfo('https://vuejs.org/error-reference/#runtime-5')).toBe(false);
    expect(isFatalRenderInfo('https://vuejs.org/error-reference/#runtime-6')).toBe(false);
    expect(isFatalRenderInfo('https://vuejs.org/error-reference/#runtime-14')).toBe(false);
  });

  it('不会把普通事件文案误判成致命（R4-A19 修复前的死分支防回归）', () => {
    // 修复前的正则含 'render' 与 'component render function' 两个永不出现的死分支；
    // 真实枚举只有 'render function' —— 这里钉住不因「更宽的匹配」误伤事件期
    expect(isFatalRenderInfo('render')).toBe(false);
    expect(isFatalRenderInfo('component render function')).toBe(false);
  });
});
