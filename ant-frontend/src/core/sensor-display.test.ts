import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { sensorDisplayRows } from './sensor-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function grouped(left: Json[] | undefined, right?: Json[]) {
  const original = JSON.stringify([left, right]);
  const section = (entries: Json[]) => buildSections(fixtureItem('sensors/list', entries, { category: 'sensors' })).find(section => section.key === 'array');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const before = JSON.stringify(merged), rows = sensorDisplayRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(before);
  expect(JSON.stringify([left, right])).toBe(original);
  return rows;
}

describe('传感器概览', () => {
  it('按原始序号保留重复项，不按名称或类型重新配对', () => {
    const entries = [{ name: 'same', vendor: 'vendor', type: 1 }, { name: 'same', vendor: 'vendor', type: 2 }, { name: 'same', vendor: 'vendor', type: 2 }];
    const rows = grouped(entries, entries)!;
    expect(rows.map(row => [row.key, row.type.left])).toEqual([['0', '1'], ['1', '2'], ['2', '2']]);
    expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(grouped(entries, [entries[1], entries[0], entries[2]])!.map(row => row.state)).toEqual(['changed', 'changed', 'same']);
  });
  it('旧字段、未知类型、空字符串、null、零与单侧数据保留原值', () => {
    const a = { name: '', vendor: null, type: 65599, power: 0, reportingMode: 99, wakeUp: false };
    const row = grouped([a], [a])![0];
    expect(row.name.left).toBe('');expect(row.vendor.left).toBe('null');expect(row.type.left).toBe('65599');
    expect(row.power?.left).toBe('0');expect(row.reportingMode?.left).toBe('99');expect(row.wakeUp?.left).toBe('false');
    expect(row.dynamic).toBeUndefined();expect(row.stringType).toBeUndefined();
    expect(grouped([a])![0].state).toBe('left-only');
    expect(grouped(undefined, [a])![0].state).toBe('right-only');
    expect(grouped([a], [{ ...a, wakeUp: null }])![0].state).toBe('changed');
    expect(grouped([a], [{ ...a, dynamic: false }])![0].state).toBe('changed');
  });
  it('FIFO、直连能力、深层未知字段和单侧字段差异都影响整行', () => {
    const a = { name: 'sensor', vendor: 'vendor', type: 1, fifoMaxEventCount: 0, hardwareBufferDirectChannel: false, future: { entries: [1, 2] } };
    for (const changed of [{ ...a, fifoMaxEventCount: 10 }, { ...a, hardwareBufferDirectChannel: true }, { ...a, future: { entries: [1, 3] } }, { ...a, extra: null }]) {
      expect(grouped([a], [changed])![0].state).toBe('changed');
    }
  });
  it('空数组、混合格式、复杂名称和任一侧缺少必要字段时回退原表', () => {
    const valid = { name: 'sensor', vendor: 'vendor', type: 1 };
    expect(grouped([], [])).toBeUndefined();
    expect(grouped(['legacy'])).toBeUndefined();
    expect(grouped([{ name: 'sensor', type: 1 }])).toBeUndefined();
    expect(grouped([{ name: 'sensor', vendor: 'vendor' }])).toBeUndefined();
    expect(grouped([{ ...valid, name: { raw: 'sensor' } }])).toBeUndefined();
    expect(grouped([valid, null])).toBeUndefined();
    expect(grouped([valid], [{ name: 'sensor', type: 1 }])).toBeUndefined();
  });
});
