import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { rootPathDisplayRows } from './root-path-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function grouped(left: Json[] | undefined, right?: Json[]) {
  const section = (entries: Json[]) => buildSections(fixtureItem('security/root-path-summary', { entries }, { category: 'security' })).find(section => section.key === 'entries');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const original = JSON.stringify(merged);
  const rows = rootPathDisplayRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(original);
  return rows;
}

describe('Root 路径概览', () => {
  it('按原始序号压缩字段，保留中文状态、errno 与重复路径', () => {
    const entries = [{ path: '/su', status: 'missing', errno: 2, type: null }, { path: '/su', status: 'denied', errno: 13, type: null }];
    const rows = grouped(entries, entries)!;
    expect(rows).toHaveLength(2);
    expect(rows.map(row => row.key)).toEqual(['0', '1']);
    expect(rows[0].status?.left).toBe('路径不存在');
    expect(rows[1].errno?.right).toBe('13');
    expect(rows[0].type?.left).toBe('null');
    expect(rows.every(row => row.state === 'same')).toBe(true);
  });
  it('保留单侧缺项和路径顺序变化，不按路径重排', () => {
    expect(grouped([{ path: '/a', status: 'present' }])![0].state).toBe('left-only');
    expect(grouped(undefined, [{ path: '/a', status: 'present' }])![0].state).toBe('right-only');
    const rows = grouped([{ path: '/a' }, { path: '/b' }], [{ path: '/b' }, { path: '/a' }])!;
    expect(rows[0].path.left).toBe('/a');
    expect(rows[0].path.right).toBe('/b');
    expect(rows.every(row => row.state === 'changed')).toBe(true);
  });
  it('未知字段差异也影响整行状态，缺字段与 null 保持区分', () => {
    const rows = grouped([{ path: '/a', status: 'future-status', type: null, future: { flag: false } }], [{ path: '/a', status: 'future-status', future: { flag: true } }])!;
    expect(rows[0].state).toBe('changed');
    expect(rows[0].status?.left).toBe('future-status');
    expect(rows[0].type?.left).toBe('null');
    expect(rows[0].type?.right).toBeNull();
  });
  it('空数组、非对象条目或缺少 path 时退回原字段视图', () => {
    expect(grouped([], [])).toBeUndefined();
    expect(grouped(['raw'])).toBeUndefined();
    expect(grouped([{ status: 'missing' }])).toBeUndefined();
  });
});
