import { describe, expect, it } from 'vitest';
import { widevineCapabilityFacts, widevineCapabilityRows } from './widevine-capabilities-display';
import type { Json } from './types';

const query = { mimeType: 'video/mp4', securityLevel: 1, status: 'ok', supported: false };
const sample = { schemeUuid: 'edef8ba9-79d6-4ace-a3c8-27dcd51d21ed', scope: 'container-security-level-support', queryCount: 20, queryLimit: 20, entries: [query, { ...query, supported: true }, { ...query, securityLevel: 999 }] };

describe('Widevine容器与安全级别概览', () => {
  it('保留原序重复与false，只有数值1..5附具体级别名，原件不变', () => {
    const before = JSON.stringify(sample), rows = widevineCapabilityRows(sample, sample)!;
    expect(rows.map(row => row.key)).toEqual(['metadata', '0', '1', '2']);expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(widevineCapabilityFacts(rows[1].left)).toEqual(['媒体容器 "video/mp4"', '请求级别 1 · SW_SECURE_CRYPTO', '不支持（false）', '查询成功（ok）']);
    expect(widevineCapabilityFacts(rows[2].left)).toContain('支持（true）');expect(widevineCapabilityFacts(rows[3].left)[1]).toBe('请求级别 999');
    for (const [i, name] of ['SW_SECURE_CRYPTO', 'SW_SECURE_DECODE', 'HW_SECURE_CRYPTO', 'HW_SECURE_DECODE', 'HW_SECURE_ALL'].entries()) expect(widevineCapabilityFacts({ ...query, securityLevel: i + 1 })[1]).toBe(`请求级别 ${i + 1} · ${name}`);
    for (const code of [0, 6, -1, '1', null]) expect(widevineCapabilityFacts({ ...query, securityLevel: code })[1]).toBe(`请求级别 ${JSON.stringify(code)}`);
    expect(JSON.stringify(sample)).toBe(before);
  });
  it('按完整原对象比较未知字段与类型，元数据不随查询内容误标差异', () => {
    const variants: Json[] = [{ ...query, supported: 'false' }, { ...query, securityLevel: '1' }, { ...query, mimeType: 'audio/mp4' }, { ...query, status: 'error', supported: null }, { ...query, future: false }];
    for (const entry of variants) {
      const rows = widevineCapabilityRows(sample, { ...sample, entries: [entry, ...sample.entries.slice(1)] })!;
      expect(rows.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['0']);
    }
    const swapped = widevineCapabilityRows(sample, { ...sample, entries: [sample.entries[1], sample.entries[0], sample.entries[2]] })!;
    expect(swapped.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['0', '1']);
    const a = { ...sample, future: { value: false } }, b = { ...sample, future: { value: 'false' } };
    const rows = widevineCapabilityRows(a, b)!;expect(rows.filter(row => row.state !== 'same').map(row => row.key)).toEqual(['metadata']);
    expect(widevineCapabilityFacts(rows[0].left, true)).toEqual(widevineCapabilityFacts(rows[0].right, true));
    expect(widevineCapabilityFacts({ ...query, future: false })).toContain('附加字段 1 项 · 见完整字段');
    expect(widevineCapabilityFacts({ ...query, supported: 'false' })).toContain('返回值 "false"');
  });
  it('失败/旧系统/null/缺字段/空明细分开，不把非ok返回值解释为有效支持', () => {
    for (const status of ['unsupported', 'denied', 'error', '__proto__']) {
      const facts = widevineCapabilityFacts({ ...query, status, supported: null, errorClass: 'SYNTHETIC' });
      expect(facts[2]).toBe('返回值 null');expect(facts[3]).toContain(status);expect(facts).toContain('错误 "SYNTHETIC"');
      expect(widevineCapabilityFacts({ ...query, status, supported: false })[2]).toBe('返回值 false');
    }
    const { entries: _, ...missing } = sample;
    for (const [value, fact] of [[{ ...sample, entries: [] }, '查询明细为空 []'], [{ ...sample, entries: null }, '查询明细 null'], [missing, '查询明细未采集']] as [Json, string][]) expect(widevineCapabilityFacts(widevineCapabilityRows(value, sample)![0].left, true)).toContain(fact);
    expect(widevineCapabilityRows(missing, { ...missing, entries: null })![0].state).toBe('changed');
    expect(widevineCapabilityRows({ ...missing, entries: [] }, { ...missing, entries: null })![0].state).toBe('changed');
    expect(widevineCapabilityFacts({ ...missing, queryCount: 0, entries: 20 }, true)).toContain('实际尝试 0 · 上限 20');
  });
  it('单侧/短侧缺项保留，20条全量展示，复杂已知字段或超限回退', () => {
    expect(widevineCapabilityRows(undefined, undefined)).toEqual([]);
    expect(widevineCapabilityRows(undefined, sample)!.every(row => row.state === 'right-only')).toBe(true);
    expect(widevineCapabilityRows(sample, undefined)!.every(row => row.state === 'left-only')).toBe(true);
    expect(widevineCapabilityFacts(undefined)).toEqual(['此侧没有该查询']);
    expect(widevineCapabilityRows(sample, { ...sample, entries: [query] })!.slice(2).every(row => row.state === 'left-only')).toBe(true);
    expect(widevineCapabilityRows(sample, { ...sample, entries: Array(20).fill(query) })).toHaveLength(21);
    const invalid: Json[] = [null, [], false, { queryCount: {} }, { scope: [] }, { entries: {} }, { entries: [null] }, { entries: [false] }, { entries: [{ supported: {} }] }, { entries: [{ mimeType: [] }] }, { entries: [{ securityLevel: {} }] }, { entries: [{ status: [] }] }, { entries: Array(21).fill(query) }];
    for (const value of invalid) expect(widevineCapabilityRows(sample, value)).toBeUndefined();
    expect(widevineCapabilityRows(sample, { entries: [{}] })).toBeDefined();
  });
});
