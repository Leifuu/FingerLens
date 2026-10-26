import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { displayModeRows } from './display-mode-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

const mode = { modeId: 1, physicalWidth: 1080, physicalHeight: 2400, refreshRate: 60 };
function grouped(left: Json[] | undefined, right?: Json[]) {
  const original = JSON.stringify([left, right]);
  const section = (modes: Json[]) => buildSections(fixtureItem('display/0', { supportedModes: modes }, { category: 'display' })).find(section => section.key === 'modes');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const before = JSON.stringify(merged), rows = displayModeRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(before);
  expect(JSON.stringify([left, right])).toBe(original);
  return rows;
}

describe('显示模式概览', () => {
  it('按原始序号保留重复ID/分辨率，不重新配对且保留原始刷新率', () => {
    const modes = [mode, { ...mode, refreshRate: 59.940059 }, mode];
    const rows = grouped(modes, modes)!;
    expect(rows.map(row => [row.key, row.modeId.left, row.refreshRate.left])).toEqual([['0', '1', '60'], ['1', '1', '59.940059'], ['2', '1', '60']]);
    expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(grouped(modes, [modes[1], modes[0], modes[2]])!.map(row => row.state)).toEqual(['changed', 'changed', 'same']);
  });
  it('旧JSON不猜HDR，区分单侧、null、空字符串、空数组和未来值', () => {
    const old = { ...mode, modeId: '', physicalWidth: null, physicalHeight: 0 };
    const row = grouped([old], [old])![0];
    expect(row.modeId.left).toBe('');expect(row.width.left).toBe('null');expect(row.height.left).toBe('0');expect(row.hdr.left).toBe('未采集');
    expect(grouped([old])![0]).toMatchObject({ state: 'left-only', hdr: { right: '—' } });
    expect(grouped(undefined, [old])![0].state).toBe('right-only');
    expect(grouped([{ ...mode, hdrTypeSupport: null }])![0].hdr.left).toBe('原始值 null');
    expect(grouped([{ ...mode, hdrTypeSupport: false }])![0].hdr.left).toBe('原始值 false');
    expect(grouped([{ ...mode, hdrTypeSupport: { status: 'ok', types: [] } }])![0].hdr.left).toBe('成功 · 类型 []');
    expect(grouped([{ ...mode, hdrTypeSupport: { status: 'unsupported', types: null } }])![0].hdr.left).toBe('系统不支持查询 · 类型 null');
    expect(grouped([{ ...mode, hdrTypeSupport: { status: '', types: '' } }])![0].hdr.left).toBe('（空字符串） · 类型 （空字符串）');
    expect(grouped([{ ...mode, hdrTypeSupport: { status: 'constructor', types: ['__proto__', 999] } }])![0].hdr.left).toBe('constructor · 类型 __proto__、999');
    expect(grouped([{ ...mode, hdrTypeSupportFake: { types: [2] } }])![0].hdr.left).toBe('未采集');
    expect(grouped([{ ...mode, hdrTypeSupport: { status: 'ok', types: { future: 0 } } }])![0].hdr.left).toBe('成功 · 类型 其他结构（详见完整字段）');
  });
  it('HDR名称附原始整数，保留重复，长列表只在概览限8项且明确截断', () => {
    const hdr = { status: 'partial', types: [2, 3, 4, 2, 999, 1, 0, -1, 99], typeCount: 65, returnedCount: 64, typesTruncated: true };
    const text = grouped([{ ...mode, hdrTypeSupport: hdr }])![0].hdr.left;
    expect(text).toBe('部分返回 · 类型 HDR10 (2)、HLG (3)、HDR10+ (4)、HDR10 (2)、999、Dolby Vision (1)、0、-1…（仅列前8项） · 数量 65 · 已截断（返回 64 项）');
    expect(grouped([{ ...mode, hdrTypeSupport: { status: 'error', types: null, typesTruncated: 'future' } }])![0].hdr.left).toContain('读取异常 · 类型 null · 截断状态 future');
  });
  it('完整哈希、未知字段和单侧字段变化都决定整行差异', () => {
    const original = { ...mode, hdrTypeSupport: { status: 'ok', types: [2], typeListSha256: 'a' }, future: { flag: false } };
    for (const changed of [{ ...original, hdrTypeSupport: { ...original.hdrTypeSupport, typeListSha256: 'b' } }, { ...original, future: { flag: true } }, { ...original, added: null }]) {
      expect(grouped([original], [changed])![0].state).toBe('changed');
    }
  });
  it('缺少基础模式字段、混合数组或复杂基础值时回退完整字段', () => {
    expect(grouped([], [])).toBeUndefined();expect(grouped(['legacy'])).toBeUndefined();expect(grouped([mode, null])).toBeUndefined();
    expect(grouped([{ modeId: 1, physicalWidth: 1080 }])).toBeUndefined();
    expect(grouped([{ ...mode, physicalWidth: { raw: 1080 } }])).toBeUndefined();
    expect(grouped([mode], [{ modeId: 1, physicalHeight: 2400, refreshRate: 60 }])).toBeUndefined();
  });
});
