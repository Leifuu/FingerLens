import { describe, expect, it } from 'vitest';
import { audioMixerFacts, audioMixerRows } from './audio-mixer-display';
import { audioDisplayRows } from './audio-display';
import type { Json } from './types';

const attribute = { mixerBehavior: 0, sampleRateHz: 48000, encoding: 2, channelMask: 12, channelIndexMask: 0, channelCount: 2 };
const cap = { status: 'ok', attributeCount: 3, returnedCount: 3, attributeLimit: 64, attributesTruncated: false, attributesSha256: 'a', attributes: [attribute, { ...attribute, mixerBehavior: 1, channelMask: 0, channelIndexMask: 5 }, attribute] };
const device = { id: 0, type: 2, supportedMixerAttributes: cap };

describe('音频支持的混音属性对照', () => {
  it('保留原序、重复和六个原值，仅给公开的数值行为添加名称', () => {
    const before = JSON.stringify(cap), facts = audioMixerFacts(cap)!, rows = audioMixerRows(facts, facts);
    expect(facts.status).toBe('成功（ok）');
    expect(facts.count).toBe('总数 3 · 返回 3 · 上限 64 · 截断 false · 明细 3 项');
    expect(rows.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same'], ['2', 'same']]);
    expect(rows[0].left).toEqual(['混音行为 默认（0） · 编码 2', '采样率 48000 Hz · 声道数 2', '声道位置掩码 12', '声道索引掩码 0']);
    expect(rows[1].left![0]).toBe('混音行为 位精确（1） · 编码 2');expect(rows[2].left).toEqual(rows[0].left);
    const unknown = audioMixerRows(audioMixerFacts({ ...cap, attributes: [{ ...attribute, mixerBehavior: -99, channelMask: -2147483648, sampleRateHz: '48000', encoding: '', channelCount: null }] }))[0];
    expect(unknown.left).toEqual(['混音行为 -99 · 编码 （空字符串）', '采样率 "48000" Hz · 声道数 null', '声道位置掩码 -2147483648', '声道索引掩码 0']);
    expect(audioMixerRows(audioMixerFacts({ ...cap, attributes: [{ ...attribute, mixerBehavior: '0' }] }))[0].left![0]).toContain('混音行为 "0"');
    expect(JSON.stringify(cap)).toBe(before);
  });
  it('成功空清单、缺字段、null、服务缺失和异常各自保留', () => {
    const empty = audioMixerFacts({ status: 'ok', attributeCount: 0, attributes: [] })!;
    expect(empty.count).toContain('空列表 []（不能据此判断硬件不支持）');expect(audioMixerRows(empty)).toEqual([]);
    expect(audioMixerFacts({})!.count).toContain('attributes未采集');expect(audioMixerFacts({ attributes: null })!.count).toContain('attributes=null');
    for (const [value, expected] of [[null, '原始值 null'], [false, '原始值 false'], ['', '原始值 （空字符串）']] as const) expect(audioMixerFacts(value)!.status).toBe(expected);
    for (const status of ['unsupported', 'unavailable', 'denied', 'error', 'constructor', '']) {
      const facts = audioMixerFacts({ status, attributes: null, errorClass: 'synthetic-error' })!;
      expect(facts.status).toContain('synthetic-error');expect(facts.count).toContain('attributes=null');
    }
    expect(audioMixerFacts({ status: 'unavailable' })!.status).toBe('服务不可用（unavailable）');
  });
  it('未知字段、原始类型、单侧及完整哈希仍决定差异，已知文字相同也不会漏标', () => {
    const before = JSON.stringify(device);
    for (const next of [{ ...cap, attributesSha256: 'b' }, { ...cap, future: false }, { ...cap, attributes: [{ ...attribute, future: false }, ...cap.attributes.slice(1)] }, { ...cap, attributes: [{ ...attribute, encoding: '2' }, ...cap.attributes.slice(1)] }]) {
      expect(audioDisplayRows([device], [{ ...device, supportedMixerAttributes: next }])![0].state).toBe('changed');
    }
    const a = audioMixerFacts(cap)!, b = audioMixerFacts({ ...cap, attributes: [{ ...attribute, future: false }, { ...attribute, mixerBehavior: '1' }] })!;
    const rows = audioMixerRows(a, b);
    expect(rows.map(row => row.state)).toEqual(['changed', 'changed', 'left-only']);expect(rows[0].right!.at(-1)).toBe('其它字段 1 项 · 见完整字段');
    expect(audioMixerFacts({ ...cap, future: false })!.status).toContain('其它元数据 1 项');
    expect(audioMixerRows(undefined, a)[0]).toMatchObject({ left: undefined, state: 'right-only' });
    expect(audioDisplayRows([{ id: 0, type: 2 }], [device])![0]).toMatchObject({ left: { mixer: undefined }, state: 'changed' });
    expect(audioMixerRows(audioMixerFacts({ ...cap, attributes: [{ ...attribute, channelCount: 2 }] }), audioMixerFacts({ ...cap, attributes: [{ channelCount: 2, channelIndexMask: 0, channelMask: 12, encoding: 2, sampleRateHz: 48000, mixerBehavior: 0 }] }))[0].state).toBe('same');
    expect(JSON.stringify(device)).toBe(before);
  });
  it('最多64明细且保留部分返回原值，异构或超限回退原字段', () => {
    const maximum = { ...cap, status: 'partial', attributeCount: 256, returnedCount: 64, attributesTruncated: true, attributes: Array(64).fill(attribute) };
    const facts = audioMixerFacts(maximum)!;expect(facts.status).toBe('部分返回（partial）');expect(facts.count).toContain('总数 256 · 返回 64 · 上限 64 · 截断 true');expect(audioMixerRows(facts)).toHaveLength(64);
    const invalid: Json[] = [[], { ...cap, status: {} }, { ...cap, attributesSha256: [] }, { ...cap, attributes: 'invalid' }, { ...cap, attributes: Array(65).fill(attribute) }, { ...cap, attributes: [null] }, { ...cap, attributes: [{ ...attribute, sampleRateHz: {} }] }];
    for (const value of invalid) { expect(audioMixerFacts(value)).toBeUndefined();expect(audioDisplayRows([{ ...device, supportedMixerAttributes: value }], undefined)).toBeUndefined(); }
    expect(audioMixerRows(audioMixerFacts({ attributes: [{}] }))[0].left![0]).toBe('混音行为 未采集 · 编码 未采集');
  });
});
