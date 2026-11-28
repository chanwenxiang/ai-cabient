import { describe, expect, it } from 'vitest';
import source from './AddressPicker.vue?raw';

/**
 * 本仓 admin 单测环境是 node、无组件 DOM 夹具；这里对地址录入器做源码契约测试，
 * 重点钉住“缺 Key 只切换手动录入，不清地址、不发坐标事件”。
 */

function bodyOf(name: string): string {
  const start = source.indexOf(`function ${name}(`);
  expect(start, `缺少 ${name}`).toBeGreaterThanOrEqual(0);
  const open = source.indexOf('{', start);
  const end = source.indexOf('\n}', open);
  expect(end, `${name} 函数体无法定位`).toBeGreaterThan(open);
  return source.slice(open + 1, end);
}

describe('AddressPicker 缺 Key 降级契约', () => {
  it('手动降级不清空已有地址，也不发射任何坐标', () => {
    const body = bodyOf('useManualAddress');
    expect(body).toContain('options.value = []');
    expect(body).toContain('regionReady.value = false');
    expect(body).toContain('行政区划服务暂不可用，已切换为手动填写地址');
    expect(body).not.toContain("detail.value = ''");
    expect(body).not.toContain("emit('update:latitude'");
    expect(body).not.toContain("emit('update:longitude'");
  });

  it('行政区划请求失败和空列表都走手动降级', () => {
    expect(source).toContain('if (!regionReady.value) useManualAddress();');
    expect(source).toMatch(/catch\s*\{\s*useManualAddress\(\);\s*\}/);
  });

  it('解析坐标只在请求成功后写回，失败分支不发坐标事件', () => {
    const resolveStart = source.indexOf('async function resolve()');
    const resolveEnd = source.indexOf('\n}', resolveStart);
    const resolve = source.slice(resolveStart, resolveEnd);
    const success = resolve.slice(resolve.indexOf('try {'), resolve.indexOf('} catch (e)'));
    const failure = resolve.slice(resolve.indexOf('} catch (e)'));

    expect(success).toContain("emit('update:longitude', Number(data.longitude))");
    expect(success).toContain("emit('update:latitude', Number(data.latitude))");
    expect(failure).not.toContain("emit('update:latitude'");
    expect(failure).not.toContain("emit('update:longitude'");
    expect(failure).toContain("hint.value = errorMessage(e, '地址解析失败')");
  });
});
