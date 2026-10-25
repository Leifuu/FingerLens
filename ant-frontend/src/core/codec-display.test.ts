import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { codecDisplayRows } from './codec-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function grouped(left: Json[] | undefined, right?: Json[]) {
  const original = JSON.stringify([left, right]);
  const section = (entries: Json[]) => buildSections(fixtureItem('hardware/codecs', entries, { category: 'hardware' })).find(section => section.key === 'array');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const before = JSON.stringify(merged), rows = codecDisplayRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(before);
  expect(JSON.stringify([left, right])).toBe(original);
  return rows;
}

describe('编解码器概览', () => {
  it('按原始序号保留别名和重复项，不按规范名重新配对', () => {
    const entries = [{ name: 'c2.codec', encoder: false, canonicalName: 'c2.codec' }, { name: 'OMX.alias', encoder: false, canonicalName: 'c2.codec' }, { name: 'OMX.alias', encoder: false, canonicalName: 'c2.codec' }];
    const rows = grouped(entries, entries)!;
    expect(rows.map(row => [row.key, row.name.left, row.canonicalName?.left])).toEqual([['0', 'c2.codec', 'c2.codec'], ['1', 'OMX.alias', 'c2.codec'], ['2', 'OMX.alias', 'c2.codec']]);
    expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(grouped(entries, [entries[1], entries[0], entries[2]])!.map(row => row.state)).toEqual(['changed', 'changed', 'same']);
  });
  it('旧字段、空字符串、null、未知方向、重复 MIME 与单侧数据均保留原值', () => {
    const a = { name: '', encoder: 99, supportedTypes: ['video/unknown', 'video/unknown', '', null], canonicalName: null };
    const row = grouped([a], [a])![0];
    expect(row.name.left).toBe('');expect(row.encoder.left).toBe('99');expect(row.canonicalName?.left).toBe('null');
    expect(row.hardwareAccelerated).toBeUndefined();
    expect(row.supportedTypes.map(field => field.left)).toEqual(['video/unknown', 'video/unknown', '', 'null']);
    expect(grouped([{ name: null, encoder: false, supportedTypes: [] }])![0].supportedTypes[0].left).toBe('[]');
    expect(grouped([a])![0].state).toBe('left-only');
    expect(grouped(undefined, [a])![0].state).toBe('right-only');
    expect(grouped([a], [{ ...a, supportedTypes: ['video/unknown'] }])![0].state).toBe('changed');
  });
  it('深层 profile、未知字段或单侧新增字段变化都保留为整行差异', () => {
    const a = { name: 'codec', encoder: true, hardwareAccelerated: false, capabilities: [{ type: 'video/test', profileLevels: [{ profile: 1, level: 2 }] }], future: { flag: false } };
    expect(grouped([a], [{ ...a, capabilities: [{ type: 'video/test', profileLevels: [{ profile: 1, level: 3 }] }] }])![0].state).toBe('changed');
    expect(grouped([a], [{ ...a, future: { flag: true } }])![0].state).toBe('changed');
    expect(grouped([a], [{ ...a, added: null }])![0].state).toBe('changed');
  });
  it('MIME 特性的支持/必需变化和旧 JSON 缺项都影响概览，不推断未知值', () => {
    const old = { name: 'codec', encoder: false, capabilities: [{ type: 'video/test', profileLevels: [] }] };
    const current = { ...old, capabilities: [{ ...old.capabilities[0], featureFlags: { 'secure-playback': { status: 'ok', supported: true, required: false }, 'low-latency': { status: 'unsupported', supported: null, required: null } } }] };
    expect(grouped([old], [old])![0].state).toBe('same');
    expect(grouped([old], [current])![0].state).toBe('changed');
    expect(grouped([current], [current])![0].state).toBe('same');
    for (const feature of [{ status: 'ok', supported: true, required: true }, { status: 'ok', supported: false, required: false }, { status: 'error', supported: null, required: null }]) {
      const changed = { ...current, capabilities: [{ ...current.capabilities[0], featureFlags: { ...current.capabilities[0].featureFlags, 'secure-playback': feature } }] };
      expect(grouped([current], [changed])![0].state).toBe('changed');
    }
  });
  it('空数组、混合格式、复杂名称或任一侧缺少必要字段时回退原字段表', () => {
    expect(grouped([], [])).toBeUndefined();
    expect(grouped(['legacy'])).toBeUndefined();
    expect(grouped([{ name: 'codec' }])).toBeUndefined();
    expect(grouped([{ encoder: false }])).toBeUndefined();
    expect(grouped([{ name: { raw: 'codec' }, encoder: false }])).toBeUndefined();
    expect(grouped([{ name: 'codec', encoder: false }, null])).toBeUndefined();
    expect(grouped([{ name: 'codec', encoder: false }], [{ name: 'codec' }])).toBeUndefined();
  });
});
