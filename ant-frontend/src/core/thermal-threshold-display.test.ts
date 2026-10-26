import { describe, expect, it } from 'vitest';
import { thermalThresholdFacts, thermalThresholdRows } from './thermal-threshold-display';
import type { Json } from './types';

const sample = { managerAvailable: true, status: 'ok', entryCount: 3, entryLimit: 64, entries: [{ thermalStatus: 3, headroom: 1 }, { thermalStatus: 3, headroom: 0 }, { thermalStatus: 999, headroom: -0.5 }] };

describe('热余量阈值概览', () => {
  it('按原始序号保留重复、零和负数，已知整数才附名称且原件不变', () => {
    const before = JSON.stringify(sample), rows = thermalThresholdRows(sample, sample)!;
    expect(rows.map(row => row.key)).toEqual(['metadata', '0', '1', '2']);
    expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(thermalThresholdFacts(rows[1].left)).toEqual(['热状态 3 · SEVERE', '归一化阈值 1']);
    expect(thermalThresholdFacts(rows[2].left)[1]).toBe('归一化阈值 0');
    expect(thermalThresholdFacts(rows[3].left)).toEqual(['热状态 999', '归一化阈值 -0.5']);
    expect(thermalThresholdFacts({ thermalStatus: '3', headroom: '0' })).toEqual(['热状态 "3"', '归一化阈值 "0"']);
    for (const [code, label] of ['NONE', 'LIGHT', 'MODERATE', 'SEVERE', 'CRITICAL', 'EMERGENCY', 'SHUTDOWN'].entries()) expect(thermalThresholdFacts({ thermalStatus: code, headroom: 1 })[0]).toBe(`热状态 ${code} · ${label}`);
    expect(JSON.stringify(sample)).toBe(before);
  });
  it('完整条目/未知字段/原序与类型变化决定差异，元数据不被阈值内容误带动', () => {
    const changes: Json[] = [{ thermalStatus: 3, headroom: '1' }, { thermalStatus: 3, headroom: null }, { thermalStatus: '3', headroom: 1 }, { thermalStatus: 3, headroom: 1, future: false }];
    for (const entry of changes) {
      const rows = thermalThresholdRows(sample, { ...sample, entries: [entry, ...sample.entries.slice(1)] })!;
      expect(rows.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['0']);
    }
    const swapped = thermalThresholdRows(sample, { ...sample, entries: [sample.entries[1], sample.entries[0], sample.entries[2]] })!;
    expect(swapped.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['0', '1']);
    const a = { ...sample, future: { value: false } }, b = { ...sample, future: { value: 'false' } };
    expect(thermalThresholdRows(a, b)!.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['metadata']);
    expect(thermalThresholdFacts(thermalThresholdRows(a, b)![0].left, true)).toEqual(thermalThresholdFacts(thermalThresholdRows(a, b)![0].right, true));
    expect(thermalThresholdFacts({thermalStatus: 1, headroom: 0, future: false})).toContain('附加字段 1 项 · 见完整字段');
  });
  it('成功空映射、null/缺字段、拒绝和未知状态各自保留，非有限值不改成零', () => {
    const empty = { ...sample, entryCount: 0, entries: [] };
    expect(thermalThresholdFacts(thermalThresholdRows(empty, empty)![0].left, true)).toContain('明细为空 []');
    const { entries: _, ...missing } = sample;
    expect(thermalThresholdFacts(thermalThresholdRows(missing, missing)![0].left, true)).toContain('明细未采集');
    for (const status of ['unsupported', 'unavailable', 'denied', 'error', '__proto__']) {
      const data = { managerAvailable: null, status, entryCount: null, entries: null, errorClass: 'SYNTHETIC' };
      const facts = thermalThresholdFacts(thermalThresholdRows(data, data)![0].left, true);
      expect(facts[0]).toContain(status);expect(facts).toContain('明细 null');expect(facts).toContain('管理服务 null');expect(facts).toContain('错误 "SYNTHETIC"');
    }
    expect(thermalThresholdRows(empty, { ...empty, entries: null })![0].state).toBe('changed');
    expect(thermalThresholdRows(missing, { ...missing, entries: null })![0].state).toBe('changed');
    for (const headroom of [null, { $type: 'float', value: 'NaN' }, { $type: 'float', value: 'Infinity' }] as Json[]) expect(thermalThresholdFacts({ thermalStatus: 1, headroom })[1]).toBe(`归一化阈值 ${JSON.stringify(headroom)}`);
  });
  it('单侧及短侧缺项独立，64项全部保留，复杂旧结构或超限回退', () => {
    expect(thermalThresholdRows(undefined, undefined)).toEqual([]);
    expect(thermalThresholdRows(undefined, sample)!.every(row => row.state === 'right-only')).toBe(true);
    expect(thermalThresholdRows(sample, undefined)!.every(row => row.state === 'left-only')).toBe(true);
    expect(thermalThresholdFacts(undefined)).toEqual(['此侧没有该阈值']);
    const rows = thermalThresholdRows(sample, { ...sample, entries: sample.entries.slice(0, 1) })!;
    expect(rows.slice(2).every(row => row.state === 'left-only')).toBe(true);
    expect(thermalThresholdRows(sample, { ...sample, entries: Array(64).fill(sample.entries[0]) })).toHaveLength(65);
    const invalid: Json[] = [null, false, [], { status: {} }, { entries: {} }, { entries: [null] }, { entries: [{ thermalStatus: [] }] }, { entries: Array(65).fill({}) }];
    for (const value of invalid) expect(thermalThresholdRows(sample, value)).toBeUndefined();
    expect(thermalThresholdRows(sample, { ...sample, entries: [{}] })).toBeDefined();
  });
});
