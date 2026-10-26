import { describe, expect, it } from 'vitest';
import { codecPerformancePointFacts, codecPerformancePointRows, codecPerformanceRows, codecPerformanceSummary } from './codec-performance-display';
import { codecDisplayRows } from './codec-display';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

const point = { description: 'PerformancePoint(1920x1088@120)', coversReferencePoints: { HD_30: true, HD_60: false, HD_120: null, FHD_30: true, UHD_30: false } };
const support = { status: 'ok', pointCount: 1, returnedCount: 1, pointLimit: 64, pointsTruncated: false, pointsSha256: 'hash-a', points: [point] };
const codec = (values: Json[]): Json => ({ name: 'codec', encoder: false, capabilities: values });
const cap = (value: Json = support, type: Json = 'video/test') => ({ type, performancePoints: value });

describe('编解码器性能点概览', () => {
  it('保留重复MIME和性能点原序，格式化不改写输入', () => {
    const left = codec([cap({ ...support, points: [point, point, { ...point, description: 'last' }] }), cap()]);
    const before = JSON.stringify(left), rows = codecPerformanceRows(left, left);
    expect(rows.map(row => row.key)).toEqual(['0', '1']);expect(rows.every(row => row.state === 'same')).toBe(true);
    const points = codecPerformancePointRows(rows[0]);expect(points.map(row => row.key)).toEqual(['0', '1', '2']);
    expect(points[0].left).toEqual(points[1].left);expect(points[2].left?.[0]).toBe('描述 "last"');
    expect(JSON.stringify(left)).toBe(before);
  });
  it('区分未发布/null、空数组、未采集、未知状态和原始数量类型', () => {
    expect(codecPerformanceRows(codec([{ type: 'video/test' }]), undefined)).toEqual([]);
    const rows = codecPerformanceRows(codec([{ type: 'video/test' }]), codec([cap()]));
    expect(rows[0].state).toBe('right-only');expect(codecPerformanceSummary(rows[0].left)).toBe('未采集');
    const missing = codecPerformanceSummary(cap({ status: 'not-published', points: null, pointCount: null, pointsTruncated: null }));
    expect(missing).toContain('未发布性能点信息（not-published） · points=null');expect(missing).toContain('总数 null');
    expect(codecPerformanceSummary(cap({ ...support, points: [], pointCount: 0 }))).toContain('空清单 []（未声明性能点保证）');
    for (const status of ['not-video', 'unsupported', 'denied', 'error', '__proto__']) expect(codecPerformanceSummary(cap({ status, points: null, errorClass: 'TestException' }))).toContain(status);
    const unknown = codecPerformanceSummary(cap({ status: false, pointCount: '1', returnedCount: '', pointsTruncated: 'false' }));
    expect(unknown).toContain('查询状态 false');expect(unknown).toContain('总数 "1"');expect(unknown).toContain('返回 （空字符串）');expect(unknown).toContain('截断 "false"');
  });
  it('九参考规格分别显示覆盖/不覆盖/未采集，保留字符串false和未知结构', () => {
    const facts = codecPerformancePointFacts(point)!;
    expect(facts[1]).toBe('HD 1280×720 · 30fps 覆盖 · 60fps 不覆盖 · 120fps null');
    expect(facts[2]).toContain('FHD 1920×1080');expect(facts[3]).toContain('UHD 3840×2160');expect(facts[3]).toContain('60fps 未采集');
    const typed = codecPerformancePointFacts({ ...point, description: '', coversReferencePoints: { HD_30: 'false', future: false } })!;
    expect(typed[0]).toBe('描述 （空字符串）');expect(typed[1]).toContain('30fps "false"');expect(typed[4]).toBe('其它参考点 1 项 · 见完整字段');
    expect(codecPerformancePointFacts({ description: null, coversReferencePoints: null })).toEqual(['描述 null', '参考点覆盖 null']);
  });
  it('完整哈希/未知字段/MIME类型/覆盖类型变化在所有概览层保留差异', () => {
    const left = codec([cap()]);
    for (const changed of [{ ...support, pointsSha256: 'hash-b' }, { ...support, future: false }, { ...support, points: [{ ...point, coversReferencePoints: { ...point.coversReferencePoints, HD_30: 'true' } }] }, { ...support, points: [{ ...point, future: false }] }]) {
      const row = codecPerformanceRows(left, codec([cap(changed)]))[0];expect(row.state).toBe('changed');
    }
    expect(codecPerformanceRows(codec([cap(support, false)]), codec([cap(support, 'false')]))[0].state).toBe('changed');
    const b = codec([cap({ ...support, points: [{ ...point, coversReferencePoints: { ...point.coversReferencePoints, HD_30: 'true' } }] })]);
    const sections = (value: Json) => buildSections(fixtureItem('hardware/codecs', [value])).find(section => section.key === 'array');
    const merged = mergeDisplaySections(sections(left), sections(b));
    expect(codecDisplayRows(merged.rows, [left], [b])![0].state).toBe('changed');
    const points = codecPerformancePointRows(codecPerformanceRows(left, b)[0]);expect(points[0].state).toBe('changed');expect(points[0].right?.[1]).toContain('30fps "true"');
  });
  it('单侧、重复描述、分页末点与截断外哈希分别保留，不从数量补造点', () => {
    const many = { ...support, status: 'partial', pointCount: 65, returnedCount: 64, pointsTruncated: true, points: Array.from({ length: 64 }, () => point) };
    const left = codec([cap(many)]), right = codec([cap({ ...many, pointsSha256: 'hash-b' })]);
    const rows = codecPerformanceRows(left, right);expect(rows[0].state).toBe('changed');
    expect(codecPerformanceSummary(rows[0].left)).toContain('总数 65 · 返回 64 · 上限 64');
    const points = codecPerformancePointRows(rows[0]);expect(points).toHaveLength(64);expect(points[63].key).toBe('63');expect(points.every(row => row.state === 'same')).toBe(true);
    const single = codecPerformancePointRows(codecPerformanceRows(undefined, left)[0]);expect(single[0].state).toBe('right-only');expect(single[0].left).toBeUndefined();
  });
  it('复杂MIME、异构性能点与超过边界的报告留给完整字段，正常兄弟项不丢失', () => {
    for (const bad of [cap(null), cap('legacy'), cap(support, { future: true }), cap({ ...support, points: 'legacy' }), cap({ ...support, points: [null] }), cap({ ...support, points: [{ description: {} }] }), cap({ ...support, points: Array.from({ length: 65 }, () => point) })]) {
      expect(codecPerformanceRows(codec([bad, cap()]), undefined).map(row => row.key)).toEqual(['1']);
    }
    expect(codecPerformanceRows(codec(Array.from({ length: 256 }, () => cap())), undefined)).toHaveLength(256);
    expect(codecPerformanceRows(codec(Array.from({ length: 257 }, () => cap())), undefined)).toEqual([]);
    const malicious = '<img src=x onerror=window.performancePointInjected=1>';
    expect(codecPerformancePointRows(codecPerformanceRows(codec([cap({ ...support, points: [{ ...point, description: malicious }] })]), undefined)[0])[0].left?.[0]).toContain(malicious);
  });
});
