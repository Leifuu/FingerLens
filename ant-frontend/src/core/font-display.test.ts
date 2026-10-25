import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { fontDisplayRows } from './font-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function grouped(left: Json[] | undefined, right?: Json[]) {
  const section = (entries: Json[]) => buildSections(fixtureItem('environment/system-fonts', { entries }, { category: 'environment' })).find(section => section.key === 'entries');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const original = JSON.stringify(merged), rows = fontDisplayRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(original);
  return rows;
}

describe('系统字体概览', () => {
  it('按原始序号保留同文件不同 TTC、重复元数据和原始值', () => {
    const entries = [{ fileName: 'font.ttc', ttcIndex: 0, weight: 400, slant: 0 }, { fileName: 'font.ttc', ttcIndex: 1, weight: 700, slant: 1 }, { fileName: 'font.ttc', ttcIndex: 1, weight: 700, slant: 1 }];
    const rows = grouped(entries, entries)!;
    expect(rows.map(row => [row.key, row.fileName.left, row.ttcIndex.left])).toEqual([['0', 'font.ttc', '0'], ['1', 'font.ttc', '1'], ['2', 'font.ttc', '1']]);
    expect(rows[1].weight?.left).toBe('700');
    expect(rows.every(row => row.state === 'same')).toBe(true);
  });
  it('文件名为空值、空字符串与单侧缺项保持区分，不重排数组', () => {
    const rows = grouped([{ fileName: null, ttcIndex: 0, localeTags: '' }], [{ fileName: 'font.ttf', ttcIndex: 0 }])!;
    expect(rows[0].fileName.left).toBe('null');
    expect(rows[0].localeTags?.left).toBe('');
    expect(rows[0].localeTags?.right).toBeNull();
    expect(rows[0].state).toBe('changed');
    expect(grouped([{ fileName: null, ttcIndex: 0 }])![0].state).toBe('left-only');
    expect(grouped(undefined, [{ fileName: null, ttcIndex: 0 }])![0].state).toBe('right-only');
    expect(grouped([{ fileName: 'a', ttcIndex: 0 }, { fileName: 'b', ttcIndex: 0 }], [{ fileName: 'b', ttcIndex: 0 }, { fileName: 'a', ttcIndex: 0 }])!.every(row => row.state === 'changed')).toBe(true);
  });
  it('未知字段、变体或单侧字段变化仍使整行成为差异', () => {
    const a = { fileName: 'font.ttf', ttcIndex: 0, variationSettings: '', slant: 99, future: { flag: false } };
    expect(grouped([a], [{ ...a, future: { flag: true } }])![0].state).toBe('changed');
    expect(grouped([a], [{ ...a, variationSettings: "'wght' 400" }])![0].state).toBe('changed');
    expect(grouped([a], [{ ...a, added: 0 }])![0].state).toBe('changed');
    expect(grouped([a], [a])![0].slant?.left).toBe('99');
  });
  it('空数组、混合类型、缺文件名或 TTC 时退回原字段视图', () => {
    expect(grouped([], [])).toBeUndefined();
    expect(grouped(['raw'])).toBeUndefined();
    expect(grouped([{ fileName: 'font.ttf' }])).toBeUndefined();
    expect(grouped([{ ttcIndex: 0 }])).toBeUndefined();
    expect(grouped([{ fileName: 'font.ttf', ttcIndex: 0 }, { legacy: true }])).toBeUndefined();
  });
});
