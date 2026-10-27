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
  it('编解码器安全模型保留原值、名称、状态并参与差异', () => {
    const base = { name: 'codec', encoder: false, securityModel: 0, securityModelName: 'sandboxed', securityModelStatus: 'ok', capabilities: [{ type: 'video/test' }] };
    const row = grouped([base], [base])![0];
    expect(row.securityModel?.left).toBe('0');expect(row.securityModelName?.left).toBe('sandboxed');expect(row.securityModelStatus?.left).toBe('成功');expect(row.state).toBe('same');
    expect(grouped([base], [{ ...base, securityModel: 1, securityModelName: 'memory-safe' }])![0].state).toBe('changed');
    expect(grouped([{ name: 'old', encoder: false, capabilities: [] }])![0].securityModel).toBeUndefined();
  });
  it('编码器范围和码率模式参与整行差异，保留0值和未知状态', () => {
    const support = { available: true, status: 'ok', complexity: { status: 'ok', range: { lower: 0, upper: 0 } }, quality: { status: 'ok', range: { lower: 0, upper: 0 } }, bitrateModes: { CQ: { mode: 0, status: 'ok', supported: false } } };
    const codec = (encoderCapabilities?: Json) => ({ name: 'encoder', encoder: true, capabilities: [{ type: 'video/test', ...(encoderCapabilities === undefined ? {} : { encoderCapabilities }) }] });
    expect(grouped([codec()], [codec(support)])![0].state).toBe('changed');
    expect(grouped([codec(support)], [codec(support)])![0].state).toBe('same');
    for (const changed of [{ ...support, complexity: { status: 'ok', range: { lower: 0, upper: 1 } } }, { ...support, quality: { status: 'unsupported', range: null } }, { ...support, bitrateModes: { CQ: { mode: 0, status: 'error', supported: null } } }, { ...support, future: false }]) {
      expect(grouped([codec(support)], [codec(changed)])![0].state).toBe('changed');
    }
  });
  it('声道区间保留间隙和重复，区间/哈希/未来字段变化影响整行差异', () => {
    const support = { available: true, status: 'ok', minInputChannelCount: 2, ranges: [{ lower: 2, upper: 2 }, { lower: 6, upper: 8 }, { lower: 6, upper: 8 }], rangesSha256: 'a' };
    const codec = (inputChannelSupport: Json) => ({ name: 'codec', encoder: false, capabilities: [{ type: 'audio/test', audioCapabilities: { maxInputChannelCount: 8, inputChannelSupport } }] });
    expect(grouped([codec(support)], [codec(support)])![0].state).toBe('same');
    for (const changed of [{ ...support, rangesSha256: 'b' }, { ...support, minInputChannelCount: 1 }, { ...support, ranges: [{ lower: 2, upper: 8 }] }, { ...support, future: false }]) {
      expect(grouped([codec(support)], [codec(changed)])![0].state).toBe('changed');
    }
  });
  it('音频区间、null采样率和完整集合哈希变化参与概览差异', () => {
    const audio = { available: true, status: 'ok', sampleRateMode: 'ranges', sampleRatesHz: null, sampleRateRangesHz: [{ lower: 8000, upper: 48000 }], sampleRateRangesSha256: 'a', maxInputChannelCount: 2 };
    const codec = (audioCapabilities: Json) => ({ name: 'codec', encoder: false, capabilities: [{ type: 'audio/test', audioCapabilities }] });
    expect(grouped([codec(audio)], [codec(audio)])![0].state).toBe('same');
    for (const changed of [{ ...audio, sampleRateRangesSha256: 'b' }, { ...audio, sampleRatesHz: [] }, { ...audio, maxInputChannelCount: 1 }, { ...audio, future: false }]) {
      expect(grouped([codec(audio)], [codec(changed)])![0].state).toBe('changed');
    }
  });
  it('视频范围、对齐要求和未来字段变化参与整行差异，旧JSON缺新增对象仍可查看', () => {
    const old = { name: 'codec', encoder: false, capabilities: [{ type: 'video/test', profileLevels: [] }] };
    const video = { available: true, status: 'ok', widthPixels: { lower: 2, upper: 4096 }, widthAlignmentPixels: 2, future: null };
    const current = (videoCapabilities: Json) => ({ ...old, capabilities: [{ ...old.capabilities[0], videoCapabilities }] });
    expect(grouped([old], [current(video)])![0].state).toBe('changed');
    expect(grouped([current(video)], [current(video)])![0].state).toBe('same');
    for (const changed of [{ ...video, widthPixels: { lower: 2, upper: 8192 } }, { ...video, widthAlignmentPixels: 4 }, { ...video, future: false }, { ...video, available: false, status: 'error', widthPixels: null }]) {
      expect(grouped([current(video)], [current(changed)])![0].state).toBe('changed');
    }
  });
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
  it('性能点的旧缺项、覆盖false、原序描述与完整哈希变化参与整行差异', () => {
    const old = { name: 'codec', encoder: false, capabilities: [{ type: 'video/test' }] };
    const performancePoints = { status: 'ok', pointCount: 2, returnedCount: 2, pointsTruncated: false, pointsSha256: 'a', points: [{ description: 'first', coversReferencePoints: { HD_30: true, UHD_60: false } }, { description: 'second', coversReferencePoints: { HD_30: false, UHD_60: false } }] };
    const current = { ...old, capabilities: [{ type: 'video/test', performancePoints }] };
    expect(grouped([old], [current])![0].state).toBe('changed');expect(grouped([current], [current])![0].state).toBe('same');
    const variants: Json[] = [{ ...performancePoints, pointsSha256: 'b' }, { ...performancePoints, points: [...performancePoints.points].reverse() }, { ...performancePoints, points: [{ ...performancePoints.points[0], coversReferencePoints: { HD_30: true, UHD_60: true } }, performancePoints.points[1]] }, { status: 'not-published', points: null }, { status: 'ok', points: [] }];
    for (const changed of variants) {
      expect(grouped([current], [{ ...old, capabilities: [{ type: 'video/test', performancePoints: changed }] }])![0].state).toBe('changed');
    }
  });
});
