/**
 * Lightweight assert script: npx tsx packages/shared-dict/src/dict-options.spec.ts
 * (optional; not wired into CI)
 */
import { clearDictOverrides, dictOptions, dictLabel, isRuntimeDictLoaded, setDictOverrides } from './index.js';
function assert(cond, msg) {
    if (!cond)
        throw new Error(msg);
}
clearDictOverrides();
assert(!isRuntimeDictLoaded(), 'cleared => not loaded');
assert(dictOptions('route_code').length > 0, 'ops unmanaged/not-loaded uses DICT seed');
assert(dictOptions('order_status').length > 0, 'system uses DICT when not loaded');
setDictOverrides({ order_status: { PAID: '已付清' } }, { loaded: true });
assert(isRuntimeDictLoaded(), 'loaded flag');
assert(dictOptions('order_status').length === 1 && dictOptions('order_status')[0].label === '已付清', 'system non-empty override replaces');
assert(dictOptions('route_code').length === 0, 'ops-managed missing type => empty after load');
assert(dictLabel('route_code', 'R01') === '路线 R01', 'label still falls back to DICT');
setDictOverrides({ route_code: { R99: '新路线' }, order_status: {} }, { loaded: true });
assert(dictOptions('route_code').some((o) => o.value === 'R99'), 'ops uses runtime items');
assert(dictOptions('order_status').length > 1, 'system empty override falls back to DICT');
clearDictOverrides();
assert(dictOptions('route_code').length > 0, 'after clear ops seed returns');
console.log('dict-options.spec: ok');
