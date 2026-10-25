import { describe, expect, it } from 'vitest';
import { buildSections } from '../display';
import { codecDisplayRows } from './codec-display';
import { codecEncoderFacts } from './codec-encoder-display';
import { mergeDisplaySections } from './display-compare';
import { fixtureItem } from './fixtures';
import type { Json } from './types';

function controls(left?: Json[], right?: Json[]) {
  const original = JSON.stringify([left, right]);
  const section = (capabilities: Json[]) => buildSections(fixtureItem('hardware/codecs', [{ name: 'encoder', encoder: true, capabilities }])).find(section => section.key === 'array');
  const merged = mergeDisplaySections(left ? section(left) : undefined, right ? section(right) : undefined);
  const rows = codecDisplayRows(merged.rows)?.[0].encoderControls || [];
  expect(JSON.stringify([left, right])).toBe(original);
  return rows;
}
const support = { available: true, status: 'ok', complexity: { status: 'ok', range: { lower: 0, upper: 8 } }, quality: { status: 'ok', range: { lower: 0, upper: 0 } }, bitrateModes: { CQ: { mode: 0, status: 'ok', supported: true }, CBR_FD: { mode: 3, status: 'unsupported', supported: null } } };
const cap = (encoderCapabilities: Json = support, type: Json = 'audio/test') => ({ type, encoderCapabilities });
const value = (rows: ReturnType<typeof controls>, side: 'left' | 'right', label: string) => codecEncoderFacts(rows[0], side).find(fact => fact.label === label)?.value;

describe('编码器参数概览', () => {
  it('按原始MIME序号保留重复和顺序，未知字段变化参与状态，输入不变', () => {
    const left = [cap(), cap(), cap()], rows = controls(left, [cap(), cap({ ...support, future: false }), cap(support, 'video/changed')]);
    expect(rows.map(row => [row.key, row.state])).toEqual([['0', 'same'], ['1', 'changed'], ['2', 'changed']]);
    expect(value(rows, 'left', '复杂度')).toBe('0 – 8');expect(value(rows, 'left', '质量')).toBe('0 – 0');expect(value(rows, 'left', 'CQ (0)')).toBe('支持');expect(value(rows, 'left', 'CBR_FD (3)')).toBe('未支持查询');
  });
  it('区分未采集、显式null、成功false、空字符串、查询异常及未知状态', () => {
    const missing = controls([{ type: 'audio/test' }], [cap()]);
    expect(missing[0].state).toBe('right-only');expect(value(missing, 'left', '编码能力')).toBe('未采集');
    const changed = controls([cap({ ...support, quality: null, bitrateModes: null }, '')], [cap({ ...support, quality: { status: 'error', range: null }, bitrateModes: { CQ: { mode: 0, status: 'ok', supported: false }, CBR_FD: { mode: 3, status: 'ok', supported: null } } }, null)]);
    expect(changed[0].mime?.left).toBe('');expect(changed[0].mime?.right).toBe('null');
    expect(value(changed, 'left', '质量')).toBe('null');expect(value(changed, 'left', '码率模式')).toBe('null');expect(value(changed, 'right', '质量')).toBe('查询失败');expect(value(changed, 'right', 'CQ (0)')).toBe('不支持');expect(value(changed, 'right', 'CBR_FD (3)')).toBe('null');
    const future = controls([cap({ ...support, status: '__proto__', complexity: { status: 'future', range: { lower: '', upper: 8 } } })]);
    expect(value(future, 'left', '编码能力')).toBe('__proto__');expect(value(future, 'left', '复杂度')).toBe('future · （空字符串） – 8');
    const nested = controls([cap({ ...support, quality: { future: true }, bitrateModes: { future: { flag: true } } })]);
    expect(value(nested, 'left', '质量')).toBe('见完整字段');expect(value(nested, 'left', '码率模式')).toBe('见完整字段');
  });
  it('不进行数值转换，保留精确大整数并覆盖单侧和非编码器状态', () => {
    const exact = { ...support, complexity: { status: 'ok', range: { lower: { $type: 'int64', value: '9007199254740992' }, upper: { $type: 'int64', value: '9007199254740993' } } } };
    const rows = controls([cap(exact)], [cap({ available: false, status: 'not-encoder', complexity: null, quality: null, bitrateModes: null })]);
    expect(value(rows, 'left', '复杂度')).toBe('9007199254740992 – 9007199254740993');expect(value(rows, 'right', '编码能力')).toBe('非编码器');expect(rows[0].state).toBe('changed');
    expect(controls(undefined, [cap()])[0].state).toBe('right-only');
  });
  it('旧JSON、异构结构和单纯非编码器回退完整字段；长重复MIME清单不去重', () => {
    const absent = { type: 'audio/test' }, notEncoder = cap({ available: false, status: 'not-encoder', complexity: null, quality: null, bitrateModes: null });
    for (const left of [[absent], [cap(null)], [cap('legacy')], [cap({ ...support, available: 99 })], [cap({ ...support, available: false })], [cap(support, { future: true })], [notEncoder]]) expect(controls(left)).toEqual([]);
    expect(controls([cap(null)], [cap()])).toEqual([]);
    expect(controls(Array.from({ length: 7 }, () => cap()))).toHaveLength(7);
    const modes = Object.fromEntries(Array.from({ length: 12 }, (_, mode) => [`M${mode}`, { mode, status: 'ok', supported: false }]));
    const long = controls([cap({ ...support, bitrateModes: modes })]);
    expect(codecEncoderFacts(long[0], 'left')).toHaveLength(12);expect(value(long, 'left', '其他码率模式')).toBe('4 项 · 见完整字段');
    const mixed = controls([cap({ ...support, bitrateModes: modes })], [cap()]);
    expect(value(mixed, 'left', '其他码率模式')).toBe('4 项 · 见完整字段');expect(value(mixed, 'right', '其他码率模式')).toBe('2 项 · 见完整字段');
    const duplicate = controls([cap({ ...support, bitrateModes: { 复杂度: { status: 'ok', supported: true } } })]);
    const facts = codecEncoderFacts(duplicate[0], 'left');expect(new Set(facts.map(fact => fact.key)).size).toBe(facts.length);
  });
});
