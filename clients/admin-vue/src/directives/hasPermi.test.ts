/**
 * `v-hasPermi` 回归测试。
 *
 * 背景（2026-09-24 真机取证）：旧实现会在首次挂载时快照 `el.disabled`，之后每次 sync 写回快照值。
 * 而 `:disabled` 表达式的依赖常常是异步加载的（设备生命周期状态等），首次挂载时求值为 true，
 * 等依赖就绪、组件已把可用态渲染出来，指令又把 `disabled=true` 写回去 ⇒ 按钮被永久钉死。
 * 设备详情页 DEPLOYED 状态下「解绑 / 撤回未投放 / 返厂 / 退役」四个按钮就因此点不动。
 *
 * 因此这里最核心的一条是：**指令不得去写 `disabled`**。
 *
 * store 用 mock：真实 `@/stores/auth` 会经 `@/api/client` 拉进 workspace 包
 * `@aicabinet/shared-api`，vitest 的 resolve 里没有它。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { nextTick, type ObjectDirective } from 'vue';

vi.mock('@/stores/auth', async () => {
  const { ref, reactive } = await import('vue');
  const permissions = ref<string[]>([]);
  const store = reactive({
    get permissions() {
      return permissions.value;
    },
    hasPerm: (code: string) => permissions.value.includes(code),
    __setPermissions(next: string[]) {
      permissions.value = next;
    }
  });
  return { useAuthStore: () => store };
});

const { default: hasPermi } = await import('./hasPermi');
const { useAuthStore } = await import('@/stores/auth');
const auth = useAuthStore() as unknown as { __setPermissions: (p: string[]) => void };

type AnyEl = ReturnType<typeof makeEl>;

/** 最小 DOM 替身：只实现指令真正用到的那几个接口（vitest 环境是 node，没有 document）。 */
function makeEl(initial: { disabled?: boolean } = {}) {
  const listeners = new Map<string, Set<EventListener>>();
  const attrs = new Map<string, string>();
  const removeCalls: string[] = [];
  return {
    style: { display: '', pointerEvents: '' } as { display: string; pointerEvents: string },
    disabled: initial.disabled ?? false,
    removeCalls,
    setAttribute(k: string, v: string) {
      attrs.set(k, String(v));
    },
    removeAttribute(k: string) {
      attrs.delete(k);
      removeCalls.push(k);
    },
    getAttribute(k: string) {
      return attrs.has(k) ? (attrs.get(k) as string) : null;
    },
    addEventListener(type: string, fn: EventListener) {
      if (!listeners.has(type)) listeners.set(type, new Set());
      listeners.get(type)?.add(fn);
    },
    removeEventListener(type: string, fn: EventListener) {
      listeners.get(type)?.delete(fn);
    },
    listenerCount(type: string) {
      return listeners.get(type)?.size ?? 0;
    },
    /** 模拟一次 click：返回业务监听器是否真的收到事件。 */
    dispatchClick() {
      let reached = true;
      const ev = {
        stopImmediatePropagation() {
          reached = false;
        },
        preventDefault() {}
      } as unknown as Event;
      for (const fn of [...(listeners.get('click') ?? [])]) fn(ev);
      return reached;
    }
  };
}

// `Directive` 是 ObjectDirective | FunctionDirective 的联合，联合上取不到 `mounted`，先收窄。
const dir = hasPermi as ObjectDirective<AnyEl, string | string[]>;
const call = (hook: 'mounted' | 'updated', el: AnyEl, codes: string | string[]) =>
  dir[hook]?.(el as never, { value: codes } as never, null as never, null as never);
const mount = (el: AnyEl, codes: string | string[]) => call('mounted', el, codes);
const update = (el: AnyEl, codes: string | string[]) => call('updated', el, codes);

describe('v-hasPermi', () => {
  beforeEach(() => {
    auth.__setPermissions(['ops:device:edit']);
  });

  it('有权限时绝不改写 disabled（回归：异步 :disabled 从 true 变 false 后必须保持 false）', async () => {
    // 首次挂载：`:disabled` 的依赖（如异步加载的设备状态）尚未就绪 ⇒ 此刻为 true
    const el = makeEl({ disabled: true });
    mount(el, ['ops:device:edit']);
    expect(el.disabled, '指令不得介入 disabled —— 它是模板/组件的职责').toBe(true);

    // 依赖到位，组件把可用态渲染出来
    el.disabled = false;
    update(el, ['ops:device:edit']);
    expect(el.disabled, '更新后仍必须是组件给的值 false（旧实现会写回 true）').toBe(false);

    // 再多走几次 updated，也不允许被写回
    update(el, ['ops:device:edit']);
    await nextTick();
    expect(el.disabled).toBe(false);
  });

  it('有权限时不隐藏、不加 tabindex=-1', () => {
    const el = makeEl();
    mount(el, ['ops:device:edit']);
    expect(el.style.display).toBe('');
    expect(el.style.pointerEvents).toBe('');
    expect(el.getAttribute('aria-hidden')).toBe('false');
    expect(el.removeCalls).toContain('tabindex');
  });

  it('无权限时隐藏、置 aria-hidden、去 tabindex，并在捕获阶段拦掉 click', () => {
    auth.__setPermissions([]);
    const el = makeEl();
    mount(el, ['ops:device:edit']);
    expect(el.style.display).toBe('none');
    expect(el.style.pointerEvents).toBe('none');
    expect(el.getAttribute('aria-hidden')).toBe('true');
    expect(el.getAttribute('tabindex')).toBe('-1');
    expect(el.listenerCount('click'), '必须挂上捕获守卫，挡住脚本 click').toBe(1);
    expect(el.dispatchClick(), '无权限时 click 不得抵达业务监听器').toBe(false);
  });

  it('权限恢复后解除守卫、恢复显隐', async () => {
    auth.__setPermissions([]);
    const el = makeEl();
    mount(el, ['ops:device:edit']);
    expect(el.listenerCount('click')).toBe(1);

    auth.__setPermissions(['ops:device:edit']);
    await nextTick();
    expect(el.style.display).toBe('');
    expect(el.listenerCount('click'), '恢复权限后必须撤掉守卫').toBe(0);
    expect(el.dispatchClick()).toBe(true);
  });

  it('空权限码不生效（避免 hasPerm(undefined) 误放行）', () => {
    const el = makeEl();
    mount(el, []);
    expect(el.style.display).toBe('');
    expect(el.listenerCount('click')).toBe(0);
  });
});
