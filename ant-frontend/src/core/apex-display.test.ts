import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { apexDisplayRows } from './apex-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function grouped(left: Json[] | undefined, right?: Json[]) {
  const section = (packages: Json[]) => buildSections(fixtureItem('build/apex-packages', { packages }, { category: 'build' })).find(section => section.key === 'packages');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const before = JSON.stringify(merged), rows = apexDisplayRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(before);
  return rows;
}

const code = (value: string) => ({ $type: 'int64', value });
const base = { packageName: 'actual.apex', versionName: null, versionCode: code('9007199254740992') };

describe('APEX 包概览', () => {
  it('按原始序号保留重复包、精确int64和版本名原文', () => {
    const entries = [base, { ...base, versionName: '', versionCode: code('9223372036854775807') }, base];
    const rows = grouped(entries, entries)!;
    expect(rows.map(row => [row.key, row.packageName.left])).toEqual([['0', 'actual.apex'], ['1', 'actual.apex'], ['2', 'actual.apex']]);
    expect(rows[1].versionCode.left).toBe('9223372036854775807');
    expect(rows[0].versionName?.left).toBe('null');
    expect(rows[1].versionName?.left).toBe('');
    expect(rows.every(row => row.state === 'same')).toBe(true);
  });
  it('相邻大整数、未知字段和版本名缺失变化均形成整行差异', () => {
    expect(grouped([base], [{ ...base, versionCode: code('9007199254740993') }])![0].state).toBe('changed');
    expect(grouped([{ ...base, future: { flag: false } }], [{ ...base, future: { flag: true } }])![0].state).toBe('changed');
    expect(grouped([base], [{ ...base, future: 0 }])![0].state).toBe('changed');
    const rows = grouped([base], [{ packageName: base.packageName, versionCode: base.versionCode }])!;
    expect(rows[0].state).toBe('changed');expect(rows[0].versionName?.left).toBe('null');expect(rows[0].versionName?.right).toBeNull();
  });
  it('单侧、null、空字符串与数组顺序保持区别', () => {
    expect(grouped([base])![0].state).toBe('left-only');
    expect(grouped(undefined, [base])![0].state).toBe('right-only');
    const rows = grouped([{ ...base, packageName: null, versionCode: null }], [{ ...base, packageName: '', versionCode: '' }])!;
    expect(rows[0].packageName.left).toBe('null');expect(rows[0].packageName.right).toBe('');
    expect(rows[0].versionCode.left).toBe('null');expect(rows[0].versionCode.right).toBe('');
    const other = { ...base, packageName: 'other.apex' };
    expect(grouped([base, other], [other, base])!.every(row => row.state === 'changed')).toBe(true);
  });
  it('空集合、旧结构、混合类型或任一侧缺包名/版本号时回退完整字段', () => {
    expect(grouped([], [])).toBeUndefined();
    const legacy: Json[] = [null, 'raw', {}, { packageName: 'old.apex' }, { versionCode: 1 }, { ...base, packageName: { future: 'nested' } }];
    for (const value of legacy) {
      expect(grouped([value])).toBeUndefined();
      expect(grouped([value], [base])).toBeUndefined();
    }
    expect(grouped([base, { legacyOnly: true }])).toBeUndefined();
    expect(grouped([base], [])).toBeUndefined();
  });
});
