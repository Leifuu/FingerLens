import { describe, expect, it } from 'vitest';
import { securityStateRows, securityStateText } from './security-state-display';
import type { Json } from './types';

function rows(left: Json | undefined, right?: Json) {
  const original = JSON.stringify([left, right]);
  const result = securityStateRows(left, right);
  expect(JSON.stringify([left, right])).toBe(original);
  return result;
}
const state = (entries: Json[]) => ({ available: true, status: 'ok', entries });

describe('平台补丁与组件版本概览', () => {
  it('按原始键及重复序号配对，保留两侧位置、原文及未知键', () => {
    const a = state([{ key: 'system_spl', value: '2026-03-05' }, { key: 'future.package', value: '1.0.0.0' }, { key: 'future.package', value: '0' }]);
    const b = state([a.entries[1], a.entries[0], a.entries[2]]);
    expect(rows(a, b)!.map(row => [row.name, row.left?.index, row.right?.index, row.state])).toEqual([['system_spl', 0, 1, 'changed'], ['future.package', 1, 0, 'changed'], ['future.package', 2, 2, 'same']]);
    expect(rows(a, a)!.map(row => row.state)).toEqual(['same', 'same', 'same']);
    expect(rows(a, a)![1].left?.value).toBe('1.0.0.0');
    expect(rows(a, a)![0].label).toBe('系统安全补丁');
    expect(new Set(rows(a, a)!.map(row => row.key)).size).toBe(3);
  });
  it('区分空字符串、null、未返回、缺失条目和有效空集合', () => {
    const a = state([{ key: '', value: '' }, { key: 'null', value: null }]);
    expect(rows(a, state([]))!.map(row => row.state)).toEqual(['left-only', 'left-only']);
    expect(rows(undefined, a)![0].state).toBe('right-only');
    expect(rows(a)![1].left?.value).toBeNull();
    expect(rows(state([]), state([]))).toEqual([]);
    expect(securityStateText('')).toBe('（空字符串）');
    expect(securityStateText(null)).toBe('null（空值）');
    expect(securityStateText('null')).toBe('null');
    expect(rows(a, state([{ key: '', value: '（空字符串）' }, { key: 'null', value: 'null' }]))!.map(row => row.state)).toEqual(['changed', 'changed']);
  });
  it('完整条目的未知字段/类型/缺项仍决定差异，不由展示文本判等', () => {
    const a = state([{ key: '__proto__', value: 'v', future: { flag: false } }]);
    const variants: Json[] = [{ key: '__proto__', value: 'v', future: { flag: true } }, { key: '__proto__', value: 'v', future: false }, { key: '__proto__', value: 'v' }];
    for (const entry of variants) expect(rows(a, state([entry]))![0].state).toBe('changed');
    expect(rows(a, state([{ future: { flag: false }, value: 'v', key: '__proto__' }]))![0].state).toBe('same');
    expect(rows(state([{ key: 'constructor', value: '<script>中文\n  空白\u0000</script>' }]))![0].label).toBe('组件版本');
  });
  it('旧格式、服务缺失/失败、错类型或超过128项回退完整字段', () => {
    const valid = state([{ key: 'system_spl', value: '2026-03-05' }]);
    const invalid: Json[] = [null, false, [], {}, { available: false, entries: null }, state([null]), state([{ key: 1, value: 'v' }]), state([{ key: 'x' }]), state([{ key: 'x', value: false }]), state([{ key: 'x', value: 1 }]), state([{ key: 'x', value: {} }]), state(Array.from({ length: 129 }, () => ({ key: 'x', value: 'v' })))];
    for (const value of invalid) expect(rows(value, valid)).toBeUndefined();
    expect(rows(undefined, undefined)).toBeUndefined();
    expect(rows(state(Array.from({ length: 128 }, () => ({ key: 'x', value: 'v' }))))).toHaveLength(128);
  });
  it('单侧新增键保留在原侧顺序，不覆盖特殊键或重复键', () => {
    const result = rows(state([{ key: 'constructor', value: 'a' }, { key: '__proto__', value: 'b' }]), state([{ key: '__proto__', value: 'b' }, { key: 'extra', value: 'c' }, { key: 'extra', value: 'd' }]))!;
    expect(result.map(row => [row.name, row.state])).toEqual([['constructor', 'left-only'], ['__proto__', 'changed'], ['extra', 'right-only'], ['extra', 'right-only']]);
  });
});
