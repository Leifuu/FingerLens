import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { fixtureItem } from './fixtures';
import { tlsDisplayRows } from './tls-display';
import type { Json } from './types';

function grouped(left?: Json, right?: Json) {
  const section = (value: Json) => buildSections(fixtureItem('security/tls-parameters', value, { category: 'security' })).find(section => section.key === 'fields');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const before = JSON.stringify(merged), rows = tlsDisplayRows(merged.rows);
  expect(JSON.stringify(merged)).toBe(before);
  return rows;
}

const base = { providerName: 'provider', contextProtocol: 'Default', available: true, status: 'ok', entryLimit: 256,
  supportedProtocolsCount: 2, supportedProtocolsTruncated: false, supportedProtocolsSha256: 'a',
  defaultProtocolsCount: 2, defaultProtocolsTruncated: false, defaultProtocolsSha256: 'b',
  supportedCipherSuitesCount: 25, supportedCipherSuitesTruncated: false, supportedCipherSuitesSha256: 'c',
  defaultCipherSuitesCount: 18, defaultCipherSuitesTruncated: false, defaultCipherSuitesSha256: 'd' };

describe('默认 TLS 摘要概览', () => {
  it('将17个摘要字段压缩为9行并保留原值与数量/截断信息', () => {
    const rows = grouped(base, base)!;
    expect(rows).toHaveLength(9);
    expect(rows.find(row => row.label === '提供者')?.left).toBe('provider');
    expect(rows.find(row => row.key === 'supportedCipherSuites')?.left).toBe('数量 25 · 截断 否');
    expect(rows.every(row => row.state === 'same')).toBe(true);
  });
  it('数量相同但哈希、截断或未来元数据变化仍形成差异', () => {
    const changed = grouped(base, { ...base, supportedCipherSuitesSha256: 'future' })!.filter(row => row.state !== 'same');
    expect(changed.map(row => row.key)).toEqual(['supportedCipherSuites']);
    expect(changed[0].left).toBe(changed[0].right);
    expect(grouped(base, { ...base, defaultProtocolsTruncated: true })!.find(row => row.key === 'defaultProtocols')?.state).toBe('changed');
    const unknown = grouped({ ...base, future: { flag: false } }, { ...base, future: { flag: true } })!;
    expect(unknown.find(row => row.key === 'other')?.state).toBe('changed');
    expect(unknown.find(row => row.key === 'other')?.left).toBe('1 个字段（展开查看）');
  });
  it('单侧缺项、空字符串、null、缺失和未知截断值保持区别', () => {
    expect(grouped(undefined, base)!.every(row => row.state === 'right-only')).toBe(true);
    expect(grouped(base)!.every(row => row.state === 'left-only')).toBe(true);
    const rows = grouped({ ...base, providerName: '', contextProtocol: null, defaultProtocolsCount: 0, defaultProtocolsTruncated: null },
      { ...base, defaultProtocolsCount: null, defaultProtocolsTruncated: 'future' })!;
    expect(rows.find(row => row.label === '提供者')?.left).toBe('');
    expect(rows.find(row => row.label === '上下文协议名')?.left).toBe('null');
    expect(rows.find(row => row.key === 'defaultProtocols')?.left).toBe('数量 0 · 截断 null');
    expect(rows.find(row => row.key === 'defaultProtocols')?.right).toBe('数量 null · 截断 future');
    const missing = { ...base } as Record<string, Json>;delete missing.defaultProtocolsCount;delete missing.defaultProtocolsTruncated;
    expect(grouped(missing, base)!.find(row => row.key === 'defaultProtocols')?.left).toBe('数量 未知 · 截断 未知');
  });
  it('旧结构缺少上下文或提供者、复杂身份字段与空值回退原字段表', () => {
    for (const value of [null, [], {}]) expect(grouped(value)).toBeUndefined();
    const legacy: Json[] = [{ status: 'error' }, { providerName: 'test' }, { ...base, providerName: { name: 'test' } }];
    for (const value of legacy) expect(grouped(value, base)).toBeUndefined();
    expect(grouped(base, { contextProtocol: 'Default' })).toBeUndefined();
  });
});
