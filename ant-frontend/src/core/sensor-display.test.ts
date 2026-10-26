import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { mergeDisplaySections } from './display-compare';
import { sensorDisplayRows, sensorPublicMetadataText } from './sensor-display';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function grouped(left: Json[] | undefined, right?: Json[]) {
  const original = JSON.stringify([left, right]);
  const section = (entries: Json[]) => buildSections(fixtureItem('sensors/list', entries, { category: 'sensors' })).find(section => section.key === 'array');
  const merged = mergeDisplaySections(left === undefined ? undefined : section(left), right === undefined ? undefined : section(right));
  const before = JSON.stringify(merged), rows = sensorDisplayRows(merged.rows, left, right);
  expect(JSON.stringify(merged)).toBe(before);
  expect(JSON.stringify([left, right])).toBe(original);
  return rows;
}

describe('传感器概览', () => {
  const sensor = { name: 'same', vendor: 'vendor', type: 1 };
  const metadata = <T extends Json>(value: T) => ({ ...sensor, publicMetadata: value });
  it('显示原始ID、特殊含义和附加信息布尔值，不从ID推断能力', () => {
    for (const [id, kind, label] of [[0, 'unsupported', '不支持 ID'], [-1, 'type-and-name', '按类型和名称识别'], [-2147483648, 'app-scoped-id', 'App 范围 ID']] as const) {
      expect(sensorPublicMetadataText(metadata({ sensorId: { status: 'ok', value: id, kind }, additionalInfoSupported: { status: 'ok', value: false } }))).toEqual({ sensorId: `${id} · ${label}（${kind}）`, additionalInfo: '不支持（false）' });
    }
    expect(sensorPublicMetadataText(metadata({ additionalInfoSupported: { status: 'ok', value: true } })).additionalInfo).toBe('支持（true）');
    expect(sensorPublicMetadataText(metadata({ sensorId: { status: 'ok', value: 0 } })).sensorId).toBe('0 · 含义 未采集');
  });
  it('旧JSON不回填，空/null/缺项及复杂结构各自保留', () => {
    expect(sensorPublicMetadataText(sensor)).toEqual({ sensorId: '未采集', additionalInfo: '未采集' });
    expect(sensorPublicMetadataText(metadata({}))).toEqual({ sensorId: '未采集', additionalInfo: '未采集' });
    for (const [value, expected] of [[null, '原始值 null'], ['', '原始值 （空字符串）'], [false, '原始值 false'], [[], '原始值 其他结构（详见完整字段）']] as const) {
      expect(sensorPublicMetadataText(metadata(value as Json)).sensorId).toBe(expected);
    }
    expect(sensorPublicMetadataText(metadata({ sensorId: null })).sensorId).toBe('原始值 null');
    expect(sensorPublicMetadataText(metadata({ sensorId: { status: 'ok', value: null, kind: null } })).sensorId).toBe('null · 含义 null');
  });
  it('失败状态和errorClass保留原文，不把失败false值写成不支持', () => {
    const value = metadata({ sensorId: { status: 'denied', value: null, errorClass: 'java.lang.SecurityException' }, additionalInfoSupported: { status: 'error', value: false, errorClass: '' } });
    expect(sensorPublicMetadataText(value)).toEqual({ sensorId: '读取受限（denied） · 原始值 null · "java.lang.SecurityException"', additionalInfo: '读取异常（error） · 原始值 false · （空字符串）' });
    expect(sensorPublicMetadataText(metadata({ sensorId: { status: 'unsupported', value: null } })).sensorId).toBe('不支持查询（unsupported） · 原始值 null');
    expect(sensorPublicMetadataText(metadata({ sensorId: { status: 'constructor', value: 0 } })).sensorId).toBe('读取状态 "constructor" · 原始值 0');
    expect(sensorPublicMetadataText(metadata({ sensorId: { status: '', value: '' } })).sensorId).toBe('读取状态 （空字符串） · 原始值 （空字符串）');
  });
  it('字符串/数字/布尔保持类型差别，未知值不转为平台已知含义', () => {
    expect(sensorPublicMetadataText(metadata({ sensorId: { status: 'ok', value: '0', kind: '__proto__' } })).sensorId).toBe('"0" · 含义 "__proto__"');
    expect(sensorPublicMetadataText(metadata({ additionalInfoSupported: { status: 'ok', value: 'false' } })).additionalInfo).toBe('原始值 "false"');
    expect(sensorPublicMetadataText(metadata({ additionalInfoSupported: { status: 'ok', value: null } })).additionalInfo).toBe('原始值 null');
    expect(sensorPublicMetadataText(metadata({ additionalInfoSupported: { status: 'ok', value: { future: true } } })).additionalInfo).toBe('原始值 其他结构（详见完整字段）');
  });
  it('完整原始对象决定行状态，避免格式化后的false/数字掩盖类型变化', () => {
    const a = metadata({ sensorId: { status: 'ok', value: 0, kind: 'unsupported' }, additionalInfoSupported: { status: 'ok', value: false }, future: false });
    for (const b of [metadata({ ...a.publicMetadata, sensorId: { status: 'ok', value: '0', kind: 'unsupported' } }), metadata({ ...a.publicMetadata, additionalInfoSupported: { status: 'ok', value: 'false' } }), metadata({ ...a.publicMetadata, future: 'false' })]) expect(grouped([a], [b])![0].state).toBe('changed');
    expect(grouped([a], [a])![0].state).toBe('same');
  });
  it('长字符串及嵌套未知值保留到完整字段，格式化不改写输入', () => {
    const a = metadata({ sensorId: { status: 'future', value: '<img onerror="bad()">中文\n\u0000', errorClass: false }, additionalInfoSupported: { status: 'ok', value: true }, future: { list: [null, false, 0] } });
    const original = JSON.stringify(a);
    expect(sensorPublicMetadataText(a).sensorId).toContain('中文\\n\\u0000');
    expect(JSON.stringify(a)).toBe(original);
    expect(grouped([a], [{ ...a, publicMetadata: { ...a.publicMetadata, future: { list: [null, false, 1] } } }])![0].state).toBe('changed');
  });
  it('按原始序号保留重复项，不按名称或类型重新配对', () => {
    const entries = [{ name: 'same', vendor: 'vendor', type: 1 }, { name: 'same', vendor: 'vendor', type: 2 }, { name: 'same', vendor: 'vendor', type: 2 }];
    const rows = grouped(entries, entries)!;
    expect(rows.map(row => [row.key, row.type.left])).toEqual([['0', '1'], ['1', '2'], ['2', '2']]);
    expect(rows.every(row => row.state === 'same')).toBe(true);
    expect(grouped(entries, [entries[1], entries[0], entries[2]])!.map(row => row.state)).toEqual(['changed', 'changed', 'same']);
  });
  it('旧字段、未知类型、空字符串、null、零与单侧数据保留原值', () => {
    const a = { name: '', vendor: null, type: 65599, power: 0, reportingMode: 99, wakeUp: false };
    const row = grouped([a], [a])![0];
    expect(row.name.left).toBe('');expect(row.vendor.left).toBe('null');expect(row.type.left).toBe('65599');
    expect(row.power?.left).toBe('0');expect(row.reportingMode?.left).toBe('99');expect(row.wakeUp?.left).toBe('false');
    expect(row.dynamic).toBeUndefined();expect(row.stringType).toBeUndefined();
    expect(grouped([a])![0].state).toBe('left-only');
    expect(grouped(undefined, [a])![0].state).toBe('right-only');
    expect(grouped([a], [{ ...a, wakeUp: null }])![0].state).toBe('changed');
    expect(grouped([a], [{ ...a, dynamic: false }])![0].state).toBe('changed');
  });
  it('FIFO、直连能力、深层未知字段和单侧字段差异都影响整行', () => {
    const a = { name: 'sensor', vendor: 'vendor', type: 1, fifoMaxEventCount: 0, hardwareBufferDirectChannel: false, future: { entries: [1, 2] } };
    for (const changed of [{ ...a, fifoMaxEventCount: 10 }, { ...a, hardwareBufferDirectChannel: true }, { ...a, future: { entries: [1, 3] } }, { ...a, extra: null }]) {
      expect(grouped([a], [changed])![0].state).toBe('changed');
    }
  });
  it('空数组、混合格式、复杂名称和任一侧缺少必要字段时回退原表', () => {
    const valid = { name: 'sensor', vendor: 'vendor', type: 1 };
    expect(grouped([], [])).toBeUndefined();
    expect(grouped(['legacy'])).toBeUndefined();
    expect(grouped([{ name: 'sensor', type: 1 }])).toBeUndefined();
    expect(grouped([{ name: 'sensor', vendor: 'vendor' }])).toBeUndefined();
    expect(grouped([{ ...valid, name: { raw: 'sensor' } }])).toBeUndefined();
    expect(grouped([valid, null])).toBeUndefined();
    expect(grouped([valid], [{ name: 'sensor', type: 1 }])).toBeUndefined();
  });
});
