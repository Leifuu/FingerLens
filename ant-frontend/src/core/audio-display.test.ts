import { describe, expect, it } from 'vitest';
import { audioDisplayRows, audioProfileRows } from './audio-display';
import type { Json } from './types';

const profile = { format: 2, sampleRatesHz: [48000, 48000, 0], channelMasks: [12, -2147483648], channelIndexMasks: [], encapsulationType: 999 };
const cap = { status: 'ok', profileCount: 2, returnedCount: 2, profilesTruncated: false, profilesSha256: 'a', profiles: [profile, profile] };
const device = { productName: 'synthetic', id: 0, type: -1, isSource: false, isSink: true, encodings: [2, 2], sampleRates: [48000, 0], channelCounts: [2], audioProfiles: cap };
function grouped(left: Json | undefined, right?: Json) {
  const before = JSON.stringify([left, right]);
  const rows = audioDisplayRows(left, right);
  if (rows) rows.forEach(row => audioProfileRows(row.left, row.right));
  expect(JSON.stringify([left, right])).toBe(before);
  return rows;
}

describe('音频设备关联能力概览', () => {
  it('布局摘要保留状态/原始类型/有符号值，只有数值0和1附公开常量名称', () => {
    const report = (layout: Json) => [{ ...device, speakerLayout: layout }];
    for (const [value, mask] of [[0, '0（CHANNEL_INVALID）'], [1, '1（CHANNEL_OUT_DEFAULT）'], [12, '12'], [-2147483648, '-2147483648'], [2147483647, '2147483647'], ['1', '"1"'], [null, 'null'], [false, 'false']] as const) {
      const row = grouped(report({ status: 'ok', channelMask: value }))![0];
      expect(row.left!.speakerLayout).toEqual({ status: '成功（ok）', mask: `通道掩码 ${mask}` });
    }
    for (const [status, label] of [['not-applicable', '不适用（非内置扬声器）'], ['unsupported', '系统不支持查询'], ['denied', '读取受限'], ['error', '读取异常']] as const) {
      expect(grouped(report({ status, channelMask: null, errorClass: 'synthetic-error' }))![0].left!.speakerLayout).toEqual({ status: `${label}（${status}） · "synthetic-error"`, mask: '通道掩码 null' });
    }
    expect(grouped(report({ status: 'constructor', future: {} }))![0].left!.speakerLayout).toEqual({ status: '查询状态 "constructor" · 其它元数据 1 项，见完整字段', mask: '通道掩码 未采集' });
    expect(grouped(report(null))![0].left!.speakerLayout).toEqual({ status: '原始值 null', mask: '详见完整字段' });
    const row = grouped([device], report({ status: 'ok', channelMask: 1 }))![0];
    expect(row.left!.speakerLayout).toBeUndefined();expect(row.state).toBe('changed');
    const base = { status: 'ok', channelMask: 1, future: false };
    for (const next of [{ ...base, channelMask: '1' }, { ...base, future: 'false' }, { ...base, future: true }]) expect(grouped(report(base), report(next))![0].state).toBe('changed');
    const invalid: Json[] = [[], { channelMask: {} }, { status: [] }, { errorClass: {} }];
    for (const value of invalid) expect(grouped(report(value))).toBeUndefined();
  });
  it('保留重复设备/格式和原始序号，显示有符号掩码、重复采样率和未知封装', () => {
    const rows = grouped([device, device], [device, device])!;
    expect(rows.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same']]);
    expect(rows[0].left).toMatchObject({ name: 'synthetic', identity: 'ID 0 · 类型 -1', roles: '输入 false · 输出 true', aggregate: '编码 [2,2] · 采样率 Hz [48000,0] · 声道数 [2]' });
    const profiles = audioProfileRows(rows[0].left, rows[0].right);
    expect(profiles.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'same']]);
    expect(profiles[0].left).toEqual(['格式 2 · 封装 999', '采样率 Hz [48000,48000,0]', '声道位置掩码 [12,-2147483648]', '声道索引掩码 []']);
  });
  it('兼容旧报告、单侧和空清单，不从聚合数组补写关联格式', () => {
    const { audioProfiles: _, ...old } = device;
    const rows = grouped([old], [device])!;
    expect(rows[0].left).toMatchObject({ profileStatus: '未采集', profiles: undefined });
    expect(rows[0].state).toBe('changed');
    expect(audioProfileRows(rows[0].left, rows[0].right)[0]).toMatchObject({ left: undefined, state: 'right-only' });
    expect(grouped(undefined, [device])![0]).toMatchObject({ left: undefined, state: 'right-only' });
    expect(grouped([device])![0].state).toBe('left-only');
    expect(grouped([], [])).toEqual([]);
    const empty = grouped([{ ...device, audioProfiles: { ...cap, profileCount: 0, returnedCount: 0, profiles: [] } }])![0].left!;
    expect(empty.profileStatus).toBe('成功（ok）');expect(empty.profileCount).toContain('空数组 []');expect(audioProfileRows(empty)).toEqual([]);
  });
  it('明确区分null、缺失、失败、未知状态及成功但部分返回的原始元数据', () => {
    for (const [value, expected] of [[null, '原始值 null'], [false, '原始值 false'], ['', '原始值 （空字符串）']] as const) {
      expect(grouped([{ ...device, audioProfiles: value }])![0].left!.profileStatus).toBe(expected);
    }
    for (const status of ['unsupported', 'denied', 'error', 'constructor', '']) {
      const facts = grouped([{ ...device, audioProfiles: { status, profiles: null, profileCount: null, errorClass: 'synthetic-error' } }])![0].left!;
      expect(facts.profileStatus).toContain('synthetic-error');expect(facts.profileCount).toContain('profiles=null');
    }
    const facts = grouped([{ ...device, audioProfiles: { status: 'partial', profileCount: 65, returnedCount: 64, profilesTruncated: true, profiles: Array(64).fill(profile) } }])![0].left!;
    expect(facts.profileStatus).toBe('部分可观察（partial）');expect(facts.profileCount).toBe('总数 65 · 返回 64 · 截断 true');
    expect(audioProfileRows(facts)).toHaveLength(64);
    expect(grouped([{ ...device, audioProfiles: {} }])![0].left!.profileCount).toContain('profiles未采集');
  });
  it('原始对象/类型/未知字段和完整哈希变化均标差异，不合并相同格式', () => {
    for (const next of [{ ...device, id: '0' }, { ...device, future: false }, { ...device, audioProfiles: { ...cap, future: false } }, { ...device, audioProfiles: { ...cap, profilesSha256: 'b' } }, { ...device, audioProfiles: { ...cap, profiles: [{ ...profile, future: false }, profile] } }]) {
      expect(grouped([device], [next])![0].state).toBe('changed');
    }
    const other = { ...device, isSink: 'true', audioProfiles: { ...cap, profiles: [{ ...profile, format: '2', channelMasks: [-2147483648, 12] }] } };
    const row = grouped([device], [other])![0], profiles = audioProfileRows(row.left, row.right);
    expect(row.right!.roles).toContain('输出 "true"');expect(profiles[0].right![0]).toContain('格式 "2"');
    expect(profiles.map(row => row.state)).toEqual(['changed', 'left-only']);
    const unknown = grouped([device], [{ ...device, audioProfiles: { ...cap, profiles: [{ ...profile, future: false }, profile] } }])![0];
    expect(audioProfileRows(unknown.left, unknown.right)[0].state).toBe('changed');
  });
  it('结构不匹配或超出上限回退原字段，合法边界不丢失', () => {
    const invalid: Json[] = [null, {}, [null], [{}], [{ legacyOnly: 'raw' }], [{ ...device, productName: {} }], Array(257).fill(device), [{ ...device, audioProfiles: [] }], [{ ...device, audioProfiles: { ...cap, profiles: Array(65).fill(profile) } }], [{ ...device, audioProfiles: { ...cap, profiles: [null] } }], [{ ...device, sampleRates: [true, {}] }], [{ ...device, audioProfiles: { ...cap, profiles: [{ ...profile, channelMasks: Array(1025).fill(0) }] } }]];
    for (const value of invalid) expect(grouped(value, [device])).toBeUndefined();
    expect(grouped(Array(256).fill(device))).toHaveLength(256);
    expect(grouped([{ ...device, audioProfiles: { ...cap, profiles: [{ ...profile, sampleRatesHz: Array(1024).fill(0) }] } }])![0].left!.profiles).toHaveLength(1);
    const typed = grouped([{ ...device, audioProfiles: { ...cap, profiles: [{ ...profile, sampleRatesHz: ['48000', null, false] }] } }])![0];
    expect(audioProfileRows(typed.left)[0].left![1]).toBe('采样率 Hz ["48000",null,false]');
  });
});
